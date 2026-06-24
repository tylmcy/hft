package com.hft.trading.agent;

import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.lifecycle.PhaseManager;
import com.hft.trading.queue.QueueManager;
import com.hft.trading.service.AlertService;
import com.hft.trading.websocket.WebSocketPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OperationsDiagnosticAgent - 运维诊断 Agent
 *
 * 功能：
 * - 监控 Worker 延迟（P50/P90/P99）
 * - 监控 CQ 队列堆积及趋势
 * - 监控 GC 指标
 * - 规则引擎诊断并推送告警
 * - 保留历史诊断数据
 */
@Component
public class OperationsDiagnosticAgent {

    private static final Logger logger = LoggerFactory.getLogger(OperationsDiagnosticAgent.class);

    private static final long INITIAL_DELAY_MS = 5000;
    private static final long PERIOD_MS = 30000;
    private static final long WORKER_P99_WARNING = 50;
    private static final long WORKER_P99_CRITICAL = 200;
    private static final long CQ_DEPTH_WARNING = 500;
    private static final long CQ_DEPTH_CRITICAL = 2000;
    private static final long GC_AVG_PAUSE_WARNING = 5; // ms
    private static final int HISTORY_SIZE = 60;
    private static final int CONSECUTIVE_P1_THRESHOLD = 3;

    private final AgentContext agentContext;
    private final LifecycleManager lifecycleManager;
    private final PhaseManager phaseManager;
    private final QueueManager queueManager;
    private final WebSocketPushService webSocketPushService;
    private final AlertService alertService;

    // Worker references (set by LifecycleManager)
    private volatile Object accountWorker;
    private volatile Object matchingEngineWorker;
    private volatile Object clearingWorker;

    private ScheduledExecutorService scheduler;
    private final Deque<DiagnosticSnapshot> diagnosticHistory = new LinkedList<>();
    private volatile long lastGcCollectionCount = 0;
    private volatile long lastGcCollectionTime = 0;
    private volatile int consecutiveP1Count = 0;
    private volatile long lastAccountInputDepth = 0;
    private volatile long lastMatchingInputDepth = 0;
    private volatile long lastMatchingOutputDepth = 0;
    private volatile long lastAccountSettlementDepth = 0;
    private volatile boolean running = false;

    public OperationsDiagnosticAgent(AgentContext agentContext,
                                      LifecycleManager lifecycleManager,
                                      PhaseManager phaseManager,
                                      QueueManager queueManager,
                                      WebSocketPushService webSocketPushService,
                                      AlertService alertService) {
        this.agentContext = agentContext;
        this.lifecycleManager = lifecycleManager;
        this.phaseManager = phaseManager;
        this.queueManager = queueManager;
        this.webSocketPushService = webSocketPushService;
        this.alertService = alertService;
    }

    public void setWorkers(Object accountWorker, Object matchingEngineWorker, Object clearingWorker) {
        this.accountWorker = accountWorker;
        this.matchingEngineWorker = matchingEngineWorker;
        this.clearingWorker = clearingWorker;
    }

    @PostConstruct
    public void start() {
        if (running) {
            logger.warn("OperationsDiagnosticAgent already running");
            return;
        }

        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OperationsDiagnosticAgent");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runDiagnosticCycle, INITIAL_DELAY_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
        logger.info("OperationsDiagnosticAgent started");
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
        logger.info("OperationsDiagnosticAgent stopped");
    }

    /**
     * 执行一个诊断周期
     */
    private void runDiagnosticCycle() {
        try {
            // 检查是否应该跳过此周期
            if (agentContext.shouldSkip()) {
                logger.debug("Skipping diagnostic cycle (system not ready)");
                return;
            }

            // 采集数据
            DiagnosticSnapshot snapshot = collectSnapshot();
            if (snapshot == null) {
                return;
            }

            // 保存到历史
            synchronized (diagnosticHistory) {
                diagnosticHistory.addLast(snapshot);
                if (diagnosticHistory.size() > HISTORY_SIZE) {
                    diagnosticHistory.removeFirst();
                }
            }

            // 规则引擎诊断
            List<DiagnosticResult> results = evaluateRules(snapshot);

            // 处理诊断结果
            if (!results.isEmpty()) {
                processResults(results);
            } else {
                consecutiveP1Count = 0;
                logger.debug("Diagnostic cycle completed without issues");
            }

        } catch (Exception e) {
            logger.error("Error in diagnostic cycle: {}", e.getMessage(), e);
        }
    }

