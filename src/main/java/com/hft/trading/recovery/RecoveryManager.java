package com.hft.trading.recovery;

import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.CancelSuccessEvent;
import com.hft.trading.event.EventType;
import com.hft.trading.event.SettlementEvent;
import com.hft.trading.event.TradeEvent;
import com.hft.trading.matching.MatchingEngine;
import com.hft.trading.model.Order;
import com.hft.trading.model.OrderSide;
import com.hft.trading.queue.*;
import com.hft.trading.snapshot.AccountSnapshotReader;
import com.hft.trading.snapshot.MatchingSnapshotReader;
import com.hft.trading.snapshot.SnapshotFileUtils;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import com.hft.trading.store.InMemoryPositionStore;
import com.hft.trading.worker.AccountWorker;
import com.hft.trading.worker.ClearingWorker;
import com.hft.trading.worker.MatchingEngineWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * RecoveryManager - 恢复管理器
 *
 * 根据 V12 技术方案，实现 4 阶段恢复流程：
 * Phase 0: 检测并恢复临时文件
 * Phase 1: AccountWorker 阶段 1 - 加载快照，设置 replay 模式，构建幂等集合，重放 CQ
 * Phase 2: MatchingEngine - 加载快照，设置 replay 模式，重放 CQ
 * Phase 3: ClearingWorker - 加载位置或回退，重放 CQ
 * Phase 4: AccountWorker 阶段 2 - 重放结算 CQ，禁用 replay 模式
 */
public class RecoveryManager {

    private static final Logger logger = LoggerFactory.getLogger(RecoveryManager.class);

    // 配置路径
    private final String snapshotDir;
    private final String chronicleBasePath;

    // 存储引用
    private final InMemoryAccountStore accountStore;
    private final InMemoryPositionStore positionStore;
    private final InMemoryOrderStore orderStore;
    private final InMemoryInstrumentStore instrumentStore;
    private final Map<String, MatchingEngine> matchingEngines;

    // 队列引用
    private final AccountInputQueue accountInputQueue;
    private final MatchingInputQueue matchingInputQueue;
    private final MatchingOutputQueue matchingOutputQueue;
    private final AccountSettlementQueue accountSettlementQueue;

    // Worker 引用
    private final AccountWorker accountWorker;
    private final MatchingEngineWorker matchingEngineWorker;
    private final ClearingWorker clearingWorker;

    /**
     * 构造函数
     *
     * @param snapshotDir 快照目录
     * @param chronicleBasePath Chronicle 数据目录
     * @param accountStore 账户存储
     * @param positionStore 持仓存储
     * @param orderStore 订单存储
     * @param instrumentStore 证券存储
     * @param matchingEngines 撮合引擎 Map
     * @param accountInputQueue 账户输入队列
     * @param matchingInputQueue 撮合输入队列
     * @param matchingOutputQueue 撮合输出队列
     * @param accountSettlementQueue 账户结算队列
     * @param accountWorker 账户处理器
     * @param matchingEngineWorker 撮合引擎处理器
     * @param clearingWorker 清算处理器
     */
    public RecoveryManager(String snapshotDir,
                           String chronicleBasePath,
                           InMemoryAccountStore accountStore,
                           InMemoryPositionStore positionStore,
                           InMemoryOrderStore orderStore,
                           InMemoryInstrumentStore instrumentStore,
                           Map<String, MatchingEngine> matchingEngines,
                           AccountInputQueue accountInputQueue,
                           MatchingInputQueue matchingInputQueue,
                           MatchingOutputQueue matchingOutputQueue,
                           AccountSettlementQueue accountSettlementQueue,
                           AccountWorker accountWorker,
                           MatchingEngineWorker matchingEngineWorker,
                           ClearingWorker clearingWorker) {
        this.snapshotDir = snapshotDir;
        this.chronicleBasePath = chronicleBasePath;
        this.accountStore = accountStore;
        this.positionStore = positionStore;
        this.orderStore = orderStore;
        this.instrumentStore = instrumentStore;
        this.matchingEngines = matchingEngines;
        this.accountInputQueue = accountInputQueue;
        this.matchingInputQueue = matchingInputQueue;
        this.matchingOutputQueue = matchingOutputQueue;
        this.accountSettlementQueue = accountSettlementQueue;
        this.accountWorker = accountWorker;
        this.matchingEngineWorker = matchingEngineWorker;
        this.clearingWorker = clearingWorker;
    }

