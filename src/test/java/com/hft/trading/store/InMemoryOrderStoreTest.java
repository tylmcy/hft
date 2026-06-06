package com.hft.trading.store;

import com.hft.trading.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryOrderStore 测试
 *
 * 根据 V12 技术方案 3.2.3：
 * - 使用 ConcurrentHashMap<Long, Order> 存储
 * - 支持 getOrder/addOrder/updateStatus/updateFilledQuantity 操作
 */
class InMemoryOrderStoreTest {

    private InMemoryOrderStore orderStore;

    @BeforeEach
    void setUp() {
        orderStore = new InMemoryOrderStore();
    }

    @Test
    @DisplayName("应该能够添加和获取订单")
    void shouldAddAndGetOrder() {
        Order order = createLimitBuyOrder(1L);

        orderStore.addOrder(order);

        Order retrieved = orderStore.getOrder(1L);
        assertNotNull(retrieved);
        assertEquals(1L, retrieved.getOrderId());
    }

    @Test
    @DisplayName("获取不存在的订单应该返回 null")
    void shouldReturnNullForNonExistentOrder() {
        Order retrieved = orderStore.getOrder(9999L);
        assertNull(retrieved);
    }

    @Test
    @DisplayName("应该能够更新订单状态")
    void shouldUpdateOrderStatus() {
        Order order = createLimitBuyOrder(1L);
        orderStore.addOrder(order);

        orderStore.updateStatus(1L, OrderStatus.NEW);

        Order updated = orderStore.getOrder(1L);
        assertEquals(OrderStatus.NEW, updated.getStatus());
    }

    @Test
    @DisplayName("更新不存在订单的状态应该抛出异常")
    void shouldThrowWhenUpdateStatusForNonExistentOrder() {
        assertThrows(IllegalStateException.class,
            () -> orderStore.updateStatus(9999L, OrderStatus.NEW));
    }

    @Test
    @DisplayName("应该能够更新订单成交数量")
    void shouldUpdateFilledQuantity() {
        Order order = createLimitBuyOrder(1L);
        order.updateStatus(OrderStatus.NEW);
        orderStore.addOrder(order);

        orderStore.updateFilledQuantity(1L, 50L);

        Order updated = orderStore.getOrder(1L);
        assertEquals(50L, updated.getFilledQuantity());
        assertEquals(50L, updated.getRemainingQty());
        assertEquals(OrderStatus.PARTIAL_FILLED, updated.getStatus());
    }

    @Test
    @DisplayName("更新不存在订单的成交数量应该抛出异常")
    void shouldThrowWhenUpdateFilledQuantityForNonExistentOrder() {
        assertThrows(IllegalStateException.class,
            () -> orderStore.updateFilledQuantity(9999L, 50L));
    }

    @Test
    @DisplayName("应该能够移除订单")
    void shouldRemoveOrder() {
        Order order = createLimitBuyOrder(1L);
        orderStore.addOrder(order);

        orderStore.removeOrder(1L);

        assertNull(orderStore.getOrder(1L));
    }

    @Test
    @DisplayName("应该能够查询账户的活跃订单")
    void shouldGetActiveOrdersByAccount() {
        Order order1 = createLimitBuyOrder(1L, 1001L);
        order1.updateStatus(OrderStatus.NEW);
        Order order2 = createLimitBuyOrder(2L, 1001L);
        order2.updateStatus(OrderStatus.NEW);
        order2.updateFilledQuantity(50L); // PARTIAL_FILLED
        Order order3 = createLimitBuyOrder(3L, 1001L);
        order3.updateStatus(OrderStatus.NEW);
        order3.updateFilledQuantity(100L); // FILLED
        Order order4 = createLimitBuyOrder(4L, 1002L); // 不同账户

        orderStore.addOrder(order1);
        orderStore.addOrder(order2);
        orderStore.addOrder(order3);
        orderStore.addOrder(order4);

        List<Order> activeOrders = orderStore.getActiveOrdersByAccount(1001L);

        assertEquals(2, activeOrders.size()); // NEW 和 PARTIAL_FILLED
        assertTrue(activeOrders.stream().allMatch(o -> o.getAccountId() == 1001L));
        assertTrue(activeOrders.stream().allMatch(o ->
            o.getStatus() == OrderStatus.NEW || o.getStatus() == OrderStatus.PARTIAL_FILLED ||
            o.getStatus() == OrderStatus.PENDING));
    }

