package com.hft.trading.worker;

import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.CancelSuccessEvent;
import com.hft.trading.event.TradeEvent;
import com.hft.trading.model.*;
import com.hft.trading.queue.MatchingOutputQueue;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MatchingEngineWorker 单元测试
 *
 * 根据 V12 技术方案测试：
 * - NEW_ORDER 处理：限价单/市价单/FAK/FOK
 * - CANCEL_ORDER 处理：撤单成功/失败
 * - TRADE 事件生成
 * - 订单簿操作
 */
class MatchingEngineWorkerTest {

    private InMemoryOrderStore orderStore;
    private InMemoryInstrumentStore instrumentStore;
    private MatchingEngineWorker matchingEngineWorker;
    private MatchingOutputQueue mockOutputQueue;

    @BeforeEach
    void setUp() throws Exception {
        orderStore = new InMemoryOrderStore();
        instrumentStore = new InMemoryInstrumentStore();
        mockOutputQueue = mock(MatchingOutputQueue.class);

        // Create MatchingEngineWorker with mock queue
        matchingEngineWorker = new MatchingEngineWorker(
            orderStore,
            instrumentStore,
            null, // MatchingInputQueue - not needed for direct method tests
            mockOutputQueue,
            null  // WebSocketPushService
        );
    }

    // ==================== NEW_ORDER Limit Order Tests ====================

    @Test
    @DisplayName("处理 NEW_ORDER 限价买单 - 无对手盘时加入订单簿")
    void testProcessNewOrder_LimitBuy_NoCounterparty() throws Exception {
        // Setup: Create buy order
        long orderId = 10001L;
        Order order = createLimitOrder(orderId, 1001L, OrderSide.BUY, 1000L, 100L);
        orderStore.addOrder(order);

        // Create event
        AccountInputEvent event = AccountInputEvent.newOrder(
            orderId, 1001L, 2001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 1000L, 100L, 100000L, "idem-1"
        );

        // Get matching engine
        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");

        // Invoke processNewOrder
        Method processNewOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processNewOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processNewOrder.setAccessible(true);
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Verify ORDER_UPDATE written
        verify(mockOutputQueue).writeOrderUpdateEvent(eq(orderId), eq("600000"), eq("NEW"));

        // Verify order added to order book
        assertTrue(engine.getOrderBook().getBuyOrderCount() > 0);
    }

    @Test
    @DisplayName("处理 NEW_ORDER 限价卖单 - 无对手盘时加入订单簿")
    void testProcessNewOrder_LimitSell_NoCounterparty() throws Exception {
        // Setup
        long orderId = 10001L;
        Order order = createLimitOrder(orderId, 1001L, OrderSide.SELL, 1000L, 100L);
        orderStore.addOrder(order);

        AccountInputEvent event = AccountInputEvent.newOrder(
            orderId, 1001L, 2001L, "600000",
            OrderSide.SELL, OrderType.LIMIT, 1000L, 100L, 0L, "idem-1"
        );

        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");

        Method processNewOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processNewOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processNewOrder.setAccessible(true);
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Verify order added to sell side
        assertTrue(engine.getOrderBook().getSellOrderCount() > 0);
    }

    @Test
    @DisplayName("处理 NEW_ORDER 限价买单 - 与卖盘撮合")
    void testProcessNewOrder_LimitBuy_Matching() throws Exception {
        // Setup: Add sell order to order book first
        long sellOrderId = 10001L;
        Order sellOrder = createLimitOrder(sellOrderId, 1002L, OrderSide.SELL, 1000L, 100L);
        sellOrder.updateStatus(OrderStatus.NEW);
        orderStore.addOrder(sellOrder);

        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");
        engine.getOrderBook().addOrder(sellOrder);

        // Create buy order that matches
        long buyOrderId = 10002L;
        Order buyOrder = createLimitOrder(buyOrderId, 1001L, OrderSide.BUY, 1000L, 100L);
        orderStore.addOrder(buyOrder);

        AccountInputEvent event = AccountInputEvent.newOrder(
            buyOrderId, 1001L, 2001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 1000L, 100L, 100000L, "idem-1"
        );

        Method processNewOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processNewOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processNewOrder.setAccessible(true);
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Verify TRADE generated
        verify(mockOutputQueue).writeTradeEvent(any(TradeEvent.class));
    }

    // ==================== NEW_ORDER Market Order Tests ====================

