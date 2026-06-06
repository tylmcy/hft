package com.hft.trading.queue;

import com.hft.trading.event.EventType;
import com.hft.trading.event.SettlementEvent;
import com.hft.trading.model.OrderSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AccountSettlementQueue 单元测试
 *
 * 测试范围：
 * - SETTLEMENT 事件写入与读取
 * - UNFREEZE 事件写入与读取
 * - 批量读取
 * - 事件类型路由
 */
class AccountSettlementQueueTest {

    @TempDir
    Path tempDir;

    private AccountSettlementQueue queue;
    private String basePath;

    @BeforeEach
    void setUp() {
        basePath = tempDir.toString() + "/";
        queue = new AccountSettlementQueue(basePath, 50, 10);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    @Test
    void testWriteSettlementEvent() {
        // Buyer settlement
        SettlementEvent buyerSettlement = SettlementEvent.forBuyer(
            "trade-001",  // tradeId
            1001L,        // orderId
            2001L,        // accountId
            3001L,        // userId
            "600000",     // symbol
            100000L,      // price (1000.00 yuan)
            100L,         // quantity
            10000000L,    // frozenAmountUsed
            300L          // commission (3万分 = 300分)
        );

        long index = queue.writeSettlementEvent(buyerSettlement);
        assertTrue(index > 0, "Index should be positive after write");

        List<AccountSettlementQueue.SettlementQueueEvent> events = queue.readBatch();
        assertEquals(1, events.size());

        AccountSettlementQueue.SettlementQueueEvent readEvent = events.get(0);
        assertEquals(EventType.SETTLEMENT, readEvent.getEventType());

        SettlementEvent settlement = readEvent.asSettlementEvent();
        assertNotNull(settlement);
        assertEquals("trade-001_1001_BUY", settlement.getSettlementId());
        assertEquals(1001L, settlement.getOrderId());
        assertEquals(2001L, settlement.getAccountId());
        assertEquals(3001L, settlement.getUserId());
        assertEquals("600000", settlement.getSymbol());
        assertEquals("trade-001", settlement.getTradeId());
        assertEquals(OrderSide.BUY, settlement.getSettlementType());
        assertEquals(100000L, settlement.getPrice());
        assertEquals(100L, settlement.getQuantity());
        assertEquals(10000000L, settlement.getFrozenAmountUsed());
        assertEquals(10000000L, settlement.getActualAmount());  // price * quantity
        assertTrue(settlement.isBuyer());
    }

    @Test
    void testWriteSettlementEventForSeller() {
        SettlementEvent sellerSettlement = SettlementEvent.forSeller(
            "trade-001",  // tradeId
            1002L,        // orderId
            2002L,        // accountId
            3002L,        // userId
            "600000",     // symbol
            100000L,      // price
            100L,         // quantity
            300L          // commission
        );

        queue.writeSettlementEvent(sellerSettlement);

        List<AccountSettlementQueue.SettlementQueueEvent> events = queue.readBatch();
        assertEquals(1, events.size());

        SettlementEvent settlement = events.get(0).asSettlementEvent();
        assertNotNull(settlement);
        assertEquals("trade-001_1002_SELL", settlement.getSettlementId());
        assertEquals(OrderSide.SELL, settlement.getSettlementType());
        assertFalse(settlement.isBuyer());
        assertEquals(0L, settlement.getRefundAmount());  // Seller has no refund
    }

    @Test
    void testWriteUnfreezeEvent() {
        long index = queue.writeUnfreezeEvent(
            1001L,        // orderId
            2001L,        // accountId
            3001L,        // userId
            "600000",     // symbol
            5000000L,     // unfreezeAmount (买方解冻金额)
            0L,           // unfreezeQty (卖方解冻数量)
            EventType.FREEZE // side (using EventType as per API)
        );
        assertTrue(index > 0);

        List<AccountSettlementQueue.SettlementQueueEvent> events = queue.readBatch();
        assertEquals(1, events.size());

        AccountSettlementQueue.SettlementQueueEvent readEvent = events.get(0);
        assertEquals(EventType.UNFREEZE, readEvent.getEventType());

        AccountSettlementQueue.UnfreezeData unfreeze = readEvent.asUnfreezeData();
        assertNotNull(unfreeze);
        assertEquals(1001L, unfreeze.getOrderId());
        assertEquals(2001L, unfreeze.getAccountId());
        assertEquals(3001L, unfreeze.getUserId());
        assertEquals("600000", unfreeze.getSymbol());
        assertEquals(5000000L, unfreeze.getUnfreezeAmount());
        assertEquals(0L, unfreeze.getUnfreezeQty());
        assertNotNull(unfreeze.getTimestamp());
    }

    @Test
    void testWriteUnfreezeEventForSellOrder() {
        queue.writeUnfreezeEvent(
            1002L,        // orderId
            2002L,        // accountId
            3002L,        // userId
            "600001",     // symbol
            0L,           // unfreezeAmount
            100L,         // unfreezeQty (卖方解冻持仓)
            EventType.POSITION_CHANGE // side (using EventType as per API)
        );

        List<AccountSettlementQueue.SettlementQueueEvent> events = queue.readBatch();
        assertEquals(1, events.size());

        AccountSettlementQueue.UnfreezeData unfreeze = events.get(0).asUnfreezeData();
        assertNotNull(unfreeze);
        assertEquals(0L, unfreeze.getUnfreezeAmount());
        assertEquals(100L, unfreeze.getUnfreezeQty());
    }

    @Test
    void testBatchRead() {
        // Write multiple events
        queue.writeSettlementEvent(SettlementEvent.forBuyer(
            "trade-001", 1001L, 2001L, 3001L, "600000",
            100000L, 100L, 10000000L, 300L
        ));

        queue.writeUnfreezeEvent(1003L, 2003L, 3003L, "600001",
            5000000L, 0L, EventType.FREEZE);

        queue.writeSettlementEvent(SettlementEvent.forSeller(
            "trade-002", 1002L, 2002L, 3002L, "600000",
            101000L, 200L, 400L
        ));

        queue.writeUnfreezeEvent(1004L, 2004L, 3004L, "600002",
            0L, 150L, EventType.POSITION_CHANGE);

        List<AccountSettlementQueue.SettlementQueueEvent> events = queue.readBatch();
        assertEquals(4, events.size());

        assertEquals(EventType.SETTLEMENT, events.get(0).getEventType());
        assertNotNull(events.get(0).asSettlementEvent());

        assertEquals(EventType.UNFREEZE, events.get(1).getEventType());
        assertNotNull(events.get(1).asUnfreezeData());

        assertEquals(EventType.SETTLEMENT, events.get(2).getEventType());
        assertNotNull(events.get(2).asSettlementEvent());

        assertEquals(EventType.UNFREEZE, events.get(3).getEventType());
        assertNotNull(events.get(3).asUnfreezeData());
    }

    @Test
    void testGetLastIndex() {
        // lastIndexAppended() throws exception if nothing written yet
        long index1 = queue.writeSettlementEvent(SettlementEvent.forBuyer(
            "trade-001", 1001L, 2001L, 3001L, "600000",
            100000L, 100L, 10000000L, 300L
        ));
        assertEquals(index1, queue.getLastIndex());

        long index2 = queue.writeUnfreezeEvent(1002L, 2002L, 3002L, "600001",
            5000000L, 0L, EventType.FREEZE);
        assertEquals(index2, queue.getLastIndex());
    }

    @Test
    void testGetCurrentIndex() {
        queue.writeSettlementEvent(SettlementEvent.forBuyer(
            "trade-001", 1001L, 2001L, 3001L, "600000",
            100000L, 100L, 10000000L, 300L
        ));

        long beforeRead = queue.getCurrentIndex();
        queue.readBatch();
        long afterRead = queue.getCurrentIndex();

        assertTrue(afterRead >= beforeRead, "Index should advance after read");
    }

    @Test
    void testMoveToIndex() {
        queue.writeSettlementEvent(SettlementEvent.forBuyer(
            "trade-001", 1001L, 2001L, 3001L, "600000",
            100000L, 100L, 10000000L, 300L
        ));
        long index2 = queue.writeUnfreezeEvent(1002L, 2002L, 3002L, "600001",
            5000000L, 0L, EventType.FREEZE);
        queue.writeSettlementEvent(SettlementEvent.forSeller(
            "trade-002", 1003L, 2003L, 3003L, "600002",
            102000L, 300L, 500L
        ));

        // Read all
        queue.readBatch();

        // Move to index2
        queue.moveToIndex(index2);
        List<AccountSettlementQueue.SettlementQueueEvent> events = queue.readBatch();
        assertTrue(events.size() >= 1, "Should read at least 1 event after moveToIndex");
    }

    @Test
    void testNullConversionForWrongType() {
        // Write a SETTLEMENT event
        queue.writeSettlementEvent(SettlementEvent.forBuyer(
            "trade-001", 1001L, 2001L, 3001L, "600000",
            100000L, 100L, 10000000L, 300L
        ));

        List<AccountSettlementQueue.SettlementQueueEvent> events = queue.readBatch();
        assertEquals(1, events.size());

        // Try to convert SETTLEMENT event to UnfreezeData - should return null
        assertNull(events.get(0).asUnfreezeData());

        // SettlementEvent should work
        assertNotNull(events.get(0).asSettlementEvent());
    }

    @Test
    void testSettlementEventIdempotencyKey() {
        SettlementEvent buyer1 = SettlementEvent.forBuyer(
            "trade-001", 1001L, 2001L, 3001L, "600000",
            100000L, 100L, 10000000L, 300L
        );
        assertEquals("trade-001_1001_BUY", buyer1.getSettlementId());

        SettlementEvent seller1 = SettlementEvent.forSeller(
            "trade-001", 1002L, 2002L, 3002L, "600000",
            100000L, 100L, 300L
        );
        assertEquals("trade-001_1002_SELL", seller1.getSettlementId());

        // Different tradeId/orderId should produce different settlementId
        SettlementEvent buyer2 = SettlementEvent.forBuyer(
            "trade-002", 1001L, 2001L, 3001L, "600000",
            100000L, 100L, 10000000L, 300L
        );
        assertNotEquals(buyer1.getSettlementId(), buyer2.getSettlementId());
    }

    @Test
    void testRefundCalculation() {
        // Buyer with frozen amount larger than actual amount
        SettlementEvent event = SettlementEvent.forBuyer(
            "trade-001", 1001L, 2001L, 3001L, "600000",
            100000L,    // price: 1000.00 yuan
            100L,       // quantity: 100
            12000000L,  // frozenAmountUsed: 12000.00 yuan (over-frozen)
            300L        // commission: 3.00 yuan
        );

        // actualAmount = 100000 * 100 = 10000000 (10000.00 yuan)
        // refundAmount = frozenAmountUsed - actualAmount - commission = 12000000 - 10000000 - 300 = 1999700
        assertEquals(10000000L, event.getActualAmount());
        assertEquals(1999700L, event.getRefundAmount());
    }

    @Test
    void testCloseAndReopen() {
        queue.writeSettlementEvent(SettlementEvent.forBuyer(
            "trade-001", 1001L, 2001L, 3001L, "600000",
            100000L, 100L, 10000000L, 300L
        ));

        queue.close();

        // Reopen
        queue = new AccountSettlementQueue(basePath, 50, 10);

        queue.writeUnfreezeEvent(1002L, 2002L, 3002L, "600001",
            5000000L, 0L, EventType.FREEZE);

        List<AccountSettlementQueue.SettlementQueueEvent> events = queue.readBatch();
        assertTrue(events.size() >= 1, "Should read at least 1 event after reopen");
    }
}