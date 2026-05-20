# A股交易系统技术方案

> 版本: v1.1
> 日期: 2026-05-17
> 架构师: Trading System Orchestrator
> 状态: **需求已确认，待开发**

---

## 0. 需求确认清单

| 需求项 | 确认结果 |
|--------|---------|
| **行情数据源** | 新浪财经爬取 (免费) |
| **期货品种** | IF股指期货 |
| **期权品种** | 50ETF期权 |
| **夜盘交易** | 不需要 |
| **账户体系** | 单一账户，无子账户 |
| **服务器配置** | 阿里云 8C16G |
| **用户认证** | 简单认证即可 |
| **数据加密** | 不需要 |
| **审计日志** | 不需要 |

---

## 1. 系统概述

### 1.1 设计目标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| **部署模式** | 单机单JVM | 无分布式复杂度 |
| **报撤单吞吐量** | ≥ 100,000 TPS | 每秒处理订单+撤单总量 |
| **端到端延迟** | ≤ 15ms (P99) | 从订单接收到成交回报 |
| **账户规模** | > 1,000个 | 支持大规模账户并发 |
| **行情源** | 新浪财经爬取 | 免费行情数据 |
| **前端** | 完整专业界面 | K线图+订单簿+下单面板 |
| **持久化** | 内存+异步落库 | H2磁盘模式异步批量写入 |
| **服务器** | 阿里云 8C16G | 8核CPU，16GB内存 |

### 1.2 业务范围

- **股票**: A股主板、创业板、科创板
- **期货**: IF股指期货 (沪深300)
- **期权**: 50ETF期权

### 1.3 核心约束

```
┌─────────────────────────────────────────────────────────────┐
│                     核心约束条件                              │
├─────────────────────────────────────────────────────────────┤
│ 1. 单机单JVM - 无分布式组件，无网络RPC开销                   │
│ 2. 纯内存交易 - Chronicle Queue + 内存订单簿                │
│ 3. 异步持久化 - 不阻塞交易主链路                             │
│ 4. 低延迟优先 - 所有设计决策以延迟为第一优先级               │
│ 5. 服务器限制 - 8核CPU，需合理分配线程                       │
│ 6. 无夜盘交易 - 仅支持A股交易时段                            │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 系统架构设计

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              单JVM进程                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                          接入层 (Netty)                               │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │  │
│  │  │ WebSocket   │  │  REST API   │  │  FIX协议    │  │  行情接入    │  │  │
│  │  │ (前端推送)   │  │ (HTTP接口)  │  │  (可选)     │  │  (外部源)    │  │  │
│  │  └──────┬──────┘  └──────┬──────┘  └─────────────┘  └──────┬──────┘  │  │
│  └─────────┼────────────────┼──────────────────────────────────┼────────┘  │
│            │                │                                   │           │
│            ▼                ▼                                   ▼           │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                        订单网关 (OrderGateway)                         │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │ • 订单接收与解析                                                  │  │  │
│  │  │ • 会话管理 (1000+账户连接)                                       │  │  │
│  │  │ • 风控检查 (读Chronicle Map)                                    │  │  │
│  │  │ • 发送冻结请求到队列                                             │  │  │
│  │  │ • 写入订单到Chronicle Queue                                     │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│                                    ▼ Chronicle Queue (订单事件)             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                        撮合引擎 (MatchingEngine)                       │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                    内存订单簿 (OrderBook)                         │  │  │
│  │  │  ┌─────────────────────┐  ┌─────────────────────┐               │  │  │
│  │  │  │   Bids (买单)        │  │   Asks (卖单)       │               │  │  │
│  │  │  │   TreeMap<Price,    │  │   TreeMap<Price,    │               │  │  │
│  │  │  │   LinkedList<Order>>│  │   LinkedList<Order>>│               │  │  │
│  │  │  │   (价格降序)         │  │   (价格升序)        │               │  │  │
│  │  │  └─────────────────────┘  └─────────────────────┘               │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │                                                                        │  │
│  │  撮合线程模型:                                                          │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │  单线程Disruptor模式 (每个标的独立线程)                           │  │  │
│  │  │  - 避免锁竞争                                                    │  │  │
│  │  │  - CPU亲和性绑定                                                 │  │  │
│  │  │  - 无界内存访问                                                  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│                                    ▼ Chronicle Queue (成交事件)             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                    清算线程 (ClearingThread)                           │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │ • 单一写入者模式 (Chronicle Map限制)                             │  │  │
│  │  │ • 处理冻结请求队列                                               │  │  │
│  │  │ • 处理成交清算                                                   │  │  │
│  │  │ • 写入Chronicle Map (账户/持仓)                                  │  │  │
│  │  │ • 推送WebSocket消息                                              │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│                                    ▼                                        │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                      Chronicle Map (内存映射+持久化)                   │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │  accounts.dat   Key=accountId, Value=AccountState               │  │  │
│  │  │  positions.dat  Key=(accountId<<32)|symbolCode                  │  │  │
│  │  │  • 内存映射: 读写即内存操作                                      │  │  │
│  │  │  • 磁盘持久化: 自动同步到磁盘                                     │  │  │
│  │  │  • 多读取者: 风控服务直接读取                                     │  │  │
│  │  │  • 单写入者: 清算线程独占写入                                     │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│                                    ▼ 异步批量写入                           │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                      H2 Database (历史查询)                            │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │  • 磁盘持久化模式                                                │  │  │
│  │  │  • 仅用于历史查询、报表                                          │  │  │
│  │  │  • 不参与主链路                                                  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                        风控服务 (RiskControl)                          │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │ • 读Chronicle Map (多读取者安全)                                 │  │  │
│  │  │ • 资金充足性检查 O(1)                                            │  │  │
│  │  │ • 持仓限额检查                                                  │  │  │
│  │  │ • 涨跌停价格校验                                                │  │  │
│  │  │ • 自成交检测                                                    │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                        行情服务 (MarketDataService)                     │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │ • 内部模拟行情生成器                                            │  │  │
│  │  │ • 外部行情接入适配器 (Wind/同花顺/交易所)                        │  │  │
│  │  │ • 行情数据缓存 (Redis/内存)                                     │  │  │
│  │  │ • 行情推送 (WebSocket广播)                                      │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                        监控服务 (MonitorService)                       │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │ • Prometheus指标采集端点                                        │  │  │
│  │  │ • 健康检查接口                                                  │  │  │
│  │  │ • 性能统计 (延迟/吞吐量)                                        │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

                                    │
                                    ▼ WebSocket推送
              ┌─────────────────────────────────────────────┐
              │              前端交易界面                    │
              │  ┌─────────────────────────────────────┐   │
              │  │  React/Vue + WebSocket客户端        │   │
              │  │  • K线图 (TradingView)             │   │
              │  │  • 订单簿                           │   │
              │  │  • 下单面板                         │   │
              │  │  • 持仓/资金管理                    │   │
              │  └─────────────────────────────────────┘   │
              └─────────────────────────────────────────────┘
```

### 2.2 核心设计原则

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          核心设计原则                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 【无锁设计】                                                            │
│     - 每个标的独立线程处理，无跨标的锁竞争                                   │
│     - 使用Disruptor无锁队列替代传统阻塞队列                                  │
│     - 账户数据使用ThreadLocal或CAS更新                                      │
│                                                                             │
│  2. 【零拷贝】                                                              │
│     - Chronicle Queue原生支持零拷贝序列化                                   │
│     - 使用ByteBuffer直接操作内存                                            │
│     - 避免对象创建和复制                                                    │
│                                                                             │
│  3. 【内存优先】                                                            │
│     - 所有交易数据常驻内存                                                  │
│     - 账户/持仓使用原生类型数组                                              │
│     - 避免GC压力：对象池化                                                  │
│                                                                             │
│  4. 【异步持久化】                                                          │
│     - 主链路不等待数据库IO，数据库不参与交易流程                              │
│     - Chronicle Queue/Map 实时持久化，进程崩溃数据不丢失                      │
│     - H2 异步批量写入，仅用于历史查询和报表                                   │
│     - 写入失败不影响交易                                                    │
│                                                                             │
│  5. 【CPU亲和性】                                                           │
│     - 关键线程绑定固定CPU核心                                               │
│     - 减少上下文切换                                                        │
│     - 避免CPU缓存失效                                                       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 性能分析与达标路径

### 3.1 性能目标分解

```
端到端延迟 ≤ 15ms 分解:
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  [前端] ──WebSocket──▶ [网关] ──Chronicle──▶ [撮合] ──Chronicle──▶ [清算]  │
│                          │        Queue       │        Queue       │        │
│                          ▼                   ▼                   ▼        │
│                      网络解析            撮合计算            资金计算      │
│                                                                             │
│  延迟分解:                                                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 1. WebSocket接收解析:        1-2ms                                   │   │
│  │ 2. Chronicle Queue写入:       0.01ms (10μs)                          │   │
│  │ 3. 撮合引擎处理:              0.01ms (10μs)                          │   │
│  │ 4. Chronicle Queue写入:       0.01ms (10μs)                          │   │
│  │ 5. 清算服务处理:              0.05ms (50μs)                          │   │
│  │ 6. WebSocket推送返回:         1-2ms                                   │   │
│  │ 7. 其他开销:                  2-3ms                                   │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ 总计:                        4-8ms (P99 < 15ms)                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  吞吐量目标:                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ • 单标的撮合:  500,000+ TPS (单线程)                                  │   │
│  │ • 多标的并发:  100,000+ TPS (100个标的)                              │   │
│  │ • Chronicle Q: 1,000,000+ msg/s                                      │   │
│  │ • WebSocket:    100,000+ msg/s                                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 关键性能优化点

| 组件 | 优化策略 | 预期性能 |
|------|---------|---------|
| **Chronicle Queue** | 零拷贝、内存映射、批量写入 | 写入延迟 < 10μs |
| **订单簿** | TreeMap + Long2ObjectHashMap、无锁读取 | 撮合延迟 < 10μs |
| **账户管理** | 原生类型数组、预分配内存 | 查询 O(1) < 1μs |
| **WebSocket** | Netty原生、批量推送 | 推送延迟 < 2ms |
| **持久化** | H2磁盘模式 + Chronicle实时持久化 | 数据安全、不影响主链路 |

### 3.3 性能瓶颈与解决方案

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        性能瓶颈分析                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  瓶颈1: GC暂停                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 问题: 大量对象创建导致Young GC频繁                                    │   │
│  │ 解决:                                                                 │   │
│  │   • 使用对象池 (Order/Trade对象复用)                                 │   │
│  │   • 使用原生类型集合 (Agrona)                                        │   │
│  │   • ZGC配置: 暂停时间 < 1ms                                          │   │
│  │   • 预分配内存避免运行时分配                                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  瓶颈2: 锁竞争                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 问题: 多线程访问共享资源                                              │   │
│  │ 解决:                                                                 │   │
│  │   • 每个标的独立线程 (无跨标的竞争)                                   │   │
│  │   • Disruptor无锁队列                                                │   │
│  │   • 账户数据分片 (按accountId取模)                                   │   │
│  │   • CAS替代锁                                                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  瓶颈3: 内存带宽                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 问题: 大量数据在内存中拷贝                                            │   │
│  │ 解决:                                                                 │   │
│  │   • Chronicle Queue零拷贝                                            │   │
│  │   • 直接操作ByteBuffer                                               │   │
│  │   • 避免对象序列化                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  瓶颈4: 网络IO                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 问题: WebSocket推送大量小消息                                         │   │
│  │ 解决:                                                                 │   │
│  │   • 消息批量打包推送                                                  │   │
│  │   • Netty Epoll原生传输                                              │   │
│  │   • 连接复用                                                         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. 核心组件详细设计

### 4.1 Chronicle Queue 配置

```java
/**
 * Chronicle Queue 配置 - 低延迟消息传递
 */
public class ChronicleQueueConfig {

    // 订单事件队列 (单生产者单消费者)
    public SingleChronicleQueue createOrderQueue(String basePath) {
        return SingleChronicleQueueBuilder
            .binary(basePath + "/orders")
            .rollCycle(RollCycles.DAILY)           // 每天滚动
            .indexSpacing(256)                      // 索引间隔
            .indexCount(8)                          // 索引数量
            .blockSize(64L * 1024 * 1024)          // 64MB块大小
            .create();
    }

    // 成交事件队列 (单生产者多消费者)
    public SingleChronicleQueue createTradeQueue(String basePath) {
        return SingleChronicleQueueBuilder
            .binary(basePath + "/trades")
            .rollCycle(RollCycles.DAILY)
            .indexSpacing(256)
            .indexCount(8)
            .blockSize(64L * 1024 * 1024)
            .create();
    }

    // 持久化队列 (单生产者单消费者)
    public SingleChronicleQueue createPersistQueue(String basePath) {
        return SingleChronicleQueueBuilder
            .binary(basePath + "/persist")
            .rollCycle(RollCycles.MINUTES)         // 每分钟滚动
            .indexSpacing(64)
            .indexCount(4)
            .blockSize(128L * 1024 * 1024)         // 128MB
            .create();
    }

    // Chronicle Map 实时持久化配置
    public <K, V> ChronicleMap<K, V> createPersistentMap(
            String filePath, Class<K> keyClass, Class<V> valueClass, long entries) {
        return ChronicleMap
            .of(keyClass, valueClass)
            .name(filePath)
            .entries(entries)
            .averageKeySize(16)
            .createPersistedTo(new File(filePath + ".dat"));  // 磁盘持久化
    }
}

/**
 * 订单事件定义 - 零拷贝序列化
 */
