# A股交易系统技术方案 V2

> 版本: v2.3
> 日期: 2026-05-19
> 架构师: Trading System Architect
> 状态: **设计完成，待开发**

---

## 修订历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| v1.0 | 2026-05-15 | 初始版本 |
| v1.1 | 2026-05-16 | 添加 API 接口定义 |
| v2.0 | 2026-05-17 | 重构并发模型：compute 原子操作、清算服务多线程设计、Queue Index 持久化、幂等设计 |
| v2.1 | 2026-05-18 | 重要修正: Order Queue 统一、单线程清算、多 Symbol 撮合、OrderBook 持久化 |
| v2.2 | 2026-05-19 | 新增总览页面、个人中心页面、OpenAPI设计 (HMAC签名认证、只读/交易Key分离) |
| v2.3 | 2026-05-19 | 新增新浪财经行情数据接口详细设计 (HTTP接口、WebSocket推送、数据解析、轮询策略) |

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
| **前端** | 完整专业界面 | TradingView K线图+订单簿+下单面板 |
| **持久化** | Chronicle 实时 + H2 异步 | Chronicle Map/Queue 实时持久化，H2 异步批量写入 |
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
│ 2. 纯内存交易 - Chronicle Queue + Chronicle Map             │
│ 3. 异步持久化 - H2 不阻塞交易主链路                          │
│ 4. 低延迟优先 - 所有设计决策以延迟为第一优先级               │
│ 5. 服务器限制 - 8核CPU，需合理分配线程                       │
│ 6. 无夜盘交易 - 仅支持A股交易时段                            │
│ 7. 数据安全 - compute 原子操作 + 幂等设计                    │
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
│  │                     订单网关 (OrderGateway)                            │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │ • 订单接收与解析                                                  │  │  │
│  │  │ • 会话管理 (1000+账户连接)                                       │  │  │
│  │  │ • 风控检查 (读 Chronicle Map)                                    │  │  │
│  │  │ • compute 原子冻结资金/持仓                                      │  │  │
│  │  │ • 写入订单到 Chronicle Queue                                     │  │  │
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
│  │  │  └─────────────────────┘  └─────────────────────┘               │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  │                                                                        │  │
│  │  撮合线程: 单线程处理，无锁设计                                         │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│                                    ▼ Chronicle Queue (成交事件)             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                     清算服务 (ClearingService)                         │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │  线程模型: N+2 线程                                              │  │  │
│  │  │  ├── TradeReader (1): 读 Trade Queue，分发到内存队列             │  │  │
│  │  │  ├── ClearingWorker (N): compute 原子更新账户/持仓               │  │  │
│  │  │  └── CancelProcessor (1): 处理撤单解冻                           │  │  │
│  │  │                                                                  │  │  │
│  │  │  关键设计:                                                        │  │  │
│  │  │  • Queue Index 持久化: 记录处理进度，重启后恢复                   │  │  │
│  │  │  • TradeId 去重表: 幂等检查，防止重复清算                         │  │  │
│  │  │  • compute 原子操作: Entry 级别锁，多线程安全                     │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│                                    ▼                                        │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                      Chronicle Map (内存映射+持久化)                   │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │  accounts.dat        Key=accountId, Value=AccountState          │  │  │
│  │  │  positions.dat       Key=(accountId<<32)|symbolCode             │  │  │
│  │  │  progress.dat        Key="trade_queue_index", Value=ProgressState│  │  │
│  │  │  processed_trades.dat Key=tradeId, Value=ProcessedTradeState     │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│                                    ▼ 异步批量写入                           │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                      H2 Database (历史查询)                            │  │
│  │  • 磁盘持久化模式                                                      │  │
│  │  • 仅用于历史查询、报表                                                │  │
│  │  • 不参与主链路                                                        │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                        风控服务 (RiskControl)                          │  │
│  │  • 读 Chronicle Map (多读取者安全)                                     │  │
│  │  • 资金充足性检查 O(1)                                                 │  │
│  │  • 持仓限额检查                                                        │  │
│  │  • 涨跌停价格校验                                                      │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心设计原则

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          核心设计原则                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 【compute 原子操作】                                                     │
│     - Chronicle Map 的 compute 方法提供 Entry 级别锁                        │
│     - 读取-修改-写入在同一个锁保护下完成，原子性保证                         │
│     - 不同 key 可并发执行，同 key 串行化                                    │
│                                                                             │
│  2. 【零拷贝】                                                              │
│     - Chronicle Queue 原生支持零拷贝序列化                                  │
│     - 使用 ByteBuffer 直接操作内存                                          │
│     - 避免对象创建和复制                                                    │
│                                                                             │
│  3. 【内存优先】                                                            │
│     - 所有交易数据常驻内存                                                  │
│     - 账户/持仓使用 Chronicle Map 内存映射                                  │
│     - 避免GC压力：对象池化                                                  │
│                                                                             │
│  4. 【异步持久化】                                                          │
│     - Chronicle Map/Queue 实时持久化，进程崩溃数据不丢失                     │
│     - H2 异步批量写入，仅用于历史查询和报表                                  │
│     - 主链路不等待数据库IO                                                  │
│                                                                             │
│  5. 【幂等设计】                                                            │
│     - TradeId 去重表防止重复清算                                            │
│     - putIfAbsent 原子检查 + 写入                                          │
│     - 保证数据一致性                                                        │
│                                                                             │
│  6. 【进度持久化】                                                          │
│     - Queue Index 存储在 Chronicle Map                                      │
│     - 重启后从上次位置继续处理                                              │
│     - 保证不丢失、不重复                                                    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 线程模型设计

### 3.1 线程模型总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          线程模型总览                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【IO线程 - Tomcat/WebSocket】                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ HTTP-IO-Thread-N      多线程，接收HTTP请求，不访问 Chronicle Map     │   │
│  │ WebSocket-IO-Thread-N 多线程，WebSocket推送，不访问 Chronicle Map    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【业务线程】                                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ OrderGateway Thread   多线程，compute 原子冻结资金/持仓              │   │
│  │ RiskControl Thread    多线程，只读取 Chronicle Map                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【撮合线程】                                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ MatchingEngine Thread 单线程，无锁设计，不访问 Chronicle Map         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【清算线程 - N+2】                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ TradeReader Thread    1线程，读 Trade Queue，分发到内存队列          │   │
│  │ ClearingWorker Thread N线程 (N=CPU核心数)，compute 原子更新         │   │
│  │ CancelProcessor Thread 1线程，处理撤单解冻                           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【异步线程】                                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ AsyncPersistence Thread 单线程，批量写入 H2，不访问 Chronicle Map    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 线程分类详解

| 线程类型 | 数量 | Chronicle Map 访问 | 说明 |
|---------|------|-------------------|------|
| **HTTP-IO-Thread** | 多线程 | 不访问 | Tomcat NIO 线程，接收请求 |
| **WebSocket-IO-Thread** | 多线程 | 不访问 | WebSocket 推送 |
| **OrderGateway Thread** | 多线程 | `compute` 写入 | 订单处理，原子冻结资金/持仓 |
| **RiskControl Thread** | 多线程 | 只读取 | 风控检查，不写入 |
| **MatchingEngine Thread** | 单线程 | 不访问 | 撮合引擎，无锁设计 |
| **TradeReader Thread** | 1 | 不访问 | 读 Trade Queue，分发到内存队列 |
| **ClearingWorker Thread** | N (CPU核心数) | `compute` 写入 | 成交清算，原子更新账户/持仓 |
| **CancelProcessor Thread** | 1 | `compute` 写入 | 撤单解冻 |
| **AsyncPersistence Thread** | 1 | 不访问 | H2 异步持久化 |

---

## 4. Chronicle Map 并发设计

### 4.1 compute 原子操作原理

```java
/**
 * Chronicle Map compute 方法实现原理
 * 
 * 特性:
 * 1. Entry 级别锁 (非全局锁)
 * 2. 读取-修改-写入在同一个锁保护下完成
 * 3. 不同 key 可并发执行
 * 4. 同 key 串行化，避免数据覆盖
 */
default V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    // 1. 对 key 对应的 entry 加锁
    // 2. 读取当前值
    // 3. 执行 remappingFunction
    // 4. 写入新值
    // 5. 释放锁
    // 整个过程是原子的
}
```

### 4.2 订单网关 - compute 原子冻结

```java
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
            freezeSuccess = freezePositionWithCompute(
                request.getAccountId(), 
                request.getSymbolCode(), 
                request.getQuantity()
            );
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
     * 
     * 关键点:
     * - Entry 级别锁，不同账户并发执行
     * - 同一账户串行执行，避免数据覆盖
     * - 读取-修改-写入原子完成
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
     * 风控检查 - 只读取 Chronicle Map
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
```

### 4.3 为什么使用 compute 方法？

| 问题 | 解决方案 |
|------|---------|
| 冻结资金必须同步 | 使用 `compute` 方法在订单网关直接冻结，无需队列中转 |
| 并发写入冲突 | `compute` 在 entry 级别加锁，保证原子性，多线程安全 |
| 数据一致性 | 读取-修改-写入在同一个锁保护下完成，不会被其他线程打断 |
| 性能优化 | Entry 级别锁，不同 key 可并发执行，高吞吐量 |
| 进程崩溃恢复 | Chronicle Map 持久化到磁盘，重启后自动恢复 |

---

## 5. 清算服务设计 (单线程顺序清算)

