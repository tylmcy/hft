package com.hft.trading.store;

import com.hft.trading.model.Order;
import com.hft.trading.model.OrderStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单内存存储
 *
 * 根据 V12 技术方案 3.2.3：
 * - 使用 ConcurrentHashMap<Long, Order> 存储
 * - 支持 getOrder/addOrder/updateStatus/updateFilledQuantity 操作
 * - 订单不通过 CM 快照持久化，通过 CQ 重放恢复
 */
public class InMemoryOrderStore {

    private final ConcurrentHashMap<Long, Order> orders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Order> idempotencyKeyIndex = new ConcurrentHashMap<>();

    /**
     * 添加订单（使用 putIfAbsent 防止重复）
     */
    public void addOrder(Order order) {
        Order existing = orders.putIfAbsent(order.getOrderId(), order);
        if (existing != null) {
            throw new IllegalStateException("订单已存在: orderId=" + order.getOrderId());
        }

        // 建立幂等键索引
        if (order.getIdempotencyKey() != null) {
            idempotencyKeyIndex.put(order.getIdempotencyKey(), order);
        }
    }

    /**
     * 获取订单
     */
    public Order getOrder(long orderId) {
        return orders.get(orderId);
    }

    /**
     * 移除订单
     */
    public void removeOrder(long orderId) {
        Order removed = orders.remove(orderId);
        if (removed != null && removed.getIdempotencyKey() != null) {
            idempotencyKeyIndex.remove(removed.getIdempotencyKey());
        }
    }

    /**
     * 更新订单状态
     */
    public void updateStatus(long orderId, OrderStatus newStatus) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new IllegalStateException("订单不存在: orderId=" + orderId);
        }
        order.updateStatus(newStatus);
    }

    /**
     * 更新订单成交数量
     */
    public void updateFilledQuantity(long orderId, long qty) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new IllegalStateException("订单不存在: orderId=" + orderId);
        }
        order.updateFilledQuantity(qty);
    }

    /**
     * 获取账户的活跃订单（PENDING/NEW/PARTIAL_FILLED）
     */
    public List<Order> getActiveOrdersByAccount(long accountId) {
        List<Order> result = new ArrayList<>();
        orders.values().stream()
            .filter(o -> o.getAccountId() == accountId)
            .filter(o -> isActive(o.getStatus()))
            .forEach(result::add);
        return result;
    }

    /**
     * 获取账户的所有订单
     */
    public List<Order> getOrdersByAccount(long accountId) {
        List<Order> result = new ArrayList<>();
        orders.values().stream()
            .filter(o -> o.getAccountId() == accountId)
            .forEach(result::add);
        return result;
    }

    /**
     * 获取所有订单
     */
    public List<Order> getAllOrders() {
        return new ArrayList<>(orders.values());
    }

    /**
     * 获取证券的活跃订单
     */
    public List<Order> getActiveOrdersBySymbol(String symbol) {
        List<Order> result = new ArrayList<>();
        orders.values().stream()
            .filter(o -> o.getSymbol().equals(symbol))
            .filter(o -> isActive(o.getStatus()))
            .forEach(result::add);
        return result;
    }

    /**
     * 根据幂等键查找订单
     */
    public Order findByIdempotencyKey(String idempotencyKey) {
        return idempotencyKeyIndex.get(idempotencyKey);
    }

    /**
     * 清空所有订单
     */
    public void clear() {
        orders.clear();
        idempotencyKeyIndex.clear();
    }

    /**
     * 获取订单数量
     */
    public int size() {
        return orders.size();
    }

    /**
     * 判断订单是否活跃
     */
    private boolean isActive(OrderStatus status) {
        return status == OrderStatus.PENDING ||
               status == OrderStatus.NEW ||
               status == OrderStatus.PARTIAL_FILLED;
    }
}