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
│  │                     incoming_event CQ                                │   │
│  │  (NEW_ORDER/CANCEL_ORDER/UNFREEZE/SETTLEMENT/SNAPSHOT/PHASE_CHANGE)  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                AccountWorker (单线程，批量处理)                       │   │
│  │  • 冻结/解冻资金和持仓（内存操作）                                    │   │
│  │  • 结算转账（内存操作）                                               │   │
│  │  • 批量唤醒HTTP线程 (CompletableFuture)                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     match_orders CQ                                  │   │
│  │           (NEW_ORDER/CANCEL_ORDER/SNAPSHOT)                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │              MatchingEngine (单线程，批量处理)                        │   │
│  │  • 更新内存OrderBook • 订单撮合 • 生成成交/状态事件                    │   │
│  │  • 写入ORDER_UPDATE (订单入OrderBook确认)                             │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                       trade CQ                                       │   │
│  │           (ORDER_UPDATE/TRADE/CANCEL)                                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │              ClearingWorker (单线程，批量处理)                        │   │
│  │  • 更新orders.cm状态 (PENDING→NEW, PARTIAL_FILLED→FILLED)            │   │
│  │  • 计算结算金额 • 发送SETTLEMENT/UNFREEZE事件                         │   │
│  │  • 写入clearing.dat (trades CQ index)                                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                SnapshotWorker (定时，写入SNAPSHOT事件)                │   │
│  │  • 写入SNAPSHOT到incoming_event/match_orders CQ                       │   │
│  │  • Worker处理SNAPSHOT时写快照CM → 写index.dat                         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 数据流图

