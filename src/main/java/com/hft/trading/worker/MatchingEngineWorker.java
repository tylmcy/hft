package com.hft.trading.worker;

import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.CancelSuccessEvent;
import com.hft.trading.event.EventType;
import com.hft.trading.event.TradeEvent;
import com.hft.trading.model.*;
import com.hft.trading.queue.*;
import com.hft.trading.snapshot.MatchingSnapshotWriter;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import com.hft.trading.websocket.WebSocketPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MatchingEngineWorker - 撮合引擎处理器
 *
 * 根据 V12 技术方案：
 * - 单线程批量处理 matching_input CQ 事件
 * - 批量大小：50
 * - 处理类型：NEW_ORDER / CANCEL_ORDER / SNAPSHOT / INSTRUMENT_INIT
 * - 输出：matching_output CQ（ORDER_UPDATE / TRADE / CANCEL_SUCCESS / CANCEL_REJECT）
 */
public class MatchingEngineWorker implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(MatchingEngineWorker.class);

    // 配置
    private static final int BATCH_SIZE = 50;
    private static final int IDEMPOTENT_SET_MAX_SIZE = 10000;

    // 撮合引擎（按 symbol 分组）
    private final Map<String, com.hft.trading.matching.MatchingEngine> matchingEngines = new HashMap<>();

    // 存储
    private final InMemoryOrderStore orderStore;
    private final InMemoryInstrumentStore instrumentStore;

    // WebSocket推送服务（可选）
    private final WebSocketPushService webSocketPushService;

    // 队列
    private final MatchingInputQueue matchingInputQueue;
    private final MatchingOutputQueue matchingOutputQueue;

    // 幂等集合
    private final LinkedHashSet<Long> processedOrderIds = new LinkedHashSet<>();
    private final LinkedHashSet<Long> processedCancelIds = new LinkedHashSet<>();

    // 状态
    private volatile boolean running = false;
    private volatile boolean ready = false;
    private volatile boolean replayMode = false;
    private volatile long lastProcessedIndex = -1;

    // 线程
    private Thread workerThread;

    public MatchingEngineWorker(InMemoryOrderStore orderStore,
                                InMemoryInstrumentStore instrumentStore,
                                MatchingInputQueue matchingInputQueue,
                                MatchingOutputQueue matchingOutputQueue,
                                WebSocketPushService webSocketPushService) {
        this.orderStore = orderStore;
        this.instrumentStore = instrumentStore;
        this.matchingInputQueue = matchingInputQueue;
        this.matchingOutputQueue = matchingOutputQueue;
        this.webSocketPushService = webSocketPushService;
    }

    /**
     * 启动 Worker
     */
    public synchronized void start() {
        if (running) {
            logger.warn("MatchingEngineWorker is already running");
            return;
        }

        running = true;
        workerThread = new Thread(this, "MatchingEngineWorker");
        workerThread.start();
        logger.info("MatchingEngineWorker started");
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
        logger.info("MatchingEngineWorker stopped");
    }

    @Override
    public void run() {
        logger.info("MatchingEngineWorker thread started");
        this.ready = true;

        while (running) {
            try {
                // 批量读取事件
                List<AccountInputEvent> events = matchingInputQueue.readBatch();

                if (events.isEmpty()) {
                    continue;
                }

                // 按 symbol 分组处理（提高缓存命中率）
                Map<String, List<AccountInputEvent>> eventsBySymbol = groupBySymbol(events);

                // 批量处理每个 symbol
                for (Map.Entry<String, List<AccountInputEvent>> entry : eventsBySymbol.entrySet()) {
                    String symbol = entry.getKey();
                    List<AccountInputEvent> symbolEvents = entry.getValue();

                    com.hft.trading.matching.MatchingEngine engine = getOrCreateEngine(symbol);

                    for (AccountInputEvent event : symbolEvents) {
                        try {
                            processEvent(event, engine);
                        } catch (Exception e) {
                            logger.error("Error processing event: type={}, symbol={}, error={}",
                                event.getEventType(), symbol, e.getMessage(), e);
                        }
                    }
                }

                // 更新处理位置
                lastProcessedIndex = matchingInputQueue.getCurrentIndex();

            } catch (Exception e) {
                logger.error("MatchingEngineWorker error: {}", e.getMessage(), e);
            }
        }

        logger.info("MatchingEngineWorker thread exited");
    }

    /**
     * 按 symbol 分组事件
     */
    private Map<String, List<AccountInputEvent>> groupBySymbol(List<AccountInputEvent> events) {
        Map<String, List<AccountInputEvent>> grouped = new HashMap<>();
        for (AccountInputEvent event : events) {
            if (event.getSymbol() != null) {
                grouped.computeIfAbsent(event.getSymbol(), k -> new ArrayList<>()).add(event);
            } else {
                // 无 symbol 的事件（如 INSTRUMENT_INIT）放入特殊组
                grouped.computeIfAbsent("", k -> new ArrayList<>()).add(event);
            }
        }
        return grouped;
    }

    /**
     * 获取或创建撮合引擎
     */
    private com.hft.trading.matching.MatchingEngine getOrCreateEngine(String symbol) {
        return matchingEngines.computeIfAbsent(symbol,
            k -> new com.hft.trading.matching.MatchingEngine(symbol));
    }

    /**
     * 处理单个事件
     */
    private void processEvent(AccountInputEvent event,
                              com.hft.trading.matching.MatchingEngine engine) {
        EventType eventType = event.getEventType();

        switch (eventType) {
            case NEW_ORDER:
                processNewOrder(event, engine);
                break;

            case CANCEL_ORDER:
                processCancelOrder(event, engine);
                break;

            case SNAPSHOT:
                processSnapshot(event);
                break;

            case INSTRUMENT_INIT:
                processInstrumentInit(event);
                break;

            default:
                logger.warn("Unknown event type in MatchingEngineWorker: {}", eventType);
        }
    }

    /**
     * 处理 NEW_ORDER 事件
     */
    private void processNewOrder(AccountInputEvent event,
                                  com.hft.trading.matching.MatchingEngine engine) {
        long orderId = event.getOrderId();

        // 幂等检查
        if (processedOrderIds.contains(orderId)) {
            logger.debug("Order already processed: orderId={}", orderId);
            return;
        }

        // 状态检查：订单是否已取消或拒绝
        Order order = orderStore.getOrder(orderId);
        if (order == null) {
            logger.warn("Order not found in store: orderId={}", orderId);
            return;
        }

        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REJECTED) {
            logger.debug("Order already cancelled/rejected, skipping: orderId={}, status={}",
                orderId, order.getStatus());
            return;
        }

        logger.debug("Processing NEW_ORDER: orderId={}, symbol={}, side={}, price={}, qty={}",
            orderId, event.getSymbol(), event.getSide(), event.getPrice(), event.getQuantity());

        // 写入 ORDER_UPDATE(status=NEW)
        matchingOutputQueue.writeOrderUpdateEvent(orderId, event.getSymbol(), "NEW");

        // 执行撮合
        List<TradeEvent> trades = engine.match(order);

        // 处理成交事件
        for (TradeEvent trade : trades) {
            matchingOutputQueue.writeTradeEvent(trade);
            logger.info("TRADE generated: tradeId={}, price={}, qty={}",
                trade.getTradeId(), trade.getPrice(), trade.getQuantity());
        }

        // 处理 FAK/FOK/MARKET 未成交部分
        handleRemainingOrder(order, event, engine);

        // 加入幂等集合
        addToIdempotentSet(processedOrderIds, orderId);

        logger.info("NEW_ORDER processed: orderId={}, trades={}", orderId, trades.size());
    }

    /**
     * 处理剩余订单（FAK/FOK/MARKET）
     */
    private void handleRemainingOrder(Order order, AccountInputEvent event,
                                       com.hft.trading.matching.MatchingEngine engine) {
        if (order.getRemainingQty() == 0) {
            // 全部成交，无需处理
            return;
        }

        // 仅 FAK/FOK/MARKET 需要撤销剩余部分
        if (order.getOrderType() == OrderType.LIMIT) {
            // 限价单保留在订单簿
            return;
        }

        // 剩余部分撤销
        CancelReason reason = order.getOrderType() == OrderType.FOK
            ? CancelReason.FOK_NOT_FILLABLE
            : CancelReason.FAK_REMAINING_CANCELLED;

        CancelSuccessEvent cancelEvent;
        if (order.getSide() == OrderSide.BUY) {
            cancelEvent = CancelSuccessEvent.forBuyOrder(
                order.getOrderId(),
                order.getAccountId(),
                order.getUserId(),
                order.getSymbol(),
                order.getFilledQuantity(),
                order.getRemainingQty(),
                reason,
                order.getFrozenAmount()
            );
        } else {
            cancelEvent = CancelSuccessEvent.forSellOrder(
                order.getOrderId(),
                order.getAccountId(),
                order.getUserId(),
                order.getSymbol(),
                order.getFilledQuantity(),
                order.getRemainingQty(),
                reason,
                order.getRemainingQty()
            );
        }

        matchingOutputQueue.writeCancelSuccessEvent(cancelEvent);
        logger.info("Remaining order cancelled: orderId={}, reason={}, remainingQty={}",
            order.getOrderId(), reason, order.getRemainingQty());
    }

    /**
     * 处理 CANCEL_ORDER 事件
     */
    private void processCancelOrder(AccountInputEvent event,
                                     com.hft.trading.matching.MatchingEngine engine) {
        long orderId = event.getOrderId();

        // 幂等检查
        if (processedCancelIds.contains(orderId)) {
            logger.debug("Cancel already processed: orderId={}", orderId);
            return;
        }

        logger.debug("Processing CANCEL_ORDER: orderId={}", orderId);

        Order order = orderStore.getOrder(orderId);
        if (order == null) {
            // 订单不存在，生成 CANCEL_REJECT
            logger.warn("Order not found for cancel: orderId={}", orderId);
            // 简化：直接生成 UNFREEZE 事件
            return;
        }

        // 从订单簿移除
        boolean removed = engine.getOrderBook().removeOrder(orderId);

        if (removed) {
            // 撤单成功
            CancelSuccessEvent cancelEvent;
            if (order.getSide() == OrderSide.BUY) {
                cancelEvent = CancelSuccessEvent.forBuyOrder(
                    orderId,
                    order.getAccountId(),
                    order.getUserId(),
                    order.getSymbol(),
                    order.getFilledQuantity(),
                    order.getRemainingQty(),
                    CancelReason.USER_CANCEL,
                    order.getFrozenAmount()
                );
            } else {
                cancelEvent = CancelSuccessEvent.forSellOrder(
                    orderId,
                    order.getAccountId(),
                    order.getUserId(),
                    order.getSymbol(),
                    order.getFilledQuantity(),
                    order.getRemainingQty(),
                    CancelReason.USER_CANCEL,
                    order.getRemainingQty()
                );
            }

            matchingOutputQueue.writeCancelSuccessEvent(cancelEvent);
            logger.info("CANCEL_SUCCESS: orderId={}", orderId);
        } else {
            // 订单不在订单簿（可能已成交或 PENDING 状态）
            logger.warn("Order not in orderbook: orderId={}, status={}", orderId, order.getStatus());

            // PENDING 状态订单也需要解冻
            if (order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.NEW) {
                CancelSuccessEvent cancelEvent;
                if (order.getSide() == OrderSide.BUY) {
                    cancelEvent = CancelSuccessEvent.forBuyOrder(
                        orderId,
                        order.getAccountId(),
                        order.getUserId(),
                        order.getSymbol(),
                        order.getFilledQuantity(),
                        order.getRemainingQty(),
                        CancelReason.PENDING_ORDER_NOT_IN_ORDERBOOK,
                        order.getFrozenAmount()
                    );
                } else {
                    cancelEvent = CancelSuccessEvent.forSellOrder(
                        orderId,
                        order.getAccountId(),
                        order.getUserId(),
                        order.getSymbol(),
                        order.getFilledQuantity(),
                        order.getRemainingQty(),
                        CancelReason.PENDING_ORDER_NOT_IN_ORDERBOOK,
                        order.getRemainingQty()
                    );
                }

                matchingOutputQueue.writeCancelSuccessEvent(cancelEvent);
                logger.info("PENDING order unfreeze: orderId={}", orderId);
            }
        }

        // 加入幂等集合
        addToIdempotentSet(processedCancelIds, orderId);
    }

    /**
     * 处理 SNAPSHOT 事件
     */
    private void processSnapshot(AccountInputEvent event) {
        logger.info("Processing SNAPSHOT event at index: {}", event.getCqIndex());

        try {
            // 1. 创建快照写入器
            MatchingSnapshotWriter snapshotWriter = new MatchingSnapshotWriter(
                matchingEngines, "./data/snapshots/");

            // 2. 获取当前处理位置
            long matchingInputIndex = lastProcessedIndex;
            long matchingOutputLastIndex = matchingOutputQueue.getLastIndex();

            // 3. 写入快照
            snapshotWriter.writeSnapshot(
                event.getCqIndex(),
                matchingInputIndex,
                matchingOutputLastIndex
            );

            logger.info("SNAPSHOT completed for MatchingEngine");

        } catch (Exception e) {
            logger.error("SNAPSHOT failed for MatchingEngine: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理 INSTRUMENT_INIT 事件
     */
    private void processInstrumentInit(AccountInputEvent event) {
        Instrument instrument = event.getInstrumentData();
        if (instrument != null) {
            instrumentStore.putInstrument(instrument);
            logger.info("Instrument initialized for matching: symbol={}", instrument.getSymbol());
        }
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

    // ==================== Getter ====================

    public boolean isRunning() { return running; }

    public boolean isReady() { return ready; }

    public long getLastProcessedIndex() { return lastProcessedIndex; }

    public void setReplayMode(boolean replayMode) { this.replayMode = replayMode; }

    public void setLastProcessedIndex(long index) { this.lastProcessedIndex = index; }

    /**
     * 获取撮合引擎 Map
     */
    public Map<String, com.hft.trading.matching.MatchingEngine> getMatchingEngines() {
        return matchingEngines;
    }

    /**
     * 获取五档行情
     */
    public com.hft.trading.matching.OrderBook.FiveLevelQuote getFiveLevelQuote(String symbol) {
        com.hft.trading.matching.MatchingEngine engine = matchingEngines.get(symbol);
        if (engine == null) {
            return null;
        }
        return engine.getOrderBook().getFiveLevelQuote();
    }
}