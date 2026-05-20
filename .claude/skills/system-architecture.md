# 系统架构设计 Skill

当用户需要设计整体交易系统架构时使用此skill。

## 触发场景
- 系统整体架构设计
- 模块划分和依赖
- 技术选型决策
- 容量规划

## 核心原则

### 1. 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        客户端层                              │
│  Web交易终端 | 移动APP | API网关 | 行情推送                  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                        接入层                                │
│  WebSocket Gateway | REST API | FIX Gateway | 行情分发      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      业务处理层                              │
├──────────────┬──────────────┬──────────────┬───────────────┤
│  订单网关     │   撮合引擎    │   清算服务    │   风控服务    │
│  Order GW    │  Matching    │  Clearing    │   Risk Ctrl   │
└──────────────┴──────────────┴──────────────┴───────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      数据层                                  │
├──────────────┬──────────────┬──────────────┬───────────────┤
│ Chronicle Q  │   H2 DB      │  Redis Cache │  Kafka MQ     │
│ (主链路实时) │  (历史查询)  │  (缓存)      │  (异步事件)   │
└──────────────┴──────────────┴──────────────┴───────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      监控运维层                              │
│  Prometheus | Grafana | ELK | AlertManager | Jaeger        │
└─────────────────────────────────────────────────────────────┘
```

### 2. 核心模块设计

#### 订单网关 (Order Gateway)
```java
public class OrderGateway {
    // 职责: 接收订单请求，前置校验，路由到撮合引擎
    private final ChronicleQueue orderQueue;
    private final RiskManager riskManager;

    public OrderResult submitOrder(OrderRequest request) {
        // 1. 参数校验
        // 2. 风控检查
        // 3. 生成订单ID
        // 4. 写入Chronicle Queue
        // 5. 返回订单ID
    }
}
```

#### 撮合引擎 (Matching Engine)
```java
public class MatchingEngine {
    // 职责: 订单撮合，维护订单簿，生成成交
    private final Map<String, OrderBook> orderBooks;
    private final ChronicleQueue tradeQueue;

    public void start() {
        // 从Chronicle读取订单
        // 执行撮合逻辑
        // 输出成交事件
    }
}
```

#### 清算服务 (Clearing Service)
```java
public class ClearingService {
    // 职责: 成交清算，解冻+扣款，持仓更新
    // 重要: 使用 compute 原子操作，多线程安全

    private final ChronicleMap<Long, AccountState> accountMap;
    private final ChronicleMap<Long, PositionState> positionMap;

    public void processTrade(TradeEvent trade) {
        long frozenAmount = trade.getPrice() * trade.getQuantity();

        // 1. 买方: 解冻 + 扣款 (compute 原子操作)
        accountMap.compute(trade.getBuyAccountId(), (key, buyer) -> {
            if (buyer == null) return buyer;
            buyer.setFrozen(buyer.getFrozen() - frozenAmount);
            buyer.setBalance(buyer.getBalance() - frozenAmount - trade.getBuyerCommission());
            buyer.setAvailable(buyer.getAvailable() - trade.getBuyerCommission());
            return buyer;
        });

        // 2. 卖方: 解冻持仓 + 增加资金 (compute 原子操作)
        accountMap.compute(trade.getSellAccountId(), (key, seller) -> {
            if (seller == null) return seller;
            long sellAmount = trade.getPrice() * trade.getQuantity();
            seller.setBalance(seller.getBalance() + sellAmount - trade.getSellerCommission() - trade.getStampDuty());
            seller.setAvailable(seller.getAvailable() + sellAmount - trade.getSellerCommission() - trade.getStampDuty());
            return seller;
        });

        // 3. 更新持仓 (compute 原子操作)
        // 4. 推送WebSocket
    }
}
```

#### 风控服务 (Risk Control)
```java
public class RiskControlService {
    // 职责: 实时风控检查，异常交易监控
    // 重要: 读取 Chronicle Map (多读取者安全)

    private final ChronicleMap<Long, AccountState> accountMap;

    public boolean checkFundAvailable(long accountId, long amount) {
        AccountState account = accountMap.get(accountId);
        return account != null && account.getAvailable() >= amount;
    }
}
```

#### 订单网关 (compute 原子冻结)
```java
public class OrderGateway {
    // 职责: 接收订单，风控检查，compute 原子冻结

    private final ChronicleMap<Long, AccountState> accountMap;
    private final ChronicleMap<Long, PositionState> positionMap;

