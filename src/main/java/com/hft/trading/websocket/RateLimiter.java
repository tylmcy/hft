package com.hft.trading.websocket;

/**
 * Token bucket rate limiter for WebSocket subscribe/unsubscribe requests.
 *
 * <p>This rate limiter uses a token bucket algorithm to control the rate of requests.
 * Tokens are refilled at a fixed interval, up to a maximum capacity.
 *
 * <p>Thread-safe: uses synchronized methods to ensure correct behavior under concurrent access.
 */
public class RateLimiter {

    private final int maxTokens;
    private final long refillIntervalMs;
    private int availableTokens;
    private long lastRefillTime;

    /**
     * Create a rate limiter with specified capacity and refill interval.
     *
     * @param maxTokens Maximum number of tokens (also the refill amount per interval)
     * @param refillIntervalMs Time interval in milliseconds for token refill
     */
    public RateLimiter(int maxTokens, long refillIntervalMs) {
        this.maxTokens = maxTokens;
        this.refillIntervalMs = refillIntervalMs;
        this.availableTokens = maxTokens;
        this.lastRefillTime = System.currentTimeMillis();
    }

    /**
     * Try to acquire a token.
     *
     * @return true if a token was acquired, false if no tokens available
     */
    public synchronized boolean tryAcquire() {
        refill();
        if (availableTokens > 0) {
            availableTokens--;
            return true;
        }
        return false;
    }

    /**
     * Refill tokens based on elapsed time.
     * Tokens are refilled in batches of maxTokens per refill interval.
     */
    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;
        if (elapsed >= refillIntervalMs) {
            int tokensToAdd = (int) (elapsed / refillIntervalMs) * maxTokens;
            availableTokens = Math.min(maxTokens, availableTokens + tokensToAdd);
            lastRefillTime = now;
        }
    }

    /**
     * Get current number of available tokens (for testing/debugging).
     *
     * @return current available tokens
     */
    public synchronized int getAvailableTokens() {
        refill();
        return availableTokens;
    }
}