    /**
     * 采集诊断数据快照
     */
    private DiagnosticSnapshot collectSnapshot() {
        DiagnosticSnapshot snapshot = new DiagnosticSnapshot();
        snapshot.timestamp = System.currentTimeMillis();
        snapshot.systemStatus = lifecycleManager.getSystemStatus();
        snapshot.tradingPhase = phaseManager.getCurrentPhase();

        // 采集 Worker 延迟
        snapshot.workerLatencies = collectWorkerLatencies();

        // 采集 CQ 深度
        snapshot.cqDepths = collectCqDepths();

        // 采集 GC 指标
        snapshot.gcMetrics = collectGcMetrics();

        return snapshot;
    }

    private List<WorkerLatency> collectWorkerLatencies() {
        List<WorkerLatency> latencies = new ArrayList<>();

        if (accountWorker != null) {
            WorkerLatency wl = getWorkerLatency("AccountWorker", accountWorker);
            if (wl != null) latencies.add(wl);
        }
        if (matchingEngineWorker != null) {
            WorkerLatency wl = getWorkerLatency("MatchingEngine", matchingEngineWorker);
            if (wl != null) latencies.add(wl);
        }
        if (clearingWorker != null) {
            WorkerLatency wl = getWorkerLatency("ClearingWorker", clearingWorker);
            if (wl != null) latencies.add(wl);
        }

        return latencies;
    }

    private WorkerLatency getWorkerLatency(String name, Object worker) {
        try {
            WorkerLatency wl = new WorkerLatency();
            wl.name = name;

            // 反射调用方法获取延迟指标
            java.lang.reflect.Method getLastBatch = worker.getClass().getMethod("getLastBatchDurationMs");
            wl.lastBatch = (long) getLastBatch.invoke(worker);

            java.lang.reflect.Method getP50 = worker.getClass().getMethod("getP50LatencyMs");
            wl.p50 = (long) getP50.invoke(worker);

            java.lang.reflect.Method getP90 = worker.getClass().getMethod("getP90LatencyMs");
            wl.p90 = (long) getP90.invoke(worker);

            java.lang.reflect.Method getP99 = worker.getClass().getMethod("getP99LatencyMs");
            wl.p99 = (long) getP99.invoke(worker);

            java.lang.reflect.Method isRunning = worker.getClass().getMethod("isRunning");
            wl.running = (boolean) isRunning.invoke(worker);

            java.lang.reflect.Method isReady = worker.getClass().getMethod("isReady");
            wl.ready = (boolean) isReady.invoke(worker);

            return wl;
        } catch (Exception e) {
            logger.debug("Failed to get latency for {}: {}", name, e.getMessage());
            return null;
        }
    }

    private List<CqDepth> collectCqDepths() {
        List<CqDepth> depths = new ArrayList<>();
        QueueManager.QueueStats stats = queueManager.getStats();

        long accountInputDepth = Math.max(0, stats.getAccountInputLastIndex() - stats.getAccountInputCurrentIndex());
        depths.add(new CqDepth("account_input", accountInputDepth, getTrend(accountInputDepth, lastAccountInputDepth)));
        lastAccountInputDepth = accountInputDepth;

        long matchingInputDepth = Math.max(0, stats.getMatchingInputLastIndex() - stats.getMatchingInputCurrentIndex());
        depths.add(new CqDepth("matching_input", matchingInputDepth, getTrend(matchingInputDepth, lastMatchingInputDepth)));
        lastMatchingInputDepth = matchingInputDepth;

        long matchingOutputDepth = Math.max(0, stats.getMatchingOutputLastIndex() - stats.getMatchingOutputCurrentIndex());
        depths.add(new CqDepth("matching_output", matchingOutputDepth, getTrend(matchingOutputDepth, lastMatchingOutputDepth)));
        lastMatchingOutputDepth = matchingOutputDepth;

        long accountSettlementDepth = Math.max(0, stats.getAccountSettlementLastIndex() - stats.getAccountSettlementCurrentIndex());
        depths.add(new CqDepth("account_settlement", accountSettlementDepth, getTrend(accountSettlementDepth, lastAccountSettlementDepth)));
        lastAccountSettlementDepth = accountSettlementDepth;

        return depths;
    }

    private Trend getTrend(long current, long last) {
        if (current > last) return Trend.RISING;
        if (current < last) return Trend.FALLING;
        return Trend.FLAT;
    }