### 5.1 线程模型

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ClearingService 线程模型 (单线程顺序清算)                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  线程总数: 1 (单线程顺序处理)                                                │
│                                                                             │
│  【设计原则】                                                                │
│  1. 单线程保证 Index 顺序更新，无冲突问题                                    │
│  2. compute 用于原子更新账户/持仓，保证数据一致性                            │
│  3. 简单可靠，重启恢复简单                                                   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ClearingWorker Thread (单线程)                                       │   │
│  │                                                                     │   │
│  │ 循环处理:                                                            │   │
│  │   1. 从 Trade Queue 读取 TradeEvent                                 │   │
│  │   2. putIfAbsent 幂等检查 (防止重复清算)                             │   │
│  │   3. compute 原子更新买方账户/持仓                                   │   │
│  │   4. compute 原子更新卖方账户/持仓                                   │   │
│  │   5. 更新 Index (每笔或每 N 笔)                                      │   │
│  │   6. 推送 WebSocket 成交回报                                         │   │
│  │   7. 继续处理下一个成交                                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【性能分析】                                                                │
│  - compute 操作延迟 < 1μs                                                   │
│  - 每笔清算总延迟 ~5-10μs                                                    │
│  - 吞吐量: 1,000,000 / 10μs = 10万 TPS，满足 100K TPS 需求                  │
│                                                                             │
│  【为什么单线程足够？】                                                      │
│  1. 清算操作简单: 2 次 compute 更新账户 + 1 次 Index 更新                   │
│  2. compute 是内存操作，延迟极低 (< 1μs)                                    │
│  3. 单线程避免 Index 冲突，无需滑动窗口                                      │
│  4. 单线程保证顺序，重启恢复简单                                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Queue Index 持久化设计

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Queue Index 持久化流程 (单线程)                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  问题: 服务重启后，不知道从哪个 index 开始继续处理 Trade Queue               │
│                                                                             │
│  解决方案 (单线程顺序处理，简单可靠):                                        │
│                                                                             │
│  1. ClearingWorker 读取时:                                                  │
│     long index = tailer.index();                                           │
│     trade.setQueueIndex(index);  // 传递给 TradeEvent                      │
│                                                                             │
│  2. ClearingWorker 清算成功后:                                              │
│     updateProcessedIndex(trade.getQueueIndex());                           │
│                                                                             │
│  3. Index 更新策略 (单线程，直接更新):                                       │
│     progressMap.compute("trade_queue_index", (k, v) -> {                   │
│         v.setIndex(queueIndex);                                            │
│         v.setUpdateTime(System.currentTimeMillis());                       │
│         return v;                                                          │
│     });                                                                    │
│     // 单线程处理，无需 Math.max，直接赋值即可                              │
│                                                                             │
│  4. 持久化到磁盘 (每笔立即持久化):                                           │
│     // Chronicle Map 是内存映射文件，写入延迟 < 1μs                          │
│                                                                             │
│  5. 重启恢复:                                                               │
│     long startIndex = progressMap.get("trade_queue_index").getIndex();     │
│     tailer.moveToIndex(startIndex + 1);  // 从下一条开始                   │
│                                                                             │
│  存储位置: progress.dat (Chronicle Map)                                     │
│  Key: "trade_queue_index"                                                  │
│  Value: ProgressState (index, updateTime)                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.3 幂等设计：TradeId 去重表 (单线程简化版)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    幂等处理流程 (单线程)                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【问题】                                                                    │
│  重启后可能读取到已处理的成交，需要防止重复清算                               │
│                                                                             │
│  【解决方案】                                                                │
│  TradeId 去重表 (processed_trades.dat) - 防止重复清算                        │
│                                                                             │
│  【单线程优势】                                                              │
│  1. Index 顺序更新，无冲突问题                                              │
│  2. 无需滑动窗口，设计简单                                                  │
│  3. 重启恢复简单：从 Index + 1 开始读取                                     │
│                                                                             │
│  【处理流程】                                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ClearingWorker Thread (单线程) 处理成交:                              │   │
│  │                                                                     │   │
│  │ // Step 1: 幂等检查 (在清算之前!)                                    │   │
│  │ ProcessedTradeState existing = processedTradeMap.putIfAbsent(       │   │
│  │     tradeId,                                                        │   │
│  │     new ProcessedTradeState(tradeId, queueIndex, now)               │   │
│  │ );                                                                  │   │
│  │ if (existing != null) {                                             │   │
│  │     // 已处理，跳过                                                  │   │
│  │     // 单线程情况下，这种情况只在重启后发生                           │   │
│  │     return;                                                         │   │
│  │ }                                                                   │   │
│  │                                                                     │   │
│  │ // Step 2: 执行清算 (compute 原子更新)                               │   │
│  │ // 买方账户                                                          │   │
│  │ accountMap.compute(buyAccountId, (k, buyer) -> {                    │   │
│  │     buyer.setFrozen(buyer.getFrozen() - frozenAmount);              │   │
│  │     buyer.setBalance(buyer.getBalance() - amount - commission);     │   │
│  │     buyer.setAvailable(buyer.getAvailable() - commission);          │   │
│  │     return buyer;                                                   │   │
│  │ });                                                                 │   │
│  │                                                                     │   │
│  │ // 卖方账户                                                          │   │
│  │ accountMap.compute(sellAccountId, (k, seller) -> {                  │   │
│  │     seller.setBalance(seller.getBalance() + amount - fee);          │   │
│  │     seller.setAvailable(seller.getAvailable() + amount - fee);      │   │
│  │     return seller;                                                  │   │
│  │ });                                                                 │   │
│  │                                                                     │   │
│  │ // Step 3: 更新 Index (单线程，直接赋值)                              │   │
│  │ progressMap.compute("trade_queue_index", (k, v) -> {                │   │
│  │     v.setIndex(queueIndex);                                         │   │
│  │     v.setUpdateTime(System.currentTimeMillis());                    │   │
│  │     return v;                                                       │   │
│  │ });                                                                 │   │
│  │                                                                     │   │
│  │ // Step 4: 推送 WebSocket                                            │   │
│  │ wsPushService.pushTradeReport(trade);                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【存储结构】                                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 1. processed_trades.dat (Chronicle Map)                             │   │
│  │    Key: tradeId (Long)                                              │   │
│  │    Value: ProcessedTradeState {                                     │   │
│  │        long tradeId;                                                │   │
│  │        long queueIndex;                                             │   │
│  │        long processTime;                                            │   │
│  │    }                                                                │   │
│  │    作用: 防止重复清算                                               │   │
│  │                                                                     │   │
│  │ 2. progress.dat (Chronicle Map)                                     │   │
│  │    Key: "trade_queue_index"                                         │   │
│  │    Value: ProgressState {                                           │   │
│  │        long index;                                                  │   │
│  │        long updateTime;                                             │   │
│  │    }                                                                │   │
│  │    作用: 记录处理进度                                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【重启恢复流程】                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 1. 读取 progress.dat 的 trade_queue_index                           │   │
│  │ 2. 从 index + 1 开始读取 Trade Queue                                │   │
│  │ 3. 对每个 TradeEvent:                                                │   │
│  │    - 检查 processed_trades.dat.putIfAbsent(tradeId)                 │   │
│  │    - 如果已存在，跳过 (已处理过)                                     │   │
│  │    - 如果不存在，执行清算                                            │   │
│  │ 4. 清算完成后，更新 Index                                            │   │
│  │                                                                     │   │
│  【关键保证】                                                                │
│  1. tradeId 去重: 即使重复读取，也不会重复清算                              │
│  2. Index 顺序更新: 单线程保证顺序                                          │
│  3. 重启后可能重复读取，但不会重复处理                                      │
│  4. 账户数据始终一致                                                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.4 清算服务完整代码 (单线程版)

```java
/**
 * 进度状态 - 存储各队列的处理进度
 *
 * 存储位置: Chronicle Map (progress.dat)
 * 持久化: 实时写入磁盘，重启后恢复
 */
public interface ProgressState {
    String getKey();           // 队列标识: "trade_queue_index"
    long getIndex();           // 已处理的 index
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
    long getQueueIndex();      // 对应的 Queue Index
    void setQueueIndex(long index);
    long getProcessTime();     // 处理时间
    void setProcessTime(long time);
}

/**
 * 清算服务 - 单线程顺序处理
 *
 * 线程总数: 1 (单线程)
 *
 * 设计原则:
 *   1. 单线程保证 Index 顺序更新，无冲突问题
 *   2. compute 用于原子更新账户/持仓，保证数据一致性
 *   3. 简单可靠，重启恢复简单
 *
 * 性能:
 *   - 每笔清算 ~5-10μs
 *   - 吞吐量: 10万 TPS，满足需求
 */
public class ClearingService {

    private final ChronicleMap<Long, AccountState> accountMap;
    private final ChronicleMap<Long, PositionState> positionMap;
    private final ChronicleMap<String, ProgressState> progressMap;
    private final ChronicleMap<Long, ProcessedTradeState> processedTradeMap;
    private final ChronicleQueue tradeQueue;
    private final ChronicleQueue cancelQueue;
    private final WebSocketPushService wsPushService;

    private final int workerCount;  // = CPU核心数
    private final ExecutorService workerPool;
    private final BlockingQueue<TradeEvent> tradeDispatchQueue;
    private final AtomicLong processedIndex = new AtomicLong(-1);

    public ClearingService(int workerCount) {
        this.workerCount = workerCount;
        this.workerPool = Executors.newFixedThreadPool(workerCount,
            new ThreadFactoryBuilder().setNameFormat("ClearingWorker-%d").build());
        this.tradeDispatchQueue = new LinkedBlockingQueue<>(10000);
    }

    public void start() {
        // 0. 恢复上次处理的 index
        long startIndex = recoverIndex();
        log.info("ClearingService starting from index={}", startIndex);

        // 1. 启动 TradeReader 线程
        Thread tradeReaderThread = new Thread(this::runTradeReader, "TradeReader");
        tradeReaderThread.start();

        // 2. 启动 N 个 ClearingWorker 线程
        for (int i = 0; i < workerCount; i++) {
            workerPool.submit(this::runClearingWorker);
        }

        // 3. 启动 CancelProcessor 线程
        Thread cancelThread = new Thread(this::runCancelProcessor, "CancelProcessor");
        cancelThread.start();
    }

    // ==================== 线程 1: TradeReader ====================

    private void runTradeReader() {
        long startIndex = recoverIndex();
        ExcerptTailer tailer = tradeQueue.createTailer();

        if (startIndex > 0) {
            tailer.moveToIndex(startIndex);
            private final ChronicleQueue tradeQueue;
    private final ChronicleMap<Long, ProcessedTradeState> processedTradeMap;
    private final WsPushService wsPushService;

    private volatile boolean running = false;

    public void start() {
        running = true;

        // 单线程处理清算
        Thread clearingThread = new Thread(this::runClearingWorker, "ClearingWorker");
        clearingThread.start();

        log.info("ClearingService started (single thread mode)");
    }

    // ==================== 单线程清算处理 ====================

    private void runClearingWorker() {
        ExcerptTailer tailer = tradeQueue.createTailer();

        // 从上次处理的位置恢复
        long startIndex = recoverIndex();
        if (startIndex > 0) {
            tailer.moveToIndex(startIndex);
            log.info("ClearingWorker moved to index={}", startIndex);
        }

        while (running) {
            long index = tailer.index();

            tailer.readDocument(wire -> {
                TradeEvent trade = parseTrade(wire);
                trade.setQueueIndex(index);

                // 单线程顺序处理
                processTrade(trade);
            });
        }
    }

    /**
     * 处理成交清算 - 幂等设计 (单线程)
     *
     * 单线程优势:
     *   1. Index 顺序更新，无需 Math.max
     *   2. 无需滑动窗口
     *   3. 重启恢复简单
     */
    private void processTrade(TradeEvent trade) {
        // ==================== 幂等检查 ====================
        ProcessedTradeState existing = processedTradeMap.putIfAbsent(
            trade.getTradeId(),
            createProcessedTradeState(trade.getTradeId(), trade.getQueueIndex())
        );

        if (existing != null) {
            log.info("Trade already processed, tradeId={}", trade.getTradeId());
            // 已处理，跳过（重启后可能遇到）
            return;
        }

        // ==================== 执行清算 ====================
        long frozenAmount = trade.getPrice() * trade.getQuantity();

        // 买方: 解冻 + 扣款 (compute 原子更新)
        accountMap.compute(trade.getBuyAccountId(), (key, buyer) -> {
            if (buyer == null) return buyer;

            buyer.setFrozen(buyer.getFrozen() - frozenAmount);
            buyer.setBalance(buyer.getBalance() - frozenAmount - trade.getBuyerCommission());
            buyer.setAvailable(buyer.getAvailable() - trade.getBuyerCommission());
            buyer.setUpdateTime(System.currentTimeMillis());
            return buyer;
        });

        // 买方持仓增加 (compute 原子更新)
        long buyerPosKey = (trade.getBuyAccountId() << 32) | trade.getSymbolCode();
        positionMap.compute(buyerPosKey, (key, buyerPos) -> {
            if (buyerPos == null) {
                buyerPos = new PositionState();
                buyerPos.setAccountId(trade.getBuyAccountId());
                buyerPos.setSymbolCode(trade.getSymbolCode());
            }
            buyerPos.setQuantity(buyerPos.getQuantity() + trade.getQuantity());
            buyerPos.setUpdateTime(System.currentTimeMillis());
            return buyerPos;
        });

        // 卖方: 解冻持仓 + 增加资金 (compute 原子更新)
        long sellAmount = trade.getPrice() * trade.getQuantity();
        accountMap.compute(trade.getSellAccountId(), (key, seller) -> {
            if (seller == null) return seller;

            seller.setBalance(seller.getBalance() + sellAmount - trade.getSellerCommission() - trade.getStampDuty());
            seller.setAvailable(seller.getAvailable() + sellAmount - trade.getSellerCommission() - trade.getStampDuty());
            seller.setUpdateTime(System.currentTimeMillis());
            return seller;
        });

        // 卖方持仓减少 (compute 原子更新)
        long sellerPosKey = (trade.getSellAccountId() << 32) | trade.getSymbolCode();
        positionMap.compute(sellerPosKey, (key, sellerPos) -> {
            if (sellerPos == null) return sellerPos;

            sellerPos.setQuantity(sellerPos.getQuantity() - trade.getQuantity());
            sellerPos.setFrozen(sellerPos.getFrozen() - trade.getQuantity());
            sellerPos.setUpdateTime(System.currentTimeMillis());
            return sellerPos;
        });

        // ==================== 更新 Index (单线程，直接赋值) ====================
        progressMap.compute("trade_queue_index", (key, state) -> {
            if (state == null) state = new ProgressState();
            state.setIndex(trade.getQueueIndex());
            state.setUpdateTime(System.currentTimeMillis());
            return state;
        });

        // ==================== 推送 WebSocket ====================
        wsPushService.pushTradeReport(trade);
    }

    private ProcessedTradeState createProcessedTradeState(long tradeId, long queueIndex) {
        ProcessedTradeState state = new ProcessedTradeState();
        state.setTradeId(tradeId);
        state.setQueueIndex(queueIndex);
        state.setProcessTime(System.currentTimeMillis());
        return state;
    }

    private long recoverIndex() {
        ProgressState state = progressMap.get("trade_queue_index");
        if (state == null) {
            return 0;
        }
        return state.getIndex() + 1;  // 从下一条开始
    }
}

        wsPushService.pushCancelResult(req);
    }
}
```

