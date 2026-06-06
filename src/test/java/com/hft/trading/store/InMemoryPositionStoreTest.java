package com.hft.trading.store;

import com.hft.trading.model.Position;
import com.hft.trading.service.AlertService;
import com.hft.trading.util.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryPositionStore 测试
 *
 * 根据 V12 技术方案 3.2.2：
 * - 使用 ConcurrentHashMap<String, Position> 存储，key=accountId:symbol
 * - 支持 freezePosition/unfreezePosition/settleBuyPosition/settleSellPosition 操作
 * - 每次操作后验证不变式
 */
class InMemoryPositionStoreTest {

    private InMemoryPositionStore positionStore;

    @BeforeEach
    void setUp() {
        AlertService alertService = TestUtils.createTestAlertService();
        positionStore = new InMemoryPositionStore(alertService);
    }

    @Test
    @DisplayName("应该能够添加和获取持仓")
    void shouldAddAndGetPosition() {
        Position position = new Position(1001L, "600000", 600000);

        positionStore.addPosition(position);

        Position retrieved = positionStore.getPosition(1001L, "600000");
        assertNotNull(retrieved);
        assertEquals(1001L, retrieved.getAccountId());
        assertEquals("600000", retrieved.getSymbol());
    }

    @Test
    @DisplayName("获取不存在的持仓应该返回 null")
    void shouldReturnNullForNonExistentPosition() {
        Position retrieved = positionStore.getPosition(9999L, "600000");
        assertNull(retrieved);
    }

    @Test
    @DisplayName("冻结持仓应该成功")
    void shouldFreezePositionSuccessfully() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        position.unlockT1();
        positionStore.addPosition(position);

        positionStore.freezePosition(1001L, "600000", 50L);