    @Test
    @DisplayName("应该能够查询账户的所有订单")
    void shouldGetAllOrdersByAccount() {
        Order order1 = createLimitBuyOrder(1L, 1001L);
        Order order2 = createLimitBuyOrder(2L, 1001L);
        Order order3 = createLimitBuyOrder(3L, 1002L);

        orderStore.addOrder(order1);
        orderStore.addOrder(order2);
        orderStore.addOrder(order3);

        List<Order> orders = orderStore.getOrdersByAccount(1001L);

        assertEquals(2, orders.size());
        assertTrue(orders.stream().allMatch(o -> o.getAccountId() == 1001L));
    }

    @Test
    @DisplayName("应该能够查询证券的活跃订单")
    void shouldGetActiveOrdersBySymbol() {
        Order order1 = createLimitBuyOrder(1L, "600000");
        order1.updateStatus(OrderStatus.NEW);
        Order order2 = createLimitBuyOrder(2L, "600000");
        order2.updateStatus(OrderStatus.CANCELLED);
        Order order3 = createLimitBuyOrder(3L, "600001");

        orderStore.addOrder(order1);
        orderStore.addOrder(order2);
        orderStore.addOrder(order3);

        List<Order> activeOrders = orderStore.getActiveOrdersBySymbol("600000");

        assertEquals(1, activeOrders.size());
        assertEquals("600000", activeOrders.get(0).getSymbol());
    }

    @Test
    @DisplayName("应该能够清空所有订单")
    void shouldClearAllOrders() {
        Order order1 = createLimitBuyOrder(1L);
        Order order2 = createLimitBuyOrder(2L);
        orderStore.addOrder(order1);
        orderStore.addOrder(order2);

        orderStore.clear();

        assertNull(orderStore.getOrder(1L));
        assertNull(orderStore.getOrder(2L));
        assertEquals(0, orderStore.size());
    }

    @Test
    @DisplayName("重复添加相同 orderId 应该失败")
    void shouldFailOnDuplicateOrderId() {
        Order order1 = createLimitBuyOrder(1L);
        orderStore.addOrder(order1);

        Order order2 = createLimitBuyOrder(1L); // 相同 orderId

        assertThrows(IllegalStateException.class, () -> orderStore.addOrder(order2));
    }

    @Test
    @DisplayName("应该能够根据幂等键查找订单")
    void shouldFindOrderByIdempotencyKey() {
        Order order = createLimitBuyOrder(1L);
        order.setIdempotencyKey("uuid-v4");
        orderStore.addOrder(order);

        Order found = orderStore.findByIdempotencyKey("uuid-v4");

        assertNotNull(found);
        assertEquals(1L, found.getOrderId());
    }

    @Test
    @DisplayName("不存在的幂等键应该返回 null")
    void shouldReturnNullForNonExistentIdempotencyKey() {
        Order found = orderStore.findByIdempotencyKey("non-existent");
        assertNull(found);
    }

    @Test
    @DisplayName("应该支持并发访问")
    void shouldSupportConcurrentAccess() throws InterruptedException {
        // 并发添加订单
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final long orderId = i + 1;
            threads[i] = new Thread(() -> {
                Order order = createLimitBuyOrder(orderId);
                orderStore.addOrder(order);
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(10, orderStore.size());
    }

    private Order createLimitBuyOrder(long orderId) {
        return new Order(orderId, "600000", 600000, OrderSide.BUY, OrderType.LIMIT, 105000L, 100L, 1001L, 2001L);
    }

    private Order createLimitBuyOrder(long orderId, long accountId) {
        return new Order(orderId, "600000", 600000, OrderSide.BUY, OrderType.LIMIT, 105000L, 100L, accountId, 2001L);
    }

    private Order createLimitBuyOrder(long orderId, String symbol) {
        int symbolCode = Integer.parseInt(symbol);
        return new Order(orderId, symbol, symbolCode, OrderSide.BUY, OrderType.LIMIT, 105000L, 100L, 1001L, 2001L);
    }
}