---

## 6. Chronicle Map 存储结构

### 6.1 存储结构汇总

| 文件 | Key | Value | 说明 |
|------|-----|-------|------|
| **accounts.dat** | accountId (Long) | AccountState | 账户状态 |
| **positions.dat** | (accountId<<32)|symbolCode | PositionState | 持仓状态 |
| **progress.dat** | "trade_queue_index" | ProgressState | 队列处理进度 |
| **processed_trades.dat** | tradeId (Long) | ProcessedTradeState | 已处理成交去重表 |

### 6.2 数据结构定义

```java
/**
 * 账户状态
 */
public interface AccountState {
    long getBalance();      // 总资产
    void setBalance(long balance);

    long getAvailable();    // 可用资金
    void setAvailable(long available);

    long getFrozen();       // 冻结资金
    void setFrozen(long frozen);

    long getMargin();       // 保证金
    void setMargin(long margin);

    long getUpdateTime();   // 更新时间
    void setUpdateTime(long time);
}

/**
 * 持仓状态
 */
public interface PositionState {
    long getAccountId();    // 账户ID
    void setAccountId(long accountId);

    int getSymbolCode();    // 标的代码
    void setSymbolCode(int symbolCode);

    long getQuantity();     // 持仓数量
    void setQuantity(long quantity);

    long getAvailable();    // 可用数量 (T+1)
    void setAvailable(long available);

    long getFrozen();       // 冻结数量
    void setFrozen(long frozen);

    long getAvgPrice();     // 成本价
    void setAvgPrice(long avgPrice);

    long getUpdateTime();   // 更新时间
    void setUpdateTime(long time);
}
```

---

## 7. 数据流设计

### 7.1 订单流

```
订单请求流程:
┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│ 订单网关  │ ──▶ │ 风控检查  │ ──▶ │ compute  │ ──▶ │ Order Q  │
└──────────┘     └──────────┘     │ 原子冻结  │     └──────────┘
                      │           └──────────┘
                      ▼
              读 Chronicle Map
             (检查资金充足)
```

### 7.2 成交流

```
成交清算流程:
┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│ 撮合引擎  │ ──▶ │ Trade Q  │ ──▶ │ TradeReader│ ──▶ │ 内存队列  │
└──────────┘     └──────────┘     └──────────┘     └──────────┘
                                                          │
                                                          ▼
┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│ WebSocket│ ◀── │ 推送结果  │ ◀── │ compute  │ ◀── │Clearing  │
│  推送    │     │          │     │ 原子更新  │     │ Worker   │
└──────────┘     └──────────┘     └──────────┘     └──────────┘
                                          │
                                          ▼
                                  ┌────────────────┐
                                  │ 幂等检查       │
                                  │ putIfAbsent    │
                                  │ 更新 index     │
                                  └────────────────┘
```

---

## 8. 性能分析

### 8.1 延迟分解

```
端到端延迟 ≤ 15ms 分解:
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  1. WebSocket接收解析:        1-2ms                                         │
│  2. 风控检查 (读 Chronicle Map): ~0.1ms                                     │
│  3. compute 原子冻结:         ~1-5μs (Entry 锁)                             │
│  4. Chronicle Queue写入:      ~10μs                                         │
│  5. 撮合引擎处理:             ~10μs                                          │
│  6. TradeReader分发:          ~10μs                                          │
│  7. ClearingWorker清算:       ~50μs (含 compute 更新)                       │
│  8. WebSocket推送返回:        1-2ms                                          │
│  9. 其他开销:                 2-3ms                                          │
│                                                                             │
│  总计: 4-8ms (P99 < 15ms)                                                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 吞吐量分析

| 组件 | 吞吐量 | 说明 |
|------|--------|------|
| **OrderGateway** | 多线程并发 | compute 原子冻结，不同账户并发 |
| **MatchingEngine** | 500,000+ TPS | 单线程，无锁设计 |
| **ClearingWorker** | N 线程并发 | compute 原子更新，不同账户并发 |
| **Chronicle Queue** | 1,000,000+ msg/s | 零拷贝，内存映射 |
| **系统总吞吐** | > 100,000 TPS | 端到端 |

---

## 9. JVM配置

### 9.1 启动参数

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

### 9.2 GC选型说明

| GC | 暂停时间 | 说明 |
|----|---------|------|
| **ZGC** | < 1ms | 推荐，并发整理，不需要 Stop-The-World |
| G1GC | ~10ms | 不推荐，目标 10ms 但实际可能更高 |

---

## 10. 监控指标

### 10.1 核心指标

```java
// 订单延迟
Histogram ORDER_LATENCY = Histogram.build()
    .name("trading_order_latency_seconds")
    .help("Order processing latency")
    .buckets(0.001, 0.005, 0.010, 0.015, 0.020, 0.050, 0.100)
    .register();

// 清算延迟
Histogram CLEARING_LATENCY = Histogram.build()
    .name("trading_clearing_latency_seconds")
    .help("Clearing latency")
    .buckets(0.00001, 0.00005, 0.0001, 0.0005, 0.001)
    .register();

// 队列进度
Gauge QUEUE_PROGRESS = Gauge.build()
    .name("trading_queue_progress_index")
    .help("Trade queue processed index")
    .register();

// 幂等命中
Counter IDEMPOTENT_HIT = Counter.build()
    .name("trading_idempotent_hit_total")
    .help("Idempotent check hit count")
    .register();