    /**
     * 检查是否需要恢复
     *
     * 检查规则：
     * 1. 快照目录下存在 .dat 文件（非 clearing_position.dat）
     * 2. Chronicle 目录下存在 .cq4 文件
     * 3. 存在 clearing_position.dat
     *
     * @return true 如果需要恢复
     */
    public boolean needsRecovery() {
        Path snapshotPath = Paths.get(snapshotDir);
        Path chroniclePath = Paths.get(chronicleBasePath);

        // 检查快照文件（排除 clearing_position.dat）
        if (hasDataFiles(snapshotPath)) {
            logger.info("Recovery needed: found .dat files in snapshot directory");
            return true;
        }

        // 检查 CQ 文件
        if (hasCqFiles(chroniclePath)) {
            logger.info("Recovery needed: found .cq4 files in chronicle directory");
            return true;
        }

        // 检查 clearing_position.dat
        Path clearingPosition = chroniclePath.resolve("clearing_position.dat");
        if (Files.exists(clearingPosition)) {
            logger.info("Recovery needed: found clearing_position.dat");
            return true;
        }

        logger.info("No recovery needed: no snapshot or CQ files found");
        return false;
    }

    /**
     * 执行 4 阶段恢复
     *
     * @throws Exception 恢复失败
     */
    public void performRecovery() throws Exception {
        logger.info("Starting 4-phase recovery...");

        // Phase 0: 检测并恢复临时文件
        phase0_recoverTmpFiles();

        // Phase 1: AccountWorker 阶段 1
        phase1_accountWorkerPhase1();

        // Phase 2: MatchingEngine
        phase2_matchingEngine();

        // Phase 3: ClearingWorker
        phase3_clearingWorker();

        // Phase 4: AccountWorker 阶段 2
        phase4_accountWorkerPhase2();

        logger.info("4-phase recovery completed successfully");
    }

    // ==================== Phase 实现 ====================

    /**
     * Phase 0: 检测并恢复临时文件
     */
    private void phase0_recoverTmpFiles() {
        logger.info("Phase 0: Detecting and recovering tmp files...");

        // 恢复账户快照临时文件
        SnapshotFileUtils.detectAndRecoverTmpFiles(snapshotDir, "account");

        // 恢复订单簿快照临时文件
        SnapshotFileUtils.detectAndRecoverTmpFiles(snapshotDir, "matching");

        logger.info("Phase 0 completed");
    }

    /**
     * Phase 1: AccountWorker 阶段 1
     * - 加载账户快照
     * - 设置 replay 模式
     * - 构建幂等集合
     * - 重放 account_input CQ
     */
    private void phase1_accountWorkerPhase1() throws Exception {
        logger.info("Phase 1: AccountWorker phase 1 starting...");

        if (accountWorker == null) {
            logger.warn("AccountWorker is null, skipping Phase 1");
            return;
        }

        // 查找最新账户快照
        var snapshotInfo = SnapshotFileUtils.findLatestValidSnapshot(snapshotDir, "account");

        long accountInputIndex = -1;
        long accountSettlementIndex = -1;
        long matchingInputLastIndex = -1;

        if (snapshotInfo.isPresent()) {
            // 加载快照到内存
            AccountSnapshotReader reader = new AccountSnapshotReader(accountStore, positionStore);
            String filePath = snapshotInfo.get().getFilePath();
            var snapshotData = reader.readSnapshot(filePath);

            accountInputIndex = snapshotData.getAccountInputIndex();
            accountSettlementIndex = snapshotData.getAccountSettlementIndex();
            matchingInputLastIndex = snapshotData.getMatchingInputLastIndex();

            logger.info("Account snapshot loaded: accountInputIndex={}, accountSettlementIndex={}, matchingInputLastIndex={}",
                accountInputIndex, accountSettlementIndex, matchingInputLastIndex);
        } else {
            logger.info("No valid account snapshot found, starting from beginning");
        }

        // 设置 replay 模式
        accountWorker.setReplayMode(true);

        // 构建幂等集合（简化：通过重放 CQ 自动构建）
        logger.info("Building idempotent sets via CQ replay...");

        // 重放 account_input CQ
        if (accountInputQueue != null && accountInputIndex >= 0) {
            replayAccountInputCQ(accountInputIndex + 1);
        }

        logger.info("Phase 1 completed");
    }

    /**
     * Phase 2: MatchingEngine
     * - 加载订单簿快照
     * - 设置 replay 模式
     * - 重放 matching_input CQ
     */
    private void phase2_matchingEngine() throws Exception {
        logger.info("Phase 2: MatchingEngine starting...");

        if (matchingEngineWorker == null) {
            logger.warn("MatchingEngineWorker is null, skipping Phase 2");
            return;
        }

        // 查找最新订单簿快照
        var snapshotInfo = SnapshotFileUtils.findLatestValidSnapshot(snapshotDir, "matching");

        long matchingInputIndex = -1;
        long matchingOutputLastIndex = -1;

        if (snapshotInfo.isPresent()) {
            // 加载快照到内存
            MatchingSnapshotReader reader = new MatchingSnapshotReader(matchingEngines);
            String filePath = snapshotInfo.get().getFilePath();
            var snapshotData = reader.readSnapshot(filePath);

            matchingInputIndex = snapshotData.getMatchingInputIndex();
            matchingOutputLastIndex = snapshotData.getMatchingOutputLastIndex();

            logger.info("Matching snapshot loaded: matchingInputIndex={}, matchingOutputLastIndex={}",
                matchingInputIndex, matchingOutputLastIndex);
        } else {
            logger.info("No valid matching snapshot found, starting from beginning");
        }

        // 设置 replay 模式
        matchingEngineWorker.setReplayMode(true);

        // 重放 matching_input CQ
        if (matchingInputQueue != null && matchingInputIndex >= 0) {
            replayMatchingInputCQ(matchingInputIndex + 1);
        }

        logger.info("Phase 2 completed");
    }

