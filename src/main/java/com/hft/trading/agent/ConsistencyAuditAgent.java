package com.hft.trading.agent;

import com.hft.trading.model.Account;
import com.hft.trading.model.Position;
import com.hft.trading.queue.QueueManager;
import com.hft.trading.service.AlertService;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryOrderStore;
import com.hft.trading.store.InMemoryPositionStore;
import com.hft.trading.websocket.WebSocketPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ConsistencyAuditAgent - 一致性审计 Agent
 *
 * 功能：
 * - 定期验证账户不变式（balance = available + frozen）
 * - 定期验证持仓不变式（|quantity| = available + frozen + t1Locked）
 * - 验证冻结与活跃订单的一致性
 * - 监控 CQ 队列堆积情况
 * - 连续失败时推送告警
 */
@Component
public class ConsistencyAuditAgent {

    private static final Logger logger = LoggerFactory.getLogger(ConsistencyAuditAgent.class);

    private static final long INITIAL_DELAY_MS = 5000;
    private static final long PERIOD_MS = 30000;
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 2;
    private static final long BALANCE_TOLERANCE = 1; // 允许 1 分的偏差
    private static final long CQ_PRESSURE_WARNING = 500;
    private static final long CQ_PRESSURE_CRITICAL = 2000;

    private final AgentContext agentContext;
    private final InMemoryAccountStore accountStore;
    private final InMemoryPositionStore positionStore;
    private final InMemoryOrderStore orderStore;
    private final QueueManager queueManager;
    private final WebSocketPushService webSocketPushService;
    private final AlertService alertService;

    private ScheduledExecutorService scheduler;
    private volatile int consecutiveFailureCount = 0;
    private volatile long lastAccountInputDepth = 0;
    private volatile long lastMatchingInputDepth = 0;
    private volatile long lastMatchingOutputDepth = 0;
    private volatile long lastAccountSettlementDepth = 0;
    private volatile boolean running = false;

    public ConsistencyAuditAgent(AgentContext agentContext,
                                  InMemoryAccountStore accountStore,
                                  InMemoryPositionStore positionStore,
                                  InMemoryOrderStore orderStore,
                                  QueueManager queueManager,
                                  WebSocketPushService webSocketPushService,
                                  AlertService alertService) {
        this.agentContext = agentContext;
        this.accountStore = accountStore;
        this.positionStore = positionStore;
        this.orderStore = orderStore;
        this.queueManager = queueManager;
        this.webSocketPushService = webSocketPushService;
        this.alertService = alertService;
    }

    @PostConstruct
    public void start() {
        if (running) {
            logger.warn("ConsistencyAuditAgent already running");
            return;
        }

        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConsistencyAuditAgent");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runAuditCycle, INITIAL_DELAY_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
        logger.info("ConsistencyAuditAgent started");
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("ConsistencyAuditAgent stopped");
    }

    /**
     * 执行一个审计周期
     */
    private void runAuditCycle() {
        try {
            // 检查是否应该跳过此周期
            if (agentContext.shouldSkip()) {
                logger.debug("Skipping audit cycle (system not ready)");
                return;
            }

            boolean hasViolation = false;

            // 执行四项检查
            hasViolation |= checkAccountInvariants();
            hasViolation |= checkPositionInvariants();
            hasViolation |= checkFrozenOrderConsistency();
            hasViolation |= checkCqPressure();

            // 更新连续失败计数
            if (hasViolation) {
                consecutiveFailureCount++;
                logger.warn("Audit cycle completed with violations, consecutive failures: {}",
                    consecutiveFailureCount);
            } else {
                consecutiveFailureCount = 0;
                logger.debug("Audit cycle completed without violations");
            }

        } catch (Exception e) {
            logger.error("Error in audit cycle: {}", e.getMessage(), e);
            // 异常不增加连续失败计数，避免因为 Agent 自身错误导致误报
        }
    }

    /**
     * 检查账户不变式
     */
    private boolean checkAccountInvariants() {
        boolean hasViolation = false;
        List<Account> accounts = accountStore.getAllAccounts();

        for (Account account : accounts) {
            long balance = account.getBalance();
            long available = account.getAvailable();
            long frozen = account.getFrozen();
            long marginFrozen = account.getMarginFrozen();
            long orderFrozen = account.getOrderFrozen();

            // 检查 balance = available + frozen
            long balanceDiff = balance - (available + frozen);
            if (Math.abs(balanceDiff) > BALANCE_TOLERANCE) {
                hasViolation = true;
                recordViolation("ACCOUNT_INVARIANT", account.getAccountId(), null,
                    "Account invariant violation: balance=%d, available=%d, frozen=%d, diff=%d",
                    balance, available, frozen, balanceDiff);
            }

            // 检查 frozen = marginFrozen + orderFrozen
            long frozenDiff = frozen - (marginFrozen + orderFrozen);
            if (Math.abs(frozenDiff) > BALANCE_TOLERANCE) {
                hasViolation = true;
                recordViolation("ACCOUNT_FROZEN_SPLIT", account.getAccountId(), null,
                    "Account frozen split violation: frozen=%d, marginFrozen=%d, orderFrozen=%d, diff=%d",
                    frozen, marginFrozen, orderFrozen, frozenDiff);
            }
        }

        return hasViolation;
    }

