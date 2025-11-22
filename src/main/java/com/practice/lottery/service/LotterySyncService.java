package com.practice.lottery.service;

import com.practice.lottery.dao.entity.LotteryEvent;
import com.practice.lottery.dao.entity.LotteryPrize;
import com.practice.lottery.dao.entity.UserLotteryQuota;
import com.practice.lottery.dao.repository.LotteryEventRepository;
import com.practice.lottery.dao.repository.LotteryPrizeRepository;
import com.practice.lottery.dao.repository.UserLotteryQuotaRepository;
import com.practice.lottery.exception.LotteryException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LotterySyncService {
    private final RedissonClient redissonClient;
    private final LotteryEventRepository lotteryEventRepository;
    private final LotteryPrizeRepository lotteryPrizeRepository;
    private final UserLotteryQuotaRepository userLotteryQuotaRepository;

    private static final String EVENT_REMAIN_KEY = "lottery:%d:remainAmount";
    private static final String USER_CHANCE_KEY = "lottery:%d:user:%d:chance";
    private static final String PRIZE_STOCK_KEY = "lottery:%d:prize:%s:stock";

    /**
     * Sync all lottery data from Redis back to database
     * Used when lottery error occurs or for scheduled backup
     */
    @Transactional
    public SyncResult syncLotteryDataToDatabase(Long eventId) {
        log.info("Starting lottery data sync for eventId: {}", eventId);

        SyncResult result = new SyncResult();
        result.setEventId(eventId);

        try {
            // Step 1: Sync event remain amount
            syncEventRemainAmount(eventId, result);

            // Step 2: Sync prize stocks
            syncPrizeStocks(eventId, result);

            // Step 3: Sync user quotas (if needed)
            // Note: This is optional as user quotas are usually synced individually

            result.setSuccess(true);
            log.info("Lottery data sync completed successfully for eventId: {}", eventId);

        } catch (Exception e) {
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            log.error("Lottery data sync failed for eventId: {}", eventId, e);
            throw new LotteryException("Sync failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Sync event remain amount from Redis to database
     */
    private void syncEventRemainAmount(Long eventId, SyncResult result) {
        String eventKey = String.format(EVENT_REMAIN_KEY, eventId);
        RAtomicLong eventRemain = redissonClient.getAtomicLong(eventKey);

        if (!eventRemain.isExists()) {
            log.warn("Event remain key not found in Redis: {}", eventKey);
            return;
        }

        long redisRemain = eventRemain.get();

        LotteryEvent event = lotteryEventRepository.findById(eventId)
                .orElseThrow(() -> new LotteryException("Event not found: " + eventId));

        int dbRemain = event.getRemainAmount();

        if (redisRemain != dbRemain) {
            event.setRemainAmount((int) redisRemain);
            lotteryEventRepository.save(event);

            result.setEventRemainSynced(true);
            result.setEventRemainBefore(dbRemain);
            result.setEventRemainAfter((int) redisRemain);

            log.info("Event remain amount synced: {} -> {}", dbRemain, redisRemain);
        }
    }

    /**
     * Sync all prize stocks from Redis to database
     */
    private void syncPrizeStocks(Long eventId, SyncResult result) {
        List<LotteryPrize> prizes = lotteryPrizeRepository.findByLotteryEventId(eventId);
        List<PrizeStockChange> changes = new ArrayList<>();

        for (LotteryPrize prize : prizes) {
            String stockKey = String.format(PRIZE_STOCK_KEY, eventId, prize.getName());
            RAtomicLong prizeStock = redissonClient.getAtomicLong(stockKey);

            if (!prizeStock.isExists()) {
                log.warn("Prize stock key not found in Redis: {}", stockKey);
                continue;
            }

            long redisStock = prizeStock.get();
            int dbStock = prize.getAmount();

            if (redisStock != dbStock) {
                prize.setAmount((int) redisStock);
                lotteryPrizeRepository.save(prize);

                PrizeStockChange change = new PrizeStockChange();
                change.setPrizeId(prize.getId());
                change.setPrizeName(prize.getName());
                change.setStockBefore(dbStock);
                change.setStockAfter((int) redisStock);
                changes.add(change);

                log.info("Prize stock synced: {} ({} -> {})",
                        prize.getName(), dbStock, redisStock);
            }
        }

        result.setPrizeStockChanges(changes);
    }

    /**
     * Sync single user quota from Redis to database
     */
    @Async
    @Transactional
    public void syncUserQuota(Long eventId, Long userId) {
        String userKey = String.format(USER_CHANCE_KEY, eventId, userId);
        RAtomicLong userChance = redissonClient.getAtomicLong(userKey);

        if (!userChance.isExists()) {
            log.warn("User chance key not found in Redis: {}", userKey);
            return;
        }

        long redisChance = userChance.get();

        UserLotteryQuota quota = userLotteryQuotaRepository
                .findByUidAndLotteryEventId(userId, eventId)
                .orElseThrow(() -> new LotteryException("User quota not found"));

        int dbChance = quota.getDrawQuota();

        if (redisChance != dbChance) {
            quota.setDrawQuota((int) redisChance);
            userLotteryQuotaRepository.save(quota);

            log.info("User quota synced: userId={}, eventId={}, {} -> {}",
                    userId, eventId, dbChance, redisChance);
        }
    }

    /**
     * Emergency sync - sync all data for event when error occurs
     */
    @Transactional
    public void emergencySyncAfterError(Long eventId, Long userId, Exception error) {
        log.error("Emergency sync triggered for eventId: {}, userId: {}, error: {}",
                eventId, userId, error.getMessage());

        try {
            // Sync event and prizes
            syncLotteryDataToDatabase(eventId);

            // Sync user quota
            if (userId != null) {
                syncUserQuota(eventId, userId);
            }

            log.info("Emergency sync completed successfully");

        } catch (Exception syncError) {
            log.error("Emergency sync failed", syncError);
            // Don't throw exception to avoid cascading failures
        }
    }


    // ========== Result DTOs ==========

    @Data
    public static class SyncResult {
        private Long eventId;
        private Boolean success;
        private String errorMessage;

        private Boolean eventRemainSynced = false;
        private Integer eventRemainBefore;
        private Integer eventRemainAfter;

        private List<PrizeStockChange> prizeStockChanges = new ArrayList<>();
    }

    @Data
    public static class PrizeStockChange {
        private Long prizeId;
        private String prizeName;
        private Integer stockBefore;
        private Integer stockAfter;
    }


}


