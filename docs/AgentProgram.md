# Agent 实施方案

> 版本: v1.0 | 日期: 2026-06-24 | 状态: **待评审**
> 实施范围：一致性审计 Agent + 运维诊断 Agent

---

## 目录

1. [概述](#1-概述)
2. [公共基础设施](#2-公共基础设施)
3. [一致性审计 Agent](#3-一致性审计-agent)
4. [运维诊断 Agent](#4-运维诊断-agent)
5. [现有文件修改清单](#5-现有文件修改清单)
6. [实施顺序与工作量](#6-实施顺序与工作量)

---

## 1. 概述

本次实施两个 Agent，共享同一套公共基础设施，与现有代码的集成边界清晰。

### 1.1 实施范围

| Agent | 定位 | 运行模式 | 与主链路关系 |
|-------|------|---------|------------|
| 一致性审计 Agent | 验证账户/持仓不变式，检测数据不一致 | 每 30 秒定时扫描 | 只读旁路，零侵入 |
| 运维诊断 Agent | 采集 Worker 延迟、CQ 堆积、GC 指标，规则引擎诊断 | 每 30 秒定时采集 | 采集现有指标，零侵入 |

### 1.2 实施总览

| 维度 | 数据 |
|------|------|
| 新增 Java 文件 | 4 个（SlidingWindow + 两个 Agent + AgentContext） |
| 修改现有 Java 文件 | 4 个（3 个 Worker 类 + WebSocketPushService） |
| 修改前端文件 | 1 个（WebSocket 类型定义） |
| 总代码量 | ~450 行 Java + 2 行 TypeScript |
| 不修改的组件 | AccountStore/PositionStore/OrderStore、CQ 文件、H2、LifecycleManager、PhaseManager、QueueManager、AlertService |

---

## 2. 公共基础设施

### 2.1 AgentContext

两个 Agent 均需要感知系统运行状态，避免在不恰当的阶段执行。AgentContext 统一提供此能力。

**文件**：`src/main/java/com/hft/trading/agent/AgentContext.java`

```java
package com.hft.trading.agent;

import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.lifecycle.PhaseManager;
import com.hft.trading.event.TradingPhase;
import org.springframework.stereotype.Component;

/**
 * Agent 运行上下文
 *
 * 各 Agent 通过此组件感知系统状态，决定是否执行、
 * 是否降级、是否跳过当前周期。
 */
@Component
public class AgentContext {

    // 系统在 RUNNING 状态且为交易时段时，Agent 全功能运行
    // 其他状态：Agent 跳过或降级

    private volatile boolean degradedMode = false;

    public boolean shouldSkip() {
        // 系统状态不是 RUNNING（含 RECOVERING/SNAPSHOTTING）→ 跳过
        // 通过调用 LifecycleManager.systemStatus 判断
    }

    public boolean isDegraded() { return degradedMode; }
}
```

**说明**：AgentContext 的设计保持极简，只提供 `shouldSkip()` 和 `isDegraded()` 两个方法。`shouldSkip()` 检查 `LifecycleManager.systemStatus` 和 `PhaseManager.currentPhase` 是否为可运行状态。Agent 在 AuditManager 或 DiagnosticsManager 中才是真正的调度者。

### 2.2 新增包路径

```
src/main/java/com/hft/trading/agent/
  ├── AgentContext.java
  ├── ConsistencyAuditAgent.java
  └── OperationsDiagnosticAgent.java
src/main/java/com/hft/trading/util/
  └── SlidingWindow.java        (已有 util 包，新增文件)
```

---

## 3. 一致性审计 Agent

### 3.1 文件

`src/main/java/com/hft/trading/agent/ConsistencyAuditAgent.java`

### 3.2 调度方式

使用 `ScheduledExecutorService` 单线程定时调度，模式和 `PhaseManager` 一致：

```java
// 在 start() 中
scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "ConsistencyAuditAgent");
    t.setDaemon(true);
    return t;
});
scheduler.scheduleAtFixedRate(this::runAuditCycle, 5_000, 30_000, TimeUnit.MILLISECONDS);
```

启动延迟 5 秒，确保系统已进入 RUNNING 状态。周期 30 秒。

### 3.3 审计流程

```
runAuditCycle():
  │
  ├─ 1. agentContext.shouldSkip() == true → 跳过本周期
  │
  ├─ 2. 执行 4 项检查（只读，不持有锁）
  │
  ├─ 3. 处理检查结果
  │     ├─ 全部通过 → counter=0，静默
  │     └─ 有违规 → counter++
  │           └─ counter >= 2 → 推送告警
  │
  └─ 4. 记录 lastAuditTimestamp
```

### 3.4 四项检查（逐项代码级定义）

#### 检查 1：账户不变式

```java
/**
 * 验证 balance = available + frozen
 * 验证 frozen = marginFrozen + orderFrozen
 *
 * 允许偏差 BALANCE_TOLERANCE = 1（分），
 * 容忍在途清算事件造成的瞬态偏差。
 */
for (Account account : accountStore.getAllAccounts()) {
    long balanceDiff = account.getBalance() - (account.getAvailable() + account.getFrozen());
    if (Math.abs(balanceDiff) > BALANCE_TOLERANCE) {
        recordViolation("ACCOUNT_INVARIANT", account.getAccountId(), null,
            "balance=%d, available=%d, frozen=%d, diff=%d",
            account.getBalance(), account.getAvailable(), account.getFrozen(), balanceDiff);
    }

    long frozenDiff = account.getFrozen() - (account.getMarginFrozen() + account.getOrderFrozen());
    if (Math.abs(frozenDiff) > BALANCE_TOLERANCE) {
        recordViolation("ACCOUNT_FROZEN_SPLIT", account.getAccountId(), null,
            "frozen=%d, marginFrozen=%d, orderFrozen=%d, diff=%d",
            account.getFrozen(), account.getMarginFrozen(), account.getOrderFrozen(), frozenDiff);
    }
}
```

预期扫描量：1000 个账户 → 单次审计 <1ms。

#### 检查 2：持仓不变式

```java
/**
 * 验证 |quantity| = available + frozen + t1Locked
 *
 * 由于当前版本仅支持多头（quantity >= 0），
 * 实现简化为 quantity == available + frozen + t1Locked。
 * 空头场景（quantity < 0 时取绝对值比较）暂不启用。
 */
for (Position position : positionStore.getAllPositions()) {
    long sum = position.getAvailable() + position.getFrozen() + position.getT1Locked();
    if (position.getQuantity() != sum) {
        recordViolation("POSITION_INVARIANT", position.getAccountId(), position.getSymbol(),
            "quantity=%d, available=%d, frozen=%d, t1Locked=%d, sum=%d",
            position.getQuantity(), position.getAvailable(),
            position.getFrozen(), position.getT1Locked(), sum);
    }
}
```

#### 检查 3：冻结-订单交叉校验

```java
/**
 * 验证 account.orderFrozen 与活跃订单的 frozenAmount 之和一致。
 *
 * 活跃订单 = status IN (PENDING, NEW, PARTIAL_FILLED)
 * 预期：orderFrozen == SUM(activeOrders.frozenAmount)
 *
 * 允许偏差 1 分（最后一笔清算的冻结扣减在途）。
 */
for (Account account : accountStore.getAllAccounts()) {
    List<Order> activeOrders = orderStore.getActiveOrdersByAccount(account.getAccountId());
    long totalOrderFrozen = activeOrders.stream()
        .mapToLong(Order::getFrozenAmount)
        .sum();
    long diff = account.getOrderFrozen() - totalOrderFrozen;
    if (Math.abs(diff) > 1) {
        recordViolation("FROZEN_ORDER_MISMATCH", account.getAccountId(), null,
            "orderFrozen=%d, sumActiveOrderFrozen=%d, unmatched=%d",
            account.getOrderFrozen(), totalOrderFrozen, diff);
    }
}
```

#### 检查 4：CQ 事件流审计

```java
/**
 * 验证 CQ 队列没有持续堆积。
 *
 * 对比本次与上次的堆积数（depth = lastIndex - currentIndex）：
 * - depth > 500 且比上次增长 → 告警
 * - depth > 2000 → 直接告警（不论趋势）
 */
QueueManager.QueueStats stats = queueManager.getStats();
// 读取 5 条 CQ 的 depth
// 与上一次采集值对比趋势
```

### 3.5 告警分级与推送

```java
private void recordViolation(String checkType, Long accountId, String symbol, String format, Object... args) {
    String detail = String.format(format, args);
    consecutiveFailureCount++;

    if (consecutiveFailureCount < CONSECUTIVE_FAILURE_THRESHOLD) {
        return; // 未达到连续阈值，静默
    }

    // 达到阈值，判定严重级别
    String level = checkType.equals("CQ_BACKPRESSURE") ? "P1" : "P0";

    // 推送 WebSocket
    AgentAlertMessage msg = new AgentAlertMessage();
    msg.setAgentName("ConsistencyAudit");
    msg.setAlertLevel(level);
    msg.setCheckType(checkType);
    msg.setAccountId(accountId);
    msg.setSymbol(symbol);
    msg.setDetail(detail);
    msg.setTimestamp(System.currentTimeMillis());
    webSocketPushService.pushAgentAlert(msg);

    // 调用 AlertService
    AlertService.AlertLevel alertLevel = level.equals("P0")
        ? AlertService.AlertLevel.P0 : AlertService.AlertLevel.P1;
    alertService.sendAlert(alertLevel, "ConsistencyAudit",
        "[" + checkType + "] " + detail);
}
```

### 3.6 降级路径

| 场景 | 行为 |
|------|------|
| `agentContext.shouldSkip()` 返回 true | 跳过本次审计，counter 不变 |
| `InMemoryAccountStore.getAllAccounts()` 返回空 | 静默通过，视为首次启动 |
| 执行过程中抛出异常 | 记录 error 日志，跳过本次，counter 不变 |
| 连续审计均通过 | counter 清 0 |

---

## 4. 运维诊断 Agent

### 4.1 文件

`src/main/java/com/hft/trading/agent/OperationsDiagnosticAgent.java`

### 4.2 调度方式

与审计 Agent 相同，ScheduledExecutorService 单线程，周期 30 秒。

### 4.3 数据采集

#### 4.3.1 Worker 延迟指标

**3 个 Worker 类各新增以下字段和方法**（修改模式完全相同）：

| 新增项 | 类型 | 说明 |
|--------|------|------|
| `lastBatchStartTime` | `volatile long` | 最近一批的开始时间 (nanoTime) |
| `lastBatchEndTime` | `volatile long` | 最近一批的结束时间 (nanoTime) |
| `lastBatchDurationMs` | `volatile long` | 最近一批耗时 (ms)，约等于 (end-start)/1_000_000 |
| `latencyWindow` | `SlidingWindow` | 滑动窗口，保留最近 50 个耗时样本 |
| `getLastBatchDurationMs()` | getter | 返回最近一批耗时 |
| `getP50LatencyMs()` | getter | 返回滑动窗口 P50 |
| `getP90LatencyMs()` | getter | 返回滑动窗口 P90 |
| `getP99LatencyMs()` | getter | 返回滑动窗口 P99 |

**修改位置**：在 `run()` 方法的 batch 循环中，起始处记录 `lastBatchStartTime`，结束后记录 `lastBatchEndTime`。

```java
// 在 AccountWorker.run() 中，补充两行代码：
// 位于 while(running) 循环内的批量处理前后

long batchStart = System.nanoTime();
// ... 批量读取和处理 ...
long batchEnd = System.nanoTime();
long durationMs = (batchEnd - batchStart) / 1_000_000;
this.lastBatchDurationMs = durationMs;
this.latencyWindow.add(durationMs);
```

MatchingEngineWorker 和 ClearingWorker 同样的修改模式，每处 3 行变更。

#### 4.3.2 CQ 堆积指标

通过 `QueueManager.getStats()` 直接获取：

```
CQ 堆积数 = lastIndex - currentIndex

account_input depth    = stats.getAccountInputLastIndex() - stats.getAccountInputCurrentIndex()
matching_input depth   = stats.getMatchingInputLastIndex() - stats.getMatchingInputCurrentIndex()
matching_output depth  = stats.getMatchingOutputLastIndex() - stats.getMatchingOutputCurrentIndex()
account_settlement depth = stats.getAccountSettlementLastIndex() - stats.getAccountSettlementCurrentIndex()
```

#### 4.3.3 GC 指标

通过 `java.lang.management.ManagementFactory` 获取：

```java
List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
// 遍历，找到 ZGC 的 GC 信息
// 从上次采集到本次：collectionCount 增量、collectionTime 增量
// 平均暂停时间 = timeDelta / countDelta
```

#### 4.3.4 最终采集数据结构

```java
class DiagnosticSnapshot {
    // Worker 延迟
    List<WorkerLatency> workerLatencies;   // 3 个 Worker

    // CQ 堆积
    List<CqDepth> cqDepths;                // 4 条 CQ

    // GC
    GcMetrics gcMetrics;

    // 系统上下文
    LifecycleManager.SystemStatus systemStatus;
    TradingPhase tradingPhase;
    long timestamp;
}

class WorkerLatency {
    String name;     // "AccountWorker" | "MatchingEngine" | "ClearingWorker"
    long p50, p90, p99;
    boolean running;
    boolean ready;
}

class CqDepth {
    String name;     // "account_input" | "matching_input" | "matching_output" | "account_settlement"
    long depth;
    Trend trend;     // RISING | FLAT | FALLING（与上次对比）
}

class GcMetrics {
    long totalPauseMs;
    int  pauseCount;
    long avgPauseMs;
    long maxPauseMs;
}
```

### 4.4 诊断规则

以下规则按优先级排列，高优先级规则先匹配：

```java
private List<DiagnosticResult> evaluate(DiagnosticSnapshot snap) {

    // ── Rule 1: CQ 堆积 P0 ──
    // 触发条件：任一 CQ depth > 2000 且 trend == RISING
    // 严重级别：P0
    // 定位：具体 CQ 名称
    // 原因："matching_output 堆积 2745 条且持续增长，下游清算处理能力不足"
    // 建议："检查 ClearingWorker 是否正常，考虑增大 CLEARING_BATCH_SIZE"

    // ── Rule 2: Worker 延迟 P0 ──
    // 触发条件：任一 Worker p99 > 200ms
    // 严重级别：P0
    // 定位：具体 Worker 名称
    // 原因："AccountWorker P99 延迟 340ms，超过 200ms 阈值"
    // 建议："检查该 Worker 是否为耗时操作卡住，考虑重启"

    // ── Rule 3: CQ 堆积 P1 ──
    // 触发条件：任一 CQ depth > 500 且 trend == RISING，未触发 P0
    // 严重级别：P1
    // 定位：具体 CQ 名称
    // 原因："account_input 堆积 623 条且持续增长"
    // 建议："观察下一周期是否缓解，如持续恶化将升级为 P0"

    // ── Rule 4: Worker 延迟 P1 ──
    // 触发条件：任一 Worker p99 > 50ms，未触发 P0
    // 严重级别：P1
    // 原因："MatchingEngine P99 延迟 89ms，超过 50ms 阈值"
    // 建议："关注该 Worker 处理性能"

    // ── Rule 5: 综合判定 ──
    // 触发条件：同时出现 Rule 3（CQ 堆积 P1）+ Rule 4（Worker 延迟 P1）
    //          且指向不同的 CQ/Worker（不是同一条链）
    // 升级为：P0
    // 原因："系统整体处理能力下降，多处堆积 + 多 Worker 延迟"
    // 建议："检查 GC 是否异常、磁盘 IO 是否达到瓶颈"

    // ── Rule 6: GC 异常 P2 ──
    // 触发条件：avgPauseMs > 5ms
    // 严重级别：P2
    // 原因："ZGC 平均暂停 12ms，超过 5ms 阈值"
    // 建议："检查堆内存使用率和 GC 配置"

    // ── Rule 7: 多周期异常聚合 ──
    // 触发条件：连续 3 次诊断均有 P1+ 告警，但未触发 P0
    // 升级为：P0
    // 原因："持续异常未缓解，可能已处于降级边缘"
    // 建议："人工介入检查系统状态"

    // ── 正常：诊断结果列表为空 ──
    // → 静默，不做任何输出
}
```

### 4.5 诊断结果推送

```java
// 诊断周期结束后，如果 results 不为空
for (DiagnosticResult r : results) {
    // 1. WebSocket 推送（管理端订阅 agent_alert 通道）
    AgentAlertMessage msg = new AgentAlertMessage();
    msg.setAgentName("OpsDiagnostic");
    msg.setAlertLevel(r.level);
    msg.setCheckType(r.ruleName);
    msg.setDetail(String.format("[%s] %s | 建议: %s", r.localizedCause, r.value, r.suggestion));
    msg.setTimestamp(System.currentTimeMillis());
    webSocketPushService.pushAgentAlert(msg);

    // 2. AlertService
    alertService.sendAlert(r.level.toAlertLevel(), "OpsDiagnostic",
        "[" + r.ruleName + "] " + r.localizedCause + " | value=" + r.value);
}

// 保留最近 60 次诊断结果（环形缓冲），供前端 admin 页面拉取
diagnosticHistory.add(snapshotWithResults);
if (diagnosticHistory.size() > 60) diagnosticHistory.poll();
```

### 4.6 降级路径

| 场景 | 行为 |
|------|------|
| Worker 的 latencyWindow 为空（刚启动） | 跳过该 Worker 的延迟阈值判断，不影响其他诊断 |
| QueueManager.getStats() 返回全 0 | 视为 CQ 尚未初始化，跳过 CQ 堆积检查 |
| GC MXBean 获取异常 | 跳过 GC 检查，不影响其他诊断 |
| Agent 自身异常 | 记录 error 日志，跳过本次诊断周期 |

---

## 5. 现有文件修改清单

### 5.1 需修改的文件

| 文件 | 修改类型 | 改动量 | 改动内容 |
|------|---------|--------|---------|
| `AccountWorker.java` | 方法内新增 | +8 行 | 新增 batch timing 字段 + run() 中记录耗时 + getter |
| `MatchingEngineWorker.java` | 方法内新增 | +8 行 | 同上 |
| `ClearingWorker.java` | 方法内新增 | +8 行 | 同上 |
| `WebSocketPushService.java` | 新增内部类 + 方法 | +25 行 | 新增 `AgentAlertMessage` 内部类和 `pushAgentAlert()` 方法 |
| `frontend/src/websocket/types.ts` | 类型枚举新增 | +2 行 | `ChannelType` 增加 `agent_alert`，`MessageType` 增加 `agent_alert` |
| `TradingConfig.java` | Bean 注册 | +8 行 | 注册 ConsistencyAuditAgent 和 OperationsDiagnosticAgent 的 bean |

### 5.2 不需要修改的文件

以下文件不需要做任何改动：

- `InMemoryAccountStore.java` —— 审计 Agent 只读引用
- `InMemoryPositionStore.java` —— 同上
- `InMemoryOrderStore.java` —— 同上
- `AlertService.java` —— 复用现有告警接口
- `QueueManager.java` —— 复用现有 `getStats()` 方法
- `LifecycleManager.java` —— Agent 只读引用
- `PhaseManager.java` —— Agent 通过 AgentContext 间接引用
- `WebSocketServer.java` —— `pushAgentAlert()` 复用现有的 `broadcast()` 方法
- 所有 `model/*.java` —— 只读
- 所有 `event/*.java` —— 不涉及
- 所有 `queue/*.java` 下的 CQ 实现类 —— 不涉及
- 所有前端组件文件 —— 仅类型定义 +2 行
- `HftTradingApplication.java` —— 不涉及

### 5.3 修改示例：AccountWorker.java

```java
// 在类字段区域新增（约 3 行）
private volatile long lastBatchDurationMs = 0;
private final SlidingWindow latencyWindow = new SlidingWindow(50);

// 在 run() 方法的 while(running) 循环中（约 3 行）
// 在批量读取 processing 前：
long batchStart = System.nanoTime();
// ... 现有批量处理逻辑（不做任何改动）...
long batchEnd = System.nanoTime();
long durationMs = (batchEnd - batchStart) / 1_000_000;
this.lastBatchDurationMs = durationMs;
this.latencyWindow.add(durationMs);

// 新增 getter 方法（约 2 行）
public long getLastBatchDurationMs() { return lastBatchDurationMs; }
public long getP99LatencyMs() { return latencyWindow.getPercentile(99); }
```

MatchingEngineWorker 和 ClearingWorker 的修改模式完全一致。

---

## 6. 实施顺序与工作量

### 6.1 推荐实施顺序

```
Step 1: SlidingWindow.java         [纯工具类，无依赖，10 分钟]
    ↓
Step 2: AgentContext.java          [无依赖，10 分钟]
    ↓
Step 3: 3 个 Worker 的 timing 修改 [相互独立，可并行，15 分钟/个]
    ↓
Step 4: WebSocketPushService.java + types.ts  [相互独立，可并行，15 分钟]
    ↓
Step 5: ConsistencyAuditAgent.java [依赖 Step 2 + Step 4，30 分钟]
    ↓
Step 6: OperationsDiagnosticAgent.java [依赖 Step 2 + Step 3 + Step 4，30 分钟]
    ↓
Step 7: TradingConfig.java 注册    [2 分钟]
    ↓
Step 8: 编译验证 + 启动测试        [10 分钟]
```

### 6.2 工作量汇总

| 步骤 | 文件 | 类型 | 预估时间 |
|------|------|------|---------|
| 1 | SlidingWindow.java | 新增 | 10 分钟 |
| 2 | AgentContext.java | 新增 | 10 分钟 |
| 3a | AccountWorker.java 修改 | 修改 | 15 分钟 |
| 3b | MatchingEngineWorker.java 修改 | 修改 | 15 分钟 |
| 3c | ClearingWorker.java 修改 | 修改 | 15 分钟 |
| 4a | WebSocketPushService.java 修改 | 修改 | 15 分钟 |
| 4b | frontend types.ts 修改 | 修改 | 2 分钟 |
| 5 | ConsistencyAuditAgent.java | 新增 | 30 分钟 |
| 6 | OperationsDiagnosticAgent.java | 新增 | 30 分钟 |
| 7 | TradingConfig.java 修改 | 修改 | 2 分钟 |
| 8 | 编译 + 测试 | 验证 | 10 分钟 |
| **总计** | **11 次操作** | | **~2.5 小时** |

---

## 附录 A：SlidingWindow.java 设计

```java
package com.hft.trading.util;

/**
 * 滑动窗口百分位计算
 *
 * 固定容量环形缓冲，维护一个有序副本用于百分位查询。
 * add() O(1)，getPercentile() 每次 O(n log n)，
 * 但 n=50 时可忽略。
 */
public class SlidingWindow {

    private final long[] buffer;
    private final long[] sorted;
    private int index = 0;
    private int size = 0;
    private final int capacity;

    public SlidingWindow(int capacity) {
        this.capacity = capacity;
        this.buffer = new long[capacity];
        this.sorted = new long[capacity];
    }

    public synchronized void add(long value) {
        buffer[index] = value;
        index = (index + 1) % capacity;
        if (size < capacity) size++;
    }

    public synchronized long getPercentile(int percentile) {
        if (size == 0) return 0;
        // 复制到 sorted 数组并排序
        // 计算位置并返回
    }
}
```

## 附录 B：AgentAlertMessage（WebSocket 推送数据结构）

```java
// 新增在 WebSocketPushService.java 中
public static class AgentAlertMessage {
    private String agentName;      // "ConsistencyAudit" | "OpsDiagnostic"
    private String alertLevel;     // "P0" | "P1" | "P2"
    private String checkType;      // "ACCOUNT_INVARIANT" | "CQ_BACKPRESSURE" | ...
    private Long accountId;        // 仅审计 Agent 使用
    private String symbol;         // 仅审计 Agent 使用
    private String detail;         // 人类可读的描述
    private String suggestion;     // 操作建议（仅运维诊断）
    private long timestamp;        // 事件时间戳
}
```
