package com.hft.trading.queue;

import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.EventType;
import com.hft.trading.event.TradingPhase;
import com.hft.trading.model.Instrument;
import com.hft.trading.model.InstrumentType;
import com.hft.trading.model.OrderSide;
import com.hft.trading.model.OrderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MatchingInputQueue 单元测试
 *
 * 测试范围：
 * - NEW_ORDER 事件写入与读取
 * - CANCEL_ORDER 事件写入与读取
 * - SNAPSHOT 事件写入与读取
 * - INSTRUMENT_INIT 事件写入与读取
 * - 批量读取
 */
class MatchingInputQueueTest {

    @TempDir
    Path tempDir;

    private MatchingInputQueue queue;
    private String basePath;

    @BeforeEach
    void setUp() {
        basePath = tempDir.toString() + "/";
        queue = new MatchingInputQueue(basePath, 50, 10);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    @Test
    void testWriteNewOrderEvent() {
        AccountInputEvent event = AccountInputEvent.newOrder(
            1001L,  // orderId
            2001L,  // accountId
            3001L,  // userId
            "600000",  // symbol
            OrderSide.BUY,
            OrderType.LIMIT,
            100000L,  // price
            100L,     // quantity
            10000000L,  // frozenAmount
            "idempotency-key-001"
        );

        long index = queue.writeEvent(event);
        assertTrue(index > 0, "Index should be positive after write");

        List<AccountInputEvent> events = queue.readBatch();
        assertEquals(1, events.size());

        AccountInputEvent readEvent = events.get(0);
        assertEquals(EventType.NEW_ORDER, readEvent.getEventType());
        assertEquals(1001L, readEvent.getOrderId());
        assertEquals("600000", readEvent.getSymbol());
        assertEquals(OrderSide.BUY, readEvent.getSide());
        assertEquals(OrderType.LIMIT, readEvent.getOrderType());
    }

    @Test
    void testWriteCancelOrderEvent() {
        AccountInputEvent event = AccountInputEvent.cancelOrder(
            1001L,  // orderId
            2001L,  // accountId
            null    // cancelReason
        );

        long index = queue.writeEvent(event);
        assertTrue(index > 0);

        List<AccountInputEvent> events = queue.readBatch();
        assertEquals(1, events.size());

        AccountInputEvent readEvent = events.get(0);
        assertEquals(EventType.CANCEL_ORDER, readEvent.getEventType());
        assertEquals(1001L, readEvent.getOrderId());
        assertEquals(2001L, readEvent.getAccountId());
    }

    @Test
    void testWriteSnapshotEvent() {
        AccountInputEvent event = AccountInputEvent.snapshot(12345678L);

        long index = queue.writeEvent(event);
        assertTrue(index > 0);

        List<AccountInputEvent> events = queue.readBatch();
        assertEquals(1, events.size());

        AccountInputEvent readEvent = events.get(0);
        assertEquals(EventType.SNAPSHOT, readEvent.getEventType());
        assertEquals(12345678L, readEvent.getCqIndex());
    }

    @Test
    void testWritePhaseChangeEvent() {
        AccountInputEvent event = AccountInputEvent.phaseChange(TradingPhase.CONTINUOUS_TRADING);

        long index = queue.writeEvent(event);
        assertTrue(index > 0);

        List<AccountInputEvent> events = queue.readBatch();
        assertEquals(1, events.size());

        AccountInputEvent readEvent = events.get(0);
        assertEquals(EventType.PHASE_CHANGE, readEvent.getEventType());
        assertEquals(TradingPhase.CONTINUOUS_TRADING, readEvent.getPhase());
    }

    @Test
    void testWriteInstrumentInitEvent() {
        Instrument instrument = new Instrument(
            "600000",  // symbol
            600000,    // symbolCode
            InstrumentType.STOCK,
            "浦发银行",
            100000L,   // prevClose (1000.00 yuan)
            0.1,       // limitUpRatio (10%)
            0.1,       // limitDownRatio (10%)
            100L,      // lotSize
            1L,        // minPriceIncrement
            3          // commissionRate (万分之三)
        );

        AccountInputEvent event = AccountInputEvent.instrumentInit(instrument);

        long index = queue.writeEvent(event);
        assertTrue(index > 0);

        List<AccountInputEvent> events = queue.readBatch();
        assertEquals(1, events.size());

        AccountInputEvent readEvent = events.get(0);
        assertEquals(EventType.INSTRUMENT_INIT, readEvent.getEventType());
        assertEquals("600000", readEvent.getSymbol());

        Instrument readInstrument = readEvent.getInstrumentData();
        assertNotNull(readInstrument);
        assertEquals("600000", readInstrument.getSymbol());
        assertEquals("浦发银行", readInstrument.getName());
        assertEquals(100000L, readInstrument.getPrevClose());
    }

    @Test
    void testBatchRead() {
        // 写入多个不同类型的事件
        queue.writeEvent(AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        ));
        queue.writeEvent(AccountInputEvent.newOrder(
            1002L, 2001L, 3001L, "600000",
            OrderSide.SELL, OrderType.LIMIT, 101000L, 100L, 0L, "key-2"
        ));
        queue.writeEvent(AccountInputEvent.cancelOrder(1001L, 2001L, null));
        queue.writeEvent(AccountInputEvent.snapshot(99999L));

        List<AccountInputEvent> events = queue.readBatch();
        assertEquals(4, events.size());

        assertEquals(EventType.NEW_ORDER, events.get(0).getEventType());
        assertEquals(1001L, events.get(0).getOrderId());

        assertEquals(EventType.NEW_ORDER, events.get(1).getEventType());
        assertEquals(1002L, events.get(1).getOrderId());

        assertEquals(EventType.CANCEL_ORDER, events.get(2).getEventType());
        assertEquals(1001L, events.get(2).getOrderId());

        assertEquals(EventType.SNAPSHOT, events.get(3).getEventType());
    }