```

---

## 11. 总结

### 11.1 关键设计决策

| 决策 | 原因 |
|------|------|
| **compute 原子操作** | Entry 级别锁，多线程安全，高性能 |
| **清算服务 N+2 线程** | TradeReader 单线程保证顺序，ClearingWorker 多线程并发处理 |
| **Queue Index 持久化** | 重启后恢复处理进度，不丢失数据 |
| **TradeId 去重表** | 幂等检查，防止重复清算 |
| **ZGC** | 暂停时间 < 1ms，适合低延迟交易系统 |

### 11.2 数据安全保证

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          数据安全保证                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. compute 原子操作: 读取-修改-写入原子完成，不会被其他线程打断              │
│  2. 幂等设计: putIfAbsent 原子检查 + 写入，防止重复清算                      │
│  3. 进度持久化: Queue Index 存储在 Chronicle Map，重启后恢复                │
│  4. 数据持久化: Chronicle Map/Queue 实时持久化到磁盘                        │
│  5. 进程崩溃恢复: 重启后从 Chronicle Map/Queue 恢复数据                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 12. 线程池配置详解

### 12.1 线程池配置汇总 (8核CPU)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        线程池配置总览 (8核CPU)                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【IO线程池】                                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Tomcat HTTP 线程池        数量: 8-32 (动态调整)                      │   │
│  │   配置: server.tomcat.threads.max=32, min-spare=8                   │   │
│  │   职责: 接收HTTP请求，执行订单处理 (同步)                            │   │
│  │                                                                     │   │
│  │ WebSocket EventLoopGroup   数量: 8 (CPU核心数)                       │   │
│  │   配置: new NioEventLoopGroup(8)                                    │   │
│  │   职责: WebSocket推送，不访问 Chronicle Map                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【撮合线程】                                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ MatchingEngine Thread     数量: 1 (单线程，无锁设计)                 │   │
│  │   职责: 订单撮合，从 Order Queue 读取，输出 Trade Event             │   │
│  │   特点: busy-wait 轮询，无阻塞                                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【清算线程池 - N+2】                                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ TradeReader Thread        数量: 1                                   │   │
│  │   职责: 从 Trade Queue 读取，分发到内存队列                          │   │
│  │                                                                     │   │
│  │ ClearingWorker 线程池     数量: 8 (CPU核心数)                        │   │
│  │   配置: Executors.newFixedThreadPool(8)                             │   │
│  │   职责: 成交清算，compute 原子更新账户/持仓                          │   │
│  │                                                                     │   │
│  │ CancelProcessor Thread    数量: 1                                   │   │
│  │   职责: 处理撤单解冻，compute 原子解冻                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【异步线程】                                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ AsyncPersistence Thread   数量: 1 (单线程，定时批量)                 │   │
│  │   职责: H2 异步持久化，每100ms或1000条批量写入                       │   │
│  │                                                                     │   │
│  │ MarketData Thread         数量: 1 (单线程)                          │   │
│  │   职责: 行情接收，新浪财经爬取                                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  线程总数: 32 + 8 + 1 + 1 + 8 + 1 + 1 + 1 = 53 线程                        │
│  实际活跃: ~20-30 线程 (IO线程大部分时间阻塞等待)                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 12.2 重要说明: OrderGateway 不需要独立线程池

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    OrderGateway 线程模型说明                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ⚠️ 设计决策: OrderGateway 不使用独立线程池                                  │
│                                                                             │
│  原因:                                                                      │
│  1. 订单处理是同步操作，在 Tomcat HTTP 线程中直接执行                        │
│  2. compute 原子操作保证并发安全，无需额外同步                               │
│  3. 避免不必要的线程切换开销                                                │
│                                                                             │
│  流程:                                                                      │
│  Tomcat HTTP-IO Thread                                                      │
│      │                                                                      │
│      ├── 1. 接收 HTTP POST /api/orders                                      │
│      ├── 2. 参数校验                                                        │
│      ├── 3. 风控检查 (读 Chronicle Map)                                     │
│      ├── 4. compute 原子冻结资金/持仓                                       │
│      ├── 5. 写入 Order Queue                                                │
│      └── 6. 同步返回 OrderResult                                            │
│                                                                             │
│  性能: 整个流程 < 1ms，无需异步化                                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 13. 线程切换机制

### 13.1 线程切换数据流图

详细数据流图请参考: [thread-workflow.html](thread-workflow.html)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           线程切换机制总览                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【流程 1: HTTP请求 → 订单处理】                                             │
│                                                                             │
│  Tomcat HTTP-IO Thread (同步执行)                                           │
│      │                                                                      │
│      │  1. 接收 HTTP POST /api/orders                                       │
│      │  2. 参数校验                                                         │
│      │  3. 风控检查 (读 Chronicle Map)                                      │
│      │  4. compute 原子冻结                                                 │
│      │  5. 写入 Order Queue                                                 │
│      │  6. 返回 OrderResult                                                 │
│      ▼                                                                      │
│  无线程切换 (同步完成)                                                       │
│                                                                             │
│  切换方式: 无切换，同步执行                                                  │
│  Chronicle Map 访问: compute 写入 (accounts.dat, positions.dat)             │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【流程 2: 订单 → 撮合】                                                     │
│                                                                             │
│  Tomcat HTTP-IO Thread                                                      │
│      │                                                                      │
│      │  写入 Order Queue                                                    │
│      ▼                                                                      │
│  Chronicle Queue (order_queue.dat) ──零拷贝──▶ MatchingEngine Thread        │
│                                                                             │
│  MatchingEngine Thread (单线程)                                             │
│      │                                                                      │
│      │  1. 从 Order Queue 零拷贝读取                                        │
│      │  2. 执行撮合逻辑                                                     │
│      │  3. 输出 Trade Event 到 Trade Queue                                  │
│      ▼                                                                      │
│  继续循环处理下一订单                                                        │
│                                                                             │
│  切换方式: Chronicle Queue (零拷贝，无上下文切换开销)                        │
│  Chronicle Map 访问: 不访问                                                 │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【流程 3: 成交 → 清算】                                                     │
│                                                                             │
│  MatchingEngine Thread                                                      │
│      │                                                                      │
│      │  写入 Trade Queue                                                    │
│      ▼                                                                      │
│  Chronicle Queue (trade_queue.dat) ──零拷贝──▶ TradeReader Thread           │
│                                                                             │
│  TradeReader Thread (单线程)                                                │
│      │                                                                      │
│      │  1. 从 Trade Queue 读取 TradeEvent                                   │
│      │  2. 获取 Queue Index                                                 │
│      │  3. 分发到 BlockingQueue (内存队列)                                  │
│      ▼                                                                      │
│  BlockingQueue<TradeEvent> (内存队列，容量10000)                            │
│      │                                                                      │
│      │  阻塞队列传递                                                        │
│      ▼                                                                      │
│  ClearingWorker Thread (N=8 线程并发)                                       │
│      │                                                                      │
│      │  1. 从 BlockingQueue 取出 TradeEvent                                 │
│      │  2. putIfAbsent 幂等检查                                             │
│      │  3. compute 原子更新账户/持仓                                        │
│      │  4. 立即持久化 Queue Index                                            │
│      │  5. 推送 WebSocket                                                   │
│      ▼                                                                      │
│  继续处理下一个成交                                                          │
│                                                                             │
│  切换方式: BlockingQueue (内存队列，生产者-消费者模式)                        │
│  Chronicle Map 访问:                                                        │
│    - processed_trades.dat: putIfAbsent (幂等检查)                           │
│    - accounts.dat, positions.dat: compute (原子更新)                        │
│    - progress.dat: compute (Index 持久化)                                   │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【流程 4: 清算 → 前端推送】                                                 │
│                                                                             │
│  ClearingWorker Thread                                                      │
│      │                                                                      │
│      │  清算成功后，调用 wsPushService.push()                               │
│      ▼                                                                      │
│  WebSocket Channel (Netty 异步)                                             │
│      │                                                                      │
│      │  异步发送                                                            │
│      ▼                                                                      │
│  WebSocket EventLoop Thread (8线程)                                         │
│      │                                                                      │
│      │  实际发送到客户端                                                    │
│      ▼                                                                      │
│  客户端接收                                                                  │
│                                                                             │
│  切换方式: Netty Channel (异步，非阻塞)                                      │
│  Chronicle Map 访问: 不访问                                                 │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【流程 5: 清算 → 异步持久化】                                               │
│                                                                             │
│  ClearingWorker Thread                                                      │
│      │                                                                      │
│      │  清算成功后，写入内存缓冲区                                           │
│      ▼                                                                      │
│  ConcurrentLinkedQueue<TradeEvent> (无锁队列)                               │
│      │                                                                      │
│      │  定时批量刷新 (每100ms或1000条)                                       │
│      ▼                                                                      │
│  AsyncPersistence Thread (单线程)                                           │
│      │                                                                      │
│      │  批量写入 H2                                                         │
│      │  更新 persistence_index                                              │
│      ▼                                                                      │
│  H2 Database                                                                │
│                                                                             │
│  切换方式: ConcurrentLinkedQueue (无锁队列，异步批量)                        │
│  Chronicle Map 访问: progress.dat (compute "persistence_index")             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 13.2 线程切换开销分析

| 切换方式 | 延迟 | 说明 |
|---------|------|------|
| 同步执行 | 0 | OrderGateway 在 Tomcat 线程中同步执行，无切换 |
| Chronicle Queue | < 1μs | 零拷贝，内存映射文件，无上下文切换 |
| BlockingQueue | ~1μs | 内存队列，生产者-消费者模式 |
| Netty Channel | ~1μs | 异步非阻塞，事件驱动 |
| ConcurrentLinkedQueue | < 1μs | 无锁队列，CAS 操作 |

---

## 14. JVM 重启恢复机制

### 14.1 内存队列/缓冲区丢失风险分析

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      JVM 重启时的数据风险分析                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【风险点 1: BlockingQueue<TradeEvent> 内存队列】                            │
│                                                                             │
│  TradeReader Thread                                                         │
│      │                                                                      │
│      │  读取 Trade Queue (Chronicle Queue - 已持久化)                       │
│      ▼                                                                      │
│  BlockingQueue<TradeEvent> (内存队列，容量10000)                            │
│      │                                                                      │
│      │  ⚠️ JVM 重启时，内存队列中的数据丢失！                                │
│      │  ⚠️ 这些成交可能已经被读取但未处理                                    │
│      ▼                                                                      │
│  ClearingWorker Thread                                                      │
│                                                                             │
│  【保护机制】                                                                │
│  - Index 在清算成功后才更新                                                 │
│  - 重启后从旧 Index 开始重新读取                                            │
│  - putIfAbsent 幂等检查，跳过已处理成交                                     │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【风险点 2: ConcurrentLinkedQueue 持久化缓冲】                              │
│                                                                             │
│  ClearingWorker Thread                                                      │
│      │                                                                      │
│      │  清算完成后，写入内存缓冲区                                           │
│      ▼                                                                      │
│  ConcurrentLinkedQueue<TradeEvent> (内存缓冲)                               │
│      │                                                                      │
│      │  ⚠️ JVM 重启时，缓冲区数据丢失！                                      │
│      │  ⚠️ 这些成交已清算但未写入 H2                                         │
│      ▼                                                                      │
│  AsyncPersistence Thread                                                    │
│      │                                                                      │
│      ▼                                                                      │
│  H2 Database (历史查询用)                                                   │
│                                                                             │
│  【保护机制】                                                                │
│  - 账户/持仓已在 Chronicle Map 中实时持久化                                 │
│  - H2 仅用于历史查询，不影响交易                                            │
│  - 重启后可从 Chronicle Map 恢复最新状态                                    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 14.2 数据安全保证

| 场景 | JVM 重启时机 | 重启后行为 | 是否安全 |
|------|-------------|-----------|---------|
| 1 | TradeReader 读取后，清算前 | Index 未更新，重新读取 → putIfAbsent 幂等检查 | ✅ 安全 |
| 2 | ClearingWorker 清算后，Index 更新前 | Index 未更新，重新读取 → putIfAbsent 幂等检查 | ✅ 安全 |
| 3 | Index 更新后，持久化前 | Index 可能丢失，重新读取 → putIfAbsent 幂等检查 | ✅ 安全 |
| 4 | H2 持久化缓冲区丢失 | Chronicle Map 已更新，H2 仅历史查询用 | ✅ 安全 |

### 14.3 Index 持久化优化

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Index 持久化优化 (每笔立即持久化)                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【独立的 Index Chronicle Map: progress.dat】                                │
│                                                                             │
│  存储内容:                                                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Key                      │ Value                        │ 说明      │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ "order_queue_index"      │ ProgressState (index, time)  │ 订单队列  │   │
│  │ "trade_queue_index"      │ ProgressState (index, time)  │ 成交队列  │   │
│  │ "cancel_queue_index"     │ ProgressState (index, time)  │ 撤单队列  │   │
│  │ "persistence_index"      │ ProgressState (index, time)  │ H2持久化  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【持久化策略: 每笔立即持久化】                                               │
│                                                                             │
│  ClearingWorker 清算成功后:                                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ // 1. 幂等检查 + 清算 (已保证原子性)                                  │   │
│  │ boolean success = processTradeWithIdempotency(trade);               │   │
│  │                                                                     │   │
│  │ // 2. 立即持久化 Index (Chronicle Map 是内存映射，写入极快)          │   │
│  │ if (success) {                                                      │   │
│  │     progressMap.compute("trade_queue_index", (k, v) -> {            │   │
│  │         v.setIndex(Math.max(v.getIndex(), trade.getQueueIndex()));  │   │
│  │         v.setUpdateTime(System.currentTimeMillis());                │   │
│  │         return v;                                                   │   │
│  │     });                                                             │   │
│  │ }                                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【性能影响分析】                                                            │
│  Chronicle Map 是内存映射文件:                                               │
│  - 写入操作: 直接写入内存，由 OS 异步刷盘                                    │
│  - 延迟: < 1μs (内存写入)                                                   │
│  - 即使 JVM 崩溃，OS 也会保证内存映射文件的完整性                             │
│                                                                             │
│  因此: 每笔持久化 Index 的性能开销可忽略不计                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 14.4 JVM 重启恢复流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      JVM 重启恢复流程                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. Main Thread: 加载 Chronicle Map 文件                                    │
│     - accounts.dat, positions.dat (账户/持仓状态)                           │
│     - progress.dat (各队列 Index)                                          │
│     - processed_trades.dat (已处理成交去重表)                               │
│                                                                             │
│  2. Main Thread: 读取各队列的 Index                                         │
│     long orderIndex = progressMap.get("order_queue_index").getIndex();      │
│     long tradeIndex = progressMap.get("trade_queue_index").getIndex();      │
│     long cancelIndex = progressMap.get("cancel_queue_index").getIndex();    │
│                                                                             │
│  3. MatchingEngine Thread: 从 order_index + 1 开始读取                      │
│     tailer.moveToIndex(orderIndex + 1);                                     │
│                                                                             │
│  4. TradeReader Thread: 从 trade_index + 1 开始读取                         │
│     tailer.moveToIndex(tradeIndex + 1);                                     │
│                                                                             │
│  5. ClearingWorker Thread: putIfAbsent 幂等检查                             │
│     - 如果已处理，跳过                                                       │
│     - 否则执行清算                                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 15. HTTP REST API 接口定义

详细接口定义请参考: [http-api-definition.html](http-api-definition.html)

### 15.1 接口概览

| 模块 | 接口 | 方法 | 说明 | 前端组件 |
|------|------|------|------|---------|
| **认证** | /api/auth/login | POST | 用户登录 | LoginForm.tsx |
| **认证** | /api/auth/logout | POST | 用户登出 | Header |
| **账户** | /api/account/info | GET | 账户信息 | AccountBar.tsx, AccountPanel.tsx |
| **账户** | /api/account/positions | GET | 持仓列表 | AccountPanel.tsx |
| **订单** | /api/orders | POST | 下单 | OrderPanel.tsx, QuickOrder.tsx |
| **订单** | /api/orders/{orderId} | DELETE | 撤单 | OrderTable.tsx |
| **订单** | /api/orders/active | GET | 当前委托 | OrderTable.tsx |
| **历史** | /api/trades | GET | 历史成交 | 历史成交 Tab |
| **历史** | /api/account/cashflow | GET | 资金流水 | 资金流水 Tab |
| **行情** | /api/market/symbols | GET | 股票列表 | MarketList.tsx |
| **行情** | /api/market/watchlist | GET/POST/DELETE | 自选股管理 | MarketList.tsx |

### 15.2 接口调用流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        接口调用流程 (按阶段)                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【阶段 1: 登录初始化】                                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 1. POST /api/auth/login          → 获取 JWT Token                   │   │
│  │ 2. GET /api/account/info         → 初始化导航栏账户信息              │   │
│  │ 3. GET /api/market/watchlist     → 加载自选股列表                   │   │
│  │ 4. WebSocket 连接                 → 订阅行情/订单/成交推送           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【阶段 2: 市场浏览】                                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 1. GET /api/market/symbols        → 搜索/筛选股票                   │   │
│  │ 2. POST /api/market/watchlist     → 添加自选股                      │   │
│  │ 3. WebSocket 订阅行情             → K线/订单簿/成交明细实时更新      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【阶段 3: 下单交易】                                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 1. GET /api/account/info (缓存)   → 显示可用资金                    │   │
│  │ 2. POST /api/orders               → 提交订单                        │   │
│  │ 3. WebSocket 推送订单状态         → 更新 OrderTable                  │   │
│  │ 4. WebSocket 推送成交             → 更新账户/持仓/成交明细          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【阶段 4: 撤单操作】                                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 1. DELETE /api/orders/{orderId}   → 撤单请求                        │   │
│  │ 2. WebSocket 推送撤单结果         → 更新订单状态/解冻资金           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【阶段 5: 历史查询】                                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 1. GET /api/orders/active         → 当前委托 Tab                    │   │
│  │ 2. GET /api/trades                → 历史成交 Tab                    │   │
│  │ 3. GET /api/account/cashflow      → 资金流水 Tab                    │   │
│  │ 4. GET /api/account/positions     → 持仓管理 Tab                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 15.3 核心接口详细定义

#### 15.3.1 下单接口

```
POST /api/orders

