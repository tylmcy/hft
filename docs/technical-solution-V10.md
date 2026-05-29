# A股交易系统技术方案 V10

> 版本: v10.0
> 日期: 2026-05-24
> 架构师: Trading System Architect
> 状态: **设计完成，可落地开发**

---

## 修订历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| v8.0 | 2026-05-22 | 全新架构：事件溯源+快照恢复模式，单线程账户操作 |
| v8.1 | 2026-05-23 | 修正：内存数据存储，批量处理优化方案，目标100K TPS |
| v8.2 | 2026-05-23 | 修复P0问题：统一内存API、UNFREEZE事件、错误码、优雅关闭、快照原子性 |
| v9.0 | 2026-05-23 | 完整严谨版本：补充所有缺失章节、批量处理完整代码、性能分析、配置参数 |
| v9.1 | 2026-05-24 | 多Agent审查修复：PHASE_CHANGE事件、checksum校验、frozenAmountUsed字段、集合竞价路由、EOD撤单流程 |
| v10.0 | 2026-05-24 | 最终版本：整合所有修复，完整严谨的前后一致版本，准备开发落地 |

---

## 核心设计要点

| 要点 | 说明 |
|------|------|
| **数据存储** | JVM内存为主存储，Chronicle Map仅用于快照持久化 |
| **线程模型** | AccountWorker/MatchingEngine/ClearingWorker 单线程串行处理 |
| **批量处理** | 批量读取CQ → 批量内存操作 → 批量写入CQ → 批量唤醒HTTP |
| **快照机制** | 暂停所有Worker → 写入CM快照 → 写入index.dat（原子rename） |
| **恢复机制** | 读取index.dat → 加载CM到内存 → 重放CQ增量事件 |
| **HTTP同步** | CompletableFuture + 超时机制，替代CountDownLatch |

**文件命名约定**：
- `.dat`: Chronicle Map快照文件后缀（如 `account_snapshot_12345.dat`）
- `.cq`: Chronicle Queue事件队列文件后缀（如 `incoming_event.cq`），实际磁盘文件使用 `.cq4` 扩展名
- `.checksum`: 校验和文件后缀（如 `account_snapshot_12345.dat.checksum`）
- 文档中 `orders.cm`、`processed_trades.cm` 等是概念性引用，实际文件使用 `.dat` 后缀

## 核心配置常量

```java
public class TradingConfig {
    // 批量处理配置
    public static final int BATCH_SIZE = 50;          // 每批次最大事件数
    public static final long TIMEOUT_MS = 1;          // 批量读取超时（毫秒）
    public static final int SNAPSHOT_INTERVAL_S = 60; // 快照间隔（秒）

    // 超时配置
    public static final long HTTP_TIMEOUT_MS = 5000;  // HTTP请求超时（毫秒）
    public static final long WS_IDLE_TIMEOUT_MS = 300000; // WebSocket空闲超时（5分钟）
    public static final long WS_HEARTBEAT_MS = 30000;     // WebSocket心跳间隔（30秒）

    // 限流配置
    public static final int ORDER_TPS_LIMIT = 100;    // 下单TPS限制
    public static final int QUERY_QPS_LIMIT = 1000;   // 查询QPS限制
}
```

---

## 目录