    /**
     * Phase 3: ClearingWorker
     * - 加载处理位置或回退
     * - 设置 replay 模式
     * - 重放 matching_output CQ
     */
    private void phase3_clearingWorker() throws Exception {
        logger.info("Phase 3: ClearingWorker starting...");

        if (clearingWorker == null) {
            logger.warn("ClearingWorker is null, skipping Phase 3");
            return;
        }

        // 加载处理位置
        ClearingPositionManager positionManager = new ClearingPositionManager(chronicleBasePath);
        long clearingPosition = positionManager.loadPosition();

        logger.info("Clearing position loaded: {}", clearingPosition);

        // 设置 replay 模式
        clearingWorker.setReplayMode(true);

        // 重放 matching_output CQ
        if (matchingOutputQueue != null && clearingPosition >= 0) {
            replayMatchingOutputCQ(clearingPosition + 1);
        }

        logger.info("Phase 3 completed");
    }

    /**
     * Phase 4: AccountWorker 阶段 2
     * - 重放 account_settlement CQ
     * - 禁用 replay 模式
     */
    private void phase4_accountWorkerPhase2() throws Exception {
        logger.info("Phase 4: AccountWorker phase 2 starting...");

        if (accountWorker == null) {
            logger.warn("AccountWorker is null, skipping Phase 4");
            return;
        }

        // 重放 account_settlement CQ（处理 SETTLEMENT 事件）
        if (accountSettlementQueue != null) {
            replayAccountSettlementCQ();
        }

        // 禁用 replay 模式
        accountWorker.setReplayMode(false);

        if (matchingEngineWorker != null) {
            matchingEngineWorker.setReplayMode(false);
        }

        if (clearingWorker != null) {
            clearingWorker.setReplayMode(false);
        }

        logger.info("Phase 4 completed");
    }

    // ==================== CQ 重放方法 ====================

    /**
     * 重放 account_input CQ
     *
     * 处理事件类型：
     * - NEW_ORDER: 执行冻结，但跳过 matching_input 写入（因为已处理）
     * - CANCEL_ORDER: 转发到 matching_input
     * - DEPOSIT: 执行入金
     * - PHASE_CHANGE: 更新阶段
     * - INSTRUMENT_INIT: 初始化证券
     * - SNAPSHOT: 跳过（快照已在恢复时加载）
     */
    private void replayAccountInputCQ(long startIndex) {
        logger.info("Replaying account_input CQ from index: {}", startIndex);

        if (accountInputQueue == null) {
            logger.warn("AccountInputQueue is null, skipping replay");
            return;
        }

        // 移动到起始位置
        accountInputQueue.moveToIndex(startIndex);

        // 构建幂等集合
        Set<Long> processedOrderIds = new HashSet<>();

        int eventCount = 0;
        int skippedCount = 0;

        while (true) {
            List<AccountInputEvent> events = accountInputQueue.readBatchForReplay();
            if (events.isEmpty()) {
                break;
            }

            for (AccountInputEvent event : events) {
                try {
                    eventCount++;
                    replayAccountInputEvent(event, processedOrderIds);
                } catch (Exception e) {
                    logger.error("Error replaying account_input event: type={}, error={}",
                        event.getEventType(), e.getMessage(), e);
                }
            }
        }

        // 更新 AccountWorker 的处理位置
        if (accountWorker != null) {
            accountWorker.setLastProcessedIndex(accountInputQueue.getCurrentIndex());
        }

        logger.info("Account_input CQ replay completed: events={}, skipped={}", eventCount, skippedCount);
    }

    /**
     * 重放单个 account_input 事件
     */
    private void replayAccountInputEvent(AccountInputEvent event, Set<Long> processedOrderIds) {
        EventType eventType = event.getEventType();

        switch (eventType) {
            case NEW_ORDER:
                replayNewOrder(event, processedOrderIds);
                break;

            case CANCEL_ORDER:
                // CANCEL_ORDER 转发到 matching_input CQ
                if (matchingInputQueue != null) {
                    matchingInputQueue.writeEvent(event);
                    logger.debug("CANCEL_ORDER forwarded to matching_input: orderId={}", event.getOrderId());
                }
                break;

            case DEPOSIT:
                replayDeposit(event);
                break;

            case PHASE_CHANGE:
                if (accountWorker != null) {
                    accountWorker.processPhaseChange(event);
                }
                break;

            case INSTRUMENT_INIT:
                if (instrumentStore != null && event.getInstrumentData() != null) {
                    instrumentStore.putInstrument(event.getInstrumentData());
                    logger.debug("Instrument initialized: symbol={}", event.getSymbol());
                }
                break;

            case SNAPSHOT:
                // SNAPSHOT 事件在恢复时跳过
                logger.debug("SNAPSHOT event skipped during replay");
                break;

            case SETTLEMENT:
                // SETTLEMENT 由 Phase 4 处理
                logger.debug("SETTLEMENT event skipped in Phase 1");
                break;

            default:
                logger.warn("Unknown event type in account_input replay: {}", eventType);
        }
    }

