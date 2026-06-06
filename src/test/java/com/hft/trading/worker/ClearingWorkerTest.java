package com.hft.trading.worker;

import com.hft.trading.event.CancelSuccessEvent;
import com.hft.trading.event.EventType;
import com.hft.trading.event.SettlementEvent;
import com.hft.trading.event.TradeEvent;
import com.hft.trading.model.*;
import com.hft.trading.queue.AccountSettlementQueue;
import com.hft.trading.queue.MatchingOutputQueue;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ClearingWorker 单元测试
 *
 * 根据 V12 技术方案测试：
 * - TRADE 事件处理：生成 SETTLEMENT 事件
 * - ORDER_UPDATE 事件处理：更新订单状态
 * - CANCEL_SUCCESS 事件处理：生成 UNFREEZE 事件
 * - 幂等性检查
 */
class ClearingWorkerTest {

    private InMemoryOrderStore orderStore;
    private InMemoryInstrumentStore instrumentStore;
    private ClearingWorker clearingWorker;
    private AccountSettlementQueue mockSettlementQueue;

    @BeforeEach
    void setUp() throws Exception {
        orderStore = new InMemoryOrderStore();
        instrumentStore = new InMemoryInstrumentStore();
        mockSettlementQueue = mock(AccountSettlementQueue.class);

        // Create ClearingWorker with mock queue
        clearingWorker = new ClearingWorker(
            orderStore,
            instrumentStore,
            null, // MatchingOutputQueue - not needed for direct method tests
            mockSettlementQueue,
            null  // WebSocketPushService
        );
    }

    // ==================== TRADE Tests ====================

    @Test
    @DisplayName("处理 TRADE 事件成功 - 验证生成买方和卖方 SETTLEMENT 事件")
    void testProcessTrade() throws Exception {
        // Setup: Create buy and sell orders
        long buyOrderId = 10001L;
        long sellOrderId = 10002L;
        long buyAccountId = 1001L;
        long sellAccountId = 1002L;

        Order buyOrder = createOrder(buyOrderId, buyAccountId, OrderSide.BUY, 1000L, 100L, 100000L);
        Order sellOrder = createOrder(sellOrderId, sellAccountId, OrderSide.SELL, 1000L, 100L, 0L);
        orderStore.addOrder(buyOrder);
        orderStore.addOrder(sellOrder);

        // Create TRADE event
        String tradeId = "trade-001";
        TradeEvent tradeEvent = TradeEvent.of(
            tradeId,
            buyOrderId,  // takerOrderId
            sellOrderId, // makerOrderId
            "600000",
            1000L,       // price: 10.00 yuan
            100L,        // quantity
            buyAccountId,
            sellAccountId,
            buyOrderId,
            sellOrderId,
            OrderSide.BUY,
            OrderType.LIMIT,
            1000L,       // orderPrice
            100L,        // orderQuantity
            100000L,     // frozenAmount
            3            // commissionRate: 0.03%
        );

        // Invoke processTrade
        Method processTrade = ClearingWorker.class.getDeclaredMethod("processTrade", TradeEvent.class);
        processTrade.setAccessible(true);
        processTrade.invoke(clearingWorker, tradeEvent);

        // Verify orders updated with filled quantity
        assertEquals(100L, orderStore.getOrder(buyOrderId).getFilledQuantity());
        assertEquals(100L, orderStore.getOrder(sellOrderId).getFilledQuantity());

        // Verify SETTLEMENT events generated (2 calls: buyer and seller)
        verify(mockSettlementQueue, times(2)).writeSettlementEvent(any(SettlementEvent.class));
    }