    @Test
    @DisplayName("处理 NEW_ORDER 市价买单 - 与卖盘撮合")
    void testProcessNewOrder_MarketBuy() throws Exception {
        // Setup: Add sell order to order book
        long sellOrderId = 10001L;
        Order sellOrder = createLimitOrder(sellOrderId, 1002L, OrderSide.SELL, 1000L, 100L);
        sellOrder.updateStatus(OrderStatus.NEW);
        orderStore.addOrder(sellOrder);

        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");
        engine.getOrderBook().addOrder(sellOrder);

        // Create market buy order
        long buyOrderId = 10002L;
        Order buyOrder = createMarketOrder(buyOrderId, 1001L, OrderSide.BUY, 100L);
        orderStore.addOrder(buyOrder);

        AccountInputEvent event = AccountInputEvent.newOrder(
            buyOrderId, 1001L, 2001L, "600000",
            OrderSide.BUY, OrderType.MARKET, 0L, 100L, 100000L, "idem-1"
        );

        Method processNewOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processNewOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processNewOrder.setAccessible(true);
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Verify TRADE generated
        verify(mockOutputQueue).writeTradeEvent(any(TradeEvent.class));

        // Market order should not stay in order book
        assertEquals(0, engine.getOrderBook().getBuyOrderCount());
    }

    @Test
    @DisplayName("处理 NEW_ORDER 市价卖单 - 与买盘撮合")
    void testProcessNewOrder_MarketSell() throws Exception {
        // Setup: Add buy order to order book
        long buyOrderId = 10001L;
        Order buyOrder = createLimitOrder(buyOrderId, 1002L, OrderSide.BUY, 1000L, 100L);
        buyOrder.updateStatus(OrderStatus.NEW);
        orderStore.addOrder(buyOrder);

        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");
        engine.getOrderBook().addOrder(buyOrder);

        // Create market sell order
        long sellOrderId = 10002L;
        Order sellOrder = createMarketOrder(sellOrderId, 1001L, OrderSide.SELL, 100L);
        orderStore.addOrder(sellOrder);

        AccountInputEvent event = AccountInputEvent.newOrder(
            sellOrderId, 1001L, 2001L, "600000",
            OrderSide.SELL, OrderType.MARKET, 0L, 100L, 0L, "idem-1"
        );

        Method processNewOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processNewOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processNewOrder.setAccessible(true);
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Verify TRADE generated
        verify(mockOutputQueue).writeTradeEvent(any(TradeEvent.class));
    }

    // ==================== FAK Order Tests ====================

    @Test
    @DisplayName("处理 NEW_ORDER FAK 买单 - 部分成交剩余撤销")
    void testProcessNewOrder_FAK_PartialFill() throws Exception {
        // Setup: Add sell order with less quantity
        long sellOrderId = 10001L;
        Order sellOrder = createLimitOrder(sellOrderId, 1002L, OrderSide.SELL, 1000L, 50L);
        sellOrder.updateStatus(OrderStatus.NEW);
        orderStore.addOrder(sellOrder);

        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");
        engine.getOrderBook().addOrder(sellOrder);

        // Create FAK buy order for 100 shares
        long buyOrderId = 10002L;
        Order buyOrder = createFAKOrder(buyOrderId, 1001L, OrderSide.BUY, 1000L, 100L);
        orderStore.addOrder(buyOrder);

        AccountInputEvent event = AccountInputEvent.newOrder(
            buyOrderId, 1001L, 2001L, "600000",
            OrderSide.BUY, OrderType.FAK, 1000L, 100L, 100000L, "idem-1"
        );

        Method processNewOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processNewOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processNewOrder.setAccessible(true);
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Verify TRADE generated (50 shares)
        verify(mockOutputQueue).writeTradeEvent(any(TradeEvent.class));

        // Verify CANCEL_SUCCESS for remaining 50 shares
        verify(mockOutputQueue).writeCancelSuccessEvent(any(CancelSuccessEvent.class));
    }

    @Test
    @DisplayName("处理 NEW_ORDER FAK 买单 - 无对手盘立即撤销")
    void testProcessNewOrder_FAK_NoCounterparty() throws Exception {
        // No orders in book
        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");

        // Create FAK buy order
        long buyOrderId = 10001L;
        Order buyOrder = createFAKOrder(buyOrderId, 1001L, OrderSide.BUY, 1000L, 100L);
        orderStore.addOrder(buyOrder);

        AccountInputEvent event = AccountInputEvent.newOrder(
            buyOrderId, 1001L, 2001L, "600000",
            OrderSide.BUY, OrderType.FAK, 1000L, 100L, 100000L, "idem-1"
        );

        Method processNewOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processNewOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processNewOrder.setAccessible(true);
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Verify CANCEL_SUCCESS for entire quantity (no trade)
        verify(mockOutputQueue, never()).writeTradeEvent(any(TradeEvent.class));
        verify(mockOutputQueue).writeCancelSuccessEvent(any(CancelSuccessEvent.class));
    }

    // ==================== FOK Order Tests ====================

