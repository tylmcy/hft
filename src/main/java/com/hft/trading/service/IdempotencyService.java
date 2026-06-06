package com.hft.trading.service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等服务
 *
 * 根据 V12 技术方案 5.2：
 * HTTP 层幂等检查，防止重复下单
 */
public class IdempotencyService {

    /**
     * 幂等状态
     */
    public enum Status {
        PROCESSING,  // 处理中
        CONFIRMED    // 已确认
    }

    /**
     * 幂等检查结果
     */
    public static class Result {
        private final Status status;
        private final Long orderId;

        public Result(Status status, Long orderId) {
            this.status = status;
            this.orderId = orderId;
        }

        public Status getStatus() {
            return status;
        }

        public Long getOrderId() {
            return orderId;
        }
    }

    /**
     * 幂等条目
     */
    private static class Entry {
        final long orderId;
        volatile Status status;
        final long createTime;

        Entry(long orderId) {
            this.orderId = orderId;
            this.status = Status.PROCESSING;
            this.createTime = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis; // 过期时间（毫秒）

    public IdempotencyService() {
        this(300000); // 默认 5 分钟
    }

    public IdempotencyService(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /**
     * 检查或注册幂等键
     *
     * @param idempotencyKey 幂等键
     * @param orderId        订单ID
     * @return 检查结果
     */
    public Result checkOrRegister(String idempotencyKey, long orderId) {
        // 清理过期条目
        cleanExpired();

        // 先检查是否存在
        Entry existing = entries.get(idempotencyKey);
        if (existing != null) {
            // 已存在：返回已有条目
            return new Result(existing.status, existing.orderId);
        }

        // 不存在：创建新条目
        Entry newEntry = new Entry(orderId);
        Entry previous = entries.putIfAbsent(idempotencyKey, newEntry);

        if (previous != null) {
            // 并发情况下，其他线程先创建了：返回已有条目
            return new Result(previous.status, previous.orderId);
        }

        // 新创建成功：返回 PROCESSING，orderId 为 null
        return new Result(Status.PROCESSING, null);
    }

    /**
     * 确认幂等键
     */
    public void confirm(String idempotencyKey, long orderId) {
        Entry entry = entries.get(idempotencyKey);
        if (entry != null && entry.orderId == orderId) {
            entry.status = Status.CONFIRMED;
        }
    }

    /**
     * 释放幂等键（失败时调用）
     */
    public void release(String idempotencyKey) {
        entries.remove(idempotencyKey);
    }

    /**
     * 获取已确认的订单ID
     */
    public Long getOrderId(String idempotencyKey) {
        Entry entry = entries.get(idempotencyKey);
        if (entry != null && entry.status == Status.CONFIRMED) {
            return entry.orderId;
        }
        return null;
    }

    /**
     * 清理过期条目
     */
    private void cleanExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(entry ->
            now - entry.getValue().createTime > ttlMillis
        );
    }
}