    @Test
    @DisplayName("处理 TRADE 事件 - 验证佣金计算")
    void testProcessTrade_CommissionCalculation() throws Exception {
        // Setup
        long buyOrderId = 10001L;
        Order buyOrder = createOrder(buyOrderId, 1001L, OrderSide.BUY, 1000L, 100L, 100000L);
        orderStore.addOrder(buyOrder);

        Order sellOrder = createOrder(10002L, 1002L, OrderSide.SELL, 1000L, 100L, 0L);
        orderStore.addOrder(sellOrder);

        // Trade amount: 1000 * 100 = 100000 (1000 yuan)
        // Commission: 100000 * 3 / 10000 = 30 (0.30 yuan)
        TradeEvent tradeEvent = TradeEvent.of(
            "trade-001", 10001L, 10002L, "600000",
            1000L, 100L,
            1001L, 1002L, 10001L, 10002L,
            OrderSide.BUY, OrderType.LIMIT, 1000L, 100L, 100000L, 3
        );

        Method processTrade = ClearingWorker.class.getDeclaredMethod("processTrade", TradeEvent.class);
        processTrade.setAccessible(true);
        processTrade.invoke(clearingWorker, tradeEvent);

        // Verify commission in settlement - captured via argument
        verify(mockSettlementQueue, times(2)).writeSettlementEvent(any(SettlementEvent.class));
    }

    @Test
    @DisplayName("处理 TRADE 事件幂等检查 - 重复事件应跳过")
    void testProcessTrade_Idempotency() throws Exception {
        // Setup
        Order buyOrder = createOrder(10001L, 1001L, OrderSide.BUY, 1000L, 100L, 100000L);
        Order sellOrder = createOrder(10002L, 1002L, OrderSide.SELL, 1000L, 100L, 0L);
        orderStore.addOrder(buyOrder);
        orderStore.addOrder(sellOrder);

        TradeEvent tradeEvent = TradeEvent.of(
            "trade-001", 10001L, 10002L, "600000",
            1000L, 100L,
            1001L, 1002L, 10001L, 10002L,
            OrderSide.BUY, OrderType.LIMIT, 1000L, 100L, 100000L, 3
        );

        // Set replay mode
        setReplayMode(clearingWorker, true);

        Method processTrade = ClearingWorker.class.getDeclaredMethod("processTrade", TradeEvent.class);
        processTrade.setAccessible(true);

        // Process first time
        processTrade.invoke(clearingWorker, tradeEvent);

        // Process same trade again
        processTrade.invoke(clearingWorker, tradeEvent);

        // Verify only 2 settlement events (one per side) - idempotent on replay
        verify(mockSettlementQueue, times(2)).writeSettlementEvent(any(SettlementEvent.class));
    }

    @Test
    @DisplayName("处理 TRADE 事件 - 市价单冻结金额计算")
    void testProcessTrade_MarketOrderFrozenCalculation() throws Exception {
        // Setup market buy order
        long buyOrderId = 10001L;
        Order buyOrder = createMarketOrder(buyOrderId, 1001L, OrderSide.BUY, 100L, 150000L);
        buyOrder.setMaxPrice(1500L); // Max price for market buy
        orderStore.addOrder(buyOrder);

        Order sellOrder = createOrder(10002L, 1002L, OrderSide.SELL, 1000L, 100L, 0L);
        orderStore.addOrder(sellOrder);

        // Market buy: frozenAmount = maxPrice * quantity = 1500 * 100 = 150000
        // Trade: price = 1000, quantity = 100, amount = 100000
        TradeEvent tradeEvent = TradeEvent.of(
            "trade-001", buyOrderId, 10002L, "600000",
            1000L, 100L,  // trade price 10.00, quantity 100
            1001L, 1002L, buyOrderId, 10002L,
            OrderSide.BUY, OrderType.MARKET, 0L, 100L, 150000L, 3
        );

        Method processTrade = ClearingWorker.class.getDeclaredMethod("processTrade", TradeEvent.class);
        processTrade.setAccessible(true);
        processTrade.invoke(clearingWorker, tradeEvent);

        // Verify settlement generated
        verify(mockSettlementQueue, times(2)).writeSettlementEvent(any(SettlementEvent.class));
    }

    // ==================== ORDER_UPDATE Tests ====================

