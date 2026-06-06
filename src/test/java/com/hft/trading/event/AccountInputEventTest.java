package com.hft.trading.event;

import com.hft.trading.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AccountInputEvent 测试
 *
 * 根据技术方案 V12 3.3.1：
 * account_input CQ 包含 NEW_ORDER/CANCEL_ORDER/SETTLEMENT/SNAPSHOT/PHASE_CHANGE/DEPOSIT/INSTRUMENT_INIT
 */
class AccountInputEventTest {

    @Test
    @DisplayName("创建 NEW_ORDER 事件应该正确设置所有字段")
    void shouldCreateNewOrderEvent() {
        long orderId = 123456789L;
        long accountId = 1001L;
        long userId = 2001L;
        String symbol = "600000";
        OrderSide side = OrderSide.BUY;
        OrderType orderType = OrderType.LIMIT;
        long price = 105000L;
        long quantity = 100L;
        long frozenAmount = 105032L;
        String idempotencyKey = "uuid-v4";

        AccountInputEvent event = AccountInputEvent.newOrder(
            orderId, accountId, userId, symbol, side, orderType,
            price, quantity, frozenAmount, idempotencyKey
        );

        assertEquals(EventType.NEW_ORDER, event.getEventType());
        assertEquals(orderId, event.getOrderId());
        assertEquals(accountId, event.getAccountId());
        assertEquals(userId, event.getUserId());
        assertEquals(symbol, event.getSymbol());
        assertEquals(side, event.getSide());
        assertEquals(orderType, event.getOrderType());
        assertEquals(price, event.getPrice());
        assertEquals(quantity, event.getQuantity());
        assertEquals(frozenAmount, event.getFrozenAmount());
        assertEquals(idempotencyKey, event.getIdempotencyKey());
        assertNotNull(event.getTimestamp());
    }

    @Test
    @DisplayName("创建 CANCEL_ORDER 事件应该正确设置字段")
    void shouldCreateCancelOrderEvent() {
        long orderId = 123456789L;
        long accountId = 1001L;
        CancelReason cancelReason = CancelReason.USER_CANCEL;

        AccountInputEvent event = AccountInputEvent.cancelOrder(orderId, accountId, cancelReason);

        assertEquals(EventType.CANCEL_ORDER, event.getEventType());
        assertEquals(orderId, event.getOrderId());
        assertEquals(accountId, event.getAccountId());
        assertEquals(cancelReason, event.getCancelReason());
    }

    @Test
    @DisplayName("创建 SNAPSHOT 事件应该正确设置字段")
    void shouldCreateSnapshotEvent() {
        long cqIndex = 12345L;

        AccountInputEvent event = AccountInputEvent.snapshot(cqIndex);

        assertEquals(EventType.SNAPSHOT, event.getEventType());
        assertEquals(cqIndex, event.getCqIndex());
    }

    @Test
    @DisplayName("创建 PHASE_CHANGE 事件应该正确设置字段")
    void shouldCreatePhaseChangeEvent() {
        TradingPhase phase = TradingPhase.CONTINUOUS_TRADING;

        AccountInputEvent event = AccountInputEvent.phaseChange(phase);

        assertEquals(EventType.PHASE_CHANGE, event.getEventType());
        assertEquals(phase, event.getPhase());
    }

    @Test
    @DisplayName("创建 DEPOSIT 事件应该正确设置字段")
    void shouldCreateDepositEvent() {
        String depositId = "deposit-001";
        long userId = 2001L;
        long accountId = 1001L;
        long amount = 10000000L; // 10万元
        String reason = "手动入金";
        long operatorId = 9999L;

        AccountInputEvent event = AccountInputEvent.deposit(
            depositId, userId, accountId, amount, reason, operatorId, false, null
        );

        assertEquals(EventType.DEPOSIT, event.getEventType());
        assertEquals(depositId, event.getDepositId());
        assertEquals(userId, event.getUserId());
        assertEquals(accountId, event.getAccountId());
        assertEquals(amount, event.getDepositAmount());
        assertEquals(reason, event.getDepositReason());
        assertEquals(operatorId, event.getOperatorId());
        assertFalse(event.isCorrection());
    }

    @Test
    @DisplayName("创建 DEPOSIT 纠正事件应该正确设置字段")
    void shouldCreateDepositCorrectionEvent() {
        String correctionId = "correction-001";
        long userId = 2001L;
        long accountId = 1001L;
        long amount = -50000L; // 负数表示扣回
        String reason = "纠正多入金错误";
        long operatorId = 9999L;
        String originalDepositId = "deposit-001";

        AccountInputEvent event = AccountInputEvent.deposit(
            correctionId, userId, accountId, amount, reason, operatorId, true, originalDepositId
        );

        assertEquals(EventType.DEPOSIT, event.getEventType());
        assertTrue(event.isCorrection());
        assertEquals(originalDepositId, event.getOriginalDepositId());
    }

    @Test
    @DisplayName("创建 INSTRUMENT_INIT 事件应该正确设置字段")
    void shouldCreateInstrumentInitEvent() {
        Instrument instrument = new Instrument("600000", 600000, InstrumentType.STOCK,
            "浦发银行", 105000L, 0.10, 0.10, 100L, 1L, 3);

        AccountInputEvent event = AccountInputEvent.instrumentInit(instrument);

        assertEquals(EventType.INSTRUMENT_INIT, event.getEventType());
        assertEquals(instrument, event.getInstrumentData());
    }

    @Test
    @DisplayName("市价单应该设置 maxPrice 和 minPrice")
    void shouldSetMaxPriceAndMinPriceForMarketOrder() {
        AccountInputEvent event = AccountInputEvent.newOrder(
            1L, 1001L, 2001L, "600000", OrderSide.BUY, OrderType.MARKET,
            0L, 100L, 115500L, "uuid"
        );

        event.setMaxPrice(115500L); // 涨停价
        event.setMinPrice(94500L);  // 跌停价

        assertEquals(115500L, event.getMaxPrice());
        assertEquals(94500L, event.getMinPrice());
    }

    @Test
    @DisplayName("系统订单应该标记 isSystemOrder")
    void shouldMarkSystemOrder() {
        AccountInputEvent event = AccountInputEvent.newOrder(
            1L, 1001L, 2001L, "600000", OrderSide.SELL, OrderType.MARKET,
            0L, 100L, 0L, null
        );

        event.setSystemOrder(true, "强平");

        assertTrue(event.isSystemOrder());
        assertEquals("强平", event.getSystemReason());
    }
}
