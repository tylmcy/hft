package com.hft.trading.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Order（订单）数据模型测试
 *
 * 根据 V12 技术方案 3.1.3：
 * - 订单状态机：PENDING → NEW → PARTIAL_FILLED → FILLED/CANCELLED
 * - volatile 字段保证跨线程可见性
 */
class OrderTest {

    @Test
    @DisplayName("创建订单时应该正确初始化所有字段")
    void shouldInitializeAllFieldsWhenCreatingOrder() {
        long orderId = 1234567890123456789L;
        String symbol = "600000";
        int symbolCode = 600000;
        OrderSide side = OrderSide.BUY;
        OrderType orderType = OrderType.LIMIT;
        long price = 105000L; // 10.50元
        long quantity = 100L;
        long accountId = 1001L;
        long userId = 2001L;

        Order order = new Order(orderId, symbol, symbolCode, side, orderType, price, quantity, accountId, userId);

        assertEquals(orderId, order.getOrderId());
        assertEquals(symbol, order.getSymbol());
        assertEquals(symbolCode, order.getSymbolCode());
        assertEquals(side, order.getSide());
        assertEquals(orderType, order.getOrderType());
        assertEquals(price, order.getPrice());
        assertEquals(quantity, order.getQuantity());
        assertEquals(accountId, order.getAccountId());
        assertEquals(userId, order.getUserId());
        assertEquals(OrderStatus.PENDING, order.getStatus());
        assertEquals(0L, order.getFilledQuantity());
        assertEquals(quantity, order.getRemainingQty());
        assertEquals(0L, order.getFrozenAmount());
    }

    @Test
    @DisplayName("市价单价格应该为0")
    void shouldHaveZeroPriceForMarketOrder() {
        Order order = new Order(1L, "600000", 600000, OrderSide.BUY, OrderType.MARKET, 0L, 100L, 1001L, 2001L);

        assertEquals(0L, order.getPrice());
        assertEquals(OrderType.MARKET, order.getOrderType());
    }

    @Test
    @DisplayName("订单状态转换：PENDING → NEW")
    void shouldTransitionFromPendingToNew() {
        Order order = createLimitBuyOrder();

        order.updateStatus(OrderStatus.NEW);

        assertEquals(OrderStatus.NEW, order.getStatus());
    }

    @Test
    @DisplayName("订单状态转换：NEW → PARTIAL_FILLED")
    void shouldTransitionFromNewToPartialFilled() {
        Order order = createLimitBuyOrder();
        order.updateStatus(OrderStatus.NEW);

        order.updateFilledQuantity(50L);

        assertEquals(OrderStatus.PARTIAL_FILLED, order.getStatus());
        assertEquals(50L, order.getFilledQuantity());
        assertEquals(50L, order.getRemainingQty());
    }

    @Test
    @DisplayName("订单状态转换：PARTIAL_FILLED → FILLED")
    void shouldTransitionFromPartialFilledToFilled() {
        Order order = createLimitBuyOrder();
        order.updateStatus(OrderStatus.NEW);
        order.updateFilledQuantity(50L);

        order.updateFilledQuantity(50L);

        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(100L, order.getFilledQuantity());
        assertEquals(0L, order.getRemainingQty());
    }