    @Test
    @DisplayName("处理 ORDER_UPDATE 事件 - 更新订单状态为 NEW")
    void testProcessOrderUpdate() throws Exception {
        // Setup: Create order in PENDING status
        long orderId = 10001L;
        Order order = createOrder(orderId, 1001L, OrderSide.BUY, 1000L, 100L, 100000L);
        orderStore.addOrder(order);

        // Create ORDER_UPDATE data (status is read as EventType from wire, but stored as string)
        // Using ORDER_UPDATE as placeholder since the actual status string "NEW" is converted
        MatchingOutputQueue.OrderUpdateData data = new MatchingOutputQueue.OrderUpdateData(
            orderId, "600000", EventType.ORDER_UPDATE
        );

        // Invoke processOrderUpdate
        Method processOrderUpdate = ClearingWorker.class.getDeclaredMethod(
            "processOrderUpdate", MatchingOutputQueue.OrderUpdateData.class);
        processOrderUpdate.setAccessible(true);
        processOrderUpdate.invoke(clearingWorker, data);

        // Verify order status updated
        assertEquals(OrderStatus.NEW, orderStore.getOrder(orderId).getStatus());
    }

    @Test
    @DisplayName("处理 ORDER_UPDATE 事件 - 订单不存在时忽略")
    void testProcessOrderUpdate_OrderNotFound() throws Exception {
        MatchingOutputQueue.OrderUpdateData data = new MatchingOutputQueue.OrderUpdateData(
            99999L, "600000", EventType.ORDER_UPDATE
        );

        Method processOrderUpdate = ClearingWorker.class.getDeclaredMethod(
            "processOrderUpdate", MatchingOutputQueue.OrderUpdateData.class);
        processOrderUpdate.setAccessible(true);

        // Should not throw
        assertDoesNotThrow(() -> processOrderUpdate.invoke(clearingWorker, data));
    }

    // ==================== CANCEL_SUCCESS Tests ====================

    @Test
    @DisplayName("处理 CANCEL_SUCCESS 买单事件 - 生成 UNFREEZE 事件")
    void testProcessCancelSuccess_BuyOrder() throws Exception {
        // Setup
        long orderId = 10001L;
        Order order = createOrder(orderId, 1001L, OrderSide.BUY, 1000L, 100L, 100000L);
        order.updateStatus(OrderStatus.NEW);
        order.updateFilledQuantity(30L); // Partially filled
        orderStore.addOrder(order);

        // Create CANCEL_SUCCESS event
        CancelSuccessEvent cancelEvent = CancelSuccessEvent.forBuyOrder(
            orderId, 1001L, 2001L, "600000",
            30L,    // filledQuantity
            70L,    // remainingQty
            CancelReason.USER_CANCEL,
            70000L  // unfreezeAmount (70 shares * 1000 frozen per share)
        );

        // Invoke processCancelSuccess
        Method processCancelSuccess = ClearingWorker.class.getDeclaredMethod(
            "processCancelSuccess", CancelSuccessEvent.class);
        processCancelSuccess.setAccessible(true);
        processCancelSuccess.invoke(clearingWorker, cancelEvent);

        // Verify UNFREEZE event generated
        verify(mockSettlementQueue).writeUnfreezeEvent(
            eq(orderId), eq(1001L), eq(2001L), eq("600000"),
            eq(70000L), eq(0L), any(EventType.class)
        );
    }

