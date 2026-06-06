package com.hft.trading.queue;

import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.EventType;
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
 * AccountInputQueue 单元测试
 *
 * 测试范围：
 * - 事件写入与读取
 * - 批量读取
 * - 索引跟踪
 * - 移动到指定索引
 */
class AccountInputQueueTest {

    @TempDir
    Path tempDir;

    private AccountInputQueue queue;
    private String basePath;

    @BeforeEach
    void setUp() {
        basePath = tempDir.toString() + "/";
        queue = new AccountInputQueue(basePath, 50, 10);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    @Test
    void testWriteAndReadEvent() {
        // 创建 NEW_ORDER 事件
        AccountInputEvent event = AccountInputEvent.newOrder(
            1001L,  // orderId
            2001L,  // accountId
            3001L,  // userId
            "600000",  // symbol
            OrderSide.BUY,
            OrderType.LIMIT,
            100000L,  // price (1000.00 yuan)
            100L,     // quantity
            10000000L,  // frozenAmount
            "idempotency-key-001"
        );

        // 写入事件
        long index = queue.writeEvent(event);
        assertTrue(index > 0, "Index should be positive after write");

        // 读取事件
        List<AccountInputEvent> events = queue.readBatch();
        assertEquals(1, events.size(), "Should read 1 event");

        AccountInputEvent readEvent = events.get(0);
        assertEquals(EventType.NEW_ORDER, readEvent.getEventType());
        assertEquals(1001L, readEvent.getOrderId());
        assertEquals(2001L, readEvent.getAccountId());
        assertEquals(3001L, readEvent.getUserId());
        assertEquals("600000", readEvent.getSymbol());
        assertEquals(OrderSide.BUY, readEvent.getSide());
        assertEquals(OrderType.LIMIT, readEvent.getOrderType());
        assertEquals(100000L, readEvent.getPrice());
        assertEquals(100L, readEvent.getQuantity());
        assertEquals("idempotency-key-001", readEvent.getIdempotencyKey());
    }

    @Test
    void testWriteMultipleEvents() {
        // 写入多个事件
        AccountInputEvent event1 = AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        );
        AccountInputEvent event2 = AccountInputEvent.newOrder(
            1002L, 2001L, 3001L, "600001",
            OrderSide.SELL, OrderType.MARKET, 0L, 200L, 0L, "key-2"
        );
        AccountInputEvent event3 = AccountInputEvent.snapshot(12345L);

        long index1 = queue.writeEvent(event1);
        long index2 = queue.writeEvent(event2);
        long index3 = queue.writeEvent(event3);

        assertTrue(index2 > index1, "Indices should be increasing");
        assertTrue(index3 > index2, "Indices should be increasing");

        // 批量读取
        List<AccountInputEvent> events = queue.readBatch();
        assertEquals(3, events.size());

        assertEquals(EventType.NEW_ORDER, events.get(0).getEventType());
        assertEquals(1001L, events.get(0).getOrderId());

        assertEquals(EventType.NEW_ORDER, events.get(1).getEventType());
        assertEquals(1002L, events.get(1).getOrderId());

        assertEquals(EventType.SNAPSHOT, events.get(2).getEventType());
        assertEquals(12345L, events.get(2).getCqIndex());
    }

    @Test
    void testBatchRead() {
        // 写入 5 个事件
        for (int i = 0; i < 5; i++) {
            AccountInputEvent event = AccountInputEvent.newOrder(
                1000L + i, 2001L, 3001L, "600000",
                OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-" + i
            );
            queue.writeEvent(event);
        }

        // 批量读取
        List<AccountInputEvent> events = queue.readBatch();
        assertEquals(5, events.size(), "Should read all 5 events");

        for (int i = 0; i < 5; i++) {
            assertEquals(1000L + i, events.get(i).getOrderId());
        }
    }

    @Test
    void testGetLastIndex() {
        // lastIndexAppended() throws exception if nothing written yet
        // First write an event
        AccountInputEvent event = AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        );

        long index = queue.writeEvent(event);
        assertEquals(index, queue.getLastIndex(), "Last index should match written index");

