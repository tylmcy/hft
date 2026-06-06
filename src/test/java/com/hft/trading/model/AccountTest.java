package com.hft.trading.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Account 数据模型测试
 *
 * 根据 V12 技术方案 3.1.1：
 * - 不变式：balance = available + frozen
 * - 不变式：frozen = marginFrozen + orderFrozen
 */
class AccountTest {

    @Test
    @DisplayName("创建账户时应该正确初始化所有字段")
    void shouldInitializeAllFieldsWhenCreatingAccount() {
        long accountId = 1001L;
        long userId = 2001L;

        Account account = new Account(accountId, userId);

        assertEquals(accountId, account.getAccountId());
        assertEquals(userId, account.getUserId());
        assertEquals(0L, account.getBalance());
        assertEquals(0L, account.getAvailable());
        assertEquals(0L, account.getFrozen());
        assertEquals(0L, account.getMarginFrozen());
        assertEquals(0L, account.getOrderFrozen());
        assertEquals(0L, account.getDebt());
        assertEquals(0L, account.getUnrealizedPnl());
        assertEquals(0.0, account.getRiskRatio());
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
    }

    @Test
    @DisplayName("余额不变式：balance = available + frozen")
    void shouldMaintainBalanceInvariant() {
        Account account = new Account(1001L, 2001L);

        // 初始状态
        assertTrue(account.validateInvariant());

        // 入金后
        account.deposit(100000L);
        assertTrue(account.validateInvariant());
        assertEquals(100000L, account.getBalance());
        assertEquals(100000L, account.getAvailable());
        assertEquals(0L, account.getFrozen());
    }

    @Test
    @DisplayName("冻结不变式：frozen = marginFrozen + orderFrozen")
    void shouldMaintainFrozenInvariant() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);

        // 冻结订单资金
        account.freeze(10000L);

        assertTrue(account.validateInvariant());
        assertEquals(10000L, account.getFrozen());
        assertEquals(10000L, account.getOrderFrozen());
        assertEquals(90000L, account.getAvailable());
    }

    @Test
    @DisplayName("冻结资金时应该正确扣减 available 并增加 frozen")
    void shouldFreezeCorrectly() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);

        account.freeze(30000L);

        assertEquals(70000L, account.getAvailable());
        assertEquals(30000L, account.getFrozen());
        assertEquals(30000L, account.getOrderFrozen());
        assertEquals(100000L, account.getBalance());
        assertTrue(account.validateInvariant());
    }

    @Test
    @DisplayName("解冻资金时应该正确增加 available 并减少 frozen")
    void shouldUnfreezeCorrectly() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);
        account.freeze(30000L);

        account.unfreeze(10000L);

        assertEquals(80000L, account.getAvailable());
        assertEquals(20000L, account.getFrozen());
        assertEquals(20000L, account.getOrderFrozen());
        assertEquals(100000L, account.getBalance());
        assertTrue(account.validateInvariant());
    }

    @Test
    @DisplayName("买方结算：扣减冻结，扣减余额，退款到 available")
    void shouldSettleBuyCorrectly() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);
        account.freeze(50000L); // 冻结 50000

        // 结算：实际使用 40000，佣金 120，退款 9880
        long frozenUsed = 50000L;
        long actualAmount = 40000L;
        long commission = 120L;
        long refund = frozenUsed - actualAmount - commission; // 9880

        account.settleBuy(frozenUsed, actualAmount, commission);

        // balance = 100000 - 40000 - 120 = 59880
        // frozen = 50000 - 50000 = 0
        // available = 50000 + 9880 = 59880
        assertEquals(59880L, account.getBalance());
        assertEquals(0L, account.getFrozen());
        assertEquals(59880L, account.getAvailable());
        assertTrue(account.validateInvariant());
    }

    @Test
    @DisplayName("卖方结算：增加余额和 available")
    void shouldSettleSellCorrectly() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);

        long netIncome = 50000L - 150L; // 成交金额 - 佣金

        account.settleSell(netIncome);

        assertEquals(149850L, account.getBalance());
        assertEquals(149850L, account.getAvailable());
        assertTrue(account.validateInvariant());
    }

    @Test
    @DisplayName("余额不足时冻结应该抛出异常")
    void shouldThrowWhenFreezeInsufficientBalance() {
        Account account = new Account(1001L, 2001L);
        account.deposit(10000L);

        assertThrows(IllegalStateException.class, () -> account.freeze(20000L));
    }

    @Test
    @DisplayName("冻结金额超过已冻结金额时解冻应该抛出异常")
    void shouldThrowWhenUnfreezeExceedsFrozen() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);
        account.freeze(10000L);

        assertThrows(IllegalStateException.class, () -> account.unfreeze(20000L));
    }

    @Test
    @DisplayName("账户状态应该正确转换")
    void shouldChangeAccountStatus() {
        Account account = new Account(1001L, 2001L);

        assertEquals(AccountStatus.ACTIVE, account.getStatus());

        account.suspend();
        assertEquals(AccountStatus.SUSPENDED, account.getStatus());

        account.activate();
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
    }

    @Test
    @DisplayName("SUSPENDED 账户不应该允许冻结")
    void shouldNotAllowFreezeWhenSuspended() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);
        account.suspend();

        assertThrows(IllegalStateException.class, () -> account.freeze(10000L));
    }
}