public interface OrderEvent {
    // 使用原生类型，避免对象装箱
    long orderId();
    void orderId(long orderId);

    long accountId();
    void accountId(long accountId);

    int symbolCode();      // 使用int编码代替String
    void symbolCode(int code);

    long price();
    void price(long price);

    long quantity();
    void quantity(long qty);

    byte direction();      // 0=买入, 1=卖出
    void direction(byte dir);

    byte orderType();      // 0=限价, 1=市价, 2=FAK, 3=FOK
    void orderType(byte type);

    long timestamp();
    void timestamp(long ts);
}
```

### 4.2 撮合引擎设计

```java
/**
 * 撮合引擎核心 - 单线程处理每个标的
 */
public class MatchingEngine {

    // 订单簿: 标的代码 -> OrderBook
    private final Int2ObjectHashMap<OrderBook> orderBooks;

    // 标的代码 -> 处理线程
    private final Int2ObjectHashMap<MatchingThread> matchingThreads;

    // Chronicle Queue 引用
    private final ChronicleQueue orderQueue;
    private final ChronicleQueue tradeQueue;

    /**
     * 订单簿数据结构
     */
    public class OrderBook {
        private final int symbolCode;

        // 买单: 价格降序 (高价在前)
        private final TreeMap<Long, LinkedList<Order>> bids =
            new TreeMap<>(Comparator.reverseOrder());

        // 卖单: 价格升序 (低价在前)
        private final TreeMap<Long, LinkedList<Order>> asks =
            new TreeMap<>();

        // 快速查找: orderId -> Order
        private final Long2ObjectHashMap<Order> orderMap =
            new Long2ObjectHashMap<>();

        // 价格档位缓存 (Level-2)
        private final Long2ObjectHashMap<PriceLevel> priceLevels =
            new Long2ObjectHashMap<>();

        /**
         * 撮合算法 - 价格优先时间优先
         * 时间复杂度: O(log n) 价格查找 + O(1) 队列操作
         */
        public List<Trade> match(Order incomingOrder) {
            List<Trade> trades = new ArrayList<>();

            if (incomingOrder.direction() == Direction.BUY) {
                // 买入单: 与卖单撮合
                trades = matchWithAsks(incomingOrder);
                if (incomingOrder.getRemainingQty() > 0) {
                    addToBids(incomingOrder);
                }
            } else {
                // 卖出单: 与买单撮合
                trades = matchWithBids(incomingOrder);
                if (incomingOrder.getRemainingQty() > 0) {
                    addToAsks(incomingOrder);
                }
            }

            return trades;
        }

        /**
         * 与卖单撮合
         */
        private List<Trade> matchWithAsks(Order buyOrder) {
            List<Trade> trades = new ArrayList<>();

            while (buyOrder.getRemainingQty() > 0 && !asks.isEmpty()) {
                // 获取最低卖价
                Map.Entry<Long, LinkedList<Order>> bestAsk = asks.firstEntry();
                if (bestAsk.getKey() > buyOrder.price()) {
                    break; // 价格不匹配
                }

                LinkedList<Order> askOrders = bestAsk.getValue();
                while (!askOrders.isEmpty() && buyOrder.getRemainingQty() > 0) {
                    Order sellOrder = askOrders.peekFirst();

                    // 计算成交数量
                    long matchQty = Math.min(
                        buyOrder.getRemainingQty(),
                        sellOrder.getRemainingQty()
                    );

                    // 生成成交记录
                    Trade trade = Trade.builder()
                        .tradeId(generateTradeId())
                        .buyOrderId(buyOrder.orderId())
                        .sellOrderId(sellOrder.orderId())
                        .price(bestAsk.getKey())
                        .quantity(matchQty)
                        .timestamp(System.nanoTime())
                        .build();
                    trades.add(trade);

                    // 更新订单数量
                    buyOrder.fill(matchQty);
                    sellOrder.fill(matchQty);

                    if (sellOrder.getRemainingQty() == 0) {
                        askOrders.pollFirst();
                        orderMap.remove(sellOrder.orderId());
                    }
                }

                if (askOrders.isEmpty()) {
                    asks.remove(bestAsk.getKey());
                    priceLevels.remove(bestAsk.getKey());
                }
            }

            return trades;
        }
    }

    /**
     * 撮合线程 - 每个标的独立线程
     */
    public class MatchingThread implements Runnable {
        private final int symbolCode;
        private final OrderBook orderBook;
        private final ExcerptTailer orderTailer;
        private final ExcerptAppender tradeAppender;

        // CPU亲和性绑定
        private void bindCpu(int cpuCore) {
            // 使用JNA绑定CPU核心
        }

        @Override
        public void run() {
            while (running) {
                // 从Chronicle Queue读取订单
                orderTailer.readDocument(wire -> {
                    OrderEvent event = wire.read();

                    // 执行撮合
                    Order order = convertToOrder(event);
                    List<Trade> trades = orderBook.match(order);

                    // 写入成交事件
                    for (Trade trade : trades) {
                        writeTradeEvent(trade);
                    }

                    // 推送订单状态更新
                    pushOrderUpdate(order);
                });
            }
        }
    }
}
```

### 4.3 账户/持仓存储架构设计

> **核心问题**: Chronicle Map 并发写入问题
>
> 如果订单网关和清算线程都直接写入 Chronicle Map，会出现数据覆盖问题：
> ```
> 线程A (订单网关冻结):     线程B (清算线程扣款):
> get() → available=1000    get() → available=1000
> available -= 500          available -= 300
> put() → available=500     put() → available=700  ← 覆盖!
> ```

#### 解决方案: 所有写操作通过单一清算线程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    账户/持仓存储架构                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Chronicle Map 本身就是内存映射文件，读写性能极高                            │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                 Chronicle Map (内存映射 + 磁盘持久化)                │   │
│  │                                                                      │   │
│  │  accounts.dat                                                        │   │
│  │  ┌─────────────────────────────────────────────────────────────┐    │   │
│  │  │ Key: accountId (Long)                                       │    │   │
│  │  │ Value: AccountState                                         │    │   │
│  │  │   - balance: long                                           │    │   │
│  │  │   - available: long                                         │    │   │
│  │  │   - frozen: long                                            │    │   │
│  │  │   - margin: long                                            │    │   │
│  │  │   - updateTime: long                                        │    │   │
│  │  └─────────────────────────────────────────────────────────────┘    │   │
│  │                                                                      │   │
│  │  positions.dat                                                       │   │
│  │  ┌─────────────────────────────────────────────────────────────┐    │   │
│  │  │ Key: (accountId << 32) | symbolCode (Long)                  │    │   │
│  │  │ Value: PositionState                                        │    │   │
│  │  │   - quantity: long                                          │    │   │
│  │  │   - available: long                                         │    │   │
│  │  │   - frozen: long                                            │    │   │
│  │  │   - avgPrice: long                                          │    │   │
│  │  │   - updateTime: long                                        │    │   │
│  │  └─────────────────────────────────────────────────────────────┘    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【并发访问模式 - 单写入者设计】                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                      │   │
│  │  核心原则: 所有写操作都通过清算线程，其他服务只读取                   │   │
│  │                                                                      │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │ 订单网关 (只读取 + 发送请求):                                 │   │   │
│  │  │                                                              │   │   │
│  │  │ // 1. 风控检查 - 读取 Chronicle Map                          │   │   │
│  │  │ AccountState account = accountMap.get(accountId);           │   │   │
│  │  │ if (account.getAvailable() >= amount) {                      │   │   │
│  │  │                                                              │   │   │
│  │  │   // 2. 发送冻结请求到队列 (不直接写入 Chronicle Map)        │   │   │
│  │  │   freezeQueue.write(FreezeRequest.of(accountId, amount));   │   │   │
│  │  │                                                              │   │   │
│  │  │   // 3. 等待清算线程处理并返回结果                            │   │   │
│  │  │   FreezeResult result = waitForResult(orderId);             │   │   │
│  │  │   if (result.success) {                                      │   │   │
│  │  │     // 4. 冻结成功，写入订单队列                              │   │   │
│  │  │     orderQueue.write(order);                                │   │   │
│  │  │   } else {                                                   │   │   │
│  │  │     return "资金不足";                                        │   │   │
│  │  │   }                                                          │   │   │
│  │  │ }                                                            │   │   │
│  │  └─────────────────────────────────────────────────────────────┘   │   │
│  │                                                                      │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │ 清算线程 (唯一写入者):                                        │   │   │
│  │  │                                                              │   │   │
│  │  │ while (running) {                                            │   │   │
│  │  │   // 1. 处理冻结请求 (优先)                                   │   │   │
│  │  │   processFreezeRequests();                                   │   │   │
│  │  │                                                              │   │   │
│  │  │   // 2. 处理成交清算                                          │   │   │
│  │  │   processTrades();                                           │   │   │
│  │  │                                                              │   │   │
│  │  │   // 3. 处理撤单解冻                                          │   │   │
│  │  │   processCancelRequests();                                   │   │   │
│  │  │ }                                                            │   │   │
│  │  └─────────────────────────────────────────────────────────────┘   │   │
│  │                                                                      │   │
│  │  关键点:                                                             │   │
│  │  • 订单网关: 只读取 Chronicle Map，发送请求到队列                    │   │
│  │  • 清算线程: 单线程处理所有写操作，无并发冲突                        │   │
│  │  • 冻结流程: 同步等待结果，保证资金安全                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 数据流设计

```
订单请求流程 (所有写操作通过清算线程):
┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│ 订单网关  │ ──▶ │ 风控检查  │ ──▶ │ 发送冻结  │ ──▶ │ 等待结果  │
└──────────┘     └──────────┘     └──────────┘     └──────────┘
                      │                │                │
                      ▼                ▼                ▼
              读Chronicle Map    写入冻结请求队列    阻塞等待
             (检查资金充足)      (不直接写Map)      (超时返回失败)
                                           │
                                           ▼
                                    ┌──────────────┐
                                    │   清算线程   │
                                    │  处理冻结请求 │
                                    │  写入Map     │
                                    │  返回结果    │
                                    └──────────────┘
                                           │
                                           ▼
                                    冻结成功 → 写入订单队列
                                    冻结失败 → 返回错误

成交清算流程:
┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│ 撮合引擎  │ ──▶ │ Trade Q  │ ──▶ │ 清算线程  │ ──▶ │ WebSocket│
└──────────┘     └──────────┘     └──────────┘     └──────────┘
                                       │
                                       ▼
                              ┌────────────────┐
                              │ 解冻+扣款      │
                              │ 更新持仓        │
                              │ 写入Chronicle  │
                              │    Map         │
                              │ (唯一写入者)   │
                              └────────────────┘
```

#### 代码实现

```java
/**
 * Chronicle Map 配置 - 账户状态
 */
public class ChronicleMapConfig {

    public ChronicleMap<Long, AccountState> createAccountMap(String basePath) {
        return ChronicleMap
            .of(Long.class, AccountState.class)
            .name("accounts")
            .entries(10000)
            .averageValueSize(64)
            .createPersistedTo(new File(basePath + "/accounts.dat"));
    }

    public ChronicleMap<Long, PositionState> createPositionMap(String basePath) {
        return ChronicleMap
            .of(Long.class, PositionState.class)
            .name("positions")
            .entries(100000)
            .averageValueSize(64)
            .createPersistedTo(new File(basePath + "/positions.dat"));
    }
}

/**
 * 账户状态 - Chronicle Map Value
 */
public interface AccountState {
    long getBalance();
    void setBalance(long balance);

    long getAvailable();
    void setAvailable(long available);

    long getFrozen();
    void setFrozen(long frozen);

    long getMargin();
    void setMargin(long margin);

    long getUpdateTime();
    void setUpdateTime(long time);
}

/**
 * 订单网关 - 使用 compute 原子冻结
 */
public class OrderGateway {

    private final ChronicleMap<Long, AccountState> accountMap;
    private final ChronicleMap<Long, PositionState> positionMap;
    private final ChronicleQueue orderQueue;

    /**
     * 提交订单 - 风控检查 + compute 原子冻结
     */
    public OrderResult submitOrder(OrderRequest request) {
        // 1. 参数校验
        if (!validateOrder(request)) {
            return OrderResult.reject("参数错误");
        }

        // 2. 风控检查 - 读取 Chronicle Map
        if (!riskCheck(request)) {
            return OrderResult.reject("风控拒绝");
        }

        // 3. 使用 compute 原子冻结资金/持仓
        long orderId = generateOrderId();
        boolean freezeSuccess;

        if (request.getDirection() == Direction.BUY) {
            long amount = request.getPrice() * request.getQuantity();
            freezeSuccess = freezeFundWithCompute(request.getAccountId(), amount);
        } else {
            freezeSuccess = freezePositionWithCompute(request.getAccountId(), request.getSymbolCode(), request.getQuantity());
        }

        if (!freezeSuccess) {
            return OrderResult.reject("资金/持仓不足");
        }

        // 4. 冻结成功，写入订单队列
        writeOrderQueue(orderId, request);
        return OrderResult.success(orderId);
    }

    /**
     * 使用 compute 原子冻结资金
     * compute 方法保证原子性：在 entry 级别加锁，读取-修改-写入在同一个锁保护下完成
     */
    private boolean freezeFundWithCompute(long accountId, long amount) {
        AtomicBoolean success = new AtomicBoolean(false);

        accountMap.compute(accountId, (key, account) -> {
            if (account == null || account.getAvailable() < amount) {
                success.set(false);
                return account;  // 不修改，返回原值
            }

            // 原子修改
            account.setAvailable(account.getAvailable() - amount);
            account.setFrozen(account.getFrozen() + amount);
            account.setUpdateTime(System.currentTimeMillis());
            success.set(true);
            return account;
        });

        return success.get();
    }

