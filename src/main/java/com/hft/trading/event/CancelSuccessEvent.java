package com.hft.trading.event;

import com.hft.trading.model.CancelReason;
import com.hft.trading.model.OrderSide;

/**
 * CancelSuccess 事件（matching_output CQ）
 *
 * 根据技术方案 V12 3.3.3：
 * CANCEL_SUCCESS 事件表示撤单成功，触发 ClearingWorker 生成 UNFREEZE
 */
public class CancelSuccessEvent {
    private final EventType eventType;
    private final long timestamp;

    // 订单信息
    private final long orderId;
    private final long accountId;
    private final long userId;
    private final String symbol;
    private final OrderSide side;

    // 撤单信息
    private final long filledQuantity;   // 已成交数量
    private final long remainingQty;     // 剩余数量
    private final CancelReason cancelReason;

    // 解冻信息
    private final long unfreezeAmount;   // 解冻金额（买方）
    private final long unfreezeQty;      // 解冻数量（卖方）

    private CancelSuccessEvent(long orderId, long accountId, long userId, String symbol,
                               OrderSide side, long filledQuantity, long remainingQty,
                               CancelReason cancelReason, long unfreezeAmount, long unfreezeQty) {
        this.eventType = EventType.CANCEL_SUCCESS;
        this.timestamp = System.currentTimeMillis();
        this.orderId = orderId;
        this.accountId = accountId;
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.filledQuantity = filledQuantity;
        this.remainingQty = remainingQty;
        this.cancelReason = cancelReason;
        this.unfreezeAmount = unfreezeAmount;
        this.unfreezeQty = unfreezeQty;
    }

    /**
     * 创建买单撤单成功事件
     */
    public static CancelSuccessEvent forBuyOrder(long orderId, long accountId, long userId,
                                                  String symbol, long filledQuantity, long remainingQty,
                                                  CancelReason cancelReason, long remainingFrozen) {
        return new CancelSuccessEvent(orderId, accountId, userId, symbol, OrderSide.BUY,
            filledQuantity, remainingQty, cancelReason, remainingFrozen, 0L);
    }

    /**
     * 创建卖单撤单成功事件
     */
    public static CancelSuccessEvent forSellOrder(long orderId, long accountId, long userId,
                                                   String symbol, long filledQuantity, long remainingQty,
                                                   CancelReason cancelReason, long remainingQtyToUnfreeze) {
        return new CancelSuccessEvent(orderId, accountId, userId, symbol, OrderSide.SELL,
            filledQuantity, remainingQty, cancelReason, 0L, remainingQtyToUnfreeze);
    }

    // ==================== Getter 方法 ====================

    public EventType getEventType() {
        return eventType;
    }

    public long getTimestamp() {
        return timestamp;
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

    public OrderSide getSide() {
        return side;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public long getRemainingQty() {
        return remainingQty;
    }

    public CancelReason getCancelReason() {
        return cancelReason;
    }

    public long getUnfreezeAmount() {
        return unfreezeAmount;
    }

    public long getUnfreezeQty() {
        return unfreezeQty;
    }

    public boolean isBuyOrder() {
        return side == OrderSide.BUY;
    }
}