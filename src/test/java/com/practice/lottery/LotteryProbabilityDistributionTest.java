package com.practice.lottery;

import com.practice.lottery.dao.entity.LotteryEvent;
import com.practice.lottery.dao.entity.LotteryPrize;
import com.practice.lottery.dao.repository.LotteryEventRepository;
import com.practice.lottery.dao.repository.LotteryPrizeRepository;
import com.practice.lottery.dao.repository.UserLotteryQuotaRepository;
import com.practice.lottery.service.LotteryService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 抽獎系統機率分布測試
 * 使用三個核心統計方法驗證機率準確性：
 * 1. 基本機率分布檢驗
 * 2. 卡方適合度檢定 (Chi-Square Test)
 * 3. 信賴區間驗證 (Confidence Interval)
 */
@Slf4j
@SpringBootTest
public class LotteryProbabilityDistributionTest {

    @Autowired
    private LotteryService lotteryService;

    @Autowired
    private LotteryEventRepository lotteryEventRepository;

    @Autowired
    private LotteryPrizeRepository lotteryPrizeRepository;

    @Autowired
    private UserLotteryQuotaRepository userLotteryQuotaRepository;

    @Autowired
    private RedissonClient redissonClient;

    private  Long TEST_EVENT_ID = 888L;
    private static final Long TEST_USER_ID = 10L;

    // 三種獎品的標準設定
    private static final String FIRST_PRIZE = "FirstPrize";
    private static final String SECOND_PRIZE = "SecondPrize";
    private static final String THIRD_PRIZE = "ThirdPrize";
    private static final String MISS = "Miss";

    @BeforeEach
    public void setup() {
        cleanupTestData();
    }

    @AfterEach
    public void cleanup() {
        cleanupTestData();
    }

    /**
     * Test 1: 基本機率分布測試
     * 驗證實際中獎率與設定機率的偏差是否在可接受範圍內
     */
    @Test
    public void testBasicProbabilityDistribution() {
        log.info("\n" + "=".repeat(70));
        log.info("Test 1: Basic Probability Distribution Test");
        log.info("=".repeat(70));

        // 設定三種獎品的機率
        Map<String, BigDecimal> expectedRates = createStandardPrizeRates();

        log.info("\nExpected Probabilities:");
        expectedRates.forEach((prize, rate) ->
                log.info("  {}: {}%", prize, rate.multiply(BigDecimal.valueOf(100)))
        );

        // 創建測試活動
        createTestEventWithPrizes(expectedRates, 10000);

        // 執行 10,000 次抽獎
        int totalDraws = 10000;
        setUserQuota(TEST_USER_ID, totalDraws);
        lotteryService.initPrizeStock(TEST_EVENT_ID);

        Map<String, Integer> actualCounts = executeDraw(totalDraws);
        Map<String, BigDecimal> actualRates = calculateActualRates(actualCounts, totalDraws);

        // 打印結果報告
        printProbabilityReport(expectedRates, actualRates, totalDraws);

        // 驗證：使用 ±2% 容差
        verifyProbabilityDistribution(expectedRates, actualRates, 0.02);

        log.info("\n✓ Test 1 PASSED: Probability distribution matches expected values");
        log.info("=".repeat(70) + "\n");
    }