请求参数:
{
    "symbol": "600000",           // 标的代码
    "side": "buy",                // 方向: buy/sell (股票)
                                  //       open_long/open_short/close_long/close_short (期货)
                                  //       call/put (期权)
    "orderType": "limit",         // 订单类型: limit/market/fak/fok
    "price": 1000,                // 价格 (分)，市价单可不传
    "quantity": 100,              // 数量
    "accountId": 10001            // 账户ID (可选)
}

响应参数:
{
    "code": 0,
    "message": "success",
    "data": {
        "orderId": 100001,
        "symbol": "600000",
        "status": "pending",
        "createTime": 1716307200000
    }
}
```

#### 15.3.2 账户信息接口

```
GET /api/account/info

响应参数:
{
    "code": 0,
    "message": "success",
    "data": {
        "accountId": 10001,
        "totalAsset": 123456700,      // 总资产 (分)
        "marketValue": 78901200,       // 持仓市值 (分)
        "available": 56789000,         // 可用资金 (分)
        "frozen": 100000,              // 冻结资金 (分)
        "margin": 45678900,            // 占用保证金 (分)
        "riskLevel": 45.6,             // 风险度 (%)
        "unrealizedPnl": 1234500,      // 浮动盈亏 (分)
        "unrealizedPnlRatio": 1.23     // 浮盈比例 (%)
    }
}
```

### 15.4 错误码定义

| 错误码 | 说明 |
|-------|------|
| 0 | 成功 |
| 1001 | 用户名或密码错误 |
| 1002 | Token 已过期 |
| 2001 | 账户余额不足 |
| 2002 | 持仓不足 |
| 2003 | 超出涨跌停限制 |
| 2004 | 订单不存在 |
| 2005 | 订单已成交，无法撤单 |
| 9999 | 系统异常 |

---

## 16. 参考文档

- [线程工作模型数据流图](thread-workflow.html)
- [HTTP REST API 接口定义](http-api-definition.html)
- [系统架构设计 Skill](../.claude/skills/system-architecture.md)

---

## 17. V2.1 重要修正

### 16.1 Order Queue 和 Cancel Queue 合并

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    统一的 Order Queue 设计                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【问题】                                                                    │
│  原设计: Order Queue 和 Cancel Queue 分开，MatchingEngine 需监听两个队列     │
│  缺陷: 增加复杂度，需要额外的 CancelProcessor 线程                           │
│                                                                             │
│  【修正】                                                                    │
│  统一为 Order Queue，包含两种事件类型:                                       │
│                                                                             │
│  OrderEvent 类型:                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ enum OrderEventType {                                               │   │
│  │     NEW_ORDER,      // 新订单                                       │   │
│  │     CANCEL_ORDER,   // 撤单请求                                     │   │
│  │     MODIFY_ORDER    // 改单请求 (可选)                              │   │
│  │ }                                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  OrderEvent 结构:                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ interface OrderEvent {                                              │   │
│  │     long getOrderId();                                              │   │
│  │     String getSymbol();                                             │   │
│  │     OrderEventType getType();    // NEW/CANCEL/MODIFY              │   │
│  │     long getPrice();                                               │   │
│  │     long getQuantity();                                            │   │
│  │     OrderSide getSide();           // BUY/SELL                     │   │
│  │     long getAccountId();                                           │   │
│  │     long getTimestamp();                                           │   │
│  │ }                                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【优势】                                                                    │
│  1. MatchingEngine 只需监听一个队列，简化设计                               │
│  2. 顺序保证: 新订单和撤单按时间顺序处理                                    │
│  3. 无需额外的 CancelProcessor 线程                                         │
│  4. 线程数从 N+2 减少到 N+1                                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 16.2 ClearingWorker 按 AccountId 分片

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ClearingWorker 线程划分机制                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【问题】                                                                    │
│  原设计: N 个 ClearingWorker 线程并发处理，未说明如何划分                    │
│  缺陷: 同一成交可能被多个线程同时处理，依赖 putIfAbsent 幂等                 │
│                                                                             │
│  【修正】                                                                    │
│  按 AccountId 分片，同一账户的操作在同一线程串行化                           │
│                                                                             │
│  分片逻辑:                                                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ int workerId = (int) (trade.getBuyAccountId() % workerCount);       │   │
│  │ workerPools[workerId].submit(() -> processTrade(trade));            │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【幂等保证】                                                                │
│  1. 同一账户的操作在同一线程 → 自然串行化                                   │
│  2. 买方账户: 直接修改 (同线程，无需 compute)                               │
│  3. 卖方账户: compute 原子更新 (可能跨线程)                                 │
│  4. putIfAbsent 作为双重保护 (可选)                                        │
│                                                                             │
│  【处理流程】                                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ClearingWorker 线程处理成交:                                         │   │
│  │                                                                     │   │
│  │ // 1. 买方账户 (同线程，直接修改):                                   │   │
│  │ AccountState buyer = accountMap.get(buyAccountId);                 │   │
│  │ buyer.setAvailable(buyer.getAvailable() - commission);             │   │
│  │ buyer.setBalance(buyer.getBalance() - amount - commission);        │   │
│  │ // 无需 compute，同线程串行化                                        │   │
│  │                                                                     │   │
│  │ // 2. 卖方账户 (可能跨线程，compute 原子更新):                       │   │
│  │ accountMap.compute(sellAccountId, (k, seller) -> {                 │   │
│  │     seller.setBalance(seller.getBalance() + amount - fee);         │   │
│  │     seller.setAvailable(seller.getAvailable() + amount - fee);     │   │
│  │     return seller;                                                  │   │
│  │ });                                                                 │   │
│  │                                                                     │   │
│  │ // 3. 幂等检查 (可选双重保护):                                       │   │
│  │ ProcessedTradeState existing = processedTradeMap.putIfAbsent(      │   │
│  │     tradeId, createProcessedTradeState(tradeId)                    │   │
│  │ );                                                                  │   │
│  │ if (existing != null) return; // 已处理                             │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【优势】                                                                    │
│  1. 同一账户的操作串行化，减少 compute 调用                                 │
│  2. 账户热点分散 (不同账户在不同线程)                                       │
│  3. 更好的缓存局部性 (同一账户数据在同一线程)                               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 16.3 多 Symbol 撮合模型

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    多 Symbol 撮合模型                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【问题】                                                                    │
│  原设计: 只有一个 MatchingEngine Thread，未体现多 Symbol 的逻辑隔离          │
│  缺陷: 所有 Symbol 的订单混在一起处理                                       │
│                                                                             │
│  【修正】                                                                    │
│  单线程处理所有 Symbol，每个 Symbol 有独立的 OrderBook                       │
│                                                                             │
│  结构:                                                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ MatchingEngine Thread (单线程)                                       │   │
│  │                                                                     │   │
│  │ // Symbol → OrderBook 映射                                          │   │
│  │ Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();      │   │
│  │                                                                     │   │
│  │ // 处理流程                                                          │   │
│  │ while (running) {                                                   │   │
│  │     OrderEvent order = orderQueue.read();                           │   │
│  │     String symbol = order.getSymbol();                              │   │
│  │     OrderBook book = orderBooks.get(symbol);                        │   │
│  │                                                                     │   │
│  │     if (order.getType() == NEW_ORDER) {                             │   │
│  │         // 1. 写入 orderbook.dat (PENDING 状态)                     │   │
│  │         orderbookMap.put(orderKey, createOrderState(order));        │   │
│  │         // 2. 加入 OrderBook                                         │   │
│  │         book.addOrder(order);                                       │   │
│  │         // 3. 执行撮合                                               │   │
│  │         List<TradeEvent> trades = book.match(order);                │   │
│  │         // 4. 输出成交                                               │   │
│  │         for (TradeEvent trade : trades) {                           │   │
│  │             tradeQueue.write(trade);                                │   │
│  │         }                                                           │   │
│  │         // 5. 更新 orderbook.dat                                    │   │
│  │         updateOrderStates(trades);                                  │   │
│  │     } else if (order.getType() == CANCEL_ORDER) {                   │   │
│  │         // 1. 从 OrderBook 移除                                      │   │
│  │         book.cancel(order.getOrderId());                            │   │
│  │         // 2. 更新 orderbook.dat 状态为 CANCEL                      │   │
│  │         orderbookMap.compute(orderKey, (k, v) -> {                  │   │
│  │             v.setStatus(CANCEL);                                    │   │
│  │             return v;                                               │   │
│  │         });                                                         │   │
│  │         // 3. 生成 CancelEvent 到 Trade Queue                       │   │
│  │         TradeEvent cancelEvent = createCancelEvent(order);          │   │
│  │         tradeQueue.write(cancelEvent);                              │   │
│  │     }                                                               │   │
│  │ }                                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  OrderBook 结构:                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ class OrderBook {                                                   │   │
│  │     String symbol;                                                  │   │
│  │                                                                     │   │
│  │     // 买单: 价格降序 (最高价优先)                                   │   │
│  │     TreeMap<Long, List<Order>> buyOrders;                           │   │
│  │                                                                     │   │
│  │     // 卖单: 价格升序 (最低价优先)                                   │   │
│  │     TreeMap<Long, List<Order>> sellOrders;                          │   │
│  │                                                                     │   │
│  │     // 撮合逻辑: 价格优先、时间优先                                  │   │
│  │     List<TradeEvent> match(OrderEvent newOrder) {                   │   │
│  │         // ...                                                      │   │
│  │     }                                                               │   │
│  │                                                                     │   │
│  │     // 撤单逻辑                                                      │   │
│  │     boolean cancel(long orderId) {                                  │   │
│  │         // 从订单簿移除                                              │   │
│  │     }                                                               │   │
│  │ }                                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【性能分析】                                                                │
│  - 单线程处理所有 Symbol，吞吐量取决于撮合算法复杂度                        │
│  - 每笔撮合 < 10μs，100K TPS 可达成                                         │
│  - 无锁设计，无并发竞争                                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 16.4 OrderBook 持久化设计

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    OrderBook 持久化设计                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【问题】                                                                    │
│  原设计: OrderBook 是内存中的 TreeMap，JVM 重启后丢失                       │
│  缺陷: 挂单状态丢失，用户订单无法恢复                                        │
│                                                                             │
│  【修正】                                                                    │
│  使用 Chronicle Map 存储 OrderBook 状态                                     │
│                                                                             │
│  存储结构:                                                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 文件: orderbook.dat (Chronicle Map)                                 │   │
│  │                                                                     │   │
│  │ Key: (symbolCode << 32) | orderId                                  │   │
│  │ Value: OrderState {                                                 │   │
│  │     long orderId;                                                   │   │
│  │     String symbol;                                                  │   │
│  │     long price;                                                     │   │
│  │     long quantity;        // 原始数量                               │   │
│  │     long remainingQty;    // 剩余数量                               │   │
│  │     OrderSide side;       // BUY/SELL                               │   │
│  │     long accountId;                                                │   │
│  │     OrderStatus status;   // PENDING/PARTIAL_FILLED/FILLED/CANCEL │   │
│  │     long createTime;                                               │   │
│  │     long updateTime;                                               │   │
│  │ }                                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  OrderStatus 状态说明:                                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ PENDING:         新订单，未成交                                     │   │
│  │ PARTIAL_FILLED:  部分成交                                           │   │
│  │ FILLED:          全部成交                                           │   │
│  │ CANCEL:          已撤单                                             │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【恢复流程】                                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ JVM 重启后:                                                          │   │
│  │                                                                     │   │
│  │ 1. 加载 orderbook.dat                                               │   │
│  │ 2. 按 symbol 分组，重建 OrderBook                                   │   │
│  │                                                                     │   │
│  │ for (OrderState order : orderbookMap.values()) {                    │   │
│  │     if (order.getStatus() == PENDING ||                             │   │
│  │         order.getStatus() == PARTIAL_FILLED) {                      │   │
│  │                                                                     │   │
│  │         String symbol = order.getSymbol();                          │   │
│  │         OrderBook book = orderBooks.computeIfAbsent(                │   │
│  │             symbol, k -> new OrderBook(k)                           │   │
│  │         );                                                          │   │
│  │         book.restoreOrder(order);                                   │   │
│  │     }                                                               │   │
│  │ }                                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【撮合流程更新】                                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ MatchingEngine 处理订单时:                                           │   │
│  │                                                                     │   │
│  │ 1. 新订单:                                                           │   │
│  │    - 写入 orderbook.dat (PENDING 状态)                              │   │
│  │    - 加入内存 OrderBook                                              │   │
│  │    - 执行撮合                                                        │   │
│  │    - 更新 remainingQty (compute 原子更新)                            │   │
│  │                                                                     │   │
│  │ 2. 撤单:                                                             │   │
│  │    - 从内存 OrderBook 移除                                           │   │
│  │    - 更新 orderbook.dat 状态为 CANCEL                               │   │
│  │    - 生成 CancelEvent 到 Trade Queue                                │   │
│  │                                                                     │   │
│  │ 3. 成交:                                                             │   │
│  │    - 更新 remainingQty                                               │   │
│  │    - 如果 remainingQty == 0，状态改为 FILLED                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  【性能影响】                                                                │
│  - Chronicle Map 写入延迟 < 1μs                                            │
│  - 每笔订单增加 ~2μs 开销                                                   │
│  - 总延迟仍 < 15ms                                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 16.5 Chronicle Map 存储结构汇总 (V2.1)

| 文件 | Key | Value | 说明 |
|------|-----|-------|------|
| **accounts.dat** | accountId (Long) | AccountState | 账户状态 |
| **positions.dat** | (accountId<<32)|symbolCode | PositionState | 持仓状态 |
| **orderbook.dat** | (symbolCode<<32)|orderId | OrderState | 订单簿状态 (新增) |
| **progress.dat** | "order_queue_index" 等 | ProgressState | 队列处理进度 |
| **processed_trades.dat** | tradeId (Long) | ProcessedTradeState | 已处理成交去重表 |

### 16.6 线程模型对比 (V2.0 vs V2.1)

| 项目 | V2.0 | V2.1 | 变化 |
|------|------|------|------|
| Order Queue | 独立 | 统一 (含 NEW/CANCEL) | 合并 |
| Cancel Queue | 独立 | 无 | 移除 |
| CancelProcessor Thread | 1 线程 | 无 | 移除 |
| ClearingWorker 分片 | 无 | 按 AccountId % N | 新增 |
| 多 Symbol 撮合 | 未说明 | 单线程，独立 OrderBook | 新增 |
| OrderBook 持久化 | 无 | orderbook.dat | 新增 |
| 清算线程数 | N+2 | N+1 | 减少 1 |

---

## 18. OpenAPI 设计

### 18.1 概述

为支持量化交易用户和做市商策略接入，系统提供 OpenAPI 接口，支持 WebSocket 行情订阅和 REST API 交易操作。

**设计原则**：
- 只读 Key 和交易 Key 分离，权限清晰
- HMAC-SHA256 签名认证，安全性高
- 与 Web 端共享相同的业务逻辑和数据源

### 18.2 API Key 类型

| Key 类型 | 权限范围 | 说明 |
|---------|---------|------|
| **只读 Key** | 行情订阅、账户查询、持仓查询、委托查询 | 仅查看，无交易权限 |
| **交易 Key** | 只读权限 + 下单、撤单 | 完整交易权限，可指定可交易品种 |

**交易 Key 品种限制**：
- 创建时可勾选：股票、期货、期权
- 仅允许在授权品种范围内下单
- 超出品种范围返回权限错误

### 18.3 HMAC 签名认证

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          HMAC 签名认证流程                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【请求签名流程】                                                            │
│                                                                             │
│  1. 构造签名字串:                                                            │
│     StringToSign = Method + "\n" + Path + "\n" + Timestamp + "\n" + BodyHash│
│                                                                             │
│     示例:                                                                    │
│     POST\n                                                                   │
│     /openapi/orders\n                                                        │
│     1716307200\n                                                             │
│     a1b2c3d4e5f6... (SHA256(Body) 的前32字符)                               │
│                                                                             │
│  2. 使用 Secret Key 进行 HMAC-SHA256 签名:                                   │
│     Signature = HMAC_SHA256(SecretKey, StringToSign)                        │
│                                                                             │
│  3. 请求 Header 携带:                                                       │
│     X-API-Key: ak_xxxxxxxxxxxx                                               │
│     X-Timestamp: 1716307200                                                 │
│     X-Signature: 签名结果 (Hex编码)                                          │
│                                                                             │
│  【服务端验证流程】                                                          │
│                                                                             │
│  1. 检查 Timestamp 是否在 ±5 分钟内                                          │
│  2. 根据 API Key 查询 Secret Key                                            │
│  3. 重新计算签名并比对                                                       │
│  4. 检查 API Key 状态和权限                                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 18.4 OpenAPI 接口定义

#### 18.4.1 REST API 接口

| 接口 | 方法 | 说明 | Key 类型 |
|------|------|------|---------|
| /openapi/account | GET | 账户信息 | 只读/交易 |
| /openapi/positions | GET | 持仓列表 | 只读/交易 |
| /openapi/orders | POST | 下单 | 交易 |
| /openapi/orders/{orderId} | DELETE | 撤单 | 交易 |
| /openapi/orders/active | GET | 当前委托 | 只读/交易 |

#### 18.4.2 接口详细定义

**账户信息接口**：
```
GET /openapi/account

