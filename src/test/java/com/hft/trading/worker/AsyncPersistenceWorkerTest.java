package com.hft.trading.worker;

import com.hft.trading.event.CancelSuccessEvent;
import com.hft.trading.event.EventType;
import com.hft.trading.event.TradeEvent;
import com.hft.trading.model.*;
import com.hft.trading.queue.MatchingOutputQueue;
import com.hft.trading.store.InMemoryOrderStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AsyncPersistenceWorker 单元测试
 *
 * 根据 V12 技术方案测试：
 * - TRADE 事件持久化到 trades_history
 * - ORDER_UPDATE 事件持久化到 orders_history
 * - CANCEL_SUCCESS 事件更新订单状态
 * - 幂等性检查
 */
class AsyncPersistenceWorkerTest {

    private InMemoryOrderStore orderStore;
    private MatchingOutputQueue mockQueue;
    private DataSource dataSource;
    private AsyncPersistenceWorker worker;

    @BeforeEach
    void setUp() throws Exception {
        orderStore = new InMemoryOrderStore();
        mockQueue = mock(MatchingOutputQueue.class);

        // Create H2 in-memory database with test schema
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        // Create tables manually for test isolation
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // trades_history table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS trades_history (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    trade_id VARCHAR(50) UNIQUE,
                    order_id BIGINT,
                    account_id BIGINT,
                    user_id BIGINT,
                    symbol VARCHAR(10),
                    side VARCHAR(5),
                    order_type VARCHAR(10),
                    price BIGINT,
                    order_price BIGINT,
                    quantity BIGINT,
                    amount BIGINT,
                    commission BIGINT,
                    frozen_amount_used BIGINT,
                    trade_time BIGINT,
                    trade_date VARCHAR(8)
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_trade_id ON trades_history(trade_id)");

            // orders_history table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS orders_history (
                    order_id BIGINT PRIMARY KEY,
                    account_id BIGINT,
                    user_id BIGINT,
                    symbol VARCHAR(10),
                    side VARCHAR(5),
                    order_type VARCHAR(10),
                    price BIGINT,
                    quantity BIGINT,
                    filled_quantity BIGINT,
                    status VARCHAR(20),
                    commission BIGINT,
                    frozen_amount BIGINT,
                    cancel_reason VARCHAR(100),
                    idempotency_key VARCHAR(50),
                    create_time BIGINT,
                    update_time BIGINT,
                    trade_date VARCHAR(8)
                )
                """);
        }

        // Create worker
        worker = new AsyncPersistenceWorker(orderStore, mockQueue, dataSource);
    }

    // ==================== TRADE Tests ====================

    @Test
    @DisplayName("处理 TRADE 事件 - 持久化买方和卖方成交记录")
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
            buyOrderId,
            sellOrderId,
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
        Method processTrade = AsyncPersistenceWorker.class.getDeclaredMethod("processTrade", TradeEvent.class);
        processTrade.setAccessible(true);
        processTrade.invoke(worker, tradeEvent);

        // Verify trades persisted to database
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Check buyer trade
            ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM trades_history WHERE trade_id = 'trade-001_B'");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));

            // Check seller trade
            rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM trades_history WHERE trade_id = 'trade-001_S'");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("处理 TRADE 事件 - 验证成交数据正确性")
    void testProcessTrade_DataCorrectness() throws Exception {
        // Setup
        long buyOrderId = 10001L;
        long sellOrderId = 10002L;

        Order buyOrder = createOrder(buyOrderId, 1001L, OrderSide.BUY, 1000L, 100L, 100000L);
        Order sellOrder = createOrder(sellOrderId, 1002L, OrderSide.SELL, 1000L, 100L, 0L);
        orderStore.addOrder(buyOrder);
        orderStore.addOrder(sellOrder);

        TradeEvent tradeEvent = TradeEvent.of(
            "trade-002", buyOrderId, sellOrderId, "600000",
            1000L, 100L,
            1001L, 1002L, buyOrderId, sellOrderId,
            OrderSide.BUY, OrderType.LIMIT, 1000L, 100L, 100000L, 3
        );

        Method processTrade = AsyncPersistenceWorker.class.getDeclaredMethod("processTrade", TradeEvent.class);
        processTrade.setAccessible(true);
        processTrade.invoke(worker, tradeEvent);

        // Verify trade data
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                "SELECT * FROM trades_history WHERE trade_id = 'trade-002_B'");
            assertTrue(rs.next());

            assertEquals(10001L, rs.getLong("order_id"));
            assertEquals(1001L, rs.getLong("account_id"));
            assertEquals("600000", rs.getString("symbol"));
            assertEquals("BUY", rs.getString("side"));
            assertEquals(1000L, rs.getLong("price"));
            assertEquals(100L, rs.getLong("quantity"));
            // Amount = 1000 * 100 = 100000
            assertEquals(100000L, rs.getLong("amount"));
            // Commission = 100000 * 3 / 10000 = 30
            assertEquals(30L, rs.getLong("commission"));
        }
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
            "trade-003", 10001L, 10002L, "600000",
            1000L, 100L,
            1001L, 1002L, 10001L, 10002L,
            OrderSide.BUY, OrderType.LIMIT, 1000L, 100L, 100000L, 3
        );

        Method processTrade = AsyncPersistenceWorker.class.getDeclaredMethod("processTrade", TradeEvent.class);
        processTrade.setAccessible(true);

        // Process first time
        processTrade.invoke(worker, tradeEvent);

        // Process same trade again
        processTrade.invoke(worker, tradeEvent);

        // Verify only 2 records (one for buyer, one for seller)
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM trades_history WHERE trade_id LIKE 'trade-003%'");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1)); // Only 2, not 4
        }
    }

    // ==================== ORDER_UPDATE Tests ====================

    @Test
    @DisplayName("处理 ORDER_UPDATE 事件 - 持久化订单")
    void testProcessOrderUpdate() throws Exception {
        // Setup: Create order
        long orderId = 10001L;
        Order order = createOrder(orderId, 1001L, OrderSide.BUY, 1000L, 100L, 100000L);
        orderStore.addOrder(order);

        // Create ORDER_UPDATE data
        MatchingOutputQueue.OrderUpdateData data = new MatchingOutputQueue.OrderUpdateData(
            orderId, "600000", EventType.ORDER_UPDATE
        );

        // Invoke processOrderUpdate
        Method processOrderUpdate = AsyncPersistenceWorker.class.getDeclaredMethod(
            "processOrderUpdate", MatchingOutputQueue.OrderUpdateData.class);
        processOrderUpdate.setAccessible(true);
        processOrderUpdate.invoke(worker, data);

        // Verify order persisted
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                "SELECT * FROM orders_history WHERE order_id = 10001");
            assertTrue(rs.next());

            assertEquals(1001L, rs.getLong("account_id"));
            assertEquals("600000", rs.getString("symbol"));
            assertEquals("BUY", rs.getString("side"));
            assertEquals("LIMIT", rs.getString("order_type"));
            assertEquals(1000L, rs.getLong("price"));
            assertEquals(100L, rs.getLong("quantity"));
        }
    }

    @Test
    @DisplayName("处理 ORDER_UPDATE 事件 - 订单不存在时忽略")
    void testProcessOrderUpdate_OrderNotFound() throws Exception {
        MatchingOutputQueue.OrderUpdateData data = new MatchingOutputQueue.OrderUpdateData(
            99999L, "600000", EventType.ORDER_UPDATE
        );

        Method processOrderUpdate = AsyncPersistenceWorker.class.getDeclaredMethod(
            "processOrderUpdate", MatchingOutputQueue.OrderUpdateData.class);
        processOrderUpdate.setAccessible(true);

        // Should not throw
        assertDoesNotThrow(() -> processOrderUpdate.invoke(worker, data));
    }

    @Test
    @DisplayName("处理 ORDER_UPDATE 幂等检查 - 重复事件应跳过")
    void testProcessOrderUpdate_Idempotency() throws Exception {
        // Setup
        Order order = createOrder(10001L, 1001L, OrderSide.BUY, 1000L, 100L, 100000L);
        orderStore.addOrder(order);

        MatchingOutputQueue.OrderUpdateData data = new MatchingOutputQueue.OrderUpdateData(
            10001L, "600000", EventType.ORDER_UPDATE
        );

        Method processOrderUpdate = AsyncPersistenceWorker.class.getDeclaredMethod(
            "processOrderUpdate", MatchingOutputQueue.OrderUpdateData.class);
        processOrderUpdate.setAccessible(true);

        // Process first time
        processOrderUpdate.invoke(worker, data);

        // Process same event again
        processOrderUpdate.invoke(worker, data);

        // Verify only 1 order record
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM orders_history WHERE order_id = 10001");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    // ==================== CANCEL_SUCCESS Tests ====================

    @Test
    @DisplayName("处理 CANCEL_SUCCESS 事件 - 更新订单状态")
    void testProcessCancelSuccess() throws Exception {
        // Setup
        long orderId = 10001L;
        Order order = createOrder(orderId, 1001L, OrderSide.BUY, 1000L, 100L, 100000L);
        order.updateStatus(OrderStatus.NEW);
        order.updateFilledQuantity(30L);
        order.setCancelReason(CancelReason.USER_CANCEL);
        orderStore.addOrder(order);

        // First persist the order
        Method persistOrder = AsyncPersistenceWorker.class.getDeclaredMethod(
            "persistOrder", Order.class);
        persistOrder.setAccessible(true);
        persistOrder.invoke(worker, order);

        // Create CANCEL_SUCCESS event
        CancelSuccessEvent cancelEvent = CancelSuccessEvent.forBuyOrder(
            orderId, 1001L, 2001L, "600000",
            30L, 70L, CancelReason.USER_CANCEL, 70000L
        );

        // Update order status to CANCELLED in memory
        order.updateStatus(OrderStatus.CANCELLED);

        // Invoke processCancelSuccess
        Method processCancelSuccess = AsyncPersistenceWorker.class.getDeclaredMethod(
            "processCancelSuccess", CancelSuccessEvent.class);
        processCancelSuccess.setAccessible(true);
        processCancelSuccess.invoke(worker, cancelEvent);

        // Verify order status updated in database
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                "SELECT status, filled_quantity FROM orders_history WHERE order_id = 10001");
            assertTrue(rs.next());
            assertEquals("CANCELLED", rs.getString("status"));
            assertEquals(30L, rs.getLong("filled_quantity"));
        }
    }

    // ==================== Commission Calculation Tests ====================

    @Test
    @DisplayName("佣金计算 - 验证公式 (amount * rate) / 10000")
    void testCommissionCalculation() throws Exception {
        // amount = 100000 (1000 yuan), rate = 3 (0.03%)
        // commission = 100000 * 3 / 10000 = 30 (0.30 yuan)

        Order buyOrder = createOrder(10001L, 1001L, OrderSide.BUY, 1000L, 100L, 100000L);
        Order sellOrder = createOrder(10002L, 1002L, OrderSide.SELL, 1000L, 100L, 0L);
        orderStore.addOrder(buyOrder);
        orderStore.addOrder(sellOrder);

        TradeEvent tradeEvent = TradeEvent.of(
            "trade-commission", 10001L, 10002L, "600000",
            1000L, 100L,
            1001L, 1002L, 10001L, 10002L,
            OrderSide.BUY, OrderType.LIMIT, 1000L, 100L, 100000L, 3
        );

        Method processTrade = AsyncPersistenceWorker.class.getDeclaredMethod("processTrade", TradeEvent.class);
        processTrade.setAccessible(true);
        processTrade.invoke(worker, tradeEvent);

        // Verify commission
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                "SELECT commission FROM trades_history WHERE trade_id = 'trade-commission_B'");
            assertTrue(rs.next());
            assertEquals(30L, rs.getLong("commission"));
        }
    }

    // ==================== Worker Lifecycle Tests ====================

    @Test
    @DisplayName("Worker 生命周期 - start 和 stop")
    void testWorkerLifecycle() {
        AsyncPersistenceWorker newWorker = new AsyncPersistenceWorker(
            orderStore, mockQueue, dataSource);

        assertFalse(newWorker.isRunning());

        newWorker.start();
        assertTrue(newWorker.isRunning());

        newWorker.stop();
        assertFalse(newWorker.isRunning());
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
}
