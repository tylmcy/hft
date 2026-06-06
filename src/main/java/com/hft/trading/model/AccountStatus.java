package com.hft.trading.model;

/**
 * 账户状态枚举
 */
public enum AccountStatus {
    ACTIVE,           // 正常活跃
    SUSPENDED,        // 暂停（异常场景）
    LIQUIDATING,      // 强平中（期货/期权）
    NEGATIVE_BALANCE, // 余额为负
    CLOSED            // 已关闭
}