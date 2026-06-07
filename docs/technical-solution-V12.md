# A股交易系统技术方案 V12

> 版本: v12.5 | 日期: 2026-06-07 | 架构师: Trading System Architect
> 状态: **设计完成，可落地开发**
> 前身: V11.10（两轮审查72问题）→ V12.0（修复14问题）→ V12.1（落地前P0审查修复4项）→ V12.2 → V12.3（多维度审查修复40项：7 P0 + 15 P1 + 18 P2）→ V12.4（审查修复18项）→ **V12.5（前端设计一致性修复6项）**

---

## 核心设计要点

> 术语：**RPO**（Recovery Point Objective，恢复点目标）| **RTO**（Recovery Time Objective，恢复时间目标）| **CQ**（Chronicle Queue）

| 要点 | 说明 |
|------|------|
| **RPO = 0** | 所有CQ同步刷盘（writeMode=SYNC），account_output CQ为ASYNC（非RPO关键），快照原子写入（tmp→rename），MD5校验，独立快照+增量恢复，tmp残留文件检测 |
| **不重复处理** | 三层幂等模型：HTTP层（idempotencyKey）→ MatchingEngine（processed_order_ids）→ AccountWorker（processed_trade_ids 兜底）；ClearingWorker恢复时增加TRADE幂等检查；恢复期间幂等集合动态扩容 |
| **单线程串行** | AccountWorker / MatchingEngine / ClearingWorker 各单线程，内存操作无需加锁 |
| **数据存储** | JVM内存为主存储（ConcurrentHashMap），Chronicle Map仅用于快照持久化 |
| **批量处理** | AccountWorker批量50条，ClearingWorker批量100条 |
| **恢复机制** | 各Worker独立快照，四阶段顺序恢复，replay_mode防重复写入 |

**文件命名约定**：
- `.cq4`: Chronicle Queue事件队列后缀
- `.dat`: Chronicle Map快照文件后缀
- `.checksum`: MD5校验和文件后缀（32字节十六进制）

---

## 核心配置常量

| 配置项 | 值 | 说明 |
|--------|-----|------|
| ACCOUNT_BATCH_SIZE | 50 | AccountWorker每批次最大事件数 |
| CLEARING_BATCH_SIZE | 100 | ClearingWorker每批次最大事件数 |
| TIMEOUT_MS | 1 | 批量读取超时（毫秒） |
| SNAPSHOT_INTERVAL_S | 60 | 快照间隔（秒） |
| HTTP_TIMEOUT_MS | 5000 | HTTP请求超时（毫秒） |
| WS_IDLE_TIMEOUT_MS | 300000 | WebSocket空闲超时（5分钟） |
| WS_HEARTBEAT_MS | 30000 | WebSocket心跳间隔（30秒） |
| ORDER_TPS_LIMIT | 100 | 下单TPS限制 |
| QUERY_QPS_LIMIT | 1000 | 查询QPS限制 |
| IDEMPOTENT_SET_MAX_SIZE | 10000 | 幂等集合内存最大容量（正常模式）；恢复模式动态扩容至实际需要大小 |
| SNAPSHOT_FORCE_TIMEOUT_MS | 30000 | 强制快照超时（毫秒） |
| MAX_RETRY_COUNT | 3 | CQ写入失败最大重试次数 |
| ARCHIVE_RETENTION_DAYS | 30 | CQ归档保留天数 |

---

## 目录

