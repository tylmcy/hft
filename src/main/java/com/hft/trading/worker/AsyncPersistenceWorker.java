package com.hft.trading.worker;

import com.hft.trading.event.CancelSuccessEvent;
import com.hft.trading.event.EventType;
import com.hft.trading.event.TradeEvent;
import com.hft.trading.model.Order;
import com.hft.trading.model.OrderType;
import com.hft.trading.queue.MatchingOutputQueue;
import com.hft.trading.store.InMemoryOrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.*;

/**
 * AsyncPersistenceWorker - 异步持久化处理器
 *
 * 根据 V12 技术方案：
 * - 单线程批量处理 matching_output CQ 事件
 * - 批量大小：100
 * - 处理类型：TRADE / ORDER_UPDATE / CANCEL_SUCCESS
 * - 持久化到 H2 数据库：trades_history / orders_history
 */
public class AsyncPersistenceWorker implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(AsyncPersistenceWorker.class);

    // 配置
    private static final int BATCH_SIZE = 100;
    private static final int IDEMPOTENT_SET_MAX_SIZE = 10000;

    // 存储
    private final InMemoryOrderStore orderStore;

    // 队列
    private final MatchingOutputQueue matchingOutputQueue;

    // 数据库
    private final JdbcTemplate jdbcTemplate;

    // 幂等集合（防止重复插入）
    private final LinkedHashSet<String> persistedTradeIds = new LinkedHashSet<>();
    private final LinkedHashSet<Long> persistedOrderIds = new LinkedHashSet<>();

    // 状态
    private volatile boolean running = false;
    private volatile long lastProcessedIndex = -1;

    // 线程
    private Thread workerThread;

    public AsyncPersistenceWorker(InMemoryOrderStore orderStore,
                                  MatchingOutputQueue matchingOutputQueue,
                                  DataSource dataSource) {
        this.orderStore = orderStore;
        this.matchingOutputQueue = matchingOutputQueue;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 启动 Worker
     */
    public synchronized void start() {
        if (running) {
            logger.warn("AsyncPersistenceWorker is already running");
            return;
        }

        running = true;
        workerThread = new Thread(this, "AsyncPersistenceWorker");
        workerThread.start();
        logger.info("AsyncPersistenceWorker started");
    }

    /**
     * 停止 Worker
     */
    public synchronized void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.info("AsyncPersistenceWorker stopped");
    }

    @Override
    public void run() {
        logger.info("AsyncPersistenceWorker thread started");

        while (running) {
            try {
                // 批量读取事件
                List<MatchingOutputQueue.MatchingOutputEvent> events = matchingOutputQueue.readBatch();

                if (events.isEmpty()) {
                    continue;
                }

                // 批量处理
                for (MatchingOutputQueue.MatchingOutputEvent event : events) {
                    try {
                        processEvent(event);
                    } catch (Exception e) {
                        logger.error("Error processing event: type={}, error={}",
                            event.getEventType(), e.getMessage(), e);
                    }
                }

                // 更新处理位置
                lastProcessedIndex = matchingOutputQueue.getCurrentIndex();

            } catch (Exception e) {
                logger.error("AsyncPersistenceWorker error: {}", e.getMessage(), e);
            }
        }

        logger.info("AsyncPersistenceWorker thread exited");
    }

    /**
     * 处理单个事件
     */
    private void processEvent(MatchingOutputQueue.MatchingOutputEvent event) {
        EventType eventType = event.getEventType();

        switch (eventType) {
            case ORDER_UPDATE:
                processOrderUpdate(event.asOrderUpdate());
                break;

            case TRADE:
                processTrade(event.asTradeEvent());
                break;

            case CANCEL_SUCCESS:
                processCancelSuccess(event.asCancelSuccessEvent());
                break;

            default:
                logger.debug("Event type not handled for persistence: {}", eventType);
        }
    }

    /**
     * 处理 ORDER_UPDATE 事件 - 持久化订单创建
     */
    private void processOrderUpdate(MatchingOutputQueue.OrderUpdateData data) {
        if (data == null) return;

        Long orderId = data.getOrderId();
        if (orderId == null) return;

        // 幂等检查
        if (persistedOrderIds.contains(orderId)) {
            logger.debug("Order already persisted: orderId={}", orderId);
            return;
        }

        Order order = orderStore.getOrder(orderId);
        if (order == null) {
            logger.warn("Order not found for persistence: orderId={}", orderId);
            return;
        }

        // 持久化订单
        persistOrder(order);

        // 加入幂等集合
        addToIdempotentSet(persistedOrderIds, orderId);

        logger.debug("Order persisted: orderId={}", orderId);
    }

    /**
     * 处理 TRADE 事件 - 持久化成交记录
     */
    private void processTrade(TradeEvent tradeEvent) {
        if (tradeEvent == null) return;

        String tradeId = tradeEvent.getTradeId();

        // 幂等检查
        if (persistedTradeIds.contains(tradeId)) {
            logger.debug("Trade already persisted: tradeId={}", tradeId);
            return;
        }

        logger.debug("Processing TRADE for persistence: tradeId={}, price={}, qty={}",
            tradeId, tradeEvent.getPrice(), tradeEvent.getQuantity());

        // 持久化买方成交
        persistTradeForSide(tradeEvent, true);

        // 持久化卖方成交
        persistTradeForSide(tradeEvent, false);

        // 更新订单状态（买方和卖方）
        updateOrderStatus(tradeEvent.getBuyOrderId());
        updateOrderStatus(tradeEvent.getSellOrderId());

        // 加入幂等集合
        addToIdempotentSet(persistedTradeIds, tradeId);

        logger.info("TRADE persisted: tradeId={}", tradeId);
    }

    /**
     * 处理 CANCEL_SUCCESS 事件 - 更新订单状态
     */
    private void processCancelSuccess(CancelSuccessEvent cancelEvent) {
        if (cancelEvent == null) return;

        long orderId = cancelEvent.getOrderId();

        logger.debug("Processing CANCEL_SUCCESS for persistence: orderId={}", orderId);

        // 更新订单状态
        updateOrderStatus(orderId);

        logger.info("CANCEL_SUCCESS processed: orderId={}", orderId);
    }

    /**
     * 持久化订单到 orders_history 表
     */
    private void persistOrder(Order order) {
        // Use MERGE for H2 compatibility (upsert)
        String sql = """
            MERGE INTO orders_history
            (order_id, account_id, user_id, symbol, side, order_type, price, quantity,
             filled_quantity, status, commission, frozen_amount, cancel_reason,
             idempotency_key, create_time, update_time, trade_date)
            KEY(order_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        String tradeDate = formatTradeDate(order.getCreateTime());

        jdbcTemplate.update(sql,
            order.getOrderId(),
            order.getAccountId(),
            order.getUserId(),
            order.getSymbol(),
            order.getSide().name(),
            order.getOrderType().name(),
            order.getPrice(),
            order.getQuantity(),
            order.getFilledQuantity(),
            order.getStatus().name(),
            0L, // commission - 由清算阶段计算
            order.getFrozenAmount(),
            order.getCancelReason() != null ? order.getCancelReason().name() : null,
            order.getIdempotencyKey(),
            order.getCreateTime(),
            order.getUpdateTime(),
            tradeDate
        );
    }

    /**
     * 持久化成交记录到 trades_history 表
     */
    private void persistTradeForSide(TradeEvent tradeEvent, boolean isBuy) {
        long orderId = isBuy ? tradeEvent.getBuyOrderId() : tradeEvent.getSellOrderId();
        long accountId = isBuy ? tradeEvent.getBuyAccountId() : tradeEvent.getSellAccountId();

        // 从订单获取 userId
        Order order = orderStore.getOrder(orderId);
        long userId = order != null ? order.getUserId() : accountId;

        // 计算佣金
        long amount = tradeEvent.getAmount();
        int commissionRate = tradeEvent.getCommissionRate();
        long commission = (amount * commissionRate) / 10000;

        // 计算 frozenAmountUsed（仅买方）
        long frozenAmountUsed = 0;
        if (isBuy && order != null) {
            frozenAmountUsed = calculateFrozenAmountUsed(order, tradeEvent);
        }

        // Use MERGE for H2 compatibility (upsert)
        String sql = """
            MERGE INTO trades_history
            (trade_id, order_id, account_id, user_id, symbol, side, order_type,
             price, order_price, quantity, amount, commission, frozen_amount_used,
             trade_time, trade_date)
            KEY(trade_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        String tradeDate = formatTradeDate(tradeEvent.getTimestamp());

        // 为买方和卖方生成不同的 trade_id
        String sideTradeId = tradeEvent.getTradeId() + "_" + (isBuy ? "B" : "S");

        jdbcTemplate.update(sql,
            sideTradeId,
            orderId,
            accountId,
            userId,
            tradeEvent.getSymbol(),
            isBuy ? "BUY" : "SELL",
            tradeEvent.getOrderType().name(),
            tradeEvent.getPrice(),
            tradeEvent.getOrderPrice(),
            tradeEvent.getQuantity(),
            amount,
            commission,
            frozenAmountUsed,
            tradeEvent.getTimestamp(),
            tradeDate
        );
    }

    /**
     * 更新订单状态到数据库
     */
    private void updateOrderStatus(long orderId) {
        Order order = orderStore.getOrder(orderId);
        if (order == null) {
            logger.warn("Order not found for status update: orderId={}", orderId);
            return;
        }

        String sql = """
            UPDATE orders_history
            SET filled_quantity = ?, status = ?, frozen_amount = ?,
                cancel_reason = ?, update_time = ?
            WHERE order_id = ?
            """;

        int updated = jdbcTemplate.update(sql,
            order.getFilledQuantity(),
            order.getStatus().name(),
            order.getFrozenAmount(),
            order.getCancelReason() != null ? order.getCancelReason().name() : null,
            order.getUpdateTime(),
            orderId
        );

        if (updated == 0) {
            // 订单还未插入，先插入
            persistOrder(order);
        }
    }

    /**
     * 计算 frozenAmountUsed（买方）
     */
    private long calculateFrozenAmountUsed(Order order, TradeEvent tradeEvent) {
        long tradeAmount = tradeEvent.getPrice() * tradeEvent.getQuantity();
        long originalFrozenAmount = tradeEvent.getFrozenAmount();
        long originalQuantity = tradeEvent.getOrderQuantity();
        long orderPrice = tradeEvent.getOrderPrice();

        if (order.getOrderType() == OrderType.MARKET) {
            // 市价单：使用涨停价
            long maxPrice = tradeEvent.getMaxPrice();
            if (maxPrice > 0 && originalQuantity > 0) {
                long denominator = maxPrice * originalQuantity;
                if (denominator > 0) {
                    return (tradeAmount * originalFrozenAmount + denominator - 1) / denominator;
                }
            }
        } else {
            // 限价单
            if (orderPrice > 0 && originalQuantity > 0) {
                long denominator = orderPrice * originalQuantity;
                if (denominator > 0) {
                    return (tradeAmount * originalFrozenAmount + denominator - 1) / denominator;
                }
            }
        }

        // 简化处理：按比例
        if (originalQuantity > 0) {
            return (tradeAmount * originalFrozenAmount + originalQuantity - 1) / originalQuantity;
        }

        return tradeEvent.getFrozenAmount();
    }

    /**
     * 格式化交易日期
     */
    private String formatTradeDate(long timestamp) {
        java.time.LocalDate date = java.time.Instant.ofEpochMilli(timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate();
        return String.format("%04d%02d%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    // ==================== 辅助方法 ====================

    private <T> void addToIdempotentSet(LinkedHashSet<T> set, T item) {
        set.add(item);
        while (set.size() > IDEMPOTENT_SET_MAX_SIZE) {
            Iterator<T> it = set.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    // ==================== Getter ====================

    public boolean isRunning() { return running; }

    public long getLastProcessedIndex() { return lastProcessedIndex; }
}
