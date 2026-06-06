package com.hft.trading.matching;

import com.hft.trading.event.TradeEvent;
import com.hft.trading.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MatchingEngine 测试
 *
 * 撮合引擎核心逻辑测试
 */
class MatchingEngineTest {

    private MatchingEngine matchingEngine;
    private final String symbol = "600000";

    @BeforeEach
    void setUp() {
        matchingEngine = new MatchingEngine(symbol);
    }

    @Test
    @DisplayName("新创建的撮合引擎应该有空的订单簿")
    void shouldHaveEmptyOrderBookWhenCreated() {
        assertEquals(0, matchingEngine.getOrderBook().getTotalOrderCount());
    }

    @Test
    @DisplayName("限价买单应该加入订单簿")
    void shouldAddLimitBuyOrderToOrderBook() {
        Order order = createLimitBuyOrder(1L, 105000L, 100L);

        List<TradeEvent> trades = matchingEngine.match(order);

        assertTrue(trades.isEmpty());
        assertEquals(1, matchingEngine.getOrderBook().getBuyOrderCount());
    }

    @Test
    @DisplayName("限价卖单应该加入订单簿")
    void shouldAddLimitSellOrderToOrderBook() {
        Order order = createLimitSellOrder(1L, 110000L, 100L);

        List<TradeEvent> trades = matchingEngine.match(order);

        assertTrue(trades.isEmpty());
        assertEquals(1, matchingEngine.getOrderBook().getSellOrderCount());
    }

    @Test
    @DisplayName("买单价格 >= 卖单最优价应该撮合成交")
    void shouldMatchWhenBuyPriceEqualsOrExceedsBestAsk() {
        // 先加入卖单
        Order sellOrder = createLimitSellOrder(1L, 105000L, 100L);
        matchingEngine.match(sellOrder);

        // 加入买单，价格等于卖单价格
        Order buyOrder = createLimitBuyOrder(2L, 105000L, 100L);
        List<TradeEvent> trades = matchingEngine.match(buyOrder);

        // 应该成交
        assertEquals(1, trades.size());
        TradeEvent trade = trades.get(0);
        assertEquals(105000L, trade.getPrice());
        assertEquals(100L, trade.getQuantity());
        assertEquals(2L, trade.getTakerOrderId());
        assertEquals(1L, trade.getMakerOrderId());

        // 订单簿应该为空
        assertEquals(0, matchingEngine.getOrderBook().getTotalOrderCount());
    }

    @Test
    @DisplayName("买单价格 > 卖单最优价应该以卖方价格成交")
    void shouldTradeAtAskPriceWhenBuyPriceHigher() {
        Order sellOrder = createLimitSellOrder(1L, 105000L, 100L);
        matchingEngine.match(sellOrder);

        Order buyOrder = createLimitBuyOrder(2L, 108000L, 100L);
        List<TradeEvent> trades = matchingEngine.match(buyOrder);

        assertEquals(1, trades.size());
        assertEquals(105000L, trades.get(0).getPrice()); // 以卖方价格成交
    }

    @Test
    @DisplayName("部分成交应该更新订单的已成交数量")
    void shouldUpdateFilledQuantityOnPartialFill() {
        Order sellOrder = createLimitSellOrder(1L, 105000L, 100L);
        matchingEngine.match(sellOrder);

        Order buyOrder = createLimitBuyOrder(2L, 105000L, 50L);
        List<TradeEvent> trades = matchingEngine.match(buyOrder);

        assertEquals(1, trades.size());
        assertEquals(50L, trades.get(0).getQuantity());

        // 卖单应该部分成交
        Order remainingSell = matchingEngine.getOrderBook().findOrder(1L);
        assertNotNull(remainingSell);
        assertEquals(50L, remainingSell.getFilledQuantity());
        assertEquals(50L, remainingSell.getRemainingQty());
    }

    @Test
    @DisplayName("大买单应该消耗多个卖单")
    void shouldConsumeMultipleSellOrders() {
        matchingEngine.match(createLimitSellOrder(1L, 105000L, 100L));
        matchingEngine.match(createLimitSellOrder(2L, 106000L, 100L));
        matchingEngine.match(createLimitSellOrder(3L, 107000L, 100L));

        Order buyOrder = createLimitBuyOrder(4L, 107000L, 250L);
        List<TradeEvent> trades = matchingEngine.match(buyOrder);

        assertEquals(3, trades.size());
        // 按卖单价格升序成交
        assertEquals(105000L, trades.get(0).getPrice());
        assertEquals(106000L, trades.get(1).getPrice());
        assertEquals(107000L, trades.get(2).getPrice());

        // 买单应该部分成交，剩余50股
        assertEquals(1, matchingEngine.getOrderBook().getBuyOrderCount());
        Order remainingBuy = matchingEngine.getOrderBook().findOrder(4L);
        assertNotNull(remainingBuy);
        assertEquals(50L, remainingBuy.getRemainingQty());
    }