    /**
     * 使用 compute 原子冻结持仓
     */
    private boolean freezePositionWithCompute(long accountId, int symbolCode, long quantity) {
        long key = (accountId << 32) | symbolCode;
        AtomicBoolean success = new AtomicBoolean(false);

        positionMap.compute(key, (key2, position) -> {
            if (position == null || position.getAvailable() < quantity) {
                success.set(false);
                return position;
            }

            position.setAvailable(position.getAvailable() - quantity);
            position.setFrozen(position.getFrozen() + quantity);
            position.setUpdateTime(System.currentTimeMillis());
            success.set(true);
            return position;
        });

        return success.get();
    }

    /**
     * 风控检查 - 读取 Chronicle Map
     */
    private boolean riskCheck(OrderRequest request) {
        if (request.getDirection() == Direction.BUY) {
            AccountState account = accountMap.get(request.getAccountId());
            if (account == null) return false;
            return account.getAvailable() >= request.getPrice() * request.getQuantity();
        } else {
            long key = (request.getAccountId() << 32) | request.getSymbolCode();
            PositionState position = positionMap.get(key);
            if (position == null) return false;
            return position.getAvailable() >= request.getQuantity();
        }
    }
}

/**
 * 进度状态 - 存储各队列的处理进度
 *
 * 存储位置: Chronicle Map (progress.dat)
 * 持久化: 实时写入磁盘，重启后恢复
 */
public interface ProgressState {
    String getKey();           // 队列标识: "trade_queue_index", "cancel_queue_index"
    long getIndex();           // 已处理的最大 index
    void setIndex(long index);
    long getUpdateTime();      // 更新时间
    void setUpdateTime(long time);
}

/**
 * 已处理成交记录 - 用于幂等检查
 *
 * 存储位置: Chronicle Map (processed_trades.dat)
 * Key: tradeId (Long)
 * Value: ProcessedTradeState
 *
 * 作用: 防止重复清算，保证幂等性
 */
public interface ProcessedTradeState {
    long getTradeId();         // 成交ID
    void setTradeId(long tradeId);
    long getProcessTime();    // 处理时间
    void setProcessTime(long time);
    byte getStatus();         // 状态: 1=已清算
    void setStatus(byte status);
}

/**
 * 清算服务 - 多线程设计
 *
 * 线程总数: 1 + N + 1 = N+2 (N = CPU核心数)
 *   - 1 个 TradeReader 线程: 从 Trade Queue 读取成交，分发到处理线程池
 *   - N 个 ClearingWorker 线程: 并发处理成交清算 (compute 原子更新)
 *   - 1 个 CancelProcessor 线程: 处理撤单解冻
 *
 * 关键设计:
 *   1. Queue Index 持久化: 记录处理进度，重启后恢复
 *   2. TradeId 去重表: 幂等检查，防止重复清算
 */
public class ClearingService {

    private final ChronicleMap<Long, AccountState> accountMap;
    private final ChronicleMap<Long, PositionState> positionMap;
    private final ChronicleMap<String, ProgressState> progressMap;  // 进度存储
    private final ChronicleMap<Long, ProcessedTradeState> processedTradeMap;  // 去重表
    private final ChronicleQueue tradeQueue;
    private final ChronicleQueue cancelQueue;
    private final WebSocketPushService wsPushService;

    // 线程池配置
    private final int workerCount;  // = CPU核心数
    private final ExecutorService workerPool;

    // 成交分发队列 (内存队列，高性能)
    private final BlockingQueue<TradeEvent> tradeDispatchQueue;

    // 已处理的最大 index (内存缓存)
    private final AtomicLong processedIndex = new AtomicLong(-1);

    /**
     * 初始化清算服务
     */
    public ClearingService(int workerCount) {
        this.workerCount = workerCount;
        this.workerPool = Executors.newFixedThreadPool(workerCount,
            new ThreadFactoryBuilder().setNameFormat("ClearingWorker-%d").build());
        this.tradeDispatchQueue = new LinkedBlockingQueue<>(10000);  // 容量10000
    }

    /**
     * 启动清算服务
     */
    public void start() {
        // 0. 恢复上次处理的 index (关键)
        long startIndex = recoverIndex();
        log.info("ClearingService starting from index={}", startIndex);

        // 1. 启动 TradeReader 线程 (单线程)
        Thread tradeReaderThread = new Thread(this::runTradeReader, "TradeReader");
        tradeReaderThread.start();

        // 2. 启动 N 个 ClearingWorker 线程
        for (int i = 0; i < workerCount; i++) {
            workerPool.submit(this::runClearingWorker);
        }

        // 3. 启动 CancelProcessor 线程 (单线程)
        Thread cancelThread = new Thread(this::runCancelProcessor, "CancelProcessor");
        cancelThread.start();
    }

    // ==================== 线程 1: TradeReader ====================

    /**
     * TradeReader 线程 - 从 Trade Queue 读取成交，分发到内存队列
     *
     * 职责:
     *   - 从 Chronicle Queue 读取 TradeEvent
     *   - 分发到内存队列 (非阻塞，高性能)
     *   - 单线程保证顺序读取
     *
     * 重要: 不在这里更新 index，而是传递 index 给 ClearingWorker
     *       重启后从 recoverIndex() 返回的位置开始读取
     */
    private void runTradeReader() {
        // 关键: 从上次处理的位置开始读取
        long startIndex = recoverIndex();
        ExcerptTailer tailer = tradeQueue.createTailer();

        // 移动到指定 index
        if (startIndex > 0) {
            tailer.moveToIndex(startIndex);
            log.info("TradeReader moved to index={}", startIndex);
        }

        while (running) {
            // 读取前记录 index
            long index = tailer.index();

            tailer.readDocument(wire -> {
                TradeEvent trade = parseTrade(wire);
                trade.setQueueIndex(index);  // 传递 index 给成交事件

                // 非阻塞分发到内存队列
                if (!tradeDispatchQueue.offer(trade)) {
                    // 队列满，记录告警
                    log.warn("Trade dispatch queue full, tradeId={}", trade.getTradeId());
                }
            });
        }
    }

    // ==================== 线程 2~N+1: ClearingWorker ====================