    @Test
    @DisplayName("订单状态转换：NEW → CANCELLED")
    void shouldTransitionFromNewToCancelled() {
        Order order = createLimitBuyOrder();
        order.updateStatus(OrderStatus.NEW);

        order.updateStatus(OrderStatus.CANCELLED);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @Test
    @DisplayName("订单状态转换：PARTIAL_FILLED → CANCELLED")
    void shouldTransitionFromPartialFilledToCancelled() {
        Order order = createLimitBuyOrder();
        order.updateStatus(OrderStatus.NEW);
        order.updateFilledQuantity(50L);

        order.updateStatus(OrderStatus.CANCELLED);

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(50L, order.getFilledQuantity());
    }

    @Test
    @DisplayName("订单状态转换：PENDING → REJECTED")
    void shouldTransitionFromPendingToRejected() {
        Order order = createLimitBuyOrder();

        order.updateStatus(OrderStatus.REJECTED);

        assertEquals(OrderStatus.REJECTED, order.getStatus());
    }

    @Test
    @DisplayName("非法状态转换应该抛出异常：FILLED → CANCELLED")
    void shouldThrowOnInvalidTransitionFromFilledToCancelled() {
        Order order = createLimitBuyOrder();
        order.updateStatus(OrderStatus.NEW);
        order.updateFilledQuantity(100L); // FILLED

        assertThrows(IllegalStateException.class, () -> order.updateStatus(OrderStatus.CANCELLED));
    }

    @Test
    @DisplayName("非法状态转换应该抛出异常：CANCELLED → FILLED")
    void shouldThrowOnInvalidTransitionFromCancelledToFilled() {
        Order order = createLimitBuyOrder();
        order.updateStatus(OrderStatus.NEW);
        order.updateStatus(OrderStatus.CANCELLED);

        assertThrows(IllegalStateException.class, () -> order.updateFilledQuantity(100L));
    }

    @Test
    @DisplayName("设置冻结金额应该成功")
    void shouldSetFrozenAmount() {
        Order order = createLimitBuyOrder();

        order.setFrozenAmount(105032L); // 价格×数量 + 预估佣金

        assertEquals(105032L, order.getFrozenAmount());
    }

    @Test
    @DisplayName("更新冻结金额应该成功")
    void shouldUpdateFrozenAmount() {
        Order order = createLimitBuyOrder();
        order.setFrozenAmount(105032L);

        order.updateFrozenAmount(50000L);

        assertEquals(55032L, order.getFrozenAmount());
    }

    @Test
    @DisplayName("买方订单应该有冻结金额")
    void shouldHaveFrozenAmountForBuyOrder() {
        Order order = createLimitBuyOrder();
        order.setFrozenAmount(105000L);

        assertTrue(order.getFrozenAmount() > 0);
    }

    @Test
    @DisplayName("卖方订单不应该冻结资金（冻结持仓）")
    void shouldNotFreezeAmountForSellOrder() {
        Order order = new Order(1L, "600000", 600000, OrderSide.SELL, OrderType.LIMIT, 105000L, 100L, 1001L, 2001L);

        order.setFrozenAmount(0L); // 卖单不冻结资金

        assertEquals(0L, order.getFrozenAmount());
    }

    @Test
    @DisplayName("市价买单应该设置 maxPrice")
    void shouldSetMaxPriceForMarketBuyOrder() {
        Order order = new Order(1L, "600000", 600000, OrderSide.BUY, OrderType.MARKET, 0L, 100L, 1001L, 2001L);

        order.setMaxPrice(115500L); // 涨停价

        assertEquals(115500L, order.getMaxPrice());
        assertEquals(0L, order.getPrice()); // 市价单price仍为0
    }

    @Test
    @DisplayName("市价卖单应该设置 minPrice")
    void shouldSetMinPriceForMarketSellOrder() {
        Order order = new Order(1L, "600000", 600000, OrderSide.SELL, OrderType.MARKET, 0L, 100L, 1001L, 2001L);

        order.setMinPrice(94500L); // 跌停价

        assertEquals(94500L, order.getMinPrice());
    }

    @Test
    @DisplayName("应该能够设置撤单原因")
    void shouldSetCancelReason() {
        Order order = createLimitBuyOrder();
        order.updateStatus(OrderStatus.NEW);
        order.updateStatus(OrderStatus.CANCELLED);

        order.setCancelReason(CancelReason.USER_CANCEL);

        assertEquals(CancelReason.USER_CANCEL, order.getCancelReason());
    }

    @Test
    @DisplayName("应该能够设置幂等键")
    void shouldSetIdempotencyKey() {
        Order order = createLimitBuyOrder();
        String idempotencyKey = "uuid-v4";

        order.setIdempotencyKey(idempotencyKey);

        assertEquals(idempotencyKey, order.getIdempotencyKey());
    }

    @Test
    @DisplayName("订单状态应该是 volatile 以保证跨线程可见性")
    void shouldHaveVolatileStatus() throws InterruptedException {
        Order order = createLimitBuyOrder();

        // 线程1：更新状态
        Thread t1 = new Thread(() -> order.updateStatus(OrderStatus.NEW));

        // 线程2：读取状态
        Thread t2 = new Thread(() -> {
            // 等待状态变为 NEW
            while (order.getStatus() != OrderStatus.NEW) {
                Thread.yield();
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(OrderStatus.NEW, order.getStatus());
    }

    @Test
    @DisplayName("FOK 订单应该正确标识")
    void shouldIdentifyFokOrder() {
        Order order = new Order(1L, "600000", 600000, OrderSide.BUY, OrderType.FOK, 105000L, 100L, 1001L, 2001L);

        assertEquals(OrderType.FOK, order.getOrderType());
    }

    @Test
    @DisplayName("FAK 订单应该正确标识")
    void shouldIdentifyFakOrder() {
        Order order = new Order(1L, "600000", 600000, OrderSide.BUY, OrderType.FAK, 105000L, 100L, 1001L, 2001L);

        assertEquals(OrderType.FAK, order.getOrderType());
    }

    private Order createLimitBuyOrder() {
        return new Order(1L, "600000", 600000, OrderSide.BUY, OrderType.LIMIT, 105000L, 100L, 1001L, 2001L);
    }
}