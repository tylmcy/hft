package com.hft.trading.model;

/**
 * 持仓数据模型
 *
 * 根据 V12 技术方案 3.1.2：
 * - 不变式：|quantity| = available + frozen + t1Locked
 * - 支持多头（正数）和空头（负数，期货/期权预留）
 */
public class Position {
    private final long accountId;
    private final String symbol;
    private final int symbolCode;
    private long quantity;
    private long available;
    private long frozen;
    private long t1Locked;
    private long avgPrice;
    private long unrealizedPnl;
    private long updateTime;

    public Position(long accountId, String symbol, int symbolCode) {
        this.accountId = accountId;
        this.symbol = symbol;
        this.symbolCode = symbolCode;
        this.quantity = 0L;
        this.available = 0L;
        this.frozen = 0L;
        this.t1Locked = 0L;
        this.avgPrice = 0L;
        this.unrealizedPnl = 0L;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 从快照恢复持仓
     * 用于快照读取时恢复完整持仓状态
     */
    public static Position fromSnapshot(long accountId, String symbol, int symbolCode,
                                         long quantity, long available, long frozen,
                                         long t1Locked, long avgPrice) {
        Position position = new Position(accountId, symbol, symbolCode);
        position.quantity = quantity;
        position.available = available;
        position.frozen = frozen;
        position.t1Locked = t1Locked;
        position.avgPrice = avgPrice;
        position.updateTime = System.currentTimeMillis();
        return position;
    }

    public long getAccountId() {
        return accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getSymbolCode() {
        return symbolCode;
    }

    public long getQuantity() {
        return quantity;
    }

    public long getAvailable() {
        return available;
    }

    public long getFrozen() {
        return frozen;
    }

    public long getT1Locked() {
        return t1Locked;
    }

    public long getAvgPrice() {
        return avgPrice;
    }

    public long getUnrealizedPnl() {
        return unrealizedPnl;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    /**
     * 验证不变式：|quantity| = available + frozen + t1Locked
     *
     * 注意：空头持仓时 available/frozen/t1Locked 都应为负数
     */
    public boolean validateInvariant() {
        return Math.abs(quantity) == Math.abs(available + frozen + t1Locked);
    }

    /**
     * 持仓是否为空（可移除）
     */
    public boolean isEmpty() {
        return quantity == 0 && available == 0 && frozen == 0 && t1Locked == 0;
    }

    /**
     * 买方结算：增加持仓和T+1锁定，更新成本价
     *
     * @param qty   成交数量
     * @param price 成交价格（分）
     */
    public void settleBuy(long qty, long price) {
        // 计算成交金额
        long tradeAmount = price * qty;

        // 更新成本价（加权平均，floor向下取整）
        long oldTotalCost = this.quantity * this.avgPrice;
        long newTotalCost = oldTotalCost + tradeAmount;
        this.quantity += qty;
        this.avgPrice = this.quantity == 0 ? 0 : newTotalCost / this.quantity; // floor

        // A股T+1锁定
        this.t1Locked += qty;

        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 卖方结算：扣减持仓和冻结
     *
     * @param qty 成交数量
     */
    public void settleSell(long qty) {
        this.quantity -= qty;
        this.frozen -= qty;

        // 清空时重置成本价
        if (this.quantity == 0) {
            this.avgPrice = 0;
        }

        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 冻结持仓：扣减 available，增加 frozen
     *
     * @param qty 冻结数量
     */
    public void freezePosition(long qty) {
        if (qty > available) {
            throw new IllegalStateException("可用持仓不足，可用: " + available + ", 需要冻结: " + qty);
        }
        this.available -= qty;
        this.frozen += qty;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 解冻持仓：增加 available，减少 frozen
     *
     * @param qty 解冻数量
     */
    public void unfreezePosition(long qty) {
        if (qty > frozen) {
            throw new IllegalStateException("冻结持仓不足，已冻结: " + frozen + ", 需要解冻: " + qty);
        }
        this.available += qty;
        this.frozen -= qty;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * T+1 解锁：将 t1Locked 转为 available
     */
    public void unlockT1() {
        this.available += this.t1Locked;
        this.t1Locked = 0;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 设置数量（期货/期权空头预留）
     */
    public void setQuantity(long quantity) {
        this.quantity = quantity;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 设置可用（期货/期权空头预留）
     */
    public void setAvailable(long available) {
        this.available = available;
        this.updateTime = System.currentTimeMillis();
    }
}