    /**
     * ClearingWorker 线程 - 从内存队列取成交，执行清算
     *
     * 职责:
     *   - 从内存队列取 TradeEvent
     *   - compute 原子更新买方账户/持仓
     *   - compute 原子更新卖方账户/持仓
     *   - 推送 WebSocket 成交回报
     *   - **更新已处理 index (关键)**
     *
     * 多线程安全保证:
     *   - compute 方法 Entry 级别锁
     *   - 不同账户并发执行
     *   - 同一账户串行执行
     *   - index 更新使用 AtomicLong，取最大值
     */
    private void runClearingWorker() {
        while (running) {
            try {
                TradeEvent trade = tradeDispatchQueue.poll(100, TimeUnit.MILLISECONDS);
                if (trade == null) continue;

                // 处理单笔成交 (幂等)
                boolean success = processTrade(trade);

                // 关键: 只有清算成功才更新 index
                if (success) {
                    updateProcessedIndex(trade.getQueueIndex());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // 已处理的最大 index
    private final AtomicLong processedIndex = new AtomicLong(-1);

    /**
     * 更新已处理的 index - 使用 AtomicLong 保证原子性
     */
    private void updateProcessedIndex(long index) {
        processedIndex.updateAndGet(current -> Math.max(current, index));

        // 定期持久化到磁盘 (每处理 100 笔)
        if (index % 100 == 0) {
            persistProcessedIndex();
        }
    }

    /**
     * 持久化已处理 index 到磁盘
     */
    private void persistProcessedIndex() {
        progressMap.compute("trade_queue_index", (key, state) -> {
            if (state == null) state = new ProgressState();
            state.setIndex(processedIndex.get());
            state.setUpdateTime(System.currentTimeMillis());
            return state;
        });
    }

    /**
     * 启动时恢复 index
     */
    private long recoverIndex() {
        ProgressState state = progressMap.get("trade_queue_index");
        if (state == null) {
            return 0;  // 首次启动，从头开始
        }
        return state.getIndex() + 1;  // 从下一条开始
    }

    /**
     * 处理成交清算 - 幂等设计
     *
     * @return true=清算成功, false=已处理过(跳过)
     */
    private boolean processTrade(TradeEvent trade) {
        // ==================== 幂等检查 (关键) ====================
        // 先检查是否已处理
        if (processedTradeMap.containsKey(trade.getTradeId())) {
            log.info("Trade already processed, tradeId={}", trade.getTradeId());
            return false;  // 已处理，跳过
        }

        // 标记为处理中 (防止并发重复处理)
        // 使用 putIfAbsent 原子操作，如果已存在则返回旧值
        ProcessedTradeState existing = processedTradeMap.putIfAbsent(
            trade.getTradeId(),
            createProcessedTradeState(trade.getTradeId())
        );

        if (existing != null) {
            // 另一个线程已经处理了
            log.info("Trade already processed by another thread, tradeId={}", trade.getTradeId());
            return false;
        }

        // ==================== 执行清算 ====================
        long frozenAmount = trade.getPrice() * trade.getQuantity();

        // 买方: 解冻 + 扣款 (使用 compute 原子操作)
        accountMap.compute(trade.getBuyAccountId(), (key, buyer) -> {
            if (buyer == null) return buyer;

            buyer.setFrozen(buyer.getFrozen() - frozenAmount);  // 解冻
            buyer.setBalance(buyer.getBalance() - frozenAmount - trade.getBuyerCommission());
            buyer.setAvailable(buyer.getAvailable() - trade.getBuyerCommission());
            buyer.setUpdateTime(System.currentTimeMillis());
            return buyer;
        });

        // 买方持仓增加 (使用 compute 原子操作)
        long buyerPosKey = (trade.getBuyAccountId() << 32) | trade.getSymbolCode();
        positionMap.compute(buyerPosKey, (key, buyerPos) -> {
            if (buyerPos == null) {
                buyerPos = new PositionState();
                buyerPos.setAccountId(trade.getBuyAccountId());
                buyerPos.setSymbolCode(trade.getSymbolCode());
            }
            buyerPos.setQuantity(buyerPos.getQuantity() + trade.getQuantity());
            // T+1: 当日买入不可用
            buyerPos.setUpdateTime(System.currentTimeMillis());
            return buyerPos;
        });

        // 卖方: 解冻持仓 + 增加资金 (使用 compute 原子操作)
        long sellAmount = trade.getPrice() * trade.getQuantity();
        accountMap.compute(trade.getSellAccountId(), (key, seller) -> {
            if (seller == null) return seller;

            seller.setBalance(seller.getBalance() + sellAmount - trade.getSellerCommission() - trade.getStampDuty());
            seller.setAvailable(seller.getAvailable() + sellAmount - trade.getSellerCommission() - trade.getStampDuty());
            seller.setUpdateTime(System.currentTimeMillis());
            return seller;
        });

        // 卖方持仓减少 (使用 compute 原子操作)
        long sellerPosKey = (trade.getSellAccountId() << 32) | trade.getSymbolCode();
        positionMap.compute(sellerPosKey, (key, sellerPos) -> {
            if (sellerPos == null) return sellerPos;

            sellerPos.setQuantity(sellerPos.getQuantity() - trade.getQuantity());
            sellerPos.setFrozen(sellerPos.getFrozen() - trade.getQuantity());  // 解冻
            sellerPos.setUpdateTime(System.currentTimeMillis());
            return sellerPos;
        });

        // 推送 WebSocket (异步，不阻塞清算)
        wsPushService.pushTradeReport(trade);

        return true;  // 清算成功
    }

    /**
     * 创建已处理成交状态
     */
    private ProcessedTradeState createProcessedTradeState(long tradeId) {
        ProcessedTradeState state = new ProcessedTradeState();
        state.setTradeId(tradeId);
        state.setProcessTime(System.currentTimeMillis());
        state.setStatus((byte) 1);  // 标记为已处理
        return state;
    }

    // ==================== 线程 N+2: CancelProcessor ====================

    /**
     * CancelProcessor 线程 - 处理撤单解冻
     *
     * 职责:
     *   - 从 Cancel Queue 读取撤单请求
     *   - compute 原子解冻资金/持仓
     *   - 推送 WebSocket 撤单结果
     *
     * 单线程原因:
     *   - 撤单量远小于成交量
     *   - 单线程足够处理
     */
    private void runCancelProcessor() {
        ExcerptTailer tailer = cancelQueue.createTailer();

        while (running) {
            tailer.readDocument(wire -> {
                CancelRequest req = parseCancelRequest(wire);
                processCancel(req);
            });
        }
    }

    /**
     * 处理撤单解冻 - 使用 compute 原子操作
     */
    private void processCancel(CancelRequest req) {
        if (req.getDirection() == Direction.BUY) {
            // 解冻资金
            accountMap.compute(req.getAccountId(), (key, account) -> {
                if (account == null) return account;

                long frozenAmount = req.getPrice() * req.getQuantity();
                account.setFrozen(account.getFrozen() - frozenAmount);
                account.setAvailable(account.getAvailable() + frozenAmount);
                account.setUpdateTime(System.currentTimeMillis());
                return account;
            });
        } else {
            // 解冻持仓
            long key = (req.getAccountId() << 32) | req.getSymbolCode();
            positionMap.compute(key, (key2, position) -> {
                if (position == null) return position;

                position.setFrozen(position.getFrozen() - req.getQuantity());
                position.setAvailable(position.getAvailable() + req.getQuantity());
                position.setUpdateTime(System.currentTimeMillis());
                return position;
            });
        }

        // 推送撤单结果
        wsPushService.pushCancelResult(req);
    }
}

/**
 * 风控服务 - 读取 Chronicle Map
 */
public class RiskControlService {

    private final ChronicleMap<Long, AccountState> accountMap;
    private final ChronicleMap<Long, PositionState> positionMap;

    /**
     * 资金充足性检查 - 读取 Chronicle Map
     */
    public boolean checkFundAvailable(long accountId, long amount) {
        AccountState account = accountMap.get(accountId);
        return account != null && account.getAvailable() >= amount;
    }

    /**
     * 持仓充足性检查 - 读取 Chronicle Map
     */
    public boolean checkPositionAvailable(long accountId, int symbolCode, long quantity) {
        long key = (accountId << 32) | symbolCode;
        PositionState position = positionMap.get(key);
        return position != null && position.getAvailable() >= quantity;
    }
}

```

#### 清算服务多线程设计总结

| 线程 | 数量 | 职责 | Chronicle Map 访问 |
|------|------|------|-------------------|
| **TradeReader** | 1 | 从 Trade Queue 读取成交，分发到内存队列 | 不访问 |
| **ClearingWorker** | N (CPU核心数) | 处理成交清算，compute 原子更新 | `compute` 写入 |
| **CancelProcessor** | 1 | 处理撤单解冻 | `compute` 写入 |

#### Queue Index 持久化设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Queue Index 持久化流程                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. TradeReader 读取时:                                              │
│     long index = tailer.index();                                    │
│     trade.setQueueIndex(index);  // 传递给 TradeEvent               │
│                                                                      │
│  2. ClearingWorker 清算成功后:                                       │
│     updateProcessedIndex(trade.getQueueIndex());                    │
│                                                                      │
│  3. Index 更新策略:                                                  │
│     processedIndex.updateAndGet(current -> Math.max(current, index));│
│     // 取最大值，保证不丢失数据                                       │
│                                                                      │
│  4. 持久化到磁盘:                                                    │
│     progressMap.compute("trade_queue_index", ...)                   │
│     // 每 100 笔持久化一次                                           │
│                                                                      │
│  5. 重启恢复:                                                        │
│     long startIndex = progressMap.get("trade_queue_index").getIndex();│
│     tailer.moveToIndex(startIndex + 1);  // 从下一条开始             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### 幂等设计：TradeId 去重表

```
┌─────────────────────────────────────────────────────────────────────┐
│                    幂等处理流程                                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  问题: 同一笔成交可能被重复处理，导致账户数据错误                       │
│                                                                      │
│  解决方案: TradeId 去重表 (processed_trades.dat)                     │
│                                                                      │
│  1. 幂等检查 (原子操作):                                              │
│     if (processedTradeMap.containsKey(tradeId)) {                   │
│         return false;  // 已处理，跳过                                │
│     }                                                               │
│                                                                      │
│  2. 原子标记:                                                        │
│     // putIfAbsent: 如果返回 null 表示首次处理                        │
│     // 如果返回旧值表示已被其他线程处理                                │
│     ProcessedTradeState existing = processedTradeMap.putIfAbsent(   │
│         tradeId, createProcessedTradeState(tradeId)                 │
│     );                                                              │
│     if (existing != null) return false;                             │
│                                                                      │
│  3. 执行清算 (compute 原子更新):                                       │
│     accountMap.compute(buyAccountId, ...)                           │
│     positionMap.compute(buyPosKey, ...)                              │
│                                                                      │
│  4. 更新进度 index:                                                   │
│     updateProcessedIndex(queueIndex)                                │
│                                                                      │
│  5. 推送 WebSocket:                                                  │
│     wsPushService.pushTradeReport(trade)                            │
│                                                                      │
│     return true;  // 清算成功                                        │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### Chronicle Map 存储结构汇总

| 文件 | Key | Value | 说明 |
|------|-----|-------|------|
| **accounts.dat** | accountId (Long) | AccountState | 账户状态 |
| **positions.dat** | (accountId<<32)|symbolCode | PositionState | 持仓状态 |
| **progress.dat** | "trade_queue_index" | ProgressState | 队列处理进度 |
| **processed_trades.dat** | tradeId (Long) | ProcessedTradeState | 已处理成交去重表 |

#### 为什么使用 compute 方法？

| 问题 | 解决方案 |
|------|---------|
| 冻结资金必须同步 | 使用 `compute` 方法在订单网关直接冻结，无需队列中转 |
| 并发写入冲突 | `compute` 在 entry 级别加锁，保证原子性，多线程安全 |
| 数据一致性 | 读取-修改-写入在同一个锁保护下完成，不会被其他线程打断 |
| 性能优化 | Entry 级别锁，不同 key 可并发执行，高吞吐量 |
| 进程崩溃恢复 | Chronicle Map 持久化到磁盘，重启后自动恢复 |

#### compute 方法原理

```java
// Chronicle Map compute 方法实现原理
default V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    // 1. 对 key 对应的 entry 加锁 (Entry 级别锁，非全局锁)
    // 2. 读取当前值
    // 3. 执行 remappingFunction
    // 4. 写入新值
    // 5. 释放锁
    // 整个过程是原子的
}
```

#### 关键点说明

1. **原子冻结**: `compute` 方法保证读取-修改-写入的原子性，多线程并发安全

2. **Entry 级别锁**: 不同 key 的操作可以并发执行，性能优于全局锁

3. **同 key 串行化**: 对同一账户的并发操作会串行执行，避免数据覆盖

4. **同步返回**: 冻结结果立即返回，无需等待队列处理

5. **清算更新**: 清算服务也使用 `compute` 方法，保证成交后数据一致性

6. **幂等处理**: 使用 `putIfAbsent` 原子检查 + 写入，防止重复清算

7. **进度持久化**: Queue Index 存储在 Chronicle Map，重启后恢复

### 4.4 风控服务设计

```java
/**
 * 风控服务 - 读取 Chronicle Map
 */
public class RiskControlService {

    private final ChronicleMap<Long, AccountState> accountMap;
    private final ChronicleMap<Long, PositionState> positionMap;
    private final RuleEngine ruleEngine;

    /**
     * 订单风控检查 - 快速路径
     */
    public RiskResult checkOrder(Order order) {
        // 1. 涨跌停价格检查 (O(1))
        if (!checkPriceLimit(order)) {
            return RiskResult.reject("超出涨跌停限制");
        }

        // 2. 交易时段检查 (O(1))
        if (!checkTradingTime()) {
            return RiskResult.reject("非交易时段");
        }

        // 3. 资金充足性检查 - 读 Chronicle Map (O(1))
        if (order.getDirection() == Direction.BUY) {
            if (!checkFundAvailable(order)) {
                return RiskResult.reject("资金不足");
            }
        }

        // 4. 持仓充足性检查 - 读 Chronicle Map (O(1))
        if (order.getDirection() == Direction.SELL) {
            if (!checkPositionAvailable(order)) {
                return RiskResult.reject("持仓不足");
            }
        }

        // 5. 持仓限额检查 (O(1))
        if (!checkPositionLimit(order)) {
            return RiskResult.reject("超出持仓限额");
        }

        // 6. 自成交检测 (需要查询订单簿)
        if (checkSelfTrade(order)) {
            return RiskResult.reject("自成交风险");
        }

        return RiskResult.pass();
    }

    /**
     * 资金充足性检查 - 读取 Chronicle Map
     */
    private boolean checkFundAvailable(Order order) {
        AccountState account = accountMap.get(order.getAccountId());
        if (account == null) return false;

        long amount = order.getPrice() * order.getQuantity();
        return account.getAvailable() >= amount;
    }

    /**
     * 持仓充足性检查 - 读取 Chronicle Map
     */
    private boolean checkPositionAvailable(Order order) {
        long key = (order.getAccountId() << 32) | order.getSymbolCode();
        PositionState position = positionMap.get(key);
        if (position == null) return false;

        return position.getAvailable() >= order.getQuantity();
    }

    /**
     * 涨跌停价格检查
     */
    private boolean checkPriceLimit(Order order) {
        long prevClose = getPrevClose(order.getSymbolCode());
        double limit = getPriceLimitRatio(order.getSymbolCode());

        long upperLimit = (long)(prevClose * (1 + limit));
        long lowerLimit = (long)(prevClose * (1 - limit));

        return order.getPrice() >= lowerLimit && order.getPrice() <= upperLimit;
    }

    private double getPriceLimitRatio(int symbolCode) {
        // ST股票
        if (isST(symbolCode)) return 0.05;
        // 创业板
        if (isGem(symbolCode)) return 0.20;
        // 科创板
        if (isStar(symbolCode)) return 0.20;
        // 主板
        return 0.10;
    }
     * 自成交检测 - 检查是否有反向挂单
     */
    private boolean checkSelfTrade(Order order) {
        OrderBook orderBook = matchingEngine.getOrderBook(order.getSymbolCode());

        if (order.getDirection() == Direction.BUY) {
            // 检查是否有自己的卖单
            for (Order ask : orderBook.getAsks()) {
                if (ask.getAccountId() == order.getAccountId()) {
                    return true;
                }
            }
        } else {
            // 检查是否有自己的买单
            for (Order bid : orderBook.getBids()) {
                if (bid.getAccountId() == order.getAccountId()) {
                    return true;
                }
            }
        }

        return false;
    }
}
```

### 4.5 WebSocket推送设计

```java
/**
 * WebSocket推送服务 - Netty实现
 */
public class WebSocketPushService {

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Map<Long, Channel> accountChannels; // accountId -> Channel

    /**
     * 推送订单状态更新
     */
    public void pushOrderUpdate(long accountId, Order order) {
        Channel channel = accountChannels.get(accountId);
        if (channel == null || !channel.isActive()) {
            return;
        }

        OrderUpdateMessage msg = OrderUpdateMessage.builder()
            .type("ORDER_UPDATE")
            .orderId(order.getOrderId())
            .status(order.getStatus())
            .filledQty(order.getFilledQty())
            .avgPrice(order.getAvgPrice())
            .timestamp(System.currentTimeMillis())
            .build();

        channel.writeAndFlush(msg);
    }

    /**
     * 推送成交回报
     */
    public void pushTradeReport(long accountId, Trade trade) {
        Channel channel = accountChannels.get(accountId);
        if (channel == null || !channel.isActive()) {
            return;
        }

        TradeReportMessage msg = TradeReportMessage.builder()
            .type("TRADE_REPORT")
            .tradeId(trade.getTradeId())
            .symbol(trade.getSymbol())
            .price(trade.getPrice())
            .quantity(trade.getQuantity())
            .direction(trade.getDirection())
            .timestamp(trade.getTimestamp())
            .build();

        channel.writeAndFlush(msg);
    }

    /**
     * 批量推送行情数据
     */
    @Scheduled(fixedRate = 100) // 100ms推送一次
    public void pushMarketData() {
        List<MarketData> dataList = collectMarketData();

        // 批量推送
        for (Channel channel : accountChannels.values()) {
            if (channel.isActive()) {
                MarketDataMessage msg = new MarketDataMessage(dataList);
                channel.writeAndFlush(msg);
            }
        }
    }
}
```

---

## 5. 数据模型设计

### 5.1 内存数据结构

```java
/**
 * 核心数据结构 - 内存模型
 */

// 订单
public class Order {
    long orderId;           // 订单ID
    long accountId;         // 账户ID
    int symbolCode;         // 标的代码 (int编码)
    long price;             // 价格 (放大10000倍存储)
    long quantity;          // 委托数量
    long filledQty;         // 已成交数量
    byte direction;         // 方向: 0=买入, 1=卖出
    byte orderType;         // 类型: 0=限价, 1=市价
    byte status;            // 状态: 0=新建, 1=部分成交, 2=全部成交, 3=已撤销
    long createTime;        // 创建时间
    long updateTime;        // 更新时间
}

// 成交
public class Trade {
    long tradeId;           // 成交ID
    long buyOrderId;        // 买方订单ID
    long sellOrderId;       // 卖方订单ID
    long buyAccountId;      // 买方账户ID
    long sellAccountId;     // 卖方账户ID
    int symbolCode;         // 标的代码
    long price;             // 成交价格
    long quantity;          // 成交数量
    long tradeTime;         // 成交时间
}

// 账户
public class Account {
    long accountId;         // 账户ID
    long balance;           // 总资产
    long available;         // 可用资金
    long frozen;            // 冻结资金
    long margin;            // 保证金
    Int2ObjectHashMap<Position> positions; // 持仓
}

// 持仓
public class Position {
    int symbolCode;         // 标的代码
    long quantity;          // 持仓数量
    long available;        // 可用数量 (T+1)
    long frozen;           // 冻结数量
    long avgPrice;         // 成本价
}

// 资金流水
public class FundFlow {
    long flowId;            // 流水ID
    long accountId;         // 账户ID
    long amount;            // 变动金额
    byte flowType;          // 类型: 买入/卖出/转入/转出
    long refId;             // 关联ID
    long balanceBefore;     // 变动前余额
    long balanceAfter;      // 变动后余额
    long createTime;        // 创建时间
}
```

### 5.2 H2数据库表结构

> **时间字段规范**: 所有时间字段使用 `BIGINT` 存储毫秒级时间戳 (epoch milliseconds)
> - 后端: `System.currentTimeMillis()` 获取
> - 前端: `new Date(timestamp)` 转换为本地时区显示

```sql
-- 订单表
CREATE TABLE orders (
    order_id BIGINT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    direction TINYINT NOT NULL,
    order_type TINYINT NOT NULL,
    price BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    filled_qty BIGINT DEFAULT 0,
    status TINYINT NOT NULL,
    create_time BIGINT NOT NULL,       -- 时间戳 (毫秒)
    update_time BIGINT NOT NULL        -- 时间戳 (毫秒)
);

CREATE INDEX idx_order_account ON orders(account_id, create_time);
CREATE INDEX idx_order_symbol ON orders(symbol, create_time);

-- 成交表
CREATE TABLE trades (
    trade_id BIGINT PRIMARY KEY,
    buy_order_id BIGINT NOT NULL,
    sell_order_id BIGINT NOT NULL,
    buy_account_id BIGINT NOT NULL,
    sell_account_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    price BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    trade_time BIGINT NOT NULL         -- 时间戳 (毫秒)
);

CREATE INDEX idx_trade_time ON trades(trade_time);
CREATE INDEX idx_trade_account ON trades(buy_account_id, trade_time);
CREATE INDEX idx_trade_account2 ON trades(sell_account_id, trade_time);

-- 账户表
CREATE TABLE accounts (
    account_id BIGINT PRIMARY KEY,
    balance BIGINT NOT NULL,
    available BIGINT NOT NULL,
    frozen BIGINT NOT NULL,
    margin BIGINT NOT NULL,
    update_time BIGINT NOT NULL        -- 时间戳 (毫秒)
);

-- 持仓表
CREATE TABLE positions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    quantity BIGINT NOT NULL,
    available BIGINT NOT NULL,
    frozen BIGINT NOT NULL,
    avg_price BIGINT NOT NULL,
    update_time BIGINT NOT NULL,       -- 时间戳 (毫秒)
    UNIQUE KEY uk_account_symbol (account_id, symbol)
);

-- 资金流水表
CREATE TABLE fund_flows (
    flow_id BIGINT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    flow_type TINYINT NOT NULL,
    ref_id BIGINT,
    balance_before BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    create_time BIGINT NOT NULL        -- 时间戳 (毫秒)
);

CREATE INDEX idx_flow_account ON fund_flows(account_id, create_time);

-- 已撤销订单索引 (用于定时清理)
CREATE INDEX idx_order_cancelled ON orders(status, update_time)
    WHERE status = 3;  -- status=3 已撤销
```

### 5.3 H2数据库配置

```java
/**
 * H2数据库配置 - 磁盘持久化模式
 */
public class H2Config {

    // H2磁盘持久化连接配置
    public DataSource createDataSource() {
        HikariDataSource ds = new HikariDataSource();
        // 磁盘持久化模式，数据文件存储在 ./data/h2/trading.mv.db
        ds.setJdbcUrl("jdbc:h2:./data/h2/trading;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE");
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setMaximumPoolSize(4);           // 持久化线程池小
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(5000);      // 5秒超时
        ds.setIdleTimeout(60000);
        ds.setMaxLifetime(1800000);
        return ds;
    }
}
```

### 5.4 定时任务 - 清理已撤销订单

```java
/**
 * 定时清理任务 - 每分钟删除2小时前的已撤销订单
 */
public class OrderCleanupTask {

    private final JdbcTemplate jdbcTemplate;
    private final ScheduledExecutorService scheduler;

    public void start() {
        // 每分钟执行一次
        scheduler.scheduleAtFixedRate(this::cleanupCancelledOrders,
            60000, 60000, TimeUnit.MILLISECONDS);
    }

    /**
     * 删除2小时前已撤销的订单
     * 主链路检查、风控、清结算均不走数据库，仅用于历史查询
     */
    private void cleanupCancelledOrders() {
        // 计算2小时前的时间戳
        long twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000;
        String sql = "DELETE FROM orders WHERE status = 3 AND update_time < ?";
        int deleted = jdbcTemplate.update(sql, twoHoursAgo);
        if (deleted > 0) {
            log.info("Cleaned up {} cancelled orders older than 2 hours", deleted);
        }
    }
}
```

### 5.5 数据持久化策略

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          数据持久化策略                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【主链路数据 - Chronicle 实时持久化】                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Chronicle Queue: 订单流、成交流                                      │   │
│  │   • 配置: blockSize=64MB, rollCycle=DAILY                           │   │
│  │   • 特性: 零拷贝、内存映射、进程崩溃数据不丢失                         │   │
│  │   • 路径: ./data/queue/orders/, ./data/queue/trades/                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Chronicle Map: 账户状态、持仓状态                                    │   │
│  │   • 配置: createPersistedTo() 磁盘持久化                            │   │
│  │   • 特性: 实时写入磁盘、进程重启后自动恢复                            │   │
│  │   • 路径: ./data/map/accounts.dat, ./data/map/positions.dat         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【历史数据 - H2 异步批量持久化】                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ H2 Database: 订单历史、成交历史、资金流水                            │   │
│  │   • 模式: 磁盘持久化 (jdbc:h2:./data/h2/trading)                    │   │
│  │   • 写入: 异步批量，每100ms或1000条批量写入                          │   │
│  │   • 用途: 历史查询、报表生成、审计追溯                                │   │
│  │   • 清理: 已撤销订单保留2小时，定时任务每分钟清理                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【主链路不走数据库】                                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ✓ 下单检查: Chronicle Map 内存读取 (账户余额、持仓)                  │   │
│  │ ✓ 风控检查: Chronicle Map 内存计算 (资金充足性、持仓限额)            │   │
│  │ ✓ 撮合引擎: 内存订单簿 TreeMap                                      │   │
│  │ ✓ 清算服务: Chronicle Map 内存更新 (账户、持仓)                     │   │
│  │ ✗ 数据库仅用于: 异步持久化、历史查询、报表                           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. JVM配置与性能调优

### 6.1 JVM启动参数

> **GC选型说明**: 使用 ZGC 替代 G1GC。原因：
> - ZGC 暂停时间 < 1ms，G1GC 目标 10ms 但实际可能更高
> - ZGC 并发整理，不需要 Stop-The-World，对交易系统延迟更友好
> - 10GB 大堆内存下 ZGC 性能优异，吞吐量损失极小
> - Java 17+ ZGC 已生产可用 (JDK 15+ 正式支持)

```bash
java \
  -Xms10g -Xmx10g \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -XX:+UseStringDeduplication \
  -XX:+AlwaysPreTouch \
  -XX:+UseCompressedOops \
  -XX:+UseCompressedClassPointers \
  -XX:+UseTLAB \
  -XX:+ResizeTLAB \
  -XX:MaxDirectMemorySize=4g \
  -XX:ConcGCThreads=2 \
  -XX:ParallelGCThreads=6 \
  -Djava.net.preferIPv4Stack=true \
  -jar trading-system.jar
```

### 6.2 性能调优清单

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          性能调优清单                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【内存配置】                                                                │
│  □ 堆内存: 10GB (固定大小，避免动态扩容)                                      │
│  □ 直接内存: 4GB (Chronicle Queue使用)                                      │
│  □ 预分配内存: -XX:+AlwaysPreTouch                                          │
│  □ TLAB启用: -XX:+UseTLAB -XX:+ResizeTLAB                                   │
│                                                                             │
│  【GC配置】                                                                  │
│  □ ZGC: 超低延迟GC，目标暂停 < 1ms (G1GC仍需10ms+)                         │
│  □ 并发整理: 不需要Stop-The-World                                           │
│  □ 适合大堆: 10GB堆内存ZGC表现优异                                          │
│  □ 字符串去重: 减少重复字符串内存                                            │
│                                                                             │
│  【线程配置】                                                                │
│  □ 撮合线程: 每个标的独立线程，绑定CPU核心                                   │
│  □ 网络线程: Netty Boss/Worker线程                                          │
│  □ 持久化线程: 单线程批量写入                                                │
│  □ 监控线程: 低优先级后台线程                                                │
│                                                                             │
│  【对象池化】                                                                │
│  □ Order对象池: 复用订单对象                                                │
│  □ Trade对象池: 复用成交对象                                                │
│  □ ByteBuffer池: Netty ByteBuf池                                           │
│  □ 避免装箱: 使用原生类型集合                                                │
│                                                                             │
│  【CPU亲和性】                                                               │
│  □ 撮合线程绑定独立CPU核心                                                  │
│  □ 网络线程绑定独立CPU核心                                                  │
│  □ 避免上下文切换                                                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. 监控指标设计

### 7.1 核心监控指标

```java
/**
 * Prometheus监控指标
 */
public class MetricsConfig {

    // 订单相关指标
    public static final Counter ORDER_RECEIVED = Counter.build()
        .name("trading_order_received_total")
        .help("Total orders received")
        .labelNames("symbol", "direction")
        .register();

    public static final Counter ORDER_REJECTED = Counter.build()
        .name("trading_order_rejected_total")
        .help("Total orders rejected")
        .labelNames("symbol", "reason")
        .register();

    public static final Histogram ORDER_LATENCY = Histogram.build()
        .name("trading_order_latency_seconds")
        .help("Order processing latency")
        .labelNames("symbol")
        .buckets(0.001, 0.005, 0.010, 0.015, 0.020, 0.050, 0.100)
        .register();

    // 撮合相关指标
    public static final Counter TRADE_COUNT = Counter.build()
        .name("trading_trade_total")
        .help("Total trades")
        .labelNames("symbol")
        .register();

    public static final Histogram MATCHING_LATENCY = Histogram.build()
        .name("trading_matching_latency_seconds")
        .help("Matching engine latency")
        .labelNames("symbol")
        .buckets(0.00001, 0.00005, 0.0001, 0.0005, 0.001)
        .register();

    // Chronicle Queue指标
    public static final Gauge QUEUE_SIZE = Gauge.build()
        .name("trading_queue_size")
        .help("Chronicle queue size")
        .labelNames("queue_type")
        .register();

    public static final Gauge QUEUE_LATENCY = Gauge.build()
        .name("trading_queue_latency_seconds")
        .help("Chronicle queue write latency")
        .labelNames("queue_type")
        .register();

    // 账户相关指标
    public static final Gauge ACTIVE_ACCOUNTS = Gauge.build()
        .name("trading_active_accounts")
        .help("Number of active accounts")
        .register();

    public static final Gauge TOTAL_BALANCE = Gauge.build()
        .name("trading_total_balance")
        .help("Total account balance")
        .register();
}
```

### 7.2 Grafana仪表盘

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Grafana仪表盘布局                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     系统概览 (System Overview)                       │   │
│  │  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐         │   │
│  │  │ 订单TPS   │  │ 延迟P99   │  │ 成功率    │  │ 活跃账户  │         │   │
│  │  │ 125,000   │  │ 12.5ms    │  │ 99.8%    │  │ 1,234     │         │   │
│  │  └───────────┘  └───────────┘  └───────────┘  └───────────┘         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     订单处理 (Order Processing)                      │   │
│  │  ┌──────────────────────────────────────────────────────────────┐   │   │
│  │  │  订单延迟分布图 (P50/P90/P99)                                  │   │   │
│  │  └──────────────────────────────────────────────────────────────┘   │   │
│  │  ┌──────────────────────────────────────────────────────────────┐   │   │
│  │  │  订单TPS趋势图                                                 │   │   │
│  │  └──────────────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     撮合引擎 (Matching Engine)                       │   │
│  │  ┌──────────────────────────────────────────────────────────────┐   │   │
│  │  │  撮合延迟分布图 (目标 < 10μs)                                  │   │   │
│  │  └──────────────────────────────────────────────────────────────┘   │   │
│  │  ┌──────────────────────────────────────────────────────────────┐   │   │
│  │  │  订单簿深度变化图                                              │   │   │
│  │  └──────────────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     JVM性能 (JVM Performance)                        │   │
│  │  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐         │   │
│  │  │ 堆内存    │  │ GC暂停    │  │ 线程数    │  │ CPU使用   │         │   │
│  │  │ 6.2GB     │  │ <1ms      │  │ 45        │  │ 65%       │         │   │
│  │  └───────────┘  └───────────┘  └───────────┘  └───────────┘         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. 前端架构设计

### 8.1 技术栈

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          前端技术栈                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  框架: React 18 + TypeScript                                                │
│  状态管理: Zustand (轻量级)                                                  │
│  UI组件: Ant Design 5.x                                                     │
│  图表: TradingView (专业金融图表库)                                           │
│  WebSocket: 原生WebSocket + 重连机制                                        │
│  构建: Vite                                                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 界面布局（增强版 v2）

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  Logo  │ 股票 │ 期货 │ 期权 │ 自选 │  总资产: ¥1,234,567  │  可用: ¥567,890  │  保证金: ¥456,789  │ 设置 │
│        │      │      │      │      │  持仓市值: ¥789,012  │  风险度: 45.6%   │  浮盈: +¥12,345   │      │
├─────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  ┌────────────┐  ┌────────────────────────────────┐  ┌──────────────────┐  ┌────────────┐        │
│  │ 市场列表    │  │   [分时] [K线] [深度图]        │  │    订单簿        │  │ 账户详情    │        │
│  │ ┌────────┐ │  │                                │  │  卖五  10.05 500 │  │ (AccountPanel)│      │
│  │ │ 搜索框  │ │  │        K线图区域               │  │  卖四  10.04 800 │  │            │        │
│  │ └────────┘ │  │       (TradingView)            │  │  卖三  10.03 1.2K│  │ 资金明细    │        │
│  │            │  │                                │  │  卖二  10.02 2K  │  │ ──────────│        │
│  │ 全部股票   │  │                                │  │  卖一  10.01 5K  │  │ 期初: 1.2M│        │
│  │ 涨幅榜     │  ├────────────────────────────────┤  │  ──────────────  │  │ 入金: +50K│        │
│  │ 跌幅榜     │  │  成交明细 (Time & Sales)       │  │  买一  10.00 3K  │  │ 出金: -0  │        │
│  │ 成交额榜   │  │  10:30:05  10.01  100  B       │  │  买二   9.99 2K  │  │ 手续费: 370│       │
│  │ 自选股     │  │  10:30:06  10.00  200  S       │  │  买三   9.98 1K  │  │ 印花税: 434│       │
│  │            │  │  ...                           │  │  买四   9.97 800 │  │ ──────────│        │
│  │            │  ├────────────────────────────────┤  │  买五   9.96 500 │  │ 净值:1.23M│        │
│  │            │  │        下单面板                 │  │                  │  │            │        │
│  │            │  │  [限价] [市价] [FAK] [FOK]     │  │  ┌────────────┐  │  │ 持仓明细    │        │
│  │            │  │  价格: [__10.00__] [↑] [↓]     │  │  │ 快捷下单    │  │  │ 600000    │        │
│  │            │  │  数量: [__100___] [1/2][全部]  │  │  │ 买 [10.00] │  │  │ 持仓:1000│        │
│  │            │  │  [买入 F1]       [卖出 F2]      │  │  │ 卖 [10.01] │  │  │ 成本:10.00│        │
│  │            │  └────────────────────────────────┘  │  └────────────┘  │  │ 盈亏:+500│        │
│  └────────────┘                                      └──────────────────┘  │ IF2401   │        │
│                                                                            │  │ 多头:2手 │        │
│                                                                            │  │ 保证金:..│        │
│                                                                            │  └────────────┘        │
├─────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  [当前委托]  [历史成交]  [资金流水]  [持仓管理]  [交易统计]  [预警设置]                              │
├─────────────────────────────────────────────────────────────────────────────────────────────────────┤
│  订单ID  标的  方向  价格  数量  已成交  状态  时间  操作                                             │
│  100001  600000 买入 10.00  100   50    部分成交 09:30  [撤单]                                      │
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**布局说明**：

| 区域 | 位置 | 内容 | 重要性 |
|------|------|------|--------|
| **账户信息栏** | 导航栏中间 | 总资产、可用、保证金、风险度、浮盈 | ⭐⭐⭐⭐⭐ |
| **市场列表** | 左侧 | 股票/期货/期权列表、涨幅榜、自选股 | ⭐⭐⭐ |
| **K线图区** | 中央 | 分时/K线/深度图、成交明细、下单面板 | ⭐⭐⭐⭐ |
| **订单簿** | 右侧上 | 五档买卖盘、快捷下单 | ⭐⭐⭐⭐ |
| **账户详情** | 右侧下 | 资金明细、持仓明细、保证金详情 | ⭐⭐⭐⭐ |
| **底部标签** | 底部 | 委托、成交、流水、持仓、统计、预警 | ⭐⭐⭐ |

**优势**：
- 节省垂直空间，图表区域更大
- 账户核心指标始终可见
- 紧凑布局，信息密度高

### 8.3 功能模块清单

#### 8.3.1 顶部导航菜单

| 一级菜单 | 二级菜单/功能 | 说明 |
|----------|---------------|------|
| **Logo** | - | 系统标识，点击返回首页 |
| **股票** | 全部股票、涨幅榜、跌幅榜、成交额榜、自选股 | A股交易入口 |
| **期货** | IF当月、IF次月、IF季月 | IF股指期货交易 |
| **期权** | 认购期权、认沽期权、按到期日筛选 | 50ETF期权交易 |
| **自选** | 自选股列表、添加自选、删除自选 | 自选股管理 |
| **策略** | 策略列表、新建策略、策略回测 | 交易策略管理(预留) |
| **账户** | 资金余额、可用资金、浮动盈亏 | 实时显示账户状态 |
| **设置** | 主题切换、快捷键设置、预警设置、合约信息 | 系统设置 |

#### 8.3.2 左侧面板 - 市场列表

| 功能 | 子功能 | 交互方式 |
|------|--------|----------|
| 搜索框 | 模糊搜索标的代码/名称 | 实时搜索，回车确认 |
| 全部股票 | 按代码排序 | 点击切换标的 |
| 涨幅榜 | 按涨幅降序，显示涨幅% | 实时更新 |
| 跌幅榜 | 按跌幅升序，显示跌幅% | 实时更新 |
| 成交额榜 | 按成交额降序 | 实时更新 |
| 自选股 | 用户自选标的列表 | 支持拖拽排序 |

#### 8.3.3 账户信息栏 (导航栏中间)

**位置**: 导航栏中间，菜单和设置按钮之间，始终可见

| 指标 | 说明 | 显示格式 | 颜色规则 |
|------|------|----------|----------|
| **总资产** | 账户总资产 = 现金 + 持仓市值 | ¥1,234,567 | 白色 |
| **持仓市值** | 股票持仓总市值 | ¥789,012 | 白色 |
| **可用资金** | 可用于开新仓的资金 | ¥567,890 | 青色(高亮) |
| **风险度** | 占用保证金/总资产 | 45.6% ████░░ | <50%绿/50-80%黄/>80%红 |
| **占用保证金** | 期货/期权持仓占用的保证金 | ¥456,789 | 橙色 |
| **浮动盈亏** | 当前持仓未实现盈亏 | +¥12,345 (+1.23%) | 盈利绿色/亏损红色 |

**交互设计**:
- 鼠标悬停任意指标 → 显示详情Tooltip
- 点击指标 → 展开详情弹窗
- 风险度进度条实时变化，超过80%闪烁警告
- 浮动盈亏实时更新，颜色随盈亏变化

**精简原则**:
- 导航栏空间有限，仅展示6个核心指标
- 详细信息在右侧账户详情面板展示
- 节省垂直空间，图表区域更大

#### 8.3.4 中央区域 - 图表与交易

| 功能区 | 子功能 | 说明 |
|--------|--------|------|
| **分时图** | 当日价格走势、成交量、均价线 | 展示当日走势 |
| **K线图** | 周期切换(1m/5m/15m/30m/60m/日K/周K/月K)、技术指标(MACD/KDJ/RSI/BOLL)、画线工具 | TradingView专业图表 |
| **深度图** | 买卖盘深度可视化 | 横向柱状图展示买卖盘 |
| **成交明细** | Time & Sales、每笔成交时间/价格/数量/方向 | 实时滚动更新 |
| **下单面板** | 订单类型切换、价格输入(↑↓调整tick)、数量输入(1/2/全部快捷按钮)、买入/卖出按钮 | 支持快捷键操作 |

#### 8.3.5 右侧面板 - 订单簿与账户详情

| 功能区 | 子功能 | 交互方式 |
|--------|--------|----------|
| **五档订单簿** | 卖五~卖一、买一~买五、价格/数量 | 点击价格触发快捷下单 |
| **快捷下单** | 买入按钮(显示买一价)、卖出按钮(显示卖一价) | 一键下单 |
| **合约信息** | 标的名称、昨收、涨跌停、合约乘数(期货/期权) | 悬停显示详情 |
| **账户详情面板** | 资金明细、持仓明细、保证金详情 | 实时更新 |

**账户详情面板 (AccountPanel.tsx)**:

| 区域 | 内容 | 说明 |
|------|------|------|
| **资金明细** | 期初资金、入金、出金、手续费、印花税、当前净值 | 当日资金变动明细 |
| **持仓明细** | 各标的持仓数量、成本价、现价、盈亏、盈亏% | 按标的逐一展示 |
| **保证金详情** | 期货/期权持仓、占用保证金、可用保证金、风险度 | 期货期权专用 |

**持仓明细展示格式**:
```
┌────────────────────────────┐
│ 600000 浦发银行            │
│ 持仓: 1,000股  成本: 10.00 │
│ 现价: 10.50  盈亏: +¥500   │
│ 盈亏%: +5.00%              │
├────────────────────────────┤
│ IF2401 股指期货            │
│ 多头: 2手  开仓价: 3850    │
│ 现价: 3880  盈亏: +¥6,000  │
│ 保证金: ¥92,400            │
├────────────────────────────┤
│ 10003720 50ETF认购         │
│ 持仓: 10张  成本: 0.15     │
│ 现价: 0.18  盈亏: +¥300    │
│ 保证金: ¥1,800             │
└────────────────────────────┘
```

#### 8.3.6 底部面板 - 标签页

| 标签页 | 功能 | 列信息 |
|--------|------|--------|
| **当前委托** | 活跃订单管理 | 订单ID、标的、方向、价格、数量、已成交、状态、时间、撤单操作 |
| **历史成交** | 成交记录查询 | 成交ID、订单ID、标的、方向、价格、数量、手续费、成交时间 |
| **资金流水** | 资金变动记录 | 流水ID、类型、金额、余额变化、关联订单、时间 |
| **持仓管理** | 当前持仓列表 | 标的、持仓数量、可用数量、成本价、现价、盈亏、盈亏% |
| **交易统计** | 当日交易汇总 | 总成交笔数、买入金额、卖出金额、手续费、印花税、净盈亏 |
| **预警设置** | 价格预警管理 | 标的、预警类型(价格/涨跌幅/成交量)、触发条件、状态、操作 |

### 8.4 键盘快捷键

| 快捷键 | 功能 | 适用场景 |
|--------|------|----------|
| **F1** | 快速买入 | 下单面板聚焦时 |
| **F2** | 快速卖出 | 下单面板聚焦时 |
| **F3** | 撤销当前选中订单 | 委托列表聚焦时 |
| **Esc** | 清空下单面板/关闭弹窗 | 全局 |
| **↑** | 价格上调一个tick | 下单面板价格输入聚焦时 |
| **↓** | 价格下调一个tick | 下单面板价格输入聚焦时 |
| **Ctrl+↑** | 数量+100 | 下单面板数量输入聚焦时 |
| **Ctrl+↓** | 数量-100 | 下单面板数量输入聚焦时 |
| **Tab** | 切换焦点区域 | 全局 |
| **Enter** | 确认下单 | 下单面板聚焦时 |
| **Delete** | 撤销选中订单 | 委托列表聚焦时 |
| **Ctrl+Z** | 撤销全部订单 | 全局 |
| **Ctrl+S** | 添加当前标的到自选 | 全局 |

### 8.5 交互优化设计

#### 8.5.1 快捷下单流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          快捷下单交互流程                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 点击订单簿价格                                                          │
│     ┌──────────────┐                                                       │
│     │ 卖一  10.01  │ ← 点击此处                                             │
│     └──────┬───────┘                                                       │
│            │                                                               │
│            ▼                                                               │
│     ┌──────────────────────────────────────────────────────────────────┐   │
│     │ 下单面板自动填入:                                                 │   │
│     │   • 价格: 10.01 (卖一价)                                          │   │
│     │   • 方向: 卖出 (点击卖盘默认卖出)                                  │   │
│     │   • 数量: 保持上次输入或默认100                                    │   │
│     └──────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  2. 快捷下单按钮                                                            │
│     ┌──────────────────────────────────────────────────────────────────┐   │
│     │  ┌─────────────┐        ┌─────────────┐                          │   │
│     │  │ 买 [10.00] │        │ 卖 [10.01] │                          │   │
│     │  │  (买一价)   │        │  (卖一价)   │                          │   │
│     │  └─────────────┘        └─────────────┘                          │   │
│     │       ↑                       ↑                                    │   │
│     │   点击直接以买一价买入    点击直接以卖一价卖出                      │   │
│     └──────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  3. 价格微调                                                                │
│     ┌──────────────────────────────────────────────────────────────────┐   │
│     │  价格: [__10.00__]  [↑]  [↓]                                       │   │
│     │                      │    │                                        │   │
│     │                   +tick  -tick  (一个最小变动价位)                   │   │
│     └──────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  4. 数量快捷按钮                                                            │
│     ┌──────────────────────────────────────────────────────────────────┐   │
│     │  数量: [__100___]  [1/2]  [1/3]  [全部]  [清空]                   │   │
│     │                      │     │      │                                │   │
│     │                   减半   1/3    全仓                                │   │
│     └──────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 8.5.2 预警系统

```javascript
/**
 * 预警规则配置
 */
const alertTypes = {
  PRICE_ABOVE: '价格高于',      // 价格 >= 触发价
  PRICE_BELOW: '价格低于',      // 价格 <= 触发价
  CHANGE_ABOVE: '涨幅超过',    // 涨幅 >= 触发值%
  CHANGE_BELOW: '跌幅超过',    // 跌幅 <= 触发值%
  VOLUME_SURGE: '成交量异常',   // 成交量 > 均值 * N倍
  TURNOVER_HIGH: '换手率过高'   // 换手率 > 触发值%
};

// 预警触发后操作
const alertActions = {
  NOTIFY: '弹窗通知',
  SOUND: '声音提醒',
  AUTO_TRADE: '自动交易(预留)'
};
```

#### 8.5.3 交易统计面板

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          当日交易统计                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  总成交笔数: 45笔              买入笔数: 28笔          卖出笔数: 17笔 │   │
│  │  总成交金额: ¥1,234,567        买入金额: ¥800,000      卖出金额: ¥434,567 │   │
│  │  手续费合计: ¥370.37           印花税: ¥434.57         过户费: ¥12.35 │   │
│  │  净盈亏:      +¥5,432.10       盈亏率:  +0.44%                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  成交分布:                                                           │   │
│  │  ┌──────────────────────────────────────────────────────────────┐   │   │
│  │  │  09:30-10:00  ████████████████  15笔                          │   │   │
│  │  │  10:00-10:30  ██████████  10笔                                 │   │   │
│  │  │  10:30-11:00  ████████  8笔                                    │   │   │
│  │  │  11:00-11:30  ██████████████  12笔                            │   │   │
│  │  └──────────────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.6 前端组件结构

```
src/
├── components/
│   ├── Layout/
│   │   ├── Header.tsx           # 顶部导航
│   │   ├── Sidebar.tsx          # 左侧市场列表
│   │   ├── MainContent.tsx      # 中央图表区
│   │   ├── RightPanel.tsx       # 右侧订单簿
│   │   └── BottomPanel.tsx      # 底部标签页
│   │
│   ├── Chart/
│   │   ├── TradingViewChart.tsx # TradingView K线图
│   │   ├── TimeShareChart.tsx   # 分时图
│   │   ├── DepthChart.tsx       # 深度图
│   │   └── ChartToolbar.tsx     # 图表工具栏
│   │
│   ├── OrderBook/
│   │   ├── OrderBook.tsx        # 五档订单簿
│   │   ├── QuickOrder.tsx       # 快捷下单
│   │   └── ContractInfo.tsx      # 合约信息
│   │
│   ├── Trade/
│   │   ├── OrderPanel.tsx       # 下单面板
│   │   ├── PriceInput.tsx       # 价格输入(带↑↓)
│   │   ├── QuantityInput.tsx    # 数量输入(带快捷按钮)
│   │   └── TradeConfirm.tsx      # 交易确认弹窗
│   │
│   ├── Market/
│   │   ├── MarketList.tsx       # 市场列表
│   │   ├── StockRow.tsx         # 股票行
│   │   ├── SearchBox.tsx        # 搜索框
│   │   └── TradeDetails.tsx     # 成交明细
│   │
│   ├── Orders/
│   │   ├── ActiveOrders.tsx     # 当前委托
│   │   ├── TradeHistory.tsx     # 历史成交
│   │   ├── FundFlow.tsx         # 资金流水
│   │   └── PositionList.tsx     # 持仓管理
│   │
│   ├── Stats/
│   │   ├── TradeStats.tsx       # 交易统计
│   │   └── StatsChart.tsx       # 统计图表
│   │
│   ├── Alert/
│   │   ├── AlertList.tsx        # 预警列表
│   │   ├── AlertForm.tsx        # 新建预警
│   │   └── AlertNotify.tsx      # 预警通知
│   │
│   └── Settings/
│       ├── ThemeToggle.tsx      # 主题切换
│       ├── HotkeySettings.tsx   # 快捷键设置
│       └── ContractInfo.tsx     # 合约信息
│
├── hooks/
│   ├── useWebSocket.ts          # WebSocket连接
│   ├── useMarketData.ts         # 行情数据
│   ├── useOrders.ts             # 订单管理
│   ├── usePositions.ts          # 持仓管理
│   ├── useHotkeys.ts            # 快捷键
│   └── useAlerts.ts             # 预警系统
│
├── stores/
│   ├── marketStore.ts           # 行情状态
│   ├── orderStore.ts            # 订单状态
│   ├── accountStore.ts          # 账户状态
│   └── settingsStore.ts         # 设置状态
│
└── utils/
    ├── keyboard.ts              # 键盘事件处理
    ├── format.ts                # 数据格式化
    └── calculation.ts           # 计算(盈亏/手续费)
```

---

## 9. 开发计划

### 9.1 阶段划分

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          开发计划 (12周)                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Phase 1: 核心基础 (第1-3周)                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Week 1: 项目骨架 + Chronicle Queue                                  │   │
│  │ Week 2: 撮合引擎核心                                                 │   │
│  │ Week 3: 账户管理 + 清算服务                                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Phase 2: 业务完善 (第4-6周)                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Week 4: 风控服务 + A股规则                                           │   │
│  │ Week 5: WebSocket推送 + REST API                                     │   │
│  │ Week 6: 异步持久化 + H2数据库                                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Phase 3: 前端开发 (第7-9周)                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Week 7: 前端框架搭建 + WebSocket客户端                              │   │
│  │ Week 8: K线图 + 订单簿 + 下单面板                                   │   │
│  │ Week 9: 持仓管理 + 资金流水 + 完整测试                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Phase 4: 优化上线 (第10-12周)                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Week 10: 性能优化 + JVM调优                                          │   │
│  │ Week 11: 监控告警 + 压力测试                                         │   │
│  │ Week 12: 文档完善 + 部署上线                                         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 9.2 里程碑验收标准

| 阶段 | 里程碑 | 验收标准 |
|------|--------|---------|
| Phase 1 | 核心链路跑通 | 订单能撮合、账户能清算 |
| Phase 2 | 业务功能完整 | 风控生效、数据能持久化 |
| Phase 3 | 前端可用 | 完整交易流程可操作 |
| Phase 4 | 性能达标 | 100K TPS、15ms延迟 |

---

## 10. 新浪行情接入设计

### 10.1 新浪财经接口

```
新浪财经提供免费的实时行情数据接口:

接口地址: http://hq.sinajs.cn/list={symbol}

示例:
- 股票: sh600000 (浦发银行)
- 期货: IF2401 (股指期货)
- 期权: 10003720 (50ETF期权)

返回格式:
var hq_str_sh600000="浦发银行,10.05,10.00,10.12,10.15,9.98,10.11,10.12,12345678,123456789,..."
```

### 10.2 行情爬取服务

```java
/**
 * 新浪行情爬取服务
 */
public class SinaMarketDataService {

    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    // 行情缓存
    private final Map<String, MarketData> marketDataCache;

    // 订阅的标的列表
    private final Set<String> subscribedSymbols;

    /**
     * 启动行情爬取
     * 每3秒爬取一次 (新浪限制频率)
     */
    public void start() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                fetchMarketData();
            } catch (Exception e) {
                log.error("Fetch market data failed", e);
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    /**
     * 批量爬取行情
     */
    private void fetchMarketData() {
        // 构建请求URL
        String symbols = String.join(",", subscribedSymbols);
        String url = "http://hq.sinajs.cn/list=" + symbols;

        // HTTP请求
        HttpResponse response = httpClient.get(url);
        String body = response.body();

        // 解析行情数据
        parseMarketData(body);
    }

    /**
     * 解析新浪行情格式
     */
    private void parseMarketData(String body) {
        String[] lines = body.split("\n");
        for (String line : lines) {
            // 解析: var hq_str_sh600000="浦发银行,10.05,..."
            String symbol = extractSymbol(line);
            String data = extractData(line);

            String[] fields = data.split(",");
            if (fields.length >= 32) {
                MarketData marketData = MarketData.builder()
                    .symbol(symbol)
                    .name(fields[0])           // 名称
                    .open(parsePrice(fields[1]))
                    .prevClose(parsePrice(fields[2]))
                    .lastPrice(parsePrice(fields[3]))
                    .high(parsePrice(fields[4]))
                    .low(parsePrice(fields[5]))
                    .bid(parsePrice(fields[6]))
                    .ask(parsePrice(fields[7]))
                    .volume(parseLong(fields[8]))
                    .amount(parseLong(fields[9]))
                    .bid1(parsePrice(fields[10]))
                    .bid1Qty(parseLong(fields[11]))
                    .bid2(parsePrice(fields[12]))
                    .bid2Qty(parseLong(fields[13]))
                    // ... 五档买卖盘
                    .timestamp(System.currentTimeMillis())
                    .build();

                marketDataCache.put(symbol, marketData);

                // 推送行情
                pushMarketData(marketData);
            }
        }
    }
}
```

### 10.3 支持的标的

| 类型 | 代码格式 | 示例 |
|------|---------|------|
| A股主板 | sh/sz + 6位代码 | sh600000, sz000001 |
| 创业板 | sz + 300xxx | sz300001 |
| 科创板 | sh + 688xxx | sh688001 |
| IF期货 | IF + 4位月份 | IF2401 |
| 50ETF期权 | 8位数字 | 10003720 |

---

## 11. 期货期权规则设计

### 11.1 IF股指期货规则

```java
/**
 * IF股指期货合约
 */
public class IFFuturesContract {

    // 合约乘数: 300元/点
    private static final long CONTRACT_MULTIPLIER = 300;

    // 最小变动价位: 0.2点
    private static final long TICK_SIZE = 20; // 放大100倍存储

    // 保证金比例: 12%
    private static final double MARGIN_RATIO = 0.12;

    // 交易时段 (无夜盘)
    private static final TradingSession[] SESSIONS = {
        new TradingSession("09:30-11:30", "上午"),
        new TradingSession("13:00-15:00", "下午")
    };

    /**
     * 计算合约价值
     */
    public long calculateValue(long price) {
        return price * CONTRACT_MULTIPLIER;
    }

    /**
     * 计算保证金
     */
    public long calculateMargin(long price, long quantity) {
        long value = calculateValue(price) * quantity;
        return (long)(value * MARGIN_RATIO);
    }

    /**
     * 开仓/平仓方向
     */
    public enum OffsetFlag {
        OPEN,       // 开仓
        CLOSE,      // 平仓
        CLOSETODAY  // 平今仓
    }
}
```

### 11.2 50ETF期权规则

```java
/**
 * 50ETF期权合约
 */
public class Option50ETFContract {

    // 合约单位: 10000份
    private static final long CONTRACT_SIZE = 10000;

    // 最小变动价位
    // 权利金<=0.1元: 0.0001元
    // 权利金>0.1元: 0.001元
    public long getTickSize(long premium) {
        return premium <= 1000 ? 1 : 10; // 放大10000倍存储
    }

    // 期权类型
    public enum OptionType {
        CALL,   // 认购期权
        PUT     // 认沽期权
    }

    // 行权方式: 欧式 (仅到期日行权)
    public enum ExerciseStyle {
        EUROPEAN
    }

    /**
     * 计算期权保证金 (卖方)
     * 认购期权义务仓保证金:
     * = [合约前结算价 + Max(12%×合约标的前收盘价 - 认购期权虚值, 7%×标的前收盘价)] × 合约单位
     */
    public long calculateMargin(OptionContract contract, long premium) {
        long underlyingPrice = getUnderlyingPrice(); // 50ETF价格

        if (contract.getType() == OptionType.CALL) {
            // 认购期权
            long callValue = Math.max(
                0.12 * underlyingPrice - Math.max(0, contract.getStrikePrice() - underlyingPrice),
                0.07 * underlyingPrice
            );
            return (premium + callValue) * CONTRACT_SIZE;
        } else {
            // 认沽期权
            long putValue = Math.max(
                0.12 * underlyingPrice - Math.max(0, underlyingPrice - contract.getStrikePrice()),
                0.07 * contract.getStrikePrice()
            );
            return (premium + putValue) * CONTRACT_SIZE;
        }
    }
}
```

---

## 12. 服务器资源配置 (8C16G)

### 12.1 线程分配方案

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     8核CPU线程分配方案                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  CPU核心分配:                                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Core 0: Netty Boss线程 (网络接收)                                    │   │
│  │ Core 1-2: Netty Worker线程 (网络处理)                                │   │
│  │ Core 3-5: 撮合引擎线程 (3个标的独立线程)                              │   │
│  │   - 股票撮合线程                                                     │   │
│  │   - IF期货撮合线程                                                   │   │
│  │   - 50ETF期权撮合线程                                                │   │
│  │ Core 6: 清算服务线程                                                  │   │
│  │ Core 7: 持久化线程 + 监控线程                                         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  线程总数: ~15个关键线程                                                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 12.2 内存分配方案

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     16GB内存分配方案                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  JVM堆内存: 10GB                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ -Xms10g -Xmx10g                                                     │   │
│  │ • 年轻代: 4GB                                                        │   │
│  │ • 老年代: 6GB                                                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  直接内存: 4GB                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ -XX:MaxDirectMemorySize=4g                                          │   │
│  │ • Chronicle Queue: 2GB                                               │   │
│  │ • Netty ByteBuf: 1GB                                                 │   │
│  │ • 其他: 1GB                                                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  操作系统预留: 2GB                                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 12.3 JVM配置 (针对8C16G优化)

> 使用 ZGC 替代 G1GC，暂停时间从 10ms 降至 < 1ms

```bash
java \
  -Xms10g -Xmx10g \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -XX:+UseStringDeduplication \
  -XX:+AlwaysPreTouch \
  -XX:+UseCompressedOops \
  -XX:+UseCompressedClassPointers \
  -XX:+UseTLAB \
  -XX:+ResizeTLAB \
  -XX:MaxDirectMemorySize=4g \
  -XX:ConcGCThreads=2 \
  -XX:ParallelGCThreads=6 \
  -Djava.net.preferIPv4Stack=true \
  -jar trading-system.jar
```

---

## 13. 用户认证设计

### 13.1 简单认证方案

```java
/**
 * 简单用户认证服务
 */
public class AuthService {

    // 用户信息存储 (内存)
    private final Map<String, User> users = new ConcurrentHashMap<>();

    // Token存储
    private final Map<String, Long> tokenCache = new ConcurrentHashMap<>();

    /**
     * 用户登录
     */
    public LoginResult login(String username, String password) {
        User user = users.get(username);
        if (user == null) {
            return LoginResult.fail("用户不存在");
        }

        if (!verifyPassword(password, user.getPasswordHash())) {
            return LoginResult.fail("密码错误");
        }

        // 生成Token
        String token = generateToken(username);
        tokenCache.put(token, user.getAccountId());

        return LoginResult.success(token, user.getAccountId());
    }

    /**
     * Token验证
     */
    public Long verifyToken(String token) {
        return tokenCache.get(token);
    }

    /**
     * 登出
     */
    public void logout(String token) {
        tokenCache.remove(token);
    }
}
```

### 13.2 认证流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          认证流程                                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 用户登录                                                                │
│     POST /api/login {username, password}                                    │
│            │                                                                │
│            ▼                                                                │
│     返回: {token, accountId}                                                │
│                                                                             │
│  2. 后续请求                                                                │
│     所有API请求携带Header: Authorization: Bearer {token}                    │
│            │                                                                │
│            ▼                                                                │
│     服务端验证Token有效性                                                    │
│                                                                             │
│  3. WebSocket连接                                                           │
│     ws://host/ws?token={token}                                              │
│            │                                                                │
│            ▼                                                                │
│     建立连接后绑定accountId                                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 14. 待确认事项

### ~~10.1 需要进一步确认的问题~~

**所有需求已确认，无待确认事项。**

---

## 15. API 接口定义

### 15.1 HTTP REST API

#### 15.1.1 用户认证接口

**POST /api/auth/login** - 用户登录

请求参数:
```json
{
  "username": "string",    // 用户名，必填
  "password": "string"    // 密码，必填
}
```

响应参数:
```json
{
  "code": 0,              // 状态码，0=成功
  "message": "success",   // 提示信息
  "data": {
    "token": "string",   // 认证Token
    "accountId": 12345,  // 账户ID
    "expireTime": 1716307200000  // Token过期时间戳
  }
}
```

**POST /api/auth/logout** - 用户登出

请求头: `Authorization: Bearer {token}`

响应参数:
```json
{
  "code": 0,
  "message": "success"
}
```

#### 15.1.2 订单接口

**POST /api/order** - 提交订单

请求头: `Authorization: Bearer {token}`

请求参数:
```json
{
  "symbol": "600000",     // 标的代码，必填
  "direction": 0,         // 方向: 0=买入, 1=卖出，必填
  "orderType": 0,         // 类型: 0=限价, 1=市价, 2=FAK, 3=FOK，必填
  "price": 100000,        // 价格 (放大10000倍)，限价单必填
  "quantity": 100         // 数量，必填
}
```

响应参数:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "orderId": 100001,    // 订单ID
    "status": 0           // 状态: 0=待成交, 1=部分成交, 2=全部成交, 3=已撤销
  }
}
```

**DELETE /api/order/{orderId}** - 撤销订单

请求头: `Authorization: Bearer {token}`

路径参数: `orderId` - 订单ID

响应参数:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "orderId": 100001,
    "status": 3           // 已撤销
  }
}
```

**GET /api/order/{orderId}** - 查询订单详情

请求头: `Authorization: Bearer {token}`

路径参数: `orderId` - 订单ID

响应参数:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "orderId": 100001,
    "symbol": "600000",
    "direction": 0,
    "orderType": 0,
    "price": 100000,
    "quantity": 100,
    "filledQty": 50,
    "avgPrice": 100050,
    "status": 1,
    "createTime": 1716220800000,
    "updateTime": 1716220805000
  }
}
```

**GET /api/orders** - 查询委托列表

请求头: `Authorization: Bearer {token}`

查询参数:
- `symbol` (可选): 标的代码
- `status` (可选): 订单状态
- `startDate` (可选): 开始日期 (YYYY-MM-DD)
- `endDate` (可选): 结束日期 (YYYY-MM-DD)
- `page` (可选): 页码，默认1
- `size` (可选): 每页条数，默认20

响应参数:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 100,
    "page": 1,
    "size": 20,
    "list": [
      {
        "orderId": 100001,
        "symbol": "600000",
        "direction": 0,
        "orderType": 0,
        "price": 100000,
        "quantity": 100,
        "filledQty": 50,
        "avgPrice": 100050,
        "status": 1,
        "createTime": 1716220800000
      }
    ]
  }
}
```

