package com.hft.trading.event;

import com.hft.trading.model.*;

/**
 * Account Input CQ 事件
 *
 * 根据技术方案 V12 3.3.1：
 * 包含 NEW_ORDER/CANCEL_ORDER/SETTLEMENT/SNAPSHOT/PHASE_CHANGE/DEPOSIT/INSTRUMENT_INIT
 */
public class AccountInputEvent {
    // 基础字段
    private final EventType eventType;
    private long timestamp;

    // 订单相关字段
    private Long orderId;
    private Long accountId;
    private Long userId;
    private String symbol;
    private OrderSide side;
    private OrderType orderType;
    private Long price;
    private Long quantity;
    private Long frozenAmount;
    private String idempotencyKey;
    private Long maxPrice;
    private Long minPrice;

    // 撤单相关
    private CancelReason cancelReason;

    // 快照相关
    private Long cqIndex;

    // 交易阶段
    private TradingPhase phase;

    // 入金相关
    private String depositId;
    private Long depositAmount;
    private Boolean isCorrection;
    private String originalDepositId;
    private Long operatorId;
    private String depositReason;

    // 系统订单
    private boolean isSystemOrder;
    private String systemReason;

    // Instrument 数据
    private Instrument instrumentData;

    private AccountInputEvent(EventType eventType) {
        this.eventType = eventType;
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建 NEW_ORDER 事件
     */
    public static AccountInputEvent newOrder(
            long orderId, long accountId, long userId, String symbol,
            OrderSide side, OrderType orderType, long price, long quantity,
            long frozenAmount, String idempotencyKey) {
        AccountInputEvent event = new AccountInputEvent(EventType.NEW_ORDER);
        event.orderId = orderId;
        event.accountId = accountId;
        event.userId = userId;
        event.symbol = symbol;
        event.side = side;
        event.orderType = orderType;
        event.price = price;
        event.quantity = quantity;
        event.frozenAmount = frozenAmount;
        event.idempotencyKey = idempotencyKey;
        return event;
    }

    /**
     * 创建 CANCEL_ORDER 事件
     */
    public static AccountInputEvent cancelOrder(long orderId, long accountId, CancelReason cancelReason) {
        AccountInputEvent event = new AccountInputEvent(EventType.CANCEL_ORDER);
        event.orderId = orderId;
        event.accountId = accountId;
        event.cancelReason = cancelReason;
        return event;
    }

    /**
     * 创建 SNAPSHOT 事件
     */
    public static AccountInputEvent snapshot(long cqIndex) {
        AccountInputEvent event = new AccountInputEvent(EventType.SNAPSHOT);
        event.cqIndex = cqIndex;
        return event;
    }

    /**
     * 创建 PHASE_CHANGE 事件
     */
    public static AccountInputEvent phaseChange(TradingPhase phase) {
        AccountInputEvent event = new AccountInputEvent(EventType.PHASE_CHANGE);
        event.phase = phase;
        return event;
    }

    /**
     * 创建 DEPOSIT 事件
     */
    public static AccountInputEvent deposit(
            String depositId, long userId, long accountId, long amount,
            String reason, long operatorId, boolean isCorrection, String originalDepositId) {
        AccountInputEvent event = new AccountInputEvent(EventType.DEPOSIT);
        event.depositId = depositId;
        event.userId = userId;
        event.accountId = accountId;
        event.depositAmount = amount;
        event.depositReason = reason;
        event.operatorId = operatorId;
        event.isCorrection = isCorrection;
        event.originalDepositId = originalDepositId;
        return event;
    }

    /**
     * 创建 INSTRUMENT_INIT 事件
     */
    public static AccountInputEvent instrumentInit(Instrument instrument) {
        AccountInputEvent event = new AccountInputEvent(EventType.INSTRUMENT_INIT);
        event.instrumentData = instrument;
        event.symbol = instrument.getSymbol();
        return event;
    }

    // ==================== Getter 方法 ====================

    public EventType getEventType() {
        return eventType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public Long getPrice() {
        return price;
    }

    public Long getQuantity() {
        return quantity;
    }

    public Long getFrozenAmount() {
        return frozenAmount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getMaxPrice() {
        return maxPrice;
    }

    public Long getMinPrice() {
        return minPrice;
    }

    public CancelReason getCancelReason() {
        return cancelReason;
    }

    public Long getCqIndex() {
        return cqIndex;
    }

    public TradingPhase getPhase() {
        return phase;
    }

    public String getDepositId() {
        return depositId;
    }

    public Long getDepositAmount() {
        return depositAmount;
    }

    public Boolean isCorrection() {
        return isCorrection;
    }

    public String getOriginalDepositId() {
        return originalDepositId;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public String getDepositReason() {
        return depositReason;
    }

    public boolean isSystemOrder() {
        return isSystemOrder;
    }

    public String getSystemReason() {
        return systemReason;
    }

    public Instrument getInstrumentData() {
        return instrumentData;
    }

    // ==================== Setter 方法 ====================

    public void setMaxPrice(long maxPrice) {
        this.maxPrice = maxPrice;
    }

    public void setMinPrice(long minPrice) {
        this.minPrice = minPrice;
    }

    public void setSystemOrder(boolean isSystemOrder, String systemReason) {
        this.isSystemOrder = isSystemOrder;
        this.systemReason = systemReason;
    }
}