    /**
     * 重放 NEW_ORDER 事件
     */
    private void replayNewOrder(AccountInputEvent event, Set<Long> processedOrderIds) {
        Long orderId = event.getOrderId();
        if (orderId == null) {
            return;
        }

        // 幂等检查
        if (processedOrderIds.contains(orderId)) {
            logger.debug("NEW_ORDER already processed: orderId={}", orderId);
            return;
        }

        // 执行冻结
        if (accountWorker != null) {
            Long accountId = event.getAccountId();
            if (accountId == null) {
                logger.warn("NEW_ORDER missing accountId: orderId={}", orderId);
                return;
            }

            OrderSide side = event.getSide();
            if (side == OrderSide.BUY) {
                // 买单冻结资金
                Long frozenAmount = event.getFrozenAmount();
                if (frozenAmount != null && frozenAmount > 0) {
                    try {
                        accountStore.freeze(accountId, frozenAmount);
                        logger.debug("Buy order freeze replayed: orderId={}, frozenAmount={}",
                            orderId, frozenAmount);
                    } catch (Exception e) {
                        logger.warn("Freeze failed during replay: orderId={}, error={}",
                            orderId, e.getMessage());
                    }
                }
            } else {
                // 卖单冻结持仓
                String symbol = event.getSymbol();
                Long quantity = event.getQuantity();
                if (symbol != null && quantity != null && quantity > 0) {
                    try {
                        positionStore.freezePosition(accountId, symbol, quantity);
                        logger.debug("Sell order freeze replayed: orderId={}, symbol={}, qty={}",
                            orderId, symbol, quantity);
                    } catch (Exception e) {
                        logger.warn("Freeze position failed during replay: orderId={}, error={}",
                            orderId, e.getMessage());
                    }
                }
            }
        }

        // 创建订单并存入 store
        if (orderStore != null && orderStore.getOrder(orderId) == null) {
            Order order = createOrderFromEvent(event);
            if (order != null) {
                orderStore.addOrder(order);
            }
        }

        // 加入幂等集合
        processedOrderIds.add(orderId);
    }

    /**
     * 从事件创建订单
     */
    private Order createOrderFromEvent(AccountInputEvent event) {
        if (event.getOrderId() == null || event.getSymbol() == null) {
            return null;
        }

        int symbolCode;
        try {
            symbolCode = Integer.parseInt(event.getSymbol());
        } catch (NumberFormatException e) {
            symbolCode = event.getSymbol().hashCode();
        }

        Order order = new Order(
            event.getOrderId(),
            event.getSymbol(),
            symbolCode,
            event.getSide(),
            event.getOrderType(),
            event.getPrice() != null ? event.getPrice() : 0L,
            event.getQuantity() != null ? event.getQuantity() : 0L,
            event.getAccountId() != null ? event.getAccountId() : 0L,
            event.getUserId() != null ? event.getUserId() : 0L
        );

        if (event.getFrozenAmount() != null) {
            order.setFrozenAmount(event.getFrozenAmount());
        }
        if (event.getMaxPrice() != null) {
            order.setMaxPrice(event.getMaxPrice());
        }
        if (event.getMinPrice() != null) {
            order.setMinPrice(event.getMinPrice());
        }
        if (event.getIdempotencyKey() != null) {
            order.setIdempotencyKey(event.getIdempotencyKey());
        }

        return order;
    }

    /**
     * 重放 DEPOSIT 事件
     */
    private void replayDeposit(AccountInputEvent event) {
        Long accountId = event.getAccountId();
        Long amount = event.getDepositAmount();

        if (accountId == null || amount == null) {
            logger.warn("DEPOSIT event missing required fields: accountId={}, amount={}",
                accountId, amount);
            return;
        }

        try {
            accountStore.deposit(accountId, amount);
            logger.debug("DEPOSIT replayed: accountId={}, amount={}", accountId, amount);
        } catch (Exception e) {
            logger.error("DEPOSIT replay failed: accountId={}, error={}", accountId, e.getMessage());
        }
    }