    /**
     * Test 2: 卡方適合度檢定 (Chi-Square Goodness of Fit Test)
     * 使用統計學方法驗證機率分布是否符合預期
     * <p>
     * 原理：
     * χ² = Σ [(觀察值 - 期望值)² / 期望值]
     * 如果 χ² < 臨界值，則接受假設（分布符合預期）
     */
    @Test
    public void testChiSquareGoodnessOfFit() {
        log.info("\n" + "=".repeat(70));
        log.info("Test 2: Chi-Square Goodness of Fit Test");
        log.info("=".repeat(70));

        Map<String, BigDecimal> expectedRates = createStandardPrizeRates();

        createTestEventWithPrizes(expectedRates, 5000);

        int totalDraws = 5000;
        setUserQuota(TEST_USER_ID, totalDraws);
        lotteryService.initPrizeStock(TEST_EVENT_ID);

        Map<String, Integer> actualCounts = executeDraw(totalDraws);

        // 計算卡方統計量
        double chiSquare = calculateChiSquare(expectedRates, actualCounts, totalDraws);

        // 自由度 = 類別數 - 1 = 4 - 1 = 3
        int degreesOfFreedom = 3;

        // α = 0.05 的臨界值
        double criticalValue = 7.815; // df=3, α=0.05

        log.info("\nChi-Square Test Results:");
        log.info("  Chi-square statistic: {:.4f}", chiSquare);
        log.info("  Degrees of freedom: {}", degreesOfFreedom);
        log.info("  Critical value (α=0.05): {:.4f}", criticalValue);
        log.info("  Decision: {}", chiSquare < criticalValue ?
                "✓ Accept H₀ (distribution fits)" : "✗ Reject H₀ (distribution does not fit)");

        // 詳細計算過程
        log.info("\nDetailed Calculation:");
        for (Map.Entry<String, BigDecimal> entry : expectedRates.entrySet()) {
            String prize = entry.getKey();
            double expected = entry.getValue().doubleValue() * totalDraws;
            int observed = actualCounts.getOrDefault(prize, 0);
            double contribution = Math.pow(observed - expected, 2) / expected;

            log.info("  {}: Observed={}, Expected={:.1f}, Contribution={:.4f}",
                    prize, observed, expected, contribution);
        }

        // Miss 的計算
        double expectedMissRate = 1.0 - expectedRates.values().stream()
                .mapToDouble(BigDecimal::doubleValue).sum();
        double expectedMissCount = expectedMissRate * totalDraws;
        int observedMiss = actualCounts.getOrDefault(MISS, 0);
        double missContribution = Math.pow(observedMiss - expectedMissCount, 2) / expectedMissCount;
        log.info("  {}: Observed={}, Expected={:.1f}, Contribution={:.4f}",
                MISS, observedMiss, expectedMissCount, missContribution);

        // 驗證卡方統計量小於臨界值
        assertThat(chiSquare)
                .as("Chi-square statistic should be less than critical value")
                .isLessThan(criticalValue);

        log.info("\n✓ Test 2 PASSED: Distribution passes chi-square test");
        log.info("=".repeat(70) + "\n");
    }

    /**
     * Test 3: 信賴區間驗證 (Confidence Interval Test)
     * 驗證實際機率是否落在 95% 信賴區間內
     * <p>
     * 原理：
     * CI = p ± z × SE
     * where SE = √[p(1-p)/n]
     * z = 1.96 for 95% confidence
     */
    @Test
    public void testConfidenceIntervals() {
        log.info("\n" + "=".repeat(70));
        log.info("Test 3: 95% Confidence Interval Test");
        log.info("=".repeat(70));

        Map<String, BigDecimal> expectedRates = createStandardPrizeRates();

        createTestEventWithPrizes(expectedRates, 10000);

        int totalDraws = 10000;
        setUserQuota(TEST_USER_ID, totalDraws);
        lotteryService.initPrizeStock(TEST_EVENT_ID);

        Map<String, Integer> actualCounts = executeDraw(totalDraws);

        log.info("\nConfidence Interval Analysis (95% CI):");
        log.info(String.format("\n%-15s %10s %12s %20s %10s",
                "Prize", "Expected", "Actual", "95% CI", "Status"));
        log.info("-".repeat(75));

        boolean allWithinCI = true;

        // 檢查三種獎品
        for (Map.Entry<String, BigDecimal> entry : expectedRates.entrySet()) {
            String prize = entry.getKey();
            double expectedRate = entry.getValue().doubleValue();
            int actualCount = actualCounts.getOrDefault(prize, 0);
            double actualRate = (double) actualCount / totalDraws;

            // 計算 95% 信賴區間
            double[] ci = calculateConfidenceInterval(expectedRate, totalDraws, 0.95);
            boolean withinCI = actualRate >= ci[0] && actualRate <= ci[1];

            log.info(String.format("%-15s %9.2f%% %11.2f%% [%5.2f%%, %5.2f%%] %10s",
                    prize,
                    expectedRate * 100,
                    actualRate * 100,
                    ci[0] * 100,
                    ci[1] * 100,
                    withinCI ? "✓ PASS" : "✗ FAIL"
            ));

            // 顯示計算細節
            double se = Math.sqrt((expectedRate * (1 - expectedRate)) / totalDraws);
            log.info("    Standard Error: {:.6f}, Margin: ±{:.4f}%", se, (1.96 * se * 100));

            if (!withinCI) {
                allWithinCI = false;
            }
        }

        // 檢查 Miss 機率
        int missCount = actualCounts.getOrDefault(MISS, 0);
        double missRate = (double) missCount / totalDraws;
        double expectedMissRate = 1.0 - expectedRates.values().stream()
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
        double[] missCI = calculateConfidenceInterval(expectedMissRate, totalDraws, 0.95);
        boolean missWithinCI = missRate >= missCI[0] && missRate <= missCI[1];

        log.info(String.format("%-15s %9.2f%% %11.2f%% [%5.2f%%, %5.2f%%] %10s",
                MISS,
                expectedMissRate * 100,
                missRate * 100,
                missCI[0] * 100,
                missCI[1] * 100,
                missWithinCI ? "✓ PASS" : "✗ FAIL"
        ));

        double missSE = Math.sqrt((expectedMissRate * (1 - expectedMissRate)) / totalDraws);
        log.info("    Standard Error: {:.6f}, Margin: ±{:.4f}%", missSE, (1.96 * missSE * 100));

        // 驗證所有機率都在信賴區間內
        assertThat(allWithinCI && missWithinCI)
                .as("All probabilities should be within 95% confidence intervals")
                .isTrue();

        log.info("\n✓ Test 3 PASSED: All probabilities within 95% confidence intervals");
        log.info("=".repeat(70) + "\n");
    }