**GET /api/trades** - 查询成交记录

请求头: `Authorization: Bearer {token}`

查询参数:
- `symbol` (可选): 标的代码
- `startDate` (可选): 开始日期
- `endDate` (可选): 结束日期
- `page` (可选): 页码
- `size` (可选): 每页条数

响应参数:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 50,
    "page": 1,
    "size": 20,
    "list": [
      {
        "tradeId": 200001,
        "orderId": 100001,
        "symbol": "600000",
        "direction": 0,
        "price": 100050,
        "quantity": 50,
        "commission": 15,
        "tradeTime": 1716220805000
      }
    ]
  }
}
```

#### 15.1.3 账户接口

**GET /api/account/balance** - 查询账户资金

请求头: `Authorization: Bearer {token}`

响应参数:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "accountId": 12345,
    "balance": 100000000,      // 总资产 (放大10000倍)
    "available": 50000000,     // 可用资金
    "frozen": 30000000,        // 冻结资金
    "margin": 20000000,        // 保证金
    "profitLoss": 5000000,     // 浮动盈亏
    "updateTime": 1716220800000
  }
}
```

**GET /api/account/positions** - 查询持仓

请求头: `Authorization: Bearer {token}`

查询参数:
- `symbol` (可选): 标的代码

响应参数:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      {
        "symbol": "600000",
        "quantity": 1000,        // 持仓数量
        "available": 800,        // 可用数量 (T+1)
        "frozen": 200,           // 冻结数量
        "avgPrice": 100000,      // 成本价
        "profitLoss": 50000      // 浮动盈亏
      }
    ]
  }
}
```

**GET /api/account/flows** - 查询资金流水

请求头: `Authorization: Bearer {token}`

查询参数:
- `flowType` (可选): 流水类型
- `startDate` (可选): 开始日期
- `endDate` (可选): 结束日期
- `page` (可选): 页码
- `size` (可选): 每页条数

响应参数:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 200,
    "page": 1,
    "size": 20,
    "list": [
      {
        "flowId": 300001,
        "amount": -1000500,      // 变动金额 (负数为支出)
        "flowType": 1,           // 类型: 1=买入, 2=卖出, 3=转入, 4=转出
        "refId": 200001,         // 关联ID
        "balanceBefore": 10100500,
        "balanceAfter": 10000000,
        "createTime": 1716220805000
      }
    ]
  }
}
```

