package com.hft.trading.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RateLimiter Test
 *
 * Tests token bucket rate limiter for WebSocket subscribe/unsubscribe requests.
 */
class RateLimiterTest {

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        // 10 tokens max, refill every 1000ms (1 second)
        rateLimiter = new RateLimiter(10, 1000);
    }

    @Test
    @DisplayName("testAllowWithinLimit - verify requests within limit are allowed")
    void testAllowWithinLimit() {
        // When: acquire within limit
        for (int i = 0; i < 10; i++) {
            assertTrue(rateLimiter.tryAcquire(), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    @DisplayName("testDenyOverLimit - verify requests over limit are denied")
    void testDenyOverLimit() {
        // Given: exhaust all tokens
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryAcquire();
        }

        // When: acquire over limit
        boolean result = rateLimiter.tryAcquire();

        // Then: should be denied
        assertFalse(result, "Request over limit should be denied");
    }

    @Test
    @DisplayName("testRefill - verify tokens refill over time")
    void testRefill() throws InterruptedException {
        // Given: exhaust all tokens
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryAcquire();
        }
        assertFalse(rateLimiter.tryAcquire(), "Should be denied after exhausting tokens");

        // When: wait for refill interval
        Thread.sleep(1100); // Wait slightly more than 1000ms

        // Then: tokens should be refilled
        assertTrue(rateLimiter.tryAcquire(), "Request should be allowed after refill");
    }

    @Test
    @DisplayName("testPartialRefill - verify partial token consumption")
    void testPartialRefill() throws InterruptedException {
        // Given: consume only 5 tokens
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryAcquire();
        }

        // When: wait for refill interval
        Thread.sleep(1100);

        // Then: all tokens should be available again (capped at max)
        for (int i = 0; i < 10; i++) {
            assertTrue(rateLimiter.tryAcquire(), "Request " + (i + 1) + " should be allowed after refill");
        }
    }

    @Test
    @DisplayName("testConcurrentAccess - verify thread safety")
    void testConcurrentAccess() throws InterruptedException {
        // Given: multiple threads trying to acquire tokens
        int threadCount = 5;
        int requestsPerThread = 3;
        Thread[] threads = new Thread[threadCount];
        int[] successCount = {0};

        // When: run concurrent requests
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    if (rateLimiter.tryAcquire()) {
                        synchronized (successCount) {
                            successCount[0]++;
                        }
                    }
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        // Then: should not exceed max tokens (10)
        assertEquals(10, successCount[0], "Total successful requests should not exceed max tokens");
    }
}
