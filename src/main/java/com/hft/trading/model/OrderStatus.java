package com.hft.trading.model;

/**
 * 订单状态枚举
 *
 * 状态机：
 * PENDING → NEW → PARTIAL_FILLED → FILLED
 * PENDING → REJECTED
 * NEW/PARTIAL_FILLED → CANCELLED
 */
public enum OrderStatus {
    PENDING,          // 已入库，等待入OrderBook
    NEW,              // 已入OrderBook
    PARTIAL_FILLED,   // 部分成交
    FILLED,           // 全部成交
    CANCELLED,        // 已撤销
    REJECTED          // 已拒绝（冻结失败等）
}