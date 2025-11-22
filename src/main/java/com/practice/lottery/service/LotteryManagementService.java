package com.practice.lottery.service;

import com.practice.lottery.controller.request.LotteryUpdateRequest;
import com.practice.lottery.controller.request.UpdatePrizeRateRequest;
import com.practice.lottery.controller.response.LotteryEventResponse;
import com.practice.lottery.controller.response.LotteryStatusResponse;
import com.practice.lottery.dao.entity.LotteryEvent;
import com.practice.lottery.dao.entity.LotteryPrize;
import com.practice.lottery.dao.repository.LotteryEventRepository;
import com.practice.lottery.dao.repository.LotteryPrizeRepository;
import com.practice.lottery.exception.LotteryException;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LotteryManagementService {
    private final LotteryEventRepository lotteryEventRepository;
    private final LotteryPrizeRepository lotteryPrizeRepository;
    private final RedissonClient redissonClient;

    private static final String EVENT_ACTIVE_KEY = "lottery:%d:isActive";
    private static final String PRIZE_RATE_KEY = "lottery:%d:prize:rate";
    private static final String PRIZE_STOCK_KEY = "lottery:%d:prize:%s:stock";
    private static final String EVENT_REMAIN_KEY = "lottery:%d:remainAmount";

    /**
     * Update lottery event settings and prize rates
     * Synchronizes all changes to Redis
     */
    @Transactional
    public void updateLotteryRates(LotteryUpdateRequest request) {
        Long eventId = request.getEventId();

        // Step 1: Validate event exists
        LotteryEvent event = lotteryEventRepository.findById(eventId)
                .orElseThrow(() -> new LotteryException("Lottery event not found"));

        // Step 2: Update active status if provided
        if (request.getIsActive() != null) {
            event.setIsActive(request.getIsActive());
            // Sync to Redis
            updateEventActiveStatus(eventId, request.getIsActive());
        }

        if(request.getRemainAmount() !=null){
            event.setRemainAmount(request.getRemainAmount());
            updateRemainAmount(eventId,request.getRemainAmount());
        }
        lotteryEventRepository.save(event);

        // Step 3: Update prize rates if provided
        if (request.getRateUpdateList() != null && !request.getRateUpdateList().isEmpty()) {
            updatePrizeRates(eventId, request.getRateUpdateList());
        }
    }

    /**
     * Update prize rates in both database and Redis
     */
    private void updatePrizeRates(Long eventId, java.util.List<UpdatePrizeRateRequest> rateUpdateList) {
        String rateKey = String.format(PRIZE_RATE_KEY, eventId);
        RMap<String, BigDecimal> rateMap = redissonClient.getMap(rateKey);

        for (UpdatePrizeRateRequest updatePrizeRateRequest : rateUpdateList) {
            // Validate decimal precision
            if (updatePrizeRateRequest.getRate().scale() > 2) {
                throw new LotteryException(
                        "Rate must have at most 2 decimal places: " + updatePrizeRateRequest.getRate().toPlainString()
                );
            }

            // Step 1: Update database
            LotteryPrize prize = lotteryPrizeRepository.findById(updatePrizeRateRequest.getId())
                    .orElseThrow(() -> new LotteryException("Prize not found: " + updatePrizeRateRequest.getId()));

            prize.setRate(updatePrizeRateRequest.getRate());
            lotteryPrizeRepository.save(prize);

            // Step 2: Update Redis rate map
            rateMap.put(prize.getName(), updatePrizeRateRequest.getRate());
        }

        // Step 3: Validate total rate <= 1.0
        BigDecimal totalRate = getTotalPrizeRateByEventId(eventId);
        if (totalRate.compareTo(BigDecimal.ONE) > 0) {
            throw new LotteryException("Total prize rate exceeds 1.0: " + totalRate.toPlainString());
        }
    }

    /**
     * Initialize or refresh all lottery data in Redis
     * Useful for admin operations or system startup
     */
    @Transactional(readOnly = true)
    public void refreshLotteryCache(Long eventId) {
        // Step 1: Load event
        LotteryEvent event = lotteryEventRepository.findById(eventId)
                .orElseThrow(() -> new LotteryException("Lottery event not found"));

        // Step 2: Refresh active status
        updateEventActiveStatus(eventId, event.getIsActive());

        // Step 3: Refresh event remain amount
        String eventRemainKey = String.format("lottery:%d:remainAmount", eventId);
        RAtomicLong eventRemain = redissonClient.getAtomicLong(eventRemainKey);
        eventRemain.set(event.getRemainAmount());

        // Step 4: Refresh prize rates and stocks
        String rateKey = String.format(PRIZE_RATE_KEY, eventId);
        RMap<String, BigDecimal> rateMap = redissonClient.getMap(rateKey);
        rateMap.clear();

        java.util.List<LotteryPrize> prizes = lotteryPrizeRepository.findByLotteryEventId(eventId);
        for (LotteryPrize prize : prizes) {
            // Update rate
            rateMap.put(prize.getName(), prize.getRate());

            // Update stock
            String stockKey = String.format(PRIZE_STOCK_KEY, eventId, prize.getName());
            RAtomicLong prizeStock = redissonClient.getAtomicLong(stockKey);
            prizeStock.set(prize.getAmount());
        }
    }

    /**
     * Update single prize rate (alternative method)
     */
    @Transactional
    public void updateSinglePrizeRate(Long eventId, Long prizeId, BigDecimal newRate) {
        // Validate precision
        if (newRate.scale() > 2) {
            throw new LotteryException("Rate must have at most 2 decimal places");
        }

        // Validate range
        if (newRate.compareTo(BigDecimal.ZERO) < 0 || newRate.compareTo(BigDecimal.ONE) > 0) {
            throw new LotteryException("Rate must be between 0.0 and 1.0");
        }

        // Update database
        LotteryPrize prize = lotteryPrizeRepository.findById(prizeId)
                .orElseThrow(() -> new LotteryException("Prize not found"));

        if (!prize.getLotteryEventId().equals(eventId)) {
            throw new LotteryException("Prize does not belong to this event");
        }

        prize.setRate(newRate);
        lotteryPrizeRepository.save(prize);

        // Update Redis
        String rateKey = String.format(PRIZE_RATE_KEY, eventId);
        RMap<String, BigDecimal> rateMap = redissonClient.getMap(rateKey);
        rateMap.put(prize.getName(), newRate);

        // Validate total rate
        BigDecimal totalRate = getTotalPrizeRateByEventId(eventId);
        if (totalRate.compareTo(BigDecimal.ONE) > 0) {
            throw new LotteryException("Total prize rate exceeds 1.0: " + totalRate.toPlainString());
        }
    }

    /**
     * Calculate total prize rate from database
     */
    private BigDecimal getTotalPrizeRateByEventId(Long eventId) {
        return lotteryPrizeRepository.findByLotteryEventId(eventId)
                .stream()
                .map(LotteryPrize::getRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Clear all Redis cache for a lottery event
     * Use with caution - mainly for testing or emergency fixes
     */
    public void clearLotteryCache(Long eventId) {
        // Clear active status
        String activeKey = String.format("lottery:%d:isActive", eventId);
        redissonClient.getBucket(activeKey).delete();

        // Clear event remain
        String eventRemainKey = String.format("lottery:%d:remainAmount", eventId);
        redissonClient.getAtomicLong(eventRemainKey).delete();

        // Clear rate map
        String rateKey = String.format(PRIZE_RATE_KEY, eventId);
        redissonClient.getMap(rateKey).delete();

        // Clear prize stocks
        java.util.List<LotteryPrize> prizes = lotteryPrizeRepository.findByLotteryEventId(eventId);
        for (LotteryPrize prize : prizes) {
            String stockKey = String.format(PRIZE_STOCK_KEY, eventId, prize.getName());
            redissonClient.getAtomicLong(stockKey).delete();
        }
    }


    /**
     * Update event active status in Redis
     * Called when admin changes event status
     */
    public void updateEventActiveStatus(Long lotteryEventId, Boolean isActive) {
        String key = String.format(EVENT_ACTIVE_KEY, lotteryEventId);
        RBucket<Boolean> activeStatus = redissonClient.getBucket(key);
        activeStatus.set(isActive);
    }


    /**
     * Update  event remain amount in Redis
     * Called when admin changes event remain amount
     */
    public void updateRemainAmount(Long lotteryEventId, Integer  remainAmount) {
        String key = String.format(EVENT_REMAIN_KEY, lotteryEventId);
        RAtomicLong eventRemain = redissonClient.getAtomicLong(
                key
        );
        eventRemain.set(Long.valueOf(remainAmount));
    }


    /**
     * Get lottery event status with all prize rates and stocks
     * Fetches from Redis if available, fallback to database
     */
    @Transactional(readOnly = true)
    public LotteryStatusResponse getLotteryStatus(Long eventId) {
        // Step 1: Load event from database
        LotteryEvent event = lotteryEventRepository.findById(eventId)
                .orElseThrow(() -> new LotteryException("Lottery event not found"));

        // Step 2: Get active status from Redis or database
        Boolean isActive = getEventActiveStatus(eventId, event.getIsActive());

        // Step 3: Get event remain amount from Redis or database
        Long remainAmount = getEventRemainAmount(eventId, event.getRemainAmount());

        // Step 4: Load all prizes
        List<LotteryPrize> prizes = lotteryPrizeRepository.findByLotteryEventId(eventId);

        // Step 5: Build prize info list with Redis data
        String rateKey = String.format(PRIZE_RATE_KEY, eventId);
        RMap<String, BigDecimal> rateMap = redissonClient.getMap(rateKey);

        List<LotteryStatusResponse.PrizeInfo> prizeInfoList = prizes.stream()
                .map(prize -> {
                    // Get rate from Redis, fallback to database
                    BigDecimal rate = rateMap.getOrDefault(prize.getName(), prize.getRate());

                    // Get stock from Redis, fallback to database
                    String stockKey = String.format(PRIZE_STOCK_KEY, eventId, prize.getName());
                    RAtomicLong prizeStock = redissonClient.getAtomicLong(stockKey);
                    Long stock = prizeStock.isExists() ? prizeStock.get() : (long) prize.getAmount();

                    return LotteryStatusResponse.PrizeInfo.builder()
                            .prizeId(prize.getId())
                            .prizeName(prize.getName())
                            .rate(rate)
                            .stock(stock)
                            .build();
                })
                .toList();

        // Step 6: Calculate total rate
        BigDecimal totalRate = prizeInfoList.stream()
                .map(LotteryStatusResponse.PrizeInfo::getRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Step 7: Build response
        return LotteryStatusResponse.builder()
                .eventId(event.getId())
                .eventName(event.getName())
                .isActive(isActive)
                .remainAmount(remainAmount)
                .totalRate(totalRate)
                .prizes(prizeInfoList)
                .build();
    }

    @Transactional(readOnly = true)
    public List<LotteryEventResponse> getAllEvents() {
        List<LotteryEvent> events = lotteryEventRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        return events.stream()
                .map(this::convertToEventResponse)
                .collect(Collectors.toList());
    }


    /**
     * Get event active status, prefer Redis
     */
    private Boolean getEventActiveStatus(Long eventId, Boolean dbValue) {
        String activeKey = String.format("lottery:%d:isActive", eventId);
        RBucket<Boolean> bucket = redissonClient.getBucket(activeKey);
        Boolean redisValue = bucket.get();
        return redisValue != null ? redisValue : dbValue;
    }

    /**
     * Get event remain amount, prefer Redis
     */
    private Long getEventRemainAmount(Long eventId, Integer dbValue) {
        String remainKey = String.format("lottery:%d:remainAmount", eventId);
        RAtomicLong remain = redissonClient.getAtomicLong(remainKey);
        return remain.isExists() ? remain.get() : dbValue.longValue();
    }

    private LotteryEventResponse convertToEventResponse(LotteryEvent event) {


        return LotteryEventResponse.builder()
                .id(event.getId())
                .name(event.getName())
                .remainAmount(getEventRemainAmount(event.getId(),event.getRemainAmount()).intValue())
                .isActive(event.getIsActive())
                .settingAmount(event.getSettingAmount())
                .createdTime(event.getCreatedTime())
                .updatedTime(event.getUpdatedTime())
                .build();
    }


}