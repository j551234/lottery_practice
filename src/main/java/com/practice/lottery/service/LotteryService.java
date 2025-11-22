package com.practice.lottery.service;

import com.practice.lottery.dao.entity.LotteryEvent;
import com.practice.lottery.dao.entity.LotteryPrize;
import com.practice.lottery.dao.entity.UserLotteryQuota;
import com.practice.lottery.dao.repository.LotteryEventRepository;
import com.practice.lottery.dao.repository.LotteryPrizeRepository;
import com.practice.lottery.dao.repository.UserLotteryQuotaRepository;
import com.practice.lottery.exception.LotteryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryService {
    private final LotteryEventRepository lotteryEventRepository;
    private final LotteryPrizeRepository lotteryPrizeRepository;
    private final RedissonClient redissonClient;
    private final UserLotteryQuotaRepository userLotteryQuotaRepository;
    private final LotterySyncService lotterySyncService;
    private final WinRecordService winRecordService;

    // Redis key templates
    private static final String EVENT_REMAIN_KEY = "lottery:%d:remainAmount";
    private static final String USER_CHANCE_KEY = "lottery:%d:user:%d:chance";
    private static final String PRIZE_STOCK_KEY = "lottery:%d:prize:%s:stock";  // Changed to per-prize key
    private static final String PRIZE_RATE_KEY = "lottery:%d:prize:rate";
    private static final String LOCK_KEY = "lottery:%d:lock";
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

        // Initialize prize stock (using RAtomicLong per prize) and rate
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
    }

    public String drawRedis(Long lotteryEventId, Long userId) {
        RLock lock = redissonClient.getLock(String.format(LOCK_KEY, lotteryEventId));

        try {
                if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                    throw new LotteryException("System busy, please try again later");
            }

            // Check if event is active
            validateEventActive(lotteryEventId);

            // Step 1: Check and decrement event and user quota (atomic operations)
            checkAndDecrementQuota(lotteryEventId, userId);

            // Step 2: Load prize data
            PrizeData prizeData = loadPrizeData(lotteryEventId);

            // Step 3: Execute lottery selection logic
            String selectedPrize = selectPrize(prizeData);

            // Step 4: Decrement prize stock
            if (!"Miss".equals(selectedPrize)) {
                selectedPrize = decrementPrizeStock(lotteryEventId, selectedPrize);
                if (!"Miss".equals(selectedPrize)) {
                    winRecordService.saveWinRecordAsync(lotteryEventId, userId, selectedPrize)
                            .exceptionally(ex -> {
                                log.error("Failed to save win record for user {} in event {}",
                                        userId, lotteryEventId, ex);
                                // Could trigger alert or retry mechanism here
                                return null;
                            });
                }

            }

            return selectedPrize;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            // Emergency sync on error
            lotterySyncService.emergencySyncAfterError(lotteryEventId, userId, e);

            throw new LotteryException("System busy, please try again later");

        } catch (Exception e) {
            log.error("Lottery draw error for eventId: {}, userId: {}", lotteryEventId, userId, e);

            // Emergency sync on error
            lotterySyncService.emergencySyncAfterError(lotteryEventId, userId, e);

            throw e;

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Check and decrement quota using Redisson atomic operations
     */
    private void checkAndDecrementQuota(Long lotteryEventId, Long userId) {
        String eventKey = String.format(EVENT_REMAIN_KEY, lotteryEventId);
        String userKey = String.format(USER_CHANCE_KEY, lotteryEventId, userId);

        // Initialize event remaining count if not exists
        RAtomicLong eventRemain = redissonClient.getAtomicLong(eventKey);
        if (!eventRemain.isExists()) {
            initEventRemain(lotteryEventId, eventRemain);
        }

        // Initialize user chance count if not exists
        RAtomicLong userChance = redissonClient.getAtomicLong(userKey);
        if (!userChance.isExists()) {
            initUserChance(lotteryEventId, userId, userChance);
        }

        // Atomically decrement event count
        long eventAfter = eventRemain.decrementAndGet();
        if (eventAfter < 0) {
            eventRemain.incrementAndGet(); // Rollback
            throw new LotteryException("Lottery event ended, insufficient remaining draws");
        }

        // Atomically decrement user count
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
        eventRemain.set(remain);
    }

    /**
     * Initialize user lottery chance from database
     */
    private void initUserChance(Long lotteryEventId, Long userId, RAtomicLong userChance) {
        UserLotteryQuota quota = userLotteryQuotaRepository
                .findByUidAndLotteryEventId(userId, lotteryEventId)
                .orElseThrow(() -> new LotteryException("User lottery quota not found"));

        Integer drawQuota = Optional.ofNullable(quota.getDrawQuota()).orElse(0);
        if (drawQuota <= 0) {
            throw new LotteryException("User has insufficient remaining draws");
        }
        userChance.set(drawQuota.longValue());
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
            List<LotteryPrize> prizeList = lotteryPrizeRepository.findByLotteryEventId(lotteryEventId);
            if (prizeList.isEmpty()) {
                throw new LotteryException("Lottery prizes not found");
            }

            rateMap.clear();

            for (LotteryPrize prize : prizeList) {
                // Initialize each prize stock as RAtomicLong
                String stockKey = String.format(PRIZE_STOCK_KEY, lotteryEventId, prize.getName());
                RAtomicLong prizeStock = redissonClient.getAtomicLong(stockKey);
                if (!prizeStock.isExists()) {
                    prizeStock.set(prize.getAmount());
                }

                rateMap.put(prize.getName(), Optional.ofNullable(prize.getRate()).orElse(BigDecimal.ZERO));
            }
        }

        return new PrizeData(lotteryEventId, rateMap);
    }

    /**
     * Select prize using cumulative probability algorithm
     */
    private String selectPrize(PrizeData prizeData) {
        // Filter prizes with stock greater than 0
        List<String> availablePrizes = prizeData.rateMap().keySet().stream()
                .filter(prizeName -> {
                    String stockKey = String.format(PRIZE_STOCK_KEY, prizeData.eventId(), prizeName);
                    RAtomicLong prizeStock = redissonClient.getAtomicLong(stockKey);
                    return prizeStock.isExists() && prizeStock.get() > 0;
                })
                .toList();

        if (availablePrizes.isEmpty()) {
            return "Miss";
        }

        // Build cumulative probability map
        BigDecimal cumulative = BigDecimal.ZERO;
        Map<String, BigDecimal> cumulativeMap = new LinkedHashMap<>();

        for (String prize : availablePrizes) {
            BigDecimal rate = prizeData.rateMap().getOrDefault(prize, BigDecimal.ZERO);
            cumulative = cumulative.add(rate);
            cumulativeMap.put(prize, cumulative);
        }

        // Random selection based on cumulative probability
        BigDecimal random = BigDecimal.valueOf(Math.random());
        for (Map.Entry<String, BigDecimal> entry : cumulativeMap.entrySet()) {
            if (random.compareTo(entry.getValue()) <= 0) {
                return entry.getKey();
            }
        }

        return "Miss";
    }

    /**
     * Atomically decrement prize stock using RAtomicLong
     */
    private String decrementPrizeStock(Long lotteryEventId, String prizeName) {
        String stockKey = String.format(PRIZE_STOCK_KEY, lotteryEventId, prizeName);
        RAtomicLong prizeStock = redissonClient.getAtomicLong(stockKey);

        // Atomically decrement stock
        long newStock = prizeStock.decrementAndGet();

        if (newStock < 0) {
            // Insufficient stock, rollback
            prizeStock.incrementAndGet();
            return "Miss";
        }

        return prizeName;
    }


    /**
     * Validate event is active before drawing
     * Throws exception if event is inactive
     */
    public void validateEventActive(Long lotteryEventId) {
        if (!isEventActive(lotteryEventId)) {
            throw new LotteryException("Lottery event is not active");
        }

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

        // Initialize from database
        LotteryEvent event = lotteryEventRepository.findById(lotteryEventId)
                .orElseThrow(() -> new LotteryException("Lottery event not found"));

        activeStatus.set(event.getIsActive());
        return event.getIsActive();
    }




    /**
     * Internal data structure for prize information
     */
    private record PrizeData(
            Long eventId,
            RMap<String, BigDecimal> rateMap
    ) {}
}

