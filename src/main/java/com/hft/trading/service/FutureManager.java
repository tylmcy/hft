package com.hft.trading.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Future 管理器
 *
 * 根据 V12 技术方案 3.5：
 * HTTP同步等待机制，使用 CompletableFuture 实现
 */
public class FutureManager {

    /**
     * 处理结果
     */
    public static class ProcessResult {
        private final boolean success;
        private final String message;
        private final Long orderId;

        public ProcessResult(boolean success, String message, Long orderId) {
            this.success = success;
            this.message = message;
            this.orderId = orderId;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public Long getOrderId() {
            return orderId;
        }
    }

    private final ConcurrentHashMap<Long, CompletableFuture<ProcessResult>> futures = new ConcurrentHashMap<>();

    /**
     * 注册 Future
     *
     * @param orderId 订单ID
     * @return CompletableFuture
     */
    public CompletableFuture<ProcessResult> register(long orderId) {
        CompletableFuture<ProcessResult> future = new CompletableFuture<>();
        futures.put(orderId, future);
        return future;
    }

    /**
     * 获取 Future
     *
     * @param orderId 订单ID
     * @return CompletableFuture 或 null
     */
    public CompletableFuture<ProcessResult> getFuture(long orderId) {
        return futures.get(orderId);
    }

    /**
     * 完成 Future 并移除
     *
     * @param orderId 订单ID
     * @param result  处理结果
     */
    public void complete(long orderId, ProcessResult result) {
        CompletableFuture<ProcessResult> future = futures.remove(orderId);
        if (future != null) {
            future.complete(result);
        }
    }

    /**
     * 移除 Future（超时或取消时调用）
     *
     * @param orderId 订单ID
     */
    public void remove(long orderId) {
        futures.remove(orderId);
    }

    /**
     * 获取当前等待数量
     */
    public int size() {
        return futures.size();
    }
}
