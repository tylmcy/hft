package com.hft.trading.worker;

import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.EventType;
import com.hft.trading.event.SettlementEvent;
import com.hft.trading.event.TradingPhase;
import com.hft.trading.model.*;
import com.hft.trading.queue.*;
import com.hft.trading.service.FutureManager;
import com.hft.trading.snapshot.AccountSnapshotWriter;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import com.hft.trading.store.InMemoryPositionStore;
import com.hft.trading.websocket.WebSocketPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * AccountWorker - 账户操作处理器
 *
 * 根据 V12 技术方案：
 * - 单线程批量处理 account_input CQ 事件
 * - 批量大小：50
 * - 处理类型：NEW_ORDER / CANCEL_ORDER / SETTLEMENT / SNAPSHOT / PHASE_CHANGE / DEPOSIT
 * - 写入：matching_input CQ + account_output CQ
 */
public class AccountWorker implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(AccountWorker.class);

    // 配置
    private static final int BATCH_SIZE = 50;
    private static final int IDEMPOTENT_SET_MAX_SIZE = 10000;

    // 存储
    private final InMemoryAccountStore accountStore;
    private final InMemoryPositionStore positionStore;
    private final InMemoryOrderStore orderStore;
    private final InMemoryInstrumentStore instrumentStore;
    private final FutureManager futureManager;

    // WebSocket推送服务（可选）
    private final WebSocketPushService webSocketPushService;

    // 队列
    private final AccountInputQueue accountInputQueue;
    private final MatchingInputQueue matchingInputQueue;
    private final AccountSettlementQueue accountSettlementQueue;

    // 幂等集合
    private final LinkedHashSet<Long> processedOrderIds = new LinkedHashSet<>();
    private final LinkedHashSet<String> processedSettlementIds = new LinkedHashSet<>();
    private final LinkedHashSet<Long> processedUnfreezeIds = new LinkedHashSet<>();

    // 状态
    private volatile boolean running = false;
    private volatile boolean replayMode = false;
    private volatile TradingPhase currentPhase = TradingPhase.CLOSED;
    private volatile long lastProcessedIndex = -1;

    // 线程
    private Thread workerThread;

    public AccountWorker(InMemoryAccountStore accountStore,
                         InMemoryPositionStore positionStore,
                         InMemoryOrderStore orderStore,
                         InMemoryInstrumentStore instrumentStore,
                         FutureManager futureManager,
                         AccountInputQueue accountInputQueue,
                         MatchingInputQueue matchingInputQueue,
                         AccountSettlementQueue accountSettlementQueue,
                         WebSocketPushService webSocketPushService) {
        this.accountStore = accountStore;
        this.positionStore = positionStore;
        this.orderStore = orderStore;
        this.instrumentStore = instrumentStore;
        this.futureManager = futureManager;
        this.accountInputQueue = accountInputQueue;
        this.matchingInputQueue = matchingInputQueue;
        this.accountSettlementQueue = accountSettlementQueue;
        this.webSocketPushService = webSocketPushService;
    }

    /**
     * 启动 Worker
     */
    public synchronized void start() {
        if (running) {
            logger.warn("AccountWorker is already running");
            return;
        }

        running = true;
        workerThread = new Thread(this, "AccountWorker");
        workerThread.start();
        logger.info("AccountWorker started");
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
        logger.info("AccountWorker stopped");
    }

    @Override
    public void run() {
        logger.info("AccountWorker thread started");

        while (running) {
            try {
                // 批量读取事件
                List<AccountInputEvent> events = accountInputQueue.readBatch();

                if (events.isEmpty()) {
                    continue;
                }

                // 批量处理
                List<AccountInputEvent> matchingInputEvents = new ArrayList<>();

                for (AccountInputEvent event : events) {
                    try {
                        ProcessResult result = processEvent(event, matchingInputEvents);
                        logger.debug("Event processed: type={}, success={}, orderId={}",
                            event.getEventType(), result.success, result.orderId);
                    } catch (Exception e) {
                        logger.error("Error processing event: type={}, error={}",
                            event.getEventType(), e.getMessage(), e);
                    }
                }

                // 批量写入 matching_input CQ
                if (!matchingInputEvents.isEmpty()) {
                    matchingInputQueue.writeBatch(matchingInputEvents);
                }

                // 更新处理位置
                lastProcessedIndex = accountInputQueue.getCurrentIndex();

            } catch (Exception e) {
                logger.error("AccountWorker error: {}", e.getMessage(), e);
            }
        }

        logger.info("AccountWorker thread exited");
    }

    /**
     * 处理单个事件
     */
    private ProcessResult processEvent(AccountInputEvent event,
                                        List<AccountInputEvent> matchingInputEvents) {
        EventType eventType = event.getEventType();

        switch (eventType) {
            case NEW_ORDER:
                return processNewOrder(event, matchingInputEvents);

            case CANCEL_ORDER:
                return processCancelOrder(event, matchingInputEvents);

            case SETTLEMENT:
                return processSettlement(event);

            case SNAPSHOT:
                return processSnapshot(event);

            case PHASE_CHANGE:
                return processPhaseChange(event);

            case DEPOSIT:
                return processDeposit(event);

            case INSTRUMENT_INIT:
                return processInstrumentInit(event);

            default:
                logger.warn("Unknown event type: {}", eventType);
                return new ProcessResult(false, "Unknown event type", null);
        }
    }

    /**
     * 处理 NEW_ORDER 事件
     */
    private ProcessResult processNewOrder(AccountInputEvent event,
                                           List<AccountInputEvent> matchingInputEvents) {
        long orderId = event.getOrderId();
        long accountId = event.getAccountId();
        OrderSide side = event.getSide();

        logger.debug("Processing NEW_ORDER: orderId={}, accountId={}, side={}, symbol={}",
            orderId, accountId, side, event.getSymbol());

        // 1. 检查账户
        Account account = accountStore.getAccount(accountId);
        if (account == null) {
            return completeWithError(orderId, "账户不存在: " + accountId);
        }

        // 2. 检查账户状态
        if (account.getStatus() != AccountStatus.ACTIVE) {
            return completeWithError(orderId, "账户状态异常: " + account.getStatus());
        }

        // 3. 检查交易阶段
        if (currentPhase != TradingPhase.CONTINUOUS_TRADING) {
            return completeWithError(orderId, "非交易时段: " + currentPhase);
        }

        // 4. 幂等检查
        if (processedOrderIds.contains(orderId)) {
            logger.debug("Order already processed: orderId={}", orderId);
            // 已经处理过，跳过但返回成功
            return new ProcessResult(true, "已处理", orderId);
        }

        // 5. 执行冻结
        boolean freezeSuccess = false;
        if (side == OrderSide.BUY) {
            freezeSuccess = freezeForBuyOrder(event, account);
        } else {
            freezeSuccess = freezeForSellOrder(event, account);
        }

        if (!freezeSuccess) {
            return completeWithError(orderId, "冻结失败");
        }

        // 6. 创建订单
        Order order = createOrder(event);
        orderStore.addOrder(order);

        // 7. 加入幂等集合
        addToIdempotentSet(processedOrderIds, orderId);

        // 8. 转发到 matching_input CQ（非 replay 模式）
        if (!replayMode || !processedOrderIds.contains(orderId)) {
            matchingInputEvents.add(event);
        }

        logger.info("NEW_ORDER processed: orderId={}, status=PENDING", orderId);

        // WebSocket推送订单状态更新
        pushOrderUpdate(order, "PENDING");

        return new ProcessResult(true, "冻结成功", orderId);
    }

    /**
     * 买单冻结资金
     */
    private boolean freezeForBuyOrder(AccountInputEvent event, Account account) {
        long frozenAmount = event.getFrozenAmount();

        try {
            accountStore.freeze(account.getAccountId(), frozenAmount);
            logger.debug("Frozen {} for buy order, accountId={}", frozenAmount, account.getAccountId());
            return true;
        } catch (IllegalStateException e) {
            logger.warn("Freeze failed for buy order: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 卖单冻结持仓
     */
    private boolean freezeForSellOrder(AccountInputEvent event, Account account) {
        String symbol = event.getSymbol();
        long quantity = event.getQuantity();

        Position position = positionStore.getPosition(account.getAccountId(), symbol);
        if (position == null) {
            logger.warn("No position for sell order: accountId={}, symbol={}",
                account.getAccountId(), symbol);
            return false;
        }

        if (position.getAvailable() < quantity) {
            logger.warn("Insufficient position: available={}, required={}",
                position.getAvailable(), quantity);
            return false;
        }

        try {
            positionStore.freezePosition(account.getAccountId(), symbol, quantity);
            logger.debug("Frozen {} shares for sell order, accountId={}, symbol={}",
                quantity, account.getAccountId(), symbol);
            return true;
        } catch (IllegalStateException e) {
            logger.warn("Freeze position failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 创建订单对象
     */
    private Order createOrder(AccountInputEvent event) {
        int symbolCode = parseSymbolCode(event.getSymbol());
        Order order = new Order(
            event.getOrderId(),
            event.getSymbol(),
            symbolCode,
            event.getSide(),
            event.getOrderType(),
            event.getPrice(),
            event.getQuantity(),
            event.getAccountId(),
            event.getUserId()
        );
        order.setFrozenAmount(event.getFrozenAmount());
        order.setMaxPrice(event.getMaxPrice() != null ? event.getMaxPrice() : 0L);
        order.setMinPrice(event.getMinPrice() != null ? event.getMinPrice() : 0L);
        order.setIdempotencyKey(event.getIdempotencyKey());
        return order;
    }

    /**
     * 处理 CANCEL_ORDER 事件
     */
    private ProcessResult processCancelOrder(AccountInputEvent event,
                                              List<AccountInputEvent> matchingInputEvents) {
        long orderId = event.getOrderId();

        logger.debug("Processing CANCEL_ORDER: orderId={}", orderId);

        // 获取订单
        Order order = orderStore.getOrder(orderId);
        if (order == null) {
            logger.warn("Order not found for cancel: orderId={}", orderId);
            return new ProcessResult(false, "订单不存在", orderId);
        }

        // 检查订单状态
        if (order.getStatus() == OrderStatus.FILLED || order.getStatus() == OrderStatus.CANCELLED) {
            logger.warn("Order already completed: orderId={}, status={}", orderId, order.getStatus());
            return new ProcessResult(false, "订单已完成", orderId);
        }

        // 转发到 matching_input CQ 由 MatchingEngine 处理撤单
        matchingInputEvents.add(event);

        logger.info("CANCEL_ORDER forwarded to MatchingEngine: orderId={}", orderId);

        return new ProcessResult(true, "撤单请求已转发", orderId);
    }

    /**
     * 处理 SETTLEMENT 事件（来自 ClearingWorker）
     */
    private ProcessResult processSettlement(AccountInputEvent event) {
        // SETTLEMENT 由第二阶段 AccountWorker 从 account_settlement CQ 处理
        // 这里暂不实现，由 AccountSettlementWorker 处理
        logger.debug("SETTLEMENT event received, will be processed by AccountSettlementWorker");
        return new ProcessResult(true, "SETTLEMENT received", null);
    }

    /**
     * 处理 SNAPSHOT 事件
     */
    private ProcessResult processSnapshot(AccountInputEvent event) {
        logger.info("Processing SNAPSHOT event at index: {}", event.getCqIndex());

        try {
            // 1. 创建快照写入器
            AccountSnapshotWriter snapshotWriter = new AccountSnapshotWriter(
                accountStore, positionStore, "./data/snapshots/");

            // 2. 获取当前处理位置
            long accountInputIndex = lastProcessedIndex;
            long accountSettlementIndex = 0; // TODO: 从 AccountSettlementQueue 获取
            long matchingInputLastIndex = 0; // TODO: 从 MatchingInputQueue 获取

            // 3. 写入快照
            snapshotWriter.writeSnapshot(
                event.getCqIndex(),
                accountInputIndex,
                accountSettlementIndex,
                matchingInputLastIndex
            );

            logger.info("SNAPSHOT completed at index: {}", event.getCqIndex());
            return new ProcessResult(true, "SNAPSHOT completed", null);

        } catch (Exception e) {
            logger.error("SNAPSHOT failed: {}", e.getMessage(), e);
            return new ProcessResult(false, "SNAPSHOT failed: " + e.getMessage(), null);
        }
    }

    /**
     * 处理 PHASE_CHANGE 事件
     */
    public ProcessResult processPhaseChange(AccountInputEvent event) {
        TradingPhase newPhase = event.getPhase();
        logger.info("Processing PHASE_CHANGE: {} -> {}", currentPhase, newPhase);

        currentPhase = newPhase;

        logger.info("Trading phase changed to: {}", currentPhase);
        return new ProcessResult(true, "Phase changed", null);
    }

    /**
     * 处理 DEPOSIT 事件（入金）
     */
    private ProcessResult processDeposit(AccountInputEvent event) {
        long accountId = event.getAccountId();
        long amount = event.getDepositAmount();

        logger.info("Processing DEPOSIT: accountId={}, amount={}", accountId, amount);

        Account account = accountStore.getAccount(accountId);
        if (account == null) {
            logger.error("Account not found for deposit: accountId={}", accountId);
            return new ProcessResult(false, "账户不存在", null);
        }

        // 执行入金
        accountStore.deposit(accountId, amount);

        logger.info("DEPOSIT completed: accountId={}, newBalance={}",
            accountId, account.getBalance());

        // WebSocket推送账户资金变动
        pushAccountUpdate(account);

        return new ProcessResult(true, "入金成功", null);
    }

    /**
     * 处理 INSTRUMENT_INIT 事件
     */
    private ProcessResult processInstrumentInit(AccountInputEvent event) {
        Instrument instrument = event.getInstrumentData();
        if (instrument != null) {
            instrumentStore.putInstrument(instrument);
            logger.info("Instrument initialized: symbol={}", instrument.getSymbol());
        }
        return new ProcessResult(true, "Instrument initialized", null);
    }

    /**
     * 处理 account_settlement CQ 的 SETTLEMENT 事件（第二阶段）
     */
    public void processSettlementEvent(SettlementEvent event) {
        String settlementId = event.getSettlementId();

        // 幂等检查
        if (processedSettlementIds.contains(settlementId)) {
            logger.debug("Settlement already processed: settlementId={}", settlementId);
            return;
        }

        // 执行结算转账
        if (event.getSettlementType() == OrderSide.BUY) {
            settleBuyer(event);
        } else {
            settleSeller(event);
        }

        // 加入幂等集合
        addToIdempotentSet(processedSettlementIds, settlementId);
    }

    /**
     * 买方结算
     */
    private void settleBuyer(SettlementEvent event) {
        long accountId = event.getAccountId();
        long price = event.getPrice();
        long quantity = event.getQuantity();
        long frozenAmountUsed = event.getFrozenAmountUsed();
        long commission = event.getCommission();
        long totalDeduction = price * quantity + commission;
        long refund = frozenAmountUsed - totalDeduction;

        logger.debug("Settling buyer: accountId={}, price={}, qty={}, frozenUsed={}, commission={}",
            accountId, price, quantity, frozenAmountUsed, commission);

        // 原子更新账户
        Account account = accountStore.getAccount(accountId);
        if (account == null) {
            logger.error("Account not found for settlement: accountId={}", accountId);
            return;
        }

        // 执行结算（frozen -> balance 扣减，refund -> available）
        accountStore.settleBuy(accountId, frozenAmountUsed, totalDeduction, refund);

        // 更新持仓（T+1 锁定）
        positionStore.settleBuyPosition(accountId, event.getSymbol(), quantity, price);

        logger.info("Buyer settled: accountId={}, totalDeduction={}, refund={}",
            accountId, totalDeduction, refund);

        // WebSocket推送账户和持仓变动
        pushAccountUpdate(account);
        pushPositionUpdate(accountId, event.getSymbol());
    }

    /**
     * 卖方结算
     */
    private void settleSeller(SettlementEvent event) {
        long accountId = event.getAccountId();
        long price = event.getPrice();
        long quantity = event.getQuantity();
        long commission = event.getCommission();
        long netIncome = price * quantity - commission;

        logger.debug("Settling seller: accountId={}, price={}, qty={}, commission={}",
            accountId, price, quantity, commission);

        // 更新持仓
        positionStore.settleSellPosition(accountId, event.getSymbol(), quantity);

        // 更新账户余额
        Account account = accountStore.getAccount(accountId);
        if (account != null) {
            accountStore.settleSell(accountId, netIncome);

            // WebSocket推送账户和持仓变动
            pushAccountUpdate(account);
            pushPositionUpdate(accountId, event.getSymbol());
        }

        logger.info("Seller settled: accountId={}, netIncome={}", accountId, netIncome);
    }

    // ==================== 辅助方法 ====================

    private ProcessResult completeWithError(Long orderId, String message) {
        logger.warn("Event processing failed: {}", message);
        return new ProcessResult(false, message, orderId);
    }

    private int parseSymbolCode(String symbol) {
        try {
            return Integer.parseInt(symbol);
        } catch (NumberFormatException e) {
            return symbol.hashCode();
        }
    }

    private <T> void addToIdempotentSet(LinkedHashSet<T> set, T item) {
        set.add(item);
        // 超过最大容量时移除最旧的
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
     * 推送账户资金变动
     */
    private void pushAccountUpdate(Account account) {
        pushIfAvailable(() -> {
            WebSocketPushService.AccountUpdateMessage message = new WebSocketPushService.AccountUpdateMessage();
            message.setUserId(account.getUserId());
            message.setBalance(account.getBalance() / 100.0); // 分转元
            message.setAvailable(account.getAvailable() / 100.0);
            message.setFrozen(account.getFrozen() / 100.0);
            message.setUpdateTime(System.currentTimeMillis());
            webSocketPushService.pushAccountUpdate(account.getUserId(), message);
        });
    }

    /**
     * 推送持仓变动
     */
    private void pushPositionUpdate(long accountId, String symbol) {
        pushIfAvailable(() -> {
            Position position = positionStore.getPosition(accountId, symbol);
            if (position != null) {
                Account account = accountStore.getAccount(accountId);
                if (account != null) {
                    WebSocketPushService.PositionUpdateMessage message = new WebSocketPushService.PositionUpdateMessage();
                    message.setUserId(account.getUserId());
                    message.setSymbol(symbol);
                    message.setQuantity(position.getQuantity());
                    message.setAvailable(position.getAvailable());
                    message.setFrozen(position.getFrozen());
                    message.setAvgCost(position.getAvgPrice() / 100.0); // 分转元
                    message.setUpdateTime(System.currentTimeMillis());
                    webSocketPushService.pushPositionUpdate(account.getUserId(), message);
                }
            }
        });
    }

    // ==================== Getter ====================

    public boolean isRunning() { return running; }

    public TradingPhase getCurrentPhase() { return currentPhase; }

    public long getLastProcessedIndex() { return lastProcessedIndex; }

    public void setReplayMode(boolean replayMode) { this.replayMode = replayMode; }

    public void setLastProcessedIndex(long index) { this.lastProcessedIndex = index; }

    // ==================== 内部类 ====================

    private static class ProcessResult {
        final boolean success;
        final String message;
        final Long orderId;

        ProcessResult(boolean success, String message, Long orderId) {
            this.success = success;
            this.message = message;
            this.orderId = orderId;
        }
    }
}
