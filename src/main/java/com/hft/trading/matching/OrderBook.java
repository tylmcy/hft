package com.hft.trading.matching;

import com.hft.trading.model.Order;
import com.hft.trading.model.OrderSide;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单簿
 *
 * 管理买卖双方订单，支持价格优先、时间优先撮合
 */
public class OrderBook {

    private final String symbol;

    // 买单：价格降序（高价优先），同价格按时间升序
    private final TreeMap<Long, LinkedList<Order>> buyOrders = new TreeMap<>(Collections.reverseOrder());

    // 卖单：价格升序（低价优先），同价格按时间升序
    private final TreeMap<Long, LinkedList<Order>> sellOrders = new TreeMap<>();

    // orderId -> Order 快速查找索引
    private final ConcurrentHashMap<Long, Order> orderIndex = new ConcurrentHashMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    /**
     * 添加订单到订单簿
     */
    public void addOrder(Order order) {
        TreeMap<Long, LinkedList<Order>> book = order.getSide() == OrderSide.BUY ? buyOrders : sellOrders;
        long price = order.getPrice();

        book.computeIfAbsent(price, k -> new LinkedList<>()).addLast(order);
        orderIndex.put(order.getOrderId(), order);
    }

    /**
     * 从订单簿移除订单
     */
    public boolean removeOrder(long orderId) {
        Order order = orderIndex.remove(orderId);
        if (order == null) {
            return false;
        }

        TreeMap<Long, LinkedList<Order>> book = order.getSide() == OrderSide.BUY ? buyOrders : sellOrders;
        long price = order.getPrice();

        LinkedList<Order> ordersAtPrice = book.get(price);
        if (ordersAtPrice != null) {
            ordersAtPrice.remove(order);
            if (ordersAtPrice.isEmpty()) {
                book.remove(price);
            }
        }

        return true;
    }

    /**
     * 根据 orderId 查找订单
     */
    public Order findOrder(long orderId) {
        return orderIndex.get(orderId);
    }

    /**
     * 获取最优买价
     */
    public long getBestBuyPrice() {
        return buyOrders.isEmpty() ? 0L : buyOrders.firstKey();
    }

    /**
     * 获取最优卖价
     */
    public long getBestSellPrice() {
        return sellOrders.isEmpty() ? 0L : sellOrders.firstKey();
    }

    /**
     * 获取买单列表（按价格降序）
     */
    public List<Order> getBuyOrders() {
        List<Order> result = new ArrayList<>();
        for (LinkedList<Order> orders : buyOrders.values()) {
            result.addAll(orders);
        }
        return result;
    }

    /**
     * 获取卖单列表（按价格升序）
     */
    public List<Order> getSellOrders() {
        List<Order> result = new ArrayList<>();
        for (LinkedList<Order> orders : sellOrders.values()) {
            result.addAll(orders);
        }
        return result;
    }

    /**
     * 获取买单数量
     */
    public int getBuyOrderCount() {
        return (int) buyOrders.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 获取卖单数量
     */
    public int getSellOrderCount() {
        return (int) sellOrders.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 获取总订单数量
     */
    public int getTotalOrderCount() {
        return orderIndex.size();
    }

    /**
     * 获取五档行情
     */
    public FiveLevelQuote getFiveLevelQuote() {
        List<PriceLevel> bidLevels = new ArrayList<>();
        List<PriceLevel> askLevels = new ArrayList<>();

        // 买盘（取最高5档）
        int bidCount = 0;
        for (Map.Entry<Long, LinkedList<Order>> entry : buyOrders.entrySet()) {
            if (bidCount >= 5) break;
            long price = entry.getKey();
            long volume = entry.getValue().stream().mapToLong(Order::getRemainingQty).sum();
            bidLevels.add(new PriceLevel(price, volume));
            bidCount++;
        }
        // 填充空档
        while (bidLevels.size() < 5) {
            bidLevels.add(new PriceLevel(0L, 0L));
        }

        // 卖盘（取最低5档）
        int askCount = 0;
        for (Map.Entry<Long, LinkedList<Order>> entry : sellOrders.entrySet()) {
            if (askCount >= 5) break;
            long price = entry.getKey();
            long volume = entry.getValue().stream().mapToLong(Order::getRemainingQty).sum();
            askLevels.add(new PriceLevel(price, volume));
            askCount++;
        }
        // 填充空档
        while (askLevels.size() < 5) {
            askLevels.add(new PriceLevel(0L, 0L));
        }

        return new FiveLevelQuote(symbol, bidLevels, askLevels);
    }

    /**
     * 价格档位
     */
    public static class PriceLevel {
        private final long price;
        private final long volume;

        public PriceLevel(long price, long volume) {
            this.price = price;
            this.volume = volume;
        }

        public long getPrice() { return price; }
        public long getVolume() { return volume; }
    }

    /**
     * 五档行情
     */
    public static class FiveLevelQuote {
        private final String symbol;
        private final List<PriceLevel> bidLevels;
        private final List<PriceLevel> askLevels;
        private final long updateTime;

        public FiveLevelQuote(String symbol, List<PriceLevel> bidLevels, List<PriceLevel> askLevels) {
            this.symbol = symbol;
            this.bidLevels = Collections.unmodifiableList(bidLevels);
            this.askLevels = Collections.unmodifiableList(askLevels);
            this.updateTime = System.currentTimeMillis();
        }

        public String getSymbol() { return symbol; }
        public List<PriceLevel> getBidLevels() { return bidLevels; }
        public List<PriceLevel> getAskLevels() { return askLevels; }
        public long getUpdateTime() { return updateTime; }
    }
}
