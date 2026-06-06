package com.hft.trading.event;

import com.hft.trading.model.OrderSide;

/**
 * Settlement 事件（account_settlement CQ）
 *
 * 根据技术方案 V12 3.3.4：
 * SETTLEMENT 事件用于 AccountWorker 执行结算转账
 */
public class SettlementEvent {
    private final EventType eventType;
    private final long timestamp;

    // 结算ID（幂等检查用）
    private final String settlementId; // tradeId_orderId

    // 订单和账户信息
    private final long orderId;
    private final long accountId;
    private final long userId;
    private final String symbol;

    // 成交信息
    private final String tradeId;
    private final OrderSide settlementType; // BUYER / SELLER（内部用 side 区分）
    private final long price;
    private final long quantity;

    // 买方专用
    private final long frozenAmountUsed; // 本次使用的冻结金额
    private final long actualAmount;     // 实际扣款金额 = price × quantity
    private final long refundAmount;     // 退还差额 = frozenAmountUsed - actualAmount - commission
    private final long commission;       // 佣金

    // 卖方专用（frozenAmountUsed = quantity，单位不同）

    private SettlementEvent(String settlementId, long orderId, long accountId, long userId,
                            String symbol, String tradeId, OrderSide settlementType,
                            long price, long quantity, long frozenAmountUsed,
                            long actualAmount, long refundAmount, long commission) {
        this.eventType = EventType.SETTLEMENT;
        this.timestamp = System.currentTimeMillis();
        this.settlementId = settlementId;
        this.orderId = orderId;
        this.accountId = accountId;
        this.userId = userId;
        this.symbol = symbol;
        this.tradeId = tradeId;
        this.settlementType = settlementType;
        this.price = price;
        this.quantity = quantity;
        this.frozenAmountUsed = frozenAmountUsed;
        this.actualAmount = actualAmount;
        this.refundAmount = refundAmount;
        this.commission = commission;
    }

    /**
     * 创建买方结算事件
     */
    public static SettlementEvent forBuyer(String tradeId, long orderId, long accountId,
                                           long userId, String symbol, long price, long quantity,
                                           long frozenAmountUsed, long commission) {
        String settlementId = tradeId + "_" + orderId + "_BUY";
        long actualAmount = price * quantity;
        long refundAmount = frozenAmountUsed - actualAmount - commission;

        return new SettlementEvent(settlementId, orderId, accountId, userId, symbol, tradeId,
            OrderSide.BUY, price, quantity, frozenAmountUsed, actualAmount, refundAmount, commission);
    }

    /**
     * 创建卖方结算事件
     */
    public static SettlementEvent forSeller(String tradeId, long orderId, long accountId,
                                            long userId, String symbol, long price, long quantity,
                                            long commission) {
        String settlementId = tradeId + "_" + orderId + "_SELL";
        long actualAmount = price * quantity;

        return new SettlementEvent(settlementId, orderId, accountId, userId, symbol, tradeId,
            OrderSide.SELL, price, quantity, quantity, actualAmount, 0L, commission);
    }

    // ==================== Getter 方法 ====================

    public EventType getEventType() {
        return eventType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSettlementId() {
        return settlementId;
    }

    public long getOrderId() {
        return orderId;
    }

    public long getAccountId() {
        return accountId;
    }

    public long getUserId() {
        return userId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getTradeId() {
        return tradeId;
    }

    public OrderSide getSettlementType() {
        return settlementType;
    }

    public long getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public long getFrozenAmountUsed() {
        return frozenAmountUsed;
    }

    public long getActualAmount() {
        return actualAmount;
    }

    public long getRefundAmount() {
        return refundAmount;
    }

    public long getCommission() {
        return commission;
    }

    public boolean isBuyer() {
        return settlementType == OrderSide.BUY;
    }
}