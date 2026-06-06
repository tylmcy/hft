package com.hft.trading.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Position（持仓）数据模型测试
 *
 * 根据 V12 技术方案 3.1.2：
 * - 不变式：|quantity| = available + frozen + t1Locked
 * - 支持多头（正数）和空头（负数）
 */
class PositionTest {

    @Test
    @DisplayName("创建持仓时应该正确初始化所有字段")
    void shouldInitializeAllFieldsWhenCreatingPosition() {
        long accountId = 1001L;
        String symbol = "600000";
        int symbolCode = 600000;

        Position position = new Position(accountId, symbol, symbolCode);

        assertEquals(accountId, position.getAccountId());
        assertEquals(symbol, position.getSymbol());
        assertEquals(symbolCode, position.getSymbolCode());
        assertEquals(0L, position.getQuantity());
        assertEquals(0L, position.getAvailable());
        assertEquals(0L, position.getFrozen());
        assertEquals(0L, position.getT1Locked());
        assertEquals(0L, position.getAvgPrice());
        assertEquals(0L, position.getUnrealizedPnl());
    }

    @Test
    @DisplayName("持仓不变式：|quantity| = available + frozen + t1Locked")
    void shouldMaintainPositionInvariant() {
        Position position = new Position(1001L, "600000", 600000);

        // 初始状态
        assertTrue(position.validateInvariant());

        // 买入成交后
        position.settleBuy(100L, 105000L); // 100股，成本价10.50元
        assertTrue(position.validateInvariant());
        assertEquals(100L, position.getQuantity());
        assertEquals(0L, position.getAvailable()); // T+1锁定
        assertEquals(100L, position.getT1Locked());
    }

    @Test
    @DisplayName("买入成交应该增加持仓和T+1锁定")
    void shouldSettleBuyCorrectly() {
        Position position = new Position(1001L, "600000", 600000);

        // 首次买入
        position.settleBuy(100L, 105000L);

        assertEquals(100L, position.getQuantity());
        assertEquals(0L, position.getAvailable());
        assertEquals(0L, position.getFrozen());
        assertEquals(100L, position.getT1Locked());
        assertEquals(105000L, position.getAvgPrice()); // 成本价 = 10.50元
        assertTrue(position.validateInvariant());

        // 继续买入，验证成本价计算
        position.settleBuy(100L, 110000L); // 以11.00元再买入100股

        assertEquals(200L, position.getQuantity());
        assertEquals(200L, position.getT1Locked());
        // 平均成本 = (100*10.50 + 100*11.00) / 200 = 10.75元
        assertEquals(107500L, position.getAvgPrice());
        assertTrue(position.validateInvariant());
    }

    @Test
    @DisplayName("卖出冻结应该正确扣减 available 并增加 frozen")
    void shouldFreezePositionCorrectly() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        position.unlockT1(); // 解锁T+1

        position.freezePosition(50L);

        assertEquals(50L, position.getAvailable());
        assertEquals(50L, position.getFrozen());
        assertEquals(100L, position.getQuantity());
        assertTrue(position.validateInvariant());
    }

    @Test
    @DisplayName("冻结超过可用持仓应该抛出异常")
    void shouldThrowWhenFreezeExceedsAvailable() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        position.unlockT1();

        assertThrows(IllegalStateException.class, () -> position.freezePosition(200L));
    }

    @Test
    @DisplayName("卖出成交应该扣减持仓和冻结")
    void shouldSettleSellCorrectly() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        position.unlockT1();
        position.freezePosition(50L);

        position.settleSell(50L);

        assertEquals(50L, position.getQuantity());
        assertEquals(0L, position.getFrozen());
        assertEquals(50L, position.getAvailable());
        assertTrue(position.validateInvariant());
    }

    @Test
    @DisplayName("全部卖出后持仓应该为零并可移除")
    void shouldHaveZeroPositionAfterFullSell() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        position.unlockT1();
        position.freezePosition(100L);

        position.settleSell(100L);

        assertEquals(0L, position.getQuantity());
        assertEquals(0L, position.getAvailable());
        assertEquals(0L, position.getFrozen());
        assertEquals(0L, position.getT1Locked());
        assertTrue(position.isEmpty());
        assertTrue(position.validateInvariant());
    }

    @Test
    @DisplayName("T+1 解锁应该将 t1Locked 转为 available")
    void shouldUnlockT1Correctly() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);

        position.unlockT1();

        assertEquals(100L, position.getAvailable());
        assertEquals(0L, position.getT1Locked());
        assertTrue(position.validateInvariant());
    }

    @Test
    @DisplayName("解冻持仓应该增加 available 并减少 frozen")
    void shouldUnfreezePositionCorrectly() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        position.unlockT1();
        position.freezePosition(50L);

        position.unfreezePosition(30L);

        assertEquals(80L, position.getAvailable());
        assertEquals(20L, position.getFrozen());
        assertTrue(position.validateInvariant());
    }

    @Test
    @DisplayName("解冻超过冻结数量应该抛出异常")
    void shouldThrowWhenUnfreezeExceedsFrozen() {
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 105000L);
        position.unlockT1();
        position.freezePosition(30L);

        assertThrows(IllegalStateException.class, () -> position.unfreezePosition(50L));
    }

    @Test
    @DisplayName("空头持仓应该支持负数数量")
    void shouldSupportShortPosition() {
        Position position = new Position(1001L, "600000", 600000);

        // 期货/期权空头建仓（未来功能）
        // 当前 A股版本不支持，但数据模型预留
        position.setQuantity(-100L);
        position.setAvailable(-100L);

        assertEquals(-100L, position.getQuantity());
        assertEquals(-100L, position.getAvailable());
        assertTrue(position.validateInvariant());
    }

    @Test
    @DisplayName("成本价计算：分批买入应该计算加权平均价")
    void shouldCalculateWeightedAvgPrice() {
        Position position = new Position(1001L, "600000", 600000);

        // 第一批：100股@10.00元
        position.settleBuy(100L, 100000L);
        assertEquals(100000L, position.getAvgPrice());

        // 第二批：200股@11.00元
        position.settleBuy(200L, 110000L);
        // 平均成本 = (100*100000 + 200*110000) / 300 = 106666.67 ≈ 106666 (floor)
        assertEquals(106666L, position.getAvgPrice());

        // 第三批：100股@10.50元
        position.settleBuy(100L, 105000L);
        // 平均成本 = (300*106666 + 100*105000) / 400 = 106249.5 ≈ 106249 (floor)
        assertEquals(106249L, position.getAvgPrice());
    }
}