    @Test
    @DisplayName("处理 NEW_ORDER FOK 买单 - 全部成交")
    void testProcessNewOrder_FOK_FullFill() throws Exception {
        // Setup: Add sell order with enough quantity
        long sellOrderId = 10001L;
        Order sellOrder = createLimitOrder(sellOrderId, 1002L, OrderSide.SELL, 1000L, 100L);
        sellOrder.updateStatus(OrderStatus.NEW);
        orderStore.addOrder(sellOrder);

        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");
        engine.getOrderBook().addOrder(sellOrder);

        // Create FOK buy order for 100 shares
        long buyOrderId = 10002L;
        Order buyOrder = createFOKOrder(buyOrderId, 1001L, OrderSide.BUY, 1000L, 100L);
        orderStore.addOrder(buyOrder);

        AccountInputEvent event = AccountInputEvent.newOrder(
            buyOrderId, 1001L, 2001L, "600000",
            OrderSide.BUY, OrderType.FOK, 1000L, 100L, 100000L, "idem-1"
        );

        Method processNewOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processNewOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processNewOrder.setAccessible(true);
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Verify TRADE generated (full 100 shares)
        verify(mockOutputQueue).writeTradeEvent(any(TradeEvent.class));
    }

    @Test
    @DisplayName("处理 NEW_ORDER FOK 买单 - 无法全部成交时撤销")
    void testProcessNewOrder_FOK_NotFillable() throws Exception {
        // Setup: Add sell order with insufficient quantity
        long sellOrderId = 10001L;
        Order sellOrder = createLimitOrder(sellOrderId, 1002L, OrderSide.SELL, 1000L, 50L);
        sellOrder.updateStatus(OrderStatus.NEW);
        orderStore.addOrder(sellOrder);

        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");
        engine.getOrderBook().addOrder(sellOrder);

        // Create FOK buy order for 100 shares (only 50 available)
        long buyOrderId = 10002L;
        Order buyOrder = createFOKOrder(buyOrderId, 1001L, OrderSide.BUY, 1000L, 100L);
        orderStore.addOrder(buyOrder);

        AccountInputEvent event = AccountInputEvent.newOrder(
            buyOrderId, 1001L, 2001L, "600000",
            OrderSide.BUY, OrderType.FOK, 1000L, 100L, 100000L, "idem-1"
        );

        Method processNewOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processNewOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processNewOrder.setAccessible(true);
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Verify NO trade
        verify(mockOutputQueue, never()).writeTradeEvent(any(TradeEvent.class));

        // Verify CANCEL_SUCCESS with FOK_NOT_FILLABLE reason
        verify(mockOutputQueue).writeCancelSuccessEvent(any(CancelSuccessEvent.class));
    }

    // ==================== CANCEL_ORDER Tests ====================

    @Test
    @DisplayName("处理 CANCEL_ORDER 成功 - 订单从订单簿移除")
    void testProcessCancelOrder_Success() throws Exception {
        // Setup: Add order to order book
        long orderId = 10001L;
        Order order = createLimitOrder(orderId, 1001L, OrderSide.BUY, 1000L, 100L);
        order.updateStatus(OrderStatus.NEW);
        orderStore.addOrder(order);

        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");
        engine.getOrderBook().addOrder(order);

        // Create cancel event
        AccountInputEvent event = AccountInputEvent.cancelOrder(orderId, 1001L, CancelReason.USER_CANCEL);

        Method processCancelOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processCancelOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processCancelOrder.setAccessible(true);
        processCancelOrder.invoke(matchingEngineWorker, event, engine);

        // Verify order removed from book
        assertNull(engine.getOrderBook().findOrder(orderId));

        // Verify CANCEL_SUCCESS generated
        verify(mockOutputQueue).writeCancelSuccessEvent(any(CancelSuccessEvent.class));
    }

    @Test
    @DisplayName("处理 CANCEL_ORDER - 订单不在订单簿但为 PENDING 状态")
    void testProcessCancelOrder_PendingOrder() throws Exception {
        // Setup: Create PENDING order (not in order book yet)
        long orderId = 10001L;
        Order order = createLimitOrder(orderId, 1001L, OrderSide.BUY, 1000L, 100L);
        order.setFrozenAmount(100000L);
        orderStore.addOrder(order);

        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");

        AccountInputEvent event = AccountInputEvent.cancelOrder(orderId, 1001L, CancelReason.USER_CANCEL);

        Method processCancelOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processCancelOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processCancelOrder.setAccessible(true);
        processCancelOrder.invoke(matchingEngineWorker, event, engine);

        // Verify CANCEL_SUCCESS generated for PENDING order
        verify(mockOutputQueue).writeCancelSuccessEvent(any(CancelSuccessEvent.class));
    }