请求 Header:
  X-API-Key: ak_xxxxxxxxxxxx
  X-Timestamp: 1716307200
  X-Signature: 签名结果

响应:
{
    "code": 0,
    "message": "success",
    "data": {
        "accountId": 10001,
        "totalAsset": 123456700,      // 总资产 (分)
        "available": 56789000,         // 可用资金 (分)
        "frozen": 100000,              // 冻结资金 (分)
        "margin": 45678900,            // 保证金 (分)
        "unrealizedPnl": 1234500       // 浮动盈亏 (分)
    }
}
```

**持仓列表接口**：
```
GET /openapi/positions

响应:
{
    "code": 0,
    "message": "success",
    "data": {
        "positions": [
            {
                "symbol": "600000",
                "name": "浦发银行",
                "quantity": 1000,         // 持仓数量
                "available": 1000,        // 可用数量
                "frozen": 0,              // 冻结数量
                "avgPrice": 1000,         // 成本价 (分)
                "currentPrice": 1050,     // 当前价 (分)
                "unrealizedPnl": 5000     // 浮动盈亏 (分)
            }
        ]
    }
}
```

**下单接口**：
```
POST /openapi/orders

请求 Body:
{
    "symbol": "600000",           // 标的代码
    "side": "buy",                 // 方向: buy/sell (股票)
                                  //       open_long/open_short/close_long/close_short (期货)
                                  //       call/put (期权)
    "orderType": "limit",          // 订单类型: limit/market/fak/fok
    "price": 1000,                 // 价格 (分)，市价单可不传
    "quantity": 100                // 数量
}

响应:
{
    "code": 0,
    "message": "success",
    "data": {
        "orderId": 100001,
        "symbol": "600000",
        "status": "pending",
        "createTime": 1716307200000
    }
}
```

**撤单接口**：
```
DELETE /openapi/orders/{orderId}

响应:
{
    "code": 0,
    "message": "success",
    "data": {
        "orderId": 100001,
        "status": "cancelled"
    }
}
```

**当前委托接口**：
```
GET /openapi/orders/active?symbol=600000&page=1&size=20

响应:
{
    "code": 0,
    "message": "success",
    "data": {
        "orders": [
            {
                "orderId": 100001,
                "symbol": "600000",
                "side": "buy",
                "orderType": "limit",
                "price": 1000,
                "quantity": 100,
                "filledQuantity": 50,
                "status": "partial_filled",
                "createTime": 1716307200000
            }
        ],
        "total": 5,
        "page": 1,
        "size": 20
    }
}
```

#### 18.4.3 WebSocket 接口

**连接端点**: `ws://host:port/openapi/ws?api_key=<api_key>&timestamp=<timestamp>&signature=<signature>`

**订阅消息**:
```json
{
    "type": "subscribe",
    "channel": "market_snapshot|kline|orderbook|order_update|trade_update|account_update|position_update",
    "symbols": ["600000"]
}
```

**推送消息类型**:
- `market_snapshot` - 行情快照推送 (只读/交易)
- `kline` - K线数据推送 (只读/交易)
- `orderbook` - 订单簿 Level 2 推送 (只读/交易)
- `order_update` - 订单状态推送 (交易)
- `trade_update` - 成交推送 (交易)
- `account_update` - 账户资金变动推送 (交易)
- `position_update` - 持仓变动推送 (交易)

**权限说明**:
| Channel | 只读 Key | 交易 Key |
|---------|---------|---------|
| market_snapshot | ✓ | ✓ |
| kline | ✓ | ✓ |
| orderbook | ✓ | ✓ |
| order_update | ✗ | ✓ |
| trade_update | ✗ | ✓ |
| account_update | ✗ | ✓ |
| position_update | ✗ | ✓ |