    private GcMetrics collectGcMetrics() {
        GcMetrics metrics = new GcMetrics();

        try {
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            long totalCollectionCount = 0;
            long totalCollectionTime = 0;

            for (GarbageCollectorMXBean gcBean : gcBeans) {
                totalCollectionCount += gcBean.getCollectionCount();
                totalCollectionTime += gcBean.getCollectionTime();
            }

            // 计算增量
            long countDelta = totalCollectionCount - lastGcCollectionCount;
            long timeDelta = totalCollectionTime - lastGcCollectionTime;

            metrics.totalPauseMs = totalCollectionTime;
            metrics.pauseCount = totalCollectionCount;

            if (countDelta > 0) {
                metrics.avgPauseMs = timeDelta / countDelta;
            }
            metrics.maxPauseMs = metrics.avgPauseMs;

            lastGcCollectionCount = totalCollectionCount;
            lastGcCollectionTime = totalCollectionTime;

        } catch (Exception e) {
            logger.debug("Failed to collect GC metrics: {}", e.getMessage());
        }

        return metrics;
    }

    /**
     * 规则引擎评估
     */
    private List<DiagnosticResult> evaluateRules(DiagnosticSnapshot snapshot) {
        List<DiagnosticResult> results = new ArrayList<>();
        boolean hasP0 = false;
        boolean hasP1 = false;

        // Rule 1: CQ 堆积 P0
        for (CqDepth depth : snapshot.cqDepths) {
            if (depth.depth > CQ_DEPTH_CRITICAL && depth.trend == Trend.RISING) {
                hasP0 = true;
                results.add(new DiagnosticResult(
                    "CQ_PRESSURE_CRITICAL", "P0", depth.name,
                    String.format("%s 堆积 %d 条且持续增长，下游处理能力不足", depth.name, depth.depth),
                    String.valueOf(depth.depth),
                    "检查下游 Worker 是否正常，考虑增大批量处理大小"
                ));
            }
        }

        // Rule 2: Worker 延迟 P0
        for (WorkerLatency latency : snapshot.workerLatencies) {
            if (latency.p99 > WORKER_P99_CRITICAL) {
                hasP0 = true;
                results.add(new DiagnosticResult(
                    "WORKER_LATENCY_CRITICAL", "P0", latency.name,
                    String.format("%s P99 延迟 %d ms，超过 %d ms 阈值", latency.name, latency.p99, WORKER_P99_CRITICAL),
                    String.valueOf(latency.p99),
                    "检查该 Worker 是否被耗时操作卡住，考虑重启"
                ));
            }
        }

        // Rule 3: CQ 堆积 P1（如果还没有触发 P0）
        if (!hasP0) {
            for (CqDepth depth : snapshot.cqDepths) {
                if (depth.depth > CQ_DEPTH_WARNING && depth.trend == Trend.RISING) {
                    hasP1 = true;
                    results.add(new DiagnosticResult(
                        "CQ_PRESSURE_WARNING", "P1", depth.name,
                        String.format("%s 堆积 %d 条且持续增长", depth.name, depth.depth),
                        String.valueOf(depth.depth),
                        "观察下一周期是否缓解，如持续恶化将升级为 P0"
                    ));
                }
            }
        }

        // Rule 4: Worker 延迟 P1（如果还没有触发 P0）
        if (!hasP0) {
            for (WorkerLatency latency : snapshot.workerLatencies) {
                if (latency.p99 > WORKER_P99_WARNING) {
                    hasP1 = true;
                    results.add(new DiagnosticResult(
                        "WORKER_LATENCY_WARNING", "P1", latency.name,
                        String.format("%s P99 延迟 %d ms，超过 %d ms 阈值", latency.name, latency.p99, WORKER_P99_WARNING),
                        String.valueOf(latency.p99),
                        "关注该 Worker 处理性能"
                    ));
                }
            }
        }

        // Rule 5: 综合判定 - 同时有 CQ P1 和 Worker P1（不同组件）则升级为 P0
        if (!hasP0 && hasP1) {
            boolean hasCqP1 = results.stream().anyMatch(r -> r.checkType.startsWith("CQ_PRESSURE"));
            boolean hasWorkerP1 = results.stream().anyMatch(r -> r.checkType.startsWith("WORKER_LATENCY"));
            if (hasCqP1 && hasWorkerP1) {
                results.add(new DiagnosticResult(
                    "SYSTEM_CAPACITY_DEGRADED", "P0", "System",
                    "系统整体处理能力下降，多处堆积且多个 Worker 延迟升高",
                    "Multiple",
                    "检查 GC 是否异常、磁盘 IO 是否达到瓶颈"
                ));
                hasP0 = true;
            }
        }

        // Rule 6: GC 异常 P2
        if (snapshot.gcMetrics.avgPauseMs > GC_AVG_PAUSE_WARNING) {
            results.add(new DiagnosticResult(
                "GC_PAUSE_HIGH", "P2", "GC",
                String.format("GC 平均暂停 %d ms，超过 %d ms 阈值", snapshot.gcMetrics.avgPauseMs, GC_AVG_PAUSE_WARNING),
                String.valueOf(snapshot.gcMetrics.avgPauseMs),
                "检查堆内存使用率和 GC 配置"
            ));
        }

        // Rule 7: 连续 3 次 P1 升级为 P0
        if (hasP1 && !hasP0) {
            consecutiveP1Count++;
            if (consecutiveP1Count >= CONSECUTIVE_P1_THRESHOLD) {
                results.add(new DiagnosticResult(
                    "PERSISTENT_DEGRADATION", "P0", "System",
                    String.format("连续 %d 次周期均有 P1 告警，系统已降级", consecutiveP1Count),
                    String.valueOf(consecutiveP1Count),
                    "人工介入检查系统状态"
                ));
            }
        } else if (!hasP1) {
            consecutiveP1Count = 0;
        }

        return results;
    }