```
【下单流程 - HTTP同步等待冻结完成】

HTTP线程(虚拟线程)
    │
    │ 1. 参数校验、幂等检查
    │ 2. 写入 incoming_event CQ (NEW_ORDER)
    │ 3. CompletableFuture.await(5s)
    ▼
AccountWorker(单线程)
    │
    │ 4. 批量读取 incoming_event CQ
    │ 5. 批量内存更新账户/持仓
    │ 6. 批量写入 match_orders CQ
    │ 7. 批量 CompletableFuture.complete()
    ▼
HTTP线程返回orderId ←─────────────────────────────┘


【撮合流程 - 异步，HTTP已返回】

MatchingEngine(单线程)
    │
    │ 8. 批量读取 match_orders CQ
    │ 9. 批量更新内存OrderBook
    │ 10. 批量撮合
    │ 11. 批量写入 trade CQ
    ▼


【清算流程 - 异步】

ClearingWorker(单线程)
    │
    │ 12. 批量读取 trade CQ
    │ 13. 批量更新订单状态（内存）
    │ 14. 批量写入 incoming_event CQ (SETTLEMENT/UNFREEZE)
    ▼


【结算/解冻流程 - 异步】

AccountWorker(单线程)
    │
    │ 15. 批量读取 incoming_event CQ
    │ 16. 批量内存更新账户/持仓
    │ 17. WebSocket推送成交/撤单结果
    ▼


【快照流程 - 定时每分钟】

SnapshotWorker
    │
    │ 18. 暂停 AccountWorker/MatchingEngine/ClearingWorker
    │ 19. 内存数据 → CM快照文件 (accounts_{ver}.dat, positions_{ver}.dat, ...)
    │ 20. 获取各CQ当前索引
    │ 21. 写入 index.dat (原子: 先写tmp再rename)
    │ 22. 恢复 Worker 运行
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

#### 3.3.1 incoming_event CQ

```
IncomingEvent {
    eventType: enum,      // NEW_ORDER / CANCEL_ORDER / UNFREEZE / SETTLEMENT / SNAPSHOT / PHASE_CHANGE
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
    reason: String,       // 撤单/拒绝原因 (CANCEL_ORDER/UNFREEZE事件)
    idempotencyKey: String,
    timestamp: long,
    cqIndex: long         // SNAPSHOT事件专用：CQ写入位置，用于快照文件命名
}
```

| 事件类型 | 来源 | 说明 |
|---------|------|------|
| NEW_ORDER | HTTP线程 | 下单请求 |
| CANCEL_ORDER | HTTP线程/EOD | 撤单请求（含EOD自动撤单） |
| UNFREEZE | ClearingWorker | 解冻请求（撤单/拒绝时） |
| SETTLEMENT | ClearingWorker | 结算请求（买卖双方各一条，含frozenAmountUsed） |
| SNAPSHOT | SnapshotWorker | 触发快照保存 |
| PHASE_CHANGE | SnapshotWorker | 交易时段切换（触发集合竞价撮合） |

#### 3.3.2 match_orders CQ

```
MatchOrderEvent {
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

#### 3.3.3 trade CQ

```
TradeEvent {
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
    orderId: long,
    accountId: long,
    symbol: String,      // 用于解冻持仓
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

6. 写入incoming_event CQ（带异常处理）
   try {
       incomingEventQueue.write(IncomingEvent { eventType: NEW_ORDER, ... });
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

9. 批量读取incoming_event CQ
   batch = readBatch(incomingEventQueue, BATCH_SIZE);

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
                    matchOrdersEvents.add(createMatchOrderEvent(event));
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

11. 批量写入match_orders CQ
    batchWrite(matchOrdersQueue, matchOrdersEvents);

12. 批量唤醒HTTP线程
    for (i = 0; i < orderIdsToComplete.size(); i++) {
        futureManager.complete(orderIdsToComplete.get(i), results.get(i));
    }
```

### 5.2 撮合流程

```
【MatchingEngine批量处理】

1. 批量读取match_orders CQ
   batch = readBatch(matchOrdersQueue, BATCH_SIZE);

2. 按symbol分组（提高缓存命中率）
   grouped = batch.groupBy(event -> event.symbol);

3. 批量撮合
   trades = [];
   for (symbol, events : grouped) {
       OrderBook book = orderBooks.get(symbol);
       for (event : events) {
           switch (event.eventType) {
               case NEW_ORDER:
                   // 幂等检查：使用订单快照CM（orders_snapshot）
                   // 重启时从订单快照加载已处理订单ID，用于幂等检查
                   if (processedOrdersSet.contains(event.orderId)) {
                       log.warn("Order already processed, skip: {}", event.orderId);
                       continue;
                   }
                   processedOrdersSet.add(event.orderId);

                   // 根据订单类型选择撮合方式
                   // 集合竞价期间：订单累积到CallAuctionProcessor，不立即撮合
                   if (currentPhase == CALL_AUCTION_OPEN || currentPhase == CALL_AUCTION_CLOSE) {
                       callAuctionProcessor.addCallAuctionOrder(event);
                       // 仍写入ORDER_UPDATE事件（订单已接收）
                       trades.add(OrderUpdateEvent {
                           orderId: event.orderId,
                           status: NEW,
                           filledQuantity: 0,
                           remainingQty: event.quantity
                       });
                   } else {
                       // 连续竞价期间：正常撮合
                       book.addOrder(event);
                       trades.addAll(matchOrderByType(book, event));
                       
                       // 写入ORDER_UPDATE事件（订单入OrderBook确认）
                       trades.add(OrderUpdateEvent {
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
                       trades.add(CancelSuccessEvent {
                           orderId: event.orderId,
                           unfreezeAmount: order.frozenAmount,
                           unfreezeQty: order.remainingQty,
                           side: order.side
                       });
                   } else {
                       // 撤单失败，返回CANCEL_REJECT
                       trades.add(CancelRejectEvent {
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

4. 批量写入trade CQ
   batchWrite(tradeQueue, trades);

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

1. 批量读取trade CQ
   batch = readBatch(tradeQueue, BATCH_SIZE);

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
               // 幂等检查：使用String类型tradeId
               // tradeId格式: "takerOrderId_makerOrderId"
               if (processedTradesCM.putIfAbsent(event.tradeId, true) != null) {
                   log.warn("Trade already processed, skip: {}", event.tradeId);
                   continue;
               }

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

               // 生成结算事件
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
               // 生成解冻事件
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

3. 批量写入incoming_event CQ
   batchWrite(incomingEventQueue, settlementEvents);

4. 每批次后写入clearing_index.dat
   writeClearingIndex(lastProcessedIndex);
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

1. 批量读取incoming_event CQ
   batch = readBatch(incomingEventQueue, BATCH_SIZE);

2. 批量处理
   for (event : batch) {
       switch (event.eventType) {
           case SETTLEMENT:
               // 幂等检查：使用settlementId = tradeId + "_" + orderId
               String settlementId = event.tradeId + "_" + event.orderId;
               if (processedSettlementsCM.putIfAbsent(settlementId, true) != null) {
                   log.warn("Settlement already processed, skip: {}", settlementId);
                   continue;
               }
               
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
               wsPushService.pushCancelResult(event);
               break;

           case PHASE_CHANGE:
               // 更新当前交易阶段
               currentPhase = event.phase;
               log.info("Trading phase changed to: {}", currentPhase);

               // 如果进入CLOSED阶段，拒绝后续NEW_ORDER事件
               if (currentPhase == CLOSED) {
                   log.info("Market closed, subsequent NEW_ORDER events will be rejected");
               }
               break;
       }
   }
```

**关键设计说明**：

1. **幂等保证**：AccountWorker使用`processed_settlements CM`记录已处理的结算ID，防止重复结算
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

```java
// Chronicle Queue 工厂配置
public class ChronicleQueueConfig {

    // incoming_event CQ - 入口事件队列
    @Bean
    public SingleChronicleQueue incomingEventQueue() {
        return SingleChronicleQueueBuilder
            .binary("data/chronicle/incoming_event")
            .rollCycle(RollCycles.HOURLY)           // 按小时滚动
            .blockSize(128L * 1024 * 1024)          // 128MB块大小
            .bufferSize(256 * 1024)                 // 256KB写缓冲
            .indexCount(512)                        // 索引槽位数
            .indexSpacing(16)                       // 索引间隔
            .wireType(WireType.BINARY)              // 二进制格式
            .build();
    }

    // match_orders CQ - 待撮合订单队列
    @Bean
    public SingleChronicleQueue matchOrdersQueue() {
        return SingleChronicleQueueBuilder
            .binary("data/chronicle/match_orders")
            .rollCycle(RollCycles.HOURLY)
            .blockSize(64L * 1024 * 1024)           // 64MB块大小
            .bufferSize(128 * 1024)
            .wireType(WireType.BINARY)
            .build();
    }

    // trade CQ - 成交事件队列
    @Bean
    public SingleChronicleQueue tradeQueue() {
        return SingleChronicleQueueBuilder
            .binary("data/chronicle/trade")
            .rollCycle(RollCycles.HOURLY)
            .blockSize(64L * 1024 * 1024)
            .bufferSize(128 * 1024)
            .wireType(WireType.BINARY)
            .build();
    }
}
```

**CQ参数说明**：

| 参数 | incoming_event | match_orders | trade | 说明 |
|------|----------------|--------------|-------|------|
| blockSize | 128MB | 64MB | 64MB | 内存映射文件块大小 |
| bufferSize | 256KB | 128KB | 128KB | 写缓冲大小 |
| rollCycle | HOURLY | HOURLY | HOURLY | 滚动周期（每小时一个文件） |
| wireType | BINARY | BINARY | BINARY | 序列化格式 |

### 12.4 Chronicle Map配置

```java
// Chronicle Map 快照文件配置
public class ChronicleMapConfig {

    // 账户快照CM
    public ChronicleMap<String, AccountSnapshot> createAccountSnapshotCM(long cqIndex) {
        return ChronicleMap
            .of(String.class, AccountSnapshot.class)
            .name("account_snapshot")
            .entries(5000)                          // 预估5000个账户
            .averageKeySize(32)                     // 平均key长度32字节
            .averageValueSize(512)                  // 平均value大小512字节
            .createPersistedTo(String.format(
                "data/snapshots/account_snapshot_%d.dat", cqIndex));
    }

    // 订单快照CM
    public ChronicleMap<Long, OrderSnapshot> createOrderSnapshotCM(long cqIndex) {
        return ChronicleMap
            .of(Long.class, OrderSnapshot.class)
            .name("order_snapshot")
            .entries(100000)                        // 预估10万订单
            .averageValueSize(256)
            .createPersistedTo(String.format(
                "data/snapshots/order_snapshot_%d.dat", cqIndex));
    }

    // 订单簿快照CM
    public ChronicleMap<String, OrderBookSnapshot> createOrderbookSnapshotCM(long cqIndex) {
        return ChronicleMap
            .of(String.class, OrderBookSnapshot.class)
            .name("orderbook_snapshot")
            .entries(5000)                          // 预估5000只股票
            .averageKeySize(8)                      // symbol长度
            .averageValueSize(65536)                // 64KB每股票订单簿
            .createPersistedTo(String.format(
                "data/snapshots/orderbook_snapshot_%d.dat", cqIndex));
    }

    // 幂等存储：已处理订单ID
    @Bean
    public ChronicleMap<Long, Boolean> processedOrdersCM() {
        return ChronicleMap
            .of(Long.class, Boolean.class)
            .name("processed_orders")
            .entries(1000000)                       // 预估100万订单
            .createOrRecoverPersistedTo(
                "data/chronicle/processed_orders.dat");
    }

    // 幂等存储：已处理成交ID
    @Bean
    public ChronicleMap<String, Boolean> processedTradesCM() {
        return ChronicleMap
            .of(String.class, Boolean.class)
            .name("processed_trades")
            .entries(500000)                        // 预估50万成交
            .averageKeySize(32)                     // tradeId格式: takerId_makerId
            .createOrRecoverPersistedTo(
                "data/chronicle/processed_trades.dat");
    }

    // 幂等存储：已处理结算ID
    @Bean
    public ChronicleMap<String, Boolean> processedSettlementsCM() {
        return ChronicleMap
            .of(String.class, Boolean.class)
            .name("processed_settlements")
            .entries(1000000)                       // 预估100万结算（买卖双方各一条）
            .averageKeySize(48)                     // settlementId格式: tradeId_orderId
            .createOrRecoverPersistedTo(
                "data/chronicle/processed_settlements.dat");
    }
}
```

**CM参数说明**：

| 文件 | entries | averageValueSize | 预估大小 | 说明 |
|------|---------|------------------|----------|------|
| account_snapshot | 5,000 | 512B | ~2.5MB | 账户快照 |
| order_snapshot | 100,000 | 256B | ~25MB | 订单快照 |
| orderbook_snapshot | 5,000 | 64KB | ~320MB | 订单簿快照 |
| processed_orders | 1,000,000 | 1B | ~8MB | 已处理订单ID |
| processed_trades | 500,000 | 1B | ~15MB | 已处理成交ID |
| processed_settlements | 1,000,000 | 1B | ~48MB | 已处理结算ID |

### 12.5 目录结构

```
data/
├── chronicle/
│   ├── incoming_event/           # CQ目录（按小时滚动）
│   │   ├── 20260523-08.cq4
│   │   ├── 20260523-09.cq4
│   │   └── metadata.cq4
│   ├── match_orders/
│   │   └── ...
│   ├── trade/
│   │   └── ...
│   ├── processed_orders.dat       # 幂等存储
│   ├── processed_trades.dat
│   └── processed_settlements.dat
├── snapshots/
│   ├── account_snapshot_123456.dat
│   ├── order_snapshot_123456.dat
│   ├── orderbook_snapshot_123456.dat
│   └── clearing_index.dat
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
       // 从CM快照 + CQ增量恢复
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

**T+1解锁逻辑**：

```java
private void unlockT1Positions() {
    for (Position pos : positions.values()) {
        // A股T+1：昨日买入的今日可卖
        if (pos.t1Locked > 0) {
            pos.available += pos.t1Locked;
            pos.t1Locked = 0;
            log.info("T+1 unlocked: accountId={}, symbol={}, qty={}",
                     pos.accountId, pos.symbol, pos.available);
        }
    }
}
```

**检查未解冻数据**：

```java
private void checkPendingFreezes() {
    // 检查是否有未解冻的资金
    for (Account acc : accounts.values()) {
        if (acc.orderFrozen > 0) {
            log.error("发现未解冻资金: accountId={}, orderFrozen={}", 
                      acc.accountId, acc.orderFrozen);
            // 告警，需要人工介入
            alertManager.sendAlert("账户存在未解冻资金: " + acc.accountId);
        }
    }
    
    // 检查是否有未解冻的持仓
    for (Position pos : positions.values()) {
        if (pos.frozen > 0) {
            log.error("发现未解冻持仓: accountId={}, symbol={}, frozen={}",
                      pos.accountId, pos.symbol, pos.frozen);
            alertManager.sendAlert("持仓存在未解冻数量: " + pos.accountId + "/" + pos.symbol);
        }
    }
}
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

```java
/**
 * EOD时撤销所有未成交订单
 * 通过写入CANCEL_ORDER事件到incoming_event CQ，复用现有撤单清结算逻辑
 * 确保所有撤单操作都经过CQ持久化，保证RPO=0
 */
private void cancelAllPendingOrders() {
    int count = 0;
    
    for (Order order : orders.values()) {
        // 撤销所有未完成订单（包括PENDING/NEW/PARTIAL_FILLED）
        if (order.status == PENDING || order.status == NEW || order.status == PARTIAL_FILLED) {
            // 写入CANCEL_ORDER事件到incoming_event CQ
            IncomingEvent cancelEvent = new IncomingEvent();
            cancelEvent.eventType = CANCEL_ORDER;
            cancelEvent.orderId = order.orderId;
            cancelEvent.accountId = order.accountId;
            cancelEvent.symbol = order.symbol;
            cancelEvent.reason = "EOD_AUTO_CANCEL";
            
            incomingEventCQ.write(cancelEvent);
            count++;
        }
    }
    
    log.info("EOD wrote {} CANCEL_ORDER events to incoming_event CQ", count);
    
    // 等待撤单处理完成（AccountWorker和ClearingWorker会处理这些事件）
    awaitQueueEmpty(10000);
}
```

**归档幂等存储**：

```java
/**
 * EOD归档幂等存储CM文件
 * 将当日processed_orders/trades/settlements CM重命名为带日期的备份文件
 * 并创建新的空CM供次日交易使用
 */
private void archiveIdempotencyMaps() {
    String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    
    // 归档processed_orders CM
    Path ordersOld = Paths.get("data/chronicle/processed_orders.dat");
    Path ordersArchive = Paths.get("data/archive/processed_orders_" + date + ".dat");
    Files.move(ordersOld, ordersArchive, StandardCopyOption.REPLACE_EXISTING);
    processedOrdersCM.close();
    processedOrdersCM = ChronicleMap.of(Long.class, Boolean.class)
        .name("processed_orders")
        .entries(1000000)
        .createPersistedTo(ordersOld.toString());
    
    // 归档processed_trades CM
    Path tradesOld = Paths.get("data/chronicle/processed_trades.dat");
    Path tradesArchive = Paths.get("data/archive/processed_trades_" + date + ".dat");
    Files.move(tradesOld, tradesArchive, StandardCopyOption.REPLACE_EXISTING);
    processedTradesCM.close();
    processedTradesCM = ChronicleMap.of(String.class, Boolean.class)
        .name("processed_trades")
        .entries(500000)
        .averageKeySize(32)
        .createPersistedTo(tradesOld.toString());
    
    // 归档processed_settlements CM
    Path settlementsOld = Paths.get("data/chronicle/processed_settlements.dat");
    Path settlementsArchive = Paths.get("data/archive/processed_settlements_" + date + ".dat");
    Files.move(settlementsOld, settlementsArchive, StandardCopyOption.REPLACE_EXISTING);
    processedSettlementsCM.close();
    processedSettlementsCM = ChronicleMap.of(String.class, Boolean.class)
        .name("processed_settlements")
        .entries(1000000)
        .averageKeySize(48)
        .createPersistedTo(settlementsOld.toString());
    
    log.info("Idempotency maps archived for date {}", date);
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

**核心设计**：通过 SNAPSHOT 事件触发快照，无需额外暂停机制。

| Worker | 快照触发方式 | 快照文件命名 |
|--------|-------------|-------------|
| AccountWorker | SNAPSHOT 事件写入 incoming_event CQ | account_snapshot_{incoming_cq_index}.dat |
| MatchingEngine | SNAPSHOT 事件写入 match_orders CQ | orderbook_snapshot_{match_cq_index}.dat |
| ClearingWorker | 每批次后写入 trades CQ index | clearing_index.dat |

**关键设计原理**：

1. **独立索引存储**：三个Worker的快照索引独立存储，这是正确的设计：
   - AccountWorker处理incoming_event CQ，快照索引记录其处理位置
   - MatchingEngine处理match_orders CQ，快照索引记录其处理位置
   - ClearingWorker处理trades CQ，索引记录其处理位置
   - 各索引反映各自CQ的真实处理进度，不存在"不一致"问题

2. **恢复时的正确行为**：
   - AccountWorker从`account_snapshot_{index}`加载，从`incoming_cq_index+1`重放
   - MatchingEngine从`orderbook_snapshot_{index}`加载，从`match_cq_index+1`重放
   - ClearingWorker从`clearing_index.dat`获取位置，从`trades_cq_index+1`重放
   - 每个Worker独立恢复，下游Worker通过幂等机制处理可能的重复事件

3. **幂等机制保证一致性**：
   - MatchingEngine：`processed_orders CM`防止重复处理订单
   - ClearingWorker：`processed_trades CM`防止重复处理成交
   - AccountWorker：`processed_settlements CM`防止重复结算

**快照处理流程**：
- Worker 本身是单线程，处理到 SNAPSHOT 事件时自然暂停后续事件处理
- 批量读取时，若发现 SNAPSHOT 事件，即使未达批量大小也立即处理当前批次
- SNAPSHOT 事件处理完成后，后续事件在下一批次处理

### 17.2 AccountWorker 快照流程

```java
public class AccountWorker implements Runnable {
    
    public void run() {
        while (running) {
            // 批量读取，最多50个或超时1ms
            List<IncomingEvent> events = incomingEventCQ.readBatch(50, 1);
            
            // 检查是否包含 SNAPSHOT 事件
            IncomingEvent snapshotEvent = findSnapshotEvent(events);
            
            if (snapshotEvent != null) {
                // 1. 分离普通事件和快照事件
                List<IncomingEvent> normalEvents = events.stream()
                    .filter(e -> e.eventType != SNAPSHOT)
                    .collect(Collectors.toList());
                
                // 2. 处理普通事件（快照点之前的事件）
                for (IncomingEvent e : normalEvents) {
                    processNormalEvent(e);
                }
                
                // 3. 执行快照（此时后续事件仍在CQ中，下一批次处理）
                long cqIndex = snapshotEvent.cqIndex;
                executeAccountSnapshot(cqIndex);
                
                // 4. 继续下一批次（处理快照点之后的事件）
                continue;
            }
            
            // 无快照事件，正常批量处理
            for (IncomingEvent e : events) {
                processNormalEvent(e);
            }
        }
    }
    
    private void executeAccountSnapshot(long cqIndex) {
        // 1. 写入账户快照
        ChronicleMap<Long, Account> accountCM = ChronicleMap.of(Long.class, Account.class)
            .name("accounts")
            .entries(10000)
            .createPersistedTo("data/snapshots/account_snapshot_" + cqIndex + ".dat");
        for (Map.Entry<Long, Account> e : accounts.entrySet()) {
            accountCM.put(e.getKey(), e.getValue());
        }
        accountCM.close();
        
        // 2. 写入持仓快照
        ChronicleMap<Long, Position> positionCM = ChronicleMap.of(Long.class, Position.class)
            .name("positions")
            .entries(100000)
            .createPersistedTo("data/snapshots/position_snapshot_" + cqIndex + ".dat");
        for (Map.Entry<Long, Position> e : positions.entrySet()) {
            positionCM.put(e.getKey(), e.getValue());
        }
        positionCM.close();
        
        // 3. 写入订单快照
        ChronicleMap<Long, Order> orderCM = ChronicleMap.of(Long.class, Order.class)
            .name("orders")
            .entries(100000)
            .createPersistedTo("data/snapshots/order_snapshot_" + cqIndex + ".dat");
        for (Map.Entry<Long, Order> e : orders.entrySet()) {
            orderCM.put(e.getKey(), e.getValue());
        }
        orderCM.close();

        // 4. 写入已处理结算ID快照（processed_settlements）
        ChronicleMap<String, Boolean> settlementCM = ChronicleMap.of(String.class, Boolean.class)
            .name("processed_settlements")
            .entries(1000000)
            .averageKeySize(48)
            .createPersistedTo("data/snapshots/processed_settlements_" + cqIndex + ".dat");
        for (Map.Entry<String, Boolean> e : processedSettlementsCM.entrySet()) {
            settlementCM.put(e.getKey(), e.getValue());
        }
        settlementCM.close();

        // 5. 写入校验和文件（使用共享工具类）
        ChecksumUtils.writeChecksum("account_snapshot_" + cqIndex + ".dat");
        ChecksumUtils.writeChecksum("position_snapshot_" + cqIndex + ".dat");
        ChecksumUtils.writeChecksum("order_snapshot_" + cqIndex + ".dat");
        ChecksumUtils.writeChecksum("processed_settlements_" + cqIndex + ".dat");

        log.info("Account snapshot completed at cqIndex={}", cqIndex);
    }

    /**
     * 从事件列表中查找SNAPSHOT事件
     */
    private IncomingEvent findSnapshotEvent(List<IncomingEvent> events) {
        for (IncomingEvent e : events) {
            if (e.eventType == SNAPSHOT) {
                return e;
            }
        }
        return null;
    }

    /**
     * 处理单个IncomingEvent事件
     */
    private void processNormalEvent(IncomingEvent event) {
        switch (event.eventType) {
            case NEW_ORDER:
                processNewOrder(event);
                break;
            case CANCEL_ORDER:
                processCancelOrder(event);
                break;
            case UNFREEZE:
                processUnfreeze(event);
                break;
            case SETTLEMENT:
                processSettlement(event);
                break;
            case PHASE_CHANGE:
                processPhaseChange(event);
                break;
            default:
                log.warn("Unknown event type: {}", event.eventType);
        }
    }
}
```

### 17.3 MatchingEngine 快照流程

```java
public class MatchingEngine implements Runnable {
    
    public void run() {
        while (running) {
            List<MatchOrderEvent> events = matchOrdersCQ.readBatch(50, 1);
            
            MatchOrderEvent snapshotEvent = findSnapshotEvent(events);
            
            if (snapshotEvent != null) {
                // 1. 处理快照点之前的订单
                List<MatchOrderEvent> normalEvents = events.stream()
                    .filter(e -> e.eventType != SNAPSHOT)
                    .collect(Collectors.toList());
                
                for (MatchOrderEvent e : normalEvents) {
                    processOrder(e);
                    tryMatch(e.symbol);
                }
                
                // 2. 执行订单簿快照
                long cqIndex = snapshotEvent.cqIndex;
                executeOrderbookSnapshot(cqIndex);
                
                continue;
            }
            
            // 无快照事件，正常处理
            for (MatchOrderEvent e : events) {
                processOrder(e);
                tryMatch(e.symbol);
            }
        }
    }
    
    private void executeOrderbookSnapshot(long cqIndex) {
        // 序列化所有订单簿
        ChronicleMap<String, byte[]> orderbookCM = ChronicleMap.of(String.class, byte[].class)
            .name("orderbook")
            .entries(5000)
            .averageValueSize(10240)
            .createPersistedTo("data/snapshots/orderbook_snapshot_" + cqIndex + ".dat");
        
        for (Map.Entry<String, OrderBook> e : orderBooks.entrySet()) {
            orderbookCM.put(e.getKey(), serializeOrderBook(e.getValue()));
        }
        orderbookCM.close();
        
        // 写入校验和文件（使用共享工具类）
        ChecksumUtils.writeChecksum("orderbook_snapshot_" + cqIndex + ".dat");
        
        log.info("Orderbook snapshot completed at cqIndex={}", cqIndex);
    }
    
    /**
     * 从事件列表中查找SNAPSHOT事件
     */
    private MatchOrderEvent findSnapshotEvent(List<MatchOrderEvent> events) {
        for (MatchOrderEvent e : events) {
            if (e.eventType == SNAPSHOT) {
                return e;
            }
        }
        return null;
    }
}
```

### 17.4 ClearingWorker 索引记录

ClearingWorker 不执行完整快照，仅记录 trades CQ 处理位置：

```java
public class ClearingWorker implements Runnable {
    
    public void run() {
        while (running) {
            List<TradeEvent> events = tradeQueue.readBatch(50, 1);
            
            if (events.isEmpty()) continue;
            
            // 批量处理成交
            for (TradeEvent e : events) {
                processTrade(e);
            }
            
            // 每批次后记录当前 trades CQ index
            long lastIndex = events.get(events.size() - 1).cqIndex;
            writeClearingIndex(lastIndex);
        }
    }
    
    private void writeClearingIndex(long index) {
        // 写入 clearing_index.dat（原子：先写tmp再rename）
        Path tempFile = Paths.get("data/snapshots/clearing_index.tmp");
        Path targetFile = Paths.get("data/snapshots/clearing_index.dat");
        
        ChronicleMap<String, Long> indexCM = ChronicleMap.of(String.class, Long.class)
            .name("clearing_index")
            .entries(1)
            .createPersistedTo(tempFile.toString());
        indexCM.put("lastProcessedIndex", index);
        indexCM.close();
        
        // 原子rename
        Files.move(tempFile, targetFile, 
                   StandardCopyOption.ATOMIC_MOVE, 
                   StandardCopyOption.REPLACE_EXISTING);
    }
}
```

### 17.5 快照事件生成

SnapshotWorker 定时向 incoming_event 和 match_orders CQ 写入 SNAPSHOT 事件：

```java
public class SnapshotWorker implements Runnable {
    
    private final long snapshotIntervalMs = 60000;  // 1分钟
    private TradingPhase currentPhase = CLOSED;     // 当前交易时段
    
    public void run() {
        while (running) {
            Thread.sleep(snapshotIntervalMs);
            
            // 0. 检测交易时段变化，写入PHASE_CHANGE事件
            TradingPhase newPhase = detectTradingPhase();
            if (newPhase != currentPhase) {
                writePhaseChangeEvent(newPhase);
                currentPhase = newPhase;
            }
            
            // 1. 向 incoming_event CQ 写入 SNAPSHOT 事件
            long incomingIndex = incomingEventCQ.writeSnapshotEvent();
            log.info("SNAPSHOT event written to incoming_event CQ at index {}", incomingIndex);
            
            // 2. 等待 AccountWorker 处理完快照（通过文件存在判断 + 校验和验证）
            awaitSnapshotComplete("account_snapshot_" + incomingIndex + ".dat");
            
            // 3. 向 match_orders CQ 写入 SNAPSHOT 事件
            long matchIndex = matchOrdersCQ.writeSnapshotEvent();
            log.info("SNAPSHOT event written to match_orders CQ at index {}", matchIndex);
            
            // 4. 等待 MatchingEngine 处理完快照
            awaitSnapshotComplete("orderbook_snapshot_" + matchIndex + ".dat");
            
            // 5. 清理旧快照（保留最近3个）
            cleanupOldSnapshots();
        }
    }
    
    /**
     * 检测当前交易时段（基于系统时间）
     * A股交易时段：
     * - 09:15-09:25 开盘集合竞价
     * - 09:30-11:30 上午连续竞价
     * - 13:00-14:57 下午连续竞价
     * - 14:57-15:00 收盘集合竞价
     * - 其他时间 CLOSED
     */
    private TradingPhase detectTradingPhase() {
        LocalTime now = LocalTime.now();
        
        if (now.isAfter(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(9, 25))) {
            return CALL_AUCTION_OPEN;
        } else if (now.isAfter(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(11, 30))) {
            return CONTINUOUS_TRADING;
        } else if (now.isAfter(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(14, 57))) {
            return CONTINUOUS_TRADING;
        } else if (now.isAfter(LocalTime.of(14, 57)) && now.isBefore(LocalTime.of(15, 0))) {
            return CALL_AUCTION_CLOSE;
        } else {
            return CLOSED;
        }
    }
    
    /**
     * 写入PHASE_CHANGE事件到incoming_event CQ
     * 触发AccountWorker和MatchingEngine执行时段切换逻辑
     */
    private void writePhaseChangeEvent(TradingPhase newPhase) {
        IncomingEvent phaseEvent = new IncomingEvent();
        phaseEvent.eventType = PHASE_CHANGE;
        phaseEvent.phase = newPhase;
        phaseEvent.timestamp = System.currentTimeMillis();
        
        incomingEventCQ.write(phaseEvent);
        log.info("PHASE_CHANGE event written: {}", newPhase);
        
        // 集合竞价结束时触发撮合
        if (newPhase == CONTINUOUS_TRADING && currentPhase == CALL_AUCTION_OPEN) {
            // 集合竞价结束，触发开盘撮合
            triggerOpenCallAuction();
        } else if (newPhase == CLOSED && currentPhase == CALL_AUCTION_CLOSE) {
            // 收盘集合竞价结束，触发收盘撮合
            triggerCloseCallAuction();
        }
    }
    
    private void triggerOpenCallAuction() {
        // 通知MatchingEngine执行开盘集合竞价撮合
        for (String symbol : activeSymbols) {
            callAuctionProcessor.processCallAuction(symbol);
        }
        log.info("Open call auction triggered for {} symbols", activeSymbols.size());
    }
    
    private void triggerCloseCallAuction() {
        // 通知MatchingEngine执行收盘集合竞价撮合
        for (String symbol : activeSymbols) {
            callAuctionProcessor.processCallAuction(symbol);
        }
        log.info("Close call auction triggered for {} symbols", activeSymbols.size());
    }
    
    /**
     * 等待快照完成（文件存在 + 校验和验证）
     */
    private void awaitSnapshotComplete(String filename) {
        Path file = Paths.get("data/snapshots/" + filename);
        Path checksumFile = Paths.get("data/snapshots/" + filename + ".checksum");
        long deadline = System.currentTimeMillis() + 10000;  // 最多等10秒
        
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file) && Files.exists(checksumFile)) {
                // 验证校验和
                if (verifyChecksum(file, checksumFile)) {
                    return;
                } else {
                    log.error("Snapshot checksum mismatch: {}", filename);
                    // 删除损坏的快照文件
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(checksumFile);
                    // 继续等待，可能正在写入新的快照
                }
            }
            Thread.sleep(100);
        }
        log.warn("Snapshot timeout: {}", filename);
    }

    /**
     * 校验快照文件完整性
     */
    private boolean verifyChecksum(Path dataFile, Path checksumFile) {
        String expectedChecksum = Files.readString(checksumFile);
        String actualChecksum = ChecksumUtils.calculateMD5(dataFile);
        return expectedChecksum.equals(actualChecksum);
    }
}