    /**
     * 重放 matching_input CQ
     *
     * 处理事件类型：
     * - NEW_ORDER: 添加到订单簿，跳过已成交 trade
     * - CANCEL_ORDER: 处理撤单
     * - SNAPSHOT: 跳过
     * - INSTRUMENT_INIT: 初始化证券
     */
    private void replayMatchingInputCQ(long startIndex) {
        logger.info("Replaying matching_input CQ from index: {}", startIndex);

        if (matchingInputQueue == null) {
            logger.warn("MatchingInputQueue is null, skipping replay");
            return;
        }

        // 移动到起始位置
        matchingInputQueue.moveToIndex(startIndex);

        // 幂等集合
        Set<Long> processedOrderIds = new HashSet<>();
        Set<Long> processedCancelIds = new HashSet<>();

        int eventCount = 0;

        while (true) {
            List<AccountInputEvent> events = matchingInputQueue.readBatchForReplay();
            if (events.isEmpty()) {
                break;
            }

            for (AccountInputEvent event : events) {
                try {
                    eventCount++;
                    replayMatchingInputEvent(event, processedOrderIds, processedCancelIds);
                } catch (Exception e) {
                    logger.error("Error replaying matching_input event: type={}, error={}",
                        event.getEventType(), e.getMessage(), e);
                }
            }
        }

        // 更新 MatchingEngineWorker 的处理位置
        if (matchingEngineWorker != null) {
            matchingEngineWorker.setLastProcessedIndex(matchingInputQueue.getCurrentIndex());
        }

        logger.info("Matching_input CQ replay completed: events={}", eventCount);
    }

    /**
     * 重放单个 matching_input 事件
     */
    private void replayMatchingInputEvent(AccountInputEvent event,
                                           Set<Long> processedOrderIds,
                                           Set<Long> processedCancelIds) {
        EventType eventType = event.getEventType();

        switch (eventType) {
            case NEW_ORDER:
                replayMatchingNewOrder(event, processedOrderIds);
                break;

            case CANCEL_ORDER:
                replayMatchingCancelOrder(event, processedCancelIds);
                break;

            case SNAPSHOT:
                logger.debug("SNAPSHOT event skipped during matching replay");
                break;

            case INSTRUMENT_INIT:
                if (instrumentStore != null && event.getInstrumentData() != null) {
                    instrumentStore.putInstrument(event.getInstrumentData());
                }
                break;

            default:
                logger.warn("Unknown event type in matching_input replay: {}", eventType);
        }
    }

    /**
     * 重放撮合引擎的 NEW_ORDER 事件
     */
    private void replayMatchingNewOrder(AccountInputEvent event, Set<Long> processedOrderIds) {
        Long orderId = event.getOrderId();
        if (orderId == null) {
            return;
        }

        // 幂等检查
        if (processedOrderIds.contains(orderId)) {
            logger.debug("NEW_ORDER already processed in matching: orderId={}", orderId);
            return;
        }

        // 获取订单
        Order order = orderStore.getOrder(orderId);
        if (order == null) {
            logger.warn("Order not found in store during matching replay: orderId={}", orderId);
            return;
        }

        // 获取或创建撮合引擎
        String symbol = event.getSymbol();
        if (symbol == null) {
            logger.warn("NEW_ORDER missing symbol: orderId={}", orderId);
            return;
        }

        MatchingEngine engine = matchingEngines.get(symbol);
        if (engine == null) {
            engine = new MatchingEngine(symbol);
            matchingEngines.put(symbol, engine);
        }

        // 在 replay 模式下，只添加到订单簿，不执行撮合（成交已在 Phase 3 处理）
        if (order.getStatus() == com.hft.trading.model.OrderStatus.NEW ||
            order.getStatus() == com.hft.trading.model.OrderStatus.PENDING) {
            engine.getOrderBook().addOrder(order);
            logger.debug("Order added to orderbook during replay: orderId={}, symbol={}",
                orderId, symbol);
        }

        processedOrderIds.add(orderId);
    }

    /**
     * 重放撮合引擎的 CANCEL_ORDER 事件
     */
    private void replayMatchingCancelOrder(AccountInputEvent event, Set<Long> processedCancelIds) {
        Long orderId = event.getOrderId();
        if (orderId == null) {
            return;
        }

        // 幂等检查
        if (processedCancelIds.contains(orderId)) {
            logger.debug("CANCEL_ORDER already processed in matching: orderId={}", orderId);
            return;
        }

        // 获取订单
        Order order = orderStore.getOrder(orderId);
        if (order == null) {
            logger.debug("Order not found for cancel during replay: orderId={}", orderId);
            processedCancelIds.add(orderId);
            return;
        }

        // 从订单簿移除
        String symbol = order.getSymbol();
        MatchingEngine engine = matchingEngines.get(symbol);
        if (engine != null) {
            boolean removed = engine.getOrderBook().removeOrder(orderId);
            if (removed) {
                logger.debug("Order removed from orderbook during replay: orderId={}", orderId);
            }
        }

        // 更新订单状态
        if (order.getStatus() != com.hft.trading.model.OrderStatus.FILLED) {
            order.updateStatus(com.hft.trading.model.OrderStatus.CANCELLED);
        }

        processedCancelIds.add(orderId);
    }