#### 15.1.4 行情接口

**GET /api/market/{symbol}** - 查询实时行情

路径参数: `symbol` - 标的代码

响应参数:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "symbol": "600000",
    "name": "浦发银行",
    "open": 100000,           // 开盘价
    "prevClose": 99000,       // 昨收
    "lastPrice": 100500,      // 最新价
    "high": 101000,           // 最高
    "low": 99500,             // 最低
    "volume": 12345678,       // 成交量
    "amount": 1234567890,     // 成交额
    "bid1": 100400,           // 买一价
    "bid1Qty": 1000,          // 买一量
    "bid2": 100300,
    "bid2Qty": 2000,
    "bid3": 100200,
    "bid3Qty": 3000,
    "bid4": 100100,
    "bid4Qty": 4000,
    "bid5": 100000,
    "bid5Qty": 5000,
    "ask1": 100600,           // 卖一价
    "ask1Qty": 1000,          // 卖一量
    "ask2": 100700,
    "ask2Qty": 2000,
    "ask3": 100800,
    "ask3Qty": 3000,
    "ask4": 100900,
    "ask4Qty": 4000,
    "ask5": 101000,
    "ask5Qty": 5000,
    "timestamp": 1716220800000
  }
}
```

**GET /api/market/kline** - 查询K线数据

查询参数:
- `symbol` (必填): 标的代码
- `period` (必填): 周期 (1m/5m/15m/30m/60m/1d/1w/1M)
- `startTime` (可选): 开始时间戳
- `endTime` (可选): 结束时间戳
- `limit` (可选): 返回条数，默认500

响应参数:
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "symbol": "600000",
    "period": "1d",
    "list": [
      {
        "time": 1716220800000,   // 时间戳
        "open": 100000,
        "high": 101000,
        "low": 99500,
        "close": 100500,
        "volume": 12345678
      }
    ]
  }
}
```