/**
 * 校验和工具类（共享工具）
 */
public class ChecksumUtils {
    
    /**
     * 计算文件的MD5校验和
     */
    public static String calculateMD5(Path file) {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] fileBytes = Files.readAllBytes(file);
        byte[] hash = md.digest(fileBytes);
        
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * 写入快照文件的校验和
     */
    public static void writeChecksum(String filename) {
        Path dataFile = Paths.get("data/snapshots/" + filename);
        Path checksumFile = Paths.get("data/snapshots/" + filename + ".checksum");
        
        String checksum = calculateMD5(dataFile);
        Files.writeString(checksumFile, checksum);
    }
    
    /**
     * 验证文件校验和
     */
    public static boolean verifyChecksum(Path dataFile, Path checksumFile) {
        if (!Files.exists(checksumFile)) {
            return false;
        }
        String expectedChecksum = Files.readString(checksumFile);
        String actualChecksum = calculateMD5(dataFile);
        return expectedChecksum.equals(actualChecksum);
    }
}

/**
 * 恢复时查找有效快照
 */
public class SnapshotRecovery {

    /**
     * 查找最新有效快照索引，若损坏则回退到旧快照
     */
    public long findLatestValidSnapshotIndex(String pattern) {
        List<Path> files = findFiles(pattern);
        Collections.sort(files, Collections.reverseOrder());

        for (Path file : files) {
            Path checksumFile = Paths.get(file.toString() + ".checksum");
            if (Files.exists(checksumFile) && verifyChecksum(file, checksumFile)) {
                long index = extractIndex(file);
                log.info("Found valid snapshot: {} at index {}", file.getFileName(), index);
                return index;
            }
            log.warn("Checksum failed, trying older snapshot: {}", file.getFileName());
        }

        log.warn("No valid snapshot found for pattern: {}", pattern);
        return -1;  // 无有效快照，需从CQ起点重放
    }

