# 系统健康监控 Skill

当用户需要设计和实现交易系统的健康度监控体系时使用此skill。

## 触发场景
- 监控指标设计
- 告警规则配置
- 性能仪表盘开发
- 系统健康检查

## 核心原则

### 1. 核心监控指标

#### 系统层指标
```java
public class SystemMetrics {
    // JVM 指标
    private double heapUsedPercent;      // 堆内存使用率
    private long gcPauseMs;              // GC 暂停时间
    private int threadCount;             // 线程数

    // Chronicle Queue 指标
    private long queueLatencyNs;         // 队列写入延迟
    private long queueSize;              // 队列积压
    private long indexCount;             // 索引数量

    // 系统指标
    private double cpuUsedPercent;       // CPU 使用率
    private long networkBytes;           // 网络吞吐
    private long diskIOBytes;            // 磁盘 IO
}
```

#### 业务层指标
```java
public class BusinessMetrics {
    // 订单指标
    private long orderCount;             // 订单总数
    private long orderLatencyUs;         // 订单处理延迟
    private double orderSuccessRate;     // 订单成功率
    private long rejectedOrderCount;     // 拒单数量

    // 撮合指标
    private long tradeCount;             // 成交数量
    private long matchLatencyUs;         // 撮合延迟
    private long orderBookDepth;         // 订单簿深度
    private double matchRate;            // 撮合率

    // 清算指标
    private long clearingLatencyMs;      // 清算延迟
    private long pendingClearing;        // 待清算数量
    private double clearingSuccessRate;  // 清算成功率

    // 账户指标
    private long activeAccounts;         // 活跃账户数
    private long totalBalance;           // 总资金
    private long marginUsed;             // 已用保证金
}
```

### 2. 监控架构

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Metrics    │────▶│  Prometheus │────▶│  Grafana    │
│  Collector  │     │  (存储)      │     │  (可视化)   │
└─────────────┘     └─────────────┘     └─────────────┘
       │                                        │
       │                                        ▼
       │                                 ┌─────────────┐
       └────────────────────────────────▶│  AlertMgr   │
                                         │  (告警)     │
                                         └─────────────┘
```

### 3. Grafana 仪表盘设计

#### 系统概览面板
- **系统健康度**: 综合评分 (0-100)
- **关键指标**: 订单TPS、延迟P99、成功率
- **资源使用**: CPU、内存、磁盘、网络
- **告警状态**: 当前告警数量和级别

#### 交易业务面板
- **订单统计**: 实时订单量、成功率、拒单原因分布
- **撮合性能**: 撮合延迟分布、订单簿深度变化
- **清算状态**: 清算进度、待处理数量
- **账户概览**: 活跃账户、资金分布

#### Chronicle Queue 面板
- **队列状态**: 各队列积压情况
- **写入性能**: 写入延迟、吞吐量
- **索引状态**: 索引文件大小、数量

### 4. 告警规则配置

```yaml
groups:
  - name: trading_system
    rules:
      # 系统告警
      - alert: HighMemoryUsage
        expr: jvm_heap_used_percent > 85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "JVM 堆内存使用率过高"

      - alert: HighGCPause
        expr: jvm_gc_pause_seconds_sum > 1
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "GC 暂停时间过长"

      # 业务告警
      - alert: HighOrderLatency
        expr: order_latency_p99_us > 10000
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "订单处理延迟过高"

      - alert: LowSuccessRate
        expr: order_success_rate < 0.95
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "订单成功率下降"

      - alert: QueueBacklog
        expr: chronicle_queue_size > 100000
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "Chronicle 队列积压"
```

### 5. 健康检查接口

```java
@RestController
public class HealthController {

    @GetMapping("/health")
    public HealthStatus health() {
        return HealthStatus.builder()
            .status(checkOverallStatus())
            .components(Map.of(
                "matchingEngine", checkMatchingEngine(),
                "clearingService", checkClearingService(),
                "persistence", checkPersistence(),
                "chronicleQueue", checkChronicleQueue()
            ))
            .build();
    }

    @GetMapping("/ready")
    public ReadyStatus ready() {
        // 检查系统是否准备好接收流量
    }

    @GetMapping("/live")
    public LiveStatus live() {
        // 检查系统是否存活
    }
}
```

### 6. 日志监控

```java
// 使用 Logback + ELK
<appender name="JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>traceId</includeMdcKeyName>
        <includeMdcKeyName>orderId</includeMdcKeyName>
        <includeMdcKeyName>accountId</includeMdcKeyName>
    </encoder>
</appender>
```

## 输出要求
- 完整的监控指标定义
- Prometheus 配置文件
- Grafana Dashboard JSON
- 告警规则配置
- 健康检查接口实现
