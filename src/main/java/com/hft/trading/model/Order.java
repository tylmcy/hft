package com.hft.trading.model;

/**
 * 订单数据模型
 *
 * 根据 V12 技术方案 3.1.3：
 * - 订单状态机：PENDING → NEW → PARTIAL_FILLED → FILLED/CANCELLED
 * - volatile 字段保证跨线程可见性
 */
public class Order {
    private final long orderId;
    private final String symbol;
    private final int symbolCode;
    private final OrderSide side;
    private final OrderType orderType;
    private final long price; // 市价单为0
    private final long quantity;
    private volatile long filledQuantity;
    private volatile long remainingQty;
    private volatile long frozenAmount;
    private long maxPrice; // 市价买单涨停价
    private long minPrice; // 市价卖单跌停价
    private final long accountId;
    private final long userId;
    private volatile OrderStatus status;
    private CancelReason cancelReason;
    private String idempotencyKey;
    private final long createTime;
    private volatile long updateTime;

    public Order(long orderId, String symbol, int symbolCode, OrderSide side, OrderType orderType,
                 long price, long quantity, long accountId, long userId) {
        this.orderId = orderId;
        this.symbol = symbol;
        this.symbolCode = symbolCode;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.quantity = quantity;
        this.accountId = accountId;
        this.userId = userId;

        this.filledQuantity = 0L;
        this.remainingQty = quantity;
        this.frozenAmount = 0L;
        this.maxPrice = 0L;
        this.minPrice = 0L;
        this.status = OrderStatus.PENDING;
        this.cancelReason = null;
        this.idempotencyKey = null;
        this.createTime = System.currentTimeMillis();
        this.updateTime = this.createTime;
    }

    public long getOrderId() {
        return orderId;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getSymbolCode() {
        return symbolCode;
    }

    public OrderSide getSide() {
        return side;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public long getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public long getRemainingQty() {
        return remainingQty;
    }

    public long getFrozenAmount() {
        return frozenAmount;
    }

    public long getMaxPrice() {
        return maxPrice;
    }

    public long getMinPrice() {
        return minPrice;
    }

    public long getAccountId() {
        return accountId;
    }

    public long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public CancelReason getCancelReason() {
        return cancelReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    /**
     * 更新订单状态（带状态机校验）
     */
    public void updateStatus(OrderStatus newStatus) {
        validateTransition(newStatus);
        this.status = newStatus;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 更新成交数量（自动更新状态）
     */
    public void updateFilledQuantity(long qty) {
        if (status == OrderStatus.CANCELLED || status == OrderStatus.REJECTED) {
            throw new IllegalStateException("订单已终止，无法更新成交数量: " + status);
        }

        this.filledQuantity += qty;
        this.remainingQty -= qty;

        // 自动更新状态
        if (this.filledQuantity == this.quantity) {
            this.status = OrderStatus.FILLED;
        } else if (this.filledQuantity > 0) {
            this.status = OrderStatus.PARTIAL_FILLED;
        }

        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 设置冻结金额
     */
    public void setFrozenAmount(long amount) {
        this.frozenAmount = amount;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 更新冻结金额（扣减）
     */
    public void updateFrozenAmount(long usedAmount) {
        this.frozenAmount -= usedAmount;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 设置最大价格（市价买单涨停价）
     */
    public void setMaxPrice(long maxPrice) {
        this.maxPrice = maxPrice;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 设置最小价格（市价卖单跌停价）
     */
    public void setMinPrice(long minPrice) {
        this.minPrice = minPrice;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 设置撤单原因
     */
    public void setCancelReason(CancelReason cancelReason) {
        this.cancelReason = cancelReason;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 设置幂等键
     */
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 验证状态转换是否合法
     */
    private void validateTransition(OrderStatus newStatus) {
        // 定义合法的状态转换
        boolean valid = switch (status) {
            case PENDING -> newStatus == OrderStatus.NEW ||
                           newStatus == OrderStatus.REJECTED ||
                           newStatus == OrderStatus.CANCELLED;
            case NEW -> newStatus == OrderStatus.PARTIAL_FILLED ||
                       newStatus == OrderStatus.FILLED ||
                       newStatus == OrderStatus.CANCELLED;
            case PARTIAL_FILLED -> newStatus == OrderStatus.PARTIAL_FILLED ||
                                  newStatus == OrderStatus.FILLED ||
                                  newStatus == OrderStatus.CANCELLED;
            case FILLED, CANCELLED, REJECTED -> false; // 终态，不允许转换
        };

        if (!valid) {
            throw new IllegalStateException(
                "非法状态转换: " + status + " → " + newStatus);
        }
    }
}