    private long extractIndex(Path file) {
        // 从文件名提取index，如 account_snapshot_1001.dat → 1001
        String filename = file.getFileName().toString();
        int lastUnderscore = filename.lastIndexOf('_');
        int dotIndex = filename.indexOf('.', lastUnderscore);
        return Long.parseLong(filename.substring(lastUnderscore + 1, dotIndex));
    }
}
```

### 17.6 快照文件结构

```
data/snapshots/
├── account_snapshot_1001.dat         # AccountWorker 在 incoming_cq_index=1001 时的快照
├── position_snapshot_1001.dat        # 持仓快照（与账户快照同 index）
├── order_snapshot_1001.dat           # 订单快照（与账户快照同 index）
├── processed_settlements_1001.dat    # AccountWorker 已处理结算ID（与账户快照同 index）
├── orderbook_snapshot_2005.dat       # MatchingEngine 在 match_cq_index=2005 时的快照
├── clearing_index.dat                # ClearingWorker 最后处理的 trades_cq_index
├── *.checksum                        # 各快照文件的MD5校验文件
```

**快照文件关联关系**：

| Worker | 快照文件 | CQ索引来源 | 说明 |
|--------|---------|-----------|------|
| AccountWorker | account/position/order/processed_settlements_snapshot_{index}.dat | incoming_event CQ | 同index的四文件，用于恢复账户状态和幂等检查 |
| MatchingEngine | orderbook_snapshot_{index}.dat | match_orders CQ | 订单簿快照，已处理订单ID从order_snapshot提取 |
| ClearingWorker | clearing_index.dat | trades CQ | 仅记录CQ处理位置，processed_trades CM为内存持久化 |

**注意**：
- `processed_orders` 不单独保存快照，恢复时从 `order_snapshot` 提取订单ID用于幂等检查
- `processed_trades` CM 使用 `createOrRecoverPersistedTo` 持久化到磁盘，重启后自动恢复，无需单独快照文件

### 17.7 恢复流程

```java
public void recover() {
    log.info("Starting recovery...");

    // 1. 找到最新的有效快照文件（验证checksum，损坏则回退到旧快照）
    long accountSnapshotIndex = findLatestValidSnapshotIndex("account_snapshot_*.dat");
    long matchSnapshotIndex = findLatestValidSnapshotIndex("orderbook_snapshot_*.dat");
    long clearingIndex = readClearingIndex();

    // 如果无有效快照，从CQ起点重放
    if (accountSnapshotIndex < 0) {
        log.warn("No valid account snapshot, will replay from CQ start");
        accountSnapshotIndex = 0;
    }
    if (matchSnapshotIndex < 0) {
        log.warn("No valid match snapshot, will replay from CQ start");
        matchSnapshotIndex = 0;
    }

    log.info("Snapshot indices: account={}, match={}, clearing={}",
             accountSnapshotIndex, matchSnapshotIndex, clearingIndex);

    // 2. 加载 AccountWorker 快照到内存
    loadAccountSnapshot(accountSnapshotIndex);
    loadPositionSnapshot(accountSnapshotIndex);
    loadOrderSnapshot(accountSnapshotIndex);
    loadProcessedSettlements(accountSnapshotIndex);  // 加载已处理结算ID

    // 3. 加载 MatchingEngine 快照到内存
    loadOrderbookSnapshot(matchSnapshotIndex);
    // 从订单快照中提取已处理订单ID用于幂等检查
    loadProcessedOrdersFromOrderSnapshot(matchSnapshotIndex);

    // 4. 加载 ClearingWorker 已处理成交ID
    loadProcessedTrades(clearingIndex);  // 加载已处理成交ID

    // 5. 重放 incoming_event CQ 从 accountSnapshotIndex+1 开始
    //    (SETTLEMENT/UNFREEZE 事件需要 AccountWorker 处理)
    replayIncomingEvents(accountSnapshotIndex + 1);

    // 6. 重放 match_orders CQ 从 matchSnapshotIndex+1 开始
    //    MatchingEngine会通过 processedOrdersCM 幂等跳过已处理订单
    replayMatchOrders(matchSnapshotIndex + 1);

    // 7. 重放 trades CQ 从 clearingIndex+1 开始
    //    ClearingWorker会通过 processedTradesCM 幂等跳过已处理成交
    replayTrades(clearingIndex + 1);

    log.info("Recovery completed");
}

