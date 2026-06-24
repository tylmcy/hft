package com.hft.trading.worker;

import com.hft.trading.event.CancelSuccessEvent;
import com.hft.trading.event.EventType;
import com.hft.trading.event.SettlementEvent;
import com.hft.trading.event.TradeEvent;
import com.hft.trading.model.*;
import com.hft.trading.queue.*;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import com.hft.trading.util.SlidingWindow;
import com.hft.trading.websocket.WebSocketPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * ClearingWorker - 清算处理器
 *
 * 根据 V12 技术方案：
 * - 单线程批量处理 matching_output CQ 事件
 * - 批量大小：100
 * - 处理类型：ORDER_UPDATE / TRADE / CANCEL_SUCCESS / CANCEL_REJECT
 * - 输出：account_settlement CQ（SETTLEMENT / UNFREEZE 事件）
 */
public class ClearingWorker implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ClearingWorker.class);

    // 配置
    private static final int BATCH_SIZE = 100;
    private static final int IDEMPOTENT_SET_MAX_SIZE = 10000;

    // 存储
    private final InMemoryOrderStore orderStore;
    private final InMemoryInstrumentStore instrumentStore;

    // WebSocket推送服务（可选）
    private final WebSocketPushService webSocketPushService;

    // 队列
    private final MatchingOutputQueue matchingOutputQueue;
    private final AccountSettlementQueue accountSettlementQueue;

    // 幂等集合（恢复时使用）
    private final LinkedHashSet<String> processedTradeIds = new LinkedHashSet<>();
    private final LinkedHashSet<Long> processedCancelIds = new LinkedHashSet<>();

    // 订单冻结金额记录（用于计算 frozenAmountUsed）
    private final Map<Long, OrderFreezeRecord> orderFreezeRecords = new HashMap<>();

    // 状态
    private volatile boolean running = false;
    private volatile boolean ready = false;
    private volatile boolean replayMode = false;
    private volatile long lastProcessedIndex = -1;

    // 延迟统计
    private volatile long lastBatchStartTime = 0;
    private volatile long lastBatchEndTime = 0;
    private volatile long lastBatchDurationMs = 0;
    private final SlidingWindow latencyWindow = new SlidingWindow(50);

    // 线程
    private Thread workerThread;

    /**
     * 订单冻结记录
     */
    private static class OrderFreezeRecord {
        final long originalFrozenAmount;
        final long originalQuantity;
        final long maxPrice; // 市价买单涨停价
        final long orderPrice; // 限价单价格

        OrderFreezeRecord(long frozenAmount, long quantity, long maxPrice, long orderPrice) {
            this.originalFrozenAmount = frozenAmount;
            this.originalQuantity = quantity;
            this.maxPrice = maxPrice;
            this.orderPrice = orderPrice;
        }
    }

    public ClearingWorker(InMemoryOrderStore orderStore,
                          InMemoryInstrumentStore instrumentStore,
                          MatchingOutputQueue matchingOutputQueue,
                          AccountSettlementQueue accountSettlementQueue,
                          WebSocketPushService webSocketPushService) {
        this.orderStore = orderStore;
        this.instrumentStore = instrumentStore;
        this.matchingOutputQueue = matchingOutputQueue;
        this.accountSettlementQueue = accountSettlementQueue;
        this.webSocketPushService = webSocketPushService;
    }

    /**
     * 启动 Worker
     */
    public synchronized void start() {
        if (running) {
            logger.warn("ClearingWorker is already running");
            return;
        }

        running = true;
        workerThread = new Thread(this, "ClearingWorker");
        workerThread.start();
        logger.info("ClearingWorker started");
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
        logger.info("ClearingWorker stopped");
    }

    @Override
    public void run() {
        logger.info("ClearingWorker thread started");
        this.ready = true;

        while (running) {
            try {
                long batchStart = System.nanoTime();
                lastBatchStartTime = batchStart;

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

                long batchEnd = System.nanoTime();
                lastBatchEndTime = batchEnd;
                long durationMs = (batchEnd - batchStart) / 1_000_000;
                lastBatchDurationMs = durationMs;
                latencyWindow.add(durationMs);

            } catch (Exception e) {
                logger.error("ClearingWorker error: {}", e.getMessage(), e);
            }
        }

        logger.info("ClearingWorker thread exited");
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

            case CANCEL_REJECT:
                processCancelReject(event.asCancelSuccessEvent());
                break;

            default:
                logger.warn("Unknown event type in ClearingWorker: {}", eventType);
        }
    }

    /**
     * 处理 ORDER_UPDATE 事件
     */
    private void processOrderUpdate(MatchingOutputQueue.OrderUpdateData data) {
        if (data == null) return;

        long orderId = data.getOrderId();
        Order order = orderStore.getOrder(orderId);

        if (order == null) {
            logger.warn("Order not found for ORDER_UPDATE: orderId={}", orderId);
            return;
        }

        // 更新订单状态：PENDING -> NEW
        if (order.getStatus() == OrderStatus.PENDING) {
            try {
                order.updateStatus(OrderStatus.NEW);
                logger.debug("Order status updated: orderId={}, status=NEW", orderId);

                // WebSocket推送订单状态更新
                pushOrderUpdate(order, "NEW");
            } catch (IllegalStateException e) {
                logger.warn("Failed to update order status: {}", e.getMessage());
            }
        }
    }

    /**
     * 处理 TRADE 事件
     */
    private void processTrade(TradeEvent tradeEvent) {
        if (tradeEvent == null) return;

        String tradeId = tradeEvent.getTradeId();

        // 幂等检查（恢复模式下）
        if (replayMode && processedTradeIds.contains(tradeId)) {
            logger.debug("Trade already processed: tradeId={}", tradeId);
            return;
        }

        logger.debug("Processing TRADE: tradeId={}, price={}, qty={}",
            tradeId, tradeEvent.getPrice(), tradeEvent.getQuantity());

        // 更新订单成交数量
        updateOrderFilledQuantity(tradeEvent);

        // 计算佣金
        long amount = tradeEvent.getAmount();
        int commissionRate = tradeEvent.getCommissionRate();
        long commission = calculateCommission(amount, commissionRate);

        // 生成买方和卖方 SETTLEMENT 事件
        generateSettlementEvents(tradeEvent, commission);

        // WebSocket推送成交信息给买方和卖方
        Order buyOrderForPush = orderStore.getOrder(tradeEvent.getBuyOrderId());
        Order sellOrderForPush = orderStore.getOrder(tradeEvent.getSellOrderId());
        if (buyOrderForPush != null) {
            pushTradeUpdate(tradeEvent, buyOrderForPush.getUserId(), buyOrderForPush.getOrderId(), OrderSide.BUY);
        }
        if (sellOrderForPush != null) {
            pushTradeUpdate(tradeEvent, sellOrderForPush.getUserId(), sellOrderForPush.getOrderId(), OrderSide.SELL);
        }

        // 加入幂等集合
        addToIdempotentSet(processedTradeIds, tradeId);

        logger.info("TRADE processed: tradeId={}, commission={}", tradeId, commission);
    }

    /**
     * 更新订单成交数量
     */
    private void updateOrderFilledQuantity(TradeEvent tradeEvent) {
        // 更新买方订单（updateFilledQuantity 会自动更新状态）
        Order buyOrder = orderStore.getOrder(tradeEvent.getBuyOrderId());
        if (buyOrder != null) {
            buyOrder.updateFilledQuantity(tradeEvent.getQuantity());
        }

        // 更新卖方订单
        Order sellOrder = orderStore.getOrder(tradeEvent.getSellOrderId());
        if (sellOrder != null) {
            sellOrder.updateFilledQuantity(tradeEvent.getQuantity());
        }
    }

    /**
     * 计算佣金
     */
    private long calculateCommission(long amount, int commissionRate) {
        // commission = (amount × commissionRate) / 10000（floor）
        return (amount * commissionRate) / 10000;
    }

    /**
     * 计算 frozenAmountUsed（买方）
     */
    private long calculateFrozenAmountUsed(Order order, TradeEvent tradeEvent) {
        // 记录原始冻结信息
        OrderFreezeRecord record = orderFreezeRecords.computeIfAbsent(
            order.getOrderId(),
            k -> new OrderFreezeRecord(
                order.getFrozenAmount(),
                order.getQuantity(),
                order.getMaxPrice(),
                order.getPrice()
            )
        );

        long tradeAmount = tradeEvent.getPrice() * tradeEvent.getQuantity();

        // 计算公式
        if (order.getOrderType() == OrderType.MARKET) {
            // 市价单：ceil(成交金额 × originalFrozenAmount / (maxPrice × originalQuantity))
            long denominator = record.maxPrice * record.originalQuantity;
            if (denominator == 0) return tradeEvent.getFrozenAmount();
            long calculated = (tradeAmount * record.originalFrozenAmount + denominator - 1) / denominator;
            return Math.min(calculated, order.getFrozenAmount());
        } else {
            // 限价单：ceil(成交金额 × originalFrozenAmount / (orderPrice × originalQuantity))
            long denominator = record.orderPrice * record.originalQuantity;
            if (denominator == 0) return tradeEvent.getFrozenAmount();
            long calculated = (tradeAmount * record.originalFrozenAmount + denominator - 1) / denominator;
            return Math.min(calculated, order.getFrozenAmount());
        }
    }

    /**
     * 生成买方和卖方 SETTLEMENT 事件
     */
    private void generateSettlementEvents(TradeEvent tradeEvent, long commission) {
        // 买方结算
        Order buyOrder = orderStore.getOrder(tradeEvent.getBuyOrderId());
        if (buyOrder != null) {
            long frozenAmountUsed = calculateFrozenAmountUsed(buyOrder, tradeEvent);

            // 更新订单冻结金额
            buyOrder.setFrozenAmount(buyOrder.getFrozenAmount() - frozenAmountUsed);

            SettlementEvent buyerSettlement = SettlementEvent.forBuyer(
                tradeEvent.getTradeId(),
                tradeEvent.getBuyOrderId(),
                tradeEvent.getBuyAccountId(),
                tradeEvent.getBuyOrderId(), // userId 从订单获取，这里简化处理
                tradeEvent.getSymbol(),
                tradeEvent.getPrice(),
                tradeEvent.getQuantity(),
                frozenAmountUsed,
                commission
            );

            accountSettlementQueue.writeSettlementEvent(buyerSettlement);
            logger.debug("Buyer SETTLEMENT generated: orderId={}, frozenUsed={}",
                tradeEvent.getBuyOrderId(), frozenAmountUsed);
        }

        // 卖方结算
        Order sellOrder = orderStore.getOrder(tradeEvent.getSellOrderId());
        if (sellOrder != null) {
            SettlementEvent sellerSettlement = SettlementEvent.forSeller(
                tradeEvent.getTradeId(),
                tradeEvent.getSellOrderId(),
                tradeEvent.getSellAccountId(),
                tradeEvent.getSellOrderId(), // userId 从订单获取
                tradeEvent.getSymbol(),
                tradeEvent.getPrice(),
                tradeEvent.getQuantity(),
                commission
            );

            accountSettlementQueue.writeSettlementEvent(sellerSettlement);
            logger.debug("Seller SETTLEMENT generated: orderId={}", tradeEvent.getSellOrderId());
        }
    }

    /**
     * 处理 CANCEL_SUCCESS 事件
     */
    private void processCancelSuccess(CancelSuccessEvent cancelEvent) {
        if (cancelEvent == null) return;

        long orderId = cancelEvent.getOrderId();

        // 幂等检查（恢复模式下）
        if (replayMode && processedCancelIds.contains(orderId)) {
            logger.debug("Cancel already processed: orderId={}", orderId);
            return;
        }

        logger.debug("Processing CANCEL_SUCCESS: orderId={}, remainingQty={}",
            orderId, cancelEvent.getRemainingQty());

        // 生成 UNFREEZE 事件
        long unfreezeAmount = cancelEvent.getUnfreezeAmount();
        long unfreezeQty = cancelEvent.getUnfreezeQty();

        accountSettlementQueue.writeUnfreezeEvent(
            orderId,
            cancelEvent.getAccountId(),
            cancelEvent.getUserId(),
            cancelEvent.getSymbol(),
            unfreezeAmount,
            unfreezeQty,
            cancelEvent.getSide() == OrderSide.BUY ? EventType.FREEZE : EventType.UNFREEZE
        );

        // WebSocket推送订单状态更新
        Order order = orderStore.getOrder(orderId);
        if (order != null) {
            pushOrderUpdate(order, "CANCELLED");
        }

        // 加入幂等集合
        addToIdempotentSet(processedCancelIds, orderId);

        logger.info("CANCEL_SUCCESS processed: orderId={}, unfreezeAmount={}, unfreezeQty={}",
            orderId, unfreezeAmount, unfreezeQty);
    }

    /**
     * 处理 CANCEL_REJECT 事件
     */
    private void processCancelReject(CancelSuccessEvent cancelEvent) {
        if (cancelEvent == null) return;

        // CANCEL_REJECT 仅在 PENDING_ORDER_NOT_IN_ORDERBOOK 且订单状态为 PENDING/NEW 时触发 UNFREEZE
        // 简化处理：不做额外操作，由 AccountWorker 处理
        logger.debug("CANCEL_REJECT received: orderId={}", cancelEvent.getOrderId());
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

    /**
     * WebSocket推送辅助方法（null安全）
     */
    private void pushIfAvailable(Runnable pushAction) {
        if (webSocketPushService != null) {
            try {
                pushAction.run();
            } catch (Exception e) {
                logger.warn("WebSocket push failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 推送订单状态更新
     */
    private void pushOrderUpdate(Order order, String status) {
        pushIfAvailable(() -> {
            WebSocketPushService.OrderUpdateMessage message = new WebSocketPushService.OrderUpdateMessage();
            message.setOrderId(String.valueOf(order.getOrderId()));
            message.setSymbol(order.getSymbol());
            message.setSide(order.getSide().name());
            message.setOrderType(order.getOrderType().name());
            message.setQuantity(order.getQuantity());
            message.setPrice(order.getPrice() / 100.0); // 分转元
            message.setFilledQuantity(order.getFilledQuantity());
            message.setAvgPrice(order.getFilledQuantity() > 0 ? order.getPrice() / 100.0 : 0.0);
            message.setStatus(status);
            webSocketPushService.pushOrderUpdate(order.getUserId(), message);
        });
    }

    /**
     * 推送成交信息
     */
    private void pushTradeUpdate(TradeEvent tradeEvent, long userId, long orderId, OrderSide side) {
        pushIfAvailable(() -> {
            WebSocketPushService.TradeUpdateMessage message = new WebSocketPushService.TradeUpdateMessage();
            message.setTradeId(tradeEvent.getTradeId());
            message.setOrderId(String.valueOf(orderId));
            message.setSymbol(tradeEvent.getSymbol());
            message.setSide(side.name());
            message.setQuantity(tradeEvent.getQuantity());
            message.setPrice(tradeEvent.getPrice() / 100.0); // 分转元
            message.setTradeTime(tradeEvent.getTimestamp());
            webSocketPushService.pushTradeUpdate(userId, message);
        });
    }

    // ==================== Getter ====================

    public boolean isRunning() { return running; }

    public boolean isReady() { return ready; }

    public long getLastProcessedIndex() { return lastProcessedIndex; }

    public void setReplayMode(boolean replayMode) { this.replayMode = replayMode; }

    public void setLastProcessedIndex(long index) { this.lastProcessedIndex = index; }

    public long getLastBatchDurationMs() { return lastBatchDurationMs; }

    public long getP50LatencyMs() { return latencyWindow.getPercentile(50); }

    public long getP90LatencyMs() { return latencyWindow.getPercentile(90); }

    public long getP99LatencyMs() { return latencyWindow.getPercentile(99); }
}