    /**
     * 处理诊断结果
     */
    private void processResults(List<DiagnosticResult> results) {
        for (DiagnosticResult result : results) {
            logger.warn("[OpsDiagnostic][{}][{}] {} | value={}",
                result.alertLevel, result.checkType, result.localizedCause, result.value);

            // WebSocket 推送
            WebSocketPushService.AgentAlertMessage message =
                new WebSocketPushService.AgentAlertMessage();
            message.setAgentName("OpsDiagnostic");
            message.setAlertLevel(result.alertLevel);
            message.setCheckType(result.checkType);
            message.setSymbol(result.localizedComponent);
            message.setDetail(String.format("[%s] %s | 建议: %s",
                result.localizedComponent, result.localizedCause, result.suggestion));
            message.setSuggestion(result.suggestion);
            message.setTimestamp(System.currentTimeMillis());
            webSocketPushService.pushAgentAlert(message);

            // AlertService 告警
            AlertService.AlertLevel alertLevel = "P0".equals(result.alertLevel) ?
                AlertService.AlertLevel.P0 : "P1".equals(result.alertLevel) ?
                AlertService.AlertLevel.P1 : AlertService.AlertLevel.P2;
            alertService.sendAlert(alertLevel, "OpsDiagnostic",
                "[%s] %s | value=%s", result.checkType, result.localizedCause, result.value);
        }
    }

    /**
     * 获取诊断历史（用于管理页面）
     */
    public List<DiagnosticSnapshot> getDiagnosticHistory() {
        synchronized (diagnosticHistory) {
            return new ArrayList<>(diagnosticHistory);
        }
    }

    // 数据结构定义
    public static class DiagnosticSnapshot {
        public List<WorkerLatency> workerLatencies;
        public List<CqDepth> cqDepths;
        public GcMetrics gcMetrics;
        public LifecycleManager.SystemStatus systemStatus;
        public com.hft.trading.event.TradingPhase tradingPhase;
        public long timestamp;
    }

    public static class WorkerLatency {
        public String name;
        public long lastBatch;
        public long p50;
        public long p90;
        public long p99;
        public boolean running;
        public boolean ready;
    }

    public static class CqDepth {
        public String name;
        public long depth;
        public Trend trend;

        public CqDepth(String name, long depth, Trend trend) {
            this.name = name;
            this.depth = depth;
            this.trend = trend;
        }
    }

    public enum Trend {
        RISING,
        FLAT,
        FALLING
    }

    public static class GcMetrics {
        public long totalPauseMs;
        public long pauseCount;
        public long avgPauseMs;
        public long maxPauseMs;
    }

    public static class DiagnosticResult {
        public String checkType;
        public String alertLevel;
        public String localizedComponent;
        public String localizedCause;
        public String value;
        public String suggestion;

        public DiagnosticResult(String checkType, String alertLevel, String localizedComponent,
                                String localizedCause, String value, String suggestion) {
            this.checkType = checkType;
            this.alertLevel = alertLevel;
            this.localizedComponent = localizedComponent;
            this.localizedCause = localizedCause;
            this.value = value;
            this.suggestion = suggestion;
        }
    }
}