private void loadProcessedTrades(long index) {
    Path file = Paths.get("data/snapshots/processed_trades_" + index + ".dat");
    if (Files.exists(file)) {
        ChronicleMap<String, Boolean> cm = ChronicleMap.createFromFile(file.toString());
        for (Map.Entry<String, Boolean> e : cm.entrySet()) {
            processedTradesCM.put(e.getKey(), e.getValue());
        }
        cm.close();
        log.info("Loaded {} processed trades from snapshot at index {}",
                 processedTradesCM.size(), index);
    }
}

private void loadProcessedSettlements(long index) {
    Path file = Paths.get("data/snapshots/processed_settlements_" + index + ".dat");
    if (Files.exists(file)) {
        ChronicleMap<String, Boolean> cm = ChronicleMap.createFromFile(file.toString());
        for (Map.Entry<String, Boolean> e : cm.entrySet()) {
            processedSettlementsCM.put(e.getKey(), e.getValue());
        }
        cm.close();
        log.info("Loaded {} processed settlements from snapshot at index {}",
                 processedSettlementsCM.size(), index);
    }
}

private void loadProcessedOrdersFromOrderSnapshot(long index) {
    // 从订单快照中提取已处理订单ID（订单快照包含所有已进入撮合引擎的订单）
    Path file = Paths.get("data/snapshots/order_snapshot_" + index + ".dat");
    if (Files.exists(file)) {
        ChronicleMap<Long, Order> cm = ChronicleMap.createFromFile(file.toString());
        for (Map.Entry<Long, Order> e : cm.entrySet()) {
            processedOrdersSet.add(e.getKey());  // 订单ID用于幂等检查
        }
        cm.close();
        log.info("Loaded {} processed order IDs from order snapshot at index {}",
                 processedOrdersSet.size(), index);
    }
}

