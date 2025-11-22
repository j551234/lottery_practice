package com.practice.lottery.service;
import com.practice.lottery.controller.response.WinRecordResponse;
import com.practice.lottery.dao.entity.LotteryEvent;
import com.practice.lottery.dao.entity.LotteryPrize;
import com.practice.lottery.dao.entity.WinRecord;
import com.practice.lottery.dao.repository.LotteryEventRepository;
import com.practice.lottery.dao.repository.LotteryPrizeRepository;
import com.practice.lottery.dao.repository.WinRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class WinRecordService {
    private final WinRecordRepository winRecordRepository;
    private final LotteryPrizeRepository lotteryPrizeRepository;
    private final LotteryEventRepository lotteryEventRepository;
    private final RedissonClient redissonClient;

    private static final String PRIZE_STOCK_KEY = "lottery:%d:prize:%s:stock";

    /**
     * Asynchronously save win record to database
     * Uses new transaction to avoid blocking main lottery flow
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompletableFuture<WinRecord> saveWinRecordAsync(
            Long eventId,
            Long userId,
            String prizeName
    ) {
        try {
            log.info("Async saving win record: eventId={}, userId={}, prize={}",
                    eventId, userId, prizeName);

            // Get prize info
            LotteryPrize prize = lotteryPrizeRepository
                    .findByLotteryEventIdAndName(eventId, prizeName)
                    .orElseThrow(() -> new IllegalArgumentException("Prize not found: " + prizeName));

            // Get current stock from Redis
            String stockKey = String.format(PRIZE_STOCK_KEY, eventId, prizeName);
            RAtomicLong prizeStock = redissonClient.getAtomicLong(stockKey);
            Integer remainStock = prizeStock.isExists() ?
                    Math.toIntExact(prizeStock.get()) : prize.getAmount();

            // Create win record
            WinRecord record = WinRecord.builder()
                    .lotteryEventId(eventId)
                    .uid(userId)
                    .drawPrizeId(prize.getId())
                    .remainPrizeAmount(remainStock)
                    .createdTime(LocalDateTime.now())
                    .build();

            WinRecord saved = winRecordRepository.save(record);

            log.info("Win record saved successfully: recordId={}", saved.getId());

            return CompletableFuture.completedFuture(saved);

        } catch (Exception e) {
            log.error("Failed to save win record asynchronously", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Get all win records for a user
     */
    @Transactional(readOnly = true)
    public List<WinRecordResponse> getUserWinRecords(Long userId) {
        List<WinRecord> records = winRecordRepository.findByUid(userId);
        return convertToResponseList(records);
    }

    /**
     * Convert WinRecord to WinRecordResponse
     */
    private WinRecordResponse convertToResponse(WinRecord record) {
        // Fetch prize details
        LotteryPrize prize = lotteryPrizeRepository.findById(record.getDrawPrizeId())
                .orElse(null);

        // Fetch event details
        LotteryEvent event = lotteryEventRepository.findById(record.getLotteryEventId())
                .orElse(null);

        return WinRecordResponse.builder()
                .id(record.getId())
                .lotteryEventId(record.getLotteryEventId())
                .eventName(event != null ? event.getName() : "Unknown Event")
                .uid(record.getUid())
                .drawPrizeId(record.getDrawPrizeId())
                .prizeName(prize != null ? prize.getName() : "Unknown Prize")
                .remainPrizeAmount(record.getRemainPrizeAmount())
                .createdTime(record.getCreatedTime())
                .build();
    }

    /**
     * Convert list of WinRecord to list of WinRecordResponse
     */
    private List<WinRecordResponse> convertToResponseList(List<WinRecord> records) {
        return records.stream()
                .map(this::convertToResponse)
                .toList();
    }

}