    @Test
    @DisplayName("市价单应该立即与最优对手方撮合")
    void shouldMatchMarketOrderImmediately() {
        matchingEngine.match(createLimitSellOrder(1L, 105000L, 100L));
        matchingEngine.match(createLimitSellOrder(2L, 106000L, 100L));

        Order marketBuy = createMarketBuyOrder(3L, 150L);
        List<TradeEvent> trades = matchingEngine.match(marketBuy);

        assertEquals(2, trades.size());
        // 应该成交两个卖单
        assertEquals(100L, trades.get(0).getQuantity());
        assertEquals(50L, trades.get(1).getQuantity());

        // 市价单应该完全成交（不留在订单簿）
        assertNull(matchingEngine.getOrderBook().findOrder(3L));
    }

    @Test
    @DisplayName("市价卖单应该与买单撮合")
    void shouldMatchMarketSellOrder() {
        matchingEngine.match(createLimitBuyOrder(1L, 105000L, 100L));
        matchingEngine.match(createLimitBuyOrder(2L, 104000L, 100L));

        Order marketSell = createMarketSellOrder(3L, 150L);
        List<TradeEvent> trades = matchingEngine.match(marketSell);

        assertEquals(2, trades.size());
        // 应该与最优买单成交
        assertEquals(105000L, trades.get(0).getPrice());
        assertEquals(100L, trades.get(0).getQuantity());
        assertEquals(104000L, trades.get(1).getPrice());
        assertEquals(50L, trades.get(1).getQuantity());
    }

    @Test
    @DisplayName("对手方没有订单时市价单应该拒绝")
    void shouldRejectMarketOrderWhenNoCounterparty() {
        Order marketBuy = createMarketBuyOrder(1L, 100L);
        List<TradeEvent> trades = matchingEngine.match(marketBuy);

        assertTrue(trades.isEmpty());
        // 市价单不留在订单簿
        assertEquals(0, matchingEngine.getOrderBook().getTotalOrderCount());
    }

    @Test
    @DisplayName("FOK订单全部成交或全部撤销")
    void shouldFillOrKillForFOKOrder() {
        matchingEngine.match(createLimitSellOrder(1L, 105000L, 50L));

        // FOK订单数量超过可用量，应该撤销
        Order fokBuy = createFOKBuyOrder(2L, 105000L, 100L);
        List<TradeEvent> trades = matchingEngine.match(fokBuy);

        assertTrue(trades.isEmpty());
        assertNull(matchingEngine.getOrderBook().findOrder(2L));

        // 增加卖单后FOK应该成交
        matchingEngine.match(createLimitSellOrder(3L, 106000L, 50L));
        Order fokBuy2 = createFOKBuyOrder(4L, 106000L, 100L);
        trades = matchingEngine.match(fokBuy2);

        assertEquals(2, trades.size());
        assertEquals(100L, trades.stream().mapToLong(TradeEvent::getQuantity).sum());
    }

    @Test
    @DisplayName("FAK订单立即成交能成交的部分")
    void shouldFillAndKillForFAKOrder() {
        matchingEngine.match(createLimitSellOrder(1L, 105000L, 50L));

        Order fakBuy = createFAKBuyOrder(2L, 105000L, 100L);
        List<TradeEvent> trades = matchingEngine.match(fakBuy);

        assertEquals(1, trades.size());
        assertEquals(50L, trades.get(0).getQuantity());
        // FAK订单不留在订单簿
        assertNull(matchingEngine.getOrderBook().findOrder(2L));
    }

    // Helper methods
    private Order createLimitBuyOrder(long orderId, long price, long quantity) {
        return new Order(orderId, symbol, Integer.parseInt(symbol),
            OrderSide.BUY, OrderType.LIMIT, price, quantity, 1001L, 2001L);
    }

    private Order createLimitSellOrder(long orderId, long price, long quantity) {
        return new Order(orderId, symbol, Integer.parseInt(symbol),
            OrderSide.SELL, OrderType.LIMIT, price, quantity, 1001L, 2001L);
    }

    private Order createMarketBuyOrder(long orderId, long quantity) {
        return new Order(orderId, symbol, Integer.parseInt(symbol),
            OrderSide.BUY, OrderType.MARKET, 0L, quantity, 1001L, 2001L);
    }

    private Order createMarketSellOrder(long orderId, long quantity) {
        return new Order(orderId, symbol, Integer.parseInt(symbol),
            OrderSide.SELL, OrderType.MARKET, 0L, quantity, 1001L, 2001L);
    }

    private Order createFOKBuyOrder(long orderId, long price, long quantity) {
        return new Order(orderId, symbol, Integer.parseInt(symbol),
            OrderSide.BUY, OrderType.FOK, price, quantity, 1001L, 2001L);
    }

    private Order createFAKBuyOrder(long orderId, long price, long quantity) {
        return new Order(orderId, symbol, Integer.parseInt(symbol),
            OrderSide.BUY, OrderType.FAK, price, quantity, 1001L, 2001L);
    }
}
