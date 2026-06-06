package com.hft.trading.queue;

import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.EventType;
import com.hft.trading.event.SettlementEvent;
import com.hft.trading.event.TradeEvent;
import com.hft.trading.model.OrderSide;
import com.hft.trading.model.OrderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueueManager 单元测试
 *
 * 测试范围：
 * - 所有队列初始化
 * - 统计信息获取
 * - 优雅关闭
 */
class QueueManagerTest {

    @TempDir
    Path tempDir;

    private QueueManager queueManager;
    private String basePath;

    @BeforeEach
    void setUp() {
        basePath = tempDir.toString() + "/";
        queueManager = new QueueManager(basePath);
    }

    @AfterEach
    void tearDown() {
        if (queueManager != null) {
            queueManager.closeAll();
        }
    }

    @Test
    void testCreateAllQueues() {
        assertNotNull(queueManager.getAccountInputQueue(), "AccountInputQueue should be initialized");
        assertNotNull(queueManager.getMatchingInputQueue(), "MatchingInputQueue should be initialized");
        assertNotNull(queueManager.getMatchingOutputQueue(), "MatchingOutputQueue should be initialized");
        assertNotNull(queueManager.getAccountSettlementQueue(), "AccountSettlementQueue should be initialized");
    }

    @Test
    void testGetStats() {
        // getStats() requires at least one write to each queue
        // because lastIndexAppended() throws exception if nothing written yet
        queueManager.getAccountInputQueue().writeEvent(
            AccountInputEvent.newOrder(1001L, 2001L, 3001L, "600000",
                OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1")
        );
        queueManager.getMatchingInputQueue().writeEvent(
            AccountInputEvent.newOrder(1002L, 2001L, 3001L, "600001",
                OrderSide.SELL, OrderType.LIMIT, 101000L, 100L, 0L, "key-2")
        );
        String tradeId = TradeEvent.generateTradeId(1001L, 1002L);
        queueManager.getMatchingOutputQueue().writeTradeEvent(
            TradeEvent.of(tradeId, 1001L, 1002L, "600000",
                100000L, 100L, 2001L, 2002L, 1001L, 1002L,
                OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, 3)
        );
        queueManager.getAccountSettlementQueue().writeSettlementEvent(
            SettlementEvent.forBuyer("trade-001", 1001L, 2001L, 3001L, "600000",
                100000L, 100L, 10000000L, 300L)
        );

        QueueManager.QueueStats stats = queueManager.getStats();
        assertNotNull(stats, "Stats should not be null");

        // After write, last indices should be positive
        assertTrue(stats.getAccountInputLastIndex() > 0);
        assertTrue(stats.getMatchingInputLastIndex() > 0);
        assertTrue(stats.getMatchingOutputLastIndex() > 0);
        assertTrue(stats.getAccountSettlementLastIndex() > 0);
    }

    @Test
    void testStatsAfterWrite() {
        // Write events to all queues
        queueManager.getAccountInputQueue().writeEvent(
            AccountInputEvent.newOrder(1001L, 2001L, 3001L, "600000",
                OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1")
        );

        queueManager.getMatchingInputQueue().writeEvent(
            AccountInputEvent.newOrder(1002L, 2001L, 3001L, "600001",
                OrderSide.SELL, OrderType.LIMIT, 101000L, 100L, 0L, "key-2")
        );

        String tradeId = TradeEvent.generateTradeId(1001L, 1002L);
        queueManager.getMatchingOutputQueue().writeTradeEvent(
            TradeEvent.of(tradeId, 1001L, 1002L, "600000",
                100000L, 100L, 2001L, 2002L, 1001L, 1002L,
                OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, 3)
        );

        queueManager.getAccountSettlementQueue().writeSettlementEvent(
            SettlementEvent.forBuyer("trade-001", 1001L, 2001L, 3001L, "600000",
                100000L, 100L, 10000000L, 300L)
        );

        QueueManager.QueueStats stats = queueManager.getStats();

        // After write, last indices should be positive
        assertTrue(stats.getAccountInputLastIndex() > 0, "AccountInput lastIndex should be positive");
        assertTrue(stats.getMatchingInputLastIndex() > 0, "MatchingInput lastIndex should be positive");
        assertTrue(stats.getMatchingOutputLastIndex() > 0, "MatchingOutput lastIndex should be positive");
        assertTrue(stats.getAccountSettlementLastIndex() > 0, "AccountSettlement lastIndex should be positive");
    }

    @Test
    void testCloseAll() {
        // Write some events
        queueManager.getAccountInputQueue().writeEvent(
            AccountInputEvent.newOrder(1001L, 2001L, 3001L, "600000",
                OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1")
        );

        // Close should not throw
        assertDoesNotThrow(() -> queueManager.closeAll());

        // After close, queues should be unusable (will throw on write)
        // Note: This behavior depends on Chronicle Queue implementation
    }

    @Test
    void testQueueStatsToString() {
        // Write events first to get valid indices
        queueManager.getAccountInputQueue().writeEvent(
            AccountInputEvent.newOrder(1001L, 2001L, 3001L, "600000",
                OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1")
        );
        queueManager.getMatchingInputQueue().writeEvent(
            AccountInputEvent.newOrder(1002L, 2001L, 3001L, "600001",
                OrderSide.SELL, OrderType.LIMIT, 101000L, 100L, 0L, "key-2")
        );
        String tradeId = TradeEvent.generateTradeId(1001L, 1002L);
        queueManager.getMatchingOutputQueue().writeTradeEvent(
            TradeEvent.of(tradeId, 1001L, 1002L, "600000",
                100000L, 100L, 2001L, 2002L, 1001L, 1002L,
                OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, 3)
        );
        queueManager.getAccountSettlementQueue().writeSettlementEvent(
            SettlementEvent.forBuyer("trade-001", 1001L, 2001L, 3001L, "600000",
                100000L, 100L, 10000000L, 300L)
        );

        QueueManager.QueueStats stats = queueManager.getStats();
        String str = stats.toString();

        assertNotNull(str);
        assertTrue(str.contains("QueueStats"));
        assertTrue(str.contains("accountInput"));
        assertTrue(str.contains("matchingInput"));
        assertTrue(str.contains("matchingOutput"));
        assertTrue(str.contains("accountSettlement"));
    }

    @Test
    void testMultipleWritesToAllQueues() {
        // Write multiple events to each queue
        for (int i = 0; i < 10; i++) {
            queueManager.getAccountInputQueue().writeEvent(
                AccountInputEvent.newOrder(1000L + i, 2001L, 3001L, "600000",
                    OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-" + i)
            );

            queueManager.getMatchingInputQueue().writeEvent(
                AccountInputEvent.newOrder(2000L + i, 2001L, 3001L, "600001",
                    OrderSide.SELL, OrderType.LIMIT, 101000L, 100L, 0L, "mkey-" + i)
            );

            String tradeId = TradeEvent.generateTradeId(3000L + i, 4000L + i);
            queueManager.getMatchingOutputQueue().writeTradeEvent(
                TradeEvent.of(tradeId, 3000L + i, 4000L + i, "600000",
                    100000L, 100L, 2001L, 2002L, 3000L + i, 4000L + i,
                    OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, 3)
            );

            queueManager.getAccountSettlementQueue().writeSettlementEvent(
                SettlementEvent.forBuyer("trade-" + i, 5000L + i, 2001L, 3001L, "600000",
                    100000L, 100L, 10000000L, 300L)
            );
        }

        QueueManager.QueueStats stats = queueManager.getStats();

        // Verify all queues have advanced
        assertTrue(stats.getAccountInputLastIndex() > 0);
        assertTrue(stats.getMatchingInputLastIndex() > 0);
        assertTrue(stats.getMatchingOutputLastIndex() > 0);
        assertTrue(stats.getAccountSettlementLastIndex() > 0);
    }

    @Test
    void testAccountInputQueueOperations() {
        AccountInputQueue queue = queueManager.getAccountInputQueue();

        // Write and read
        AccountInputEvent event = AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1"
        );
        queue.writeEvent(event);

        // Read
        var events = queue.readBatch();
        assertEquals(1, events.size());
        assertEquals(EventType.NEW_ORDER, events.get(0).getEventType());
    }

    @Test
    void testMatchingInputQueueOperations() {
        MatchingInputQueue queue = queueManager.getMatchingInputQueue();

        // Write and read
        AccountInputEvent event = AccountInputEvent.newOrder(
            1001L, 2001L, 3001L, "600000",
            OrderSide.SELL, OrderType.MARKET, 0L, 100L, 0L, "key-1"
        );
        queue.writeEvent(event);

        var events = queue.readBatch();
        assertEquals(1, events.size());
        assertEquals(EventType.NEW_ORDER, events.get(0).getEventType());
    }

    @Test
    void testMatchingOutputQueueOperations() {
        MatchingOutputQueue queue = queueManager.getMatchingOutputQueue();

        String tradeId = TradeEvent.generateTradeId(1001L, 1002L);
        TradeEvent event = TradeEvent.of(
            tradeId, 1001L, 1002L, "600000",
            100000L, 100L, 2001L, 2002L, 1001L, 1002L,
            OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, 3
        );
        queue.writeTradeEvent(event);

        var events = queue.readBatch();
        assertEquals(1, events.size());
        assertEquals(EventType.TRADE, events.get(0).getEventType());
    }

    @Test
    void testAccountSettlementQueueOperations() {
        AccountSettlementQueue queue = queueManager.getAccountSettlementQueue();

        SettlementEvent event = SettlementEvent.forBuyer(
            "trade-001", 1001L, 2001L, 3001L, "600000",
            100000L, 100L, 10000000L, 300L
        );
        queue.writeSettlementEvent(event);

        var events = queue.readBatch();
        assertEquals(1, events.size());
        assertEquals(EventType.SETTLEMENT, events.get(0).getEventType());
    }

    @Test
    void testCloseAndReopen() {
        // Write events
        queueManager.getAccountInputQueue().writeEvent(
            AccountInputEvent.newOrder(1001L, 2001L, 3001L, "600000",
                OrderSide.BUY, OrderType.LIMIT, 100000L, 100L, 10000000L, "key-1")
        );

        // Close
        queueManager.closeAll();

        // Reopen
        queueManager = new QueueManager(basePath);

        // Verify queues are functional
        assertNotNull(queueManager.getAccountInputQueue());

        // Write new event
        queueManager.getAccountInputQueue().writeEvent(
            AccountInputEvent.newOrder(1002L, 2001L, 3001L, "600001",
                OrderSide.SELL, OrderType.LIMIT, 101000L, 100L, 0L, "key-2")
        );

        var events = queueManager.getAccountInputQueue().readBatch();
        assertTrue(events.size() >= 1, "Should read at least 1 event after reopen");
    }
}
