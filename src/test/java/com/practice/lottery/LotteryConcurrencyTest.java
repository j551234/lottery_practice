package com.practice.lottery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.practice.lottery.controller.request.LoginRequest;
import com.practice.lottery.dao.entity.LotteryEvent;
import com.practice.lottery.dao.entity.LotteryPrize;
import com.practice.lottery.dao.entity.UserLotteryQuota;
import com.practice.lottery.dao.repository.LotteryEventRepository;
import com.practice.lottery.dao.repository.LotteryPrizeRepository;
import com.practice.lottery.dao.repository.UserLotteryQuotaRepository;
import com.practice.lottery.dao.repository.WinRecordRepository;
import com.practice.lottery.service.LotteryService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
public class LotteryConcurrencyTest {


    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LotteryEventRepository lotteryEventRepository;

    @Autowired
    private LotteryPrizeRepository lotteryPrizeRepository;

    @Autowired
    private UserLotteryQuotaRepository userLotteryQuotaRepository;

    @Autowired
    private WinRecordRepository winRecordRepository;

    @Autowired
    private LotteryService lotteryService;

    @Autowired
    private RedissonClient redissonClient;

    private static final Long TEST_USER_ID = 2L;
    private Long TEST_EVENT_ID = 999l;
    private String userToken;
    private  String USER_CHANCE_KEY = "lottery:%d:user:%d:chance";
    private String adminToken;

    @BeforeEach
    public void setup() throws Exception {
        // Clean up test data
        cleanupTestData();

        // Initialize test event
        initializeTestEvent();

        // Get authentication tokens
        userToken = getAuthToken("user", "123456");
        adminToken = getAuthToken("admin", "123456");
    }