### 15.2 WebSocket 消息定义

#### 15.2.1 连接建立

连接地址: `ws://host/ws?token={token}`

连接成功后返回:
```json
{
  "type": "CONNECTED",
  "accountId": 12345,
  "timestamp": 1716220800000
}
```

#### 15.2.2 客户端发送消息

**SUBSCRIBE** - 订阅行情

```json
{
  "type": "SUBSCRIBE",
  "symbols": ["600000", "600001", "IF2401"]
}
```

**UNSUBSCRIBE** - 取消订阅

```json
{
  "type": "UNSUBSCRIBE",
  "symbols": ["600000"]
}
```

#### 15.2.3 服务端推送消息

**MARKET_DATA** - 行情推送 (每3秒)

```json
{
  "type": "MARKET_DATA",
  "data": {
    "symbol": "600000",
    "name": "浦发银行",
    "lastPrice": 100500,
    "open": 100000,
    "high": 101000,
    "low": 99500,
    "volume": 12345678,
    "bid1": 100400,
    "bid1Qty": 1000,
    "ask1": 100600,
    "ask1Qty": 1000,
    "timestamp": 1716220800000
  }
}
```

**ORDER_UPDATE** - 订单状态更新

```json
{
  "type": "ORDER_UPDATE",
  "data": {
    "orderId": 100001,
    "symbol": "600000",
    "direction": 0,
    "status": 1,           // 0=待成交, 1=部分成交, 2=全部成交, 3=已撤销
    "filledQty": 50,
    "remainingQty": 50,
    "avgPrice": 100050,
    "updateTime": 1716220805000
  }
}
```

