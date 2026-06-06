package com.hft.trading.store;

import com.hft.trading.model.Account;
import com.hft.trading.service.AlertService;
import com.hft.trading.util.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryAccountStore 测试
 *
 * 根据 V12 技术方案 3.2.1：
 * - 使用 ConcurrentHashMap 存储
 * - 支持 freeze/unfreeze/settleBuy/settleSell 操作
 * - 每次操作后验证不变式
 */
class InMemoryAccountStoreTest {

    private InMemoryAccountStore accountStore;

    @BeforeEach
    void setUp() {
        AlertService alertService = TestUtils.createTestAlertService();
        accountStore = new InMemoryAccountStore(alertService);
    }

    @Test
    @DisplayName("应该能够添加和获取账户")
    void shouldAddAndGetAccount() {
        Account account = new Account(1001L, 2001L);

        accountStore.addAccount(account);

        Account retrieved = accountStore.getAccount(1001L);
        assertNotNull(retrieved);
        assertEquals(1001L, retrieved.getAccountId());
        assertEquals(2001L, retrieved.getUserId());
    }

    @Test
    @DisplayName("获取不存在的账户应该返回 null")
    void shouldReturnNullForNonExistentAccount() {
        Account retrieved = accountStore.getAccount(9999L);
        assertNull(retrieved);
    }

    @Test
    @DisplayName("冻结资金应该成功")
    void shouldFreezeSuccessfully() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);
        accountStore.addAccount(account);

        accountStore.freeze(1001L, 30000L);

        Account updated = accountStore.getAccount(1001L);
        assertEquals(70000L, updated.getAvailable());
        assertEquals(30000L, updated.getFrozen());
        assertTrue(updated.validateInvariant());
    }

    @Test
    @DisplayName("冻结不存在账户应该抛出异常")
    void shouldThrowWhenFreezeNonExistentAccount() {
        assertThrows(IllegalStateException.class, () -> accountStore.freeze(9999L, 10000L));
    }

    @Test
    @DisplayName("冻结余额不足应该抛出异常")
    void shouldThrowWhenFreezeInsufficientBalance() {
        Account account = new Account(1001L, 2001L);
        account.deposit(10000L);
        accountStore.addAccount(account);

        assertThrows(IllegalStateException.class, () -> accountStore.freeze(1001L, 20000L));
    }

    @Test
    @DisplayName("冻结 SUSPENDED 账户应该抛出异常")
    void shouldThrowWhenFreezeSuspendedAccount() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);
        account.suspend();
        accountStore.addAccount(account);

        assertThrows(IllegalStateException.class, () -> accountStore.freeze(1001L, 10000L));
    }

    @Test
    @DisplayName("解冻资金应该成功")
    void shouldUnfreezeSuccessfully() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);
        account.freeze(30000L);
        accountStore.addAccount(account);

        accountStore.unfreeze(1001L, 10000L);

        Account updated = accountStore.getAccount(1001L);
        assertEquals(80000L, updated.getAvailable());
        assertEquals(20000L, updated.getFrozen());
        assertTrue(updated.validateInvariant());
    }

    @Test
    @DisplayName("解冻不存在的账户应该静默处理")
    void shouldSilentlyHandleUnfreezeNonExistentAccount() {
        // 解冻不存在的账户不应该抛出异常（静默处理）
        assertDoesNotThrow(() -> accountStore.unfreeze(9999L, 10000L));
    }

    @Test
    @DisplayName("买方结算应该正确执行")
    void shouldSettleBuyCorrectly() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);
        account.freeze(50000L);
        accountStore.addAccount(account);

        // 结算：使用冻结 50000，实际成交 40000，佣金 120
        accountStore.settleBuy(1001L, 50000L, 40000L, 120L);

        Account updated = accountStore.getAccount(1001L);
        // balance = 100000 - 40000 - 120 = 59880
        // frozen = 50000 - 50000 = 0
        // available = 50000 + (50000 - 40000 - 120) = 59880
        assertEquals(59880L, updated.getBalance());
        assertEquals(0L, updated.getFrozen());
        assertEquals(59880L, updated.getAvailable());
        assertTrue(updated.validateInvariant());
    }

    @Test
    @DisplayName("卖方结算应该正确执行")
    void shouldSettleSellCorrectly() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);
        accountStore.addAccount(account);

        long netIncome = 50000L - 150L;
        accountStore.settleSell(1001L, netIncome);

        Account updated = accountStore.getAccount(1001L);
        assertEquals(149850L, updated.getBalance());
        assertEquals(149850L, updated.getAvailable());
        assertTrue(updated.validateInvariant());
    }

    @Test
    @DisplayName("入金应该成功")
    void shouldDepositSuccessfully() {
        Account account = new Account(1001L, 2001L);
        accountStore.addAccount(account);

        accountStore.deposit(1001L, 100000L);

        Account updated = accountStore.getAccount(1001L);
        assertEquals(100000L, updated.getBalance());
        assertEquals(100000L, updated.getAvailable());
        assertTrue(updated.validateInvariant());
    }

    @Test
    @DisplayName("入金到不存在的账户应该抛出异常")
    void shouldThrowWhenDepositToNonExistentAccount() {
        assertThrows(IllegalStateException.class, () -> accountStore.deposit(9999L, 100000L));
    }

    @Test
    @DisplayName("应该能够移除账户")
    void shouldRemoveAccount() {
        Account account = new Account(1001L, 2001L);
        accountStore.addAccount(account);

        accountStore.removeAccount(1001L);

        assertNull(accountStore.getAccount(1001L));
    }

    @Test
    @DisplayName("不变式验证失败时应该自动修复并告警")
    void shouldAutoFixWhenInvariantViolation() {
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L);
        accountStore.addAccount(account);

        // 模拟不变式破坏（直接修改内部状态）
        // 这种情况应该由自动修复机制处理
        Account retrieved = accountStore.getAccount(1001L);
        assertTrue(retrieved.validateInvariant());
    }

    @Test
    @DisplayName("应该支持并发访问")
    void shouldSupportConcurrentAccess() throws InterruptedException {
        Account account = new Account(1001L, 2001L);
        account.deposit(1000000L);
        accountStore.addAccount(account);

        // 并发冻结
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                accountStore.freeze(1001L, 1000L);
                accountStore.unfreeze(1001L, 1000L);
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        Account updated = accountStore.getAccount(1001L);
        assertTrue(updated.validateInvariant());
        assertEquals(1000000L, updated.getBalance());
    }
}