### 18.5 API Key 存储

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          API Key 存储结构                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【Chronicle Map: api_keys.dat】                                             │
│                                                                             │
│  Key: apiKey (String)                                                       │
│  Value: ApiKeyState {                                                       │
│      long apiKeyId;           // Key ID                                     │
│      long accountId;          // 所属账户ID                                  │
│      String apiKey;           // API Key (ak_xxxx)                          │
│      String secretKeyHash;    // Secret Key 的 SHA256 Hash                  │
│      String name;             // Key 名称                                    │
│      KeyType type;            // READ_ONLY / TRADING                         │
│      Set<String> symbols;     // 可交易品种: STOCK, FUTURES, OPTION          │
│      KeyStatus status;        // ACTIVE / DISABLED / DELETED               │
│      long createTime;         // 创建时间                                    │
│      long lastUsedTime;       // 最后使用时间                                 │
│  }                                                                          │
│                                                                             │
│  【H2 Database: api_key_history】                                            │
│                                                                             │
│  用于审计日志，记录 API Key 的创建、使用、删除历史                             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 18.6 API Key 管理接口 (Web 端)

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/apikeys | GET | 获取 API Key 列表 |
| /api/apikeys | POST | 创建新 API Key |
| /api/apikeys/{keyId} | DELETE | 删除 API Key |
| /api/apikeys/{keyId}/secret | GET | 查看 Secret Key (仅创建后首次) |

**创建 API Key 请求**:
```
POST /api/apikeys

请求 Body:
{
    "name": "策略机器人A",
    "type": "trading",            // readonly / trading
    "symbols": ["stock", "futures"]  // 交易Key时必填
}

响应:
{
    "code": 0,
    "message": "success",
    "data": {
        "apiKeyId": 1,
        "apiKey": "ak_a1b2c3d4e5f6g7h8",
        "secretKey": "sk_xxxxxxxxxxxxxxxxxxxxxx",  // 仅显示一次！
        "name": "策略机器人A",
        "type": "trading",
        "symbols": ["stock", "futures"],
        "createTime": 1716307200000
    }
}
```

**重要提示**: Secret Key 仅在创建时返回一次，后续无法再次查看，用户需妥善保存。

### 18.7 错误码定义

| 错误码 | 说明 |
|-------|------|
| 3001 | API Key 不存在 |
| 3002 | API Key 已禁用 |
| 3003 | 签名验证失败 |
| 3004 | Timestamp 过期 (超过 ±5 分钟) |
| 3005 | 权限不足 (只读 Key 尝试交易) |
| 3006 | 品种权限不足 |
| 3007 | API Key 已删除 |

### 18.8 前端新增页面

#### 18.8.1 总览页面 (Overview)

**路由**: `/overview`

**组件结构**:
```
OverviewPage.tsx
├── IndexBar.tsx           # 指数行情栏
├── TopGainers.tsx         # 上涨Top5
├── TopLosers.tsx          # 下跌Top5
├── TopVolume.tsx          # 成交量Top5
└── ExchangeRanking.tsx    # 各交易所品种涨幅排序
    ├── ShseRanking.tsx    # 上交所A股
    ├── SzseRanking.tsx    # 深交所A股
    ├── CffexRanking.tsx   # 中金所期货
    └── SseOptionRanking.tsx  # 上交所期权
```

**数据来源**: 新浪财经 WebSocket 实时推送

**功能**:
- 指数行情实时更新，点击跳转对应品种详情
- Top5 排行榜实时排序
- 各交易所品种涨幅倒排序，点击跳转详情

#### 18.8.2 个人中心页面 (Profile)

**路由**: `/profile`

**组件结构**:
```
ProfilePage.tsx
├── UserInfo.tsx            # 用户信息卡片
├── AccountStats.tsx        # 账户统计
└── ApiKeyManager.tsx       # API Key 管理
    ├── ApiKeyList.tsx      # Key 列表
    └── CreateApiKeyModal.tsx  # 创建弹窗
```

**功能**:
- 显示用户基本信息
- 显示账户统计 (总资产、API Key 数量、交易统计)
- API Key 创建、查看、删除
- 创建时选择 Key 类型和可交易品种

### 18.9 菜单结构更新

**原菜单**: `股票 | 期货 | 期权 | 自选`

**新菜单**: `总览 | 股票 | 期货 | 期权 | 自选 | 个人中心`

| 菜单 | 路由 | 说明 |
|------|------|------|
| 总览 | /overview | 市场全景视图 |
| 股票 | /stock | 股票交易页面 (原有) |
| 期货 | /futures | 期货交易页面 (原有) |
| 期权 | /option | 期权交易页面 (原有) |
| 自选 | /watchlist | 自选股页面 (原有) |
| 个人中心 | /profile | 用户信息 + API Key 管理 |

---

## 19. 新浪财经行情数据接口

### 19.1 概述

系统从新浪财经获取实时行情数据，包括股票、指数、期货、期权的实时报价、买卖盘、成交明细等。

**数据获取方式**：
- HTTP 轮询：获取快照数据，适合低频场景
- WebSocket 推送：获取实时数据，适合高频场景

### 19.2 HTTP 接口

#### 19.2.1 股票实时行情

**接口地址**：
```
https://hq.sinajs.cn/list={symbols}
```

**请求示例**：
```
https://hq.sinajs.cn/list=sh600000,sz000001,sh600519
```

**参数说明**：

| 参数 | 类型 | 说明 |
|------|------|------|
| symbols | String | 股票代码列表，逗号分隔，需带市场前缀 |

**市场前缀**：

| 前缀 | 市场 | 示例 |
|------|------|------|
| sh | 上交所 | sh600000 |
| sz | 深交所 | sz000001 |
| hk | 港股 | hk00700 |

**返回格式**：
```javascript
var hq_str_sh600000="浦发银行,10.01,9.98,10.05,9.90,9.90,10.05,12345678,123456789,1000,10.05,2000,10.06,3000,10.07,4000,10.08,5000,10.09,6000,10.10,7000,10.11,2026-05-19,10:30:00,00";
```

**字段解析**（以逗号分隔）：

| 序号 | 字段 | 说明 | 示例 |
|------|------|------|------|
| 0 | name | 股票名称 | 浦发银行 |
| 1 | open | 今日开盘价 | 10.01 |
| 2 | pre_close | 昨日收盘价 | 9.98 |
| 3 | current | 当前价格 | 10.05 |
| 4 | high | 今日最高价 | 10.05 |
| 5 | low | 今日最低价 | 9.90 |
| 6 | bid | 买一价 | 10.05 |
| 7 | ask | 卖一价 | 10.06 |
| 8 | volume | 成交量（股） | 12345678 |
| 9 | amount | 成交额（元） | 123456789 |
| 10 | bid1_volume | 买一量 | 1000 |
| 11 | bid1_price | 买一价 | 10.05 |
| 12 | bid2_volume | 买二量 | 2000 |
| 13 | bid2_price | 买二价 | 10.06 |
| 14 | bid3_volume | 买三量 | 3000 |
| 15 | bid3_price | 买三价 | 10.07 |
| 16 | bid4_volume | 买四量 | 4000 |
| 17 | bid4_price | 买四价 | 10.08 |
| 18 | bid5_volume | 买五量 | 5000 |
| 19 | bid5_price | 买五价 | 10.09 |
| 20 | ask1_volume | 卖一量 | 6000 |
| 21 | ask1_price | 卖一价 | 10.10 |
| 22 | ask2_volume | 卖二量 | 7000 |
| 23 | ask2_price | 卖二价 | 10.11 |
| 24 | date | 日期 | 2026-05-19 |
| 25 | time | 时间 | 10:30:00 |
| 26 | status | 状态 | 00 (停牌=03) |

#### 19.2.2 指数行情

**接口地址**：
```
https://hq.sinajs.cn/list={index_codes}
```

**常用指数代码**：

| 代码 | 名称 | 前缀 |
|------|------|------|
| 000001 | 上证指数 | sh |
| 399001 | 深证成指 | sz |
| 399006 | 创业板指 | sz |
| 000688 | 科创50 | sh |
| 000300 | 沪深300 | sh |

**请求示例**：
```
https://hq.sinajs.cn/list=sh000001,sz399001,sz399006,sh000688,sh000300
```

**返回格式**：与股票行情相同

#### 19.2.3 期货实时行情

**接口地址**：
```
https://hq.sinajs.cn/list={futures_codes}
```

**期货代码格式**：`nf_{合约代码}`

**常用期货代码**：

| 代码 | 名称 |
|------|------|
| nf_IF2406 | 沪深300期货2406 |
| nf_IC2406 | 中证500期货2406 |
| nf_IH2406 | 上证50期货2406 |
| nf_IM2406 | 中证1000期货2406 |

**请求示例**：
```
https://hq.sinajs.cn/list=nf_IF2406,nf_IC2406,nf_IH2406
```

**返回格式**：
```javascript
var hq_str_nf_IF2406="3850.0,3848.0,3852.0,3845.0,3850.0,3849.0,12345,1234567890,3850.0,100,3851.0,200,...";
```

**字段解析**：

| 序号 | 字段 | 说明 |
|------|------|------|
| 0 | name | 合约名称 |
| 1 | open | 开盘价 |
| 2 | pre_close | 昨结算价 |
| 3 | current | 当前价 |
| 4 | high | 最高价 |
| 5 | low | 最低价 |
| 6 | volume | 成交量（手） |
| 7 | amount | 成交额 |
| 8-17 | bid/ask | 五档买卖盘 |
| ... | settlement | 结算价 |

#### 19.2.4 期权实时行情

**接口地址**：
```
https://hq.sinajs.cn/list={option_codes}
```

**期权代码格式**：`op_{期权代码}`

**50ETF期权代码示例**：

| 代码 | 名称 |
|------|------|
| op_10003720 | 50ETF购6月2500 |
| op_10003721 | 50ETF沽6月2500 |

**请求示例**：
```
https://hq.sinajs.cn/list=op_10003720,op_10003721
```

#### 19.2.5 涨跌排行

**接口地址**：
```
https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData?page={page}&num={num}&sort={sort}&asc={asc}&node={node}&symbol={symbol}
```

**参数说明**：

| 参数 | 类型 | 说明 |
|------|------|------|
| page | int | 页码，从1开始 |
| num | int | 每页数量，最大80 |
| sort | string | 排序字段：changepercent(涨跌幅)、turnoverrate(换手率)、volume(成交量) |
| asc | int | 排序方向：1升序，0降序 |
| node | string | 市场：hs_a(沪深A股)、sh_a(上交所A股)、sz_a(深交所A股) |
| symbol | string | 资产类型：股票为空 |

**请求示例（涨幅榜Top10）**：
```
https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData?page=1&num=10&sort=changepercent&asc=0&node=hs_a
```

**返回格式**：
```json
[
    {
        "symbol": "sh600519",
        "code": "600519",
        "name": "贵州茅台",
        "trade": "1856.00",
        "pricechange": "168.73",
        "changepercent": "10.00",
        "buy": "1856.00",
        "sell": "1856.01",
        "settlement": "",
        "open": "1700.00",
        "high": "1856.00",
        "low": "1687.27",
        "volume": 12345678,
        "amount": 123456789012.34,
        "ticktime": "10:30:00",
        "per": 35.67,
        "pb": 12.34,
        "mktcap": 2345678901234.56,
        "nmc": 2345678901234.56,
        "turnoverratio": 0.56
    }
]
```

**字段说明**：

| 字段 | 说明 |
|------|------|
| symbol | 带市场前缀的代码 |
| code | 股票代码 |
| name | 股票名称 |
| trade | 当前价 |
| pricechange | 涨跌额 |
| changepercent | 涨跌幅(%) |
| buy | 买一价 |
| sell | 卖一价 |
| open | 开盘价 |
| high | 最高价 |
| low | 最低价 |
| volume | 成交量(股) |
| amount | 成交额(元) |
| per | 市盈率 |
| pb | 市净率 |
| mktcap | 总市值 |
| nmc | 流通市值 |
| turnoverratio | 换手率(%) |

