package com.hft.trading.model;

/**
 * 订单类型枚举
 */
public enum OrderType {
    LIMIT,   // 限价单
    MARKET,  // 市价单
    FAK,     // Fill-and-Kill（立即成交剩余撤销）
    FOK      // Fill-or-Kill（全部成交或全部撤销）
}