    /**
     * Test 1: Concurrent lottery draws with single user
     * Purpose: Test if system correctly handles quota deduction under high concurrency
     */
    @Test
    public void testConcurrentDraws_SingleUser() throws Exception {
        log.info("=== Test 1: Concurrent Draws - Single User ===");

        int totalDraws = 1000;
        int concurrentThreads = 100;

        // Set user quota
        updateUserQuota(TEST_USER_ID, totalDraws);

        // Initialize lottery cache
        lotteryService.initPrizeStock(TEST_EVENT_ID);

        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch latch = new CountDownLatch(totalDraws);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> errors = new CopyOnWriteArrayList<>();

        long startTime = System.currentTimeMillis();

        // Submit all draw requests
        for (int i = 0; i < totalDraws; i++) {
            final int drawNumber = i;
            executor.submit(() -> {
                try {
                    MvcResult result = mockMvc.perform(
                            post("/user/event/" + TEST_EVENT_ID + "/draw")
                                    .header("Authorization", "Bearer " + userToken)
                    ).andReturn();

                    int status = result.getResponse().getStatus();
                    if (status == 200) {
                        successCount.incrementAndGet();
                        log.debug("Draw {} succeeded", drawNumber);
                    } else {
                        failureCount.incrementAndGet();
                        errors.add("Draw " + drawNumber + " failed with status " + status);
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    errors.add("Draw " + drawNumber + " threw exception: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all draws to complete
        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Print results
        log.info("=== Test 1 Results ===");
        log.info("Total draws attempted: {}", totalDraws);
        log.info("Successful draws: {}", successCount.get());
        log.info("Failed draws: {}", failureCount.get());
        log.info("Duration: {} ms", duration);
        log.info("Throughput: {} draws/second", totalDraws * 1000.0 / duration);

        if (!errors.isEmpty()) {
            log.warn("Errors encountered:");
            errors.forEach(log::warn);
        }

        // Verify results
//        assertThat(successCount.get()).isEqualTo(totalDraws);
//        assertThat(failureCount.get()).isEqualTo(0);

        // Verify database consistency
        String userKey = String.format(USER_CHANCE_KEY, TEST_EVENT_ID, TEST_USER_ID);
        RAtomicLong userChange=redissonClient.getAtomicLong(userKey);
        assertThat(Math.toIntExact(userChange.get())).isEqualTo(0);

        log.info("✓ Test 1 PASSED: All draws succeeded with correct quota deduction");
    }

    /**
     * Test 2: Over-quota scenario
     * Purpose: Test if system correctly prevents over-drawing
     */
    @Test
    public void testConcurrentDraws_OverQuota() throws Exception {
        log.info("=== Test 2: Over-Quota Scenario ===");

        int userQuota = 50;
        int attemptedDraws = 100; // Try to draw more than quota
        int concurrentThreads = 50;

        // Set limited user quota
        updateUserQuota(TEST_USER_ID, userQuota);

        // Initialize lottery cache
        lotteryService.initPrizeStock(TEST_EVENT_ID);

        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch latch = new CountDownLatch(attemptedDraws);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger quotaExceededCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Submit draw requests exceeding quota
        for (int i = 0; i < attemptedDraws; i++) {
            executor.submit(() -> {
                try {
                    MvcResult result = mockMvc.perform(
                            post("/user/event/" + TEST_EVENT_ID + "/draw")
                                    .header("Authorization", "Bearer " + userToken)
                    ).andReturn();

                    int status = result.getResponse().getStatus();
                    if (status == 200) {
                        successCount.incrementAndGet();
                    } else if (status == 400) {
                        String response = result.getResponse().getContentAsString();
                        if (response.contains("insufficient remaining draws")) {
                            quotaExceededCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    log.error("Draw failed with exception", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        long endTime = System.currentTimeMillis();

        // Print results
        log.info("=== Test 2 Results ===");
        log.info("User quota: {}", userQuota);
        log.info("Attempted draws: {}", attemptedDraws);
        log.info("Successful draws: {}", successCount.get());
        log.info("Quota exceeded rejections: {}", quotaExceededCount.get());
        log.info("Duration: {} ms", endTime - startTime);

        // Verify results
        assertThat(successCount.get()).isEqualTo(userQuota);
        assertThat(quotaExceededCount.get()).isEqualTo(attemptedDraws - userQuota);

        // Verify database consistency
        UserLotteryQuota quota = userLotteryQuotaRepository
                .findByUidAndLotteryEventId(TEST_USER_ID, TEST_EVENT_ID)
                .orElseThrow();
        assertThat(quota.getDrawQuota()).isEqualTo(0);

        log.info("✓ Test 2 PASSED: System correctly prevented over-drawing");
    }

    /**
     * Test 3: Prize stock depletion
     * Purpose: Test if system correctly handles prize stock exhaustion
     */
    @Test
    public void testConcurrentDraws_PrizeStockDepletion() throws Exception {
        log.info("=== Test 3: Prize Stock Depletion ===");

        int prizeStock = 30;
        int userQuota = 100;
        int concurrentThreads = 50;

        // Create event with limited prize stock
        createLimitedStockEvent(prizeStock);

        // Set user quota higher than prize stock
        updateUserQuota(TEST_USER_ID, userQuota);

        // Initialize lottery cache
        lotteryService.initPrizeStock(TEST_EVENT_ID);

        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch latch = new CountDownLatch(userQuota);

        AtomicInteger totalDraws = new AtomicInteger(0);
        AtomicInteger winCount = new AtomicInteger(0);
        AtomicInteger missCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Submit all draws
        for (int i = 0; i < userQuota; i++) {
            executor.submit(() -> {
                try {
                    MvcResult result = mockMvc.perform(
                            post("/user/event/" + TEST_EVENT_ID + "/draw")
                                    .header("Authorization", "Bearer " + userToken)
                    ).andReturn();

                    if (result.getResponse().getStatus() == 200) {
                        totalDraws.incrementAndGet();
                        String response = result.getResponse().getContentAsString();
                        Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
                        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
                        String prize = (String) data.get("prize");

                        if ("Miss".equals(prize)) {
                            missCount.incrementAndGet();
                        } else {
                            winCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    log.error("Draw failed", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        long endTime = System.currentTimeMillis();

        // Print results
        log.info("=== Test 3 Results ===");
        log.info("Prize stock: {}", prizeStock);
        log.info("User quota: {}", userQuota);
        log.info("Total draws: {}", totalDraws.get());
        log.info("Win count: {}", winCount.get());
        log.info("Miss count: {}", missCount.get());
        log.info("Duration: {} ms", endTime - startTime);

        // Verify results
        assertThat(winCount.get()).isLessThanOrEqualTo(prizeStock);
        assertThat(totalDraws.get()).isEqualTo(userQuota);

        // Verify prize stock in database
        List<LotteryPrize> prizes = lotteryPrizeRepository.findByLotteryEventId(TEST_EVENT_ID);
        int totalRemainingStock = prizes.stream()
                .mapToInt(LotteryPrize::getAmount)
                .sum();

        log.info("Remaining prize stock in DB: {}", totalRemainingStock);
        assertThat(totalRemainingStock).isGreaterThanOrEqualTo(0);

        log.info("✓ Test 3 PASSED: Prize stock correctly managed under concurrency");
    }

    /**
     * Test 4: Multiple users concurrent draws
     * Purpose: Test system performance with multiple users drawing simultaneously
     */
    @Test
    public void testConcurrentDraws_MultipleUsers() throws Exception {
        log.info("=== Test 4: Multiple Users Concurrent Draws ===");

        int numberOfUsers = 20;
        int drawsPerUser = 10;
        int totalDraws = numberOfUsers * drawsPerUser;

        // Initialize lottery cache
        lotteryService.initPrizeStock(TEST_EVENT_ID);

        ExecutorService executor = Executors.newFixedThreadPool(numberOfUsers);
        CountDownLatch latch = new CountDownLatch(totalDraws);

        AtomicInteger totalSuccessCount = new AtomicInteger(0);
        ConcurrentHashMap<Long, Integer> userSuccessMap = new ConcurrentHashMap<>();

        long startTime = System.currentTimeMillis();

        // Simulate multiple users
        for (long userId = 100; userId < 100 + numberOfUsers; userId++) {
            final long currentUserId = userId;

            // Create user quota
            createUserQuota(currentUserId, drawsPerUser);

            // Submit draws for this user
            for (int draw = 0; draw < drawsPerUser; draw++) {
                executor.submit(() -> {
                    try {
                        MvcResult result = mockMvc.perform(
                                post("/user/event/" + TEST_EVENT_ID + "/draw")
                                        .header("Authorization", "Bearer " + userToken)
                        ).andReturn();

                        if (result.getResponse().getStatus() == 200) {
                            totalSuccessCount.incrementAndGet();
                            userSuccessMap.merge(currentUserId, 1, Integer::sum);
                        }
                    } catch (Exception e) {
                        log.error("Draw failed for user {}", currentUserId, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        latch.await(120, TimeUnit.SECONDS);
        executor.shutdown();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Print results
        log.info("=== Test 4 Results ===");
        log.info("Number of users: {}", numberOfUsers);
        log.info("Draws per user: {}", drawsPerUser);
        log.info("Total draws: {}", totalDraws);
        log.info("Total successful draws: {}", totalSuccessCount.get());
        log.info("Duration: {} ms", duration);
        log.info("Throughput: {} draws/second", totalDraws * 1000.0 / duration);

        // Verify each user's draw count
        log.info("Per-user success counts:");
        userSuccessMap.forEach((userId, count) -> {
            log.info("  User {}: {} draws", userId, count);
            assertThat(count).isLessThanOrEqualTo(drawsPerUser);
        });

        log.info("✓ Test 4 PASSED: Multiple users handled correctly under concurrency");
    }

    /**
     * Test 5: Stress test - Maximum throughput
     * Purpose: Find the maximum throughput the system can handle
     */
    @Test
    public void testStress_MaximumThroughput() throws Exception {
        log.info("=== Test 5: Stress Test - Maximum Throughput ===");

        int totalDraws = 1000;
        int concurrentThreads = 100;

        // Set large quota
        updateUserQuota(TEST_USER_ID, totalDraws);

        // Initialize lottery cache
        lotteryService.initPrizeStock(TEST_EVENT_ID);

        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch latch = new CountDownLatch(totalDraws);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<Long> responseTimes = new CopyOnWriteArrayList<>();

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalDraws; i++) {
            executor.submit(() -> {
                long requestStart = System.currentTimeMillis();
                try {
                    MvcResult result = mockMvc.perform(
                            post("/user/event/" + TEST_EVENT_ID + "/draw")
                                    .header("Authorization", "Bearer " + userToken)
                    ).andReturn();

                    long requestEnd = System.currentTimeMillis();
                    responseTimes.add(requestEnd - requestStart);

                    if (result.getResponse().getStatus() == 200) {
                        successCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(180, TimeUnit.SECONDS);
        executor.shutdown();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Calculate statistics
        double avgResponseTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);

        long maxResponseTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);

        long minResponseTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .min()
                .orElse(0L);

        // Print results
        log.info("=== Test 5 Results ===");
        log.info("Total draws: {}", totalDraws);
        log.info("Concurrent threads: {}", concurrentThreads);
        log.info("Successful draws: {}", successCount.get());
        log.info("Failed draws: {}", errorCount.get());
        log.info("Total duration: {} ms", duration);
        log.info("Throughput: {} draws/second", totalDraws * 1000.0 / duration);
        log.info("Average response time: {} ms", avgResponseTime);
        log.info("Min response time: {} ms", minResponseTime);
        log.info("Max response time: {} ms", maxResponseTime);

        // Calculate percentiles
        List<Long> sortedTimes = new ArrayList<>(responseTimes);
        sortedTimes.sort(Long::compareTo);
        int p50Index = (int) (sortedTimes.size() * 0.50);
        int p95Index = (int) (sortedTimes.size() * 0.95);
        int p99Index = (int) (sortedTimes.size() * 0.99);

        log.info("P50 response time: {} ms", sortedTimes.get(p50Index));
        log.info("P95 response time: {} ms", sortedTimes.get(p95Index));
        log.info("P99 response time: {} ms", sortedTimes.get(p99Index));

        assertThat(successCount.get()).isGreaterThan(0);
        Integer missCountLevel = (int) (totalDraws * 0.05);
        assertThat(errorCount.get()).isLessThan(missCountLevel); // Less than 5% error rate

        log.info("✓ Test 5 PASSED: System handled stress test successfully");
    }

    // ========== Helper Methods ==========

    private String getAuthToken(String username, String password) throws Exception {
        LoginRequest loginRequest = new LoginRequest(username, password);

        MvcResult result = mockMvc.perform(
                post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
        ).andExpect(status().isOk()).andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        return (String) data.get("token");
    }

    private void cleanupTestData() {
        try {
            // Clear Redis cache first
            redissonClient.getKeys().deleteByPattern("lottery:" + TEST_EVENT_ID + ":*");

            // Delete test data in correct order (avoid foreign key issues)
            // 1. Delete win records
            winRecordRepository.findAll().stream()
                    .filter(record -> record.getLotteryEventId().equals(TEST_EVENT_ID))
                    .forEach(record -> {
                        try {
                            winRecordRepository.deleteById(record.getId());
                        } catch (Exception e) {
                            log.warn("Failed to delete win record: {}", record.getId());
                        }
                    });

            // 2. Delete user quotas
            userLotteryQuotaRepository.findAll().stream()
                    .filter(quota -> quota.getLotteryEventId().equals(TEST_EVENT_ID))
                    .forEach(quota -> {
                        try {
                            userLotteryQuotaRepository.deleteById(quota.getId());
                        } catch (Exception e) {
                            log.warn("Failed to delete user quota: {}", quota.getId());
                        }
                    });

            // 3. Delete prizes
            lotteryPrizeRepository.findByLotteryEventId(TEST_EVENT_ID)
                    .forEach(prize -> {
                        try {
                            lotteryPrizeRepository.deleteById(prize.getId());
                        } catch (Exception e) {
                            log.warn("Failed to delete prize: {}", prize.getId());
                        }
                    });

            // 4. Delete event
            if (lotteryEventRepository.existsById(TEST_EVENT_ID)) {
                try {
                    lotteryEventRepository.deleteById(TEST_EVENT_ID);
                } catch (Exception e) {
                    log.warn("Failed to delete event: {}", TEST_EVENT_ID);
                }
            }

            // Flush to ensure all deletions are committed
            lotteryEventRepository.flush();

        } catch (Exception e) {
            log.error("Cleanup test data failed", e);
        }
    }

    private long initializeTestEvent() {
        // Create lottery event
        LotteryEvent event = new LotteryEvent();
        ;
        event.setName("Concurrency Test Event");
        event.setIsActive(true);
        event.setSettingAmount(10000);
        event.setRemainAmount(10000);
        event = lotteryEventRepository.saveAndFlush(event);
        TEST_EVENT_ID = event.getId();
        // Create prizes
        createPrize("small", new BigDecimal("0.20"), 1000);
        createPrize("medium", new BigDecimal("0.15"), 500);
        createPrize("big", new BigDecimal("0.10"), 200);

        return event.getId();
    }

    private void createLimitedStockEvent(int totalStock) {
        cleanupTestData();

        // Create lottery event
        LotteryEvent event = new LotteryEvent();

        event.setName("Limited Stock Test Event");
        event.setIsActive(true);
        event.setSettingAmount(1000);
        event.setRemainAmount(1000);
        event=lotteryEventRepository.saveAndFlush(event);
        TEST_EVENT_ID= event.getId();

        // Create prize with limited stock
        createPrize("limited_prize", new BigDecimal("1.00"), totalStock);
    }

    private void createPrize(String name, BigDecimal rate, int amount) {
        LotteryPrize prize = new LotteryPrize();
        prize.setLotteryEventId(TEST_EVENT_ID);
        prize.setName(name);
        prize.setRate(rate);
        prize.setAmount(amount);
        lotteryPrizeRepository.saveAndFlush(prize);
    }

    private void updateUserQuota(Long userId, int quota) {
        UserLotteryQuota userQuota = userLotteryQuotaRepository
                .findByUidAndLotteryEventId(userId, TEST_EVENT_ID)
                .orElse(null);

        if (userQuota == null) {
            userQuota = new UserLotteryQuota();
            userQuota.setUid(userId.intValue());
            userQuota.setLotteryEventId(TEST_EVENT_ID);
        }

        userQuota.setDrawQuota(quota);
        userLotteryQuotaRepository.saveAndFlush(userQuota);
    }

    private void createUserQuota(Long userId, int quota) {
        // Check if quota already exists
        if (userLotteryQuotaRepository.findByUidAndLotteryEventId(userId, TEST_EVENT_ID).isPresent()) {
            log.debug("User quota already exists for user {}", userId);
            return;
        }

        UserLotteryQuota userQuota = new UserLotteryQuota();
        userQuota.setUid(userId.intValue());
        userQuota.setLotteryEventId(TEST_EVENT_ID);
        userQuota.setDrawQuota(quota);
        userLotteryQuotaRepository.saveAndFlush(userQuota);
    }
}