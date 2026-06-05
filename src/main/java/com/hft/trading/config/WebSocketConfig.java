package com.hft.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * WebSocket configuration properties
 */
@Component
@ConfigurationProperties(prefix = "trading.ws")
public class WebSocketConfig {

    /**
     * Idle timeout for WebSocket connections (milliseconds)
     */
    private long idleTimeoutMs = 90000;

    /**
     * Heartbeat interval (milliseconds)
     */
    private long heartbeatMs = 30000;

    /**
     * Rate limit for subscribe/unsubscribe requests (per second)
     */
    private int subscribeRateLimit = 10;

    /**
     * Maximum symbols per subscription request
     */
    private int maxSymbolsPerSubscription = 50;

    // Getters and Setters

    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public void setIdleTimeoutMs(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public long getHeartbeatMs() {
        return heartbeatMs;
    }

    public void setHeartbeatMs(long heartbeatMs) {
        this.heartbeatMs = heartbeatMs;
    }

    public int getSubscribeRateLimit() {
        return subscribeRateLimit;
    }

    public void setSubscribeRateLimit(int subscribeRateLimit) {
        this.subscribeRateLimit = subscribeRateLimit;
    }

    public int getMaxSymbolsPerSubscription() {
        return maxSymbolsPerSubscription;
    }

    public void setMaxSymbolsPerSubscription(int maxSymbolsPerSubscription) {
        this.maxSymbolsPerSubscription = maxSymbolsPerSubscription;
    }
}
