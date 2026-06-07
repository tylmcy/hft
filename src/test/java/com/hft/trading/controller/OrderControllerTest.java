package com.hft.trading.controller;

import com.hft.trading.dto.OrderRequest;
import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.TradingPhase;
import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.lifecycle.PhaseManager;
import com.hft.trading.model.*;
import com.hft.trading.queue.AccountInputQueue;
import com.hft.trading.queue.QueueManager;
import com.hft.trading.service.IdempotencyService;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import com.hft.trading.store.InMemoryPositionStore;
import com.hft.trading.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * OrderController Unit Tests
 *
 * Tests REST API endpoints for order management using Mockito.
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private SnowflakeIdGenerator idGenerator;

    @Mock
    private InMemoryAccountStore accountStore;

    @Mock
    private InMemoryPositionStore positionStore;

    @Mock
    private InMemoryInstrumentStore instrumentStore;

    @Mock
    private InMemoryOrderStore orderStore;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private QueueManager queueManager;

    @Mock
    private AccountInputQueue accountInputQueue;

    @Mock
    private PhaseManager phaseManager;

    @Mock
    private LifecycleManager lifecycleManager;

    private OrderController orderController;

    @BeforeEach
    void setUp() {
        // Mock queueManager to return accountInputQueue
        when(queueManager.getAccountInputQueue()).thenReturn(accountInputQueue);

        orderController = new OrderController(
            idGenerator,
            accountStore,
            positionStore,
            instrumentStore,
            orderStore,
            idempotencyService,
            queueManager,
            phaseManager,
            lifecycleManager
        );
    }

    // ==================== Submit Order Tests ====================

    @Test
    @DisplayName("testPlaceOrder_Success - verify order is created")
    void testPlaceOrder_Success() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        when(phaseManager.canPlaceOrder()).thenReturn(true);

        // Mock idempotency check - first call returns PROCESSING
        IdempotencyService.Result idempotentResult = mock(IdempotencyService.Result.class);
        when(idempotentResult.getStatus()).thenReturn(IdempotencyService.Status.PROCESSING);
        when(idempotentResult.getOrderId()).thenReturn(null);
        when(idempotencyService.checkOrRegister(any(), anyLong())).thenReturn(idempotentResult);

        // Mock account
        Account account = new Account(1001L, 2001L);
        account.deposit(1_000_000_00L);
        when(accountStore.getAccount(1001L)).thenReturn(account);

        // Mock instrument
        Instrument instrument = new Instrument(
            "600000", 600000, InstrumentType.STOCK, "Pufa Bank",
            105000L, 0.10, 0.10, 100L, 1L, 3
        );
        when(instrumentStore.getInstrument("600000")).thenReturn(instrument);

        // Mock ID generator
        when(idGenerator.nextId()).thenReturn(10001L);

        // Mock queue write
        when(accountInputQueue.writeEvent(any(AccountInputEvent.class))).thenReturn(1L);

        // Create request
        OrderRequest request = new OrderRequest();
        request.setAccountId("1001");
        request.setSymbol("600000");
        request.setSide("BUY");
        request.setOrderType("LIMIT");
        request.setPrice("100000"); // 1000.00 yuan
        request.setQuantity("100");

        // Act
        var response = orderController.submitOrder(request);

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals("10001", response.getData().getOrderId());
        assertEquals("PENDING", response.getData().getStatus());

        // Verify order was added to store
        verify(orderStore).addOrder(any(Order.class));
        verify(accountInputQueue).writeEvent(any(AccountInputEvent.class));
    }

    @Test
    @DisplayName("testPlaceOrder_InsufficientFunds - verify 400 error for sell without position")
    void testPlaceOrder_InsufficientFunds() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        when(phaseManager.canPlaceOrder()).thenReturn(true);

        // Mock idempotency check
        IdempotencyService.Result idempotentResult = mock(IdempotencyService.Result.class);
        when(idempotentResult.getStatus()).thenReturn(IdempotencyService.Status.PROCESSING);
        when(idempotentResult.getOrderId()).thenReturn(null);
        when(idempotencyService.checkOrRegister(any(), anyLong())).thenReturn(idempotentResult);

        // Mock account
        Account account = new Account(1001L, 2001L);
        account.deposit(1_000_000_00L);
        when(accountStore.getAccount(1001L)).thenReturn(account);

        // Mock instrument
        Instrument instrument = new Instrument(
            "600000", 600000, InstrumentType.STOCK, "Pufa Bank",
            105000L, 0.10, 0.10, 100L, 1L, 3
        );
        when(instrumentStore.getInstrument("600000")).thenReturn(instrument);

        // Mock no position for sell
        when(positionStore.getPosition(1001L, "600000")).thenReturn(null);

        // Create SELL request
        OrderRequest request = new OrderRequest();
        request.setAccountId("1001");
        request.setSymbol("600000");
        request.setSide("SELL");
        request.setOrderType("LIMIT");
        request.setPrice("100000");
        request.setQuantity("100");

        // Act
        var response = orderController.submitOrder(request);

        // Assert
        assertNotNull(response);
        assertEquals("2002", response.getCode());
        assertEquals("持仓不足", response.getMessage());
    }

    @Test
    @DisplayName("testPlaceOrder_AccountNotFound - verify error for non-existent account")
    void testPlaceOrder_AccountNotFound() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        when(phaseManager.canPlaceOrder()).thenReturn(true);

        // Mock idempotency check
        IdempotencyService.Result idempotentResult = mock(IdempotencyService.Result.class);
        when(idempotentResult.getStatus()).thenReturn(IdempotencyService.Status.PROCESSING);
        when(idempotentResult.getOrderId()).thenReturn(null);
        when(idempotencyService.checkOrRegister(any(), anyLong())).thenReturn(idempotentResult);

        // Mock account not found
        when(accountStore.getAccount(anyLong())).thenReturn(null);

        // Create request
        OrderRequest request = new OrderRequest();
        request.setAccountId("9999");
        request.setSymbol("600000");
        request.setSide("BUY");
        request.setOrderType("LIMIT");
        request.setPrice("100000");
        request.setQuantity("100");

        // Act
        var response = orderController.submitOrder(request);

        // Assert
        assertNotNull(response);
        assertEquals("2004", response.getCode());
        assertEquals("账户不存在", response.getMessage());
    }

    @Test
    @DisplayName("testPlaceOrder_SymbolNotFound - verify error for non-existent symbol")
    void testPlaceOrder_SymbolNotFound() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        when(phaseManager.canPlaceOrder()).thenReturn(true);

        // Mock idempotency check
        IdempotencyService.Result idempotentResult = mock(IdempotencyService.Result.class);
        when(idempotentResult.getStatus()).thenReturn(IdempotencyService.Status.PROCESSING);
        when(idempotentResult.getOrderId()).thenReturn(null);
        when(idempotencyService.checkOrRegister(any(), anyLong())).thenReturn(idempotentResult);

        // Mock account
        Account account = new Account(1001L, 2001L);
        when(accountStore.getAccount(1001L)).thenReturn(account);

        // Mock instrument not found
        when(instrumentStore.getInstrument("999999")).thenReturn(null);

        // Create request
        OrderRequest request = new OrderRequest();
        request.setAccountId("1001");
        request.setSymbol("999999");
        request.setSide("BUY");
        request.setOrderType("LIMIT");
        request.setPrice("100000");
        request.setQuantity("100");

        // Act
        var response = orderController.submitOrder(request);

        // Assert
        assertNotNull(response);
        assertEquals("4003", response.getCode());
        assertEquals("品种不存在", response.getMessage());
    }

    @Test
    @DisplayName("testPlaceOrder_PriceOutOfRange - verify error for price outside limits")
    void testPlaceOrder_PriceOutOfRange() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        when(phaseManager.canPlaceOrder()).thenReturn(true);

        // Mock idempotency check
        IdempotencyService.Result idempotentResult = mock(IdempotencyService.Result.class);
        when(idempotentResult.getStatus()).thenReturn(IdempotencyService.Status.PROCESSING);
        when(idempotentResult.getOrderId()).thenReturn(null);
        when(idempotencyService.checkOrRegister(any(), anyLong())).thenReturn(idempotentResult);

        // Mock account
        Account account = new Account(1001L, 2001L);
        account.deposit(1_000_000_00L);
        when(accountStore.getAccount(1001L)).thenReturn(account);

        // Mock instrument with limit prices
        Instrument instrument = new Instrument(
            "600000", 600000, InstrumentType.STOCK, "Pufa Bank",
            100000L, 0.10, 0.10, 100L, 1L, 3
        );
        // limitUp = 110000 (1100.00), limitDown = 90000 (900.00)
        when(instrumentStore.getInstrument("600000")).thenReturn(instrument);

        // Create request with price above limit up
        OrderRequest request = new OrderRequest();
        request.setAccountId("1001");
        request.setSymbol("600000");
        request.setSide("BUY");
        request.setOrderType("LIMIT");
        request.setPrice("120000"); // Above limit up price
        request.setQuantity("100");

        // Act
        var response = orderController.submitOrder(request);

        // Assert
        assertNotNull(response);
        assertEquals("4002", response.getCode());
        assertEquals("价格超出涨跌停范围", response.getMessage());
    }

    @Test
    @DisplayName("testPlaceOrder_NonTradingTime - verify error when market closed")
    void testPlaceOrder_NonTradingTime() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        when(phaseManager.canPlaceOrder()).thenReturn(false);
        when(phaseManager.getCurrentPhase()).thenReturn(TradingPhase.CLOSED);

        // Create request
        OrderRequest request = new OrderRequest();
        request.setAccountId("1001");
        request.setSymbol("600000");
        request.setSide("BUY");
        request.setOrderType("LIMIT");
        request.setPrice("100000");
        request.setQuantity("100");

        // Act
        var response = orderController.submitOrder(request);

        // Assert
        assertNotNull(response);
        assertEquals("4001", response.getCode());
        assertEquals("非交易时段", response.getMessage());
    }

    @Test
    @DisplayName("testPlaceOrder_SystemPaused - verify error when system paused")
    void testPlaceOrder_SystemPaused() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(false);

        // Create request
        OrderRequest request = new OrderRequest();
        request.setAccountId("1001");
        request.setSymbol("600000");
        request.setSide("BUY");
        request.setOrderType("LIMIT");
        request.setPrice("100000");
        request.setQuantity("100");

        // Act
        var response = orderController.submitOrder(request);

        // Assert
        assertNotNull(response);
        assertEquals("4005", response.getCode());
        assertEquals("系统暂停服务", response.getMessage());
    }

    // ==================== Cancel Order Tests ====================

    @Test
    @DisplayName("testCancelOrder_Success - verify cancel accepted")
    void testCancelOrder_Success() {
        // Arrange
        when(lifecycleManager.canCancelOrder()).thenReturn(true);

        // Mock order
        Order order = new Order(
            10001L, "600000", 600000, OrderSide.BUY, OrderType.LIMIT,
            100000L, 100L, 1001L, 2001L
        );
        when(orderStore.getOrder(10001L)).thenReturn(order);

        // Mock queue write
        when(accountInputQueue.writeEvent(any(AccountInputEvent.class))).thenReturn(1L);

        // Act
        var response = orderController.cancelOrder("10001");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertEquals("PENDING_CANCEL", response.getData().getStatus());

        // Verify cancel event was written
        verify(accountInputQueue).writeEvent(any(AccountInputEvent.class));
    }

    @Test
    @DisplayName("testCancelOrder_NotFound - verify 404 error")
    void testCancelOrder_NotFound() {
        // Arrange
        when(lifecycleManager.canCancelOrder()).thenReturn(true);
        when(orderStore.getOrder(anyLong())).thenReturn(null);

        // Act
        var response = orderController.cancelOrder("99999");

        // Assert
        assertNotNull(response);
        assertEquals("3001", response.getCode());
        assertEquals("订单不存在", response.getMessage());
    }

    @Test
    @DisplayName("testCancelOrder_AlreadyFilled - verify error for filled order")
    void testCancelOrder_AlreadyFilled() {
        // Arrange
        when(lifecycleManager.canCancelOrder()).thenReturn(true);

        // Mock filled order
        Order order = new Order(
            10001L, "600000", 600000, OrderSide.BUY, OrderType.LIMIT,
            100000L, 100L, 1001L, 2001L
        );
        order.updateStatus(OrderStatus.NEW);
        order.updateFilledQuantity(100L); // Now FILLED
        when(orderStore.getOrder(10001L)).thenReturn(order);

        // Act
        var response = orderController.cancelOrder("10001");

        // Assert
        assertNotNull(response);
        assertEquals("3002", response.getCode());
        assertEquals("订单已成交", response.getMessage());
    }

    @Test
    @DisplayName("testCancelOrder_AlreadyCancelled - verify error for cancelled order")
    void testCancelOrder_AlreadyCancelled() {
        // Arrange
        when(lifecycleManager.canCancelOrder()).thenReturn(true);

        // Mock cancelled order
        Order order = new Order(
            10001L, "600000", 600000, OrderSide.BUY, OrderType.LIMIT,
            100000L, 100L, 1001L, 2001L
        );
        order.updateStatus(OrderStatus.NEW);
        order.updateStatus(OrderStatus.CANCELLED);
        when(orderStore.getOrder(10001L)).thenReturn(order);

        // Act
        var response = orderController.cancelOrder("10001");

        // Assert
        assertNotNull(response);
        assertEquals("3002", response.getCode());
        assertEquals("订单已撤销", response.getMessage());
    }

    // ==================== Get Order Tests ====================

    @Test
    @DisplayName("testGetOrder_Success - verify order details returned")
    void testGetOrder_Success() {
        // Arrange
        Order order = new Order(
            10001L, "600000", 600000, OrderSide.BUY, OrderType.LIMIT,
            100000L, 100L, 1001L, 2001L
        );
        order.updateStatus(OrderStatus.NEW);
        when(orderStore.getOrder(10001L)).thenReturn(order);

        // Act
        var response = orderController.getOrder("10001");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals("10001", response.getData().getOrderId());
        assertEquals("600000", response.getData().getSymbol());
        assertEquals("BUY", response.getData().getSide());
        assertEquals("LIMIT", response.getData().getOrderType());
        assertEquals("100000", response.getData().getPrice());
        assertEquals("100", response.getData().getQuantity());
    }

    @Test
    @DisplayName("testGetOrder_NotFound - verify 404 error")
    void testGetOrder_NotFound() {
        // Arrange
        when(orderStore.getOrder(anyLong())).thenReturn(null);

        // Act
        var response = orderController.getOrder("99999");

        // Assert
        assertNotNull(response);
        assertEquals("3001", response.getCode());
        assertEquals("订单不存在", response.getMessage());
    }

    // ==================== Get Active Orders Tests ====================

    @Test
    @DisplayName("testGetActiveOrders - verify list returned")
    void testGetActiveOrders() {
        // Arrange
        Order order1 = new Order(
            10001L, "600000", 600000, OrderSide.BUY, OrderType.LIMIT,
            100000L, 100L, 1001L, 2001L
        );
        order1.updateStatus(OrderStatus.NEW);

        Order order2 = new Order(
            10002L, "600001", 600001, OrderSide.SELL, OrderType.LIMIT,
            200000L, 200L, 1001L, 2001L
        );
        order2.updateStatus(OrderStatus.NEW);

        when(orderStore.getOrdersByAccount(1001L)).thenReturn(java.util.List.of(order1, order2));

        // Act
        var response = orderController.getActiveOrders("1001");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(2, response.getData().size());
    }

    @Test
    @DisplayName("testGetActiveOrders_AllStatuses - verify only active orders returned")
    void testGetActiveOrders_AllStatuses() {
        // Arrange
        Order activeOrder = new Order(
            10001L, "600000", 600000, OrderSide.BUY, OrderType.LIMIT,
            100000L, 100L, 1001L, 2001L
        );
        activeOrder.updateStatus(OrderStatus.NEW);

        Order filledOrder = new Order(
            10002L, "600001", 600001, OrderSide.SELL, OrderType.LIMIT,
            200000L, 200L, 1001L, 2001L
        );
        filledOrder.updateStatus(OrderStatus.NEW);
        filledOrder.updateFilledQuantity(200L); // Now FILLED

        Order cancelledOrder = new Order(
            10003L, "600002", 600002, OrderSide.BUY, OrderType.LIMIT,
            300000L, 300L, 1001L, 2001L
        );
        cancelledOrder.updateStatus(OrderStatus.NEW);
        cancelledOrder.updateStatus(OrderStatus.CANCELLED);

        when(orderStore.getOrdersByAccount(1001L))
            .thenReturn(java.util.List.of(activeOrder, filledOrder, cancelledOrder));

        // Act
        var response = orderController.getActiveOrders("1001");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());
        assertEquals("10001", response.getData().get(0).getOrderId());
    }
}
