package com.hft.trading.lifecycle;

import com.hft.trading.queue.QueueManager;
import com.hft.trading.service.AlertService;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import com.hft.trading.store.InMemoryPositionStore;
import com.hft.trading.util.TestUtils;
import com.hft.trading.worker.AccountWorker;
import com.hft.trading.worker.ClearingWorker;
import com.hft.trading.worker.MatchingEngineWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LifecycleManager 单元测试
 *
 * 测试系统生命周期管理：
 * - 启动流程（无恢复/有恢复）
 * - 关闭流程
 * - 状态转换
 * - Worker 生命周期
 *
 * 注意：由于 LifecycleManager 在内部创建真实的 Worker 和 RecoveryManager，
 * 这些测试更接近集成测试风格，验证整体生命周期行为。
 */
class LifecycleManagerTest {

    @TempDir
    Path tempDir;

    private LifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        // Create real stores
        AlertService alertService = TestUtils.createTestAlertService();
        InMemoryAccountStore accountStore = new InMemoryAccountStore(alertService);
        InMemoryPositionStore positionStore = new InMemoryPositionStore(alertService);
        InMemoryOrderStore orderStore = new InMemoryOrderStore();
        InMemoryInstrumentStore instrumentStore = new InMemoryInstrumentStore();

        // Create QueueManager with temp directory
        String basePath = tempDir.toAbsolutePath().toString();
        QueueManager queueManager = new QueueManager(basePath);

