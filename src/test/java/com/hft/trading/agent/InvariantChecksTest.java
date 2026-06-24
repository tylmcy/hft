package com.hft.trading.agent;

import com.hft.trading.model.Account;
import com.hft.trading.model.AccountStatus;
import com.hft.trading.model.Order;
import com.hft.trading.model.OrderSide;
import com.hft.trading.model.OrderType;
import com.hft.trading.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 不变式检查测试
 *
 * 验证 ConsistencyAuditAgent 所依赖的核心不变式逻辑：
 * - Account: balance = available + frozen
 * - Account: frozen = marginFrozen + orderFrozen
 * - Position: |quantity| = available + frozen + t1Locked
 */
class InvariantChecksTest {

    // ────────────── 账户不变式 ──────────────

    @Test
    @DisplayName("新账户应满足不变式")
    void newAccountShouldSatisfyInvariant() {
        Account account = new Account(1L, 1L);
        assertTrue(account.validateInvariant());
    }

    @Test
    @DisplayName("入金后应满足不变式")
    void depositShouldMaintainInvariant() {
        Account account = new Account(1L, 1L);
        account.deposit(100000L);
        assertTrue(account.validateInvariant());
        assertEquals(100000L, account.getBalance());
        assertEquals(100000L, account.getAvailable());
        assertEquals(0L, account.getFrozen());
    }

    @Test
    @DisplayName("冻结后 balance 应等于 available + frozen")
    void freezeShouldMaintainInvariant() {
        Account account = new Account(1L, 1L);
        account.deposit(100000L);
        account.freeze(30000L);
        assertTrue(account.validateInvariant());
        assertEquals(100000L, account.getBalance());
        assertEquals(70000L, account.getAvailable());
        assertEquals(30000L, account.getFrozen());
        assertEquals(30000L, account.getOrderFrozen());
        assertEquals(0L, account.getMarginFrozen());
    }

    @Test
    @DisplayName("解冻后应恢复不变式")
    void unfreezeShouldMaintainInvariant() {
        Account account = new Account(1L, 1L);
        account.deposit(100000L);
        account.freeze(30000L);
        account.unfreeze(10000L);
        assertTrue(account.validateInvariant());
        assertEquals(100000L, account.getBalance());
        assertEquals(80000L, account.getAvailable());
        assertEquals(20000L, account.getFrozen());
    }

    @Test
    @DisplayName("买方结算后应满足不变式")
    void settleBuyShouldMaintainInvariant() {
        Account account = new Account(1L, 1L);
        account.deposit(100000L);
        account.freeze(50000L);
        // 实际成交 40000，佣金 15
        account.settleBuy(50000L, 40000L, 15L);
        assertTrue(account.validateInvariant());
        // balance = 100000 - 40000 - 15 = 59985
        assertEquals(59985L, account.getBalance());
        // refund = 50000 - 40000 - 15 = 9985
        assertEquals(59985L, account.getAvailable());
        assertEquals(0L, account.getFrozen());
    }

    @Test
    @DisplayName("卖方结算后应满足不变式")
    void settleSellShouldMaintainInvariant() {
        Account account = new Account(1L, 1L);
        account.deposit(100000L);
        account.settleSell(50000L);
        assertTrue(account.validateInvariant());
        assertEquals(150000L, account.getBalance());
        assertEquals(150000L, account.getAvailable());
    }

    @Test
    @DisplayName("冻结金额分割不变式：frozen = marginFrozen + orderFrozen")
    void frozenSplitInvariant() {
        Account account = new Account(1L, 1L);
        account.deposit(100000L);
        account.freeze(30000L);
        // 当前只有 orderFrozen
        assertEquals(0L, account.getMarginFrozen());
        assertEquals(30000L, account.getOrderFrozen());
        assertEquals(30000L, account.getFrozen());
        assertTrue(account.validateInvariant());
    }

    // ────────────── 持仓不变式 ──────────────

    @Test
    @DisplayName("新持仓应满足不变式")
    void newPositionShouldSatisfyInvariant() {
        Position position = new Position(1L, "600000", 600000);
        assertTrue(position.validateInvariant());
    }

    @Test
    @DisplayName("买方结算后持仓不变式应成立")
    void settleBuyPositionShouldMaintainInvariant() {
        Position position = new Position(1L, "600000", 600000);
        position.settleBuy(1000, 1500L);
        assertTrue(position.validateInvariant());
        assertEquals(1000, position.getQuantity());
        assertEquals(0, position.getAvailable());
        assertEquals(0, position.getFrozen());
        assertEquals(1000, position.getT1Locked());
        // 1000 = 0 + 0 + 1000 ✓
    }

    @Test
    @DisplayName("冻结持仓后不变式应成立")
    void freezePositionShouldMaintainInvariant() {
        Position position = new Position(1L, "600000", 600000);
        position.settleBuy(1000, 1500L);
        position.unlockT1(); // T+1 解锁: t1Locked → available
        position.freezePosition(300);
        assertTrue(position.validateInvariant());
        assertEquals(1000, position.getQuantity());
        assertEquals(700, position.getAvailable());
        assertEquals(300, position.getFrozen());
        assertEquals(0, position.getT1Locked());
        // 1000 = 700 + 300 + 0 ✓
    }

    @Test
    @DisplayName("卖方结算后持仓不变式应成立或持仓已空")
    void settleSellPositionShouldMaintainInvariant() {
        Position position = new Position(1L, "600000", 600000);
        position.settleBuy(1000, 1500L);
        position.unlockT1();
        position.freezePosition(500);
        position.settleSell(500);
        // 卖出后: quantity=500, available=500, frozen=0, t1Locked=0
        assertTrue(position.validateInvariant() || position.isEmpty());
    }

    @Test
    @DisplayName("T+1 解锁后不变式应成立")
    void unlockT1ShouldMaintainInvariant() {
        Position position = new Position(1L, "600000", 600000);
        position.settleBuy(500, 1000L);
        assertEquals(500, position.getT1Locked());
        position.unlockT1();
        assertTrue(position.validateInvariant());
        assertEquals(500, position.getAvailable());
        assertEquals(0, position.getT1Locked());
        // 500 = 500 + 0 + 0 ✓
    }
}