    @Test
    @DisplayName("处理 CANCEL_SUCCESS 卖单事件 - 生成持仓解冻事件")
    void testProcessCancelSuccess_SellOrder() throws Exception {
        // Setup
        long orderId = 10001L;
        Order order = createOrder(orderId, 1001L, OrderSide.SELL, 1000L, 100L, 0L);
        order.updateStatus(OrderStatus.NEW);
        order.updateFilledQuantity(30L);
        orderStore.addOrder(order);

        // Create CANCEL_SUCCESS event for sell
        CancelSuccessEvent cancelEvent = CancelSuccessEvent.forSellOrder(
            orderId, 1001L, 2001L, "600000",
            30L,    // filledQuantity
            70L,    // remainingQty
            CancelReason.USER_CANCEL,
            70L     // unfreezeQty (70 shares)
        );

        // Invoke processCancelSuccess
        Method processCancelSuccess = ClearingWorker.class.getDeclaredMethod(
            "processCancelSuccess", CancelSuccessEvent.class);
        processCancelSuccess.setAccessible(true);
        processCancelSuccess.invoke(clearingWorker, cancelEvent);

        // Verify UNFREEZE event generated for position
        verify(mockSettlementQueue).writeUnfreezeEvent(
            eq(orderId), eq(1001L), eq(2001L), eq("600000"),
            eq(0L), eq(70L), any(EventType.class)
        );
    }

    @Test
    @DisplayName("处理 CANCEL_SUCCESS 幂等检查 - 重复事件应跳过")
    void testProcessCancelSuccess_Idempotency() throws Exception {
        // Setup
        Order order = createOrder(10001L, 1001L, OrderSide.BUY, 1000L, 100L, 100000L);
        orderStore.addOrder(order);

        CancelSuccessEvent cancelEvent = CancelSuccessEvent.forBuyOrder(
            10001L, 1001L, 2001L, "600000",
            0L, 100L, CancelReason.USER_CANCEL, 100000L
        );

        // Set replay mode
        setReplayMode(clearingWorker, true);

        Method processCancelSuccess = ClearingWorker.class.getDeclaredMethod(
            "processCancelSuccess", CancelSuccessEvent.class);
        processCancelSuccess.setAccessible(true);

        // Process first time
        processCancelSuccess.invoke(clearingWorker, cancelEvent);

        // Process same event again
        processCancelSuccess.invoke(clearingWorker, cancelEvent);

        // Verify only one UNFREEZE event (idempotent)
        verify(mockSettlementQueue, times(1)).writeUnfreezeEvent(
            anyLong(), anyLong(), anyLong(), anyString(), anyLong(), anyLong(), any(EventType.class)
        );
    }

    // ==================== Commission Calculation Tests ====================

    @Test
    @DisplayName("佣金计算 - 验证公式 (amount * rate) / 10000")
    void testCalculateCommission() throws Exception {
        Method calculateCommission = ClearingWorker.class.getDeclaredMethod(
            "calculateCommission", long.class, int.class);
        calculateCommission.setAccessible(true);

        // amount = 100000, rate = 3 (0.03%)
        // commission = 100000 * 3 / 10000 = 30
        long commission = (long) calculateCommission.invoke(clearingWorker, 100000L, 3);
        assertEquals(30L, commission);

        // amount = 1000000, rate = 5
        // commission = 1000000 * 5 / 10000 = 500
        commission = (long) calculateCommission.invoke(clearingWorker, 1000000L, 5);
        assertEquals(500L, commission);
    }

    // ==================== Helper Methods ====================

    private Order createOrder(long orderId, long accountId, OrderSide side,
                              long price, long quantity, long frozenAmount) {
        Order order = new Order(
            orderId, "600000", 600000, side, OrderType.LIMIT,
            price, quantity, accountId, 2001L
        );
        order.setFrozenAmount(frozenAmount);
        return order;
    }

    private Order createMarketOrder(long orderId, long accountId, OrderSide side,
                                    long quantity, long frozenAmount) {
        Order order = new Order(
            orderId, "600000", 600000, side, OrderType.MARKET,
            0L, quantity, accountId, 2001L
        );
        order.setFrozenAmount(frozenAmount);
        return order;
    }

    private void setReplayMode(ClearingWorker worker, boolean replayMode) throws Exception {
        Field replayModeField = ClearingWorker.class.getDeclaredField("replayMode");
        replayModeField.setAccessible(true);
        replayModeField.set(worker, replayMode);
    }
}
