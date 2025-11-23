package com.practice.lottery.service;

import com.practice.lottery.dao.entity.LotteryEvent;
import com.practice.lottery.dao.entity.LotteryPrize;
import com.practice.lottery.dao.entity.UserLotteryQuota;
import com.practice.lottery.dao.repository.LotteryEventRepository;
import com.practice.lottery.dao.repository.LotteryPrizeRepository;
import com.practice.lottery.dao.repository.UserLotteryQuotaRepository;
import com.practice.lottery.exception.LotteryException;
import com.practice.lottery.exception.NoEntryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryService {
    private final LotteryEventRepository lotteryEventRepository;
    private final LotteryPrizeRepository lotteryPrizeRepository;
    private final RedissonClient redissonClient;
    private final UserLotteryQuotaRepository userLotteryQuotaRepository;
    private final WinRecordService winRecordService;
    private final LotterySyncService lotterySyncService;

    // Redis key templates
    private static final String EVENT_REMAIN_KEY = "lottery:%d:remainAmount";
    private static final String USER_CHANCE_KEY = "lottery:%d:user:%d:chance";
    private static final String PRIZE_STOCK_KEY = "lottery:%d:prize:%s:stock";
    private static final String PRIZE_RATE_KEY = "lottery:%d:prize:rate";
    private static final String EVENT_ACTIVE_KEY = "lottery:%d:isActive";

    /**
     * Initialize lottery event prize stock and total draw count
     */
    public void initPrizeStock(Long lotteryEventId) {
        LotteryEvent event = lotteryEventRepository.findById(lotteryEventId)
                .orElseThrow(() -> new IllegalStateException("Lottery event not found"));

        // Initialize event remaining draw count
        RAtomicLong eventRemain = redissonClient.getAtomicLong(
                String.format(EVENT_REMAIN_KEY, lotteryEventId)
        );
        eventRemain.set(event.getRemainAmount());

        // Initialize prize stock and rate
        List<LotteryPrize> prizeList = lotteryPrizeRepository.findByLotteryEventId(lotteryEventId);

        RMap<String, BigDecimal> rateMap = redissonClient.getMap(
                String.format(PRIZE_RATE_KEY, lotteryEventId)
        );
        rateMap.clear();

        for (LotteryPrize prize : prizeList) {
            if (prize.getAmount() > 0) {
                // Store each prize stock as RAtomicLong
                String stockKey = String.format(PRIZE_STOCK_KEY, lotteryEventId, prize.getName());
                RAtomicLong prizeStock = redissonClient.getAtomicLong(stockKey);
                prizeStock.set(prize.getAmount());

                rateMap.put(prize.getName(), prize.getRate());
            }
        }

        log.info("Initialized prize stock for lottery event {}", lotteryEventId);
    }

    /**
     * Main lottery draw method using Redis atomic operations (Lock-Free)
     *
     * @param lotteryEventId 活動ID
     * @param userId 用戶ID
     * @param isKeepResult 是否保存中獎記錄
     * @return 中獎獎品名稱
     */
    public String drawRedis(Long lotteryEventId, Long userId, Boolean isKeepResult) {
        try {
            // Step 1: Validate event is active
            validateEventActive(lotteryEventId);

            // Step 2: Check and decrement quota (atomic operations, order matters!)
            checkAndDecrementQuota(lotteryEventId, userId);

            // Step 3: Load prize data
            PrizeData prizeData = loadPrizeData(lotteryEventId);

            // Step 4: Execute lottery selection logic
            String selectedPrize = selectPrize(prizeData);

            // Step 5: Decrement prize stock and save result
            if (!"Miss".equals(selectedPrize)) {
                selectedPrize = decrementPrizeStock(lotteryEventId, selectedPrize);

                if (!"Miss".equals(selectedPrize) && Boolean.TRUE.equals(isKeepResult)) {
                    saveWinRecordAsync(lotteryEventId, userId, selectedPrize);
                }
            }

            log.info("Lottery draw completed - eventId: {}, userId: {}, result: {}",
                    lotteryEventId, userId, selectedPrize);

            return selectedPrize;

        }  catch (LotteryException e) {
            log.error("Lottery business error - eventId: {}, userId: {}, message: {}",
                    lotteryEventId, userId, e.getMessage());
            lotterySyncService.emergencySyncAfterError(lotteryEventId,userId,e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected lottery error - eventId: {}, userId: {}",
                    lotteryEventId, userId, e);
            lotterySyncService.emergencySyncAfterError(lotteryEventId,userId,e);
            throw new LotteryException("System error, please try again later");
        }
    }

    /**
     * Check and decrement quota using Redisson atomic operations
     * CRITICAL: Must decrement in correct order (event first, then user) for rollback safety
     */
    private void checkAndDecrementQuota(Long lotteryEventId, Long userId) {
        String eventKey = String.format(EVENT_REMAIN_KEY, lotteryEventId);
        String userKey = String.format(USER_CHANCE_KEY, lotteryEventId, userId);

        // Get or initialize event remaining count
        RAtomicLong eventRemain = redissonClient.getAtomicLong(eventKey);
        if (!eventRemain.isExists()) {
            initEventRemain(lotteryEventId, eventRemain);
        }

        // Get or initialize user chance count
        RAtomicLong userChance = redissonClient.getAtomicLong(userKey);
        if (!userChance.isExists()) {
            initUserChance(lotteryEventId, userId, userChance);
        }

        // Atomically decrement event count first
        long eventAfter = eventRemain.decrementAndGet();
        if (eventAfter < 0) {
            eventRemain.incrementAndGet(); // Rollback
            throw new LotteryException("Lottery event ended, insufficient remaining draws");
        }

        // Then atomically decrement user count
        long userAfter = userChance.decrementAndGet();
        if (userAfter < 0) {
            userChance.incrementAndGet(); // Rollback user
            eventRemain.incrementAndGet(); // Rollback event
            throw new LotteryException("User has insufficient remaining draws");
        }
    }

    /**
     * Initialize event remaining draw count from database
     */
    private void initEventRemain(Long lotteryEventId, RAtomicLong eventRemain) {
        LotteryEvent event = lotteryEventRepository.findById(lotteryEventId)
                .orElseThrow(() -> new LotteryException("Lottery event not found, eventId=" + lotteryEventId));

        int remain = Optional.ofNullable(event.getRemainAmount()).orElse(0);
        if (remain <= 0) {
            throw new LotteryException("Lottery event ended, insufficient remaining draws");
        }

        // Use compareAndSet to avoid race condition during initialization
        eventRemain.compareAndSet(0, remain);
    }

    /**
     * Initialize user lottery chance from database
     */
    private void initUserChance(Long lotteryEventId, Long userId, RAtomicLong userChance) {
        UserLotteryQuota quota = userLotteryQuotaRepository
                .findByUidAndLotteryEventId(userId, lotteryEventId)
                .orElseThrow(() -> new NoEntryException("User lottery quota not found"));

        Integer drawQuota = Optional.ofNullable(quota.getDrawQuota()).orElse(0);
        if (drawQuota <= 0) {
            throw new LotteryException("User has insufficient remaining draws");
        }

        // Use compareAndSet to avoid race condition during initialization
        userChance.compareAndSet(0, drawQuota.longValue());
    }

    /**
     * Load prize data from Redis, fallback to database if not exists
     */
    private PrizeData loadPrizeData(Long lotteryEventId) {
        RMap<String, BigDecimal> rateMap = redissonClient.getMap(
                String.format(PRIZE_RATE_KEY, lotteryEventId)
        );

        // Load from database if Redis cache is empty
        if (rateMap.isEmpty()) {
            synchronized (this) { // Double-check locking for initialization
                if (rateMap.isEmpty()) {
                    initializePrizeDataFromDatabase(lotteryEventId, rateMap);
                }
            }
        }

        return new PrizeData(lotteryEventId, rateMap);
    }

    /**
     * Initialize prize data from database
     */
    private void initializePrizeDataFromDatabase(Long lotteryEventId, RMap<String, BigDecimal> rateMap) {
        List<LotteryPrize> prizeList = lotteryPrizeRepository.findByLotteryEventId(lotteryEventId);
        if (prizeList.isEmpty()) {
            throw new LotteryException("Lottery prizes not found");
        }

        for (LotteryPrize prize : prizeList) {
            // Initialize each prize stock as RAtomicLong
            String stockKey = String.format(PRIZE_STOCK_KEY, lotteryEventId, prize.getName());
            RAtomicLong prizeStock = redissonClient.getAtomicLong(stockKey);
            if (!prizeStock.isExists()) {
                prizeStock.set(prize.getAmount());
            }

            rateMap.put(prize.getName(), Optional.ofNullable(prize.getRate()).orElse(BigDecimal.ZERO));
        }

        log.info("Initialized prize data from database for event {}", lotteryEventId);
    }

    /**
     * Select prize using cumulative probability algorithm
     * Returns "Miss" if no prizes available or luck runs out
     */
    private String selectPrize(PrizeData prizeData) {
        // Filter prizes with stock greater than 0
        List<String> availablePrizes = prizeData.rateMap().keySet().stream()
                .filter(prizeName -> hasPrizeStock(prizeData.eventId(), prizeName))
                .toList();

        if (availablePrizes.isEmpty()) {
            return "Miss";
        }

        // Build cumulative probability map (0 to total rate)
        BigDecimal cumulative = BigDecimal.ZERO;
        Map<String, BigDecimal> cumulativeMap = new LinkedHashMap<>();

        for (String prize : availablePrizes) {
            BigDecimal rate = prizeData.rateMap().getOrDefault(prize, BigDecimal.ZERO);
            cumulative = cumulative.add(rate);
            cumulativeMap.put(prize, cumulative);
        }

        // Add "Miss" to cover remaining probability space (total rate to 1.0)
        BigDecimal totalPrizeRate = cumulative;
        if (totalPrizeRate.compareTo(BigDecimal.ONE) < 0) {
            cumulativeMap.put("Miss", BigDecimal.ONE);
        }

        // Random selection: generate random value between 0 and 1
        BigDecimal random = BigDecimal.valueOf(Math.random());

        for (Map.Entry<String, BigDecimal> entry : cumulativeMap.entrySet()) {
            if (random.compareTo(entry.getValue()) <= 0) {
                return entry.getKey();
            }
        }

        // Fallback (should never reach here with proper probability setup)
        return "Miss";
    }

    /**
     * Check if prize has available stock
     */
    private boolean hasPrizeStock(Long eventId, String prizeName) {
        String stockKey = String.format(PRIZE_STOCK_KEY, eventId, prizeName);
        RAtomicLong prizeStock = redissonClient.getAtomicLong(stockKey);
        return prizeStock.isExists() && prizeStock.get() > 0;
    }

    /**
     * Atomically decrement prize stock using RAtomicLong
     * Returns "Miss" if stock insufficient
     */
    private String decrementPrizeStock(Long lotteryEventId, String prizeName) {
        String stockKey = String.format(PRIZE_STOCK_KEY, lotteryEventId, prizeName);
        RAtomicLong prizeStock = redissonClient.getAtomicLong(stockKey);

        // Atomically decrement stock
        long newStock = prizeStock.decrementAndGet();

        if (newStock < 0) {
            // Insufficient stock, rollback
            prizeStock.incrementAndGet();
            log.warn("Prize stock insufficient - eventId: {}, prize: {}", lotteryEventId, prizeName);
            return "Miss";
        }

        return prizeName;
    }

    /**
     * Asynchronously save win record
     */
    private void saveWinRecordAsync(Long lotteryEventId, Long userId, String prizeName) {
        winRecordService.saveWinRecordAsync(lotteryEventId, userId, prizeName)
                .exceptionally(ex -> {
                    log.error("Failed to save win record - eventId: {}, userId: {}, prize: {}",
                            lotteryEventId, userId, prizeName, ex);
                    // Could trigger alert or retry mechanism here
                    return null;
                });
    }

    /**
     * Validate event is active before drawing
     * Throws exception if event is inactive
     */
    public void validateEventActive(Long lotteryEventId) {
        if (!isEventActive(lotteryEventId)) {
            throw new LotteryException("Lottery event is not active");
        }
    }

    /**
     * Check if lottery event is active
     * Initialize from database if not exists in Redis
     */
    public boolean isEventActive(Long lotteryEventId) {
        String key = String.format(EVENT_ACTIVE_KEY, lotteryEventId);
        RBucket<Boolean> activeStatus = redissonClient.getBucket(key);

        // Return if exists in Redis
        Boolean isActive = activeStatus.get();
        if (isActive != null) {
            return isActive;
        }

        // Initialize from database with double-check
        synchronized (this) {
            isActive = activeStatus.get();
            if (isActive != null) {
                return isActive;
            }

            LotteryEvent event = lotteryEventRepository.findById(lotteryEventId)
                    .orElseThrow(() -> new LotteryException("Lottery event not found"));

            activeStatus.set(event.getIsActive());
            return event.getIsActive();
        }
    }

    /**
     * Internal data structure for prize information
     */
    private record PrizeData(
            Long eventId,
            RMap<String, BigDecimal> rateMap
    ) {}
}