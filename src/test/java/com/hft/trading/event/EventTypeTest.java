package com.hft.trading.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件类型枚举测试
 */
class EventTypeTest {

    @Test
    @DisplayName("AccountInputEventType 应该包含所有必需的事件类型")
    void shouldHaveAllAccountInputEventTypes() {
        EventType[] types = EventType.getAccountInputTypes();

        assertTrue(contains(types, EventType.NEW_ORDER));
        assertTrue(contains(types, EventType.CANCEL_ORDER));
        assertTrue(contains(types, EventType.SETTLEMENT));
        assertTrue(contains(types, EventType.SNAPSHOT));
        assertTrue(contains(types, EventType.PHASE_CHANGE));
        assertTrue(contains(types, EventType.DEPOSIT));
        assertTrue(contains(types, EventType.INSTRUMENT_INIT));
    }

    @Test
    @DisplayName("AccountOutputEventType 应该包含所有必需的事件类型")
    void shouldHaveAllAccountOutputEventTypes() {
        EventType[] types = EventType.getAccountOutputTypes();

        assertTrue(contains(types, EventType.FREEZE));
        assertTrue(contains(types, EventType.UNFREEZE));
        assertTrue(contains(types, EventType.BALANCE_CHANGE));
        assertTrue(contains(types, EventType.POSITION_CHANGE));
    }

    @Test
    @DisplayName("MatchingInputEventType 应该包含所有必需的事件类型")
    void shouldHaveAllMatchingInputEventTypes() {
        EventType[] types = EventType.getMatchingInputTypes();

        assertTrue(contains(types, EventType.NEW_ORDER));
        assertTrue(contains(types, EventType.CANCEL_ORDER));
        assertTrue(contains(types, EventType.SNAPSHOT));
        assertTrue(contains(types, EventType.INSTRUMENT_INIT));
    }

    @Test
    @DisplayName("MatchingOutputEventType 应该包含所有必需的事件类型")
    void shouldHaveAllMatchingOutputEventTypes() {
        EventType[] types = EventType.getMatchingOutputTypes();

        assertTrue(contains(types, EventType.ORDER_UPDATE));
        assertTrue(contains(types, EventType.TRADE));
        assertTrue(contains(types, EventType.CANCEL_SUCCESS));
        assertTrue(contains(types, EventType.CANCEL_REJECT));
    }

    @Test
    @DisplayName("AccountSettlementEventType 应该包含所有必需的事件类型")
    void shouldHaveAllAccountSettlementEventTypes() {
        EventType[] types = EventType.getAccountSettlementTypes();

        assertTrue(contains(types, EventType.SETTLEMENT));
        assertTrue(contains(types, EventType.UNFREEZE));
    }

    private boolean contains(EventType[] types, EventType type) {
        for (EventType t : types) {
            if (t == type) return true;
        }
        return false;
    }
}