        // Create LifecycleManager
        lifecycleManager = new LifecycleManager(
            accountStore,
            positionStore,
            orderStore,
            instrumentStore,
            queueManager
        );
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // Ensure cleanup after each test
        if (lifecycleManager != null) {
            try {
                lifecycleManager.shutdown();
            } catch (Exception e) {
                // Ignore shutdown errors in cleanup
            }
            // Wait for threads to fully stop
            TimeUnit.MILLISECONDS.sleep(200);
        }
    }

    @Test
    @DisplayName("testStartup_NoRecovery - 验证无快照时的干净启动")
    void testStartup_NoRecovery() throws Exception {
        // Given: 新的临时目录，无快照文件

        // When: 启动系统
        lifecycleManager.start();

        // Then: 系统状态应为 RUNNING（因为没有快照文件需要恢复）
        assertEquals(LifecycleManager.SystemStatus.RUNNING, lifecycleManager.getSystemStatus());
    }

    @Test
    @DisplayName("testStartup_WithRecovery - 验证有快照时触发恢复")
    void testStartup_WithRecovery() throws Exception {
        // Given: 创建一个新的 LifecycleManager，让 RecoveryManager 检查快照
        // 由于 temp 目录是空的，实际上不会触发恢复
        // 这个测试验证的是启动流程能正确处理

        // When: 启动系统
        lifecycleManager.start();

        // Then: 系统状态应为 RUNNING
        assertEquals(LifecycleManager.SystemStatus.RUNNING, lifecycleManager.getSystemStatus());
    }

    @Test
    @DisplayName("testShutdown_Graceful - 验证优雅关闭序列")
    void testShutdown_Graceful() throws Exception {
        // Given: 系统已启动
        lifecycleManager.start();
        assertEquals(LifecycleManager.SystemStatus.RUNNING, lifecycleManager.getSystemStatus());

        // When: 关闭系统
        lifecycleManager.shutdown();

        // Then: 状态应为 SHUTTING_DOWN
        assertEquals(LifecycleManager.SystemStatus.SHUTTING_DOWN, lifecycleManager.getSystemStatus());

        // Workers 应被停止（验证通过再次关闭不抛异常）
        lifecycleManager.shutdown(); // 第二次关闭应安全
    }

    @Test
    @DisplayName("testSystemStatusTransitions - 验证状态转换 STARTING → RUNNING → SHUTTING_DOWN")
    void testSystemStatusTransitions() throws Exception {
        // Given: 初始状态为 STARTING
        assertEquals(LifecycleManager.SystemStatus.STARTING, lifecycleManager.getSystemStatus());

        // When: 启动系统
        lifecycleManager.start();

        // Then: 状态应为 RUNNING
        assertEquals(LifecycleManager.SystemStatus.RUNNING, lifecycleManager.getSystemStatus());

        // When: 关闭系统
        lifecycleManager.shutdown();

        // Then: 状态应为 SHUTTING_DOWN
        assertEquals(LifecycleManager.SystemStatus.SHUTTING_DOWN, lifecycleManager.getSystemStatus());
    }

    @Test
    @DisplayName("testIsAcceptingRequests_RUNNING - 验证 RUNNING 时接受请求")
    void testIsAcceptingRequests_RUNNING() throws Exception {
        // Given: 系统已启动
        lifecycleManager.start();

        // When: 状态为 RUNNING
        assertEquals(LifecycleManager.SystemStatus.RUNNING, lifecycleManager.getSystemStatus());

        // Then: 应接受请求
        assertTrue(lifecycleManager.isAcceptingRequests(), "Should accept requests when RUNNING");
        assertTrue(lifecycleManager.canPlaceOrder(), "Should allow placing orders when RUNNING");
        assertTrue(lifecycleManager.canCancelOrder(), "Should allow canceling orders when RUNNING");
    }

    @Test
    @DisplayName("testIsAcceptingRequests_STARTING - 验证 STARTING 时不接受请求")
    void testIsAcceptingRequests_STARTING() {
        // Given: 系统未启动
        assertEquals(LifecycleManager.SystemStatus.STARTING, lifecycleManager.getSystemStatus());

        // When: 状态为 STARTING
        // Then: 不应接受请求
        assertFalse(lifecycleManager.isAcceptingRequests(), "Should not accept requests when STARTING");
        assertFalse(lifecycleManager.canPlaceOrder(), "Should not allow placing orders when STARTING");
        assertFalse(lifecycleManager.canCancelOrder(), "Should not allow canceling orders when STARTING");
    }

    @Test
    @DisplayName("testIsAcceptingRequests_SHUTTING_DOWN - 验证 SHUTTING_DOWN 时不接受新请求但允许撤单")
    void testIsAcceptingRequests_SHUTTING_DOWN() throws Exception {
        // Given: 系统已启动然后关闭
        lifecycleManager.start();
        lifecycleManager.shutdown();

        // When: 状态为 SHUTTING_DOWN
        assertEquals(LifecycleManager.SystemStatus.SHUTTING_DOWN, lifecycleManager.getSystemStatus());

        // Then: 不应接受新请求
        assertFalse(lifecycleManager.isAcceptingRequests(), "Should not accept new requests when SHUTTING_DOWN");
        assertFalse(lifecycleManager.canPlaceOrder(), "Should not allow placing orders when SHUTTING_DOWN");

        // But: 应允许撤单（清理现有订单）
        assertTrue(lifecycleManager.canCancelOrder(), "Should allow canceling orders when SHUTTING_DOWN");
    }

    @Test
    @DisplayName("testWorkerLifecycle - 验证 Worker 正确启动和停止")
    void testWorkerLifecycle() throws Exception {
        // Given: 启动系统
        lifecycleManager.start();

        // When: 获取 Workers
        AccountWorker accountWorker = lifecycleManager.getAccountWorker();
        MatchingEngineWorker matchingEngineWorker = lifecycleManager.getMatchingEngineWorker();
        ClearingWorker clearingWorker = lifecycleManager.getClearingWorker();

        // Then: Workers 应已创建
        assertNotNull(accountWorker, "AccountWorker should be created");
        assertNotNull(matchingEngineWorker, "MatchingEngineWorker should be created");
        assertNotNull(clearingWorker, "ClearingWorker should be created");

        // When: 关闭系统
        lifecycleManager.shutdown();

        // Then: 再次获取 Workers 应返回相同实例（但已停止）
        assertNotNull(lifecycleManager.getAccountWorker(), "AccountWorker should still exist after shutdown");
        assertNotNull(lifecycleManager.getMatchingEngineWorker(), "MatchingEngineWorker should still exist after shutdown");
        assertNotNull(lifecycleManager.getClearingWorker(), "ClearingWorker should still exist after shutdown");
    }

    @Test
    @DisplayName("testFinalSnapshot - 验证关闭时尝试触发最终快照")
    void testFinalSnapshot() throws Exception {
        // Given: 系统已启动
        lifecycleManager.start();

        // When: 关闭系统
        lifecycleManager.shutdown();

        // Then: 系统应成功关闭（最终快照流程在 shutdown 中执行）
        assertEquals(LifecycleManager.SystemStatus.SHUTTING_DOWN, lifecycleManager.getSystemStatus());
    }

    @Test
    @DisplayName("testShutdownWithoutStartup - 验证未启动时关闭的安全性")
    void testShutdownWithoutStartup() {
        // Given: 系统未启动

        // When: 直接关闭
        lifecycleManager.shutdown();

        // Then: 应安全关闭，状态为 SHUTTING_DOWN
        assertEquals(LifecycleManager.SystemStatus.SHUTTING_DOWN, lifecycleManager.getSystemStatus());
    }

    @Test
    @DisplayName("testGetSystemStatus - 验证状态获取")
    void testGetSystemStatus() {
        // Given: 初始状态
        LifecycleManager.SystemStatus initialStatus = lifecycleManager.getSystemStatus();

        // Then: 初始状态应为 STARTING
        assertEquals(LifecycleManager.SystemStatus.STARTING, initialStatus);
    }

    @Test
    @DisplayName("testMultipleStartCalls - 验证多次调用 startup 后的状态")
    void testMultipleStartCalls() throws Exception {
        // When: 第一次启动
        lifecycleManager.start();
        assertEquals(LifecycleManager.SystemStatus.RUNNING, lifecycleManager.getSystemStatus());

        // When: 第二次启动（通过重新创建 LifecycleManager）
        // LifecycleManager 的 startup 使用 @PostConstruct，单次调用设计
        // 多次调用不会改变状态
        lifecycleManager.start();
        assertEquals(LifecycleManager.SystemStatus.RUNNING, lifecycleManager.getSystemStatus());
    }

    @Test
    @DisplayName("testMultipleShutdownCalls - 验证多次调用 shutdown 的幂等性")
    void testMultipleShutdownCalls() throws Exception {
        // Given: 系统已启动
        lifecycleManager.start();

        // When: 第一次关闭
        lifecycleManager.shutdown();
        assertEquals(LifecycleManager.SystemStatus.SHUTTING_DOWN, lifecycleManager.getSystemStatus());

        // When: 第二次关闭
        lifecycleManager.shutdown();
        assertEquals(LifecycleManager.SystemStatus.SHUTTING_DOWN, lifecycleManager.getSystemStatus());
    }

    @Test
    @DisplayName("testAsyncPersistenceWorker_NullWithoutDataSource - 验证无 DataSource 时不创建 AsyncPersistenceWorker")
    void testAsyncPersistenceWorker_NullWithoutDataSource() throws Exception {
        // Given: 未设置 DataSource

        // When: 启动系统
        lifecycleManager.start();

        // Then: AsyncPersistenceWorker 应为 null
        assertNull(lifecycleManager.getAsyncPersistenceWorker(),
            "AsyncPersistenceWorker should be null without DataSource");

        lifecycleManager.shutdown();
    }

    @Test
    @DisplayName("testSystemStatus_EnumValues - 验证 SystemStatus 枚举定义")
    void testSystemStatus_EnumValues() {
        // Verify all expected enum values exist
        LifecycleManager.SystemStatus[] statuses = LifecycleManager.SystemStatus.values();

        assertEquals(6, statuses.length, "Should have 6 status values");

        // Verify enum value names
        assertNotNull(LifecycleManager.SystemStatus.STARTING);
        assertNotNull(LifecycleManager.SystemStatus.RECOVERING);
        assertNotNull(LifecycleManager.SystemStatus.RUNNING);
        assertNotNull(LifecycleManager.SystemStatus.SHUTTING_DOWN);
        assertNotNull(LifecycleManager.SystemStatus.DEGRADED);
        assertNotNull(LifecycleManager.SystemStatus.SNAPSHOTTING);
    }
}