1. [需求与约束](#1-需求与约束)
2. [系统架构](#2-系统架构)
3. [数据模型](#3-数据模型)
4. [核心流程](#4-核心流程)
5. [并发控制与幂等性](#5-并发控制与幂等性)
6. [持久化、快照与恢复](#6-持久化快照与恢复)
7. [行情服务](#7-行情服务)
8. [API设计](#8-api设计)
9. [前端设计](#9-前端设计)
10. [性能分析](#10-性能分析)
11. [部署与运维](#11-部署与运维)
12. [错误码定义](#12-错误码定义)
13. [业务规则](#13-业务规则)
14. [生命周期管理](#14-生命周期管理)
15. [设计决策记录](#15-设计决策记录)

**附录**：A. 配置参数表 | B. 技术栈 | C. H2 数据库 Schema

---

## 1. 需求与约束

### 1.1 系统定位

| 定位 | 说明 |
|------|------|
| **系统性质** | A股模拟交易系统，非真实交易 |
| **资金来源** | 入金手工上账（管理员操作） |
| **交易逻辑** | 完整的交易、清结算逻辑，与真实交易一致 |
| **撮合模式** | 内部用户之间撮合（含做市商策略用户） |
| **行情来源** | 五档行情来自撮合引擎订单簿，新浪行情仅展示 |
| **做市商** | 通过OpenAPI接入，做市商系统独立JVM（后续独立设计） |

### 1.2 业务需求

| 需求项 | 需求描述 |
|--------|---------|
| **用户体系** | 用户注册自动创建账户，余额为0，管理员手动入金 |
| **交易品种** | A股（主板/创业板/科创板）；IF股指期货、50ETF期权数据模型预留，首版不启用 |
| **账户体系** | 多用户独立账户，支持1000+并发用户 |
| **撮合模式** | 内部撮合（含做市商策略用户） |
| **交易时段** | A股交易时段（无夜盘），仅支持连续竞价 |
| **前端界面** | TradingView K线图、订单簿、下单面板 |
| **客户端支持** | 仅Web端 |

### 1.3 性能目标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| **吞吐量** | ≥ 100,000 TPS | 订单+撤单总量（批量处理） |
| **HTTP返回延迟** | ≤ 5ms (P99) | 下单到返回orderId |
| **端到端延迟** | ≤ 15ms (P99) | 下单到收到成交回报 |
| **清算吞吐** | ≥ 5,000 TPS | 成交清算能力（假设5%成交率） |
| **可用性** | 99.99% | 年度可用性 |

### 1.4 技术约束

| 约束项 | 约束内容 |
|--------|---------|
| **部署模式** | 单机单JVM，前后端一体化部署 |
| **Java版本** | Java 21 LTS |
| **Spring Boot** | 3.x + 虚拟线程（Project Loom） |
| **Chronicle版本** | 最新稳定版 |
| **服务器配置** | 阿里云 8核CPU / 24GB内存 / 500GB NVMe SSD |
| **SSD要求** | NVMe SSD，支持 ≥ 300K fsync/s（4个SYNC CQ同时写入场景） |
| **GC策略** | ZGC（Generational），暂停时间 < 1ms |

### 1.5 设计原则

| 原则 | 说明 |
|------|------|
| **RPO = 0** | 所有RPO关键CQ通过SYNC持久化（account_output为ASYNC），快照+增量恢复，重启后数据零丢失 |
| **数据一致性优先** | 单线程串行处理，保证账户/持仓不变式始终成立 |
| **不重复处理** | 三层幂等模型：HTTP idempotencyKey → MatchingEngine processed_order_ids → AccountWorker processed_trade_ids兜底；ClearingWorker恢复时TRADE幂等检查 |
| **简单可靠** | 无锁设计，避免并发复杂度；不引入分布式协调 |

---

## 2. 系统架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              单JVM进程                                       │
├─────────────────────────────────────────────────────────────────────────────┤
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
│  │   (NEW_ORDER / CANCEL_ORDER / SETTLEMENT / SNAPSHOT / PHASE_CHANGE / │   │
│  │    DEPOSIT / INSTRUMENT_INIT)                                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                AccountWorker (单线程，批量处理)                       │   │
│  │  • 冻结/解冻资金和持仓 • 结算转账 • 写入CQ + 唤醒HTTP                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│              ┌───────────────┴───────────────┐                             │
│              ▼                               ▼                             │
│  ┌─────────────────────┐        ┌─────────────────────┐                   │
│  │   account_output CQ │        │   matching_input CQ │                   │
│  │ (冻结/解冻/持仓变更) │        │ (下单/撤单/快照)    │                   │
│  └─────────────────────┘        └─────────────────────┘                   │
│              │                               │                             │
│              ▼                               ▼                             │
│  ┌─────────────────────┐        ┌─────────────────────────────────────┐   │
│  │ 用于WebSocket推送   │        │              MatchingEngine          │   │
│  │ (Netty非阻塞写入)   │        │        (单线程，批量处理)             │   │
│  └─────────────────────┘        │  • 更新OrderBook • 撮合 • 生成事件   │   │
│                                 │  • 五档行情 • K线聚合                │   │
│                                 └─────────────────────────────────────┘   │
│                                              │                             │
│                                              ▼                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     matching_output CQ                               │   │
│  │     (ORDER_UPDATE / TRADE / CANCEL_SUCCESS / CANCEL_REJECT)          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │              ClearingWorker (单线程，批量处理100条)                   │   │
│  │  • 更新订单状态 • 计算佣金/退款 • 输出SETTLEMENT/UNFREEZE事件         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     account_settlement CQ                            │   │
│  │              (SETTLEMENT事件 / UNFREEZE事件)                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                AccountWorker (处理SETTLEMENT/UNFREEZE)               │   │
│  │  • 结算转账 • 解冻资金/持仓 • WebSocket推送                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │              SnapshotWorker (定时，写入SNAPSHOT事件)                  │   │
│  │  • 写入SNAPSHOT到account_input/matching_input CQ                     │   │
│  │  • Worker处理SNAPSHOT时写快照CM → 原子rename + MD5校验                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

**CQ文件一览**：

| CQ名称 | 用途 | 事件类型 |
|--------|------|---------|
| `account_input.cq4` | AccountWorker输入 | NEW_ORDER / CANCEL_ORDER / SETTLEMENT / SNAPSHOT / PHASE_CHANGE / DEPOSIT / INSTRUMENT_INIT |
| `account_output.cq4` | AccountWorker输出（WebSocket推送用） | FREEZE / UNFREEZE / BALANCE_CHANGE / POSITION_CHANGE |
| `matching_input.cq4` | MatchingEngine输入 | NEW_ORDER / CANCEL_ORDER / SNAPSHOT / INSTRUMENT_INIT |
| `matching_output.cq4` | MatchingEngine输出 | ORDER_UPDATE / TRADE / CANCEL_SUCCESS / CANCEL_REJECT |
| `account_settlement.cq4` | ClearingWorker输出 | SETTLEMENT / UNFREEZE |

### 2.2 数据流图

```
【下单 — HTTP同步等待冻结完成】
HTTP(虚拟线程) → account_input CQ → AccountWorker(冻结) → matching_input CQ + account_output CQ
    │                                                              │
    └── CompletableFuture.await(5s) ←──────────────────────────────┘

【撮合 — 异步】
AccountWorker → matching_input CQ → MatchingEngine(撮合) → matching_output CQ

【清算 — 异步】
MatchingEngine → matching_output CQ → ClearingWorker(清算) → account_settlement CQ

【结算/解冻 — 异步】
ClearingWorker → account_settlement CQ → AccountWorker(结算/解冻) → account_output CQ → WebSocket推送

【快照 — 定时每分钟】
SnapshotWorker → SNAPSHOT事件写入account_input/matching_input CQ → Worker处理 → 写CM快照 → 原子rename
```

### 2.3 组件职责与线程模型

| 组件 | 职责 | 线程模型 | 数据存储 |
|------|------|---------|---------|
| **REST API** | HTTP请求，参数校验，写入CQ，同步等待 | Tomcat虚拟线程（无限） | - |
| **WebSocket** | 实时推送行情、订单、成交 | Netty EventLoop（8线程） | - |
| **AccountWorker** | 冻结/解冻/结算/入金；WebSocket推送（Netty非阻塞） | 单线程 | 内存（快照时写CM） |
| **MatchingEngine** | 订单撮合、五档行情、K线聚合 | 单线程 | 内存OrderBook（快照时写CM） |
| **ClearingWorker** | 订单状态更新，佣金/退款计算，SETTLEMENT/UNFREEZE事件生成（批量100条） | 单线程 | 内存 |
| **SnapshotWorker** | 定时快照到CM文件 | 单线程定时任务 | CM文件 |
| **PhaseManager** | 交易阶段切换，写入PHASE_CHANGE事件 | 单线程定时任务 | volatile |
| **RiskMonitor** | 风险度监控、强平触发（期货/期权） | 单线程定时任务 | 读内存 |
| **LifecycleManager** | 系统级状态管理，恢复协调，启停控制 | 主线程 | volatile |
| **AsyncPersistence** | 成交/订单异步写入H2 | 1线程 | 读CQ，写H2 |

**线程数汇总**：HTTP虚拟线程(无限) + WebSocket(8) + AccountWorker(1) + MatchingEngine(1) + ClearingWorker(1) + SnapshotWorker(1) + PhaseManager(1) + RiskMonitor(1) + LifecycleManager(1) + AsyncPersistence(1) = **15+**

---

## 3. 数据模型

### 3.1 内存数据结构（主存储）

#### 3.1.1 Account（账户）

| 字段 | 类型 | 说明 |
|------|------|------|
| accountId | long | 账户ID |
| userId | long | 用户ID |
| balance | long | 账户余额（分） |
| available | long | 可用资金（分） |
| frozen | long | 冻结资金（分） |
| marginFrozen | long | 保证金占用（分），期货/期权专用 |
| orderFrozen | long | 委托冻结（分） |
| debt | long | 负债/穿仓金额（分），期货/期权专用，首版不使用，初始化为0 |
| unrealizedPnl | long | 未实现盈亏（分） |
| riskRatio | double | 风险比率（期货/期权账户） |
| status | enum | ACTIVE / SUSPENDED / LIQUIDATING / NEGATIVE_BALANCE / CLOSED |
| updateTime | long | 更新时间戳 |

**不变式**：`balance = available + frozen`，`frozen = marginFrozen + orderFrozen`

#### 3.1.2 Position（持仓）

| 字段 | 类型 | 说明 |
|------|------|------|
| accountId | long | 账户ID |
| symbol | String | 证券代码 |
| symbolCode | int | 数值代码，加速处理 |
| quantity | long | 持仓数量（正数=多头，负数=空头） |
| available | long | 可用数量 |
| frozen | long | 冻结数量 |
| t1Locked | long | T+1锁定数量 |
| avgPrice | long | 成本价（分） |
| unrealizedPnl | long | 未实现盈亏（分） |
| updateTime | long | 更新时间戳 |

**不变式**：`|quantity| = available + frozen + t1Locked`

#### 3.1.3 Order（订单）

| 字段 | 类型 | 说明 |
|------|------|------|
| orderId | long | 订单ID（雪花算法） |
| symbol | String | 证券代码 |
| symbolCode | int | 数值代码 |
| side | enum | BUY / SELL |
| orderType | enum | LIMIT / MARKET / FAK / FOK |
| price | long | 价格（分），市价单为0 |
| quantity | long | 原始数量 |
| filledQuantity | volatile long | 已成交数量 |
| remainingQty | volatile long | 剩余数量 |
| frozenAmount | volatile long | 冻结金额（分），买单专用；卖单冻结的是持仓(不冻资金) |
| maxPrice | long | 市价买单最大价格限制（涨停价） |
| minPrice | long | 市价卖单最小价格限制（跌停价） |
| accountId | long | 账户ID |
| userId | long | 用户ID |
| status | volatile enum | PENDING / NEW / PARTIAL_FILLED / FILLED / CANCELLED / REJECTED（volatile保证跨线程可见性） |

**注**：`PENDING_CANCEL`仅为HTTP撤单响应状态，不是订单状态机成员。订单在撤单完成前保持当前状态（PENDING/NEW/PARTIAL_FILLED）。HTTP返回PENDING_CANCEL表示撤单请求已接受并进入处理流程。
| cancelReason | enum | FOK_NOT_FILLABLE / FAK_REMAINING_CANCELLED / EOD_CANCEL / INSUFFICIENT_BALANCE / ORDER_NOT_FOUND / ORDER_ALREADY_FILLED / ORDER_ALREADY_CANCELLED / PENDING_ORDER_NOT_IN_ORDERBOOK |
| idempotencyKey | String | 幂等键 |
| createTime | long | 创建时间戳 |
| updateTime | long | 更新时间戳 |

**订单状态机**：

```
HTTP下单 → PENDING（已入库，等待入OrderBook）
              │
              ├─ AccountWorker冻结失败 → REJECTED
              ├─ MatchingEngine确认 → NEW（已入OrderBook）
              │     ├─ 部分成交 → PARTIAL_FILLED
              │     │     ├─ 继续成交 → PARTIAL_FILLED / FILLED
              │     │     └─ 撤单 → CANCELLED（通过UNFREEZE）
              │     └─ 全部成交 → FILLED
              ├─ FOK/FAK无法成交 → CANCELLED（通过CANCEL_SUCCESS→UNFREEZE）
              └─ UNFREEZE → CANCELLED
```

**状态转换表**：

| 当前状态 | 触发事件 | 目标状态 | 条件 | 说明 |
|---------|---------|---------|------|------|
| - | HTTP下单 | REJECTED | 风控检查失败 | 不入库 |
| - | HTTP下单 | PENDING | 风控通过 | 已入库 |
| PENDING | AccountWorker冻结失败 | REJECTED | 余额/持仓不足 | 冻结失败 |
| PENDING | ORDER_UPDATE(NEW) | NEW | MatchingEngine确认 | 过渡结束 |
| PENDING | CANCEL_SUCCESS | CANCELLED | FOK/FAK无法成交 | 通过UNFREEZE解冻 |
| PENDING | UNFREEZE | CANCELLED | 撤单解冻 | AccountWorker处理 |
| NEW | TRADE(部分) | PARTIAL_FILLED | filledQuantity < quantity | 继续撮合 |
| NEW | TRADE(全部) | FILLED | filledQuantity == quantity | 撮合完成 |
| NEW | UNFREEZE | CANCELLED | 撤单 | 通过CANCEL_SUCCESS→UNFREEZE |
| PARTIAL_FILLED | TRADE(继续) | PARTIAL_FILLED | 继续撮合 | - |
| PARTIAL_FILLED | TRADE(全部) | FILLED | 全部成交 | - |
| PARTIAL_FILLED | UNFREEZE | CANCELLED | 撤单 | 通过CANCEL_SUCCESS→UNFREEZE |
| NEW/PARTIAL_FILLED | CANCEL_SUCCESS | CANCELLED | MatchingEngine撤单成功 | 触发UNFREEZE |
| NEW/PARTIAL_FILLED | CANCEL_REJECT | 保持原状态 | 撤单失败 | 订单已成交或不存在 |

#### 3.1.4 Instrument（合约/证券信息）

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 证券代码 |
| symbolCode | int | 数值代码 |
| type | enum | STOCK / FUTURE / OPTION |
| name | String | 证券名称 |
| prevClose | long | 前收盘价（分） |
| limitUpRatio | double | 涨停比例 |
| limitDownRatio | double | 跌停比例 |
| lotSize | long | 每手数量（股票100） |
| minPriceIncrement | long | 最小价格变动单位（分） |
| commissionRate | int | 佣金率（万分比，如3=万分之三） |
| commissionPerLot | long | 每手佣金（分），期货专用，股票为0 |
| stampDutyRate | int | 印花税率（万分比），预留，当前版本为0 |
| transferFeeRate | int | 过户费率（万分比），预留，当前版本为0 |
| updateTime | long | 更新时间戳 |

#### 3.1.5 ApiKey（API密钥）

| 字段 | 类型 | 说明 |
|------|------|------|
| apiKeyId | long | API Key ID（雪花算法） |
| userId | long | 用户ID |
| apiKey | String | API Key，前缀 ak_ |
| secretKeyHash | String | Secret Key的bcrypt哈希（不存储明文） |
| keyType | enum | READ_ONLY / TRADING |
| allowedInstruments | String | 可交易品种，逗号分隔（股票,期货,期权），仅TRADING类型有效 |
| status | enum | ACTIVE / DISABLED |
| createdAt | long | 创建时间戳 |
| lastUsedAt | long | 最后使用时间戳 |

**认证方式**：HMAC-SHA256签名
- Header: X-API-Key, X-Timestamp, X-Signature
- 签名公式: signature = HMAC-SHA256(secretKey, timestamp + method + path + body)

**权限说明**：
- READ_ONLY：仅限查询接口（账户、持仓、委托、历史、行情）
- TRADING：READ_ONLY权限 + 下单、撤单

### 3.2 内存数据存储类

#### 3.2.1 InMemoryAccountStore

账户存储，ConcurrentHashMap<Long, Account>。

| 操作 | 功能要求 | 错误处理 |
|------|---------|---------|
| getAccount | 根据accountId获取 | 不存在返回null |
| freeze | 冻结资金：扣减available，增加frozen和orderFrozen | 2004(不存在)/2003(状态异常)/2001(余额不足) |
| unfreeze | 解冻资金：减少frozen和orderFrozen，增加available | 静默处理 |
| settleBuy | 买方结算原子操作：frozen -= frozenAmountUsed, orderFrozen -= frozenAmountUsed, balance -= totalDeduction, available += refund | 验证不变式 |
| settleSell | 卖方结算：balance += netIncome, available += netIncome | 验证不变式 |
| deposit | 入金：balance += amount, available += amount | - |
| writeToChronicleMap | 快照时写入CM | - |
| loadFromChronicleMap | 恢复时从CM加载 | - |

**不变式验证**：每次操作后验证 `balance = available + frozen`。验证失败时尝试自动修复，修复失败则标记 SUSPENDED + P0 告警。

#### 3.2.2 InMemoryPositionStore

持仓存储，ConcurrentHashMap<String, Position>，key=`accountId:symbol`。

| 操作 | 功能要求 | 错误处理 |
|------|---------|---------|
| getPosition | 根据accountId和symbol获取 | 不存在返回null |
| freezePosition | 冻结持仓：扣减available，增加frozen | 2002(持仓不足) |
| unfreezePosition | 解冻持仓 | 静默处理 |
| settleBuyPosition | 买方结算：quantity += qty, t1Locked += qty（不存在则新建） | 验证不变式 |
| settleSellPosition | 卖方结算：frozen -= qty, quantity -= qty | 验证不变式，qty==0时检查移除 |
| unlockT1 | SOD T+1解锁：available += t1Locked, t1Locked = 0 | 验证不变式 |
| addPosition | 新建持仓（买入成交时） | - |
| writeToChronicleMap | 快照时写入CM | - |
| loadFromChronicleMap | 恢复时从CM加载 | - |

**不变式验证**：每次操作后验证 `|quantity| = available + frozen + t1Locked`。

**unrealizedPnl计算策略**：服务端不主动维护unrealizedPnl字段。前端基于WebSocket market_snapshot推送的最新成交价，在客户端实时计算：unrealizedPnl = (currentPrice - avgPrice) × quantity。Account/Position中的unrealizedPnl字段仅在HTTP查询时按需计算（从MatchingEngine获取最新成交价）。

#### 3.2.3 InMemoryOrderStore

订单存储，ConcurrentHashMap<Long, Order>。

| 操作 | 功能要求 |
|------|---------|
| getOrder | 根据orderId获取 |
| addOrder | 添加新订单（HTTP线程） |
| updateStatus | 更新订单状态 |
| updateFilledQuantity | 累加filledQuantity，扣减remainingQty |
| removeOrder | 移除订单（成交完成/撤销） |
| clear | 清空所有订单（新交易日） |

**说明**：订单不通过 CM 快照持久化。PENDING 订单通过 account_input CQ 重放恢复，活跃订单通过 MatchingEngine 快照恢复。

#### 3.2.4 InMemoryInstrumentStore

合约存储，ConcurrentHashMap<String, Instrument>，key=symbol。SOD 时从 H2 加载或恢复时从 H2 重新加载。

| 操作 | 功能要求 |
|------|---------|
| getInstrument | 根据symbol获取，不存在返回null |
| putInstrument | 写入/更新（INSTRUMENT_INIT事件触发） |
| getCommissionRate | 返回佣金率（万分比），用于冻结金额计算 |

### 3.3 Chronicle Queue 事件结构

#### 3.3.1 account_input CQ

| 字段 | 类型 | 说明 |
|------|------|------|
| eventType | enum | NEW_ORDER / CANCEL_ORDER / SNAPSHOT / PHASE_CHANGE / DEPOSIT / INSTRUMENT_INIT |
| orderId | long | 订单ID |
| accountId | long | 账户ID（DEPOSIT事件从userId查找填充） |
| userId | long | 用户ID |
| symbol | String | 证券代码 |
| side | enum | BUY / SELL |
| orderType | enum | LIMIT / MARKET / FAK / FOK |
| price | long | 价格（分） |
| quantity | long | 数量 |
| frozenAmount | long | 本次冻结金额（分），买单=限价(或涨停价)×数量+预估佣金，卖单=0 |
| phase | enum | CONTINUOUS_TRADING / CLOSED（仅PHASE_CHANGE事件，由PhaseManager写入） |
| isSystemOrder | boolean | 是否系统订单（强平/做市） |
| systemReason | String | 系统订单原因 |
| cancelReason | enum | EOD_CANCEL / USER_CANCEL（仅CANCEL_ORDER事件） |
| depositId | String | 入金/纠正ID（仅DEPOSIT事件） |
| depositAmount | long | 入金金额（仅DEPOSIT事件，负数为纠正） |
| isCorrection | boolean | 是否纠正操作（仅DEPOSIT事件） |
| originalDepositId | String | 纠正对应的原始入金ID（仅DEPOSIT纠正事件） |
| operatorId | long | 操作管理员ID（仅DEPOSIT事件） |
| depositReason | String | 入金/纠正原因（仅DEPOSIT事件） |
| idempotencyKey | String | 幂等键 |
| maxPrice | long | 市价买单最大价格限制（涨停价），限价单为0 |
| minPrice | long | 市价卖单最小价格限制（跌停价），限价单为0 |
| timestamp | long | 时间戳 |
| cqIndex | long | CQ写入位置（SNAPSHOT专用） |
| instrumentData | Instrument | Instrument数据（仅INSTRUMENT_INIT事件） |

| 事件类型 | 来源 | 说明 |
|---------|------|------|
| NEW_ORDER | HTTP线程 | 下单请求 |
| CANCEL_ORDER | HTTP线程 / EOD | 撤单请求 |
| SNAPSHOT | SnapshotWorker | 触发快照 |
| PHASE_CHANGE | PhaseManager | 交易时段切换（仅CONTINUOUS_TRADING/CLOSED） |
| DEPOSIT | 管理员HTTP | 入金请求（含纠正） |
| INSTRUMENT_INIT | SOD流程 | Instrument数据初始化 |

**INSTRUMENT_INIT 事件**携带：symbol, symbolCode, type, name, prevClose, limitUpRatio, limitDownRatio, lotSize, minPriceIncrement, commissionRate, commissionPerLot, stampDutyRate, transferFeeRate。

#### 3.3.2 matching_input CQ

| 字段 | 类型 | 说明 |
|------|------|------|
| eventType | enum | NEW_ORDER / CANCEL_ORDER / SNAPSHOT / INSTRUMENT_INIT |
| orderId | long | 订单ID |
| symbol | String | 证券代码 |
| side | enum | BUY / SELL |
| orderType | enum | LIMIT / MARKET / FAK / FOK |
| price | long | 价格（分），市价单为0 |
| quantity | long | 数量 |
| maxPrice | long | 市价买单最大价格限制（涨停价） |
| minPrice | long | 市价卖单最小价格限制（跌停价） |
| accountId | long | 账户ID |
| userId | long | 用户ID |
| frozenAmount | long | 关联冻结金额，用于撤单解冻参考 |
| idempotencyKey | String | 幂等键 |
| timestamp | long | 时间戳 |
| cqIndex | long | CQ写入位置（SNAPSHOT专用） |
| instrumentData | Instrument | Instrument数据（仅INSTRUMENT_INIT事件） |

#### 3.3.3 matching_output CQ

| 字段 | 类型 | 说明 |
|------|------|------|
| eventType | enum | ORDER_UPDATE / TRADE / CANCEL_SUCCESS / CANCEL_REJECT |
| orderId | long | 订单ID |
| accountId | long | 账户ID |
| userId | long | 用户ID |
| symbol | String | 证券代码（CANCEL_SUCCESS/CANCEL_REJECT必填） |
| status | enum | ORDER_UPDATE: NEW / FILLED / CANCELLED |
| filledQuantity | long | 已成交数量（TRADE/CANCEL_SUCCESS填写） |
| remainingQty | long | 剩余数量（TRADE/CANCEL_SUCCESS填写） |
| tradeId | String | 成交ID：`takerOrderId_makerOrderId` |
| takerOrderId | long | 主动方订单ID |
| makerOrderId | long | 被动方订单ID |
| price | long | 成交价格 |
| quantity | long | 成交数量 |
| orderType | enum | LIMIT / MARKET / FAK / FOK（TRADE专用） |
| orderPrice | long | 原订单限价（TRADE专用，市价单为0） |
| frozenAmount | long | 原订单冻结金额（TRADE专用，卖方为0） |
| maxPrice | long | 市价买单最大价格限制（TRADE专用，限价单为0） |
| orderQuantity | long | 原订单总数量（TRADE专用） |
| buyAccountId | long | 买方账户ID |
| sellAccountId | long | 卖方账户ID |
| buyOrderId | long | 买方订单ID |
| sellOrderId | long | 卖方订单ID |
| commissionRate | int | 佣金率（万分比）（TRADE专用，买卖双方通用） |
| cancelReason | enum | FOK_NOT_FILLABLE / FAK_REMAINING_CANCELLED / EOD_CANCEL / USER_CANCEL（CANCEL_SUCCESS专用） |
| unfreezeAmount | long | 解冻金额（CANCEL_SUCCESS/CANCEL_REJECT专用，买方） |
| unfreezeQty | long | 解冻数量（CANCEL_SUCCESS/CANCEL_REJECT专用，卖方） |
| side | enum | BUY / SELL（CANCEL_SUCCESS/CANCEL_REJECT必填） |
| rejectReason | enum | ORDER_NOT_FOUND / ORDER_ALREADY_FILLED / ORDER_ALREADY_CANCELLED / PENDING_ORDER_NOT_IN_ORDERBOOK（CANCEL_REJECT专用） |
| timestamp | long | 时间戳 |

**TradeId 确定性生成**：`tradeId = takerOrderId_makerOrderId`，保证重复撮合产生相同 tradeId，支持幂等。

#### 3.3.4 account_settlement CQ

| 字段 | 类型 | 说明 |
|------|------|------|
| eventType | enum | SETTLEMENT / UNFREEZE |
| orderId | long | 订单ID |
| accountId | long | 账户ID |
| userId | long | 用户ID |
| symbol | String | 证券代码（UNFREEZE必填） |
| tradeId | String | 成交ID，幂等检查用（SETTLEMENT专用） |
| settlementType | enum | BUYER / SELLER |
| price | long | 成交价格 |
| quantity | long | 成交数量 |
| frozenAmountUsed | long | 买方=本次使用的冻结金额（分），卖方=本次成交数量（股，单位不同） |
| actualAmount | long | 实际扣款金额 = price × quantity |
| refundAmount | long | 退还差额（仅买方），= frozenAmountUsed - actualAmount - commission；卖方=0；AccountWorker独立计算退款，此字段仅用于审计 |
| commission | long | 佣金 |
| isT1Locked | boolean | 是否T+1锁定（买方true，卖方false） |
| unfreezeAmount | long | 解冻金额（UNFREEZE专用） |
| unfreezeQty | long | 解冻数量（UNFREEZE专用） |
| side | enum | BUY / SELL（UNFREEZE必填） |
| timestamp | long | 时间戳 |

**frozenAmountUsed 计算规则**（ClearingWorker使用**原始frozenAmount**计算，不使用减少后的值，避免精度损失累积）：
- ClearingWorker为每个Order维护`originalFrozenAmount`（首次TRADE时记录order.frozenAmount快照）和`originalQuantity`
- 限价买单：`frozenAmountUsed = ceil(成交金额 × originalFrozenAmount / (order.price × originalQuantity))`
- 市价买单：`frozenAmountUsed = ceil(成交金额 × originalFrozenAmount / (maxPrice × originalQuantity))`（市价单 price=0）
- 全部成交(最后一次)：`remainingQty==0 → order.frozenAmount`（使用当前frozenAmount确保精确归零）
- 卖方：frozenAmountUsed = 成交数量（股）
- 防护：frozenAmountUsed = min(计算值, order.frozenAmount)，确保不超额扣减

#### 3.3.5 account_output CQ

| 字段 | 类型 | 说明 |
|------|------|------|
| eventType | enum | FREEZE / UNFREEZE / BALANCE_CHANGE / POSITION_CHANGE |
| accountId | long | 账户ID |
| userId | long | 用户ID |
| orderId | long | 订单ID |
| tradeId | String | 成交ID（BALANCE_CHANGE/POSITION_CHANGE时为空） |
| side | enum | BUY / SELL |
| price | long | 成交价格（分） |
| quantity | long | 成交数量 |
| symbol | String | 证券代码 |
| balanceDelta | long | 余额变动 |
| availableDelta | long | 可用资金变动 |
| frozenDelta | long | 冻结资金变动 |
| marginFrozenDelta | long | 保证金冻结变动（期货/期权专用） |
| quantityDelta | long | 持仓数量变动 |
| positionAvailableDelta | long | 可用持仓变动 |
| positionFrozenDelta | long | 冻结持仓变动 |
| t1LockedDelta | long | T+1锁定变动 |
| reason | enum | ORDER_FREEZE / ORDER_UNFREEZE / TRADE_BUY / TRADE_SELL / DEPOSIT / COMMISSION / LIQUIDATION / T1_UNLOCK |
| timestamp | long | 时间戳 |

### 3.4 快照索引文件与恢复逻辑

系统采用**各Worker独立快照、四阶段顺序恢复**机制。

#### 3.4.1 AccountWorker快照

**文件命名**：`account_snapshot_{account_input_cq_index}.dat`

**文件结构**：magic(int) → version(int) → account_count → accounts[] → position_count → positions[] → account_input_index → account_settlement_index → matching_input_last_index → create_time

**校验文件**：`account_snapshot_{account_input_cq_index}.dat.checksum`（MD5）

**说明**：AccountWorker快照不保存订单数据。PENDING订单通过 account_input CQ 重放恢复，活跃订单通过 MatchingEngine 快照恢复。

**SNAPSHOT事件批处理行为**：当AccountWorker批量读取发现SNAPSHOT事件时：
1. 先处理当前批次中SNAPSHOT之前的所有普通事件（含冻结/解冻/结算）
2. 执行快照，捕获所有已处理事件的效果
3. SNAPSHOT之后的事件在下一批次处理
4. 快照的account_input_index记录SNAPSHOT事件本身的CQ索引，快照反映了截至该索引的所有事件效果

MatchingEngine同理：SNAPSHOT前的普通事件先处理，然后执行快照。

**恢复逻辑**：

```
1. 查找最新有效快照文件
   1.1 检测tmp残留文件：
       for each account_snapshot_*.tmp:
           对应的.dat不存在 && .tmp.checksum校验通过 → rename为.dat（恢复crash前未完成的快照）
           .tmp.checksum校验失败 → 删除.tmp和.tmp.checksum
   1.2 遍历 account_snapshot_*.dat（按index降序）
       for file in files:
           计算 MD5 并与 .checksum 比对
           若一致 → 有效快照
           若不一致 → 文件损坏，尝试下一个
       若无有效快照 → 初始化空状态

2. 从 H2 instruments 表加载所有 Instrument 到 InMemoryInstrumentStore

3. 加载快照到内存
   加载 accounts、positions 到 ConcurrentHashMap
   记录 account_input_index、account_settlement_index、matching_input_last_index

4. 生成幂等集合（恢复模式动态扩容：根据CQ事件量调整集合大小，不受IDEMPOTENT_SET_MAX_SIZE限制；恢复正常模式后缩回限制大小）
   processed_order_ids：扫描 matching_input CQ [matching_input_last_index, latest]
   processed_trade_ids：扫描 matching_output CQ [0, matching_output_last_index]，提取所有 TRADE 的 tradeId（MatchingEngine恢复时防重复生成TRADE）
   processed_settlement_ids：扫描 account_settlement CQ [account_settlement_index, latest]，提取所有 tradeId_orderId（防重复结算转账）
   processed_unfreeze_ids：扫描 account_settlement CQ [account_settlement_index, latest]，提取所有 UNFREEZE 事件的 orderId（防重复解冻）

5. 设置 replay_mode=true

6. 重放 account_input CQ（从 account_input_index+1）
   for event in events:
       if NEW_ORDER:
           // ⚠️ 始终执行冻结（重放从快照点之后开始，这些事件的冻结效果尚未反映在快照中）
           freeze(event)
           // ⚠️ 始终重建 Order 对象（快照不保存订单，后续 CANCEL_ORDER 需要）
           // 若orderId已在processed_order_ids中，说明MatchingEngine已处理，状态设为NEW
           OrderStatus initStatus = (orderId in processed_order_ids) ? NEW : PENDING
           orderStore.addOrder(new Order(event.orderId, event.symbol, initStatus,
                                         event.side, event.quantity, event.frozenAmount,
                                         event.maxPrice, event.minPrice))
           write_to_account_output(event)
           // 仅跳过已转发订单的 matching_input 写入
           if !(replay_mode && orderId in processed_order_ids):
               write_to_matching_input(event)

       if CANCEL_ORDER:
           // 恢复期间统一转发 matching_input 由 MatchingEngine 处理
           // 从InMemoryOrderStore查找symbol/side等信息填入matching_input事件
           write_to_matching_input(event)
           write_to_account_output(event)

7. 重放 account_settlement CQ（从 account_settlement_index+1）
   for event in events:
       if SETTLEMENT:
           if settlementId in processed_settlement_ids: skip
           执行结算转账
           加入 processed_settlement_ids
       if UNFREEZE:
           if event.orderId in processed_unfreeze_ids: skip
           解冻资金/持仓
           加入 processed_unfreeze_ids
```

#### 3.4.2 MatchingEngine快照

**文件命名**：`matching_snapshot_{matching_input_cq_index}.dat`

**文件结构**：magic → version → symbol_count → orderbooks[]（各symbol的OrderBook） → matching_input_index → matching_output_last_index → create_time

**校验文件**：`matching_snapshot_{matching_input_cq_index}.dat.checksum`（MD5，外部存储）

**恢复逻辑**：

```
1. 查找最新有效快照文件（验证MD5 checksum，检测tmp残留文件同AccountWorker逻辑）

2. 加载快照到内存
   恢复各 symbol 的 OrderBook（bids + asks）
   记录 matching_input_index、matching_output_last_index

3. 生成幂等集合（恢复模式动态扩容，不受IDEMPOTENT_SET_MAX_SIZE限制）
   existing_trade_ids：扫描 matching_output CQ [0, lastIndex]，提取所有 TRADE 的 tradeId
   existing_cancel_ids：扫描 matching_output CQ [0, lastIndex]，提取所有 CANCEL_SUCCESS/CANCEL_REJECT 的 orderId

4. 设置 replay_mode=true

5. 重放 matching_input CQ（从 matching_input_index+1）
   for event in events:
       if NEW_ORDER:
           读取 InMemoryOrderStore，若 order.status ∈ (CANCELLED, REJECTED) → 跳过
           加入 OrderBook → 撮合 → 生成 TRADE 事件
           若 replay_mode && tradeId in existing_trade_ids: 跳过TRADE写入
           否则: 写入 matching_output
           写入 ORDER_UPDATE(status=NEW)

       if CANCEL_ORDER:
           若 replay_mode && orderId in existing_cancel_ids: 跳过 CANCEL 写入
           否则: 从 OrderBook 移除 → 生成 CANCEL_SUCCESS/CANCEL_REJECT → 写入 matching_output

6. 重放完成后设置 replay_mode=false，缩回幂等集合至IDEMPOTENT_SET_MAX_SIZE限制
```

#### 3.4.3 ClearingWorker 恢复

ClearingWorker **不生成快照**。恢复时增加TRADE幂等检查，防止重复生成SETTLEMENT事件。

**恢复逻辑**：

```
1. 读取恢复起始位置（优先级）：
   - 优先：读取 data/snapshots/clearing_position.dat 中的上次处理位置
   - 若文件不存在或损坏：回退到 MatchingEngine 快照的 matching_output_last_index

2. 生成已处理TRADE幂等集合（恢复模式动态扩容）：
   clearing_processed_trade_ids：扫描 account_settlement CQ [0, latest]，提取所有 SETTLEMENT 事件的 tradeId
   clearing_processed_cancel_ids：扫描 account_settlement CQ [0, latest]，提取所有 UNFREEZE 事件的 orderId

3. 重放 matching_output CQ（从该位置+1）
   for event in events:
       if TRADE:
           if tradeId in clearing_processed_trade_ids: skip
           直接处理 → 生成 SETTLEMENT → 写入 account_settlement CQ
           加入 clearing_processed_trade_ids
       if ORDER_UPDATE(status=NEW): 更新订单 PENDING→NEW
       if CANCEL_SUCCESS:
           if orderId in clearing_processed_cancel_ids: skip
           生成 UNFREEZE → 写入 account_settlement CQ
           加入 clearing_processed_cancel_ids
       if CANCEL_REJECT(PENDING_ORDER_NOT_IN_ORDERBOOK):
           若订单状态为 PENDING/NEW:
               if orderId in clearing_processed_cancel_ids: skip
               生成 UNFREEZE

4. 重放完成后缩回幂等集合至IDEMPOTENT_SET_MAX_SIZE限制
```

#### 3.4.4 快照原子写入流程

```
1. 创建临时文件：account_snapshot_{index}.tmp
2. 写入快照数据到临时文件
3. 计算 MD5 → 写入 account_snapshot_{index}.tmp.checksum
4. 原子重命名：.tmp → .dat，.tmp.checksum → .dat.checksum
5. 清理旧快照（保留最近3个）

**crash恢复时tmp文件处理**（在3.4.1/3.4.2恢复逻辑步骤1中执行）：
- 若.tmp存在但.dat不存在 && .tmp.checksum校验通过 → rename为.dat（恢复crash前未完成的快照）
- 若.tmp.checksum校验失败 → 删除.tmp和.tmp.checksum（写入不完整）
- 若.tmp和.dat同时存在 → 删除.tmp和.tmp.checksum（rename已完成但.tmp未清理，如系统在rename后crash）
```

#### 3.4.5 恢复顺序

| 阶段 | Worker | 快照来源 | 重放CQ | 说明 |
|------|--------|---------|--------|------|
| 1 | AccountWorker（第一阶段） | account_snapshot | account_input CQ | 含tmp残留检测、幂等集动态扩容 |
| 2 | MatchingEngine | matching_snapshot | matching_input CQ | 含tmp残留检测、幂等集动态扩容 |
| 3 | ClearingWorker | clearing_position.dat（回退 matching_output_last_index） | matching_output CQ | 含TRADE幂等检查 |
| 4 | AccountWorker（第二阶段） | - | account_settlement CQ | 含幂等集动态扩容 |

**恢复阶段间依赖保证**：
- 阶段1→2：AccountWorker重建Order对象后MatchingEngine才能做状态检查
- 阶段2→3：MatchingEngine恢复OrderBook后ClearingWorker才能正确处理TRADE
- 阶段3→4：ClearingWorker生成SETTLEMENT后AccountWorker第二阶段才能正确结算
- 任一阶段失败：记录P0告警，systemStatus=DEGRADED，不继续后续阶段

**replay_mode关闭时机**：各Worker重放CQ到达尾部（`tailer.index() == appender.lastIndex()`）后关闭replay_mode，缩回幂等集合至正常大小。

#### 3.4.6 RPO/RTO 保证

| 场景 | RPO | RTO | 说明 |
|------|-----|-----|------|
| 正常关闭 | 0 | ≤30秒 | 关闭前最终快照 |
| Crash重启 | 0 | ≤1分钟 | 独立快照+CQ增量 |
| 快照失败 | 0 | ≤5分钟 | CQ仍持久化，回退到上一个有效快照 |
| CQ文件损坏 | 取决于损坏位置 | 取决于损坏程度 | 从有效快照恢复，损坏段数据可能丢失 |

**RPO=0 关键保证**：writeMode=SYNC（RPO关键CQ）+ 快照原子rename + MD5校验 + 独立快照回退 + tmp残留文件检测 + 幂等集合恢复时动态扩容 + ClearingWorker恢复幂等检查。

### 3.5 FutureManager

HTTP同步等待机制，ConcurrentHashMap<Long, CompletableFuture<ProcessResult>>。使用 `orTimeout(5s)` 实现超时，超时返回错误码1002。

---

## 4. 核心流程

### 4.1 下单流程

```
【阶段1: HTTP请求处理】

1. 幂等检查（idempotencyKey）
   - 若已存在且CONFIRMED → 返回已有orderId
   - 若为PROCESSING → 返回1003"请求处理中"
   - 否则 → 创建新条目(status=PROCESSING)

2. 交易阶段检查：
   - currentPhase == CONTINUOUS_TRADING → 继续
   - currentPhase == CLOSED（交易日收盘）→ 4001
   - currentPhase == CLOSED（非交易日）→ 4005
   - currentPhase == PRE_MARKET → 4005
   - currentPhase == LUNCH_BREAK → 4001
   （简化为：非 CONTINUOUS_TRADING 时根据 phase 返回对应错误码）

2.5 限流检查：当前userId的orderTPS > 100 → 返回1007

3. 参数校验
   - symbol格式、side枚举、orderType枚举
   - LIMIT: price > 0
   - quantity > 0 && quantity % lotSize == 0
   - 涨跌停校验（price不能超出涨停/跌停范围）
   - HTTP层从InMemoryInstrumentStore（ConcurrentHashMap，线程安全）读取Instrument数据进行lotSize/涨跌停/佣金率校验

4. 生成orderId（雪花算法）

5. 创建CompletableFuture并注册到FutureManager

6. 写入 account_input CQ（失败→删除订单、释放幂等键、返回9999）

7. 等待：future.get(5s超时) → 返回orderId和status=PENDING


【阶段2: AccountWorker批量处理】

8. 批量读取 account_input CQ（最多50条/等待1ms）

9. 批量处理：
   对于INSTRUMENT_INIT：存入 InstrumentStore
   对于PHASE_CHANGE：更新 currentPhase volatile 变量，不写入下游CQ
   对于DEPOSIT：balance += amount, available += amount → account_output CQ
   对于NEW_ORDER：
     - 市价买单：冻结金额 = 涨停价×数量 + 预估佣金，设置maxPrice=涨停价
     - 市价卖单：冻结持仓，不冻结资金，设置minPrice=跌停价
     - 限价买单/FAK/FOK：冻结金额 = price×数量 + 预估佣金
       预估佣金 = ceil(amount × commissionRate / 10000)（向上取整，保证冻结充足）
  市价买单 amount = 涨停价×数量，限价单 amount = price×数量
     - 买单 → accountStore.freeze()，卖单 → positionStore.freezePosition()
     - 成功 → 生成 matching_input 事件 + account_output 事件
     - 失败 → 状态设为 REJECTED，WebSocket推送拒绝通知

10. 批量写入 matching_input CQ 和 account_output CQ
    先写入下游CQ，再apply内存修改。若CQ写入失败：丢弃本次修改，CompletableFuture.complete(错误码5001)，重新入队（最多重试3次，超过后P0告警+丢弃，恢复时从CQ重放）。

11. 批量 CompletableFuture.complete()
```

### 4.2 撮合流程

```
【MatchingEngine批量处理】

1. 批量读取 matching_input CQ（最多50条/等待1ms）

2. 按symbol分组（提高缓存命中率）

3. 批量处理：
   对于INSTRUMENT_INIT：存入内存（供涨跌停校验）

   对于NEW_ORDER：
     - 幂等检查：若orderId ∈ processed_order_ids → 跳过
     - 状态检查：读取 InMemoryOrderStore，若 order.status ∈ (CANCELLED, REJECTED) → 跳过（已撤单。status 为 volatile，保证跨线程可见）
     - orderId → processed_order_ids
     - 加入 OrderBook → 根据orderType执行撮合
     - 写入 ORDER_UPDATE(status=NEW)
     - FAK/MARKET: 全部成交→FILLED，未成交部分→CANCEL_SUCCESS
     - FOK: 满足条件→全部成交，不满足→CANCEL_SUCCESS

   对于CANCEL_ORDER：
     - 从OrderBook移除 → CANCEL_SUCCESS（或CANCEL_REJECT）

4. 批量写入 matching_output CQ
   同一订单的TRADE事件必须排在CANCEL_SUCCESS前面

5. 五档行情推送（每3秒聚合），K线更新（实时）
```

**按订单类型撮合规则**：

| 订单类型 | 撮合规则 | 未成交部分 |
|---------|---------|-----------|
| LIMIT | 价格优先、时间优先撮合 | 保留在OrderBook |
| MARKET | 与最优对手方成交，不超过涨跌停价 | 立即撤销（CANCEL_SUCCESS） |
| FAK | 限价立即成交能成交的部分 | 剩余立即撤销（CANCEL_SUCCESS） |
| FOK | 检查对手方符合价格条件的可成交量总和≥quantity则全成交 | 否则全部撤销（CANCEL_SUCCESS） |

**FAK/FOK/MARKET 撤单解冻逻辑**：
- MatchingEngine在CANCEL_SUCCESS中仅发出remainingQty（卖方）或remainingFrozen（买方的剩余冻结金额）
- 买方remainingFrozen计算：`remainingFrozen = order.frozenAmount - Σ(tradePrice_i × tradeQty_i)`（按成交金额比例估算，不含佣金）
  - 简化计算：`remainingFrozen = order.frozenAmount × remainingQty / order.quantity`（按数量比例）
  - 若remainingQty == 0（全部未成交）：`remainingFrozen = order.frozenAmount`
- 卖方：unfreezeAmount = 0，unfreezeQty = remainingQty
- AccountWorker处理UNFREEZE时，买单直接解冻order.frozenAmount当前值（已被ClearingWorker为成交部分减少），而非用frozenAmount * remainingQty / quantity公式。卖单解冻order.quantity - order.filledQuantity。此方式自然处理部分成交后的舍入残余。
- **FAK/FOK CANCEL_SUCCESS + CANCEL_ORDER竞态**：FAK/FOK部分成交后MatchingEngine发出CANCEL_SUCCESS，同时用户可能发出CANCEL_ORDER，导致ClearingWorker生成两条UNFREEZE事件。由processed_unfreeze_ids幂等防护，第二条UNFREEZE被跳过。

### 4.3 清算流程

```
【ClearingWorker批量处理】

1. 批量读取 matching_output CQ（最多100条/等待1ms）

2. 批量处理：
   对于ORDER_UPDATE(status=NEW)：更新订单 PENDING→NEW，WebSocket推送

   对于TRADE（不做幂等检查，幂等由AccountWorker兜底）：
     - 更新买卖双方 filledQuantity、remainingQty
     - 计算成交金额：amount = price × quantity
     - 计算佣金：commission = (amount × commissionRate) / 10000（floor）
     - 计算 frozenAmountUsed（买方）：
       * 限价单：ceil(成交金额 × order.frozenAmount / (order.price × order.quantity))
       * 市价单：ceil(成交金额 × order.frozenAmount / (maxPrice × order.quantity))
       * 全部成交(最后一次)：remainingQty==0 → order.剩余frozenAmount
     * 防护：frozenAmountUsed = min(计算值, order.frozenAmount)，确保不超额扣减
     - 计算退款（买方）：refund = frozenAmountUsed - price×quantity - commission
     - 更新 order.frozenAmount -= frozenAmountUsed
     - 生成两条 SETTLEMENT 事件（买方+卖方各一条）→ account_settlement CQ

   对于CANCEL_SUCCESS：生成 UNFREEZE 事件 → account_settlement CQ

   对于CANCEL_REJECT(PENDING_ORDER_NOT_IN_ORDERBOOK)：
     若订单状态为 PENDING/NEW → 生成 UNFREEZE
```

### 4.4 结算/解冻流程

```
【AccountWorker处理SETTLEMENT/UNFREEZE】

1. 批量读取 account_settlement CQ

2. 批量处理：
   对于SETTLEMENT事件：
     - 幂等检查：settlementId = tradeId + "_" + orderId
       若已处理 → 跳过
     - 加入 processed_settlement_ids

     【买方结算 — 原子操作】
     1. 总扣款 = price × quantity + commission
     2. 退款 = frozenAmountUsed - 总扣款
     3. ⚠️ 预检查（在任何状态修改之前）：
        - if refund < 0 && available < |refund|:
            拒绝本条 SETTLEMENT（不修改任何状态）
            记录 P0 告警 + 标记账户 SUSPENDED
            不推进 account_settlement CQ 处理位置（恢复时重试）
            continue（跳过本事件）
        - if frozenAmountUsed > order.frozenAmount:
            frozenAmountUsed = order.frozenAmount（防护性截断）
            记录 P0 告警 + 标记账户 SUSPENDED
     4. 原子更新（以下字段在一次操作中完成）：
        - frozen -= frozenAmountUsed
        - orderFrozen -= frozenAmountUsed
        - balance -= 总扣款
        - if refund > 0: available += refund（差额退还）
        - if refund < 0: available -= |refund|（正常场景不应发生，ceil保证refund≥0）
     5. order.frozenAmount -= frozenAmountUsed
     6. 增加/更新持仓：quantity += qty, t1Locked += qty（A股T+1）
     7. 更新持仓成本价：avgPrice = (old_avgPrice × old_quantity + price × quantity) / new_quantity（整数除法floor）
     7. 验证不变式：balance == available + frozen

     【卖方结算】
     1. position.frozen -= quantity, position.quantity -= quantity
     2. 若 quantity==0: 验证 available+frozen+t1Locked==0 → 移除持仓（不满足则告警+SUSPENDED）
     3. 若有保证金: marginFrozen -= 保证金, frozen -= 保证金
     4. 净收入 = price×quantity - commission
     5. balance += 净收入, available += 净收入
     6. 验证不变式

   对于UNFREEZE事件：
     - 幂等检查：若 unfreezeId = event.orderId 已在 processed_unfreeze_ids 中 → 跳过
     - 加入 processed_unfreeze_ids
     - 买单：available += unfreezeAmount, frozen -= unfreezeAmount, orderFrozen -= unfreezeAmount
     - 卖单：position.available += unfreezeQty, position.frozen -= unfreezeQty
     - 更新订单状态为 CANCELLED

3. 批量写入 account_output CQ → WebSocket推送（Netty非阻塞）
```

### 4.5 撤单全链路流程

```
【阶段1: HTTP撤单请求】
  → 校验orderId → 写入CANCEL_ORDER到account_input CQ → HTTP返回PENDING_CANCEL

【阶段2: AccountWorker处理CANCEL_ORDER】
  - 所有 CANCEL_ORDER 统一转发到 matching_input CQ 由 MatchingEngine 处理
  - 无论订单是 PENDING、NEW 还是 PARTIAL_FILLED——MatchingEngine 自行判断（在OrderBook则移除，不在则CANCEL_REJECT→UNFREEZE）

【阶段3: MatchingEngine处理CANCEL_ORDER】
  - 订单在OrderBook → 移除 → CANCEL_SUCCESS
  - 订单不在OrderBook → CANCEL_REJECT

【阶段4: ClearingWorker处理撤单结果】
  - CANCEL_SUCCESS → UNFREEZE → account_settlement CQ
  - CANCEL_REJECT(PENDING_ORDER_NOT_IN_ORDERBOOK)：
    若状态为PENDING/NEW → UNFREEZE

【阶段5: AccountWorker处理UNFREEZE】
  - 解冻资金/持仓 → 更新状态CANCELLED → WebSocket推送order_update
```

### 4.6 入金流程

```
【管理员入金】
HTTP → account_input CQ (DEPOSIT事件)
  → AccountWorker: balance += amount, available += amount
  → account_output CQ (BALANCE_CHANGE)
  → WebSocket推送 account_update
```

### 4.7 入金审批流程

```
【用户申请入金】
用户提交入金申请 → 写入 deposit_requests 表（状态: PENDING）

【管理员审批】
GET /api/admin/deposits/pending → 返回待处理列表

POST /api/admin/deposits/{id}/approve
  → 检查申请有效性
  → 写入 DEPOSIT 事件到 account_input CQ
  → 更新 deposit_requests 状态为 APPROVED

POST /api/admin/deposits/{id}/reject
  → 更新 deposit_requests 状态为 REJECTED
  → 记录拒绝原因
```

**入金申请状态**：

| 状态 | 说明 |
|------|------|
| PENDING | 待处理 |
| APPROVED | 已批准 |
| REJECTED | 已拒绝 |

**入金申请表 deposit_requests**：

| 字段 | 类型 | 说明 |
|------|------|------|
| requestId | long | 申请ID |
| userId | long | 用户ID |
| accountId | long | 账户ID |
| amount | long | 申请金额（分） |
| source | String | 资金来源（银行名称等） |
| reason | String | 申请原因 |
| status | enum | PENDING / APPROVED / REJECTED |
| operatorId | long | 操作管理员ID |
| operatorNote | String | 审批备注 |
| processedAt | long | 处理时间戳 |
| createdAt | long | 创建时间戳 |

---

## 5. 并发控制与幂等性

### 5.1 单线程串行设计

AccountWorker / MatchingEngine / ClearingWorker 各单线程串行处理，访问各自数据无需加锁。
HTTP线程主要读取内存（通过ConcurrentHashMap），唯一写入是orderStore.addOrder()（ConcurrentHashMap.putIfAbsent，线程安全）；其他写入仅通过CQ。

### 5.2 三层幂等模型

系统采用**三层幂等防护**，确保资金和订单在任何场景下不重复处理：

```
┌─────────────────────────────────────────────────────────────┐
│ 第一层：HTTP 层                                             │
│ IdempotencyService (idempotencyKey)                         │
│ 使用 computeIfAbsent 原子操作，防止重复下单                  │
├─────────────────────────────────────────────────────────────┤
│ 第二层：MatchingEngine                                      │
│ processed_order_ids (LinkedHashSet, 最新10,000个)            │
│ 防止恢复时重复写入 matching_output                           │
├─────────────────────────────────────────────────────────────┤
│ 第三层：AccountWorker（兜底）                               │
│ processed_trade_ids (account_settlement CQ，近10分钟窗口)    │
│ processed_settlement_ids (LinkedHashSet, 最新10,000个)       │
│ 防止重复结算和重复转账                                       │
└─────────────────────────────────────────────────────────────┘
```

### 5.3 幂等集合定义

| 幂等集合 | 维护者 | 来源 | 内存限制 | 用途 |
|---------|--------|------|---------|------|
| `idempotencyKey` | HTTP层 | 客户端传入 | TTL 5分钟 | 防重复下单 |
| `processed_order_ids` | MatchingEngine | matching_input CQ | 最新10,000个 | 防重复撮合 |
| `existing_trade_ids` | MatchingEngine（恢复时） | matching_output CQ | 最新10,000个 | replay_mode跳过已写入成交 |
| `existing_cancel_ids` | MatchingEngine（恢复时） | matching_output CQ | 最新10,000个 | replay_mode跳过已写入撤单 |
| `processed_trade_ids` | AccountWorker | account_settlement CQ | 近10分钟内的trade_id | 兜底防重复结算 |
| `processed_settlement_ids` | AccountWorker | account_settlement CQ | 最新10,000个 | 防重复结算转账 |
| `processed_unfreeze_ids` | AccountWorker | account_settlement CQ | 最新10,000个 | 防重复解冻 |

**说明**：
- ClearingWorker 正常运行时不做幂等检查（纯转发角色），恢复时增加TRADE幂等检查（见3.4.3节）；正常模式下幂等由 AccountWorker 兜底
- `processed_trade_ids` 正常使用10分钟窗口；若恢复时快照距当前>10分钟（深度恢复），读取全量恢复范围
- 所有幂等集合使用 LinkedHashSet 实现，FIFO 淘汰

### 5.4 volatile 变量

| 变量 | 类型 | 用途 | 更新者 |
|------|------|------|--------|
| currentPhase | TradingPhase | 当前交易阶段 | PhaseManager |
| throttled | boolean | 背压标志 | MatchingEngine |
| marketOpen | boolean | 市场开关 | PhaseManager |
| systemStatus | SystemStatus | 系统级状态 | LifecycleManager |
| shuttingDown | boolean | 优雅关闭标志 | LifecycleManager |

### 5.5 系统状态与 LifecycleManager

**SystemStatus 枚举**：

| 状态 | 含义 | HTTP响应 | 说明 |
|------|------|---------|------|
| STARTING | 系统启动中 | 错误码4005 | 初始化阶段 |
| RECOVERING | 系统恢复中 | 错误码4005 | 从快照+CQ恢复 |
| RUNNING | 正常运行 | 正常响应 | 接受并处理请求 |
| SHUTTING_DOWN | 关闭中 | 下单4006，撤单正常 | 停止接收新订单 |
| DEGRADED | 降级模式 | 部分功能受限 | 异常场景降级 |
| SNAPSHOTTING | 同步快照中 | 错误码4005 | 连续快照超时后触发 |

**状态转换**：
```
启动：STARTING → RECOVERING → RUNNING
关闭：RUNNING → SHUTTING_DOWN → 关闭完成
异常：RUNNING → DEGRADED →（恢复后）RUNNING
恢复失败：RECOVERING → DEGRADED
降级中关闭：DEGRADED → SHUTTING_DOWN
恢复中关闭：RECOVERING → SHUTTING_DOWN
同步快照：RUNNING → SNAPSHOTTING → RUNNING
```

**LifecycleManager 职责**：
- 系统级状态机管理（STARTING/RECOVERING/RUNNING/SHUTTING_DOWN/DEGRADED）
- 恢复协调：按顺序执行 AccountWorker→MatchingEngine→ClearingWorker→AccountWorker 四阶段恢复
- Worker 生命周期管理（创建、启动、停止）
- 与 PhaseManager 协调：PhaseManager 管理交易阶段，仅在 systemStatus==RUNNING 时生效
- 与 SOD 流程的职责划分：LifecycleManager 负责系统状态机和恢复协调，SOD 负责交易日相关初始化（T+1解锁、Instrument加载），Worker启动由两者协作完成

---

## 6. 持久化、快照与恢复

### 6.1 持久化策略

| 数据类型 | 存储位置 | 持久化方式 | 恢复方式 |
|---------|---------|-----------|---------|
| 账户状态 | 内存 | AccountWorker快照（每60秒） | 快照+CQ增量 |
| 持仓状态 | 内存 | AccountWorker快照（每60秒） | 快照+CQ增量 |
| 订单簿+活跃订单 | 内存 | MatchingEngine快照（每60秒） | 快照+CQ增量 |
| PENDING订单 | 内存 | 不存快照 | account_input CQ重放 |
| Instrument | 内存 | 不存快照 | H2 instruments表加载 |
| 事件日志 | CQ文件 | 磁盘顺序写（writeMode=SYNC） | 直接读取 |

### 6.2 快照机制概述

系统通过 SNAPSHOT 事件触发快照，各Worker独立维护快照文件，独立恢复。

| Worker | 输入CQ | 输出CQ | 快照文件 | 快照内容 |
|--------|--------|--------|---------|---------|
| AccountWorker | account_input | account_output, matching_input | account_snapshot_{index}.dat | accounts, positions, 各CQ索引 |
| MatchingEngine | matching_input | matching_output | matching_snapshot_{index}.dat | OrderBooks, matching索引 |
| ClearingWorker | matching_output | account_settlement | 无 | - |

快照文件结构和各Worker恢复逻辑详见 3.4 节。

#### 6.2.1 快照事件生成流程

```
SnapshotWorker 定时流程（每60秒）：

1. 写入 SNAPSHOT 事件到 account_input CQ（记录 cqIndex）
   说明：SNAPSHOT事件在Worker批处理中的行为——先处理SNAPSHOT之前的同批次普通事件，再执行快照。
2. 轮询等待 AccountWorker 处理完毕：
   - 检查 account_snapshot_{cqIndex}.dat 存在 + checksum 验证
   - 最长等待 5 秒
   - 超时 → 记录 P1 告警，标记"未确认"，不阻塞系统运行
3. 写入 SNAPSHOT 事件到 matching_input CQ（记录 cqIndex）
4. 轮询等待 MatchingEngine 处理完毕（同步骤2）

5. 超时递进处理：
   consecutiveTimeoutCount 计数
   - 第 1 次超时：P1 告警，跳过下一快照周期（等 2×interval），给 Worker 追赶时间
   - 第 2 次超时：P1 告警，继续跳过下一周期
   - 第 3 次超时：P0 告警，systemStatus = SNAPSHOTTING（暂停 HTTP，强制快照）
     → 强制快照完成后恢复 systemStatus = RUNNING
强制快照超时时间：SNAPSHOT_FORCE_TIMEOUT_MS（默认30秒）。若强制快照也超时 → systemStatus=DEGRADED（P0告警），提供管理员API /api/admin/system/force-snapshot-complete 退出SNAPSHOTTING状态。
   - 快照成功后重置计数器为 0
   - 若距上次有效快照 > 5 分钟：触发同步快照
6. 清理旧快照文件（保留最近 3 个）
```

### 6.3 四阶段恢复流程

```
1. AccountWorker 第一阶段：
   从H2加载Instrument → 加载account_snapshot → 生成幂等集合 → replay_mode=true
   → 重放account_input CQ（始终冻结，仅跳过已转发的matching_input写入）

2. MatchingEngine：
   加载matching_snapshot → 恢复OrderBook → 生成现有幂等集合（existing_trade_ids + existing_cancel_ids）
   → replay_mode=true → 重放matching_input CQ（状态检查+TRADE/CANCEL双去重）

3. ClearingWorker：
   读取clearing_position.dat → 回退MatchingEngine快照matching_output_last_index
   → 生成已处理TRADE幂等集合（clearing_processed_trade_ids + clearing_processed_cancel_ids）
   → 重放matching_output CQ（TRADE/CANCEL幂等检查，跳过已处理事件）

4. AccountWorker 第二阶段：
   生成幂等集合（processed_trade_ids + processed_settlement_ids + processed_unfreeze_ids）
   → 重放 account_settlement CQ → 执行结算转账（双层幂等检查）和解冻（幂等检查）
```

### 6.4 RPO/RTO 保证

详见 3.4.6 节。关键保证：RPO关键CQ writeMode=SYNC + account_output ASYNC + 快照原子rename + MD5校验 + 独立快照回退 + tmp残留检测 + 幂等集动态扩容 + ClearingWorker恢复幂等检查。

### 6.5 CQ文件损坏处理

| 场景 | 检测 | 处理 |
|------|------|------|
| CQ部分损坏 | 读取异常 | 记录位置→跳过损坏区域→发送告警→从快照恢复 |
| CQ完全损坏 | 文件无法打开 | P0告警→回退上一个有效快照→标记DEGRADED |
| CQ文件缺失 | 文件不存在 | 新交易日→正常初始化；当日重启→告警+从快照恢复 |

### 6.6 AsyncPersistence

**职责**：异步将成交和订单状态写入 H2 历史表，不阻塞主链路。1个独立线程。

**数据源**：matching_output CQ 中的 TRADE 和 ORDER_UPDATE 事件。

**写入策略**：100条/批或1秒间隔。TRADE→trades_history，ORDER_UPDATE(FILLED/CANCELLED)→orders_history。失败重试3次，仍失败记录告警并跳过，EOD 时补写。

---

## 7. 行情服务

### 7.1 五档行情生成

根据 MatchingEngine OrderBook 生成。买盘取最高5档，卖盘取最低5档，每档 volume 为该价格所有订单 remainingQty 之和，空档位用 price=0/volume=0 填充。

**快照结构**：symbol + updateTime + bidPrices[5] + bidVolumes[5] + askPrices[5] + askVolumes[5]

### 7.2 行情推送策略

| 行情类型 | 推送频率 | 说明 |
|---------|---------|------|
| 五档行情 | 每3秒 | 聚合推送 |
| K线数据 | 实时 | 成交时推送 |
| 订单簿Level2 | 可选 | 仅订阅用户 |

### 7.3 K线生成

**组件**：KlineAggregator，在 MatchingEngine 同一线程中运行。在 MatchingEngine 生成 TRADE 事件后、写入 matching_output CQ 前，直接在内存中处理 TRADE 数据。

**聚合逻辑**：每笔 TRADE 更新所有周期（1m/5m/15m/30m/1h/1d）的当前未闭合K线。OHLCV 按标准规则计算。周期边界时闭合上一K线，开启新K线。闭合K线写入 kline_history 表。

**启动初始化**：从 H2 kline_history 加载当日已闭合K线，当前未闭合K线从 matching_output CQ 增量构建。

### 7.4 AsyncPersistence

参见 6.6 节。

---

## 8. API设计

**重要约定**：所有 API 请求和响应的 JSON 字段值均为 **STRING** 类型。**REST API**：所有字段值为STRING类型（防止JS int64精度丢失）。**WebSocket**：int64字段（orderId/accountId/balance等）为STRING，其他数值字段用原生JSON数值（提高序列化效率）。

### 8.1 REST API 总览

| 模块 | 接口 | 方法 | 说明 |
|------|------|------|------|
| 认证 | /api/auth/login | POST | 用户登录 |
| 认证 | /api/auth/logout | POST | 用户登出 |
| 认证 | /api/auth/refresh | POST | 刷新令牌 |
| 认证 | /api/auth/register | POST | 用户注册（自动创建账户，余额为0） |
| 账户 | /api/account/info | GET | 账户信息 |
| 账户 | /api/account/positions | GET | 持仓列表 |
| 订单 | /api/orders | POST | 下单（同步返回orderId，异步成交） |
| 订单 | /api/orders/{orderId} | DELETE | 撤单（异步） |
| 订单 | /api/orders/active | GET | 当前委托 |
| 订单 | /api/orders/{orderId} | GET | 单个订单详情 |
| 历史 | /api/trades | GET | 历史成交 |
| 历史 | /api/account/cashflow | GET | 资金流水 |
| 历史 | /api/orders/history | GET | 历史订单（分页） |
| 行情 | /api/market/symbols | GET | 股票列表 |
| 行情 | /api/market/watchlist | GET/POST/DELETE | 自选股管理 |
| 行情 | /api/market/kline | GET | K线历史数据 |
| 管理 | /api/admin/deposit | POST | 管理员入金 |
| 管理 | /api/admin/deposit/correction | POST | 入金纠正（负数扣回） |
| 管理 | /api/admin/deposits | GET | 入金历史查询 |
| 管理 | /api/admin/instruments | POST | 添加/更新合约 |
| 管理 | /api/admin/system/recover | POST | 从DEGRADED恢复RUNNING |
| 管理 | /api/admin/account/{id}/recover | POST | 恢复SUSPENDED账户 |
| 管理 | /api/admin/system/status | GET | 系统状态查询 |
| 管理 | /api/admin/deposits/pending | GET | 待处理入金列表 |
| 管理 | /api/admin/deposits/{id}/approve | POST | 批准入金申请 |
| 管理 | /api/admin/deposits/{id}/reject | POST | 拒绝入金申请 |
| 管理 | /api/admin/api-keys | GET | 查询用户API Key列表 |
| 管理 | /api/admin/api-keys | POST | 创建API Key |
| 管理 | /api/admin/api-keys/{id} | DELETE | 删除API Key |
| 管理 | /api/admin/api-keys/{id}/secret | GET | 查看Secret Key（仅创建后首次） |

**权限说明**：
- `/api/admin/*`接口需要JWT中role=ADMIN，否则返回错误码1009（权限不足）
- users表role枚举值：ADMIN（管理员）、TRADER（普通交易员）
- Spring Security配置：`/api/admin/*`需要ADMIN role；其他API需要认证（任意role）

### 8.2 HTTP API 详细定义

**POST /api/orders 下单**

```json
// 请求
{
  "symbol": "600000",
  "side": "BUY",
  "orderType": "LIMIT",
  "price": "1050",              // 限价单必填，市价单传"0"
  "quantity": "100",
  "maxPrice": "1155",           // 可选，系统自动填充涨停价
  "minPrice": "945",            // 可选，系统自动填充跌停价
  "idempotencyKey": "uuid-v4"
}
// 响应
{ "code": "0", "message": "success",
  "data": { "orderId": "1234567890123456789", "status": "PENDING" },
  "timestamp": "1716460800000", "traceId": "trace-uuid" }
```

**DELETE /api/orders/{orderId} 撤单**（异步）

```json
// 响应
{ "code": "0", "message": "success",
  "data": { "orderId": "1234567890123456789", "status": "PENDING_CANCEL" },
  "timestamp": "1716460800000", "traceId": "trace-uuid" }
```

撤单结果通过 WebSocket `order_update` 推送（status=CANCELLED 或 CANCEL_REJECTED）。

**所有权验证**：所有订单操作（下单、撤单、查看订单详情）必须验证JWT提取的userId是否与order.userId匹配。不匹配时返回错误码3004。

**GET /api/account/info**

```json
// 响应
{ "code": "0", "message": "success",
  "data": { "accountId": "456", "userId": "123", "balance": "10000000",
            "available": "8500000", "frozen": "1500000",
            "marginFrozen": "0", "orderFrozen": "1500000",
            "unrealizedPnl": "50000", "status": "ACTIVE" },
  "timestamp": "1716460800000", "traceId": "trace-uuid" }
```

**POST /api/admin/deposit 管理员入金**

```json
// 请求
{ "userId": "123", "amount": "1000000", "reason": "手动入金" }
// 响应
{ "code": "0", "message": "success",
  "data": { "depositId": "...", "accountId": "456",
            "newBalance": "11000000", "newAvailable": "11000000" },
  "timestamp": "1716460800000", "traceId": "trace-uuid" }
```

**POST /api/admin/deposit/correction 入金纠正**

```json
// 请求
{ "depositId": "...", "amount": "-1000000", "reason": "纠正多入金错误" }
// 响应
{ "code": "0", "message": "success",
  "data": { "correctionId": "...", "accountId": "456",
            "newBalance": "10000000", "newAvailable": "10000000" },
  "timestamp": "1716460800000", "traceId": "trace-uuid" }
```

纠正金额为负数，从账户扣回。仅允许纠正当日入金。纠正后写入deposit_history和cashflow_history。

**POST /api/auth/login**

```json
// 请求: { "username": "trader01", "password": "xxx" }
// 响应: { "code": "0", "data": { "accessToken": "eyJ...", "refreshToken": "eyJ...", "accessExpireTime": "1716468000000", "refreshExpireTime": "1717072800000", "userId": "123", "accountId": "456", "username": "trader01", "role": "TRADER" } }
```

**POST /api/auth/register**

```json
// 请求: { "username": "trader01", "password": "xxx" }
// 响应: { "code": "0", "data": { "userId": "123", "accountId": "456" } }
```

**POST /api/auth/refresh**

```json
// 请求: { "refreshToken": "eyJ..." }
// 响应: { "code": "0", "data": { "accessToken": "eyJ...", "refreshToken": "eyJ...", "accessExpireTime": "1716468000000", "refreshExpireTime": "1717072800000" } }
```

**GET /api/orders/history** 参数: symbol?, side?, status?, startDate?, endDate?, page?, pageSize?; 响应: 分页订单列表

**GET /api/market/kline** 参数: symbol, interval(1m/5m/15m/30m/1h/1d), startTime?, endTime?, limit?; 响应: K线数组

**Token刷新规则**：refreshToken一次性使用（并发通过 computeIfAbsent 原子更新），accessToken过渡期仍可用，refreshToken有效期7天。

**历史查询通用参数**：所有历史查询接口支持以下参数：
- symbol（可选）：筛选指定证券代码
- startDate（可选）：起始日期，格式YYYYMMDD
- endDate（可选）：结束日期，格式YYYYMMDD
- page（可选）：页码，默认1
- pageSize（可选）：每页条数，默认20，最大100

### 8.3 WebSocket API

**连接端点**：`ws://host:port/ws?token=<jwt_token>`

**Channel 枚举**：

| Channel | 说明 | 订阅方式 |
|---------|------|---------|
| market_snapshot | 五档行情 | symbols array |
| orderbook | 订单簿Level2 | symbols array |
| kline | K线（1m/5m/15m/30m/1h/1d） | symbols + interval |
| order_update | 订单状态更新 | 自动（按用户） |
| trade_update | 成交更新 | 自动（按用户） |
| account_update | 账户资金变动 | 自动（按用户） |
| position_update | 持仓变动 | 自动（按用户） |

**消息类型**：subscribe / unsubscribe / subscribe_confirm / unsubscribe_confirm / ping / pong / error / market_snapshot / orderbook / kline / order_update / trade_update / account_update / position_update。

**error消息格式**：
```json
{ "type": "error", "messageId": "uuid-v4", "errorCode": "4003",
  "message": "品种不存在", "channel": "market_snapshot", "symbols": ["INVALID"] }
```
**订阅限制**：单连接最大订阅50个symbol，单连接每秒最多10条subscribe/unsubscribe消息（超出返回error）。

**心跳**：客户端每30秒 ping，服务端响应 pong。90秒未收到 ping → 断开（close code 1001）。

**messageId**：subscribe/unsubscribe/error/confirm 消息携带 messageId（UUID v4），服务端推送消息不需要。

**推送消息示例**（trade_update）：
```json
{ "type": "trade_update",
  "data": { "tradeId": "12345_67890", "orderId": "1234567890123456789",
            "symbol": "600000", "side": "BUY", "price": "1050",
            "quantity": "50", "amount": "52500", "commission": "158",
            "timestamp": "1716460800000" } }
```

**推送时机与佣金字段**：trade_update在TRADE事件生成时推送（MatchingEngine线程），此时佣金尚未由ClearingWorker计算。commission字段在TRADE阶段推送时为`"0"`（占位），SETTLEMENT完成后通过`account_update`（type=COMMISSION）补推实际佣金信息。前端应在收到account_update后更新成交记录的佣金显示。

**推送触发点与增量方式**：
- 买入成交结算后：quantity += qty, t1Locked += qty
- 卖出成交结算后：quantity -= qty, frozen -= qty
- 撤单解冻后：frozen -= qty, available += qty
- T+1解锁后（SOD）：t1Locked = 0, available += t1Locked
- 推送方式：仅推送变动的持仓（增量），非全量

**reason 枚举**：ORDER_FREEZE / ORDER_UNFREEZE / TRADE_BUY / TRADE_SELL / DEPOSIT / COMMISSION / LIQUIDATION / T1_UNLOCK

### 8.4 OpenAPI 设计

OpenAPI 供做市商、量化策略等程序化交易接入使用。

#### 8.4.1 认证方式

| 参数 | 说明 |
|------|------|
| X-API-Key | API Key（ak_xxx格式） |
| X-Timestamp | 请求时间戳（毫秒），允许误差±5分钟 |
| X-Signature | HMAC-SHA256签名 |

**签名计算**：
```
signature = HMAC-SHA256(secretKey, timestamp + method + path + body)
```

**示例**：
```
timestamp = 1716460800000
method = POST
path = /openapi/orders
body = {"symbol":"600000","side":"BUY","price":"1050","quantity":"100"}

signString = "1716460800000POST/openapi/orders{"symbol":"600000","side":"BUY","price":"1050","quantity":"100"}"
signature = HMAC-SHA256(secretKey, signString)
```

#### 8.4.2 OpenAPI 接口列表

| 接口 | 方法 | 权限 | 说明 |
|------|------|------|------|
| /openapi/ws | WebSocket | READ_ONLY | 行情订阅（market_snapshot, kline, orderbook） |
| /openapi/account | GET | READ_ONLY | 账户信息 |
| /openapi/positions | GET | READ_ONLY | 持仓列表 |
| /openapi/orders/active | GET | READ_ONLY | 当前委托 |
| /openapi/orders/history | GET | READ_ONLY | 历史订单 |
| /openapi/trades | GET | READ_ONLY | 历史成交 |
| /openapi/orders | POST | TRADING | 下单 |
| /openapi/orders/{orderId} | DELETE | TRADING | 撤单 |
| /openapi/market/symbols | GET | READ_ONLY | 股票列表 |
| /openapi/market/kline | GET | READ_ONLY | K线历史 |

#### 8.4.3 WebSocket 订阅限制

| 限制项 | 值 | 说明 |
|--------|-----|------|
| 单连接最大订阅symbol数 | 50 | 防止资源滥用 |
| subscribe/unsubscribe频率 | 10条/秒 | 防止消息洪泛 |
| 连接空闲超时 | 5分钟 | 无活动自动断开 |
| 心跳间隔 | 30秒 | 客户端发起ping |

#### 8.4.4 错误码补充

| 错误码 | 含义 | 场景 |
|--------|------|------|
| 1010 | API Key无效 | X-API-Key不存在或已禁用 |
| 1011 | 签名验证失败 | 签名不匹配或时间戳过期 |
| 1012 | 权限不足 | READ_ONLY Key访问TRADING接口 |
| 1013 | 品种权限不足 | 尝试交易未授权的品种 |

---

## 9. 前端设计

### 9.1 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | React 18 + TypeScript |
| 构建 | Vite |
| UI | Ant Design 5.x |
| 状态 | Zustand |
| 图表 | lightweight-charts（TradingView开源） |

### 9.2 页面布局

```
┌─────────────────────────────────────────────────────────────────┐
│  Header: Logo | 用户信息 | 系统状态                              │
├─────────────────────────────────────────────────────────────────┤
│  Sidebar  │  Main Content                                       │
│  ├行情    │  ┌─────────────────────────────────────────────┐   │
│  ├交易    │  │  K线图  │  五档行情 | 下单面板                │   │
│  ├订单    │  ├─────────────────────────────────────────────┤   │
│  ├持仓    │  │  订单簿 (可选)                                │   │
│  └历史    │  └─────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 9.3 组件与状态设计

**组件树**：App > Header > Sidebar + MainContent (TradingViewChart + OrderBookPanel + OrderPanel) + BottomTabs (OrderTable + TradeHistory + Cashflow + PositionManager)

**Zustand Store定义**：
- useAuthStore: token, userId, role, login(), logout()
- useMarketStore: symbols[], currentSymbol, marketSnapshots{}, klines{}, subscribe(), unsubscribe()
- useOrderStore: activeOrders[], submitOrder(), cancelOrder()
- useAccountStore: accountInfo, positions[], cashflow[]
- usePositionStore: positions[], updateOnPriceChange()（接收market_snapshot时计算unrealizedPnl）

**实时P&L计算**：WebSocket market_snapshot推送currentPrice → usePositionStore.updateOnPriceChange() 计算 unrealizedPnl = (currentPrice - avgPrice) × quantity

**错误处理策略**：WebSocket断线自动重连（3秒间隔，最多5次），HTTP请求失败重试1次，错误码映射到用户提示。

---

## 10. 性能分析

### 10.1 延迟分解

HTTP返回延迟（冻结完成后即返回orderId）：HTTP接收0.1-0.3ms + 参数校验0.2-0.5ms + CQ写入0.05-0.1ms + 队列等待 + AccountWorker冻结0.1-0.2ms + 唤醒0.05ms。

AccountWorker单次操作：CQ读取~5μs + 内存更新~0.5μs + CQ写入~5μs + 唤醒~5μs ≈ **15-20μs**。

端到端延迟（下单→成交回报）：HTTP(0.5ms) + AccountWorker(1.0ms) + MatchingEngine(2.0ms) + ClearingWorker(1.5ms) + AccountWorker第二阶段(1.0ms) + WebSocket(0.5ms) ≈ **P99 ~7ms < 15ms** ✓

### 10.2 吞吐量分析

| 组件 | 逐个处理 | 批量处理 | 说明 |
|------|---------|---------|------|
| AccountWorker | 65K TPS | 100K+ TPS | 内存为主 |
| MatchingEngine | 65K TPS | 100K+ TPS | OrderBook撮合 |
| ClearingWorker | 50K TPS | 80K+ TPS | 成交×2 SETTLEMENT事件 |
| **系统整体** | 40-50K TPS | **100K TPS（峰值）/ 80K TPS（持续）** | ClearingWorker为瓶颈（持续负载下约80K，峰值可达100K） |

**注**：以上分析基于纯内存/CPU处理延迟。实际吞吐受 CQ SYNC 模式磁盘 fsync 性能约束，需在目标 SSD 上进行基准测试验证。若 SYNC 成为瓶颈，account_output CQ（仅 WebSocket 推送用途）可降级为非 SYNC 模式（不影响 RPO=0）。

### 10.3 批量大小选择

| 批量大小 | HTTP平均等待 | 吞吐 | 推荐 |
|---------|-------------|------|------|
| 20 | ~0.3ms | 80K | 低延迟 |
| **50** | **~0.75ms** | **100K** | **推荐** |
| 100 | ~1.5ms | 120K | 吞吐优先 |

### 10.4 幂等机制性能影响

| 组件 | 幂等存储 | 查询延迟 | 内存占用 |
|------|---------|---------|---------|
| MatchingEngine | 内存Set（10,000个） | ~0.5μs | ~500KB |
| AccountWorker | 内存Set（10,000个） | ~0.5μs | ~500KB |

对吞吐影响 **< 5%**。ClearingWorker 不做幂等检查，无额外开销。

### 10.5 内存使用

| 组件 | 预估内存(Heap) | 预估内存(Off-heap) |
|------|---------------|-------------------|
| JVM堆(InMemoryStore+幂等集合等) | 4-6GB | - |
| Chronicle Map快照 | - | 2-4GB |
| 内存OrderBook | 500MB-1GB | - |
| CQ缓冲 | - | 1-2GB |
| 幂等集合 | ~1MB | - |
| **Heap总计** | **5-7GB** | - |
| **Off-heap总计** | - | **3-6GB** |
| **总计** | **8-13GB** | - |

注：JVM堆配置10GB（ZGC 70%利用率下可用7GB，匹配5-7GB使用量），留14GB给off-heap（CM快照2-4GB + CQ缓冲1-2GB + Netty direct buffer 1-2GB + OS/H2 1-2GB）。添加-XX:MaxDirectMemorySize=2g限制Netty off-heap使用。

### 10.6 GC影响

ZGC（Generational）：暂停<1ms，对P99延迟可忽略。ZGC为分配驱动GC，非IHOP触发。使用SoftMaxHeapSize控制堆使用上限，超过时主动触发GC。

---

## 11. 部署与运维

### 11.1 JVM参数

```bash
java -Xms10g -Xmx10g -XX:+UseZGC -XX:+ZGenerational \
     -XX:SoftMaxHeapSize=8g -XX:ZAllocationSpikeTolerance=2 \
     -XX:+UseStringDeduplication -XX:+AlwaysPreTouch \
     -XX:MaxDirectMemorySize=2g \
     -jar hft-trading.jar
```

### 11.2 Spring Boot配置

```yaml
server:
  port: 8080
  shutdown: graceful
spring:
  threads.virtual.enabled: true
  lifecycle.timeout-per-shutdown-phase: 30s
trading:
  chronicle.base-path: data/chronicle/
  h2.database-path: data/h2/trading
  snapshot.interval-ms: 60000
  batch.size: 50
  clearing.batch-size: 100
  batch.timeout-ms: 1
  idempotent-set-max-size: 10000
  max-replay-trade-ids: 10000
  future.timeout-ms: 5000
  jwt.access-token-expiration: 7200000
  jwt.refresh-token-expiration: 604800000
  rate-limit.order-tps: 100
  rate-limit.query-qps: 1000
  ws.idle-timeout-ms: 300000
  ws.heartbeat-ms: 30000
```

### 11.3 Chronicle Queue 配置

| CQ名称 | blockSize | rollCycle | writeMode | 说明 |
|--------|-----------|-----------|-----------|------|
| account_input | 128MB | HOURLY | SYNC | 事件量大 |
| account_output | 64MB | HOURLY | ASYNC | 非RPO关键，仅WebSocket推送 |
| matching_input | 64MB | HOURLY | SYNC | - |
| matching_output | 64MB | HOURLY | SYNC | - |
| account_settlement | 64MB | HOURLY | SYNC | - |

**RPO=0 关键配置**：RPO关键CQ使用writeMode=SYNC（每条消息刷盘）+ epochOnRoll=true + sparseFile=false。account_output CQ使用ASYNC（不参与恢复，非RPO关键）。CQ roll周期错开避免同时roll：account_input整点，matching_input:15分，matching_output:30分，account_settlement:45分。

### 11.4 目录结构

```
data/
├── chronicle/
│   ├── account_input/      # 按小时滚动：20260603-09.cq4
│   ├── account_output/
│   ├── matching_input/
│   ├── matching_output/
│   └── account_settlement/
├── snapshots/
│   ├── account_snapshot_*.dat + .checksum
│   ├── matching_snapshot_*.dat + .checksum
│   └── clearing_position.dat          # ClearingWorker处理位置记录
└── h2/
    └── trading.mv.db
```

### 11.5 告警级别与渠道

| 级别 | 含义 | 响应要求 | 渠道 |
|------|------|---------|------|
| P0 | 阻塞性故障，影响核心交易/数据一致性 | 立即响应，5分钟内 | Prometheus AlertManager → Webhook |
| P1 | 需修复，影响非核心功能 | 30分钟内 | AlertManager + ERROR日志 |
| P2 | 建议优化 | 工作时间 | WARN日志 |

**告警格式**：`{级别} [{时间}] [{组件}] {错误码}: {详细信息} — 建议操作: {建议}`

### 11.6 关键指标

| 指标 | 告警阈值 | 时间窗口 | 级别 |
|------|---------|---------|------|
| JVM heap usage | > 75% of Xmx | 30s | P1 |
| JVM heap usage | > 85% of Xmx | 10s | P0 |
| GC pause duration | > 5ms 单次 | - | P1 |
| Off-heap memory | > 12GB | 30s | P1 |
| Disk free space | < 100GB | 30s | P0 |
| Disk free space | < 200GB | 30s | P1 |
| CQ队列深度 | > 50 | 5s持续 | P1 |
| CQ队列深度 | > 200 | 即时 | P0 |
| HTTP等待超时率 | > 0.01% | 10s窗口 | P1 |
| AccountWorker延迟P99 | > 5ms | 10s窗口 | P1 |
| MatchingEngine延迟P99 | > 10ms | 10s窗口 | P1 |
| ClearingWorker延迟P99 | > 10ms | 10s窗口 | P1 |
| 快照耗时 | > 5s | 每次快照 | P1 |
| 连续快照超时 | ≥ 3次 | 累计 | P0 |
| AsyncPersistence lag | > 10s落后 | 30s | P1 |

### 11.7 日志规范

```
[INFO] Order submitted: orderId=123, symbol=600000, side=BUY, price=1050, qty=100
[INFO] Account frozen: accountId=456, frozenAmount=105032, available=500000
[INFO] Order matched: orderId=123, tradeId=789, price=1049, qty=100
[INFO] Settlement completed: tradeId=789, buyer=456, seller=789, amount=104900
```

---

## 12. 错误码定义

### 12.1 错误码表

| 错误码 | 含义 | 场景 |
|--------|------|------|
| 0 | 成功 | - |
| 1001 | 参数无效 | 必填字段缺失/格式错误 |
| 1002 | 请求超时 | CompletableFuture超时 |
| 1003 | 请求处理中 | 幂等键PROCESSING |
| 1004 | Token无效 | JWT格式/签名错误 |
| 1005 | Token过期 | access token过期 |
| 1006 | Token已失效 | refreshToken已使用/撤销 |
| 1007 | 请求过于频繁 | 触发限流 |
| 1008 | 服务繁忙 | 负载过高 |
| 1009 | 权限不足 | 非管理员访问ADMIN接口 |
| 2001 | 余额不足 | available < 冻结金额 |
| 2002 | 持仓不足 | position.available < 卖出数量 |
| 2003 | 账户状态异常 | SUSPENDED/LIQUIDATING |
| 2004 | 账户不存在 | accountId无效 |
| 2005 | 入金纠正失败 | depositId不存在/非当日/金额超限 |
| 2006 | 账户余额为负 | balance < 0 |
| 3001 | 订单不存在 | orderId无效 |
| 3002 | 订单状态不允许 | 已撤销/已成交重复撤销 |
| 3003 | 订单不存在于订单簿 | OrderBook无此订单 |
| 3004 | 无权操作此订单 | 请求userId与订单userId不匹配 |
| 4001 | 交易阶段不允许 | 非连续竞价下单 |
| 4002 | 涨跌停限制 | 价格超范围 |
| 4003 | 品种不存在 | symbol无效 |
| 4004 | 自成交风险 | 存在己方反向订单 |
| 4005 | 非交易日/系统暂停 | 非交易日或系统未就绪 |
| 4006 | 系统关闭中 | 优雅关闭期间拒绝新订单 |
| 5001 | 数据写入失败 | CQ/H2写入异常 |
| 9999 | 系统异常 | 未知错误 |

### 12.2 错误响应格式

```json
{ "code": "2001", "message": "余额不足", "data": null,
  "timestamp": "1716460800000", "traceId": "abc123def456" }
```

---

## 13. 业务规则

### 13.1 涨跌停限制

- 涨停价 = prevClose × (1 + limitUpRatio)，跌停价 = prevClose × (1 - limitDownRatio)
- 价格超出范围 → 拒绝（4002）
- 价格需为 minPriceIncrement 的整数倍（A股为1分）
- 市价单系统自动填充 maxPrice=涨停价 / minPrice=跌停价

### 13.2 自成交预防

HTTP下单前校验：检查同一账户是否存在对手方订单且价格交叉 → 拒绝（4004）。撮合引擎不校验自成交（已知设计权衡：HTTP层检查与撮合之间存在毫秒级竞争窗口，概率极低。如需严格防自成交，MatchingEngine需增加同一accountId的买卖订单交叉检查）。

### 13.3 T+1规则

- 买入成交：pos.t1Locked += quantity
- 次日 SOD：pos.available += pos.t1Locked，pos.t1Locked = 0。异常处理：若t1Locked > |quantity|，P0告警 + 标记账户SUSPENDED + 修正t1Locked = |quantity| - available - frozen
- EOD 无需额外处理

### 13.4 佣金计算

| 品种 | 计算方式 | 取整 |
|------|---------|------|
| 股票 | comm = (amount × 3) / 10000 | floor（整数除法截断） |
| 期货 | comm = commissionPerLot × lots | - |

- 无最低佣金限制
- 每次成交独立计算，部分成交累计佣金等于或低于一次性成交佣金（floor具有亚可加性：floor(a)+floor(b) ≤ floor(a+b)），差异可忽略
- 佣金在结算时从成交金额中扣除
- 买方冻结时预估佣金同公式，清算时按实际成交金额重新计算
- 买方冻结预估佣金使用 ceil（向上取整，保证冻结金额覆盖实际结算成本），实际佣金用 floor（向下取整保护客户）。冻结时 ceil + 结算时 floor 保证 refund ≥ 0（数学证明：对任意x，ceil(x) ≥ floor(x)，冻结佣金≥结算佣金；结合ceil按比例分配，frozenAmountUsed ≥ tradeAmount + floor(settlementCommission)）。

### 13.5 风控与强平

**股票账户**：仅检查 balance < 0（标记 NEGATIVE_BALANCE，禁止交易）。

**期货/期权账户**（RiskMonitor，每1秒轮询）：
- riskRatio = marginFrozen / (balance + unrealizedPnl)
- 若 netAssets ≤ 0：触发强平
- ≥ 100%：触发强平 → 写入 MARKET 卖单到 account_input CQ（isSystemOrder=true）
- ≥ 80%：WebSocket 预警
- RiskMonitor 线程异常 → P0告警，系统继续运行但监控降级

---

## 14. 生命周期管理

### 14.1 交易阶段定义

| 阶段 | 时段 | 允许操作 | 拒绝下单错误码 | PHASE_CHANGE事件 |
|------|------|---------|---------------|-----------------|
| CLOSED | 09:15前, 15:00后, 非交易日 | 仅查询 | 4001(收盘) / 4005(非交易日) | 写入CQ |
| PRE_MARKET | 09:15-09:30 | 仅查询 | 4005 | 不写入（纯volatile） |
| CONTINUOUS_TRADING | 09:30-11:30, 13:00-15:00 | 全部操作 | - | 写入CQ |
| LUNCH_BREAK | 11:30-13:00 | 仅查询 | 4001 | 不写入（纯volatile） |

**转换表**（PhaseManager 驱动）：
- 09:15 → volatile phase = PRE_MARKET（当前版本不支持集合竞价）
- 09:30 → 写入 PHASE_CHANGE(CONTINUOUS_TRADING) 到 account_input CQ
- 11:30 → volatile phase = LUNCH_BREAK
- 13:00 → 写入 PHASE_CHANGE(CONTINUOUS_TRADING) 到 account_input CQ
- 15:00 → 写入 PHASE_CHANGE(CLOSED) 到 account_input CQ

### 14.2 SOD流程

```
1. 判断交易日
   - CQ文件检查：是否存在当日 account_input/*_{date}*.cq4
   - 交易日历校验：查询H2 trading_calendar
   - 快照日期比对：读取最新快照 create_time
   - 若不存在当日CQ文件且快照日期 < 当日 → 新交易日启动
   - 若存在当日CQ文件或快照日期 == 当日 → 当日重启恢复
   - 若不存在当日CQ文件但快照日期 == 当日 → 系统异常（P0告警+人工介入，CQ可能被误删）

2. 新交易日启动
   2.1 加载上一交易日最终快照（accounts, positions）
   2.2 孤立冻结检测与修复：
       遍历所有账户和持仓，检测孤立冻结：
       - 账户：若 orderFrozen > 0 但 InMemoryOrderStore 中无对应活跃订单（PENDING/NEW/PARTIAL_FILLED）
         → orderFrozen → 0, frozen -= orderFrozen, available += orderFrozen, 记录 P0 告警
       - 持仓：若 frozen > 0 但无对应活跃卖单
         → frozen → 0, available += frozen, 记录 P0 告警
       原因：EOD撤单超时可能导致冻结资金/持仓未解冻，且SOD不重放上一日CQ
   2.3 T+1 解锁：unlockT1Positions()
   2.4 从H2加载Instrument → 写入INSTRUMENT_INIT到account_input和matching_input CQ
   2.5 清空订单簿和订单（上一日订单已在EOD撤销）
   2.6 清空幂等集合
   2.7 启动Worker线程，PhaseManager.waitForMarketOpen()

3. 当日重启恢复
   recoverFromSnapshotAndCQ()（见6.3节四阶段恢复）
   恢复完成后，PhaseManager根据当前系统时间设置交易阶段：
   - 09:15-09:30 → PRE_MARKET（返回4005）
   - 09:30-11:30 → CONTINUOUS_TRADING
   - 11:30-13:00 → LUNCH_BREAK（返回4001）
   - 13:00-15:00 → CONTINUOUS_TRADING
   - 其他时段 → CLOSED（返回4001或4005，根据是否交易日判断）

4. 启动时刻边界处理
   - 09:15启动 → CLOSED阶段，错误码4005，等待09:30
   - 11:30启动 → 恢复期间返回4005（系统STARTING/RECOVERING），恢复完成后PhaseManager设为LUNCH_BREAK返回4001，等待13:00
   - 15:00后启动 → CLOSED阶段，等待下一交易日
   - 非交易日启动 → CLOSED阶段，PhaseManager每小时检查交易日历
```

### 14.3 EOD流程

```
1. 设置 systemStatus=SHUTTING_DOWN（下单返回4006，撤单正常）
2. PhaseManager 写入 PHASE_CHANGE(CLOSED) → account_input CQ
3. 等待现有HTTP请求完成（最长3秒）
4. 等待队列清空（最长5秒）
5. 撤销所有未成交订单（遍历InMemoryOrderStore中status∈{PENDING,NEW,PARTIAL_FILLED}，写入CANCEL_ORDER到account_input CQ）
   - 所有 CANCEL_ORDER 统一写入 account_input CQ，由 AccountWorker 转发 matching_input CQ
   - MatchingEngine 处理：在 OrderBook → CANCEL_SUCCESS→UNFREEZE；不在 → CANCEL_REJECT→UNFREEZE
5. 等待撤单处理完成（轮询模式，每500ms检查，maxWait = max(10s, pendingCount×100ms)）
   超时→告警，未处理的CANCEL_ORDER保留在CQ中供下次恢复
6. 最终快照（snapshotWorker.forceSnapshot()）
7. 计算当日盈亏，写入H2历史表（含AsyncPersistence失败补写）
8. 关闭Worker线程、CQ、H2
9. 归档CQ文件到 data/archive/{YYYYMMDD}/
   写入 archive_manifest.dat（清单），归档失败不清理原文件
10. 生成日终报告
```

**archive_manifest.dat 结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| archiveDate | String | 归档日期（YYYYMMDD） |
| archiveTime | long | 归档时间戳 |
| cqFiles | List\<String\> | 应归档的CQ文件列表 |
| snapshotFiles | List\<String\> | 应归档的快照文件列表 |
| archivedFiles | List\<String\> | 实际已归档的文件列表 |
| failedFiles | List\<String\> | 归档失败的文件列表 |
| checksum | String | 清单文件MD5校验和 |

**SOD归档完整性检查**：读取上个交易日 manifest → 比对文件 → 缺失文件从 data/chronicle/ 残留补归档（按日期前缀匹配）。

### 14.4 SOD恢复场景

| 场景 | CQ文件状态 | 恢复方式 |
|------|-----------|---------|
| 新交易日启动 | 不存在当日CQ | 从上一交易日快照+T+1解锁 |
| 当日重启 | 已存在当日CQ | 独立快照+CQ增量（RPO=0） |
| Crash重启 | 已存在当日CQ | 独立快照+CQ增量（RPO=0） |

### 14.5 优雅关闭流程

```
1. 设置 shuttingDown=true, systemStatus=SHUTTING_DOWN
   下单返回4006，撤单正常处理

2. 停止HTTP接收新请求

3. 等待现有HTTP请求完成（最长3秒）

4. 等待CQ队列清空（最长30秒）：
   matching_input、matching_output、account_settlement

5. 写入SNAPSHOT到account_input和matching_input CQ

6. 等待Worker处理SNAPSHOT（最长5秒）

7. 停止所有Worker线程：
   accountWorker.stop() → matchingEngine.stop() → clearingWorker.stop()

8. 关闭CQ、H2、WebSocket

关闭时间线：T+0s(标志) → T+3s(队列清空) → T+33s(SNAPSHOT) → T+38s(Worker停) → T+40s(完成)
每个阶段均有超时保护，超时记录未处理事件数量。
```

### 14.6 ClearingWorker 位置记录

为限制 ClearingWorker 恢复时的 CQ 重放范围，ClearingWorker 每处理 10,000 条 matching_output CQ 事件后，将当前处理位置（matching_output_cq_index）写入 `data/snapshots/clearing_position.dat`（原子 tmp→rename）。

恢复时 ClearingWorker 从此位置+1 开始重放，而非从 MatchingEngine 快照位置开始，减少重放量。此文件非快照，仅记录位置索引。

---

## 15. 设计决策记录

以下为 V12 设计过程中确认的关键决策，记录于此供后续参考。

### 15.1 校验和算法：MD5

**决策**：继续使用 MD5。场景为快照文件完整性校验（防写入损坏），非安全场景。MD5 计算速度快、校验文件仅32字符。后续如有安全需求可升级 SHA-256。

### 15.2 ClearingWorker 位置记录

**决策**：ClearingWorker 不生成完整快照，但每10,000条事件记录一次处理位置到 `clearing_position.dat`。此文件仅含一个 long 值（matching_output 处理位置），写入开销可忽略。恢复时从此位置重放，限制 RTO。详见 14.6 节。

### 15.3 processed_trade_ids 窗口：时间+数量双限

**决策**：保持 10 分钟时间窗口 + 10,000 条数量上限的双重限制。正常恢复（快照≤60秒前）时间窗口足够；深度恢复（快照>10分钟前）自动切换为全量恢复范围（从 account_settlement_index 到 latest 的全部 trade_id）。此逻辑已在 3.4.1/5.3 节描述。

### 15.4 H2 数据库：首版保持

**决策**：首版使用 H2 嵌入式数据库。H2 在单节点部署下足够，AsyncPersistence 采用批量写入降低并发压力。后续如需迁移 PostgreSQL，仅需替换数据源配置和 SQL 方言，内存模型和 CQ 架构不受影响。

### 15.5 多节点扩展：暂无计划

**决策**：当前架构为严格的单节点设计，无近期多节点扩展计划。CQ 为单 JVM 本地队列，如需多节点需引入分布式消息队列（如 Kafka）替代。该重构不在当前版本范围内。

### 15.6 集合竞价：首版不支持

**决策**：首版仅支持连续竞价（09:30-11:30, 13:00-15:00）。PRE_MARKET（09:15-09:30）和集合竞价（09:15-09:25）标记为后续版本功能。当前 PRE_MARKET 阶段 HTTP 返回 4005，仅允许查询。

### 15.7 交易品种：首版聚焦 A 股现货

**决策**：首版仅支持 A 股现货交易（type=STOCK）。期货（FUTURE）和期权（OPTION）的数据模型、风控逻辑已在设计中预留，但首版不启用。相关字段（marginFrozen、riskRatio、commissionPerLot）初始化为 0 或保留默认值。

### 15.8 外部行情：首版使用内部订单簿

**决策**：五档行情完全来自 MatchingEngine 内部订单簿。后续版本可接入外部行情源（如新浪）作为展示参考，但不参与撮合定价。此架构已支持——外部队列仅需一个额外的 MarketDataFeed 组件读取外部数据并写入对应推送通道。

### 15.9 writeMode=SYNC 吞吐量：上线前基准测试

**决策**：RPO关键CQ（account_input/matching_input/matching_output/account_settlement）保持writeMode=SYNC。account_output CQ使用ASYNC（不参与恢复，非RPO关键）。SSD要求：NVMe，≥300K fsync/s。CQ roll周期错开避免同时roll。上线前在目标环境基准测试验证100K TPS。

### 15.10 佣金取整：floor 统一

**决策**：所有品种统一使用 floor（整数除法截断）作为佣金取整方式。买方冻结金额使用 ceil（向上取整）以保证冻结资金充足。此策略对股票（万分之三）足够精确，期货上线时如有特殊需求可通过 Instrument.commissionRate 精度调整（如万分比升为十万分比）。

---

## 附录

### A. 配置参数表

| 参数 | 默认值 | 说明 |
|------|--------|------|
| chronicle.base-path | data/chronicle/ | Chronicle文件基础路径 |
| h2.database-path | data/h2/trading | H2数据库路径 |
| jwt.access-token-expiration | 7200000 | Access Token过期时间（ms） |
| jwt.refresh-token-expiration | 604800000 | Refresh Token过期时间（ms） |
| jwt.secret-key | （从环境变量TRADING_JWT_SECRET读取） | JWT签名密钥，不写入配置文件，从环境变量加载 |
| jwt.secret-key-rotation | 首版使用固定密钥 | 密钥轮换策略：首版固定密钥，后续版本支持旧密钥过渡期验证 |
| rate-limit.order-tps | 100 | 单用户下单限流 |
| rate-limit.query-qps | 1000 | 单用户查询限流 |
| snapshot.interval-ms | 60000 | 快照间隔（ms） |
| batch.size | 50 | AccountWorker批量大小 |
| clearing.batch-size | 100 | ClearingWorker批量大小 |
| batch.timeout-ms | 1 | 批量读取超时（ms） |
| future.timeout-ms | 5000 | CompletableFuture超时（ms） |
| ws.idle-timeout-ms | 300000 | WebSocket空闲超时（ms） |
| ws.heartbeat-ms | 30000 | WebSocket心跳间隔（ms） |
| idempotent-set-max-size | 10000 | 幂等集合最大容量 |
| max-replay-trade-ids | 10000 | 恢复时CQ最大读取数 |

### B. 技术栈

| 组件 | 技术 |
|------|------|
| Java | Java 21 LTS |
| Spring Boot | 3.x + 虚拟线程 |
| 消息队列 | Chronicle Queue |
| 快照存储 | Chronicle Map |
| 内存存储 | ConcurrentHashMap |
| 数据库 | H2 (磁盘模式) |
| GC策略 | ZGC (Generational) |
| 前端框架 | React 18 + TypeScript + Vite |
| 前端图表 | lightweight-charts |
| 前端UI | Ant Design 5.x |
| 前端状态 | Zustand |
| 监控 | Prometheus + Grafana |
| 日志 | ELK Stack |
| 链路追踪 | Jaeger |

### C. H2 数据库 Schema

#### C.1 instruments（证券信息）

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | VARCHAR(10) PK | 证券代码 |
| symbol_code | INT | 数值代码 |
| name | VARCHAR(50) | 名称 |
| type | VARCHAR(10) | STOCK/FUTURE/OPTION |
| prev_close | BIGINT | 前收盘价（分） |
| limit_up_ratio | DOUBLE | 涨停比例 |
| limit_down_ratio | DOUBLE | 跌停比例 |
| lot_size | BIGINT | 每手数量 |
| min_price_increment | BIGINT | 最小变动单位（分） |
| commission_rate | INT | 佣金率（万分比） |
| commission_per_lot | BIGINT | 每手佣金（期货专用） |
| stamp_duty_rate | INT | 印花税率（预留） |
| transfer_fee_rate | INT | 过户费率（预留） |
| update_time | BIGINT | 更新时间戳 |

#### C.2 trading_calendar（交易日历）

trade_date VARCHAR(8) PK | is_trading_day BOOLEAN

#### C.3 trading_sessions（交易时段）

id INT PK | phase VARCHAR(30) | start_time TIME | end_time TIME

#### C.4 users（用户）

user_id BIGINT PK | username VARCHAR(100) UNIQUE | password_hash VARCHAR(255) | role VARCHAR(20) | created_at BIGINT

#### C.5 accounts（账户）

account_id BIGINT PK | user_id BIGINT FK → users | created_at BIGINT

#### C.6 trades_history（历史成交）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO PK | 自增主键 |
| trade_id | VARCHAR(50) | 成交ID：takerOrderId_makerOrderId |
| order_id | BIGINT | 订单ID |
| account_id | BIGINT | 账户ID |
| user_id | BIGINT | 用户ID |
| symbol | VARCHAR(10) | 证券代码 |
| side | VARCHAR(5) | BUY / SELL |
| order_type | VARCHAR(10) | LIMIT / MARKET / FAK / FOK |
| price | BIGINT | 成交价格（分） |
| order_price | BIGINT | 原订单限价（分） |
| quantity | BIGINT | 成交数量 |
| amount | BIGINT | 成交金额（分） |
| commission | BIGINT | 佣金（分） |
| frozen_amount_used | BIGINT | 本次使用的冻结金额（分） |
| trade_time | BIGINT | 成交时间戳 |
| trade_date | VARCHAR(8) | 交易日期 |

索引：(trade_id), (order_id), (account_id, trade_date), (symbol, trade_date)

#### C.7 orders_history（历史订单）

order_id BIGINT PK | account_id BIGINT | user_id BIGINT | symbol VARCHAR(10) | side VARCHAR(5) | order_type VARCHAR(10) | price BIGINT | quantity BIGINT | filled_quantity BIGINT | status VARCHAR(20) | commission BIGINT | frozen_amount BIGINT | cancel_reason VARCHAR(100) | idempotency_key VARCHAR(50) | create_time BIGINT | update_time BIGINT | trade_date VARCHAR(8)

索引：(account_id, trade_date), (user_id, trade_date), (symbol, trade_date, status)

#### C.8 cashflow_history（资金流水）

id BIGINT AUTO PK | account_id BIGINT | amount BIGINT | type VARCHAR(30) | reason VARCHAR(100) | trade_id VARCHAR(50) | order_id BIGINT | created_at BIGINT

**type枚举值**：DEPOSIT（入金）、TRADE（交易）、COMMISSION（佣金）、FREEZE（冻结）、UNFREEZE（解冻）、T1_UNLOCK（T+1解锁）
**type与reason的关系**：type为粗分类（用于查询筛选），reason为细描述（用于具体记录）。例如：type=TRADE, reason=TRADE_BUY。

索引：(account_id, created_at)

#### C.9 watchlists（自选股）

user_id BIGINT FK → users | symbol VARCHAR(10) | created_at BIGINT

主键：(user_id, symbol)

#### C.10 kline_history（K线历史）

id BIGINT AUTO PK | symbol VARCHAR(10) | interval VARCHAR(5) | open BIGINT | high BIGINT | low BIGINT | close BIGINT | volume BIGINT | open_time BIGINT | close_time BIGINT | trade_date VARCHAR(8)

索引：(symbol, interval, trade_date)

#### C.11 deposit_history（入金历史）

| 字段 | 类型 | 说明 |
|------|------|------|
| deposit_id | VARCHAR(50) PK | 入金/纠正ID |
| account_id | BIGINT | 账户ID |
| amount | BIGINT | 入金金额（负数为纠正） |
| operator_id | BIGINT | 操作管理员ID |
| reason | VARCHAR(200) | 入金/纠正原因 |
| is_correction | BOOLEAN | 是否纠正操作 |
| original_deposit_id | VARCHAR(50) | 纠正对应的原始入金ID（仅纠正时填写） |
| status | VARCHAR(20) | SUCCESS / FAILED |
| created_at | BIGINT | 创建时间戳 |

索引：(account_id, created_at)

#### C.12 api_keys（API密钥）

| 字段 | 类型 | 说明 |
|------|------|------|
| api_key_id | BIGINT PK | API Key ID |
| user_id | BIGINT FK → users | 用户ID |
| api_key | VARCHAR(50) UNIQUE | API Key (ak_xxx) |
| secret_key_hash | VARCHAR(255) | Secret Key哈希 |
| key_type | VARCHAR(20) | READ_ONLY / TRADING |
| allowed_instruments | VARCHAR(100) | 可交易品种（逗号分隔） |
| status | VARCHAR(20) | ACTIVE / DISABLED |
| created_at | BIGINT | 创建时间戳 |
| last_used_at | BIGINT | 最后使用时间戳 |

索引：(user_id), (api_key)

#### C.13 deposit_requests（入金申请）

| 字段 | 类型 | 说明 |
|------|------|------|
| request_id | BIGINT PK | 申请ID |
| user_id | BIGINT | 用户ID |
| account_id | BIGINT | 账户ID |
| amount | BIGINT | 申请金额（分） |
| source | VARCHAR(100) | 资金来源 |
| reason | VARCHAR(200) | 申请原因 |
| status | VARCHAR(20) | PENDING / APPROVED / REJECTED |
| operator_id | BIGINT | 操作管理员ID |
| operator_note | VARCHAR(500) | 审批备注 |
| processed_at | BIGINT | 处理时间戳 |
| created_at | BIGINT | 创建时间戳 |

索引：(status, created_at), (user_id, created_at)

---

> **文档版本**: v12.5 | **创建日期**: 2026-06-04 | **架构师**: Trading System Architect
> **前身**: V11.10 → V12.0 → V12.1 → V12.2 → V12.3 → V12.4 → V12.5，共计修复154个问题
> **状态**: 设计完成，可落地开发