    /**
     * Bonus Test: 並發場景下的機率分布測試
     * 驗證高並發不會影響機率準確性
     */
    @Test
    public void testProbabilityDistributionUnderConcurrency() throws Exception {
        log.info("\n" + "=".repeat(70));
        log.info("Bonus Test: Probability Distribution Under Concurrency");
        log.info("=".repeat(70));

        Map<String, BigDecimal> expectedRates = createStandardPrizeRates();

        createTestEventWithPrizes(expectedRates, 1000);

        int totalDraws = 1000;
        int threadCount = 100;
        int drawsPerThread = totalDraws / threadCount;

        setUserQuota(TEST_USER_ID, totalDraws);
        lotteryService.initPrizeStock(TEST_EVENT_ID);

        log.info("\nConcurrency Settings:");
        log.info("  Total draws: {}", totalDraws);
        log.info("  Concurrent threads: {}", threadCount);
        log.info("  Draws per thread: {}", drawsPerThread);

        // 執行並發抽獎
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        ConcurrentHashMap<String, Integer> actualCounts = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(totalDraws);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < drawsPerThread; j++) {
                    try {
                        String result = lotteryService.drawRedis(TEST_EVENT_ID, TEST_USER_ID, false);
                        actualCounts.merge(result, 1, Integer::sum);
                    } catch (Exception e) {
                        log.error("Draw failed", e);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        long duration = System.currentTimeMillis() - startTime;
        log.info("\nExecution Time: {} ms", duration);
        log.info("Throughput: {} draws/second", totalDraws * 1000.0 / duration);

        Map<String, BigDecimal> actualRates = calculateActualRates(actualCounts, totalDraws);

        printProbabilityReport(expectedRates, actualRates, totalDraws);

        verifyProbabilityDistribution(expectedRates, actualRates, 0.03);

        log.info("\n✓ Bonus Test PASSED: Probability accurate under concurrency");
        log.info("=".repeat(70) + "\n");
    }

    // ========== Helper Methods ==========

    /**
     * 創建標準的三種獎品機率設定
     */
    private Map<String, BigDecimal> createStandardPrizeRates() {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        rates.put(FIRST_PRIZE, new BigDecimal("0.05"));   // 5% - 一等獎
        rates.put(SECOND_PRIZE, new BigDecimal("0.15"));  // 15% - 二等獎
        rates.put(THIRD_PRIZE, new BigDecimal("0.30"));   // 30% - 三等獎
        // Miss = 50%
        return rates;
    }

    /**
     * 創建測試活動和獎品
     */
    private void createTestEventWithPrizes(Map<String, BigDecimal> prizeRates, int prizeStock) {
        LotteryEvent event = new LotteryEvent();

        event.setName("Probability Test Event");
        event.setIsActive(true);
        event.setSettingAmount(50000);
        event.setRemainAmount(50000);
        event = lotteryEventRepository.saveAndFlush(event);
        TEST_EVENT_ID = event.getId();
        for (Map.Entry<String, BigDecimal> entry : prizeRates.entrySet()) {
            LotteryPrize prize = new LotteryPrize();
            prize.setLotteryEventId(TEST_EVENT_ID);
            prize.setName(entry.getKey());
            prize.setRate(entry.getValue());
            prize.setAmount(prizeStock);
            lotteryPrizeRepository.saveAndFlush(prize);
        }
    }

    /**
     * 設定用戶抽獎配額
     */
    private void setUserQuota(Long userId, int quota) {
        var userQuota = new com.practice.lottery.dao.entity.UserLotteryQuota();
        userQuota.setUid(userId.intValue());
        userQuota.setLotteryEventId(TEST_EVENT_ID);
        userQuota.setDrawQuota(quota);
        userLotteryQuotaRepository.saveAndFlush(userQuota);
    }

    /**
     * 執行抽獎 - 使用並行處理加速
     */
    private Map<String, Integer> executeDraw(int totalDraws) {
        // 使用並行執行加速測試
        int parallelThreads = Math.min(Runtime.getRuntime().availableProcessors(), 10);
        int drawsPerThread = totalDraws / parallelThreads;
        int remainingDraws = totalDraws % parallelThreads;

        log.info("Executing {} draws using {} parallel threads ({} draws per thread)",
                totalDraws, parallelThreads, drawsPerThread);

        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);
        ConcurrentHashMap<String, Integer> results = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(parallelThreads);

        long startTime = System.currentTimeMillis();

        // 提交並行任務
        for (int i = 0; i < parallelThreads; i++) {
            final int threadDraws = drawsPerThread + (i == 0 ? remainingDraws : 0);
            executor.submit(() -> {
                try {
                    for (int j = 0; j < threadDraws; j++) {
                        String prize = lotteryService.drawRedis(TEST_EVENT_ID, TEST_USER_ID, false);
                        results.merge(prize, 1, Integer::sum);
                    }
                } catch (Exception e) {
                    log.error("Thread draw failed", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error("Draw execution interrupted", e);
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Completed {} draws in {} ms ({} draws/sec)",
                totalDraws, duration, String.format("%.2f", totalDraws * 1000.0 / duration));

        return new HashMap<>(results);
    }

    /**
     * 計算實際機率
     */
    private Map<String, BigDecimal> calculateActualRates(Map<String, Integer> counts, int total) {
        Map<String, BigDecimal> rates = new HashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            BigDecimal rate = BigDecimal.valueOf(entry.getValue())
                    .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
            rates.put(entry.getKey(), rate);
        }
        return rates;
    }

    /**
     * 打印機率分布報告
     */
    private void printProbabilityReport(Map<String, BigDecimal> expectedRates,
                                        Map<String, BigDecimal> actualRates,
                                        int totalDraws) {
        log.info("\n=== Probability Distribution Report ===");
        log.info("Total draws: {}", totalDraws);
        log.info(String.format("\n%-15s %12s %12s %12s %10s",
                "Prize", "Expected", "Actual", "Difference", "Status"));
        log.info("-".repeat(65));

        for (Map.Entry<String, BigDecimal> entry : expectedRates.entrySet()) {
            String prize = entry.getKey();
            BigDecimal expected = entry.getValue();
            BigDecimal actual = actualRates.getOrDefault(prize, BigDecimal.ZERO);
            BigDecimal diff = actual.subtract(expected);
            String status = Math.abs(diff.doubleValue()) < 0.02 ? "✓" : "⚠";

            log.info(String.format("%-15s %11.2f%% %11.2f%% %11.2f%% %10s",
                    prize,
                    expected.doubleValue() * 100,
                    actual.doubleValue() * 100,
                    diff.doubleValue() * 100,
                    status
            ));
        }

        // Miss rate
        BigDecimal expectedMissRate = BigDecimal.ONE.subtract(
                expectedRates.values().stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
        );
        BigDecimal actualMissRate = actualRates.getOrDefault(MISS, BigDecimal.ZERO);
        BigDecimal missDiff = actualMissRate.subtract(expectedMissRate);
        String missStatus = Math.abs(missDiff.doubleValue()) < 0.02 ? "✓" : "⚠";

        log.info(String.format("%-15s %11.2f%% %11.2f%% %11.2f%% %10s",
                MISS,
                expectedMissRate.doubleValue() * 100,
                actualMissRate.doubleValue() * 100,
                missDiff.doubleValue() * 100,
                missStatus
        ));
        log.info("-".repeat(65));
    }

    /**
     * 驗證機率分布
     */
    private void verifyProbabilityDistribution(Map<String, BigDecimal> expectedRates,
                                               Map<String, BigDecimal> actualRates,
                                               double tolerance) {
        for (Map.Entry<String, BigDecimal> entry : expectedRates.entrySet()) {
            String prize = entry.getKey();
            double expected = entry.getValue().doubleValue();
            double actual = actualRates.getOrDefault(prize, BigDecimal.ZERO).doubleValue();

            assertThat(actual)
                    .as("Prize: %s - Actual rate should be within %.1f%% of expected",
                            prize, tolerance * 100)
                    .isCloseTo(expected, within(tolerance));
        }
    }

    /**
     * 計算卡方統計量
     */
    private double calculateChiSquare(Map<String, BigDecimal> expectedRates,
                                      Map<String, Integer> actualCounts,
                                      int totalDraws) {
        double chiSquare = 0.0;

        // 三種獎品的卡方計算
        for (Map.Entry<String, BigDecimal> entry : expectedRates.entrySet()) {
            String prize = entry.getKey();
            double expectedCount = entry.getValue().doubleValue() * totalDraws;
            int actualCount = actualCounts.getOrDefault(prize, 0);

            chiSquare += Math.pow(actualCount - expectedCount, 2) / expectedCount;
        }

        // Miss 的卡方計算
        double expectedMissRate = 1.0 - expectedRates.values().stream()
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
        double expectedMissCount = expectedMissRate * totalDraws;
        int actualMissCount = actualCounts.getOrDefault(MISS, 0);

        chiSquare += Math.pow(actualMissCount - expectedMissCount, 2) / expectedMissCount;

        return chiSquare;
    }

    /**
     * 計算信賴區間
     */
    private double[] calculateConfidenceInterval(double proportion, int sampleSize, double confidence) {
        // 使用常態近似計算信賴區間
        double z = 1.96; // for 95% confidence

        double standardError = Math.sqrt((proportion * (1 - proportion)) / sampleSize);
        double margin = z * standardError;

        return new double[]{
                Math.max(0, proportion - margin),
                Math.min(1, proportion + margin)
        };
    }

    /**
     * 清理測試數據
     */
    private void cleanupTestData() {
        try {
            redissonClient.getKeys().deleteByPattern("lottery:" + TEST_EVENT_ID + ":*");

            userLotteryQuotaRepository.findAll().stream()
                    .filter(q -> q.getLotteryEventId().equals(TEST_EVENT_ID))
                    .forEach(q -> userLotteryQuotaRepository.deleteById(q.getId()));

            lotteryPrizeRepository.findByLotteryEventId(TEST_EVENT_ID)
                    .forEach(p -> lotteryPrizeRepository.deleteById(p.getId()));

            if (lotteryEventRepository.existsById(TEST_EVENT_ID)) {
                lotteryEventRepository.deleteById(TEST_EVENT_ID);
            }

            lotteryEventRepository.flush();
        } catch (Exception e) {
            log.warn("Cleanup failed", e);
        }
    }
}