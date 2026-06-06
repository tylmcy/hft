package com.hft.trading.event;

/**
 * 交易阶段枚举
 *
 * 根据技术方案 V12 14.1 节
 */
public enum TradingPhase {
    CLOSED,              // 收盘（09:15前, 15:00后, 非交易日）
    PRE_MARKET,          // 盘前（09:15-09:30，集合竞价，首版不支持）
    CONTINUOUS_TRADING,  // 连续竞价（09:30-11:30, 13:00-15:00）
    LUNCH_BREAK          // 午休（11:30-13:00）
}