    /**
     * 检查持仓不变式
     */
    private boolean checkPositionInvariants() {
        boolean hasViolation = false;
        List<Position> positions = positionStore.getAllPositions();

        for (Position position : positions) {
            long quantity = position.getQuantity();
            long available = position.getAvailable();
            long frozen = position.getFrozen();
            long t1Locked = position.getT1Locked();

            // 检查 |quantity| = available + frozen + t1Locked
            long sum = available + frozen + t1Locked;
            if (Math.abs(quantity) != sum) {
                hasViolation = true;
                recordViolation("POSITION_INVARIANT", position.getAccountId(), position.getSymbol(),
                    "Position invariant violation: quantity=%d, available=%d, frozen=%d, t1Locked=%d, sum=%d",
                    quantity, available, frozen, t1Locked, sum);
            }
        }

        return hasViolation;
    }

    /**
     * 检查冻结与订单一致性
     */
    private boolean checkFrozenOrderConsistency() {
        boolean hasViolation = false;
        List<Account> accounts = accountStore.getAllAccounts();

        for (Account account : accounts) {
            List<com.hft.trading.model.Order> activeOrders =
                orderStore.getActiveOrdersByAccount(account.getAccountId());

            long totalOrderFrozen = activeOrders.stream()
                .mapToLong(com.hft.trading.model.Order::getFrozenAmount)
                .sum();

            long diff = account.getOrderFrozen() - totalOrderFrozen;
            if (Math.abs(diff) > BALANCE_TOLERANCE) {
                hasViolation = true;
                recordViolation("FROZEN_ORDER_MISMATCH", account.getAccountId(), null,
                    "Frozen-order mismatch: account.orderFrozen=%d, sum(activeOrders.frozen)=%d, diff=%d",
                    account.getOrderFrozen(), totalOrderFrozen, diff);
            }
        }

        return hasViolation;
    }

    /**
     * 检查 CQ 队列压力
     */
    private boolean checkCqPressure() {
        boolean hasViolation = false;
        QueueManager.QueueStats stats = queueManager.getStats();

        long accountInputDepth = stats.getAccountInputLastIndex() - stats.getAccountInputCurrentIndex();
        long matchingInputDepth = stats.getMatchingInputLastIndex() - stats.getMatchingInputCurrentIndex();
        long matchingOutputDepth = stats.getMatchingOutputLastIndex() - stats.getMatchingOutputCurrentIndex();
        long accountSettlementDepth = stats.getAccountSettlementLastIndex() - stats.getAccountSettlementCurrentIndex();

        // 检查各队列堆积情况
        hasViolation |= checkQueueDepth("account_input", accountInputDepth, lastAccountInputDepth);
        hasViolation |= checkQueueDepth("matching_input", matchingInputDepth, lastMatchingInputDepth);
        hasViolation |= checkQueueDepth("matching_output", matchingOutputDepth, lastMatchingOutputDepth);
        hasViolation |= checkQueueDepth("account_settlement", accountSettlementDepth, lastAccountSettlementDepth);

        // 更新上次记录
        lastAccountInputDepth = accountInputDepth;
        lastMatchingInputDepth = matchingInputDepth;
        lastMatchingOutputDepth = matchingOutputDepth;
        lastAccountSettlementDepth = accountSettlementDepth;

        return hasViolation;
    }

    private boolean checkQueueDepth(String queueName, long depth, long lastDepth) {
        if (depth > CQ_PRESSURE_CRITICAL) {
            // 严重级别
            recordViolation("CQ_PRESSURE_CRITICAL", null, queueName,
                "Queue %s has critical backpressure: depth=%d", queueName, depth);
            return true;
        } else if (depth > CQ_PRESSURE_WARNING && depth > lastDepth) {
            // 警告级别且持续增长
            recordViolation("CQ_PRESSURE_WARNING", null, queueName,
                "Queue %s has growing backpressure: depth=%d (was=%d)", queueName, depth, lastDepth);
            return true;
        }
        return false;
    }

    /**
     * 记录违规
     */
    private void recordViolation(String checkType, Long accountId, String symbol,
                                 String format, Object... args) {
        String detail = String.format(format, args);
        logger.warn("[ConsistencyAudit][{}] {}", checkType, detail);

        // 只有达到连续失败阈值才推送告警
        if (consecutiveFailureCount >= CONSECUTIVE_FAILURE_THRESHOLD - 1) {
            // 本次之后就会达到阈值，立即推送告警
            pushAlert(checkType, accountId, symbol, detail);
        }
    }

    /**
     * 推送告警
     */
    private void pushAlert(String checkType, Long accountId, String symbol, String detail) {
        String level = checkType.startsWith("CQ_PRESSURE_CRITICAL") ? "P0" :
            checkType.startsWith("CQ_PRESSURE") ? "P1" : "P0";

        // WebSocket 推送
        WebSocketPushService.AgentAlertMessage message =
            new WebSocketPushService.AgentAlertMessage();
        message.setAgentName("ConsistencyAudit");
        message.setAlertLevel(level);
        message.setCheckType(checkType);
        message.setAccountId(accountId);
        message.setSymbol(symbol);
        message.setDetail(detail);
        message.setTimestamp(System.currentTimeMillis());
        webSocketPushService.pushAgentAlert(message);

        // AlertService 告警
        AlertService.AlertLevel alertLevel = "P0".equals(level) ?
            AlertService.AlertLevel.P0 : "P1".equals(level) ?
            AlertService.AlertLevel.P1 : AlertService.AlertLevel.P2;
        alertService.sendAlert(alertLevel, "ConsistencyAudit",
            "[{}] {}", checkType, detail);
    }
}