    /**
     * 重放 matching_output CQ
     *
     * 处理事件类型：
     * - TRADE: 生成 SETTLEMENT（如果未处理）
     * - ORDER_UPDATE: 更新订单状态
     * - CANCEL_SUCCESS: 生成 UNFREEZE（如果未处理）
     */
    private void replayMatchingOutputCQ(long startIndex) {
        logger.info("Replaying matching_output CQ from index: {}", startIndex);

        if (matchingOutputQueue == null) {
            logger.warn("MatchingOutputQueue is null, skipping replay");
            return;
        }

        // 移动到起始位置
        matchingOutputQueue.moveToIndex(startIndex);

        // 幂等集合
        Set<String> processedTradeIds = new HashSet<>();
        Set<Long> processedCancelIds = new HashSet<>();

        int eventCount = 0;

        while (true) {
            List<MatchingOutputQueue.MatchingOutputEvent> events = matchingOutputQueue.readBatchForReplay();
            if (events.isEmpty()) {
                break;
            }

            for (MatchingOutputQueue.MatchingOutputEvent event : events) {
                try {
                    eventCount++;
                    replayMatchingOutputEvent(event, processedTradeIds, processedCancelIds);
                } catch (Exception e) {
                    logger.error("Error replaying matching_output event: type={}, error={}",
                        event.getEventType(), e.getMessage(), e);
                }
            }
        }

        // 更新 ClearingWorker 的处理位置
        if (clearingWorker != null) {
            clearingWorker.setLastProcessedIndex(matchingOutputQueue.getCurrentIndex());
        }

        logger.info("Matching_output CQ replay completed: events={}", eventCount);
    }

    /**
     * 重放单个 matching_output 事件
     */
    private void replayMatchingOutputEvent(MatchingOutputQueue.MatchingOutputEvent event,
                                            Set<String> processedTradeIds,
                                            Set<Long> processedCancelIds) {
        EventType eventType = event.getEventType();

        switch (eventType) {
            case TRADE:
                replayTradeEvent(event.asTradeEvent(), processedTradeIds);
                break;

            case ORDER_UPDATE:
                replayOrderUpdate(event.asOrderUpdate());
                break;

            case CANCEL_SUCCESS:
                replayCancelSuccess(event.asCancelSuccessEvent(), processedCancelIds);
                break;

            case CANCEL_REJECT:
                logger.debug("CANCEL_REJECT skipped during replay");
                break;

            default:
                logger.warn("Unknown event type in matching_output replay: {}", eventType);
        }
    }

    /**
     * 重放 TRADE 事件
     */
    private void replayTradeEvent(TradeEvent tradeEvent, Set<String> processedTradeIds) {
        if (tradeEvent == null) {
            return;
        }

        String tradeId = tradeEvent.getTradeId();

        // 幂等检查
        if (processedTradeIds.contains(tradeId)) {
            logger.debug("TRADE already processed: tradeId={}", tradeId);
            return;
        }

        // 更新订单成交数量
        long buyOrderId = tradeEvent.getBuyOrderId();
        long sellOrderId = tradeEvent.getSellOrderId();
        long quantity = tradeEvent.getQuantity();

        Order buyOrder = orderStore.getOrder(buyOrderId);
        if (buyOrder != null) {
            buyOrder.updateFilledQuantity(quantity);
            logger.debug("Buy order filled quantity updated: orderId={}, filledQty={}",
                buyOrderId, buyOrder.getFilledQuantity());
        }

        Order sellOrder = orderStore.getOrder(sellOrderId);
        if (sellOrder != null) {
            sellOrder.updateFilledQuantity(quantity);
            logger.debug("Sell order filled quantity updated: orderId={}, filledQty={}",
                sellOrderId, sellOrder.getFilledQuantity());
        }

        // 生成 SETTLEMENT 事件到 account_settlement CQ
        generateSettlementEvents(tradeEvent);

        processedTradeIds.add(tradeId);
        logger.debug("TRADE replayed: tradeId={}", tradeId);
    }