    @Test
    void testGetLastIndex() {
        // lastIndexAppended() throws exception if nothing written yet
        // First write an event
        long index1 = queue.writeEvent(AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        ));
        assertEquals(index1, queue.getLastIndex());

        long index2 = queue.writeEvent(AccountInputEvent.newOrder(
            1002L, 2001L, 3001L, "600001",
            OrderSide.SELL, OrderType.LIMIT, 101000L, 100L, 0L, "key-2"
        ));
        assertEquals(index2, queue.getLastIndex());
    }

    @Test
    void testGetCurrentIndex() {
        queue.writeEvent(AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        ));

        long beforeRead = queue.getCurrentIndex();
        queue.readBatch();
        long afterRead = queue.getCurrentIndex();

        assertTrue(afterRead >= beforeRead, "Index should advance after read");
    }

    @Test
    void testMoveToIndex() {
        long index1 = queue.writeEvent(AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        ));
        long index2 = queue.writeEvent(AccountInputEvent.newOrder(
            1002L, 2001L, 3001L, "600001",
            OrderSide.SELL, OrderType.LIMIT, 101000L, 100L, 0L, "key-2"
        ));
        queue.writeEvent(AccountInputEvent.newOrder(
            1003L, 2001L, 3001L, "600002",
            OrderSide.BUY, OrderType.LIMIT, 102000L, 100L, 10000000L, "key-3"
        ));

        // 读取所有
        queue.readBatch();

        // 移动到 index2 重新读取
        queue.moveToIndex(index2);
        List<AccountInputEvent> events = queue.readBatch();
        assertTrue(events.size() >= 1, "Should read at least 1 event after moveToIndex");
    }

    @Test
    void testWriteBatch() {
        List<AccountInputEvent> eventsToWrite = List.of(
            AccountInputEvent.newOrder(1001L, 2001L, 3001L, "600000",
                OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"),
            AccountInputEvent.newOrder(1002L, 2001L, 3001L, "600001",
                OrderSide.SELL, OrderType.LIMIT, 101000L, 100L, 0L, "key-2"),
            AccountInputEvent.snapshot(99999L)
        );

        List<Long> indices = queue.writeBatch(eventsToWrite);
        assertEquals(3, indices.size());
        assertTrue(indices.get(1) > indices.get(0));
        assertTrue(indices.get(2) > indices.get(1));

        List<AccountInputEvent> readEvents = queue.readBatch();
        assertEquals(3, readEvents.size());
    }

    @Test
    void testDifferentOrderTypes() {
        // LIMIT order
        queue.writeEvent(AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        ));

        // MARKET order
        queue.writeEvent(AccountInputEvent.newOrder(
            1002L, 2001L, 3001L, "600000",
            OrderSide.SELL, OrderType.MARKET, 0L, 100L, 0L, "key-2"
        ));

        // FAK order
        queue.writeEvent(AccountInputEvent.newOrder(
            1003L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.FAK, 100000L, 100L, 10000000L, "key-3"
        ));

        // FOK order
        queue.writeEvent(AccountInputEvent.newOrder(
            1004L, 2001L, 3001L, "600000",
            OrderSide.SELL, OrderType.FOK, 101000L, 100L, 0L, "key-4"
        ));

        List<AccountInputEvent> events = queue.readBatch();
        assertEquals(4, events.size());

        assertEquals(OrderType.LIMIT, events.get(0).getOrderType());
        assertEquals(OrderType.MARKET, events.get(1).getOrderType());
        assertEquals(OrderType.FAK, events.get(2).getOrderType());
        assertEquals(OrderType.FOK, events.get(3).getOrderType());
    }
}
