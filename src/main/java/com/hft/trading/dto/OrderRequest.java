package com.hft.trading.dto;

import com.hft.trading.model.OrderSide;
import com.hft.trading.model.OrderType;

/**
 * 下单请求 DTO
 */
public class OrderRequest {
    private String accountId;
    private String symbol;
    private String side;       // BUY / SELL
    private String orderType;  // LIMIT / MARKET / FAK / FOK
    private String price;      // 限价单必填，市价单传"0"
    private String quantity;
    private String maxPrice;   // 可选，系统自动填充涨停价
    private String minPrice;   // 可选，系统自动填充跌停价
    private String idempotencyKey;

    // Getters
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public String getSide() { return side; }
    public String getOrderType() { return orderType; }
    public String getPrice() { return price; }
    public String getQuantity() { return quantity; }
    public String getMaxPrice() { return maxPrice; }
    public String getMinPrice() { return minPrice; }
    public String getIdempotencyKey() { return idempotencyKey; }

    // Setters
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public void setSide(String side) { this.side = side; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public void setPrice(String price) { this.price = price; }
    public void setQuantity(String quantity) { this.quantity = quantity; }
    public void setMaxPrice(String maxPrice) { this.maxPrice = maxPrice; }
    public void setMinPrice(String minPrice) { this.minPrice = minPrice; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    // 解析方法
    public Long getAccountIdAsLong() {
        return accountId != null ? Long.parseLong(accountId) : null;
    }

    public Long getPriceAsLong() {
        return price != null ? Long.parseLong(price) : 0L;
    }

    public Long getQuantityAsLong() {
        return quantity != null ? Long.parseLong(quantity) : null;
    }

    public Long getMaxPriceAsLong() {
        return maxPrice != null ? Long.parseLong(maxPrice) : 0L;
    }

    public Long getMinPriceAsLong() {
        return minPrice != null ? Long.parseLong(minPrice) : 0L;
    }

    public OrderSide getSideEnum() {
        return OrderSide.valueOf(side);
    }

    public OrderType getOrderTypeEnum() {
        return OrderType.valueOf(orderType);
    }
}