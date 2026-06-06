package com.hft.trading.event;

import com.hft.trading.model.OrderSide;
import com.hft.trading.model.OrderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TradeEvent 测试（matching_output CQ 的 TRADE 事件）
 *
 * 根据技术方案 V12 3.3.3：
 * matching_output CQ 包含 ORDER_UPDATE/TRADE/CANCEL_SUCCESS/CANCEL_REJECT
 */
class TradeEventTest {

    @Test
    @DisplayName("创建 TRADE 事件应该正确设置所有字段")
    void shouldCreateTradeEvent() {
        String tradeId = "12345_67890";
        long takerOrderId = 12345L;
        long makerOrderId = 67890L;
        String symbol = "600000";
        long price = 105000L;
        long quantity = 100L;
        long buyAccountId = 1001L;
        long sellAccountId = 1002L;
        long buyOrderId = 12345L;
        long sellOrderId = 67890L;
        OrderSide takerSide = OrderSide.BUY;
        OrderType orderType = OrderType.LIMIT;
        long orderPrice = 105000L;
        long orderQuantity = 100L;
        long frozenAmount = 105032L;
        int commissionRate = 3;

        TradeEvent event = TradeEvent.of(
            tradeId, takerOrderId, makerOrderId, symbol, price, quantity,
            buyAccountId, sellAccountId, buyOrderId, sellOrderId,
            takerSide, orderType, orderPrice, orderQuantity, frozenAmount, commissionRate
        );

        assertEquals(EventType.TRADE, event.getEventType());
        assertEquals(tradeId, event.getTradeId());
        assertEquals(takerOrderId, event.getTakerOrderId());
        assertEquals(makerOrderId, event.getMakerOrderId());
        assertEquals(symbol, event.getSymbol());
        assertEquals(price, event.getPrice());
        assertEquals(quantity, event.getQuantity());
        assertEquals(buyAccountId, event.getBuyAccountId());
        assertEquals(sellAccountId, event.getSellAccountId());
        assertEquals(takerSide, event.getSide());
        assertEquals(orderType, event.getOrderType());
        assertNotNull(event.getTimestamp());
    }

    @Test
    @DisplayName("TradeId 应该是确定性的：takerOrderId_makerOrderId")
    void shouldGenerateDeterministicTradeId() {
        long takerOrderId = 12345L;
        long makerOrderId = 67890L;

        String tradeId = TradeEvent.generateTradeId(takerOrderId, makerOrderId);

        assertEquals("12345_67890", tradeId);
        // 相同的订单ID组合应该生成相同的tradeId
        assertEquals(tradeId, TradeEvent.generateTradeId(takerOrderId, makerOrderId));
    }

    @Test
    @DisplayName("买方 TRADE 事件应该包含冻结金额")
    void shouldIncludeFrozenAmountForBuyer() {
        TradeEvent event = TradeEvent.of(
            "12345_67890", 12345L, 67890L, "600000", 105000L, 100L,
            1001L, 1002L, 12345L, 67890L, OrderSide.BUY, OrderType.LIMIT,
            105000L, 100L, 105032L, 3
        );

        assertEquals(105032L, event.getFrozenAmount());
    }

    @Test
    @DisplayName("卖方 TRADE 事件冻结金额应该为0")
    void shouldHaveZeroFrozenAmountForSeller() {
        TradeEvent event = TradeEvent.of(
            "12345_67890", 12345L, 67890L, "600000", 105000L, 100L,
            1001L, 1002L, 12345L, 67890L, OrderSide.SELL, OrderType.LIMIT,
            105000L, 100L, 0L, 3
        );

        assertEquals(0L, event.getFrozenAmount());
    }

    @Test
    @DisplayName("市价单 TRADE 事件应该包含 maxPrice")
    void shouldIncludeMaxPriceForMarketOrder() {
        TradeEvent event = TradeEvent.of(
            "12345_67890", 12345L, 67890L, "600000", 105000L, 100L,
            1001L, 1002L, 12345L, 67890L, OrderSide.BUY, OrderType.MARKET,
            0L, 100L, 115500L, 3
        );

        assertEquals(0L, event.getOrderPrice()); // 市价单 price 为 0
        assertEquals(115500L, event.getMaxPrice()); // 涨停价
    }

    @Test
    @DisplayName("计算成交金额应该正确")
    void shouldCalculateTradeAmount() {
        long price = 105000L; // 10.50元
        long quantity = 100L;

        TradeEvent event = TradeEvent.of(
            "12345_67890", 12345L, 67890L, "600000", price, quantity,
            1001L, 1002L, 12345L, 67890L, OrderSide.BUY, OrderType.LIMIT,
            price, quantity, 105032L, 3
        );

        assertEquals(10500000L, event.getAmount()); // 10.50 * 100 = 1050元 = 10500000分
    }
}