private void loadAccountSnapshot(long index) {
    Path file = Paths.get("data/snapshots/account_snapshot_" + index + ".dat");
    if (!Files.exists(file)) {
        log.warn("Account snapshot file not found: {}", file);
        return;
    }
    ChronicleMap<Long, Account> accountCM = ChronicleMap.createFromFile(file.toString());

    for (Map.Entry<Long, Account> e : accountCM.entrySet()) {
        accounts.put(e.getKey(), copyAccount(e.getValue()));
    }
    accountCM.close();

    log.info("Loaded {} accounts from snapshot at index {}", accounts.size(), index);
}

private void loadPositionSnapshot(long index) {
    Path file = Paths.get("data/snapshots/position_snapshot_" + index + ".dat");
    if (!Files.exists(file)) {
        log.warn("Position snapshot file not found: {}", file);
        return;
    }
    ChronicleMap<String, Position> positionCM = ChronicleMap.createFromFile(file.toString());

    for (Map.Entry<String, Position> e : positionCM.entrySet()) {
        positions.put(e.getKey(), copyPosition(e.getValue()));
    }
    positionCM.close();

    log.info("Loaded {} positions from snapshot at index {}", positions.size(), index);
}

/**
 * 深拷贝账户对象（Chronicle Map值类型需要显式拷贝）
 */