1. [需求与约束](#1-需求与约束)
2. [系统架构](#2-系统架构)
3. [数据模型](#3-数据模型)
4. [线程模型](#4-线程模型)
5. [核心流程](#5-核心流程)
6. [并发控制](#6-并发控制)
7. [持久化与恢复](#7-持久化与恢复)
8. [行情服务](#8-行情服务)
9. [API设计](#9-api设计)
10. [前端设计](#10-前端设计)
11. [性能分析](#11-性能分析)
12. [部署配置](#12-部署配置)
13. [监控与运维](#13-监控与运维)
14. [错误码定义](#14-错误码定义)
15. [业务规则](#15-业务规则)
16. [SOD与EOD流程](#16-sod与eod流程)
17. [快照与恢复机制](#17-快照与恢复机制)
18. [优雅关闭流程](#18-优雅关闭流程)

---

## 1. 需求与约束

### 1.1 系统定位

**模拟交易系统**

| 定位 | 说明 |
|------|------|
| **系统性质** | 模拟交易系统，非真实交易 |
| **资金来源** | 入金手工上账（管理员操作） |
| **交易逻辑** | 完整的交易、清结算逻辑，与真实交易一致 |
| **撮合模式** | 内部用户之间撮合（含做市商策略用户） |
| **行情来源** | 新浪行情仅展示，五档行情来自撮合引擎订单簿 |
| **做市商** | 通过OpenAPI接入，做市商系统独立JVM（后续独立设计） |

### 1.2 业务需求

| 需求项 | 需求描述 |
|--------|---------|
| **用户体系** | 用户注册时自动创建账户，余额为0，管理员手动入金 |
| **交易品种** | A股（主板/创业板/科创板）、IF股指期货、50ETF期权 |
| **账户体系** | 多用户，每个用户独立账户，支持1000+并发用户 |
| **撮合模式** | 内部撮合，用户间订单撮合（含做市商策略用户） |
| **交易时段** | A股交易时段（无夜盘），支持集合竞价与连续竞价 |
| **前端界面** | 专业交易终端：TradingView K线图、订单簿、下单面板 |
| **客户端支持** | 仅Web端，不支持移动端 |
| **API接入** | 支持量化交易用户和做市商通过OpenAPI下单 |

### 1.3 性能目标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| **吞吐量** | ≥ 100,000 TPS | 订单+撤单总量（启用批量处理） |
| **HTTP返回延迟** | ≤ 5ms (P99) | 下单到返回orderId |
| **端到端延迟** | ≤ 15ms (P99) | 从下单到收到成交回报 |
| **清算吞吐** | ≥ 5,000 TPS | 成交清算处理能力（假设5%成交率） |
| **可用性** | 99.99% | 年度可用性 |

### 1.4 技术约束

| 约束项 | 约束内容 |
|--------|---------|
| **部署模式** | 单机单JVM，前后端一体化部署 |
| **Java版本** | Java 21 LTS |
| **Spring Boot** | 3.x + 虚拟线程（Project Loom） |
| **Chronicle版本** | 最新稳定版 |
| **服务器配置** | 阿里云 8核CPU / 16GB内存 / 500GB SSD |
| **GC策略** | ZGC（Generational），暂停时间 < 1ms |

### 1.5 设计原则

| 原则 | 说明 |
|------|------|
| **数据一致性优先** | 所有状态变更通过CQ顺序处理，保证强一致 |
| **简单可靠** | 单线程处理账户操作，避免并发复杂度 |
| **可恢复性** | 快照+增量恢复，RPO≈1分钟 |

---

## 2. 系统架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              单JVM进程                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         Tomcat容器                                   │   │
│  │  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐            │   │
│  │  │  静态资源     │  │  REST API     │  │  WebSocket    │            │   │
│  │  │  (前端SPA)    │  │  (虚拟线程)   │  │  (Netty)      │            │   │
│  │  └───────────────┘  └───────────────┘  └───────────────┘            │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     account_input CQ                                 │   │
│  │  (NEW_ORDER/CANCEL_ORDER/SETTLEMENT/SNAPSHOT/PHASE_CHANGE/入金)      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                AccountWorker (单线程，批量处理)                       │   │
│  │  • 冻结/解冻资金和持仓（内存操作）                                    │   │
│  │  • 结算转账（内存操作）                                               │   │
│  │  • 输出: account_output CQ + matching_input CQ                       │   │
│  │  • 批量唤醒HTTP线程 (CompletableFuture)                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│              ├───────────────┴───────────────┐                             │
│              ▼                               ▼                             │
│  ┌─────────────────────┐        ┌─────────────────────┐                   │
│  │   account_output CQ │        │   matching_input CQ │                   │
│  │ (冻结/解冻/持仓变更) │        │ (下单/撤单/快照)    │                   │
│  └─────────────────────┘        └─────────────────────┘                   │
│              │                               │                             │
│              ▼                               ▼                             │
│  ┌─────────────────────┐        ┌─────────────────────────────────────┐   │
│  │ 用于WebSocket推送   │        │              MatchingEngine          │   │
│  │ (账户/持仓变动)     │        │        (单线程，批量处理)             │   │
│  └─────────────────────┘        │  • 更新内存OrderBook                 │   │
│                                 │  • 订单撮合                           │   │
│                                 │  • 输出: matching_output CQ           │   │
│                                 │  • 生成成交/状态事件                  │   │
│                                 └─────────────────────────────────────┘   │
│                                              │                              │
│                                              ▼                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     matching_output CQ                               │   │
│  │           (ORDER_UPDATE/TRADE/CANCEL_SUCCESS/CANCEL_REJECT)          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │              ClearingWorker (单线程，批量处理)                        │   │
│  │  • 更新订单状态 (PENDING→NEW, PARTIAL_FILLED→FILLED)                 │   │
│  │  • 计算结算金额                                                      │   │
│  │  • 输出: account_settlement CQ                                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     account_settlement CQ                            │   │
│  │           (SETTLEMENT事件/撤单解冻事件)                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                AccountWorker (处理SETTLEMENT/UNFREEZE)               │   │
│  │  • 结算转账（内存操作）                                               │   │
│  │  • WebSocket推送成交/撤单结果                                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                SnapshotWorker (定时，写入SNAPSHOT事件)                │   │
│  │  • 写入SNAPSHOT到account_input/matching_input CQ                      │   │
│  │  • Worker处理SNAPSHOT时写快照CM → 写index.dat                         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**CQ文件命名约定**：

| CQ名称 | 用途 | 事件类型 |
|--------|------|---------|
| `account_input.cq4` | AccountWorker输入队列 | NEW_ORDER/CANCEL_ORDER/SETTLEMENT/SNAPSHOT/PHASE_CHANGE/入金 |
| `account_output.cq4` | AccountWorker输出队列（用于WebSocket推送） | 冻结/解冻/加减钱/持仓变更 |
| `matching_input.cq4` | MatchingEngine输入队列 | NEW_ORDER/CANCEL_ORDER/SNAPSHOT |
| `matching_output.cq4` | MatchingEngine输出队列 | ORDER_UPDATE/TRADE/CANCEL_SUCCESS/CANCEL_REJECT |
| `account_settlement.cq4` | ClearingWorker输出队列（AccountWorker消费） | SETTLEMENT事件/撤单解冻事件 |

### 2.2 数据流图

```
【下单流程 - HTTP同步等待冻结完成】

HTTP线程(虚拟线程)
    │
    │ 1. 参数校验、幂等检查
    │ 2. 写入 account_input CQ (NEW_ORDER)
    │ 3. CompletableFuture.await(5s)
    ▼
AccountWorker(单线程)
    │
    │ 4. 批量读取 account_input CQ
    │ 5. 批量内存更新账户/持仓
    │ 6. 批量写入 matching_input CQ
    │ 7. 批量写入 account_output CQ (用于WebSocket推送)
    │ 8. 批量 CompletableFuture.complete()
    ▼
HTTP线程返回orderId ←─────────────────────────────┘


【撮合流程 - 异步，HTTP已返回】

MatchingEngine(单线程)
    │
    │ 9. 批量读取 matching_input CQ
    │ 10. 批量更新内存OrderBook
    │ 11. 批量撮合
    │ 12. 批量写入 matching_output CQ
    ▼


【清算流程 - 异步】

ClearingWorker(单线程)
    │
    │ 13. 批量读取 matching_output CQ
    │ 14. 批量更新订单状态（内存）
    │ 15. 批量写入 account_settlement CQ (SETTLEMENT/UNFREEZE)
    ▼


【结算/解冻流程 - 异步】

AccountWorker(单线程)
    │
    │ 16. 批量读取 account_settlement CQ
    │ 17. 批量内存更新账户/持仓
    │ 18. 批量写入 account_output CQ (用于WebSocket推送)
    │ 19. WebSocket推送成交/撤单结果
    ▼


【快照流程 - 定时每分钟】

SnapshotWorker
    │
    │ 20. 写入SNAPSHOT到 account_input CQ
    │ 21. 等待 AccountWorker 处理完快照
    │ 22. 写入SNAPSHOT到 matching_input CQ
    │ 23. 等待 MatchingEngine 处理完快照
    ▼
```

### 2.3 组件职责

| 组件 | 职责 | 线程模型 | 数据存储 |
|------|------|---------|---------|
| **REST API** | 接收HTTP请求，参数校验，写入CQ | Tomcat虚拟线程 | - |
| **WebSocket** | 实时推送行情、订单、成交 | Netty EventLoop | - |
| **AccountWorker** | 冻结/解冻/结算 | 单线程 | **内存**（快照时写CM） |
| **MatchingEngine** | 订单撮合、五档行情推送 | 单线程 | 内存OrderBook（快照时写CM） |
| **ClearingWorker** | 订单状态更新，结算计算 | 单线程 | 内存（快照时写CM） |
| **SnapshotWorker** | 定时快照到CM文件 | 单线程定时任务 | CM文件 |
| **PhaseManager** | 交易阶段切换 | 单线程定时任务 | volatile |
| **RiskMonitor** | 风险度监控、强平触发 | 单线程定时任务 | 读内存 |

---

## 3. 数据模型

### 3.1 内存数据结构（主存储）

#### 3.1.1 Account（账户）

```java
public class Account {
    long accountId;
    long userId;
    long balance;        // 账户余额（分）
    long available;      // 可用资金（分）
    long frozen;         // 冻结资金（分）
    long marginFrozen;   // 保证金占用（分）
    long orderFrozen;    // 委托冻结（分）
    long debt;           // 负债/穿仓金额（分）
    long unrealizedPnl;  // 未实现盈亏（分）
    double riskRatio;    // 风险比率（期货/期权账户，股票账户不计算）
    AccountStatus status; // ACTIVE/SUSPENDED/LIQUIDATING/NEGATIVE_BALANCE/CLOSED
    long updateTime;
}
```

**不变式**：
- `balance = available + frozen`
- `frozen = marginFrozen + orderFrozen`

#### 3.1.2 Position（持仓）

```java
public class Position {
    long accountId;
    String symbol;
    int symbolCode;      // 数值代码，加速处理
    long quantity;       // 持仓数量（正数=多头，负数=空头）
    long available;      // 可用数量（T+0）
    long frozen;         // 冻结数量
    long t1Locked;       // T+1锁定数量
    long avgPrice;       // 成本价（分）
    long unrealizedPnl;  // 未实现盈亏（分）
    long updateTime;
}
```

**不变式**：`|quantity| = available + frozen + t1Locked`

#### 3.1.3 Order（订单）

```java
public class Order {
    long orderId;
    String symbol;
    int symbolCode;
    Side side;           // BUY/SELL
    OrderType orderType; // LIMIT/MARKET/FAK/FOK
    long price;          // 价格（分），市价单为0
    long quantity;       // 原始数量
    long filledQuantity; // 已成交数量
    long remainingQty;   // 剩余数量
    long frozenAmount;   // 冻结金额（分）
    long maxPrice;       // 市价买单最大价格限制（涨停价）
    long minPrice;       // 市价卖单最小价格限制（跌停价）
    long accountId;
    long userId;
    OrderStatus status;  // PENDING/PARTIAL_FILLED/FILLED/CANCELLED/REJECTED
    String cancelReason; // 撤销原因
    String idempotencyKey; // 幂等键
    long createTime;
    long updateTime;
}
```

#### 3.1.4 Instrument（合约/证券信息）

```java
public class Instrument {
    String symbol;           // 证券代码
    int symbolCode;          // 数值代码，加速处理
    InstrumentType type;     // STOCK/FUTURE/OPTION
    String name;             // 证券名称
    long prevClose;          // 前收盘价（分）
    double limitUpRatio;     // 涨停比例（如0.10表示10%）
    double limitDownRatio;   // 跌停比例
    long lotSize;            // 每手数量（股票通常为100）
    long minPriceIncrement;  // 最小价格变动单位（分）
    long commissionPerLot;   // 每手佣金（分）
    long stampDutyRate;      // 印花税率（万分比，仅卖方收取）
    long transferFeeRate;    // 过户费率（万分比）
    long updateTime;
}

public enum InstrumentType {
    STOCK,      // 股票
    FUTURE,     // 期货
    OPTION      // 期权
}
```

**用途说明**：
- `prevClose`: 用于计算涨停价/跌停价
- `limitUpRatio`/`limitDownRatio`: 涨跌停幅度，如主板股票为10%，科创板/创业板为20%
- `lotSize`: 下单数量必须是lotSize的整数倍
- `minPriceIncrement`: 价格最小变动单位（A股通常为1分）
- `commissionPerLot`: 佣金，按手数计算
- `stampDutyRate`: 印花税，仅卖方收取（A股为千分之一）
- `transferFeeRate`: 过户费（A股为万分之0.2）

**订单状态机**：
```
HTTP下单请求
    │
    ▼
REJECTED ← 风控检查失败
    │
    ▼ 写入orders.cm (status=PENDING)
    │ 写入incoming_event.cq
    │
    ▼ AccountWorker冻结资金
    │ 写入match_orders.cq
    │
    ▼ MatchingEngine放入OrderBook
    │ 写入ORDER_UPDATE到trade.cq
    │
PENDING ← orders.cm状态（等待确认，过渡状态）
    │
    ▼ ClearingWorker读取ORDER_UPDATE
    │ 更新orders.cm：PENDING → NEW
    │
NEW ← 在OrderBook中，已确认
    │
    ├──────────────────┐
    │                  │
    ▼ 部分成交         ▼ 撤单
PARTIAL_FILLED    CANCELLED
    │                  
    ▼ 全部成交         
FILLED
```

**订单状态说明**：

| 状态 | 含义 | 是否在OrderBook | 说明 |
|------|------|-----------------|------|
| REJECTED | 入库前被拒绝 | ❌ | 风控检查失败 |
| PENDING | 已入库，等待入OrderBook | ❌ | 过渡状态，应快速变为NEW |
| NEW | 已入OrderBook，等待撮合 | ✅ | 明确在撮合中 |
| PARTIAL_FILLED | 部分成交，仍在OrderBook | ✅ | 继续撮合 |
| FILLED | 全部成交，已出OrderBook | ❌ | 撮合完成 |
| CANCELLED | 已撤销，已出OrderBook | ❌ | 用户撤销或系统撤销 |

**监控告警**：订单在 PENDING 状态超过5秒，应告警排查（CQ积压？MatchingEngine阻塞？）

#### 3.1.4 OrderBook（订单簿）

```java
public class OrderBook {
    private final String symbol;
    // 买单：价格从高到低排序（使用反向比较器）
    private final TreeMap<Long, List<Order>> bidOrders = new TreeMap<>(Comparator.reverseOrder());
    // 卖单：价格从低到高排序（自然顺序）
    private final TreeMap<Long, List<Order>> askOrders = new TreeMap<>();
    // 订单ID到订单的映射
    private final Map<Long, Order> orderMap = new HashMap<>();

    // 同价格级别内，按createTime排序（价格-时间优先）

    /**
     * 添加订单到订单簿
     */
    public void addOrder(Order order) {
        TreeMap<Long, List<Order>> book = order.side == BUY ? bidOrders : askOrders;
        book.computeIfAbsent(order.price, k -> new ArrayList<>()).add(order);
        orderMap.put(order.orderId, order);
    }

    /**
     * 从订单簿移除订单
     */
    public Order removeOrder(long orderId) {
        Order order = orderMap.remove(orderId);
        if (order == null) return null;

        TreeMap<Long, List<Order>> book = order.side == BUY ? bidOrders : askOrders;
        List<Order> ordersAtPrice = book.get(order.price);
        if (ordersAtPrice != null) {
            ordersAtPrice.remove(order);
            if (ordersAtPrice.isEmpty()) {
                book.remove(order.price);
            }
        }
        return order;
    }

    /**
     * 获取最优对手方订单
     */
    public Order getBestCounterOrder(Side side) {
        TreeMap<Long, List<Order>> book = side == BUY ? askOrders : bidOrders;
        if (book.isEmpty()) return null;
        List<Order> ordersAtBestPrice = book.firstEntry().getValue();
        return ordersAtBestPrice.isEmpty() ? null : ordersAtBestPrice.get(0);
    }

    /**
     * 获取顶部N档买盘
     */
    public List<OrderBookLevel> getTopBidLevels(int count) {
        List<OrderBookLevel> levels = new ArrayList<>();
        int remaining = count;
        for (Map.Entry<Long, List<Order>> entry : bidOrders.entrySet()) {
            if (remaining-- == 0) break;
            long volume = entry.getValue().stream().mapToLong(o -> o.remainingQty).sum();
            levels.add(new OrderBookLevel(entry.getKey(), volume));
        }
        // 填充空档位
        while (levels.size() < count) {
            levels.add(new OrderBookLevel(0, 0));
        }
        return levels;
    }

    /**
     * 获取顶部N档卖盘
     */
    public List<OrderBookLevel> getTopAskLevels(int count) {
        List<OrderBookLevel> levels = new ArrayList<>();
        int remaining = count;
        for (Map.Entry<Long, List<Order>> entry : askOrders.entrySet()) {
            if (remaining-- == 0) break;
            long volume = entry.getValue().stream().mapToLong(o -> o.remainingQty).sum();
            levels.add(new OrderBookLevel(entry.getKey(), volume));
        }
        // 填充空档位
        while (levels.size() < count) {
            levels.add(new OrderBookLevel(0, 0));
        }
        return levels;
    }

    /**
     * 获取所有卖单（用于自成交检查）
     */
    public List<Order> getAsks() {
        List<Order> allAsks = new ArrayList<>();
        for (List<Order> ordersAtPrice : askOrders.values()) {
            allAsks.addAll(ordersAtPrice);
        }
        return allAsks;
    }

    /**
     * 获取所有买单（用于自成交检查）
     */
    public List<Order> getBids() {
        List<Order> allBids = new ArrayList<>();
        for (List<Order> ordersAtPrice : bidOrders.values()) {
            allBids.addAll(ordersAtPrice);
        }
        return allBids;
    }

    /**
     * FOK订单：计算指定价格的可成交量
     */
    public long getAvailableQuantityAtPrice(Side side, long price) {
        TreeMap<Long, List<Order>> book = side == BUY ? askOrders : bidOrders;
        long totalQty = 0;

        if (side == BUY) {
            // 买单：检查卖单中价格≤buyPrice的所有订单
            for (Map.Entry<Long, List<Order>> entry : book.headMap(price, true).entrySet()) {
                totalQty += entry.getValue().stream().mapToLong(o -> o.remainingQty).sum();
            }
        } else {
            // 卖单：检查买单中价格≥sellPrice的所有订单
            for (Map.Entry<Long, List<Order>> entry : book.tailMap(price, true).entrySet()) {
                totalQty += entry.getValue().stream().mapToLong(o -> o.remainingQty).sum();
            }
        }
        return totalQty;
    }
}

/**
 * 订单簿价格档位
 */
public class OrderBookLevel {
    private long price;
    private long volume;

    public OrderBookLevel(long price, long volume) {
        this.price = price;
        this.volume = volume;
    }

    public long getPrice() { return price; }
    public long getVolume() { return volume; }
}
```

### 3.2 内存数据存储类

#### 3.2.1 InMemoryAccountStore

```java
public class InMemoryAccountStore {
    private final ConcurrentHashMap<Long, Account> accounts = new ConcurrentHashMap<>();

    public Account getAccount(long accountId) {
        return accounts.get(accountId);
    }

    /**
     * 冻结资金（单线程调用，无需锁）
     */
    public ProcessResult freeze(long accountId, long amount) {
        Account acc = accounts.get(accountId);
        if (acc == null) {
            return ProcessResult.fail(2004, "账户不存在");
        }
        if (acc.status != AccountStatus.ACTIVE) {
            return ProcessResult.fail(2003, "账户状态异常: " + acc.status);
        }
        if (acc.available < amount) {
            return ProcessResult.fail(2001, "余额不足");
        }
        
        acc.available -= amount;
        acc.frozen += amount;
        acc.orderFrozen += amount;
        acc.updateTime = System.currentTimeMillis();
        
        return ProcessResult.success();
    }

    /**
     * 解冻资金（单线程调用）
     */
    public void unfreeze(long accountId, long amount) {
        Account acc = accounts.get(accountId);
        if (acc != null) {
            acc.frozen -= amount;
            acc.orderFrozen -= amount;
            acc.available += amount;
            acc.updateTime = System.currentTimeMillis();
        }
    }

    /**
     * 快照时写入CM
     */
    public void writeToChronicleMap(ChronicleMap<String, AccountCM> cm) {
        for (Map.Entry<Long, Account> entry : accounts.entrySet()) {
            AccountCM cmValue = new AccountCM();
            copyToCM(entry.getValue(), cmValue);
            cm.put(String.valueOf(entry.getKey()), cmValue);
        }
    }
    
    /**
     * 从CM加载到内存（恢复时调用）
     */
    public void loadFromChronicleMap(ChronicleMap<String, AccountCM> cm) {
        for (Map.Entry<String, AccountCM> entry : cm.entrySet()) {
            Account acc = copyFromCM(entry.getValue());
            accounts.put(acc.accountId, acc);
        }
    }
}
```

#### 3.2.2 InMemoryPositionStore

```java
public class InMemoryPositionStore {
    private final ConcurrentHashMap<String, Position> positions = new ConcurrentHashMap<>();

    private String key(long accountId, String symbol) {
        return accountId + ":" + symbol;
    }

    public Position getPosition(long accountId, String symbol) {
        return positions.get(key(accountId, symbol));
    }

    /**
     * 冻结持仓（卖单）
     */
    public ProcessResult freezePosition(long accountId, String symbol, long quantity) {
        String k = key(accountId, symbol);
        Position pos = positions.computeIfAbsent(k, x -> new Position(accountId, symbol));
        
        if (pos.available < quantity) {
            return ProcessResult.fail(2002, "持仓不足");
        }
        
        pos.available -= quantity;
        pos.frozen += quantity;
        pos.updateTime = System.currentTimeMillis();
        
        return ProcessResult.success();
    }

    /**
     * 解冻持仓（撤单时）
     */
    public void unfreezePosition(long accountId, String symbol, long quantity) {
        Position pos = positions.get(key(accountId, symbol));
        if (pos != null) {
            pos.frozen -= quantity;
            pos.available += quantity;
            pos.updateTime = System.currentTimeMillis();
        }
    }

    /**
     * 添加新持仓（买入时）
     */
    public void addPosition(Position pos) {
        positions.put(key(pos.accountId, pos.symbol), pos);
    }

    public void writeToChronicleMap(ChronicleMap<String, PositionCM> cm) {
        for (Map.Entry<String, Position> entry : positions.entrySet()) {
            PositionCM cmValue = new PositionCM();
            copyToCM(entry.getValue(), cmValue);
            cm.put(entry.getKey(), cmValue);
        }
    }
    
    public void loadFromChronicleMap(ChronicleMap<String, PositionCM> cm) {
        for (Map.Entry<String, PositionCM> entry : cm.entrySet()) {
            Position pos = copyFromCM(entry.getValue());
            positions.put(entry.getKey(), pos);
        }
    }
}
```

#### 3.2.3 InMemoryOrderStore

```java
public class InMemoryOrderStore {
    private final ConcurrentHashMap<Long, Order> orders = new ConcurrentHashMap<>();

    public Order getOrder(long orderId) {
        return orders.get(orderId);
    }

    public void addOrder(Order order) {
        orders.put(order.orderId, order);
    }

    public void updateStatus(long orderId, OrderStatus status) {
        Order order = orders.get(orderId);
        if (order != null) {
            order.status = status;
            order.updateTime = System.currentTimeMillis();
        }
    }
    
    public void updateFilledQuantity(long orderId, long filledQty) {
        Order order = orders.get(orderId);
        if (order != null) {
            order.filledQuantity += filledQty;
            order.remainingQty -= filledQty;
            order.updateTime = System.currentTimeMillis();
        }
    }

    public void removeOrder(long orderId) {
        orders.remove(orderId);
    }

    public void clear() {
        orders.clear();
    }

    public void writeToChronicleMap(ChronicleMap<String, OrderCM> cm) {
        for (Map.Entry<Long, Order> entry : orders.entrySet()) {
            OrderCM cmValue = new OrderCM();
            copyToCM(entry.getValue(), cmValue);
            cm.put(String.valueOf(entry.getKey()), cmValue);
        }
    }
    
    public void loadFromChronicleMap(ChronicleMap<String, OrderCM> cm) {
        for (Map.Entry<String, OrderCM> entry : cm.entrySet()) {
            Order order = copyFromCM(entry.getValue());
            orders.put(order.orderId, order);
        }
    }
}
```

### 3.3 Chronicle Queue 事件结构

#### 3.3.1 account_input CQ

```
AccountInputEvent {
    eventType: enum,      // NEW_ORDER / CANCEL_ORDER / SETTLEMENT / SNAPSHOT / PHASE_CHANGE / DEPOSIT
    orderId: long,
    accountId: long,
    userId: long,
    symbol: String,
    side: enum,           // BUY / SELL
    orderType: enum,      // LIMIT / MARKET / FAK / FOK
    price: long,
    quantity: long,
    frozenAmount: long,
    tradeId: String,      // 成交ID（SETTLEMENT事件专用，用于幂等检查）
    settlementType: enum, // BUYER / SELLER (仅SETTLEMENT事件)
    frozenAmountUsed: long, // 本次结算使用的冻结金额/数量（仅SETTLEMENT事件）
                           // 买方：冻结金额（分）；卖方：冻结持仓数量
    commission: long,     // 佣金（仅SETTLEMENT事件）
    phase: enum,          // CLOSED / CALL_AUCTION_OPEN / CONTINUOUS_TRADING / CALL_AUCTION_CLOSE (仅PHASE_CHANGE事件)
    isSystemOrder: boolean, // 是否系统生成订单（强平/做市）
    systemReason: String,   // 系统订单原因：LIQUIDATION/MAKER
    reason: String,       // 撤单/拒绝原因 (CANCEL_ORDER事件)
    depositAmount: long,  // 入金金额（仅DEPOSIT事件）
    idempotencyKey: String,
    timestamp: long,
    cqIndex: long         // SNAPSHOT事件专用：CQ写入位置，用于快照文件命名
}
```

| 事件类型 | 来源 | 说明 |
|---------|------|------|
| NEW_ORDER | HTTP线程 | 下单请求 |
| CANCEL_ORDER | HTTP线程/EOD | 撤单请求（含EOD自动撤单） |
| SETTLEMENT | AccountWorker（从account_settlement CQ读取） | 结算请求（买卖双方各一条，含frozenAmountUsed） |
| SNAPSHOT | SnapshotWorker | 触发快照保存 |
| PHASE_CHANGE | SnapshotWorker | 交易时段切换（触发集合竞价撮合） |
| DEPOSIT | 管理员操作 | 入金请求 |

#### 3.3.2 matching_input CQ

```
MatchingInputEvent {
    eventType: enum,      // NEW_ORDER / CANCEL_ORDER / SNAPSHOT
    orderId: long,
    symbol: String,
    side: enum,
    orderType: enum,
    price: long,
    quantity: long,
    accountId: long,
    userId: long,
    frozenAmount: long,
    idempotencyKey: String,
    timestamp: long,
    cqIndex: long         // SNAPSHOT事件专用：CQ写入位置，用于快照文件命名
}
```

#### 3.3.3 matching_output CQ

```
MatchingOutputEvent {
    eventType: enum,      // ORDER_UPDATE / TRADE / CANCEL_SUCCESS / CANCEL_REJECT

    // ORDER_UPDATE专用字段
    orderId: long,
    accountId: long,
    userId: long,
    symbol: String,
    status: enum,         // NEW / PARTIAL_FILLED / FILLED / CANCELLED
    filledQuantity: long,
    remainingQty: long,

    // TRADE专用字段
    tradeId: String,      // 确定性ID: "takerOrderId_makerOrderId"
    symbol: String,       // 成交品种
    takerOrderId: long,
    makerOrderId: long,
    price: long,
    quantity: long,
    buyAccountId: long,
    sellAccountId: long,
    buyOrderId: long,
    sellOrderId: long,
    buyCommission: long,
    sellCommission: long,

    // CANCEL_SUCCESS专用字段
    unfreezeAmount: long,
    unfreezeQty: long,
    side: enum,

    // CANCEL_REJECT专用字段
    rejectReason: enum,   // ORDER_NOT_FOUND / ORDER_ALREADY_FILLED / ORDER_ALREADY_CANCELLED

    timestamp: long
}
```

**事件类型说明**：

| 事件类型 | 触发时机 | 处理者 |
|---------|---------|--------|
| ORDER_UPDATE | 订单放入OrderBook时 | ClearingWorker更新状态PENDING→NEW |
| TRADE | 撮合成交时 | ClearingWorker执行清算 |
| CANCEL_SUCCESS | 撤销成功时 | ClearingWorker执行解冻 |
| CANCEL_REJECT | 撤销失败时 | ClearingWorker根据reason处理 |

**TradeId确定性生成**：

```java
// TradeId = takerOrderId + "_" + makerOrderId
// 保证重复撮合产生相同tradeId，支持幂等
public String generateTradeId(long takerOrderId, long makerOrderId) {
    return takerOrderId + "_" + makerOrderId;
}

// 示例：买单12345吃掉卖单67890 → tradeId = "12345_67890"
```

#### 3.3.4 account_settlement CQ

```
AccountSettlementEvent {
    eventType: enum,      // SETTLEMENT / UNFREEZE

    // SETTLEMENT专用字段
    orderId: long,
    accountId: long,
    symbol: String,
    tradeId: String,      // 成交ID，用于幂等检查
    settlementType: enum, // BUYER / SELLER
    price: long,
    quantity: long,
    frozenAmountUsed: long, // 本次结算使用的冻结金额/数量
    commission: long,

    // UNFREEZE专用字段
    unfreezeAmount: long,
    unfreezeQty: long,
    side: enum,

    timestamp: long
}
```

| 事件类型 | 来源 | 说明 |
|---------|------|------|
| SETTLEMENT | ClearingWorker | 结算请求（买卖双方各一条） |
| UNFREEZE | ClearingWorker | 解冻请求（撤单时） |

#### 3.3.5 account_output CQ

```
AccountOutputEvent {
    eventType: enum,      // FREEZE / UNFREEZE / BALANCE_CHANGE / POSITION_CHANGE
    accountId: long,
    symbol: String,

    // 账户变动
    balanceDelta: long,
    availableDelta: long,
    frozenDelta: long,

    // 持仓变动
    quantityDelta: long,
    positionAvailableDelta: long,
    positionFrozenDelta: long,
    t1LockedDelta: long,

    reason: String,       // 变动原因
    timestamp: long
}
```

| 事件类型 | 触发时机 | 用途 |
|---------|---------|------|
| FREEZE | 下单冻结时 | WebSocket推送账户/持仓变动 |
| UNFREEZE | 撤单解冻时 | WebSocket推送账户/持仓变动 |
| BALANCE_CHANGE | 结算/入金时 | WebSocket推送账户变动 |
| POSITION_CHANGE | 结算时 | WebSocket推送持仓变动 |

### 3.4 快照索引文件

系统使用两个独立的索引文件：

**3.4.1 index.dat（主快照索引）**

原子写入：先写临时文件，再rename

```
SnapshotIndex {
    snapshotVersion: long,        // 快照版本号（时间戳）
    snapshotTime: String,         // 快照时间 YYYYMMDD_HHMMSS
    
    // CQ索引
    incomingEventIndex: long,
    matchOrdersIndex: long,
    tradeIndex: long,
    
    // 快照文件名
    accountsFile: String,
    positionsFile: String,
    ordersFile: String,
    orderbookFile: String,
    
    createTime: long
}
```

**3.4.2 clearing_index.dat（ClearingWorker进度索引）**

ClearingWorker不生成完整快照，仅记录trades CQ处理位置，独立于主快照索引：

```
// ChronicleMap结构，单key-value
clearing_index.dat:
    key: "lastProcessedIndex"
    value: long (trades CQ index)
```

**两个文件的区别**：
- `index.dat`: 由SnapshotWorker写入，包含所有CQ索引和快照文件引用，用于系统恢复
- `clearing_index.dat`: 由ClearingWorker每批次写入，仅记录trades CQ进度，用于ClearingWorker恢复

### 3.5 FutureManager（替代CountDownLatch）

```java
public class FutureManager {
    private final ConcurrentHashMap<Long, CompletableFuture<ProcessResult>> futures = 
        new ConcurrentHashMap<>();

    public CompletableFuture<ProcessResult> createFuture(long orderId) {
        CompletableFuture<ProcessResult> future = new CompletableFuture<>();
        futures.put(orderId, future);
        return future.orTimeout(5, TimeUnit.SECONDS)
                     .exceptionally(ex -> ProcessResult.timeout("请求超时"));
    }

    public void complete(long orderId, ProcessResult result) {
        CompletableFuture<ProcessResult> future = futures.remove(orderId);
        if (future != null) {
            future.complete(result);
        }
    }

    public void completeBatch(List<Long> orderIds, ProcessResult result) {
        for (Long orderId : orderIds) {
            complete(orderId, result);
        }
    }
}
```

---

## 4. 线程模型

### 4.1 线程分类

| 线程类型 | 数量 | 职责 | 数据访问 |
|---------|------|------|----------|
| Tomcat HTTP (虚拟线程) | 无限 | 接收HTTP请求，写入CQ，等待唤醒 | 读内存，写CQ |
| WebSocket EventLoop | 8 | WebSocket推送 | 读内存行情 |
| AccountWorker | 1 | 冻结/解冻/结算 | 读写内存（快照时暂停） |
| MatchingEngine | 1 | 订单撮合、行情推送 | 读写内存（快照时暂停） |
| ClearingWorker | 1 | 清算计算、更新订单状态 | 读写内存（快照时暂停） |
| SnapshotWorker | 1 | 定时快照 | 暂停其他Worker后读写 |
| PhaseManager | 1 | 交易阶段切换 | volatile |
| RiskMonitor | 1 | 风险度监控、强平触发 | 读内存 |
| AsyncPersistence | 1 | H2批量写入 | 不访问内存 |

### 4.2 线程数量汇总

| 类别 | 线程数 |
|------|--------|
| Tomcat HTTP (虚拟线程) | 无限 |
| WebSocket EventLoop | 8 |
| AccountWorker | 1 |
| MatchingEngine | 1 |
| ClearingWorker | 1 |
| SnapshotWorker | 1 |
| PhaseManager | 1 |
| RiskMonitor | 1 |
| AsyncPersistence | 1 |
| **总计** | **16+** |

---

## 5. 核心流程

### 5.1 下单流程

```
【阶段1: HTTP请求处理】

1. 幂等键检查（如果提供）
   existing = idempotencyMap.putIfAbsent(idempotencyKey, PROCESSING);
   if (existing != null) {
       if (existing.status == CONFIRMED) return existing.orderId;
       return error(1003, "请求处理中");
   }

2. 交易阶段检查
   if (!phaseManager.allowOrder) return error(4001, "当前阶段不允许下单");

3. 参数校验
   - symbol格式、side枚举、orderType枚举
   - LIMIT单: price > 0
   - quantity > 0 && quantity % lotSize == 0
   - 涨跌停校验

4. 生成订单ID
   orderId = snowflakeIdGenerator.nextId();

5. 创建CompletableFuture
   future = futureManager.createFuture(orderId);

6. 写入account_input CQ（带异常处理）
   try {
       accountInputQueue.write(AccountInputEvent { eventType: NEW_ORDER, ... });
   } catch (Exception e) {
       log.error("CQ write failed: {}", e.getMessage());
       // 回滚：删除内存中的订单
       orderStore.removeOrder(orderId);
       // 释放幂等键
       idempotencyService.fail(idempotencyKey);
       // 返回错误
       return error(9999, "系统繁忙，请稍后重试");
   }

7. 等待账户操作完成
   result = future.get();
   if (!result.isSuccess()) return error(result.code, result.message);

8. 返回orderId
   return { orderId, status: PENDING };


【阶段2: AccountWorker处理】

9. 批量读取account_input CQ
   batch = readBatch(accountInputQueue, BATCH_SIZE);

10. 批量处理
    for (event : batch) {
        switch (event.eventType) {
            case NEW_ORDER:
                // 市价单特殊处理：设置冻结金额和价格限制
                if (event.orderType == MARKET) {
                    Instrument inst = instrumentMap.get(event.symbol);
                    if (event.side == BUY) {
                        long limitUp = inst.prevClose * (1 + inst.limitUpRatio);
                        event.frozenAmount = limitUp * event.quantity + estimateCommission(event);
                        event.maxPrice = limitUp;
                    } else {
                        long limitDown = inst.prevClose * (1 - inst.limitDownRatio);
                        event.minPrice = limitDown;
                    }
                }

                if (event.side == BUY) {
                    result = accountStore.freeze(event.accountId, event.frozenAmount);
                } else {
                    result = positionStore.freezePosition(event.accountId, event.symbol, event.quantity);
                }

                if (result.isSuccess()) {
                    matchingInputEvents.add(createMatchingInputEvent(event));
                    orderIdsToComplete.add(event.orderId);
                    results.add(ProcessResult.success());
                } else {
                    // 冻结失败，更新订单状态为REJECTED
                    orderStore.updateStatus(event.orderId, REJECTED);
                    order.setCancelReason(result.message);
                    orderIdsToComplete.add(event.orderId);
                    results.add(result);
                    // WebSocket推送订单拒绝通知
                    wsPushService.pushOrderRejection(event);
                }
                break;
        }
    }

11. 批量写入matching_input CQ
    batchWrite(matchingInputQueue, matchingInputEvents);

12. 批量写入account_output CQ（用于WebSocket推送）
    batchWrite(accountOutputQueue, accountOutputEvents);

13. 批量唤醒HTTP线程
    for (i = 0; i < orderIdsToComplete.size(); i++) {
        futureManager.complete(orderIdsToComplete.get(i), results.get(i));
    }
```

### 5.2 撮合流程

```
【MatchingEngine批量处理】

1. 批量读取matching_input CQ
   batch = readBatch(matchingInputQueue, BATCH_SIZE);

2. 按symbol分组（提高缓存命中率）
   grouped = batch.groupBy(event -> event.symbol);

3. 批量撮合
   outputEvents = [];
   for (symbol, events : grouped) {
       OrderBook book = orderBooks.get(symbol);
       for (event : events) {
           switch (event.eventType) {
               case NEW_ORDER:
                   // 幂等检查：使用processed_order_ids
                   if (processedOrderIds.contains(event.orderId)) {
                       log.warn("Order already processed, skip: {}", event.orderId);
                       continue;
                   }
                   processedOrderIds.add(event.orderId);

                   // 根据订单类型选择撮合方式
                   // 集合竞价期间：订单累积到CallAuctionProcessor，不立即撮合
                   if (currentPhase == CALL_AUCTION_OPEN || currentPhase == CALL_AUCTION_CLOSE) {
                       callAuctionProcessor.addCallAuctionOrder(event);
                       // 仍写入ORDER_UPDATE事件（订单已接收）
                       outputEvents.add(OrderUpdateEvent {
                           orderId: event.orderId,
                           status: NEW,
                           filledQuantity: 0,
                           remainingQty: event.quantity
                       });
                   } else {
                       // 连续竞价期间：正常撮合
                       book.addOrder(event);
                       outputEvents.addAll(matchOrderByType(book, event));

                       // 写入ORDER_UPDATE事件（订单入OrderBook确认）
                       outputEvents.add(OrderUpdateEvent {
                           orderId: event.orderId,
                           status: NEW,
                           filledQuantity: 0,
                           remainingQty: event.quantity
                       });
                   }
                   break;

               case CANCEL_ORDER:
                   order = book.cancelOrder(event.orderId);
                   if (order != null) {
                       outputEvents.add(CancelSuccessEvent {
                           orderId: event.orderId,
                           unfreezeAmount: order.frozenAmount,
                           unfreezeQty: order.remainingQty,
                           side: order.side
                       });
                   } else {
                       // 撤单失败，返回CANCEL_REJECT
                       outputEvents.add(CancelRejectEvent {
                           orderId: event.orderId,
                           rejectReason: determineCancelRejectReason(event.orderId)
                       });
                   }
                   break;

               case SNAPSHOT:
                   // 处理快照（见17.3节）
                   executeOrderbookSnapshot(event.cqIndex);
                   break;
           }
       }
   }

4. 批量写入matching_output CQ
   batchWrite(matchingOutputQueue, outputEvents);

5. 五档行情推送（每3秒聚合）
   if (timeSinceLastPush > 3000ms) {
       for (symbol : activeSymbols) {
           snapshot = generateMarketSnapshot(orderBooks.get(symbol));
           wsPushService.pushMarketData(snapshot);
       }
   }
```

**按订单类型撮合**：

```java
private List<TradeEvent> matchOrderByType(OrderBook book, Order order) {
    switch (order.orderType) {
        case LIMIT:
            return matchLimitOrder(book, order);
        case MARKET:
            return matchMarketOrder(book, order);
        case FAK:
            return matchFAKOrder(book, order);
        case FOK:
            return matchFOKOrder(book, order);
        default:
            return List.of();
    }
}
```

**市价单处理**：立即成交，未成交部分撤销

**市价单价格约定**：
- 用户端传入 `price=0`
- AccountWorker冻结时使用涨停价（买入）或跌停价（卖出）计算冻结金额
- 订单入OrderBook时设置 `maxPrice=涨停价`（买入）或 `minPrice=跌停价`（卖出）
- 撮合时对手方价格不超过涨跌停价

```java
/**
 * AccountWorker处理市价单冻结逻辑
 */
private void processMarketOrderFreeze(Order order, Instrument inst) {
    long limitUp = inst.prevClose * (1 + inst.limitUpRatio);
    long limitDown = inst.prevClose * (1 - inst.limitDownRatio);
    
    if (order.side == BUY) {
        // 买入：按涨停价冻结资金
        order.frozenAmount = limitUp * order.quantity + estimateCommission;
        order.maxPrice = limitUp;  // 撮合时不超过此价格
    } else {
        // 卖出：按跌停价冻结持仓（跌停价用于计算预估佣金）
        order.minPrice = limitDown;  // 撮合时不低于此价格
    }
}

private List<TradeEvent> matchMarketOrder(OrderBook book, Order order) {
    List<TradeEvent> events = new ArrayList<>();
    long remainingQty = order.quantity;

    while (remainingQty > 0) {
        Order counterOrder = book.getBestCounterOrder(order.side);
        if (counterOrder == null) break;

        // 价格限制检查（不超过涨跌停价，通过maxPrice/minPrice控制）
        if (order.side == BUY && counterOrder.price > order.maxPrice) break;
        if (order.side == SELL && counterOrder.price < order.minPrice) break;

        long matchQty = Math.min(remainingQty, counterOrder.remainingQty);
        events.add(createTradeEvent(order, counterOrder, matchQty, counterOrder.price));
        remainingQty -= matchQty;
    }

    // 生成ORDER_UPDATE事件
    long filledQty = order.quantity - remainingQty;
    events.add(ORDER_UPDATE {
        orderId: order.orderId,
        status: remainingQty > 0 ? PARTIAL_FILLED : FILLED,
        filledQuantity: filledQty,
        remainingQty: remainingQty
    });

    // 未成交部分撤销
    if (remainingQty > 0) {
        // 使用订单剩余冻结金额（已在部分成交时更新）
        CancelEvent cancel = new CancelEvent(order.orderId, "市价单部分成交后撤销", remainingQty);
        cancel.unfreezeAmount = order.frozenAmount;  // 使用订单剩余冻结金额
        events.add(cancel);
    }

    return events;
}
```

**市价单部分成交解冻流程说明**：

市价单部分成交后，未成交部分撤销，触发以下解冻流程：
1. MatchingEngine生成`CancelEvent`，包含`unfreezeAmount`（按maxPrice计算剩余冻结金额）
2. ClearingWorker处理`CancelEvent`，生成`CANCEL_SUCCESS`事件写入trade CQ
3. ClearingWorker继续处理`CANCEL_SUCCESS`，生成`UNFREEZE`事件写入incoming_event CQ
4. AccountWorker处理`UNFREEZE`事件，解冻资金/持仓

**FAK (Fill-And-Kill) 处理**：立即成交能成交的部分，剩余撤销（有价格限制）

```java
private List<TradeEvent> matchFAKOrder(OrderBook book, Order order) {
    List<TradeEvent> events = new ArrayList<>();
    long remainingQty = order.quantity;

    while (remainingQty > 0) {
        Order counterOrder = book.getBestCounterOrder(order.side);
        if (counterOrder == null) break;
        
        // 价格匹配检查
        if (order.side == BUY && counterOrder.price > order.price) break;
        if (order.side == SELL && counterOrder.price < order.price) break;

        long matchQty = Math.min(remainingQty, counterOrder.remainingQty);
        events.add(createTradeEvent(order, counterOrder, matchQty, counterOrder.price));
        remainingQty -= matchQty;
    }

    // 剩余部分撤销
    if (remainingQty > 0) {
        events.add(new CancelEvent(order.orderId, "FAK订单剩余撤销", remainingQty));
    }

    return events;
}
```

**FOK (Fill-Or-Kill) 处理**：要么全部成交，要么全部撤销

```java
private List<TradeEvent> matchFOKOrder(OrderBook book, Order order) {
    // 先检查是否可全部成交（单线程撮合，检查+执行是原子的）
    long availableQty = book.getAvailableQuantityAtPrice(order.side, order.price);

    if (availableQty < order.quantity) {
        return List.of(new CancelEvent(order.orderId, "FOK订单无法全部成交", order.quantity));
    }

    // 执行撮合
    List<TradeEvent> events = new ArrayList<>();
    long remainingQty = order.quantity;

    while (remainingQty > 0) {
        Order counterOrder = book.getBestCounterOrder(order.side);
        if (counterOrder == null) break;
        
        // 价格匹配检查
        if (order.side == BUY && counterOrder.price > order.price) break;
        if (order.side == SELL && counterOrder.price < order.price) break;

        long matchQty = Math.min(remainingQty, counterOrder.remainingQty);
        events.add(createTradeEvent(order, counterOrder, matchQty, counterOrder.price));
        remainingQty -= matchQty;
    }

    return events;
}
```

**幂等保证**：

MatchingEngine 使用内存 `processedOrdersSet` 记录已处理的订单ID，防止重复处理。
**注意**：`processed_orders` 不单独保存快照，恢复时从 `order_snapshot` 提取订单ID用于幂等检查。

```java
// 恢复时从订单快照加载已处理订单ID
public void loadProcessedOrdersFromOrderSnapshot(long snapshotIndex) {
    Path file = Paths.get("data/snapshots/order_snapshot_" + snapshotIndex + ".dat");
    if (Files.exists(file)) {
        ChronicleMap<Long, Order> orderCM = ChronicleMap.createFromFile(file.toString());
        for (Map.Entry<Long, Order> e : orderCM.entrySet()) {
            processedOrdersSet.add(e.getKey());
        }
        orderCM.close();
        log.info("Loaded {} processed order IDs from order snapshot", processedOrdersSet.size());
    }
}
```

### 5.3 清算流程

```
【ClearingWorker批量处理】

1. 批量读取matching_output CQ
   batch = readBatch(matchingOutputQueue, BATCH_SIZE);

2. 批量处理
   settlementEvents = [];
   for (event : batch) {
       switch (event.eventType) {
           case ORDER_UPDATE:
               // 更新订单状态：PENDING → NEW 或 PARTIAL_FILLED → FILLED
               order = orderStore.getOrder(event.orderId);
               if (order.status == PENDING) {
                   order.status = NEW;  // 订单已入OrderBook
               } else if (order.status == PARTIAL_FILLED && event.remainingQty == 0) {
                   order.status = FILLED;
               }
               order.filledQuantity = event.filledQuantity;
               order.remainingQty = event.remainingQty;

               // WebSocket推送订单状态更新
               wsPushService.pushOrderUpdate(event);
               break;

           case TRADE:
               // 幂等检查：使用processed_trade_ids（内存中维护最新10,000个）
               if (processedTradeIds.contains(event.tradeId)) {
                   log.warn("Trade already processed, skip: {}", event.tradeId);
                   continue;
               }
               processedTradeIds.add(event.tradeId);

               // 更新订单状态
               orderStore.updateFilledQuantity(event.buyOrderId, event.quantity);
               orderStore.updateFilledQuantity(event.sellOrderId, event.quantity);

               // 计算结算金额
               amount = event.price * event.quantity;
               buyComm = calculateCommission(event, BUY);
               sellComm = calculateCommission(event, SELL);

               // 计算frozenAmountUsed（买方）- 使用订单剩余冻结金额避免舍入误差
               Order buyOrder = orderStore.getOrder(event.buyOrderId);
               long frozenAmountUsedBuyer;

               if (event.quantity == buyOrder.remainingQty) {
                   // 最后一次成交，使用全部剩余冻结金额
                   frozenAmountUsedBuyer = buyOrder.frozenAmount;
               } else {
                   // 部分成交，按比例计算（向上取整保护买方）
                   frozenAmountUsedBuyer = (buyOrder.frozenAmount * event.quantity
                       + buyOrder.remainingQty - 1) / buyOrder.remainingQty;
               }

               // 更新订单剩余冻结金额
               buyOrder.frozenAmount -= frozenAmountUsedBuyer;

               // 生成结算事件写入account_settlement CQ
               settlementEvents.add(SETTLEMENT {
                   orderId: event.buyOrderId,
                   accountId: event.buyAccountId,
                   settlementType: BUYER,
                   symbol: event.symbol,
                   price: event.price,
                   quantity: event.quantity,
                   frozenAmountUsed: frozenAmountUsedBuyer,
                   commission: buyComm,
                   tradeId: event.tradeId
               });
               settlementEvents.add(SETTLEMENT {
                   orderId: event.sellOrderId,
                   accountId: event.sellAccountId,
                   settlementType: SELLER,
                   symbol: event.symbol,
                   price: event.price,
                   quantity: event.quantity,
                   frozenAmountUsed: event.quantity,  // 卖方冻结的是持仓数量
                   commission: sellComm,
                   tradeId: event.tradeId
               });
               break;

           case CANCEL_SUCCESS:
               // 生成解冻事件写入account_settlement CQ
               settlementEvents.add(UNFREEZE {
                   orderId: event.orderId,
                   accountId: event.accountId,
                   frozenAmount: event.unfreezeAmount,
                   quantity: event.unfreezeQty,
                   side: event.side,
                   symbol: event.symbol
               });
               break;

           case CANCEL_REJECT:
               // WebSocket推送撤单失败通知
               wsPushService.pushCancelReject(event);
               break;
       }
   }

3. 批量写入account_settlement CQ
   batchWrite(accountSettlementQueue, settlementEvents);
```

**幂等保证**：

ClearingWorker 使用 `processed_trades.cm` 记录已处理的成交ID：

```java
// 恢复时加载已处理成交ID
public void loadProcessedTrades(long snapshotIndex) {
    ChronicleMap<String, Boolean> cm = ChronicleMap.createFromFile(
        "data/snapshots/processed_trades_" + snapshotIndex + ".dat");
    for (Map.Entry<String, Boolean> e : cm.entrySet()) {
        processedTradesCM.put(e.getKey(), e.getValue());
    }
    cm.close();
}

// 快照时写入已处理成交ID
public void saveProcessedTrades(long cqIndex) {
    ChronicleMap<String, Boolean> cm = ChronicleMap.of(String.class, Boolean.class)
        .name("processed_trades")
        .entries(500000)
        .averageKeySize(30)  // "1234567890123456789_9876543210987654321"
        .createPersistedTo("data/snapshots/processed_trades_" + cqIndex + ".dat");
    for (Map.Entry<String, Boolean> e : processedTradesCM.entrySet()) {
        cm.put(e.getKey(), e.getValue());
    }
    cm.close();
}
```

### 5.4 结算/解冻流程

```
【AccountWorker处理SETTLEMENT/UNFREEZE】

1. 批量读取account_settlement CQ
   batch = readBatch(accountSettlementQueue, BATCH_SIZE);

2. 批量处理
   accountOutputEvents = [];
   for (event : batch) {
       switch (event.eventType) {
           case SETTLEMENT:
               // 幂等检查：使用settlementId = tradeId + "_" + orderId
               String settlementId = event.tradeId + "_" + event.orderId;
               if (processedSettlementIds.contains(settlementId)) {
                   log.warn("Settlement already processed, skip: {}", settlementId);
                   continue;
               }
               processedSettlementIds.add(settlementId);

               if (event.settlementType == BUYER) {
                   // 买方结算（使用event.frozenAmountUsed字段）
                   order = orderStore.getOrder(event.orderId);
                   acc = accountStore.getAccount(event.accountId);

                   // frozenAmountUsed由ClearingWorker计算并传递，代表本次结算使用的冻结金额
                   long frozenUsed = event.frozenAmountUsed;

                   // 1. 解冻本次使用的冻结金额
                   acc.frozen -= frozenUsed;
                   acc.orderFrozen -= frozenUsed;

                   // 2. 计算成交金额和总扣款
                   long tradeAmount = event.price * event.quantity;
                   long totalDeduction = tradeAmount + event.commission;

                   // 3. 从balance扣减成交金额和佣金
                   acc.balance -= totalDeduction;

                   // 4. 退还差额到available（冻结金额 > 实际扣款）
                   long refund = frozenUsed - totalDeduction;
                   if (refund > 0) {
                       acc.available += refund;
                   }

                   // 5. 更新订单剩余冻结金额（用于后续部分成交）
                   order.frozenAmount -= frozenUsed;

                   // 6. 增加持仓
                   pos = positionStore.getPosition(event.accountId, event.symbol);
                   if (pos == null) {
                       pos = new Position(event.accountId, event.symbol);
                       positionStore.addPosition(pos);
                   }
                   pos.quantity += event.quantity;
                   pos.t1Locked += event.quantity; // A股T+1

                   // 7. 验证不变式
                   assert acc.balance == acc.available + acc.frozen : "账户不变式校验失败";

               } else { // SELLER
                   // 卖方结算
                   Account acc = accountStore.getAccount(event.accountId);
                   Position pos = positionStore.getPosition(event.accountId, event.symbol);
                   Order order = orderStore.getOrder(event.orderId);

                   // 1. 计算成交金额和净收入
                   long tradeAmount = event.price * event.quantity;
                   long netAmount = tradeAmount - event.commission;

                   // 2. 解冻持仓
                   pos.frozen -= event.quantity;

                   // 3. 减少持仓数量（注意多头/空头方向）
                   if (pos.quantity > 0) {
                       pos.quantity -= event.quantity;
                   } else {
                       pos.quantity += event.quantity;  // 空头回补
                   }

                   // 4. 解冻账户资金冻结（如果有的话，用于期货/期权保证金）
                   //    股票卖出：原冻结资金为0
                   //    期货卖出：解冻保证金
                   long originalFrozenAmount = order.frozenAmount;
                   if (originalFrozenAmount > 0) {
                       acc.frozen -= originalFrozenAmount;
                       acc.marginFrozen -= originalFrozenAmount;
                       acc.available += originalFrozenAmount;
                   }

                   // 5. 入账净金额
                   //    balance增加净收入
                   //    available增加净收入（资金来自持仓变现）
                   acc.balance += netAmount;
                   acc.available += netAmount;

                   // 6. 更新订单已使用冻结金额
                   order.frozenAmount -= originalFrozenAmount;

                   // 7. 验证不变式
                   assert acc.balance == acc.available + acc.frozen : "账户不变式校验失败";
                   assert Math.abs(pos.quantity) == pos.available + pos.frozen + pos.t1Locked : "持仓不变式校验失败";
               }

               // 写入account_output CQ用于WebSocket推送
               accountOutputEvents.add(createAccountOutputEvent(event));

               // WebSocket推送
               wsPushService.pushTradeReport(event);
               break;

           case UNFREEZE:
               if (event.side == BUY) {
                   accountStore.unfreeze(event.accountId, event.frozenAmount);
               } else {
                   positionStore.unfreezePosition(event.accountId, event.symbol, event.quantity);
               }
               // 验证不变式
               Account acc = accountStore.getAccount(event.accountId);
               assert acc.balance == acc.available + acc.frozen : "账户不变式校验失败";

               // 写入account_output CQ用于WebSocket推送
               accountOutputEvents.add(createUnfreezeOutputEvent(event));

               wsPushService.pushCancelResult(event);
               break;
       }
   }

3. 批量写入account_output CQ
   batchWrite(accountOutputQueue, accountOutputEvents);
```

**关键设计说明**：

1. **幂等保证**：AccountWorker使用`processed_settlement_ids`记录已处理的结算ID（内存中维护最新10,000个），防止重复结算
2. **不变式维护**：每次结算后验证`balance = available + frozen`
3. **部分成交支持**：订单的`frozenAmount`随着成交递减，支持多次部分成交

---

## 6. 并发控制

### 6.1 核心设计

**单线程串行处理 + 内存数据存储**

- AccountWorker：单线程处理所有账户/持仓状态变更
- MatchingEngine：单线程处理所有OrderBook操作
- ClearingWorker：单线程处理所有清算操作

**无锁设计**：各Worker单线程访问各自数据，无需加锁。

### 6.2 幂等性保证

```java
public class IdempotencyService {
    private final ChronicleMap<String, IdempotencyValue> idempotencyMap;

    public boolean tryAcquire(String key) {
        IdempotencyValue newValue = new IdempotencyValue();
        newValue.status = PROCESSING;
        newValue.createTime = System.currentTimeMillis();
        
        IdempotencyValue existing = idempotencyMap.putIfAbsent(key, newValue);
        return existing == null;
    }

    public void confirm(String key, long orderId) {
        idempotencyMap.computeIfPresent(key, (k, v) -> {
            v.orderId = orderId;
            v.status = CONFIRMED;
            return v;
        });
    }

    public void fail(String key) {
        idempotencyMap.remove(key);
    }
}
```

### 6.3 volatile变量

| 变量 | 类型 | 用途 | 更新者 |
|------|------|------|--------|
| currentPhase | TradingPhase | 当前交易阶段 | PhaseManager |
| throttled | boolean | 背压标志 | MatchingEngine |
| marketOpen | boolean | 市场开关 | PhaseManager |

---

## 7. 持久化与恢复

### 7.1 数据持久化策略

| 数据类型 | 存储位置 | 持久化方式 | 恢复方式 |
|---------|---------|-----------|---------|
| 账户状态 | 内存 | CM快照（每分钟） | 快照+CQ增量 |
| 持仓状态 | 内存 | CM快照（每分钟） | 快照+CQ增量 |
| 订单状态 | 内存 | CM快照（每分钟） | 快照+CQ增量 |
| 订单簿 | 内存 | CM快照（每分钟） | 快照+CQ增量 |
| 事件日志 | CQ文件 | 磁盘顺序写 | 直接读取 |

### 7.2 恢复流程

```
1. 读取最新快照索引文件
   index = readIndexFile("data/chronicle/snapshots/index.dat");
   if (index == null) {
       initializeEmptyStores();
       startWorkers();
       return;
   }

2. 从CM快照加载到内存
   // 加载账户
   accountCM = ChronicleMap.createFromFile(index.accountsFile);
   accountStore.loadFromChronicleMap(accountCM);
   accountCM.close();
   
   // 加载持仓
   positionCM = ChronicleMap.createFromFile(index.positionsFile);
   positionStore.loadFromChronicleMap(positionCM);
   positionCM.close();
   
   // 加载订单
   orderCM = ChronicleMap.createFromFile(index.ordersFile);
   orderStore.loadFromChronicleMap(orderCM);
   orderCM.close();
   
   // 加载订单簿
   orderbookData = Files.readAllBytes(index.orderbookFile);
   orderBooks = deserializeOrderBooks(orderbookData);

3. 重放CQ增量事件
   // 重放incoming_event CQ（SETTLEMENT/UNFREEZE）
   tailer = incomingEventQueue.createTailer();
   tailer.moveToIndex(index.incomingEventIndex);
   while (tailer.readDocument(event)) {
       if (event.eventType == SETTLEMENT || event.eventType == UNFREEZE) {
           accountWorker.processEvent(event);
       }
   }
   
   // 重放match_orders CQ（重建OrderBook增量）
   tailer = matchOrdersQueue.createTailer();
   tailer.moveToIndex(index.matchOrdersIndex);
   while (tailer.readDocument(event)) {
       matchingEngine.rebuildOrderBook(event);
   }
   
   // 重放trade CQ（更新订单状态）
   tailer = tradeQueue.createTailer();
   tailer.moveToIndex(index.tradeIndex);
   while (tailer.readDocument(event)) {
       clearingWorker.processEvent(event);
   }

4. 启动工作线程
   accountWorker.start();
   matchingEngine.start();
   clearingWorker.start();
```

---

## 8. 行情服务

### 8.1 五档行情生成

```java
public MarketDataSnapshot generateSnapshot(String symbol, OrderBook book) {
    MarketDataSnapshot snapshot = new MarketDataSnapshot();
    snapshot.setSymbol(symbol);
    snapshot.setUpdateTime(System.currentTimeMillis());

    // 买盘：取价格最高的5档
    List<OrderBookLevel> bidLevels = book.getTopBidLevels(5);
    for (int i = 0; i < 5; i++) {
        if (i < bidLevels.size()) {
            snapshot.setBidPrice(i, bidLevels.get(i).getPrice());
            snapshot.setBidVolume(i, bidLevels.get(i).getVolume());
        } else {
            snapshot.setBidPrice(i, 0);
            snapshot.setBidVolume(i, 0);
        }
    }

    // 卖盘：取价格最低的5档
    List<OrderBookLevel> askLevels = book.getTopAskLevels(5);
    for (int i = 0; i < 5; i++) {
        if (i < askLevels.size()) {
            snapshot.setAskPrice(i, askLevels.get(i).getPrice());
            snapshot.setAskVolume(i, askLevels.get(i).getVolume());
        } else {
            snapshot.setAskPrice(i, 0);
            snapshot.setAskVolume(i, 0);
        }
    }

    return snapshot;
}
```

### 8.2 行情推送策略

| 行情类型 | 推送频率 | 说明 |
|---------|---------|------|
| 五档行情 | 每3秒 | 聚合推送，减少网络开销 |
| K线数据 | 实时 | 成交时推送 |
| 订单簿Level2 | 可选 | 仅订阅用户 |

---

## 9. API设计

**重要约定**：所有API请求和响应的JSON字段值均为STRING类型，包括数字字段也用STRING表示。

### 9.1 REST API

| 模块 | 接口 | 方法 | 说明 |
|------|------|------|------|
| 认证 | /api/auth/login | POST | 用户登录 |
| 认证 | /api/auth/logout | POST | 用户登出 |
| 认证 | /api/auth/refresh | POST | 刷新令牌 |
| 账户 | /api/account/info | GET | 账户信息 |
| 账户 | /api/account/positions | GET | 持仓列表 |
| 订单 | /api/orders | POST | 下单 |
| 订单 | /api/orders/{orderId} | DELETE | 撤单 |
| 订单 | /api/orders/active | GET | 当前委托 |
| 历史 | /api/trades | GET | 历史成交 |
| 行情 | /api/market/symbols | GET | 股票列表 |
| 行情 | /api/market/watchlist | GET | 获取自选股列表 |
| 行情 | /api/market/watchlist | POST | 添加自选股 |
| 行情 | /api/market/watchlist/{symbol} | DELETE | 删除自选股 |

### 9.2 HTTP API 详细定义

**POST /api/orders 下单**

请求：
```json
{
  "symbol": "600000",
  "side": "BUY",
  "orderType": "LIMIT",
  "price": "1050",
  "quantity": "100",
  "idempotencyKey": "uuid-v4-string"
}
```

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": {
    "orderId": "1234567890123456789",
    "status": "PENDING"
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**DELETE /api/orders/{orderId} 撤单**

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": {
    "orderId": "1234567890123456789",
    "status": "CANCELLED",
    "unfreezeAmount": "105000",
    "unfreezeQty": "100"
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**GET /api/account/info 账户信息**

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": {
    "accountId": "456",
    "userId": "123",
    "balance": "10000000",
    "available": "8500000",
    "frozen": "1500000",
    "marginFrozen": "0",
    "orderFrozen": "1500000",
    "unrealizedPnl": "50000",
    "status": "ACTIVE"
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**GET /api/account/positions 持仓列表**

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": {
    "positions": [
      {
        "symbol": "600000",
        "symbolName": "浦发银行",
        "quantity": "1000",
        "available": "500",
        "frozen": "200",
        "t1Locked": "300",
        "avgPrice": "1050",
        "currentPrice": "1080",
        "unrealizedPnl": "30000"
      }
    ]
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**GET /api/orders/active 当前委托**

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": {
    "orders": [
      {
        "orderId": "1234567890123456789",
        "symbol": "600000",
        "side": "BUY",
        "orderType": "LIMIT",
        "price": "1050",
        "quantity": "100",
        "filledQuantity": "50",
        "remainingQty": "50",
        "status": "PARTIAL_FILLED",
        "createTime": "1716460800000"
      }
    ]
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**GET /api/trades 历史成交**

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": {
    "trades": [
      {
        "tradeId": "12345_67890",
        "orderId": "1234567890123456789",
        "symbol": "600000",
        "side": "BUY",
        "price": "1050",
        "quantity": "50",
        "commission": "158",
        "tradeTime": "1716460800000"
      }
    ],
    "total": "100",
    "page": "1",
    "pageSize": "20"
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**POST /api/auth/login 登录**

请求：
```json
{
  "username": "user@example.com",
  "password": "hashed_password"
}
```

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": {
    "accessToken": "jwt-access-token",
    "refreshToken": "jwt-refresh-token",
    "expiresIn": "7200"
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**POST /api/auth/logout 登出**

请求：
```json
{
  "refreshToken": "jwt-refresh-token"
}
```

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": null,
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**POST /api/auth/refresh 刷新令牌**

请求：
```json
{
  "refreshToken": "jwt-refresh-token"
}
```

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": {
    "accessToken": "new-jwt-access-token",
    "refreshToken": "new-jwt-refresh-token",
    "expiresIn": "7200"
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**GET /api/market/symbols 股票列表**

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": {
    "symbols": [
      {
        "symbol": "600000",
        "symbolName": "浦发银行",
        "type": "STOCK",
        "exchange": "SH",
        "status": "TRADING"
      }
    ]
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**GET /api/market/watchlist 自选股列表**

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": {
    "symbols": ["600000", "600001"]
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**POST /api/market/watchlist 添加自选股**

请求：
```json
{
  "symbol": "600000"
}
```

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": null,
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**DELETE /api/market/watchlist/{symbol} 删除自选股**

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": null,
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

### 9.3 WebSocket API

**连接端点**: `ws://host:port/ws?token=<jwt_token>`

**认证失败处理**:
- Invalid/expired token: 连接拒绝，WebSocket close code 1008
- Token refresh: 客户端需重新连接

**Channel枚举**:

| Channel | 说明 | 订阅格式 |
|---------|------|---------|
| market_snapshot | 五档行情快照 | symbols array |
| orderbook | 订单簿Level2 | symbols array |
| kline | K线数据 | symbols array |
| order_update | 订单状态更新 | 自动订阅（按用户） |
| trade_update | 成交更新 | 自动订阅（按用户） |
| account_update | 账户资金变动 | 自动订阅（按用户） |
| position_update | 持仓变动 | 自动订阅（按用户） |

**订阅消息**:
```json
{
  "type": "subscribe",
  "channel": "market_snapshot",
  "symbols": ["600000"]
}
```

**取消订阅消息**:
```json
{
  "type": "unsubscribe",
  "channel": "market_snapshot",
  "symbols": ["600000"]
}
```

**心跳消息**:
```json
// 客户端发送
{
  "type": "ping",
  "timestamp": "1716460800000"
}

// 服务端响应
{
  "type": "pong",
  "timestamp": "1716460800000"
}
```

**错误消息**:
```json
{
  "type": "error",
  "code": "1001",
  "message": "Invalid symbol format",
  "channel": "market_snapshot",
  "timestamp": "1716460800000"
}
```

**推送消息定义**:

**trade_update 成交推送**:
```json
{
  "type": "trade_update",
  "data": {
    "tradeId": "12345_67890",
    "orderId": "1234567890123456789",
    "symbol": "600000",
    "side": "BUY",
    "price": "1050",
    "quantity": "50",
    "amount": "52500",
    "commission": "158",
    "timestamp": "1716460800000"
  }
}
```

**注意**：本系统仅计算手续费，不涉及印花税逻辑。

**order_update 订单状态推送**:
```json
{
  "type": "order_update",
  "data": {
    "orderId": "1234567890123456789",
    "symbol": "600000",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": "1050",
    "quantity": "100",
    "filledQuantity": "50",
    "remainingQty": "50",
    "status": "PARTIAL_FILLED",
    "timestamp": "1716460800000"
  }
}
```

**account_update 账户变动推送**:
```json
{
  "type": "account_update",
  "data": {
    "accountId": "456",
    "balance": "10000000",
    "available": "8500000",
    "frozen": "1500000",
    "marginFrozen": "0",
    "orderFrozen": "1500000",
    "delta": "-105000",
    "reason": "ORDER_FREEZE",
    "timestamp": "1716460800000"
  }
}
```

**position_update 持仓变动推送**:
```json
{
  "type": "position_update",
  "data": {
    "accountId": "456",
    "symbol": "600000",
    "quantity": "1000",
    "available": "500",
    "frozen": "200",
    "t1Locked": "300",
    "delta": "100",
    "reason": "TRADE_BUY",
    "timestamp": "1716460800000"
  }
}
```

**market_snapshot 行情快照推送**:
```json
{
  "type": "market_snapshot",
  "data": {
    "symbol": "600000",
    "timestamp": "1716460800000",
    "bidPrices": ["1050", "1049", "1048", "1047", "1046"],
    "bidVolumes": ["1000", "2000", "1500", "3000", "2500"],
    "askPrices": ["1051", "1052", "1053", "1054", "1055"],
    "askVolumes": ["2000", "1500", "3000", "2500", "1000"]
  }
}
```

**说明**：bidPrices/bidVolumes/askPrices/askVolumes 固定5个元素，空档位用 `"0"` 填充。

**subscribe_confirm 订阅确认**:
```json
{
  "type": "subscribe_confirm",
  "channel": "market_snapshot",
  "symbols": ["600000"],
  "timestamp": "1716460800000"
}
```

**unsubscribe_confirm 取消订阅确认**:
```json
{
  "type": "unsubscribe_confirm",
  "channel": "market_snapshot",
  "symbols": ["600000"],
  "timestamp": "1716460800000"
}
```

**kline K线数据推送**:
```json
{
  "type": "kline",
  "data": {
    "symbol": "600000",
    "interval": "1m",
    "open": "1050",
    "high": "1055",
    "low": "1048",
    "close": "1053",
    "volume": "10000",
    "timestamp": "1716460800000"
  }
}
```

**orderbook 订单簿Level2推送**:
```json
{
  "type": "orderbook",
  "data": {
    "symbol": "600000",
    "bids": [
      {"price": "1050", "volume": "1000", "orderCount": "5"},
      {"price": "1049", "volume": "2000", "orderCount": "8"}
    ],
    "asks": [
      {"price": "1051", "volume": "1500", "orderCount": "3"},
      {"price": "1052", "volume": "2500", "orderCount": "6"}
    ],
    "timestamp": "1716460800000"
  }
}
```

**reason枚举值**:

| reason | 说明 | 适用消息 |
|--------|------|---------|
| ORDER_FREEZE | 下单冻结 | account_update |
| ORDER_UNFREEZE | 撤单解冻 | account_update |
| TRADE_BUY | 买入成交 | account_update, position_update |
| TRADE_SELL | 卖出成交 | account_update, position_update |
| DEPOSIT | 入金 | account_update |
| COMMISSION | 手续费扣除 | account_update |
| LIQUIDATION | 强平 | account_update, position_update |
| T1_UNLOCK | T+1解锁 | position_update |

## 10. 前端设计

### 10.1 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | React 18 + TypeScript |
| 构建 | Vite |
| UI | Ant Design 5.x |
| 状态 | Zustand |
| 图表 | lightweight-charts（TradingView开源） |

### 10.2 页面布局

```
┌─────────────────────────────────────────────────────────────────┐
│  Header: Logo | 用户信息 | 系统状态                              │
├─────────────────────────────────────────────────────────────────┤
│  Sidebar  │  Main Content                                       │
│  ├行情    │  ┌─────────────────────────────────────────────┐   │
│  ├交易    │  │  K线图 (lightweight-charts)                  │   │
│  ├订单    │  ├─────────────────────────────────────────────┤   │
│  ├持仓    │  │  五档行情 | 下单面板                          │   │
│  └历史    │  ├─────────────────────────────────────────────┤   │
│           │  │  订单簿 (可选)                                │   │
│           │  └─────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 11. 性能分析

### 11.1 延迟分解

**HTTP返回延迟**（冻结完成后即返回orderId）：

| 阶段 | 操作 | 延迟 |
|------|------|------|
| HTTP接收 | Tomcat处理 | 0.1-0.3ms |
| 参数校验 | 校验+幂等检查 | 0.2-0.5ms |
| CQ写入 | incoming_event | 0.05-0.1ms |
| 队列等待 | CompletableFuture | 取决于队列深度 |
| AccountWorker冻结 | 内存更新+CQ写入 | 0.1-0.2ms |
| 唤醒HTTP | complete | 0.05ms |

**AccountWorker单次操作耗时**：

| 操作 | 耗时 |
|------|------|
| CQ读取 | ~5μs |
| 内存更新账户 | ~0.5μs |
| 内存更新持仓 | ~0.5μs |
| CQ写入match_orders | ~5μs |
| 唤醒HTTP | ~5μs |
| **总计** | ~15-20μs |

### 11.2 端到端延迟分解

**下单 → 成交回报 完整链路**：

```
阶段                          延迟(P99)      累计
───────────────────────────────────────────────────
HTTP接收+校验                  0.5ms         0.5ms
写入incoming_event CQ          0.1ms         0.6ms
队列等待+AccountWorker处理     1.0ms         1.6ms
写入match_orders CQ            0.1ms         1.7ms
队列等待+MatchingEngine撮合    2.0ms         3.7ms
写入trade CQ                   0.1ms         3.8ms
队列等待+ClearingWorker清算    1.5ms         5.3ms
写入incoming_event CQ(SETTLEMENT) 0.1ms      5.4ms
队列等待+AccountWorker结算      1.0ms         6.4ms
WebSocket推送成交回报          0.5ms         6.9ms
───────────────────────────────────────────────────
端到端P99延迟                  ~7ms          < 15ms ✓
```

### 11.3 吞吐量分析

**逐个处理吞吐**：40-50K TPS（受限于ClearingWorker）

**批量处理吞吐**：

| 组件 | 逐个处理 | 批量处理(每批50) | 瓶颈分析 |
|------|---------|-----------------|---------|
| AccountWorker | 65K TPS | 100K+ TPS | 内存操作为主 |
| MatchingEngine | 65K TPS | 100K+ TPS | OrderBook操作 + 撮合 |
| ClearingWorker | 50K TPS | 80K+ TPS | 写入SETTLEMENT事件×2 |
| **系统整体** | **40-50K TPS** | **100K TPS** | ClearingWorker是瓶颈 |

**ClearingWorker瓶颈分析**：

每笔成交需：
1. 读取trade事件
2. 更新买卖双方订单状态
3. 计算双方佣金
4. 写入两条SETTLEMENT事件到incoming_event CQ

优化方向：成交事件批量处理、异步写入H2历史。

### 11.4 批量大小选择

| 批量大小 | 批处理耗时 | HTTP平均等待 | 吞吐 | 推荐场景 |
|---------|-----------|-------------|------|---------|
| 20 | ~0.3ms | ~0.3ms | 80K TPS | 低延迟优先 |
| **50** | ~0.75ms | ~0.75ms | **100K TPS** | **平衡（推荐）** |
| 100 | ~1.5ms | ~1.5ms | 120K TPS | 吞吐优先 |

### 11.5 延迟预估

| 负载 | HTTP返回P99 | 端到端P99 | 说明 |
|------|-------------|-----------|------|
| 30K TPS | ~2ms | ~6ms | 低负载，队列几乎无等待 |
| 60K TPS | ~3ms | ~8ms | 中等负载，队列深度适中 |
| **100K TPS** | **~5ms** | **~12ms** | **高负载，目标满足** |

**P99延迟满足目标**：HTTP返回 ≤5ms ✓，端到端 ≤15ms ✓

### 11.6 幂等CM对性能的影响

| 组件 | 幂等存储 | 查询延迟 | 内存占用 |
|------|---------|---------|---------|
| MatchingEngine | processed_orders CM | ~0.5μs (putIfAbsent) | ~100MB (100万订单) |
| ClearingWorker | processed_trades CM | ~0.5μs (putIfAbsent) | ~200MB (50万成交×30字节key) |

**影响评估**：幂等检查为内存操作，延迟可忽略，对吞吐影响 <5%。

### 11.7 内存使用

| 组件 | 预估内存 |
|------|---------|
| Chronicle Map快照 | 2-4GB |
| 内存OrderBook | 500MB-1GB |
| JVM堆 | 4-6GB |
| CQ缓冲 | 1-2GB |
| processed_orders CM | ~100MB |
| processed_trades CM | ~200MB |
| **总计** | **8-12GB** |

### 11.8 GC影响

**ZGC (Generational) 特性**：

| 指标 | 目标值 | 说明 |
|------|--------|------|
| GC暂停 | < 1ms | ZGC并发回收 |
| 堆内存占用 | 45%触发GC | InitiatingHeapOccupancyPercent |
| 内存碎片 | 低 | ZGC自动整理 |

**对延迟影响**：ZGC暂停 <1ms，对P99延迟影响可忽略。

---

## 12. 部署配置

### 12.1 JVM参数

```bash
java -Xms12g -Xmx12g \
     -XX:+UseZGC \
     -XX:MaxGCPauseMillis=1 \
     -XX:+UseStringDeduplication \
     -XX:InitiatingHeapOccupancyPercent=45 \
     -Djdk.default.URL.connectTimeout=5000 \
     -jar hft-trading.jar
```

### 12.2 Spring Boot配置

```yaml
server:
  port: 8080
  shutdown: graceful

spring:
  threads:
    virtual:
      enabled: true
  lifecycle:
    timeout-per-shutdown-phase: 30s

# 应用配置
trading:
  chronicle:
    base-path: data/chronicle/
  h2:
    database-path: data/h2/trading
  snapshot:
    interval-ms: 60000
  batch:
    size: 50
  future:
    timeout-ms: 5000
  jwt:
    access-token-expiration: 7200000
    refresh-token-expiration: 604800000
  rate-limit:
    order-tps: 100
    query-qps: 1000
```

### 12.3 Chronicle Queue配置

```
Chronicle Queue 工厂配置:

// account_input CQ - AccountWorker输入队列
accountInputQueue:
    path: "data/chronicle/account_input"
    rollCycle: HOURLY
    blockSize: 128MB
    bufferSize: 256KB
    wireType: BINARY

// account_output CQ - AccountWorker输出队列（WebSocket推送）
accountOutputQueue:
    path: "data/chronicle/account_output"
    rollCycle: HOURLY
    blockSize: 64MB
    bufferSize: 128KB
    wireType: BINARY

// matching_input CQ - MatchingEngine输入队列
matchingInputQueue:
    path: "data/chronicle/matching_input"
    rollCycle: HOURLY
    blockSize: 64MB
    bufferSize: 128KB
    wireType: BINARY

// matching_output CQ - MatchingEngine输出队列
matchingOutputQueue:
    path: "data/chronicle/matching_output"
    rollCycle: HOURLY
    blockSize: 64MB
    bufferSize: 128KB
    wireType: BINARY

// account_settlement CQ - ClearingWorker输出队列
accountSettlementQueue:
    path: "data/chronicle/account_settlement"
    rollCycle: HOURLY
    blockSize: 64MB
    bufferSize: 128KB
    wireType: BINARY
```

**CQ参数说明**：

| 参数 | account_input | matching_input | matching_output | account_settlement | 说明 |
|------|---------------|----------------|-----------------|-------------------|------|
| blockSize | 128MB | 64MB | 64MB | 64MB | 内存映射文件块大小 |
| bufferSize | 256KB | 128KB | 128KB | 128KB | 写缓冲大小 |
| rollCycle | HOURLY | HOURLY | HOURLY | HOURLY | 滚动周期（每小时一个文件） |
| wireType | BINARY | BINARY | BINARY | BINARY | 序列化格式 |

### 12.4 Chronicle Map配置

```
Chronicle Map 快照文件配置:

// AccountWorker快照CM
accountSnapshotCM:
    path: "data/snapshots/account_snapshot_{cqIndex}.dat"
    entries: 10000                   # 预估10000个账户+持仓组合
    averageValueSize: 2048           # 账户+持仓+索引信息

// MatchingEngine快照CM
matchingSnapshotCM:
    path: "data/snapshots/matching_snapshot_{cqIndex}.dat"
    entries: 5000                    # 预估5000只股票
    averageValueSize: 65536          # 64KB每股票订单簿+索引信息
```

**快照文件结构**：

| 文件 | entries | averageValueSize | 预估大小 | 说明 |
|------|---------|------------------|----------|------|
| account_snapshot_{index}.dat | 10,000 | 2KB | ~20MB | 账户+持仓+索引 |
| matching_snapshot_{index}.dat | 5,000 | 64KB | ~320MB | OrderBook+索引 |

**幂等集合内存配置**：

| 幂等集合 | 内存限制 | 来源 |
|---------|---------|------|
| processed_order_ids | 最新10,000个 | matching_input CQ重放生成 |
| processed_trade_ids | 最新10,000个 | account_settlement CQ重放生成 |
| processed_settlement_ids | 最新10,000个 | 内存维护 |

**说明**：幂等集合不再使用Chronicle Map持久化，恢复时从CQ重放生成。

### 12.5 目录结构

```
data/
├── chronicle/
│   ├── account_input/              # CQ目录（按小时滚动）
│   │   ├── 20260523-08.cq4
│   │   ├── 20260523-09.cq4
│   │   └── metadata.cq4
│   ├── account_output/
│   │   └── ...
│   ├── matching_input/
│   │   └── ...
│   ├── matching_output/
│   │   └── ...
│   └── account_settlement/
│       └── ...
├── snapshots/
│   ├── account_snapshot_123456.dat    # AccountWorker快照
│   ├── matching_snapshot_123456.dat   # MatchingEngine快照
│   └── *.checksum                     # 校验和文件
└── h2/
    └── trading.mv.db
```

---

## 13. 监控与运维

### 13.1 关键指标

| 指标 | 说明 | 告警阈值 |
|------|------|---------|
| CQ队列深度 | incoming_event积压 | > 200 |
| HTTP等待超时率 | CompletableFuture超时比例 | > 0.01% |
| AccountWorker延迟 | 处理延迟P99 | > 5ms |
| MatchingEngine延迟 | 处理延迟P99 | > 10ms |
| ClearingWorker延迟 | 处理延迟P99 | > 10ms |
| HTTP返回延迟 | 下单到返回orderId P99 | > 5ms |
| 快照耗时 | 快照写入时间 | > 5s |

### 13.2 日志规范

```
[INFO] Order submitted: orderId=123, symbol=600000, side=BUY, price=1050, qty=100
[INFO] Account frozen: accountId=456, frozenAmount=105000, available=500000
[INFO] Order matched: orderId=123, tradeId=789, price=1049, qty=100
[INFO] Settlement completed: tradeId=789, buyer=456, seller=789, amount=104900
```

---

## 14. 错误码定义

### 14.1 错误码表

| 错误码 | 含义 | 场景 |
|--------|------|------|
| 0 | 成功 | - |
| 1001 | 参数无效 | 必填字段缺失/格式错误 |
| 1002 | 请求超时 | CompletableFuture超时 |
| 1003 | 请求处理中 | 幂等键状态为PROCESSING |
| 2001 | 余额不足 | available < 冻结金额 |
| 2002 | 持仓不足 | position.available < 卖出数量 |
| 2003 | 账户状态异常 | SUSPENDED/LIQUIDATING |
| 2004 | 账户不存在 | accountId无效 |
| 3001 | 订单不存在 | 撤销时订单ID无效 |
| 3002 | 订单状态不允许 | 已撤销/已成交订单重复撤销 |
| 3003 | 订单不存在于订单簿 | 撤销时订单簿无此订单 |
| 4001 | 交易阶段不允许 | 非连续竞价时段 |
| 4002 | 涨跌停限制 | 价格超出涨跌停范围 |
| 4003 | 品种不存在 | symbol无效 |
| 4004 | 自成交风险 | 存在己方反向订单 |
| 9999 | 系统异常 | 未知错误 |

### 14.2 错误响应格式

```json
{
    "code": "2001",
    "message": "余额不足",
    "data": null,
    "timestamp": "1716460800000",
    "traceId": "abc123def456"
}
```

**注意**：所有字段值均为STRING类型，包括code和timestamp。

---

## 15. 业务规则

### 15.1 涨跌停限制

```java
public void validatePrice(Order order, Instrument instrument) {
    long limitUp = instrument.prevClose * (1 + instrument.limitUpRatio);
    long limitDown = instrument.prevClose * (1 - instrument.limitDownRatio);
    
    if (order.price > limitUp) {
        throw new OrderRejectException(4002, "价格超过涨停价");
    }
    if (order.price < limitDown) {
        throw new OrderRejectException(4002, "价格低于跌停价");
    }
}
```

### 15.2 自成交预防

**自成交在订单入CQ前校验，撮合引擎不校验自成交**：

```java
// 账户+品种锁，防止竞态条件
private final ConcurrentHashMap<String, Object> accountSymbolLocks = new ConcurrentHashMap<>();

private Object getAccountSymbolLock(long accountId, String symbol) {
    String key = accountId + "_" + symbol;
    return accountSymbolLocks.computeIfAbsent(key, k -> new Object());
}

// HTTP层下单时校验自成交（在写入incoming_event CQ前）
public void validateSelfTrade(Order order) {
    // 使用账户+品种锁防止竞态
    synchronized (getAccountSymbolLock(order.accountId, order.symbol)) {
        OrderBook book = orderBooks.get(order.symbol);
        if (book == null) return;

        // 检查是否存在自己账户的对手方订单
        if (order.side == BUY) {
            for (Order sellOrder : book.getAsks()) {
                if (sellOrder.accountId == order.accountId && sellOrder.price <= order.price) {
                    throw new OrderRejectException(4004, "自成交风险：存在己方卖单");
                }
            }
        } else {
            for (Order buyOrder : book.getBids()) {
                if (buyOrder.accountId == order.accountId && buyOrder.price >= order.price) {
                    throw new OrderRejectException(4004, "自成交风险：存在己方买单");
                }
            }
        }
    }
}
```

**撮合引擎不校验自成交**：订单进入撮合引擎后，假设已通过自成交校验，直接进行价格优先、时间优先撮合。

### 15.3 T+1规则

```java
// A股买入后T+1日才能卖出
if (instrument.type == STOCK) {
    pos.t1Locked += quantity;  // 当日买入锁定
}

// SOD时解锁T+1
public void unlockT1() {
    for (Position pos : positions.values()) {
        pos.available += pos.t1Locked;
        pos.t1Locked = 0;
    }
}
```

### 15.4 佣金计算

```java
public long calculateCommission(TradeEvent event, Side side) {
    long amount = event.price * event.quantity;
    Instrument inst = instrumentMap.get(event.symbol);
    
    if (inst.type == STOCK) {
        // 股票：万分之三，最低5元
        long comm = amount * 3 / 10000;
        return Math.max(comm, 500);  // 分为单位，5元=500分
    } else if (inst.type == FUTURE) {
        // 期货：按手数计算
        return inst.commissionPerLot * (event.quantity / inst.lotSize);
    }
    return 0;
}
```

### 15.5 集合竞价处理

**集合竞价期间订单累积，不立即撮合**：

```java
public class CallAuctionProcessor {
    
    // 集合竞价期间累积订单
    private final Map<String, List<Order>> callAuctionOrders = new ConcurrentHashMap<>();
    
    /**
     * 集合竞价期间接收订单（不立即撮合）
     */
    public void addCallAuctionOrder(Order order) {
        callAuctionOrders.computeIfAbsent(order.symbol, k -> new ArrayList<>()).add(order);
    }
    
    /**
     * 集合竞价结束时撮合
     * 原则：最大成交量 → 最小剩余量 → 最接近前收盘价 → 最高买价优先
     */
    public void processCallAuction(String symbol) {
        List<Order> orders = callAuctionOrders.get(symbol);
        if (orders == null || orders.isEmpty()) return;
        
        // 1. 按价格档位统计买卖数量
        Map<Long, Long> buyVolumeAtPrice = new TreeMap<>(Collections.reverseOrder());
        Map<Long, Long> sellVolumeAtPrice = new TreeMap<>();
        
        for (Order order : orders) {
            if (order.side == BUY) {
                buyVolumeAtPrice.merge(order.price, order.quantity, Long::sum);
            } else {
                sellVolumeAtPrice.merge(order.price, order.quantity, Long::sum);
            }
        }
        
        // 2. 计算每个价格的成交量，找出开盘价
        //    遵循A股集合竞价价格发现规则：
        //    (1) 最大成交量
        //    (2) 最小剩余量（买卖一方完全成交）
        //    (3) 最接近前收盘价
        //    (4) 最高买价优先（买方价格优先）
        long maxVolume = 0;
        long minResidual = Long.MAX_VALUE;
        long openPrice = 0;
        long prevClose = instrumentMap.get(symbol).prevClose;
        
        for (Long price : allPossiblePrices(buyVolumeAtPrice, sellVolumeAtPrice)) {
            long buyQty = getAccumulatedBuyQty(buyVolumeAtPrice, price);
            long sellQty = getAccumulatedSellQty(sellVolumeAtPrice, price);
            long volume = Math.min(buyQty, sellQty);
            long residual = Math.abs(buyQty - sellQty);  // 剩余量
            
            // 价格发现优先级比较
            boolean betterPrice = false;
            if (volume > maxVolume) {
                betterPrice = true;
            } else if (volume == maxVolume) {
                if (residual < minResidual) {
                    betterPrice = true;
                } else if (residual == minResidual) {
                    long distNew = Math.abs(price - prevClose);
                    long distOld = Math.abs(openPrice - prevClose);
                    if (distNew < distOld) {
                        betterPrice = true;
                    } else if (distNew == distOld && openPrice > 0) {
                        // 最高买价优先：若价格相同，取较高的买方价格
                        if (buyVolumeAtPrice.containsKey(price) && price > openPrice) {
                            betterPrice = true;
                        }
                    }
                }
            }
            
            if (betterPrice) {
                maxVolume = volume;
                minResidual = residual;
                openPrice = price;
            }
        }
        
        // 3. 按开盘价撮合
        if (openPrice > 0) {
            matchAtPrice(symbol, openPrice, orders);
        }
        
        // 4. 清空集合竞价缓存
        callAuctionOrders.remove(symbol);
    }
    
    /**
     * 在指定价格撮合
     */
    private void matchAtPrice(String symbol, long price, List<Order> orders) {
        // 分离买卖单，按时间优先排序
        List<Order> buyOrders = orders.stream()
            .filter(o -> o.side == BUY && o.price >= price)
            .sorted(Comparator.comparingLong(o -> o.createTime))
            .collect(Collectors.toList());
            
        List<Order> sellOrders = orders.stream()
            .filter(o -> o.side == SELL && o.price <= price)
            .sorted(Comparator.comparingLong(o -> o.createTime))
            .collect(Collectors.toList());
        
        // 撮合
        int buyIdx = 0, sellIdx = 0;
        while (buyIdx < buyOrders.size() && sellIdx < sellOrders.size()) {
            Order buyOrder = buyOrders.get(buyIdx);
            Order sellOrder = sellOrders.get(sellIdx);
            
            long matchQty = Math.min(buyOrder.remainingQty, sellOrder.remainingQty);
            
            // 生成成交事件
            tradeQueue.write(new TradeEvent(buyOrder, sellOrder, price, matchQty));
            
            buyOrder.filledQuantity += matchQty;
            buyOrder.remainingQty -= matchQty;
            sellOrder.filledQuantity += matchQty;
            sellOrder.remainingQty -= matchQty;
            
            if (buyOrder.remainingQty == 0) buyIdx++;
            if (sellOrder.remainingQty == 0) sellIdx++;
        }
    }
}
```

### 15.6 风控与强平机制

**股票账户风控**：股票账户不计算风险比率，无保证金机制。风控仅检查：
- 账户余额是否为负（`balance < 0` 时标记为 `NEGATIVE_BALANCE` 状态，禁止交易）
- 持仓是否合理（持仓数量不为负）

**期货/期权账户风控**：计算风险比率，触发强平。

```java
public class RiskMonitor implements Runnable {
    
    private static final double LIQUIDATION_THRESHOLD = 1.0;  // 风险度100%强平
    private static final double WARNING_THRESHOLD = 0.8;      // 风险度80%预警
    private static final long CHECK_INTERVAL_MS = 1000;
    
    @Override
    public void run() {
        while (running) {
            Thread.sleep(CHECK_INTERVAL_MS);
            
            for (Account acc : accountStore.getAllAccounts()) {
                if (acc.status == CLOSED) continue;
                
                InstrumentType accountType = getAccountType(acc.accountId);
                
                if (accountType == STOCK) {
                    // 股票账户：检查负余额
                    if (acc.balance < 0) {
                        acc.status = AccountStatus.NEGATIVE_BALANCE;
                        log.error("股票账户负余额: accountId={}, balance={}", acc.accountId, acc.balance);
                        alertManager.sendAlert("股票账户负余额: " + acc.accountId);
                    }
                } else if (accountType == FUTURE || accountType == OPTION) {
                    // 期货/期权账户：计算风险比率
                    double riskRatio = calculateRiskRatio(acc);
                    acc.riskRatio = riskRatio;
                    
                    if (riskRatio >= LIQUIDATION_THRESHOLD) {
                        triggerLiquidation(acc, riskRatio);
                    } else if (riskRatio >= WARNING_THRESHOLD) {
                        sendRiskWarning(acc, riskRatio);
                    }
                }
            }
        }
    }
    
    /**
     * 期货/期权风险度 = 保证金占用 / 净资产
     * 股票账户不调用此方法
     */
    private double calculateRiskRatio(Account acc) {
        long unrealizedPnl = calculateUnrealizedPnl(acc.accountId);
        long netAssets = acc.balance + unrealizedPnl;
        if (netAssets <= 0) return Double.MAX_VALUE;
        return (double) acc.marginFrozen / netAssets;
    }
    
    private long calculateUnrealizedPnl(long accountId) {
        long pnl = 0;
        for (Position pos : positionStore.getPositions(accountId)) {
            Instrument inst = instrumentMap.get(pos.symbol);
            long currentPrice = getLastPrice(inst.symbol);
            pnl += pos.quantity * (currentPrice - pos.avgPrice);
        }
        return pnl;
    }
    
    private void triggerLiquidation(Account acc, double riskRatio) {
        log.warn("触发强平: accountId={}, riskRatio={}", acc.accountId, riskRatio);
        
        // 1. 更新账户状态
        acc.status = AccountStatus.LIQUIDATING;
        
        // 2. 生成强平订单（卖出所有持仓）
        for (Position pos : positionStore.getPositions(acc.accountId)) {
            if (pos.quantity > 0) {
                IncomingEvent liquidationOrder = new IncomingEvent();
                liquidationOrder.eventType = NEW_ORDER;
                liquidationOrder.accountId = acc.accountId;
                liquidationOrder.symbol = pos.symbol;
                liquidationOrder.side = SELL;
                liquidationOrder.orderType = MARKET;  // 市价强平
                liquidationOrder.quantity = pos.available + pos.frozen;
                liquidationOrder.isSystemOrder = true;
                liquidationOrder.reason = "LIQUIDATION";
                
                incomingEventCQ.write(liquidationOrder);
            }
        }
        
        // 3. WebSocket推送通知
        wsPushService.pushLiquidationNotice(acc);
    }
}
```

**强平订单特殊处理**：

```java
// AccountWorker处理强平订单时跳过常规检查
private ProcessResult processOrder(IncomingEvent event) {
    // 强平订单跳过常规风控检查
    if (event.isSystemOrder && "LIQUIDATION".equals(event.reason)) {
        // 直接冻结持仓（无可用持仓检查）
        Position pos = positionStore.getPosition(event.accountId, event.symbol);
        if (pos != null) {
            long frozenQty = Math.min(event.quantity, pos.available + pos.frozen);
            if (pos.available < frozenQty) {
                pos.frozen += (frozenQty - pos.available);
                pos.available = 0;
            } else {
                pos.available -= frozenQty;
                pos.frozen += frozenQty;
            }
        }
        return ProcessResult.success();
    }
    
    // 常规订单检查
    if (account.status != ACTIVE) {
        return ProcessResult.fail(2003, "账户状态异常: " + account.status);
    }
    // ... 正常风控检查
}
```

---

## 16. SOD与EOD流程

### 16.1 SOD流程

```
1. 检查是否存在当日CQ文件
   cqDir = "data/chronicle/2026-05-23/";

   if (!Files.exists(cqDir)) {
       // 新交易日，从上一交易日快照恢复
       mkdir(cqDir);

       // 加载上一交易日最终快照
       prevSnapshotDir = "data/snapshots/2026-05-22/";
       loadPrevDaySnapshot(prevSnapshotDir);

       // T+1解锁：昨日买入的股票今日可卖
       unlockT1Positions();

       // 初始化当日CQ文件
       createCQFiles(cqDir);

   } else {
       // 当日CQ已存在（重启恢复）
       // 从快照 + CQ增量恢复
       recoverFromSnapshotAndCQ(cqDir);
   }

2. 检查数据一致性
   // 检查是否有未解冻的资金/持仓（EOD应该已处理）
   checkPendingFreezes();

   // 验证账户不变式
   validateAccountInvariants();

3. 加载交易配置
   从H2加载交易品种、交易时段、交易日历;

4. 启动工作线程
   accountWorker.start();
   matchingEngine.start();
   clearingWorker.start();
   snapshotWorker.start();

5. 等待开盘
   phaseManager.waitForMarketOpen();

6. 开盘集合竞价
   phaseManager.setPhase(CALL_AUCTION_OPEN);
   处理集合竞价订单;

7. 进入连续竞价
   phaseManager.setPhase(CONTINUOUS_TRADING);
```

**新交易日启动流程**：

```
startNewTradingDay(date):
    cqDir = "data/chronicle/" + date + "/"

    if (!Files.exists(cqDir)):
        // 新交易日
        mkdir(cqDir)

        // 1. 加载上一交易日最终快照
        prevDate = getPreviousTradingDay(date)
        prevSnapshotDir = "data/snapshots/" + prevDate + "/"

        if (Files.exists(prevSnapshotDir)):
            loadPrevDaySnapshot(prevSnapshotDir)

            // 2. T+1解锁
            unlockT1Positions()

        // 3. 清空订单簿和订单（上一日的订单已在EOD撤销）
        orderBooks.clear()
        orderStore.clear()

        // 4. 创建当日CQ文件
        createCQFiles(cqDir)

        // 5. 幂等集合会在恢复时从CQ重放生成
        //    processed_order_ids: 从matching_input CQ重放
        //    processed_trade_ids: 从account_settlement CQ重放
        //    processed_settlement_ids: 内存维护

        log.info("New trading day {} initialized with T+1 unlock", date)
    else:
        // 当日重启
        recoverFromSnapshotAndCQ(cqDir)
        log.info("Trading day {} recovered from snapshot+CQ", date)
```

### 16.2 EOD流程

```
1. 市场收盘
   phaseManager.setPhase(CLOSED);

2. 停止接收订单
   HTTP返回"当前阶段不允许下单";

3. 等待队列清空
   awaitQueueEmpty(5000);  // 最长5秒

4. 处理收盘集合竞价（如有）
   if (hasClosingCallAuction) {
       processClosingCallAuction();
   }

5. 跨日清理：撤销所有未成交订单（通过CANCEL_ORDER事件）
   cancelAllPendingOrders();

6. 等待撤单处理完成
   awaitQueueEmpty(10000);  // 最长10秒

7. 执行最终快照
   snapshotWorker.forceSnapshot();

8. 计算并写入当日结算数据
   // 计算当日盈亏、更新持仓成本
   calculateDailyPnl();

   // 写入H2历史表（订单、成交、资金流水）
   writeToH2History();

9. 归档幂等存储
   // processed_orders/trades/settlements CM 归档
   archiveIdempotencyMaps();

10. 关闭工作线程
    accountWorker.stop();
    matchingEngine.stop();
    clearingWorker.stop();
    snapshotWorker.stop();

11. 归档CQ文件
    // 压缩当日CQ文件（可选）
    compressCQFiles();

    // 移动快照到归档目录
    archiveSnapshots();

12. 关闭CQ和H2
    closeAll();

13. 生成日终报告
    generateDailyReport();
```

**跨日清理：通过CANCEL_ORDER事件撤销所有未成交订单**：

```
/**
 * EOD时撤销所有未成交订单
 * 通过写入CANCEL_ORDER事件到account_input CQ，复用现有撤单清结算逻辑
 * 确保所有撤单操作都经过CQ持久化，保证RPO=0
 */
private void cancelAllPendingOrders() {
    int count = 0;

    for (Order order : orders.values()) {
        // 撤销所有未完成订单（包括PENDING/NEW/PARTIAL_FILLED）
        if (order.status == PENDING || order.status == NEW || order.status == PARTIAL_FILLED) {
            // 写入CANCEL_ORDER事件到account_input CQ
            AccountInputEvent cancelEvent = new AccountInputEvent();
            cancelEvent.eventType = CANCEL_ORDER;
            cancelEvent.orderId = order.orderId;
            cancelEvent.accountId = order.accountId;
            cancelEvent.symbol = order.symbol;
            cancelEvent.reason = "EOD_AUTO_CANCEL";

            accountInputCQ.write(cancelEvent);
            count++;
        }
    }

    log.info("EOD wrote {} CANCEL_ORDER events to account_input CQ", count);

    // 等待撤单处理完成（AccountWorker和ClearingWorker会处理这些事件）
    awaitQueueEmpty(10000);
}
```

**归档幂等存储**：

```
/**
 * EOD归档幂等存储
 * 新交易日开始时，幂等集合会通过CQ重放重新生成
 * 内存中仅保留最新10,000个ID，旧ID自动丢弃
 */
private void archiveIdempotencyData() {
    String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

    // 幂等集合内存中自动维护最新10,000个ID
    // 新交易日开始时，通过CQ重放重新生成：
    // - processed_order_ids: 从matching_input CQ重放生成
    // - processed_trade_ids: 从account_settlement CQ重放生成
    // - processed_settlement_ids: 内存维护，重启后重新生成

    log.info("Idempotency data will be regenerated from CQ replay on next startup");
}
```

**T+1锁定处理**：

EOD时，当日买入的股票已被标记为 `t1Locked`，无需额外处理。次日SOD时会解锁。

### 16.3 SOD恢复场景

| 场景 | CQ文件状态 | 恢复方式 |
|------|-----------|---------|
| **新交易日启动** | 不存在当日CQ | 从上一交易日快照恢复 + T+1解锁 |
| **当日重启** | 已存在当日CQ | 从CM快照恢复 + 重放CQ增量 |
| **Crash重启** | 已存在当日CQ | 从CM快照恢复 + 重放CQ增量（RPO≤1分钟） |

**新交易日启动流程**：

```java
public void startNewTradingDay(String date) {
    String cqDir = "data/chronicle/" + date + "/";

    if (!Files.exists(Paths.get(cqDir))) {
        // 新交易日
        Files.createDirectories(Paths.get(cqDir));

        // 1. 加载上一交易日最终快照
        String prevDate = getPreviousTradingDay(date);
        Path prevSnapshotDir = Paths.get("data/snapshots/" + prevDate + "/");
        
        if (Files.exists(prevSnapshotDir)) {
            loadPrevDaySnapshot(prevSnapshotDir.toString());
            
            // 2. T+1解锁
            unlockT1Positions();
        }
        
        // 3. 清空订单簿和订单（上一日的订单已在EOD撤销）
        orderBooks.clear();
        orderStore.clear();

        // 4. 创建当日CQ
        createCQFiles(cqDir);

        // 5. 清空幂等CM
        processedOrdersCM.clear();
        processedTradesCM.clear();
        processedSettlementsCM.clear();

        log.info("New trading day {} initialized with T+1 unlock", date);
    } else {
        // 当日重启
        recoverFromSnapshotAndCQ(cqDir);
        log.info("Trading day {} recovered from snapshot+CQ", date);
    }
}
```

---

## 17. 快照与恢复机制

### 17.1 快照机制概述

**核心设计**：通过 SNAPSHOT 事件触发快照，各Worker独立维护快照文件。

| Worker | 输入CQ | 输出CQ | 快照文件 | 快照内容 |
|--------|--------|--------|---------|---------|
| AccountWorker | `account_input.cq4` | `account_output.cq4`, `matching_input.cq4` | `account_snapshot_{index}.dat` | accounts, positions, account_input index, account_settlement index, matching_input lastIndex |
| MatchingEngine | `matching_input.cq4` | `matching_output.cq4` | `matching_snapshot_{index}.dat` | matching_input index, matching_output lastIndex, active orders per symbol |
| ClearingWorker | `matching_output.cq4` | `account_settlement.cq4` | 无 | - |

**关键设计原理**：

1. **AccountWorker快照内容**：
   - accounts, positions（账户和持仓状态）
   - `account_input.cq4`处理位置（用于恢复时从此位置重放）
   - `account_settlement.cq4`处理位置（用于恢复时从此位置重放SETTLEMENT事件）
   - `matching_input.cq4` lastIndex（用于恢复时生成processed_order_ids）

2. **MatchingEngine快照内容**：
   - `matching_input.cq4`处理位置
   - `matching_output.cq4` lastIndex（用于恢复时检测已存在的trade_id）
   - active orders per symbol（当前OrderBook中的活跃订单）

3. **ClearingWorker不生成快照**：
   - 恢复时从`account_settlement.cq4`读取最新10,000个trade_id用于幂等检查
   - 无需持久化状态，直接重放`matching_output.cq4`

**幂等机制设计**：

| 幂等集合 | 来源 | 内存限制 | 用途 |
|---------|------|---------|------|
| `processed_order_ids` | `matching_input.cq4` lastIndex→latest | 最新10,000个 | MatchingEngine跳过已处理订单 |
| `processed_trade_ids` | `account_settlement.cq4` snapshot index→latest | 最新10,000个 | ClearingWorker跳过已处理成交 |
| `processed_settlement_ids` | 内存维护 | 最新10,000个 | AccountWorker跳过已处理结算 |

**说明**：
- `processed_order_ids`：恢复时从快照记录的`matching_input.cq4` lastIndex位置读取到最新位置的所有订单order_id
- `processed_trade_ids`：恢复时从快照记录的`account_settlement.cq4` snapshot index位置读取到最新位置的所有成交trade_id
- 内存中仅保留最新10,000个ID，旧ID被丢弃（节省内存）
- 幂等检查通过确定性trade_id实现：`trade_id = takerOrderId_makerOrderId`

**快照处理流程**：
- Worker 本身是单线程，处理到 SNAPSHOT 事件时自然暂停后续事件处理
- 批量读取时，若发现 SNAPSHOT 事件，即使未达批量大小也立即处理当前批次
- SNAPSHOT 事件处理完成后，后续事件在下一批次处理

### 17.2 AccountWorker 快照流程

**快照文件内容**：
- `accounts`: 所有账户状态
- `positions`: 所有持仓状态
- `account_input_index`: `account_input.cq4`处理位置
- `account_settlement_index`: `account_settlement.cq4`处理位置
- `matching_input_last_index`: `matching_input.cq4`写入的最后位置（用于恢复时生成processed_order_ids）

**快照流程**：

AccountWorker处理account_input CQ时，遇到SNAPSHOT事件执行快照：

1. 处理当前批次普通事件（SNAPSHOT之前的所有事件）
2. 执行快照：
   - 写入accounts和positions状态
   - 记录account_input CQ当前处理位置
   - 记录account_settlement CQ当前处理位置（SETTLEMENT事件来源）
   - 记录matching_input CQ写入的最后位置（用于恢复时生成processed_order_ids）
3. 后续事件在下一批次处理

**快照文件命名**: `account_snapshot_{account_input_cq_index}.dat`

**恢复流程**：

1. 加载account_snapshot_{index}.dat到内存：
   - accounts → ConcurrentHashMap<Long, Account>
   - positions → ConcurrentHashMap<String, Position>
   - 获取account_input_index, account_settlement_index, matching_input_last_index

2. 生成processed_order_ids：
   - 从matching_input CQ的matching_input_last_index位置读取到最新位置
   - 提取所有order_id，加入processed_order_ids集合
   - 内存中仅保留最新10,000个order_id（旧的丢弃）

3. 生成processed_trade_ids：
   - 从account_settlement CQ的account_settlement_index位置读取到最新位置
   - 提取所有trade_id，加入processed_trade_ids集合
   - 内存中仅保留最新10,000个trade_id（旧的丢弃）

4. 重放account_input CQ从account_input_index+1开始：
   - 处理NEW_ORDER/CANCEL_ORDER/DEPOSIT等事件

5. 重放account_settlement CQ从account_settlement_index+1开始：
   - 处理SETTLEMENT/UNFREEZE事件
   - 使用processed_settlement_ids幂等检查（内存维护最新10,000个）

### 17.3 MatchingEngine 快照流程

**快照文件内容**：
- `matching_input_index`: `matching_input.cq4`处理位置
- `matching_output_last_index`: `matching_output.cq4`写入的最后位置（用于恢复时检测已存在的trade_id）
- `active_orders`: 各symbol的活跃订单（当前在OrderBook中的订单）

**快照文件命名**: `matching_snapshot_{matching_input_cq_index}.dat`

**恢复流程**：

1. 加载matching_snapshot_{index}.dat到内存：
   - active_orders → 各symbol的OrderBook
   - 获取matching_input_index, matching_output_last_index

2. 设置replay_mode=true：
   - 恢复时MatchingEngine处于replay模式
   - replay模式下，撮合前先检查trade_id是否已存在于matching_output CQ

3. 读取matching_output CQ中已有的trade_id：
   - 从matching_output CQ开始位置读取到matching_output_last_index
   - 提取所有trade_id，加入existing_trade_ids集合

4. 重放matching_input CQ从matching_input_index+1开始：
   - 处理NEW_ORDER事件：
     - 将订单加入OrderBook
     - 执行撮合，生成TRADE事件
     - 如果生成的trade_id已存在于existing_trade_ids，跳过该TRADE（不写入matching_output）
     - 直到遇到第一个新trade_id（不在existing_trade_ids中），设置replay_mode=false
     - replay_mode=false后，正常撮合并写入所有TRADE事件
   - 处理CANCEL_ORDER事件：
     - 从OrderBook移除订单
     - 生成CANCEL_SUCCESS/CANCEL_REJECT事件

**Replay Mode机制说明**：

| replay_mode值 | 行为 |
|--------------|------|
| true | 撮合时检查trade_id是否已存在，已存在则跳过TRADE事件写入 |
| false | 正常撮合，无需检查trade_id |

**Replay Mode退出条件**：
- 遇到第一个新trade_id（不在existing_trade_ids集合中）时，设置replay_mode=false
- 之后所有TRADE事件正常写入matching_output CQ

**Replay Mode必要性**：
- 恢复时可能重复撮合已成交的订单（因为matching_input CQ重放）
- 通过trade_id确定性（takerOrderId_makerOrderId），可识别重复成交
- replay_mode确保不会写入重复TRADE事件，避免下游ClearingWorker重复处理

### 17.4 ClearingWorker 恢复机制

**ClearingWorker不生成快照**，恢复时直接重放matching_output CQ。

**恢复流程**：

1. 生成processed_trade_ids：
   - 从account_settlement CQ读取最新10,000个事件
   - 提取所有trade_id，加入processed_trade_ids集合
   - 内存中仅保留最新10,000个trade_id（旧的丢弃）

2. 重放matching_output CQ从开始位置：
   - 处理ORDER_UPDATE事件：更新订单状态
   - 处理TRADE事件：
     - 使用processed_trade_ids幂等检查（跳过已处理的成交）
     - 生成SETTLEMENT事件写入account_settlement CQ
     - 将trade_id加入processed_trade_ids（内存维护最新10,000个）
   - 处理CANCEL_SUCCESS事件：生成UNFREEZE事件写入account_settlement CQ
   - 处理CANCEL_REJECT事件：推送WebSocket通知

**幂等机制**：
- ClearingWorker使用内存processed_trade_ids集合
- processed_trade_ids来源于account_settlement CQ（最新10,000个）
- trade_id确定性：`trade_id = takerOrderId_makerOrderId`
- 重放时通过trade_id检查跳过已处理的成交

### 17.5 快照事件生成

SnapshotWorker 定时向 account_input CQ 和 matching_input CQ 写入 SNAPSHOT 事件：

**快照间隔**: 60秒

**快照流程**：

1. 向account_input CQ写入SNAPSHOT事件
2. 等待AccountWorker处理完快照（通过快照文件存在+校验和验证）
3. 向matching_input CQ写入SNAPSHOT事件
4. 等待MatchingEngine处理完快照（通过快照文件存在+校验和验证）
5. 清理旧快照文件（保留最近3个）

**校验和验证**：
- 每个快照文件写入后，计算MD5校验和并写入同名的.checksum文件
- 恢复时验证校验和，若校验失败则回退到上一个有效快照

**交易时段检测**：
- SnapshotWorker同时检测交易时段变化
- 写入PHASE_CHANGE事件到account_input CQ
- 触发集合竞价撮合（开盘/收盘集合竞价）

### 17.6 快照文件结构

```
data/snapshots/
├── account_snapshot_1001.dat         # AccountWorker在account_input_cq_index=1001时的快照
│   ├── accounts[]                    # 账户状态
│   ├── positions[]                   # 持仓状态
│   ├── account_input_index: long     # account_input.cq4处理位置
│   ├── account_settlement_index: long # account_settlement.cq4处理位置
│   └── matching_input_last_index: long # matching_input.cq4写入的最后位置
├── matching_snapshot_2005.dat        # MatchingEngine在matching_input_cq_index=2005时的快照
│   ├── matching_input_index: long    # matching_input.cq4处理位置
│   ├── matching_output_last_index: long # matching_output.cq4写入的最后位置
│   └── active_orders: Map<symbol, List<Order>> # 各symbol活跃订单
├── *.checksum                        # 各快照文件的MD5校验文件
```

**快照文件关联关系**：

| Worker | 快照文件 | 索引来源 | 用途 |
|--------|---------|---------|------|
| AccountWorker | `account_snapshot_{index}.dat` | account_input CQ | 恢复账户/持仓状态，生成processed_order_ids和processed_trade_ids |
| MatchingEngine | `matching_snapshot_{index}.dat` | matching_input CQ | 恢复OrderBook，检测已存在的trade_id |
| ClearingWorker | 无 | - | 从account_settlement CQ生成processed_trade_ids |

**恢复时幂等集合生成**：

| 幂等集合 | 来源CQ | 读取范围 | 内存限制 |
|---------|--------|---------|---------|
| `processed_order_ids` | `matching_input.cq4` | matching_input_last_index → latest | 最新10,000个 |
| `processed_trade_ids` | `account_settlement.cq4` | account_settlement_index → latest | 最新10,000个 |
| `processed_settlement_ids` | 内存维护 | - | 最新10,000个 |

### 17.7 恢复流程

**恢复顺序**：AccountWorker → MatchingEngine → ClearingWorker → AccountWorker（第二轮处理SETTLEMENT）

1. **AccountWorker恢复**：
   - 查找最新有效快照文件（验证checksum，损坏则回退到旧快照）
   - 加载account_snapshot_{index}.dat：
     - accounts → ConcurrentHashMap<Long, Account>
     - positions → ConcurrentHashMap<String, Position>
     - 获取account_input_index, account_settlement_index, matching_input_last_index
   - 生成processed_order_ids：
     - 从matching_input CQ的matching_input_last_index位置读取到最新位置
     - 提取所有order_id，加入processed_order_ids集合
     - 内存中仅保留最新10,000个order_id
   - 生成processed_trade_ids：
     - 从account_settlement CQ的account_settlement_index位置读取到最新位置
     - 提取所有trade_id，加入processed_trade_ids集合
     - 内存中仅保留最新10,000个trade_id
   - 重放account_input CQ从account_input_index+1开始：
     - 处理NEW_ORDER/CANCEL_ORDER/DEPOSIT等事件
     - 写入matching_input CQ和account_output CQ

2. **MatchingEngine恢复**：
   - 查找最新有效快照文件
   - 加载matching_snapshot_{index}.dat：
     - active_orders → 各symbol的OrderBook
     - 获取matching_input_index, matching_output_last_index
   - 设置replay_mode=true
   - 读取matching_output CQ中已有的trade_id：
     - 从开始位置读取到matching_output_last_index
     - 提取所有trade_id，加入existing_trade_ids集合
   - 重放matching_input CQ从matching_input_index+1开始：
     - 处理NEW_ORDER事件，执行撮合
     - 若生成的trade_id在existing_trade_ids中，跳过TRADE事件写入
     - 遇到第一个新trade_id时，设置replay_mode=false
     - 处理CANCEL_ORDER事件，生成CANCEL_SUCCESS/CANCEL_REJECT
     - 写入matching_output CQ

3. **ClearingWorker恢复**：
   - 生成processed_trade_ids：
     - 从account_settlement CQ读取最新10,000个事件
     - 提取所有trade_id，加入processed_trade_ids集合
   - 重放matching_output CQ从开始位置：
     - 处理ORDER_UPDATE事件，更新订单状态
     - 处理TRADE事件：
       - 若trade_id在processed_trade_ids中，跳过
       - 否则生成SETTLEMENT事件写入account_settlement CQ
       - 将trade_id加入processed_trade_ids
     - 处理CANCEL_SUCCESS事件，生成UNFREEZE事件写入account_settlement CQ
     - 处理CANCEL_REJECT事件，推送WebSocket通知

4. **AccountWorker第二轮恢复**：
   - 重放account_settlement CQ从account_settlement_index+1开始：
     - 处理SETTLEMENT事件：
       - 使用processed_settlement_ids幂等检查（内存维护最新10,000个）
       - 更新账户余额和持仓
       - 写入account_output CQ用于WebSocket推送
     - 处理UNFREEZE事件：
       - 解冻资金/持仓
       - 写入account_output CQ用于WebSocket推送

**恢复完成标志**：
- 所有CQ重放完成
- 幂等集合建立完毕
- 内存状态与快照点之后的事件一致

### 17.8 RPO/RTO 保证

| 场景 | RPO | RTO | 说明 |
|------|-----|-----|------|
| 正常关闭 | 0 | ≤30秒 | 关闭前执行最终快照 |
| Crash重启 | 0 | ≤1分钟 | 从快照恢复 + 重放CQ增量（所有事件已在CQ持久化） |
| 快照失败 | 0 | ≤5分钟 | CQ事件仍持久化，从上一个有效快照恢复或从头重放 |

**RPO=0的关键保证**：
- 所有状态变更事件都写入CQ，CQ使用内存映射文件持久化到磁盘
- 通过Event Sourcing机制：快照 + CQ增量重放可完整重建内存状态
- 快照仅用于加速恢复，不影响数据完整性

**关键保证**：
- 所有 CQ 事件持久化到磁盘
- 快照文件原子写入（先写tmp再rename）
- ClearingWorker 每批次记录处理进度

---

## 18. 优雅关闭流程

### 18.1 关闭流程

```
ShutdownHook:

1. 停止接收新请求（HTTP不再accept新连接）
   httpServer.stopAccepting();

2. 等待现有HTTP请求完成（最长3秒）
   Thread.sleep(3000);

3. 强制写入SNAPSHOT事件到CQ
   long accountIndex = accountInputCQ.writeSnapshotEvent();
   long matchIndex = matchingInputCQ.writeSnapshotEvent();

4. 等待Worker处理完SNAPSHOT（最长5秒）
   awaitSnapshotComplete("account_snapshot_" + accountIndex + ".dat", 5000);
   awaitSnapshotComplete("matching_snapshot_" + matchIndex + ".dat", 5000);

5. 停止Worker线程
   accountWorker.stop();
   matchingEngine.stop();
   clearingWorker.stop();

6. 检查是否有未处理事件（诊断用）
   long remainingAccountInput = accountInputQueue.remaining();
   long remainingMatchingInput = matchingInputQueue.remaining();
   long remainingMatchingOutput = matchingOutputQueue.remaining();
   long remainingAccountSettlement = accountSettlementQueue.remaining();

7. 关闭CQ文件
   accountInputQueue.close();
   accountOutputQueue.close();
   matchingInputQueue.close();
   matchingOutputQueue.close();
   accountSettlementQueue.close();

8. 关闭H2数据库
   h2DataSource.close();

9. 关闭WebSocket
   webSocketServer.close();
```

### 18.2 关闭时序图

```
时间线
    │
    ├─ T+0s  停止HTTP接收新请求
    │
    ├─ T+3s  写入SNAPSHOT事件到CQ
    │
    ├─ T+3s~T+8s  Worker处理SNAPSHOT，写入快照文件
    │
    ├─ T+8s  停止所有Worker
    │
    ├─ T+8s  检查未处理事件（告警）
    │
    ├─ T+9s  关闭CQ、H2、WebSocket
    │
    └─ T+10s 关闭完成
```

**关键设计点**：

1. **先写SNAPSHOT再停Worker**：确保所有已处理事件都被快照记录
2. **Worker自然暂停**：处理SNAPSHOT事件时，后续事件在下一批次，自然形成快照点
3. **超时保护**：每个阶段都有超时机制，避免无限等待
4. **诊断日志**：关闭时记录未处理事件数量，便于排查

---

## 附录

### A. 配置参数表

| 参数 | 默认值 | 说明 |
|------|--------|------|
| chronicle.base-path | data/chronicle/ | Chronicle文件基础路径 |
| h2.database-path | data/h2/trading | H2数据库路径 |
| jwt.access-token-expiration | 7200000 | Access Token过期时间（毫秒） |
| jwt.refresh-token-expiration | 604800000 | Refresh Token过期时间（毫秒） |
| rate-limit.order-tps | 100 | 单用户下单限流 |
| rate-limit.query-qps | 1000 | 单用户查询限流 |
| snapshot.interval-ms | 60000 | 快照间隔（毫秒） |
| batch.size | 50 | 批量处理大小 |
| future.timeout-ms | 5000 | CompletableFuture超时时间 |

### B. 技术栈

| 组件 | 技术 |
|------|------|
| Java | Java 21 LTS |
| Spring Boot | 3.x + 虚拟线程 |
| 消息队列 | Chronicle Queue |
| 内存存储 | ConcurrentHashMap |
| 快照存储 | Chronicle Map |
| 数据库 | H2 (磁盘模式) |
| GC策略 | ZGC (Generational) |
| 前端框架 | React 18 + TypeScript |
| 前端图表 | lightweight-charts |
| 前端UI | Ant Design 5.x |
| 前端状态 | Zustand |

---

## 版本信息

- **文档版本**: v10.0
- **创建日期**: 2026-05-23
- **架构师**: Trading System Architect
- **状态**: 设计完成，可落地开发