        Position updated = positionStore.getPosition(1001L, "600000");
        assertEquals(50L, updated.getAvailable());
        assertEquals(50L, updated.getFrozen());
        assertTrue(updated.validateInvariant());
    }

    @Test
    @DisplayName("冻结不存在持仓应该抛出异常")
    void shouldThrowWhenFreezeNonExistentPosition() {
        assertThrows(IllegalStateException.class,
            () -> positionStore.freezePosition(9999L, "600000", 50L));
    }

    @Test
    @DisplayName("冻结超过可用持仓应该抛出异常")
    void shouldThrowWhenFreezeExceedsAvailable() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        position.unlockT1();
        positionStore.addPosition(position);

        assertThrows(IllegalStateException.class,
            () -> positionStore.freezePosition(1001L, "600000", 200L));
    }

    @Test
    @DisplayName("解冻持仓应该成功")
    void shouldUnfreezePositionSuccessfully() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        position.unlockT1();
        position.freezePosition(50L);
        positionStore.addPosition(position);

        positionStore.unfreezePosition(1001L, "600000", 30L);

        Position updated = positionStore.getPosition(1001L, "600000");
        assertEquals(80L, updated.getAvailable());
        assertEquals(20L, updated.getFrozen());
        assertTrue(updated.validateInvariant());
    }

    @Test
    @DisplayName("解冻不存在的持仓应该静默处理")
    void shouldSilentlyHandleUnfreezeNonExistentPosition() {
        assertDoesNotThrow(() -> positionStore.unfreezePosition(9999L, "600000", 50L));
    }

    @Test
    @DisplayName("买方结算持仓应该增加持仓和T+1锁定")
    void shouldSettleBuyPositionCorrectly() {
        Position position = new Position(1001L, "600000", 600000);
        positionStore.addPosition(position);

        positionStore.settleBuyPosition(1001L, "600000", 100L, 105000L);

        Position updated = positionStore.getPosition(1001L, "600000");
        assertEquals(100L, updated.getQuantity());
        assertEquals(100L, updated.getT1Locked());
        assertEquals(105000L, updated.getAvgPrice());
        assertTrue(updated.validateInvariant());
    }

    @Test
    @DisplayName("买方结算不存在持仓应该自动创建")
    void shouldAutoCreatePositionOnSettleBuy() {
        // 不存在的持仓，买方结算应该自动创建
        positionStore.settleBuyPosition(1001L, "600000", 100L, 105000L);

        Position created = positionStore.getPosition(1001L, "600000");
        assertNotNull(created);
        assertEquals(1001L, created.getAccountId());
        assertEquals("600000", created.getSymbol());
        assertEquals(100L, created.getQuantity());
    }

    @Test
    @DisplayName("卖方结算持仓应该扣减持仓和冻结")
    void shouldSettleSellPositionCorrectly() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        position.unlockT1();
        position.freezePosition(50L);
        positionStore.addPosition(position);

        positionStore.settleSellPosition(1001L, "600000", 50L);

        Position updated = positionStore.getPosition(1001L, "600000");
        assertEquals(50L, updated.getQuantity());
        assertEquals(0L, updated.getFrozen());
        assertEquals(50L, updated.getAvailable());
        assertTrue(updated.validateInvariant());
    }

    @Test
    @DisplayName("卖方结算不存在持仓应该抛出异常")
    void shouldThrowWhenSettleSellNonExistentPosition() {
        assertThrows(IllegalStateException.class,
            () -> positionStore.settleSellPosition(9999L, "600000", 50L));
    }

    @Test
    @DisplayName("全部卖出后持仓应该被移除")
    void shouldRemovePositionAfterFullSell() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        position.unlockT1();
        position.freezePosition(100L);
        positionStore.addPosition(position);

        positionStore.settleSellPosition(1001L, "600000", 100L);

        // 全部卖出后持仓应该被移除
        assertNull(positionStore.getPosition(1001L, "600000"));
    }

    @Test
    @DisplayName("T+1 解锁应该正确执行")
    void shouldUnlockT1Correctly() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        positionStore.addPosition(position);

        positionStore.unlockT1(1001L, "600000");

        Position updated = positionStore.getPosition(1001L, "600000");
        assertEquals(100L, updated.getAvailable());
        assertEquals(0L, updated.getT1Locked());
        assertTrue(updated.validateInvariant());
    }

    @Test
    @DisplayName("批量解锁T+1应该正确执行")
    void shouldUnlockT1BatchCorrectly() {
        // 添加多个持仓
        Position pos1 = new Position(1001L, "600000", 600000);
        pos1.settleBuy(100L, 105000L);
        Position pos2 = new Position(1001L, "600001", 600001);
        pos2.settleBuy(200L, 110000L);
        positionStore.addPosition(pos1);
        positionStore.addPosition(pos2);

        // 批量解锁该账户的所有持仓
        positionStore.unlockT1ForAccount(1001L);

        Position updated1 = positionStore.getPosition(1001L, "600000");
        Position updated2 = positionStore.getPosition(1001L, "600001");
        assertEquals(100L, updated1.getAvailable());
        assertEquals(0L, updated1.getT1Locked());
        assertEquals(200L, updated2.getAvailable());
        assertEquals(0L, updated2.getT1Locked());
    }

    @Test
    @DisplayName("应该能够移除持仓")
    void shouldRemovePosition() {
        Position position = new Position(1001L, "600000", 600000);
        positionStore.addPosition(position);

        positionStore.removePosition(1001L, "600000");

        assertNull(positionStore.getPosition(1001L, "600000"));
    }

    @Test
    @DisplayName("应该支持并发访问")
    void shouldSupportConcurrentAccess() throws InterruptedException {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(1000L, 105000L);
        position.unlockT1();
        positionStore.addPosition(position);

        // 并发冻结和解冻
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                positionStore.freezePosition(1001L, "600000", 10L);
                positionStore.unfreezePosition(1001L, "600000", 10L);
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        Position updated = positionStore.getPosition(1001L, "600000");
        assertTrue(updated.validateInvariant());
        assertEquals(1000L, updated.getQuantity());
    }

    @Test
    @DisplayName("应该能够查询账户的所有持仓")
    void shouldGetAllPositionsForAccount() {
        Position pos1 = new Position(1001L, "600000", 600000);
        pos1.settleBuy(100L, 105000L);
        Position pos2 = new Position(1001L, "600001", 600001);
        pos2.settleBuy(200L, 110000L);
        Position pos3 = new Position(1002L, "600000", 600000); // 不同账户
        pos3.settleBuy(50L, 100000L);

        positionStore.addPosition(pos1);
        positionStore.addPosition(pos2);
        positionStore.addPosition(pos3);

        var positions = positionStore.getPositionsByAccount(1001L);
        assertEquals(2, positions.size());
        assertTrue(positions.stream().anyMatch(p -> p.getSymbol().equals("600000")));
        assertTrue(positions.stream().anyMatch(p -> p.getSymbol().equals("600001")));
    }
}