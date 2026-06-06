package com.hft.trading.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IdempotencyService 测试
 *
 * 根据 V12 技术方案 5.2：
 * HTTP 层幂等检查，防止重复下单
 */
class IdempotencyServiceTest {

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService();
    }

    @Test
    @DisplayName("首次请求应该返回 PROCESSING 状态")
    void shouldReturnProcessingForFirstRequest() {
        String idempotencyKey = "uuid-v4";
        long orderId = 12345L;

        IdempotencyService.Result result = idempotencyService.checkOrRegister(idempotencyKey, orderId);

        assertEquals(IdempotencyService.Status.PROCESSING, result.getStatus());
        assertNull(result.getOrderId());
    }

    @Test
    @DisplayName("重复请求应该返回 CONFIRMED 状态和已存在的 orderId")
    void shouldReturnConfirmedForDuplicateRequest() {
        String idempotencyKey = "uuid-v4";
        long orderId = 12345L;

        // 首次请求
        idempotencyService.checkOrRegister(idempotencyKey, orderId);

        // 确认
        idempotencyService.confirm(idempotencyKey, orderId);

        // 重复请求
        IdempotencyService.Result result = idempotencyService.checkOrRegister(idempotencyKey, orderId);

        assertEquals(IdempotencyService.Status.CONFIRMED, result.getStatus());
        assertEquals(orderId, result.getOrderId());
    }

    @Test
    @DisplayName("处理中的请求应该返回 PROCESSING 状态")
    void shouldReturnProcessingForProcessingRequest() {
        String idempotencyKey = "uuid-v4";
        long orderId = 12345L;

        // 首次请求
        idempotencyService.checkOrRegister(idempotencyKey, orderId);

        // 未确认前的重复请求
        IdempotencyService.Result result = idempotencyService.checkOrRegister(idempotencyKey, 99999L);

        assertEquals(IdempotencyService.Status.PROCESSING, result.getStatus());
        // 返回的是首次注册的 orderId
        assertEquals(orderId, result.getOrderId());
    }

    @Test
    @DisplayName("确认后应该能够查询到 orderId")
    void shouldGetOrderIdAfterConfirm() {
        String idempotencyKey = "uuid-v4";
        long orderId = 12345L;

        idempotencyService.checkOrRegister(idempotencyKey, orderId);
        idempotencyService.confirm(idempotencyKey, orderId);

        Long retrieved = idempotencyService.getOrderId(idempotencyKey);

        assertEquals(orderId, retrieved);
    }

    @Test
    @DisplayName("失败后应该释放幂等键")
    void shouldReleaseKeyAfterFailure() {
        String idempotencyKey = "uuid-v4";
        long orderId1 = 12345L;
        long orderId2 = 67890L;

        // 首次请求
        idempotencyService.checkOrRegister(idempotencyKey, orderId1);

        // 失败释放
        idempotencyService.release(idempotencyKey);

        // 可以重新注册
        IdempotencyService.Result result = idempotencyService.checkOrRegister(idempotencyKey, orderId2);

        assertEquals(IdempotencyService.Status.PROCESSING, result.getStatus());
    }

    @Test
    @DisplayName("应该支持并发访问")
    void shouldSupportConcurrentAccess() throws Exception {
        String idempotencyKey = "uuid-v4";
        long orderId = 12345L;

        // 并发注册同一个幂等键
        Thread[] threads = new Thread[10];
        CompletableFuture<IdempotencyService.Result>[] futures = new CompletableFuture[10];

        for (int i = 0; i < 10; i++) {
            final int index = i;
            futures[i] = new CompletableFuture<>();
            threads[i] = new Thread(() -> {
                try {
                    IdempotencyService.Result result = idempotencyService.checkOrRegister(idempotencyKey, orderId);
                    futures[index].complete(result);
                } catch (Exception e) {
                    futures[index].completeExceptionally(e);
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        // 所有请求都应该得到相同的结果
        // 只有一个线程会成功创建（返回PROCESSING，orderId为null）
        // 其他线程会返回已存在的条目（返回PROCESSING，orderId为已注册的orderId）
        int processingWithNull = 0;
        int processingWithOrderId = 0;

        for (CompletableFuture<IdempotencyService.Result> future : futures) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                IdempotencyService.Result result = future.get();
                assertEquals(IdempotencyService.Status.PROCESSING, result.getStatus());
                if (result.getOrderId() == null) {
                    processingWithNull++;
                } else {
                    assertEquals(orderId, result.getOrderId());
                    processingWithOrderId++;
                }
            }
        }

        // 只有一个线程应该得到 null orderId（首次创建）
        assertEquals(1, processingWithNull);
        // 其余线程应该得到已存在的 orderId
        assertEquals(9, processingWithOrderId);
    }

    @Test
    @DisplayName("应该自动过期清理旧的幂等键")
    void shouldExpireOldKeys() throws InterruptedException {
        // 创建一个 TTL 很短的实例
        IdempotencyService service = new IdempotencyService(100); // 100ms TTL

        String idempotencyKey = "uuid-v4";
        long orderId = 12345L;

        service.checkOrRegister(idempotencyKey, orderId);
        service.confirm(idempotencyKey, orderId);

        // 等待过期
        Thread.sleep(200);

        // 过期后可以重新注册
        IdempotencyService.Result result = service.checkOrRegister(idempotencyKey, orderId);

        assertEquals(IdempotencyService.Status.PROCESSING, result.getStatus());
    }

    @Test
    @DisplayName("不存在幂等键查询应该返回 null")
    void shouldReturnNullForNonExistentKey() {
        Long orderId = idempotencyService.getOrderId("non-existent");

        assertNull(orderId);
    }
}