    /**
     * 生成 SETTLEMENT 事件
     */
    private void generateSettlementEvents(TradeEvent tradeEvent) {
        if (accountSettlementQueue == null) {
            return;
        }

        long price = tradeEvent.getPrice();
        long quantity = tradeEvent.getQuantity();
        long amount = price * quantity;

        // 计算佣金（简化：使用固定佣金率）
        int commissionRate = tradeEvent.getCommissionRate();
        long commission = commissionRate > 0 ? (amount * commissionRate) / 10000 : 0;

        // 买方 SETTLEMENT
        Order buyOrder = orderStore.getOrder(tradeEvent.getBuyOrderId());
        if (buyOrder != null) {
            long frozenAmountUsed = buyOrder.getFrozenAmount();
            SettlementEvent buyerSettlement = SettlementEvent.forBuyer(
                tradeEvent.getTradeId(),
                tradeEvent.getBuyOrderId(),
                tradeEvent.getBuyAccountId(),
                buyOrder.getUserId(),
                tradeEvent.getSymbol(),
                price,
                quantity,
                frozenAmountUsed,
                commission
            );
            accountSettlementQueue.writeSettlementEvent(buyerSettlement);
            logger.debug("Buyer SETTLEMENT generated: tradeId={}, orderId={}",
                tradeEvent.getTradeId(), tradeEvent.getBuyOrderId());
        }

        // 卖方 SETTLEMENT
        Order sellOrder = orderStore.getOrder(tradeEvent.getSellOrderId());
        if (sellOrder != null) {
            SettlementEvent sellerSettlement = SettlementEvent.forSeller(
                tradeEvent.getTradeId(),
                tradeEvent.getSellOrderId(),
                tradeEvent.getSellAccountId(),
                sellOrder.getUserId(),
                tradeEvent.getSymbol(),
                price,
                quantity,
                commission
            );
            accountSettlementQueue.writeSettlementEvent(sellerSettlement);
            logger.debug("Seller SETTLEMENT generated: tradeId={}, orderId={}",
                tradeEvent.getTradeId(), tradeEvent.getSellOrderId());
        }
    }

    /**
     * 重放 ORDER_UPDATE 事件
     */
    private void replayOrderUpdate(MatchingOutputQueue.OrderUpdateData data) {
        if (data == null) {
            return;
        }

        Long orderId = data.getOrderId();
        if (orderId == null) {
            return;
        }

        Order order = orderStore.getOrder(orderId);
        if (order == null) {
            logger.debug("Order not found for ORDER_UPDATE: orderId={}", orderId);
            return;
        }

        // 更新订单状态
        if (order.getStatus() == com.hft.trading.model.OrderStatus.PENDING) {
            try {
                order.updateStatus(com.hft.trading.model.OrderStatus.NEW);
                logger.debug("Order status updated to NEW: orderId={}", orderId);
            } catch (Exception e) {
                logger.debug("Order status update skipped: orderId={}, error={}", orderId, e.getMessage());
            }
        }
    }

    /**
     * 重放 CANCEL_SUCCESS 事件
     */
    private void replayCancelSuccess(CancelSuccessEvent cancelEvent, Set<Long> processedCancelIds) {
        if (cancelEvent == null) {
            return;
        }

        Long orderId = cancelEvent.getOrderId();
        if (orderId == null) {
            return;
        }

        // 幂等检查
        if (processedCancelIds.contains(orderId)) {
            logger.debug("CANCEL_SUCCESS already processed: orderId={}", orderId);
            return;
        }

        // 生成 UNFREEZE 事件
        if (accountSettlementQueue != null) {
            accountSettlementQueue.writeUnfreezeEvent(
                orderId,
                cancelEvent.getAccountId(),
                cancelEvent.getUserId(),
                cancelEvent.getSymbol(),
                cancelEvent.getUnfreezeAmount(),
                cancelEvent.getUnfreezeQty(),
                cancelEvent.getSide() == OrderSide.BUY ? EventType.FREEZE : EventType.UNFREEZE
            );
            logger.debug("UNFREEZE event generated: orderId={}, amount={}, qty={}",
                orderId, cancelEvent.getUnfreezeAmount(), cancelEvent.getUnfreezeQty());
        }

        processedCancelIds.add(orderId);
    }

    /**
     * 重放 account_settlement CQ
     *
     * 处理事件类型：
     * - SETTLEMENT: 执行结算转账
     * - UNFREEZE: 执行解冻
     */
    private void replayAccountSettlementCQ() {
        logger.info("Replaying account_settlement CQ");

        if (accountSettlementQueue == null) {
            logger.warn("AccountSettlementQueue is null, skipping replay");
            return;
        }

        // 从头开始读取（Phase 4 从头处理所有结算事件）
        accountSettlementQueue.moveToIndex(0);

        // 幂等集合
        Set<String> processedSettlementIds = new HashSet<>();
        Set<Long> processedUnfreezeIds = new HashSet<>();

        int eventCount = 0;

        while (true) {
            List<AccountSettlementQueue.SettlementQueueEvent> events = accountSettlementQueue.readBatchForReplay();
            if (events.isEmpty()) {
                break;
            }

            for (AccountSettlementQueue.SettlementQueueEvent event : events) {
                try {
                    eventCount++;
                    replayAccountSettlementEvent(event, processedSettlementIds, processedUnfreezeIds);
                } catch (Exception e) {
                    logger.error("Error replaying account_settlement event: type={}, error={}",
                        event.getEventType(), e.getMessage(), e);
                }
            }
        }

        logger.info("Account_settlement CQ replay completed: events={}", eventCount);
    }

