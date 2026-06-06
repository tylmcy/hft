package com.hft.trading.store;

import com.hft.trading.model.Account;
import com.hft.trading.service.AlertService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账户内存存储
 *
 * 根据 V12 技术方案 3.2.1：
 * - 使用 ConcurrentHashMap<Long, Account> 存储
 * - 支持 freeze/unfreeze/settleBuy/settleSell/deposit 操作
 * - 每次操作后验证不变式
 */
public class InMemoryAccountStore {

    private final ConcurrentHashMap<Long, Account> accounts = new ConcurrentHashMap<>();
    private final AlertService alertService;

    public InMemoryAccountStore(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 添加账户
     */
    public void addAccount(Account account) {
        accounts.put(account.getAccountId(), account);
    }

    /**
     * 获取账户
     */
    public Account getAccount(long accountId) {
        return accounts.get(accountId);
    }

    /**
     * 移除账户
     */
    public void removeAccount(long accountId) {
        accounts.remove(accountId);
    }

    /**
     * 冻结资金
     *
     * @param accountId 账户ID
     * @param amount    冻结金额
     * @throws IllegalStateException 账户不存在/余额不足/状态异常
     */
    public void freeze(long accountId, long amount) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalStateException("账户不存在: " + accountId);
        }
        account.freeze(amount);
        validateInvariant(account);
    }

    /**
     * 解冻资金（静默处理不存在账户）
     *
     * @param accountId 账户ID
     * @param amount    解冻金额
     */
    public void unfreeze(long accountId, long amount) {
        Account account = accounts.get(accountId);
        if (account == null) {
            // 静默处理
            return;
        }
        account.unfreeze(amount);
        validateInvariant(account);
    }

    /**
     * 买方结算
     *
     * @param accountId     账户ID
     * @param frozenUsed    使用的冻结金额
     * @param actualAmount  实际成交金额
     * @param commission    佣金
     */
    public void settleBuy(long accountId, long frozenUsed, long actualAmount, long commission) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalStateException("账户不存在: " + accountId);
        }
        account.settleBuy(frozenUsed, actualAmount, commission);
        validateInvariant(account);
    }

    /**
     * 卖方结算
     *
     * @param accountId 账户ID
     * @param netIncome 净收入
     */
    public void settleSell(long accountId, long netIncome) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalStateException("账户不存在: " + accountId);
        }
        account.settleSell(netIncome);
        validateInvariant(account);
    }

    /**
     * 入金
     *
     * @param accountId 账户ID
     * @param amount    入金金额
     */
    public void deposit(long accountId, long amount) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalStateException("账户不存在: " + accountId);
        }
        account.deposit(amount);
        validateInvariant(account);
    }

    /**
     * 验证不变式
     */
    private void validateInvariant(Account account) {
        if (!account.validateInvariant()) {
            // 自动修复尝试（未来版本实现）
            // 当前版本：记录告警，标记 SUSPENDED
            account.suspend();
            alertService.sendP0Alert("InMemoryAccountStore",
                "Account invariant violation: accountId=" + account.getAccountId());
        }
    }

    /**
     * 获取所有账户数量
     */
    public int size() {
        return accounts.size();
    }

    /**
     * 获取所有账户
     */
    public List<Account> getAllAccounts() {
        return new ArrayList<>(accounts.values());
    }
}