    // compute 原子冻结资金
    public boolean freezeFundSync(long accountId, long amount) {
        AtomicBoolean success = new AtomicBoolean(false);

        accountMap.compute(accountId, (key, account) -> {
            if (account == null || account.getAvailable() < amount) {
                success.set(false);
                return account;  // 不修改
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
}
```

### 3. 账户/持仓存储架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    账户/持仓存储架构                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Chronicle Map 本身就是内存映射文件，读写性能极高，无需额外内存层            │
│                                                                             │
│  【Chronicle Map 配置】                                                      │
│  accounts.dat:  Key=accountId, Value=AccountState                          │
│  positions.dat: Key=(accountId<<32)|symbolCode, Value=PositionState         │
│  • 内存映射: 读写操作直接在内存中进行                                        │
│  • 磁盘持久化: createPersistedTo() 自动同步                                 │
│  • 进程崩溃恢复: 重启后自动从磁盘恢复                                        │
│                                                                             │
│  【并发访问模式 - compute 原子操作】                                         │
│                                                                             │
│  订单网关 (compute 原子冻结):                                                │
│    • 使用 compute() 方法原子冻结资金/持仓                                    │
│    • Entry 级别锁，不同 key 可并发执行                                       │
│    • 同 key 串行化，避免数据覆盖                                             │
│                                                                             │
│  清算服务 (compute 原子更新):                                                │
│    • 使用 compute() 方法原子更新账户/持仓                                    │
│    • 多线程并发处理成交，性能更高                                            │
│                                                                             │
│  风控服务 (读取):                                                           │
│    • Chronicle Map 支持多读取者                                             │
│    • accountMap.get(accountId) 直接读取                                     │
│    • O(1) 时间复杂度                                                        │
│                                                                             │
│  【数据流】                                                                  │
│  订单请求: OrderGW → 风控(读) → compute原子冻结 → 写入OrderQueue             │
│  成交清算: Trade Q → ClearingService → compute原子更新 → WebSocket          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4. 数据流设计

```
订单流 (主链路不走数据库):
用户 → OrderGW → 风控检查 → compute原子冻结 → Chronicle Q → Matching Engine
         │          │           │                              │
         │     读ChronicleMap  compute原子操作                  │
         │     (检查资金)      (Entry级别锁)                      │
         │                                                    │
         └────────────────────────────────────────────────────┘
                                                    ↓
                                              Trade Event
                                                    ↓
资金流:                                        ClearingService
Trade Event → Chronicle Q → ClearingService → compute原子更新 → WebSocket
                                              (多线程并发)
                                                    ↓
                                              H2异步持久化 (仅历史查询)

前端数据流:
WebSocket接收行情/订单状态 → Zustand更新状态 → 组件自动渲染
用户操作 → REST API下单 → 订单状态WebSocket推送
```

### 4. 技术选型

| 组件 | 技术选型 | 说明 |
|------|---------|------|
| 消息队列 | Chronicle Queue | 低延迟，主链路，实时持久化 |
| 内存存储 | Chronicle Map | 账户/持仓，实时持久化到磁盘 |
| 数据库 | H2 | 磁盘模式，仅历史查询，不参与主链路 |
| GC策略 | ZGC (Generational) | 超低延迟 <1ms，大堆友好 |
| 缓存 | Redis | 行情缓存，会话管理 |
| 消息中间件 | Kafka | 异步事件，日志收集 |
| 监控 | Prometheus + Grafana | 指标监控 |
| 日志 | ELK Stack | 日志分析 |
| 链路追踪 | Jaeger | 分布式追踪 |
| 前端框架 | React 18 + TypeScript | 组件化开发 |
| 前端图表 | TradingView | 专业K线图 |
| 前端UI | Ant Design 5.x | 企业级UI组件 |
| 前端状态 | Zustand | 轻量级状态管理 |

### 5. 性能目标

| 指标 | 目标值 |
|------|--------|
| 订单处理延迟 | < 100μs (P99) |
| 撮合延迟 | < 10μs |
| 系统吞吐量 | > 100,000 TPS |
| 数据持久化延迟 | < 10ms |
| 系统可用性 | 99.99% |

### 6. 容量规划

```java
// 单机容量估算
public class CapacityPlanning {
    // 订单量: 100万笔/天
    // 成交量: 200万笔/天
    // 账户数: 10万
    // 持仓数: 100万

    // Chronicle Queue
    long orderSize = 200; // bytes
    long dailyOrders = 1_000_000;
    long queueSize = orderSize * dailyOrders; // 200MB/天

    // H2 Database
    long tradeRecordSize = 500; // bytes
    long dailyTrades = 2_000_000;
    long dbSize = tradeRecordSize * dailyTrades; // 1GB/天
}
```

### 7. 高可用设计

```java
// 主备切换
public class FailoverManager {
    // 1. 主节点故障检测
    // 2. 备节点自动切换
    // 3. 数据同步机制
    // 4. 客户端重连
}

// 数据备份
public class BackupService {
    // 1. Chronicle Queue 复制
    // 2. H2 数据库备份
    // 3. 配置文件同步
}
```

## 输出要求
- 完整的架构设计文档
- 模块接口定义
- 技术选型说明
- 容量规划报告
- 高可用方案