**TRADE_REPORT** - 成交回报

```json
{
  "type": "TRADE_REPORT",
  "data": {
    "tradeId": 200001,
    "orderId": 100001,
    "symbol": "600000",
    "direction": 0,
    "price": 100050,
    "quantity": 50,
    "commission": 15,
    "tradeTime": 1716220805000
  }
}
```

**POSITION_UPDATE** - 持仓更新

```json
{
  "type": "POSITION_UPDATE",
  "data": {
    "symbol": "600000",
    "quantity": 1000,
    "available": 800,
    "avgPrice": 100000,
    "profitLoss": 50000
  }
}
```

**BALANCE_UPDATE** - 资金变动

```json
{
  "type": "BALANCE_UPDATE",
  "data": {
    "balance": 100000000,
    "available": 50000000,
    "frozen": 30000000,
    "profitLoss": 5000000,
    "change": -1000500,
    "changeType": "BUY_STOCK"
  }
}
```

### 15.3 错误码定义

| 错误码 | 说明 |
|--------|------|
| 0 | 成功 |
| 1001 | Token无效或已过期 |
| 1002 | 用户名或密码错误 |
| 2001 | 资金不足 |
| 2002 | 持仓不足 |
| 2003 | 超出涨跌停限制 |
| 2004 | 非交易时段 |
| 2005 | 超出持仓限额 |
| 2006 | 自成交风险 |
| 3001 | 订单不存在 |
| 3002 | 订单已撤销 |
| 3003 | 订单已成交 |
| 9999 | 系统错误 |

---

## 附录A: 关键技术决策

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 消息队列 | Chronicle Queue | 零拷贝、低延迟、单JVM最优 |
| 撮合线程模型 | 每标的独立线程 | 无锁竞争、CPU亲和性 |
| 数据库 | H2磁盘模式 | 异步持久化、数据安全 |
| WebSocket框架 | Netty | 高性能、原生支持 |
| 前端框架 | React + TradingView | 专业K线图、组件化 |
| GC策略 | ZGC (Generational) | 超低延迟 <1ms，大堆友好 |
| 行情源 | 新浪财经 | 免费、稳定、数据完整 |

---

## 附录B: 项目结构

```
trading-system/
├── trading-core/              # 核心引擎
│   ├── src/main/java/
│   │   ├── matching/          # 撮合引擎
│   │   ├── account/           # 账户管理
│   │   ├── clearing/          # 清算服务
│   │   └── risk/              # 风控服务
│   └── src/test/java/
│
├── trading-gateway/           # 接入网关
│   ├── src/main/java/
│   │   ├── websocket/         # WebSocket服务
│   │   ├── rest/              # REST API
│   │   └── auth/              # 用户认证
│   └── src/test/java/
│
├── trading-market/            # 行情服务
│   ├── src/main/java/
│   │   ├── sina/              # 新浪行情爬取
│   │   └── cache/             # 行情缓存
│   └── src/test/java/
│
├── trading-persistence/       # 持久化层
│   ├── src/main/java/
│   │   ├── h2/                # H2数据库
│   │   └── async/             # 异步持久化
│   └── src/test/java/
│
├── trading-common/            # 公共组件
│   ├── src/main/java/
│   │   ├── model/             # 数据模型
│   │   ├── queue/             # Chronicle Queue
│   │   └── util/              # 工具类
│   └── src/test/java/
│
├── trading-frontend/          # 前端项目
│   ├── src/
│   │   ├── components/        # React组件
│   │   ├── pages/             # 页面
│   │   ├── hooks/             # 自定义Hooks
│   │   └── utils/             # 工具函数
│   └── package.json
│
├── docs/                      # 文档
│   └── technical-solution.md  # 技术方案
│
├── scripts/                   # 脚本
│   ├── start.sh               # 启动脚本
│   └── stop.sh                # 停止脚本
│
└── pom.xml                    # Maven父POM
```

---

**文档版本**: v1.1
**最后更新**: 2026-05-17
**状态**: 需求已确认，可开始开发
**下一步**: 确认技术方案后，开始Phase 1开发
