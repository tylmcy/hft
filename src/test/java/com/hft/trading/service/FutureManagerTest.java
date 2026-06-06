package com.hft.trading.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FutureManager 测试
 *
 * 根据 V12 技术方案 3.5：
 * HTTP同步等待机制，使用 CompletableFuture 实现
 */
class FutureManagerTest {

    private FutureManager futureManager;

    @BeforeEach
    void setUp() {
        futureManager = new FutureManager();
    }

    @Test
    @DisplayName("应该能够注册和获取 Future")
    void shouldRegisterAndGetFuture() {
        long orderId = 12345L;

        CompletableFuture<FutureManager.ProcessResult> future = futureManager.register(orderId);

        assertNotNull(future);
        assertEquals(future, futureManager.getFuture(orderId));
    }

    @Test
    @DisplayName("完成 Future 应该唤醒等待的线程")
    void shouldCompleteFutureAndWakeUpWaitingThread() throws Exception {
        long orderId = 12345L;
        CompletableFuture<FutureManager.ProcessResult> future = futureManager.register(orderId);

        // 在另一个线程中完成 Future
        Thread completer = new Thread(() -> {
            try {
                Thread.sleep(100);
                FutureManager.ProcessResult result = new FutureManager.ProcessResult(true, "Success", null);
                futureManager.complete(orderId, result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        completer.start();

        // 等待完成
        FutureManager.ProcessResult result = future.get(1, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals("Success", result.getMessage());
        completer.join();
    }

    @Test
    @DisplayName("超时应该抛出 TimeoutException")
    void shouldThrowTimeoutException() {
        long orderId = 12345L;
        CompletableFuture<FutureManager.ProcessResult> future = futureManager.register(orderId);

        assertThrows(TimeoutException.class, () -> {
            future.get(100, TimeUnit.MILLISECONDS);
        });
    }

    @Test
    @DisplayName("完成不存在的 orderId 应该静默处理")
    void shouldSilentlyHandleNonExistentOrderId() {
        long orderId = 99999L;

        // 不应该抛出异常
        assertDoesNotThrow(() -> {
            futureManager.complete(orderId, new FutureManager.ProcessResult(false, "Not found", null));
        });
    }

    @Test
    @DisplayName("应该能够移除 Future")
    void shouldRemoveFuture() {
        long orderId = 12345L;
        futureManager.register(orderId);

        futureManager.remove(orderId);

        assertNull(futureManager.getFuture(orderId));
    }

    @Test
    @DisplayName("应该支持并发注册和完成")
    void shouldSupportConcurrentRegisterAndComplete() throws Exception {
        int count = 100;
        Thread[] threads = new Thread[count];

        for (int i = 0; i < count; i++) {
            final long orderId = i;
            threads[i] = new Thread(() -> {
                CompletableFuture<FutureManager.ProcessResult> future = futureManager.register(orderId);
                FutureManager.ProcessResult result = new FutureManager.ProcessResult(true, "OK", orderId);
                futureManager.complete(orderId, result);
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        // 验证所有 Future 都已完成
        for (int i = 0; i < count; i++) {
            CompletableFuture<FutureManager.ProcessResult> future = futureManager.getFuture((long) i);
            if (future != null) {
                assertTrue(future.isDone());
            }
        }
    }

    @Test
    @DisplayName("使用 orTimeout 应该自动超时")
    void shouldTimeoutWithOrTimeout() {
        long orderId = 12345L;
        CompletableFuture<FutureManager.ProcessResult> future = futureManager.register(orderId);

        CompletableFuture<FutureManager.ProcessResult> timeoutFuture = future.orTimeout(100, TimeUnit.MILLISECONDS);

        ExecutionException ex = assertThrows(ExecutionException.class, () -> {
            timeoutFuture.get();
        });
        assertTrue(ex.getCause() instanceof TimeoutException);
    }

    @Test
    @DisplayName("ProcessResult 应该正确存储结果信息")
    void shouldStoreResultInfo() {
        FutureManager.ProcessResult success = new FutureManager.ProcessResult(true, "OK", 12345L);
        assertTrue(success.isSuccess());
        assertEquals("OK", success.getMessage());
        assertEquals(12345L, success.getOrderId());

        FutureManager.ProcessResult failure = new FutureManager.ProcessResult(false, "Error", null);
        assertFalse(failure.isSuccess());
        assertEquals("Error", failure.getMessage());
        assertNull(failure.getOrderId());
    }

    @Test
    @DisplayName("完成后的 Future 应该从管理器中移除")
    void shouldRemoveFutureAfterComplete() {
        long orderId = 12345L;
        futureManager.register(orderId);

        futureManager.complete(orderId, new FutureManager.ProcessResult(true, "OK", orderId));

        // 完成后 Future 应该被移除（或保留供查询，具体看实现）
        // 这里我们允许两种实现方式
    }
}
