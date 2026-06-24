package com.hft.trading.lifecycle;

import com.hft.trading.agent.ConsistencyAuditAgent;
import com.hft.trading.agent.OperationsDiagnosticAgent;
import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.TradingPhase;
import com.hft.trading.service.SinaInstrumentService;
import com.hft.trading.queue.*;
import com.hft.trading.recovery.RecoveryManager;
import com.hft.trading.snapshot.SnapshotWorker;
import com.hft.trading.store.*;
import com.hft.trading.websocket.MarketDataPublisher;
import com.hft.trading.websocket.WebSocketPushService;
import com.hft.trading.websocket.WebSocketServer;
import com.hft.trading.worker.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LifecycleManager - 系统生命周期管理器
 *
 * 根据 V12 技术方案：
 * - 系统状态机：STARTING → RECOVERING → RUNNING → SHUTTING_DOWN
 * - 四阶段恢复：AccountWorker → MatchingEngine → ClearingWorker → AccountWorker(第二阶段)
 * - Worker 生命周期管理
 */
@Component
public class LifecycleManager implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(LifecycleManager.class);

    // 系统状态
    private volatile SystemStatus systemStatus = SystemStatus.STARTING;

    // SmartLifecycle running 标志
    private volatile boolean running = false;

    // 存储
    private final InMemoryAccountStore accountStore;
    private final InMemoryPositionStore positionStore;
    private final InMemoryOrderStore orderStore;
    private final InMemoryInstrumentStore instrumentStore;

    // 队列
    private final QueueManager queueManager;

    // WebSocket（可选）
    private final WebSocketServer webSocketServer;
    private final WebSocketPushService webSocketPushService;
    private final MarketDataPublisher marketDataPublisher;
    private final SnapshotWorker snapshotWorker;

    // Agents
    private final ConsistencyAuditAgent consistencyAuditAgent;
    private final OperationsDiagnosticAgent operationsDiagnosticAgent;

    // Workers
    private AccountWorker accountWorker;
    private MatchingEngineWorker matchingEngineWorker;
    private ClearingWorker clearingWorker;
    private AsyncPersistenceWorker asyncPersistenceWorker;

    // RecoveryManager
    private RecoveryManager recoveryManager;

    // DataSource
    private DataSource dataSource;

    // 配置
    private final String chronicleBasePath;

    /**
     * 系统状态枚举
     */
    public enum SystemStatus {
        STARTING,        // 启动中
        RECOVERING,      // 恢复中
        RUNNING,         // 正常运行
        SHUTTING_DOWN,   // 关闭中
        DEGRADED,        // 降级模式
        SNAPSHOTTING     // 快照中
    }

    public LifecycleManager(InMemoryAccountStore accountStore,
                            InMemoryPositionStore positionStore,
                            InMemoryOrderStore orderStore,
                            InMemoryInstrumentStore instrumentStore,
                            QueueManager queueManager,
                            WebSocketServer webSocketServer,
                            WebSocketPushService webSocketPushService,
                            MarketDataPublisher marketDataPublisher,
                            SnapshotWorker snapshotWorker,
                            ConsistencyAuditAgent consistencyAuditAgent,
                            OperationsDiagnosticAgent operationsDiagnosticAgent) {
        this.accountStore = accountStore;
        this.positionStore = positionStore;
        this.orderStore = orderStore;
        this.instrumentStore = instrumentStore;
        this.queueManager = queueManager;
        this.webSocketServer = webSocketServer;
        this.webSocketPushService = webSocketPushService;
        this.marketDataPublisher = marketDataPublisher;
        this.snapshotWorker = snapshotWorker;
        this.consistencyAuditAgent = consistencyAuditAgent;
        this.operationsDiagnosticAgent = operationsDiagnosticAgent;
        this.chronicleBasePath = "./data/chronicle/";
    }

    /**
     * 设置数据源（用于持久化）
     */
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 系统启动入口 - SmartLifecycle.start()
     */
    @Override
    public void start() {
        if (running) {
            logger.warn("LifecycleManager already running");
            return;
        }

        logger.info("=== HFT Trading System Starting ===");
        logger.info("System status: STARTING");

        try {
            // 1. 初始化 Workers
            initializeWorkers();

            // 2. SOD: 从新浪初始化品种数据（首次启动且无恢复时）
            if (!needsRecovery()) {
                initializeInstruments();
            }

            // 3. 检查是否需要恢复
            if (needsRecovery()) {
                systemStatus = SystemStatus.RECOVERING;
                logger.info("System status: RECOVERING");
                performRecoveryWithTimeout();
            }

            // 3. 启动所有 Workers
            startAllWorkers();

            // 4. 等待所有 Workers 就绪
            waitForWorkersReady();

            // 5. 系统就绪
            systemStatus = SystemStatus.RUNNING;
            running = true;
            logger.info("System status: RUNNING");
            logger.info("=== HFT Trading System Started Successfully ===");

        } catch (Exception e) {
            logger.error("System startup failed: {}", e.getMessage(), e);
            systemStatus = SystemStatus.DEGRADED;
            running = true; // 仍标记为 running，让容器启动
        }
    }

    /**
     * 系统关闭 - 优雅停机
     *
     * 流程：
     * 1. 设置 SHUTTING_DOWN 状态，立即拒绝新请求
     * 2. 等待 15 秒让队列清空（处理已接收的请求）
     * 3. 触发最终快照（保证 RPO=0）
     * 4. 勒令停止所有 Workers
     * 5. 关闭队列
     */
    public void shutdown() {
        logger.info("=== HFT Trading System Shutting Down ===");

        // 1. 立即设置状态，拒绝新请求
        systemStatus = SystemStatus.SHUTTING_DOWN;
        logger.info("System status: SHUTTING_DOWN - rejecting new requests");

        try {
            // 2. 等待队列清空（最多 15 秒）
            logger.info("Step 1: Waiting for queues to drain (max 15s)...");
            long drainTimeoutMs = 15_000;
            waitForQueuesToDrain(drainTimeoutMs);

            // 3. 触发最终快照
            logger.info("Step 2: Triggering final snapshot...");
            triggerFinalSnapshot();

            // 4. 勒令停止所有 Workers
            logger.info("Step 3: Stopping all workers...");
            stopAllWorkers();

            // 5. 关闭队列
            logger.info("Step 4: Closing queues...");
            queueManager.closeAll();

            logger.info("=== HFT Trading System Shutdown Complete ===");

        } catch (Exception e) {
            logger.error("Error during shutdown: {}", e.getMessage(), e);
        }
    }

    /**
     * 初始化 Workers
     */
    private void initializeWorkers() {
        logger.info("Initializing workers...");

        // AccountWorker
        accountWorker = new AccountWorker(
            accountStore,
            positionStore,
            orderStore,
            instrumentStore,
            null, // FutureManager - 简化处理
            queueManager.getAccountInputQueue(),
            queueManager.getMatchingInputQueue(),
            queueManager.getAccountSettlementQueue(),
            webSocketPushService // WebSocket推送服务
        );

        // MatchingEngineWorker
        matchingEngineWorker = new MatchingEngineWorker(
            orderStore,
            instrumentStore,
            queueManager.getMatchingInputQueue(),
            queueManager.getMatchingOutputQueue(),
            webSocketPushService // WebSocket推送服务
        );

        // ClearingWorker
        clearingWorker = new ClearingWorker(
            orderStore,
            instrumentStore,
            queueManager.getMatchingOutputQueue(),
            queueManager.getAccountSettlementQueue(),
            webSocketPushService // WebSocket推送服务
        );

        // AsyncPersistenceWorker (if DataSource is configured)
        if (dataSource != null) {
            asyncPersistenceWorker = new AsyncPersistenceWorker(
                orderStore,
                queueManager.getMatchingOutputQueue(),
                dataSource
            );
        }

        // Create RecoveryManager
        recoveryManager = new RecoveryManager(
            "./data/snapshots/",
            chronicleBasePath,
            accountStore,
            positionStore,
            orderStore,
            instrumentStore,
            matchingEngineWorker.getMatchingEngines(),
            queueManager.getAccountInputQueue(),
            queueManager.getMatchingInputQueue(),
            queueManager.getMatchingOutputQueue(),
            queueManager.getAccountSettlementQueue(),
            accountWorker,
            matchingEngineWorker,
            clearingWorker
        );

        // Set matching engines for MarketDataPublisher
        marketDataPublisher.setMatchingEngines(matchingEngineWorker.getMatchingEngines());

        // Set workers for OperationsDiagnosticAgent
        operationsDiagnosticAgent.setWorkers(accountWorker, matchingEngineWorker, clearingWorker);

        logger.info("Workers initialized: AccountWorker, MatchingEngineWorker, ClearingWorker, RecoveryManager" +
            (asyncPersistenceWorker != null ? ", AsyncPersistenceWorker" : "") +
            (marketDataPublisher != null ? ", MarketDataPublisher" : ""));
    }

    /**
     * SOD: 从新浪初始化品种数据
     *
     * 流程：
     * 1. 定义需要初始化的品种代码列表
     * 2. 从新浪拉取昨收价
     * 3. 生成 INSTRUMENT_INIT 事件写入 AccountInput CQ
     * 4. AccountWorker 处理后将品种注册到 InMemoryInstrumentStore
     */
    private void initializeInstruments() {
        logger.info("SOD: Initializing instruments from Sina...");

        try {
            // A股常见品种代码
            List<String> symbols = new ArrayList<>();
            // 沪市
            symbols.add("600000"); symbols.add("600519"); symbols.add("600036");
            symbols.add("601318"); symbols.add("600900"); symbols.add("601012");
            symbols.add("600887"); symbols.add("600276"); symbols.add("600585");
            symbols.add("601166"); symbols.add("600030"); symbols.add("600104");
            symbols.add("600690"); symbols.add("600809"); symbols.add("600028");
            // 深市
            symbols.add("000001"); symbols.add("000858"); symbols.add("000333");
            symbols.add("000651"); symbols.add("000002"); symbols.add("000725");
            symbols.add("000568"); symbols.add("000063"); symbols.add("000100");
            symbols.add("000538"); symbols.add("000625"); symbols.add("000776");
            // 创业板
            symbols.add("300750"); symbols.add("300059"); symbols.add("300124");
            symbols.add("300274"); symbols.add("300015"); symbols.add("300413");
            symbols.add("300433"); symbols.add("300782");

            SinaInstrumentService sinaService = new SinaInstrumentService();
            int count = sinaService.initializeInstruments(symbols, queueManager.getAccountInputQueue());

            logger.info("SOD: Instrument initialization complete: {} instruments initialized from Sina", count);

        } catch (Exception e) {
            logger.warn("SOD: Failed to initialize instruments from Sina: {}", e.getMessage());
            logger.warn("SOD: Instruments can be added later via admin API");
        }
    }

    /**
     * 检查是否需要恢复
     */
    private boolean needsRecovery() {
        return recoveryManager.needsRecovery();
    }

    /**
     * 执行四阶段恢复
     */
    private void performRecovery() {
        logger.info("Starting 4-phase recovery...");
        try {
            recoveryManager.performRecovery();
            logger.info("4-phase recovery completed");
        } catch (Exception e) {
            systemStatus = SystemStatus.DEGRADED;
            logger.error("Recovery failed, system in DEGRADED mode: {}", e.getMessage(), e);
        }
    }

    /**
     * 带超时的恢复执行
     */
    private void performRecoveryWithTimeout() {
        long timeoutMs = 60_000; // 60 秒超时
        logger.info("Starting recovery with {}ms timeout", timeoutMs);

        CompletableFuture<Void> recoveryFuture = CompletableFuture.runAsync(() -> {
            try {
                recoveryManager.performRecovery();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            recoveryFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            logger.info("Recovery completed successfully");
        } catch (TimeoutException e) {
            logger.error("Recovery timeout after {}ms", timeoutMs);
            systemStatus = SystemStatus.DEGRADED;
            throw new RuntimeException("Recovery timeout", e);
        } catch (Exception e) {
            logger.error("Recovery failed: {}", e.getMessage(), e);
            systemStatus = SystemStatus.DEGRADED;
            throw new RuntimeException("Recovery failed", e);
        }
    }

    /**
     * 启动所有 Workers
     */
    private void startAllWorkers() {
        logger.info("Starting all workers...");

        // 启动 WebSocketServer
        webSocketServer.start();
        sleep(100);

        // 按顺序启动：先 AccountWorker，再 MatchingEngine，最后 ClearingWorker
        accountWorker.start();
        sleep(100); // 等待启动

        matchingEngineWorker.start();
        sleep(100);

        clearingWorker.start();
        sleep(100);

        // 启动 AsyncPersistenceWorker（在 ClearingWorker 之后）
        if (asyncPersistenceWorker != null) {
            asyncPersistenceWorker.start();
            sleep(100);
        }

        // 启动 MarketDataPublisher（在所有 Workers 之后）
        marketDataPublisher.start();
        sleep(100);

        // 启动 SnapshotWorker
        snapshotWorker.start();
        sleep(100);

        logger.info("All workers started");
    }

    /**
     * 等待所有 Workers 就绪
     */
    private void waitForWorkersReady() {
        long timeoutMs = 60_000; // 60 秒超时
        long startTime = System.currentTimeMillis();
        long checkIntervalMs = 100;

        logger.info("Waiting for all workers to be ready...");

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            boolean accountReady = accountWorker != null && accountWorker.isReady();
            boolean matchingReady = matchingEngineWorker != null && matchingEngineWorker.isReady();
            boolean clearingReady = clearingWorker != null && clearingWorker.isReady();
            boolean persistenceReady = asyncPersistenceWorker == null || asyncPersistenceWorker.isReady();

            if (accountReady && matchingReady && clearingReady && persistenceReady) {
                logger.info("All workers ready after {}ms", System.currentTimeMillis() - startTime);
                return;
            }

            sleep(checkIntervalMs);
        }

        // 超时处理
        logger.error("Workers not ready after {}ms", timeoutMs);
        logger.error("Ready status: AccountWorker={}, MatchingEngineWorker={}, ClearingWorker={}, AsyncPersistenceWorker={}",
            accountWorker != null && accountWorker.isReady(),
            matchingEngineWorker != null && matchingEngineWorker.isReady(),
            clearingWorker != null && clearingWorker.isReady(),
            asyncPersistenceWorker != null && asyncPersistenceWorker.isReady());

        systemStatus = SystemStatus.DEGRADED;
        throw new RuntimeException("Workers startup timeout");
    }

    /**
     * 停止所有 Workers
     */
    private void stopAllWorkers() {
        logger.info("Stopping all workers...");

        // 先停止 SnapshotWorker
        snapshotWorker.stop();

        // 停止 MarketDataPublisher
        marketDataPublisher.stop();

        // 停止 AsyncPersistenceWorker（在 ClearingWorker 之前）
        if (asyncPersistenceWorker != null) {
            asyncPersistenceWorker.stop();
        }

        if (clearingWorker != null) {
            clearingWorker.stop();
        }

        if (matchingEngineWorker != null) {
            matchingEngineWorker.stop();
        }

        if (accountWorker != null) {
            accountWorker.stop();
        }

        // 停止 WebSocketServer
        webSocketServer.stop();

        logger.info("All workers stopped");
    }

    /**
     * 等待队列清空
     */
    private void waitForQueuesToDrain(long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                QueueManager.QueueStats stats = queueManager.getStats();
                // 检查是否有待处理事件
                // lastIndex 为 -1 表示队列为空
                long lastIndex = stats.getAccountInputLastIndex();
                long currentIndex = stats.getAccountInputCurrentIndex();
                if (lastIndex < 0 || lastIndex <= currentIndex) {
                    logger.info("Queues drained (lastIndex={}, currentIndex={})", lastIndex, currentIndex);
                    return;
                }
            } catch (Exception e) {
                logger.warn("Error checking queue stats: {}", e.getMessage());
            }
            sleep(100);
        }
        logger.warn("Timeout waiting for queues to drain");
    }

    /**
     * 触发最终快照
     */
    private void triggerFinalSnapshot() {
        logger.info("Triggering final snapshot...");
        // Write SNAPSHOT events to trigger snapshot
        try {
            AccountInputQueue inputQueue = queueManager.getAccountInputQueue();
            long index = inputQueue.getLastIndex();
            AccountInputEvent snapshotEvent = AccountInputEvent.snapshot(index);
            inputQueue.writeEvent(snapshotEvent);
            logger.info("Final snapshot triggered at index: {}", index);
        } catch (Exception e) {
            logger.error("Failed to trigger final snapshot: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取系统状态
     */
    public SystemStatus getSystemStatus() {
        return systemStatus;
    }

    /**
     * 检查系统是否可接受请求
     */
    public boolean isAcceptingRequests() {
        return systemStatus == SystemStatus.RUNNING;
    }

    /**
     * 检查是否可以下单
     */
    public boolean canPlaceOrder() {
        return systemStatus == SystemStatus.RUNNING;
    }

    /**
     * 检查是否可以撤单
     */
    public boolean canCancelOrder() {
        return systemStatus == SystemStatus.RUNNING || systemStatus == SystemStatus.SHUTTING_DOWN;
    }

    /**
     * 从 DEGRADED 恢复为 RUNNING
     */
    public void recover() {
        if (systemStatus == SystemStatus.DEGRADED) {
            logger.info("Recovering from DEGRADED to RUNNING");
            systemStatus = SystemStatus.RUNNING;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Getters for Workers
    public AccountWorker getAccountWorker() { return accountWorker; }
    public MatchingEngineWorker getMatchingEngineWorker() { return matchingEngineWorker; }
    public ClearingWorker getClearingWorker() { return clearingWorker; }
    public AsyncPersistenceWorker getAsyncPersistenceWorker() { return asyncPersistenceWorker; }

    // ==================== SmartLifecycle 接口实现 ====================

    @Override
    public int getPhase() {
        return 0; // 最先启动
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void stop(Runnable callback) {
        shutdown();
        running = false;
        callback.run();
    }

    @Override
    public void stop() {
        shutdown();
        running = false;
    }
}