        // Write another event
        AccountInputEvent event2 = AccountInputEvent.newOrder(
            1002L, 2001L, 3001L, "600001",
            OrderSide.BUY, OrderType.LIMIT, 101000L, 100L, 10000000L, "key-2"
        );
        long index2 = queue.writeEvent(event2);
        assertEquals(index2, queue.getLastIndex(), "Last index should update after second write");
    }

    @Test
    void testGetCurrentIndex() {
        // 初始状态
        long initialIndex = queue.getCurrentIndex();

        // 写入事件
        AccountInputEvent event = AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        );
        queue.writeEvent(event);

        // 读取前
        long beforeReadIndex = queue.getCurrentIndex();

        // 读取后
        queue.readBatch();
        long afterReadIndex = queue.getCurrentIndex();

        assertTrue(afterReadIndex >= beforeReadIndex, "Index should advance after read");
    }

    @Test
    void testMoveToIndex() {
        // 写入 3 个事件
        long index1 = queue.writeEvent(AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        ));
        long index2 = queue.writeEvent(AccountInputEvent.newOrder(
            1002L, 2001L, 3001L, "600001",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-2"
        ));
        queue.writeEvent(AccountInputEvent.newOrder(
            1003L, 2001L, 3001L, "600002",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-3"
        ));

        // 先读取所有
        queue.readBatch();

        // 移动到 index2
        queue.moveToIndex(index2);
        List<AccountInputEvent> events = queue.readBatch();

        // 应该读取到 index2 及之后的事件
        assertTrue(events.size() >= 1, "Should read at least 1 event after moveToIndex");
    }

    @Test
    void testWriteBatch() {
        List<AccountInputEvent> eventsToWrite = List.of(
            AccountInputEvent.newOrder(1001L, 2001L, 3001L, "600000",
                OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"),
            AccountInputEvent.newOrder(1002L, 2001L, 3001L, "600001",
                OrderSide.SELL, OrderType.LIMIT, 101000L, 200L, 0L, "key-2"),
            AccountInputEvent.newOrder(1003L, 2001L, 3001L, "600002",
                OrderSide.BUY, OrderType.MARKET, 0L, 300L, 15000000L, "key-3")
        );

        List<Long> indices = queue.writeBatch(eventsToWrite);
        assertEquals(3, indices.size(), "Should return 3 indices");
        assertTrue(indices.get(1) > indices.get(0), "Indices should be increasing");
        assertTrue(indices.get(2) > indices.get(1), "Indices should be increasing");

        // 验证读取
        List<AccountInputEvent> readEvents = queue.readBatch();
        assertEquals(3, readEvents.size());
    }

    @Test
    void testDifferentEventTypes() {
        // NEW_ORDER
        queue.writeEvent(AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        ));

        // CANCEL_ORDER
        queue.writeEvent(AccountInputEvent.cancelOrder(
            1001L, 2001L, null
        ));

        // SNAPSHOT
        queue.writeEvent(AccountInputEvent.snapshot(99999L));

        // PHASE_CHANGE
        queue.writeEvent(AccountInputEvent.phaseChange(
            com.hft.trading.event.TradingPhase.CONTINUOUS_TRADING
        ));

        // DEPOSIT
        queue.writeEvent(AccountInputEvent.deposit(
            "deposit-001", 3001L, 2001L, 100000000L,
            "Initial deposit", 1L, false, null
        ));

        List<AccountInputEvent> events = queue.readBatch();
        assertEquals(5, events.size());

        assertEquals(EventType.NEW_ORDER, events.get(0).getEventType());
        assertEquals(EventType.CANCEL_ORDER, events.get(1).getEventType());
        assertEquals(EventType.SNAPSHOT, events.get(2).getEventType());
        assertEquals(EventType.PHASE_CHANGE, events.get(3).getEventType());
        assertEquals(EventType.DEPOSIT, events.get(4).getEventType());
    }

    @Test
    void testCloseAndReopen() {
        // 写入事件
        AccountInputEvent event = AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        );
        queue.writeEvent(event);
        long lastIndex = queue.getLastIndex();

        // 关闭队列
        queue.close();

        // 重新打开
        queue = new AccountInputQueue(basePath, 50, 10);

        // 验证可以继续写入
        AccountInputEvent event2 = AccountInputEvent.newOrder(
            1002L, 2001L, 3001L, "600000",
            OrderSide.SELL, OrderType.LIMIT, 101000L, 100L, 0L, "key-2"
        );
        queue.writeEvent(event2);

        // 注意：新创建的 tailer 会从头开始读取
        List<AccountInputEvent> events = queue.readBatch();
        assertTrue(events.size() >= 1, "Should read at least 1 event");
    }
}