    @Test
    @DisplayName("处理 CANCEL_ORDER - 订单不存在")
    void testProcessCancelOrder_OrderNotFound() throws Exception {
        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");

        AccountInputEvent event = AccountInputEvent.cancelOrder(99999L, 1001L, CancelReason.USER_CANCEL);

        Method processCancelOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processCancelOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processCancelOrder.setAccessible(true);

        // Should not throw
        assertDoesNotThrow(() -> processCancelOrder.invoke(matchingEngineWorker, event, engine));
    }

    // ==================== Idempotency Tests ====================

    @Test
    @DisplayName("处理 NEW_ORDER 幂等检查 - 重复订单应跳过")
    void testProcessNewOrder_Idempotency() throws Exception {
        // Setup
        long orderId = 10001L;
        Order order = createLimitOrder(orderId, 1001L, OrderSide.BUY, 1000L, 100L);
        orderStore.addOrder(order);

        AccountInputEvent event = AccountInputEvent.newOrder(
            orderId, 1001L, 2001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 1000L, 100L, 100000L, "idem-1"
        );

        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");

        Method processNewOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processNewOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processNewOrder.setAccessible(true);

        // Process first time
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Process same order again
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Verify ORDER_UPDATE written only once (idempotent)
        verify(mockOutputQueue, times(1)).writeOrderUpdateEvent(anyLong(), anyString(), anyString());
    }

    // ==================== Trade Generation Tests ====================

    @Test
    @DisplayName("验证 TRADE 事件正确生成 - 买卖双方信息")
    void testTradeGeneration() throws Exception {
        // Setup: Add sell order
        long sellOrderId = 10001L;
        long sellAccountId = 1002L;
        Order sellOrder = createLimitOrder(sellOrderId, sellAccountId, OrderSide.SELL, 1000L, 100L);
        sellOrder.updateStatus(OrderStatus.NEW);
        orderStore.addOrder(sellOrder);

        com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine("600000");
        engine.getOrderBook().addOrder(sellOrder);

        // Create buy order
        long buyOrderId = 10002L;
        long buyAccountId = 1001L;
        Order buyOrder = createLimitOrder(buyOrderId, buyAccountId, OrderSide.BUY, 1000L, 100L);
        orderStore.addOrder(buyOrder);

        AccountInputEvent event = AccountInputEvent.newOrder(
            buyOrderId, buyAccountId, 2001L, "600000",
            OrderSide.BUY, OrderType.LIMIT, 1000L, 100L, 100000L, "idem-1"
        );

        Method processNewOrder = MatchingEngineWorker.class.getDeclaredMethod(
            "processNewOrder", AccountInputEvent.class, com.hft.trading.matching.MatchingEngine.class);
        processNewOrder.setAccessible(true);
        processNewOrder.invoke(matchingEngineWorker, event, engine);

        // Verify TRADE event
        verify(mockOutputQueue).writeTradeEvent(argThat(trade ->
            trade.getSymbol().equals("600000") &&
            trade.getPrice() == 1000L &&
            trade.getQuantity() == 100L &&
            trade.getBuyAccountId() == buyAccountId &&
            trade.getSellAccountId() == sellAccountId &&
            trade.getBuyOrderId() == buyOrderId &&
            trade.getSellOrderId() == sellOrderId
        ));
    }

    // ==================== Helper Methods ====================

    private Order createLimitOrder(long orderId, long accountId, OrderSide side,
                                   long price, long quantity) {
        return new Order(
            orderId, "600000", 600000, side, OrderType.LIMIT,
            price, quantity, accountId, 2001L
        );
    }

    private Order createMarketOrder(long orderId, long accountId, OrderSide side, long quantity) {
        return new Order(
            orderId, "600000", 600000, side, OrderType.MARKET,
            0L, quantity, accountId, 2001L
        );
    }

    private Order createFAKOrder(long orderId, long accountId, OrderSide side,
                                 long price, long quantity) {
        return new Order(
            orderId, "600000", 600000, side, OrderType.FAK,
            price, quantity, accountId, 2001L
        );
    }

    private Order createFOKOrder(long orderId, long accountId, OrderSide side,
                                 long price, long quantity) {
        return new Order(
            orderId, "600000", 600000, side, OrderType.FOK,
            price, quantity, accountId, 2001L
        );
    }

    private com.hft.trading.matching.MatchingEngine getOrCreateEngine(String symbol) throws Exception {
        Method getOrCreateEngine = MatchingEngineWorker.class.getDeclaredMethod(
            "getOrCreateEngine", String.class);
        getOrCreateEngine.setAccessible(true);
        return (com.hft.trading.matching.MatchingEngine) getOrCreateEngine.invoke(matchingEngineWorker, symbol);
    }
}