    /**
     * 重放单个 account_settlement 事件
     */
    private void replayAccountSettlementEvent(AccountSettlementQueue.SettlementQueueEvent event,
                                               Set<String> processedSettlementIds,
                                               Set<Long> processedUnfreezeIds) {
        EventType eventType = event.getEventType();

        switch (eventType) {
            case SETTLEMENT:
                replaySettlement(event.asSettlementEvent(), processedSettlementIds);
                break;

            case UNFREEZE:
                replayUnfreeze(event.asUnfreezeData(), processedUnfreezeIds);
                break;

            default:
                logger.warn("Unknown event type in account_settlement replay: {}", eventType);
        }
    }

    /**
     * 重放 SETTLEMENT 事件
     */
    private void replaySettlement(SettlementEvent settlementEvent, Set<String> processedSettlementIds) {
        if (settlementEvent == null) {
            return;
        }

        String settlementId = settlementEvent.getSettlementId();

        // 幂等检查
        if (processedSettlementIds.contains(settlementId)) {
            logger.debug("SETTLEMENT already processed: settlementId={}", settlementId);
            return;
        }

        long accountId = settlementEvent.getAccountId();

        if (settlementEvent.isBuyer()) {
            // 买方结算
            long frozenAmountUsed = settlementEvent.getFrozenAmountUsed();
            long actualAmount = settlementEvent.getActualAmount();
            long commission = settlementEvent.getCommission();
            long totalDeduction = actualAmount + commission;
            long refund = frozenAmountUsed - totalDeduction;

            try {
                accountStore.settleBuy(accountId, frozenAmountUsed, totalDeduction, refund);
                positionStore.settleBuyPosition(accountId, settlementEvent.getSymbol(),
                    settlementEvent.getQuantity(), settlementEvent.getPrice());
                logger.debug("Buyer settlement replayed: accountId={}, settlementId={}",
                    accountId, settlementId);
            } catch (Exception e) {
                logger.error("Buyer settlement replay failed: accountId={}, error={}",
                    accountId, e.getMessage());
            }
        } else {
            // 卖方结算
            long netIncome = settlementEvent.getActualAmount() - settlementEvent.getCommission();

            try {
                positionStore.settleSellPosition(accountId, settlementEvent.getSymbol(),
                    settlementEvent.getQuantity());
                accountStore.settleSell(accountId, netIncome);
                logger.debug("Seller settlement replayed: accountId={}, settlementId={}",
                    accountId, settlementId);
            } catch (Exception e) {
                logger.error("Seller settlement replay failed: accountId={}, error={}",
                    accountId, e.getMessage());
            }
        }

        processedSettlementIds.add(settlementId);
    }

    /**
     * 重放 UNFREEZE 事件
     */
    private void replayUnfreeze(AccountSettlementQueue.UnfreezeData data, Set<Long> processedUnfreezeIds) {
        if (data == null) {
            return;
        }

        Long orderId = data.getOrderId();
        if (orderId == null) {
            return;
        }

        // 幂等检查
        if (processedUnfreezeIds.contains(orderId)) {
            logger.debug("UNFREEZE already processed: orderId={}", orderId);
            return;
        }

        Long accountId = data.getAccountId();
        Long unfreezeAmount = data.getUnfreezeAmount();
        Long unfreezeQty = data.getUnfreezeQty();
        String symbol = data.getSymbol();

        try {
            // 解冻资金（买单）
            if (unfreezeAmount != null && unfreezeAmount > 0) {
                accountStore.unfreeze(accountId, unfreezeAmount);
                logger.debug("Unfreeze amount replayed: accountId={}, amount={}", accountId, unfreezeAmount);
            }

            // 解冻持仓（卖单）
            if (unfreezeQty != null && unfreezeQty > 0 && symbol != null) {
                positionStore.unfreezePosition(accountId, symbol, unfreezeQty);
                logger.debug("Unfreeze position replayed: accountId={}, symbol={}, qty={}",
                    accountId, symbol, unfreezeQty);
            }

            processedUnfreezeIds.add(orderId);
        } catch (Exception e) {
            logger.error("UNFREEZE replay failed: orderId={}, error={}", orderId, e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查目录是否存在 .dat 数据文件（排除 position 文件）
     */
    private boolean hasDataFiles(Path dir) {
        if (!Files.exists(dir)) {
            return false;
        }

        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".dat") && !name.equals("clearing_position.dat");
            });
        } catch (IOException e) {
            logger.warn("Failed to list files in directory: {}", dir, e);
            return false;
        }
    }

    /**
     * 检查目录是否存在 .cq4 文件
     */
    private boolean hasCqFiles(Path dir) {
        if (!Files.exists(dir)) {
            return false;
        }

        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".cq4"));
        } catch (IOException e) {
            logger.warn("Failed to list files in directory: {}", dir, e);
            return false;
        }
    }
}
