package com.hft.trading.lifecycle;

import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.TradingPhase;
import com.hft.trading.queue.*;
import com.hft.trading.recovery.RecoveryManager;
import com.hft.trading.store.*;
import com.hft.trading.websocket.MarketDataPublisher;
import com.hft.trading.websocket.WebSocketPushService;
import com.hft.trading.websocket.WebSocketServer;
import com.hft.trading.worker.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * LifecycleManager - 系统生命周期管理器
 *
 * 根据 V12 技术方案：
 * - 系统状态机：STARTING → RECOVERING → RUNNING → SHUTTING_DOWN
 * - 四阶段恢复：AccountWorker → MatchingEngine → ClearingWorker → AccountWorker(第二阶段)
 * - Worker 生命周期管理
 */
@Component
public class LifecycleManager {

    private static final Logger logger = LoggerFactory.getLogger(LifecycleManager.class);

    // 系统状态
    private volatile SystemStatus systemStatus = SystemStatus.STARTING;

    // 存储
    private final InMemoryAccountStore accountStore;
    private final InMemoryPositionStore positionStore;
    private final InMemoryOrderStore orderStore;
    private final InMemoryInstrumentStore instrumentStore;

    // 队列
    private final QueueManager queueManager;

    // WebSocket（可选）
    private WebSocketServer webSocketServer;
    private WebSocketPushService webSocketPushService;
    private MarketDataPublisher marketDataPublisher;

    // Workers
    private AccountWorker accountWorker;
    private MatchingEngineWorker matchingEngineWorker;
    private ClearingWorker clearingWorker;

    // RecoveryManager
    private RecoveryManager recoveryManager;

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
                            QueueManager queueManager) {
        this.accountStore = accountStore;
        this.positionStore = positionStore;
        this.orderStore = orderStore;
        this.instrumentStore = instrumentStore;
        this.queueManager = queueManager;
        this.chronicleBasePath = "./data/chronicle/";
    }

    /**
     * 系统启动入口
     */
    @PostConstruct
    public void startup() {
        logger.info("=== HFT Trading System Starting ===");
        logger.info("System status: STARTING");

        try {
            // 1. 初始化 Workers
            initializeWorkers();

            // 2. 检查是否需要恢复
            if (needsRecovery()) {
                systemStatus = SystemStatus.RECOVERING;
                logger.info("System status: RECOVERING");
                performRecovery();
            }

            // 3. 启动所有 Workers
            startAllWorkers();

            // 4. 系统就绪
            systemStatus = SystemStatus.RUNNING;
            logger.info("System status: RUNNING");
            logger.info("=== HFT Trading System Started Successfully ===");

        } catch (Exception e) {
            logger.error("System startup failed: {}", e.getMessage(), e);
            systemStatus = SystemStatus.DEGRADED;
        }
    }

    /**
     * 系统关闭
     */
    @PreDestroy
    public void shutdown() {
        logger.info("=== HFT Trading System Shutting Down ===");
        systemStatus = SystemStatus.SHUTTING_DOWN;
        logger.info("System status: SHUTTING_DOWN");

        try {
            // 1. 停止接收新请求
            logger.info("Step 1: Stopping new requests...");

            // 2. 等待队列清空
            logger.info("Step 2: Waiting for queues to drain...");
            waitForQueuesToDrain(5000);

            // 3. 触发最终快照
            logger.info("Step 3: Triggering final snapshot...");
            triggerFinalSnapshot();

            // 4. 停止所有 Workers
            logger.info("Step 4: Stopping all workers...");
            stopAllWorkers();

            // 5. 关闭队列
            logger.info("Step 5: Closing queues...");
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

        // Create MarketDataPublisher (optional, depends on WebSocket)
        if (webSocketServer != null) {
            marketDataPublisher = new MarketDataPublisher(webSocketServer);
            marketDataPublisher.setMatchingEngines(matchingEngineWorker.getMatchingEngines());
        }

        logger.info("Workers initialized: AccountWorker, MatchingEngineWorker, ClearingWorker, RecoveryManager" +
            (marketDataPublisher != null ? ", MarketDataPublisher" : ""));
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
     * 启动所有 Workers
     */
    private void startAllWorkers() {
        logger.info("Starting all workers...");

        // 按顺序启动：先 AccountWorker，再 MatchingEngine，最后 ClearingWorker
        accountWorker.start();
        sleep(100); // 等待启动

        matchingEngineWorker.start();
        sleep(100);

        clearingWorker.start();
        sleep(100);

        // 启动 MarketDataPublisher（在所有 Workers 之后）
        if (marketDataPublisher != null) {
            marketDataPublisher.start();
            sleep(100);
        }

        logger.info("All workers started");
    }

    /**
     * 停止所有 Workers
     */
    private void stopAllWorkers() {
        logger.info("Stopping all workers...");

        // 先停止 MarketDataPublisher
        if (marketDataPublisher != null) {
            marketDataPublisher.stop();
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

        logger.info("All workers stopped");
    }

    /**
     * 等待队列清空
     */
    private void waitForQueuesToDrain(long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            QueueManager.QueueStats stats = queueManager.getStats();
            // 简化：检查是否有待处理事件
            if (stats.getAccountInputLastIndex() <= stats.getAccountInputCurrentIndex()) {
                logger.info("Queues drained");
                return;
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

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 设置 WebSocket 推送服务
     * 用于 Spring 注入 WebSocketPushService
     */
    public void setWebSocketPushService(WebSocketPushService webSocketPushService) {
        this.webSocketPushService = webSocketPushService;
    }

    /**
     * 设置 WebSocket 服务器
     * 用于 Spring 注入 WebSocketServer
     */
    public void setWebSocketServer(WebSocketServer webSocketServer) {
        this.webSocketServer = webSocketServer;
    }

    // Getters for Workers
    public AccountWorker getAccountWorker() { return accountWorker; }
    public MatchingEngineWorker getMatchingEngineWorker() { return matchingEngineWorker; }
    public ClearingWorker getClearingWorker() { return clearingWorker; }
}
