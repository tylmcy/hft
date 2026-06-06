package com.hft.trading.model;

/**
 * 撤单原因枚举
 */
public enum CancelReason {
    FOK_NOT_FILLABLE,              // FOK订单无法全部成交
    FAK_REMAINING_CANCELLED,       // FAK订单剩余部分撤销
    EOD_CANCEL,                    // 收盘撤销
    INSUFFICIENT_BALANCE,          // 余额不足
    ORDER_NOT_FOUND,               // 订单不存在
    ORDER_ALREADY_FILLED,          // 订单已成交
    ORDER_ALREADY_CANCELLED,       // 订单已撤销
    PENDING_ORDER_NOT_IN_ORDERBOOK, // PENDING订单不在订单簿
    USER_CANCEL                    // 用户主动撤单
}