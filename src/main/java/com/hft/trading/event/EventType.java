package com.hft.trading.event;

/**
 * 事件类型枚举
 *
 * 根据技术方案 V12 3.3 节，系统包含以下 CQ 事件类型：
 */
public enum EventType {
    // Account Input CQ 事件类型
    NEW_ORDER,
    CANCEL_ORDER,
    SETTLEMENT,
    SNAPSHOT,
    PHASE_CHANGE,
    DEPOSIT,
    INSTRUMENT_INIT,

    // Account Output CQ 事件类型
    FREEZE,
    UNFREEZE,
    BALANCE_CHANGE,
    POSITION_CHANGE,

    // Matching Output CQ 事件类型
    ORDER_UPDATE,
    TRADE,
    CANCEL_SUCCESS,
    CANCEL_REJECT;

    /**
     * Account Input CQ 事件类型
     */
    public static EventType[] getAccountInputTypes() {
        return new EventType[] {
            NEW_ORDER, CANCEL_ORDER, SETTLEMENT, SNAPSHOT, PHASE_CHANGE, DEPOSIT, INSTRUMENT_INIT
        };
    }

    /**
     * Account Output CQ 事件类型
     */
    public static EventType[] getAccountOutputTypes() {
        return new EventType[] {
            FREEZE, UNFREEZE, BALANCE_CHANGE, POSITION_CHANGE
        };
    }

    /**
     * Matching Input CQ 事件类型
     */
    public static EventType[] getMatchingInputTypes() {
        return new EventType[] {
            NEW_ORDER, CANCEL_ORDER, SNAPSHOT, INSTRUMENT_INIT
        };
    }

    /**
     * Matching Output CQ 事件类型
     */
    public static EventType[] getMatchingOutputTypes() {
        return new EventType[] {
            ORDER_UPDATE, TRADE, CANCEL_SUCCESS, CANCEL_REJECT
        };
    }

    /**
     * Account Settlement CQ 事件类型
     */
    public static EventType[] getAccountSettlementTypes() {
        return new EventType[] {
            SETTLEMENT, UNFREEZE
        };
    }
}