private Account copyAccount(Account source) {
    Account copy = new Account();
    copy.accountId = source.accountId;
    copy.userId = source.userId;
    copy.balance = source.balance;
    copy.available = source.available;
    copy.frozen = source.frozen;
    copy.marginFrozen = source.marginFrozen;
    copy.orderFrozen = source.orderFrozen;
    copy.debt = source.debt;
    copy.unrealizedPnl = source.unrealizedPnl;
    copy.status = source.status;
    copy.riskRatio = source.riskRatio;
    copy.updateTime = source.updateTime;
    return copy;
}

/**
 * 深拷贝持仓对象
 */
private Position copyPosition(Position source) {
    Position copy = new Position();
    copy.accountId = source.accountId;
    copy.symbol = source.symbol;
    copy.symbolCode = source.symbolCode;
    copy.quantity = source.quantity;
    copy.available = source.available;
    copy.frozen = source.frozen;
    copy.t1Locked = source.t1Locked;
    copy.avgPrice = source.avgPrice;
    copy.unrealizedPnl = source.unrealizedPnl;
    copy.updateTime = source.updateTime;
    return copy;
}
```

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

```java
public class ShutdownHook implements Runnable {

    @Override
    public void run() {
        log.info("Shutdown signal received, starting graceful shutdown...");

        // 1. 停止接收新请求（HTTP不再accept新连接）
        httpServer.stopAccepting();
        log.info("HTTP server stopped accepting new requests");

        // 2. 等待现有HTTP请求完成（最长3秒）
        Thread.sleep(3000);
        log.info("Waited 3s for pending HTTP requests");

        // 3. 强制写入SNAPSHOT事件到CQ
        long incomingIndex = incomingEventCQ.writeSnapshotEvent();
        long matchIndex = matchOrdersCQ.writeSnapshotEvent();
        log.info("SNAPSHOT events written: incoming={}, match={}", incomingIndex, matchIndex);

        // 4. 等待Worker处理完SNAPSHOT（最长5秒）
        awaitSnapshotComplete("account_snapshot_" + incomingIndex + ".dat", 5000);
        awaitSnapshotComplete("orderbook_snapshot_" + matchIndex + ".dat", 5000);
        log.info("Workers completed snapshot processing");

        // 5. 停止Worker线程
        accountWorker.stop();
        matchingEngine.stop();
        clearingWorker.stop();
        log.info("Workers stopped");

        // 6. 检查是否有未处理事件（诊断用）
        long remainingIncoming = incomingEventQueue.remaining();
        long remainingMatch = matchOrdersQueue.remaining();
        long remainingTrade = tradeQueue.remaining();
        if (remainingIncoming > 0 || remainingMatch > 0 || remainingTrade > 0) {
            log.warn("Remaining events: incoming={}, match={}, trade={}",
                     remainingIncoming, remainingMatch, remainingTrade);
        }

        // 7. 关闭CQ文件
        incomingEventQueue.close();
        matchOrdersQueue.close();
        tradeQueue.close();
        log.info("CQ files closed");

        // 8. 关闭H2数据库
        h2DataSource.close();
        log.info("H2 database closed");

        // 9. 关闭WebSocket
        webSocketServer.close();
        log.info("WebSocket server closed");

        log.info("Graceful shutdown completed");
    }

    private void awaitSnapshotComplete(String filename, long timeoutMs) {
        Path file = Paths.get("data/snapshots/" + filename);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file)) {
                return;
            }
            Thread.sleep(100);
        }
        log.warn("Snapshot timeout: {}", filename);
    }
}

// 注册关闭钩子
Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownHook()));
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
