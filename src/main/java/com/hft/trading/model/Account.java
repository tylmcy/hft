package com.hft.trading.model;

/**
 * 账户数据模型
 *
 * 根据 V12 技术方案 3.1.1：
 * - 不变式：balance = available + frozen
 * - 不变式：frozen = marginFrozen + orderFrozen
 */
public class Account {
    private final long accountId;
    private final long userId;
    private long balance;
    private long available;
    private long frozen;
    private long marginFrozen;
    private long orderFrozen;
    private long debt;
    private long unrealizedPnl;
    private double riskRatio;
    private AccountStatus status;
    private long updateTime;

    public Account(long accountId, long userId) {
        this.accountId = accountId;
        this.userId = userId;
        this.balance = 0L;
        this.available = 0L;
        this.frozen = 0L;
        this.marginFrozen = 0L;
        this.orderFrozen = 0L;
        this.debt = 0L;
        this.unrealizedPnl = 0L;
        this.riskRatio = 0.0;
        this.status = AccountStatus.ACTIVE;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 从快照恢复账户
     * 用于快照读取时恢复完整账户状态
     */
    public static Account fromSnapshot(long accountId, long userId, long balance, long available,
                                        long frozen, long marginFrozen, long orderFrozen,
                                        AccountStatus status) {
        Account account = new Account(accountId, userId);
        account.balance = balance;
        account.available = available;
        account.frozen = frozen;
        account.marginFrozen = marginFrozen;
        account.orderFrozen = orderFrozen;
        account.status = status;
        account.updateTime = System.currentTimeMillis();
        return account;
    }

    public long getAccountId() {
        return accountId;
    }

    public long getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }

    public long getAvailable() {
        return available;
    }

    public long getFrozen() {
        return frozen;
    }

    public long getMarginFrozen() {
        return marginFrozen;
    }

    public long getOrderFrozen() {
        return orderFrozen;
    }

    public long getDebt() {
        return debt;
    }

    public long getUnrealizedPnl() {
        return unrealizedPnl;
    }

    public double getRiskRatio() {
        return riskRatio;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    /**
     * 验证不变式：balance = available + frozen
     * 且 frozen = marginFrozen + orderFrozen
     */
    public boolean validateInvariant() {
        return balance == available + frozen && frozen == marginFrozen + orderFrozen;
    }

    /**
     * 入金：增加 balance 和 available
     */
    public void deposit(long amount) {
        this.balance += amount;
        this.available += amount;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 冻结资金：扣减 available，增加 frozen 和 orderFrozen
     */
    public void freeze(long amount) {
        if (status == AccountStatus.SUSPENDED) {
            throw new IllegalStateException("账户已暂停，不允许冻结");
        }
        if (amount > available) {
            throw new IllegalStateException("余额不足，可用: " + available + ", 需要冻结: " + amount);
        }
        this.available -= amount;
        this.frozen += amount;
        this.orderFrozen += amount;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 解冻资金：增加 available，减少 frozen 和 orderFrozen
     */
    public void unfreeze(long amount) {
        if (amount > frozen) {
            throw new IllegalStateException("冻结金额不足，已冻结: " + frozen + ", 需要解冻: " + amount);
        }
        this.available += amount;
        this.frozen -= amount;
        this.orderFrozen -= amount;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 买方结算：
     * - 扣减冻结资金
     * - 扣减余额（实际成交金额 + 佣金）
     * - 退款到 available
     */
    public void settleBuy(long frozenUsed, long actualAmount, long commission) {
        // 扣减冻结
        this.frozen -= frozenUsed;
        this.orderFrozen -= frozenUsed;

        // 扣减余额
        long totalDeduction = actualAmount + commission;
        this.balance -= totalDeduction;

        // 退款
        long refund = frozenUsed - totalDeduction;
        if (refund > 0) {
            this.available += refund;
        }

        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 卖方结算：增加余额和可用资金
     */
    public void settleSell(long netIncome) {
        this.balance += netIncome;
        this.available += netIncome;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 暂停账户
     */
    public void suspend() {
        this.status = AccountStatus.SUSPENDED;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 激活账户
     */
    public void activate() {
        this.status = AccountStatus.ACTIVE;
        this.updateTime = System.currentTimeMillis();
    }
}