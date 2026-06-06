package com.hft.trading.matching;

import com.hft.trading.event.TradeEvent;
import com.hft.trading.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 撮合引擎
 *
 * 核心撮合逻辑：价格优先、时间优先
 */
public class MatchingEngine {

    private final String symbol;
    private final OrderBook orderBook;

    public MatchingEngine(String symbol) {
        this.symbol = symbol;
        this.orderBook = new OrderBook(symbol);
    }

    /**
     * 获取订单簿
     */
    public OrderBook getOrderBook() {
        return orderBook;
    }

    /**
     * 撮合订单
     *
     * @param order 待撮合订单
     * @return 成交事件列表
     */
    public List<TradeEvent> match(Order order) {
        List<TradeEvent> trades = new ArrayList<>();

        if (order.getOrderType() == OrderType.LIMIT) {
            trades = matchLimitOrder(order);
        } else if (order.getOrderType() == OrderType.MARKET) {
            trades = matchMarketOrder(order);
        } else if (order.getOrderType() == OrderType.FOK) {
            trades = matchFOKOrder(order);
        } else if (order.getOrderType() == OrderType.FAK) {
            trades = matchFAKOrder(order);
        }

        return trades;
    }

    /**
     * 撮合限价单
     */
    private List<TradeEvent> matchLimitOrder(Order order) {
        List<TradeEvent> trades = new ArrayList<>();

        if (order.getSide() == OrderSide.BUY) {
            // 买单：检查卖盘
            while (order.getRemainingQty() > 0 && orderBook.getSellOrderCount() > 0
                   && order.getPrice() >= orderBook.getBestSellPrice()) {
                Order bestAsk = getBestAsk();
                if (bestAsk == null) break;
                TradeEvent trade = executeTrade(order, bestAsk);
                trades.add(trade);
            }
            // 未完全成交的买单加入订单簿
            if (order.getRemainingQty() > 0) {
                orderBook.addOrder(order);
            }
        } else {
            // 卖单：检查买盘
            while (order.getRemainingQty() > 0 && orderBook.getBuyOrderCount() > 0
                   && order.getPrice() <= orderBook.getBestBuyPrice()) {
                Order bestBid = getBestBid();
                if (bestBid == null) break;
                TradeEvent trade = executeTrade(bestBid, order);
                trades.add(trade);
            }
            // 未完全成交的卖单加入订单簿
            if (order.getRemainingQty() > 0) {
                orderBook.addOrder(order);
            }
        }

        return trades;
    }

    /**
     * 撮合市价单
     */
    private List<TradeEvent> matchMarketOrder(Order order) {
        List<TradeEvent> trades = new ArrayList<>();

        if (order.getSide() == OrderSide.BUY) {
            // 市价买单：与卖盘撮合直到成交完或无对手方
            while (order.getRemainingQty() > 0 && orderBook.getSellOrderCount() > 0) {
                Order bestAsk = getBestAsk();
                if (bestAsk == null) break;
                TradeEvent trade = executeTrade(order, bestAsk);
                trades.add(trade);
            }
            // 市价单不留在订单簿
        } else {
            // 市价卖单：与买盘撮合
            while (order.getRemainingQty() > 0 && orderBook.getBuyOrderCount() > 0) {
                Order bestBid = getBestBid();
                if (bestBid == null) break;
                TradeEvent trade = executeTrade(bestBid, order);
                trades.add(trade);
            }
        }

        return trades;
    }

    /**
     * 撮合 FOK 订单
     */
    private List<TradeEvent> matchFOKOrder(Order order) {
        // 检查是否可以全部成交
        long availableQuantity = calculateAvailableQuantity(order);

        if (availableQuantity < order.getQuantity()) {
            // 无法全部成交，撤销
            return new ArrayList<>();
        }

        // 可以全部成交，执行撮合
        return matchMarketOrder(order);
    }

    /**
     * 撮合 FAK 订单
     */
    private List<TradeEvent> matchFAKOrder(Order order) {
        // 立即撮合能成交的部分
        return matchMarketOrder(order);
    }

    /**
     * 执行单笔成交
     */
    private TradeEvent executeTrade(Order taker, Order maker) {
        long tradePrice = maker.getPrice();
        long tradeQuantity = Math.min(taker.getRemainingQty(), maker.getRemainingQty());

        // 更新双方订单的成交数量
        taker.updateFilledQuantity(tradeQuantity);
        maker.updateFilledQuantity(tradeQuantity);

        // 如果 maker 完全成交，从订单簿移除
        if (maker.getRemainingQty() == 0) {
            orderBook.removeOrder(maker.getOrderId());
        }

        // 生成成交事件
        String tradeId = TradeEvent.generateTradeId(taker.getOrderId(), maker.getOrderId());

        long buyAccountId = taker.getSide() == OrderSide.BUY ? taker.getAccountId() : maker.getAccountId();
        long sellAccountId = taker.getSide() == OrderSide.SELL ? taker.getAccountId() : maker.getAccountId();
        long buyOrderId = taker.getSide() == OrderSide.BUY ? taker.getOrderId() : maker.getOrderId();
        long sellOrderId = taker.getSide() == OrderSide.SELL ? taker.getOrderId() : maker.getOrderId();

        return TradeEvent.of(
            tradeId,
            taker.getOrderId(),
            maker.getOrderId(),
            symbol,
            tradePrice,
            tradeQuantity,
            buyAccountId,
            sellAccountId,
            buyOrderId,
            sellOrderId,
            taker.getSide(),
            taker.getOrderType(),
            taker.getPrice(),
            taker.getQuantity(),
            taker.getFrozenAmount(),
            3 // 默认佣金率万分之三
        );
    }

    /**
     * 获取最优卖单
     */
    private Order getBestAsk() {
        List<Order> sellOrders = orderBook.getSellOrders();
        if (sellOrders.isEmpty()) {
            return null;
        }
        Order bestAsk = sellOrders.get(0);
        return bestAsk;
    }

    /**
     * 获取最优买单
     */
    private Order getBestBid() {
        List<Order> buyOrders = orderBook.getBuyOrders();
        if (buyOrders.isEmpty()) {
            return null;
        }
        Order bestBid = buyOrders.get(0);
        return bestBid;
    }

    /**
     * 计算可成交数量
     */
    private long calculateAvailableQuantity(Order order) {
        long available = 0;

        if (order.getSide() == OrderSide.BUY) {
            // 买单：统计卖盘可用量
            List<Order> sellOrders = orderBook.getSellOrders();
            for (Order ask : sellOrders) {
                if (ask.getPrice() <= order.getPrice()) {
                    available += ask.getRemainingQty();
                }
            }
        } else {
            // 卖单：统计买盘可用量
            List<Order> buyOrders = orderBook.getBuyOrders();
            for (Order bid : buyOrders) {
                if (bid.getPrice() >= order.getPrice()) {
                    available += bid.getRemainingQty();
                }
            }
        }

        return available;
    }
}