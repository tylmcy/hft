package com.hft.trading.matching;

import com.hft.trading.model.Order;
import com.hft.trading.model.OrderSide;
import com.hft.trading.model.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrderBook 测试
 *
 * 订单簿是撮合引擎的核心数据结构
 */
class OrderBookTest {

    private OrderBook orderBook;
    private final String symbol = "600000";

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook(symbol);
    }

    @Test
    @DisplayName("新创建的订单簿应该为空")
    void shouldBeEmptyWhenCreated() {
        assertEquals(0, orderBook.getBuyOrderCount());
        assertEquals(0, orderBook.getSellOrderCount());
        assertEquals(0, orderBook.getTotalOrderCount());
    }

    @Test
    @DisplayName("应该能够添加买单到订单簿")
    void shouldAddBuyOrder() {
        Order order = createLimitBuyOrder(1L, 105000L, 100L);

        orderBook.addOrder(order);

        assertEquals(1, orderBook.getBuyOrderCount());
        assertEquals(0, orderBook.getSellOrderCount());
    }

    @Test
    @DisplayName("应该能够添加卖单到订单簿")
    void shouldAddSellOrder() {
        Order order = createLimitSellOrder(1L, 110000L, 100L);

        orderBook.addOrder(order);

        assertEquals(0, orderBook.getBuyOrderCount());
        assertEquals(1, orderBook.getSellOrderCount());
    }

    @Test
    @DisplayName("买单应该按价格降序排列（高价优先）")
    void shouldSortBuyOrdersByPriceDesc() {
        Order order1 = createLimitBuyOrder(1L, 105000L, 100L);
        Order order2 = createLimitBuyOrder(2L, 110000L, 100L);
        Order order3 = createLimitBuyOrder(3L, 108000L, 100L);

        orderBook.addOrder(order1);
        orderBook.addOrder(order2);
        orderBook.addOrder(order3);

        List<Order> buyOrders = orderBook.getBuyOrders();
        assertEquals(110000L, buyOrders.get(0).getPrice());
        assertEquals(108000L, buyOrders.get(1).getPrice());
        assertEquals(105000L, buyOrders.get(2).getPrice());
    }

    @Test
    @DisplayName("卖单应该按价格升序排列（低价优先）")
    void shouldSortSellOrdersByPriceAsc() {
        Order order1 = createLimitSellOrder(1L, 110000L, 100L);
        Order order2 = createLimitSellOrder(2L, 105000L, 100L);
        Order order3 = createLimitSellOrder(3L, 108000L, 100L);

        orderBook.addOrder(order1);
        orderBook.addOrder(order2);
        orderBook.addOrder(order3);

        List<Order> sellOrders = orderBook.getSellOrders();
        assertEquals(105000L, sellOrders.get(0).getPrice());
        assertEquals(108000L, sellOrders.get(1).getPrice());
        assertEquals(110000L, sellOrders.get(2).getPrice());
    }

    @Test
    @DisplayName("相同价格的订单应该按时间顺序排列")
    void shouldSortOrdersByTimeForSamePrice() throws InterruptedException {
        Order order1 = createLimitBuyOrder(1L, 105000L, 100L);
        Thread.sleep(1);
        Order order2 = createLimitBuyOrder(2L, 105000L, 100L);
        Thread.sleep(1);
        Order order3 = createLimitBuyOrder(3L, 105000L, 100L);

        orderBook.addOrder(order1);
        orderBook.addOrder(order2);
        orderBook.addOrder(order3);

        List<Order> buyOrders = orderBook.getBuyOrders();
        assertEquals(1L, buyOrders.get(0).getOrderId());
        assertEquals(2L, buyOrders.get(1).getOrderId());
        assertEquals(3L, buyOrders.get(2).getOrderId());
    }

    @Test
    @DisplayName("应该能够从订单簿移除订单")
    void shouldRemoveOrder() {
        Order order = createLimitBuyOrder(1L, 105000L, 100L);
        orderBook.addOrder(order);

        boolean removed = orderBook.removeOrder(1L);

        assertTrue(removed);
        assertEquals(0, orderBook.getBuyOrderCount());
    }

    @Test
    @DisplayName("移除不存在的订单应该返回 false")
    void shouldReturnFalseWhenRemovingNonExistentOrder() {
        boolean removed = orderBook.removeOrder(99999L);
        assertFalse(removed);
    }

    @Test
    @DisplayName("应该能够获取最优买价")
    void shouldGetBestBuyPrice() {
        orderBook.addOrder(createLimitBuyOrder(1L, 105000L, 100L));
        orderBook.addOrder(createLimitBuyOrder(2L, 108000L, 100L));
        orderBook.addOrder(createLimitBuyOrder(3L, 103000L, 100L));

        assertEquals(108000L, orderBook.getBestBuyPrice());
    }

    @Test
    @DisplayName("应该能够获取最优卖价")
    void shouldGetBestSellPrice() {
        orderBook.addOrder(createLimitSellOrder(1L, 110000L, 100L));
        orderBook.addOrder(createLimitSellOrder(2L, 105000L, 100L));
        orderBook.addOrder(createLimitSellOrder(3L, 108000L, 100L));

        assertEquals(105000L, orderBook.getBestSellPrice());
    }

    @Test
    @DisplayName("空订单簿获取最优价格应该返回 0")
    void shouldReturnZeroForBestPriceWhenEmpty() {
        assertEquals(0L, orderBook.getBestBuyPrice());
        assertEquals(0L, orderBook.getBestSellPrice());
    }

    @Test
    @DisplayName("应该能够根据 orderId 查找订单")
    void shouldFindOrderById() {
        Order order = createLimitBuyOrder(1L, 105000L, 100L);
        orderBook.addOrder(order);

        Order found = orderBook.findOrder(1L);

        assertNotNull(found);
        assertEquals(1L, found.getOrderId());
    }

    @Test
    @DisplayName("查找不存在的订单应该返回 null")
    void shouldReturnNullWhenOrderNotFound() {
        Order found = orderBook.findOrder(99999L);
        assertNull(found);
    }

    @Test
    @DisplayName("应该能够生成五档行情")
    void shouldGenerateFiveLevelQuote() {
        // 添加买盘
        orderBook.addOrder(createLimitBuyOrder(1L, 105000L, 100L));
        orderBook.addOrder(createLimitBuyOrder(2L, 104000L, 200L));
        orderBook.addOrder(createLimitBuyOrder(3L, 103000L, 150L));

        // 添加卖盘
        orderBook.addOrder(createLimitSellOrder(4L, 106000L, 100L));
        orderBook.addOrder(createLimitSellOrder(5L, 107000L, 200L));

        OrderBook.FiveLevelQuote quote = orderBook.getFiveLevelQuote();

        // 五档行情固定返回5档，不足的用空档填充
        assertEquals(5, quote.getBidLevels().size());
        assertEquals(5, quote.getAskLevels().size());
        assertEquals(105000L, quote.getBidLevels().get(0).getPrice());
        assertEquals(100L, quote.getBidLevels().get(0).getVolume());
        // 第4、5档应该是空档
        assertEquals(0L, quote.getBidLevels().get(3).getPrice());
        assertEquals(0L, quote.getBidLevels().get(4).getPrice());
    }

    @Test
    @DisplayName("五档行情不足5档应该用空档填充")
    void shouldPadFiveLevelQuoteWithEmptyLevels() {
        orderBook.addOrder(createLimitBuyOrder(1L, 105000L, 100L));
        orderBook.addOrder(createLimitSellOrder(2L, 106000L, 100L));

        OrderBook.FiveLevelQuote quote = orderBook.getFiveLevelQuote();

        assertEquals(5, quote.getBidLevels().size());
        assertEquals(5, quote.getAskLevels().size());
        // 后面的档位价格和数量应该为0
        assertEquals(0L, quote.getBidLevels().get(1).getPrice());
        assertEquals(0L, quote.getBidLevels().get(1).getVolume());
    }

    // Helper methods
    private Order createLimitBuyOrder(long orderId, long price, long quantity) {
        Order order = new Order(orderId, symbol, Integer.parseInt(symbol),
            OrderSide.BUY, OrderType.LIMIT, price, quantity, 1001L, 2001L);
        return order;
    }

    private Order createLimitSellOrder(long orderId, long price, long quantity) {
        Order order = new Order(orderId, symbol, Integer.parseInt(symbol),
            OrderSide.SELL, OrderType.LIMIT, price, quantity, 1001L, 2001L);
        return order;
    }
}