### 19.3 WebSocket 接口

#### 19.3.1 连接地址

```
wss://hq.sinajs.cn/ws?list={symbols}
```

**注意**：新浪财经 WebSocket 接口需要特定的握手协议，建议使用 HTTP 轮询方式获取数据，然后在服务端通过 WebSocket 推送给前端。

#### 19.3.2 推荐架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          行情数据获取架构                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【数据源】新浪财经 HTTP API                                                 │
│       │                                                                     │
│       │ HTTP 轮询 (每1-3秒)                                                  │
│       ▼                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ MarketDataWorker Thread (单线程)                                     │   │
│  │                                                                     │   │
│  │ 职责:                                                                │   │
│  │ 1. 定时轮询新浪财经接口                                              │   │
│  │ 2. 解析返回数据                                                      │   │
│  │ 3. 更新 Chronicle Map (行情缓存)                                    │   │
│  │ 4. 推送到内部消息队列                                                │   │
│  │                                                                     │   │
│  │ 轮询策略:                                                            │   │
│  │ - 交易时段: 每1秒轮询                                                │   │
│  │ - 非交易时段: 每30秒轮询                                             │   │
│  │ - 自选股: 每1秒轮询                                                  │   │
│  │ - 全市场: 每3秒轮询                                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│       │                                                                     │
│       ▼ Chronicle Queue (行情事件)                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ MarketDataPublisher Thread (单线程)                                  │   │
│  │                                                                     │   │
│  │ 职责:                                                                │   │
│  │ 1. 从 Chronicle Queue 读取行情事件                                  │   │
│  │ 2. 通过 WebSocket 推送给所有订阅的客户端                              │   │
│  │ 3. 支持频道订阅: market_snapshot, kline, orderbook                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│       │                                                                     │
│       ▼ WebSocket 推送                                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 前端客户端 / OpenAPI 客户端                                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 19.4 数据存储

#### 19.4.1 Chronicle Map 存储

**文件**: `market_data.dat`

| Key | Value | 说明 |
|-----|-------|------|
| symbol (String) | MarketDataState | 实时行情快照 |

**MarketDataState 结构**：
```java
public interface MarketDataState {
    String getSymbol();           // 代码
    void setSymbol(String symbol);

    String getName();             // 名称
    void setName(String name);

    long getCurrentPrice();        // 当前价 (分)
    void setCurrentPrice(long price);

    long getOpenPrice();          // 开盘价 (分)
    void setOpenPrice(long price);

    long getPreClose();           // 昨收价 (分)
    void setPreClose(long price);

    long getHighPrice();          // 最高价 (分)
    void setHighPrice(long price);

    long getLowPrice();           // 最低价 (分)
    void setLowPrice(long price);

    long getVolume();             // 成交量
    void setVolume(long volume);

    long getAmount();             // 成交额 (分)
    void setAmount(long amount);

    long getUpdateTime();         // 更新时间戳
    void setUpdateTime(long time);

    // 五档买卖盘
    long[] getBidPrices();        // 买1-5价 (分)
    long[] getBidVolumes();      // 买1-5量
    long[] getAskPrices();        // 卖1-5价 (分)
    long[] getAskVolumes();      // 卖1-5量
}
```

### 19.5 数据解析服务

#### 19.5.1 解析器实现

```java
/**
 * 新浪财经行情解析器
 */
public class SinaMarketDataParser {

    /**
     * 解析股票行情
     * 输入: var hq_str_sh600000="浦发银行,10.01,9.98,10.05,...";
     */
    public static MarketDataState parseStockQuote(String symbol, String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }

        String[] fields = data.split(",");
        if (fields.length < 27) {
            return null;
        }

        MarketDataState state = new MarketDataState();
        state.setSymbol(symbol);
        state.setName(fields[0]);
        state.setOpenPrice(parsePrice(fields[1]));
        state.setPreClose(parsePrice(fields[2]));
        state.setCurrentPrice(parsePrice(fields[3]));
        state.setHighPrice(parsePrice(fields[4]));
        state.setLowPrice(parsePrice(fields[5]));

        // 成交量（股）
        state.setVolume(Long.parseLong(fields[8]));
        // 成交额（元）-> 分
        state.setAmount(parseAmount(fields[9]));

        // 五档买卖盘
        state.setBidVolumes(new long[]{
            Long.parseLong(fields[10]),
            Long.parseLong(fields[12]),
            Long.parseLong(fields[14]),
            Long.parseLong(fields[16]),
            Long.parseLong(fields[18])
        });
        state.setBidPrices(new long[]{
            parsePrice(fields[11]),
            parsePrice(fields[13]),
            parsePrice(fields[15]),
            parsePrice(fields[17]),
            parsePrice(fields[19])
        });
        state.setAskVolumes(new long[]{
            Long.parseLong(fields[20]),
            Long.parseLong(fields[22]),
            Long.parseLong(fields[24]),
            Long.parseLong(fields[26]),
            Long.parseLong(fields[28])
        });
        state.setAskPrices(new long[]{
            parsePrice(fields[21]),
            parsePrice(fields[23]),
            parsePrice(fields[25]),
            parsePrice(fields[27]),
            parsePrice(fields[29])
        });

        state.setUpdateTime(System.currentTimeMillis());
        return state;
    }

    /**
     * 解析价格（元 -> 分）
     */
    private static long parsePrice(String priceStr) {
        try {
            double price = Double.parseDouble(priceStr);
            return Math.round(price * 100);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 解析成交额（元 -> 分）
     */
    private static long parseAmount(String amountStr) {
        try {
            double amount = Double.parseDouble(amountStr);
            return Math.round(amount * 100);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
```

#### 19.5.2 行情获取服务

```java
/**
 * 新浪财经行情获取服务
 */
public class SinaMarketDataService {

    private static final String QUOTE_URL = "https://hq.sinajs.cn/list=";
    private static final String RANK_URL = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData";

    private final OkHttpClient httpClient;
    private final ChronicleMap<String, MarketDataState> marketDataMap;
    private final ChronicleQueue marketDataQueue;

    /**
     * 批量获取股票行情
     * @param symbols 股票代码列表（带市场前缀）
     */
    public Map<String, MarketDataState> fetchQuotes(List<String> symbols) {
        Map<String, MarketDataState> result = new HashMap<>();

        // 新浪接口每次最多请求800个股票
        int batchSize = 800;
        for (int i = 0; i < symbols.size(); i += batchSize) {
            List<String> batch = symbols.subList(i, Math.min(i + batchSize, symbols.size()));
            String url = QUOTE_URL + String.join(",", batch);

            try {
                Request request = new Request.Builder()
                    .url(url)
                    .header("Referer", "https://finance.sina.com.cn")
                    .build();

                Response response = httpClient.newCall(request).execute();
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    parseAndStoreQuotes(body, result);
                }
            } catch (Exception e) {
                log.error("Failed to fetch quotes for batch starting at {}", i, e);
            }
        }

        return result;
    }

    /**
     * 获取涨幅榜
     * @param node 市场: hs_a, sh_a, sz_a
     * @param num 数量
     */
    public List<RankItem> fetchTopGainers(String node, int num) {
        return fetchRankList(node, "changepercent", false, num);
    }

    /**
     * 获取跌幅榜
     */
    public List<RankItem> fetchTopLosers(String node, int num) {
        return fetchRankList(node, "changepercent", true, num);
    }

    /**
     * 获取成交额榜
     */
    public List<RankItem> fetchTopVolume(String node, int num) {
        return fetchRankList(node, "amount", false, num);
    }

    /**
     * 获取排行榜数据
     */
    private List<RankItem> fetchRankList(String node, String sort, boolean asc, int num) {
        List<RankItem> result = new ArrayList<>();
        String url = String.format("%s?page=1&num=%d&sort=%s&asc=%d&node=%s",
            RANK_URL, num, sort, asc ? 1 : 0, node);

        try {
            Request request = new Request.Builder()
                .url(url)
                .header("Referer", "https://vip.stock.finance.sina.com.cn")
                .build();

            Response response = httpClient.newCall(request).execute();
            if (response.isSuccessful()) {
                String body = response.body().string();
                result = parseRankList(body);
            }
        } catch (Exception e) {
            log.error("Failed to fetch rank list", e);
        }

        return result;
    }

    /**
     * 解析并存储行情数据
     */
    private void parseAndStoreQuotes(String body, Map<String, MarketDataState> result) {
        // 解析 var hq_str_sh600000="...";
        Pattern pattern = Pattern.compile("var hq_str_(\\w+)=\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(body);

        while (matcher.find()) {
            String symbol = matcher.group(1);
            String data = matcher.group(2);

            MarketDataState state = SinaMarketDataParser.parseStockQuote(symbol, data);
            if (state != null) {
                result.put(symbol, state);

                // 更新 Chronicle Map
                marketDataMap.put(symbol, state);

                // 写入 Chronicle Queue（推送事件）
                publishMarketDataEvent(state);
            }
        }
    }
}
```

### 19.6 轮询策略

#### 19.6.1 轮询频率

| 数据类型 | 交易时段 | 非交易时段 |
|---------|---------|-----------|
| 自选股行情 | 1秒 | 30秒 |
| 指数行情 | 1秒 | 30秒 |
| 全市场行情 | 3秒 | 60秒 |
| 涨跌排行 | 3秒 | 60秒 |
| 期货期权 | 1秒 | 30秒 |

#### 19.6.2 交易时段定义

| 市场 | 交易时段 |
|------|---------|
| A股 | 09:30-11:30, 13:00-15:00 |
| 期货 | 09:00-11:30, 13:30-15:00 |
| 期权 | 09:30-11:30, 13:00-15:00 |

### 19.7 错误处理

| 错误类型 | 处理策略 |
|---------|---------|
| 网络超时 | 重试3次，间隔1秒 |
| 接口返回空 | 记录日志，保持上次数据 |
| 接口返回错误 | 记录日志，降级到备用数据源 |
| 解析失败 | 记录日志，跳过该条数据 |

### 19.8 数据推送格式

#### 19.8.1 行情快照推送

```json
{
    "type": "market_snapshot",
    "data": {
        "symbol": "600000",
        "name": "浦发银行",
        "currentPrice": 1005,
        "preClose": 998,
        "change": 7,
        "changePercent": 0.70,
        "open": 1001,
        "high": 1005,
        "low": 990,
        "volume": 12345678,
        "amount": 12345678900,
        "bid1": [1005, 1000],
        "bid2": [1004, 2000],
        "bid3": [1003, 3000],
        "bid4": [1002, 4000],
        "bid5": [1001, 5000],
        "ask1": [1006, 6000],
        "ask2": [1007, 7000],
        "ask3": [1008, 8000],
        "ask4": [1009, 9000],
        "ask5": [1010, 10000],
        "time": "10:30:00",
        "timestamp": 1716307200000
    }
}
```

#### 19.8.2 涨跌榜推送

```json
{
    "type": "top_gainers",
    "data": {
        "list": [
            {
                "rank": 1,
                "symbol": "600519",
                "name": "贵州茅台",
                "currentPrice": 185600,
                "changePercent": 10.00,
                "volume": 12345678,
                "amount": 125600000000
            }
        ],
        "updateTime": 1716307200000
    }
}
```

---

## 20. 参考文档

- [线程工作模型数据流图](thread-workflow.html)
- [HTTP REST API 接口定义](http-api-definition.html)
- [前端布局设计](frontend-layout.html)
- [系统架构设计 Skill](../.claude/skills/system-architecture.md)
- 新浪财经行情接口: https://hq.sinajs.cn
- 新浪财经排行榜接口: https://vip.stock.finance.sina.com.cn


