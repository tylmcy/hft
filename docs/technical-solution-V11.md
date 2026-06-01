# A股交易系统技术方案 V11

> 版本: v11.8
> 日期: 2026-06-01
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
| v9.1 | 2026-05-24 | 多Agent审查修复：PHASE_CHANGE事件、checksum校验、frozenAmountUsed字段、EOD撤单流程 |
| v10.0 | 2026-05-24 | 最终版本：整合所有修复，完整严谨的前后一致版本，准备开发落地 |
| v11.0 | 2026-05-29 | 规范化版本：将所有Java伪代码转换为技术需求逻辑描述，确保文档作为设计规范而非实现文档 |
| v11.1 | 2026-05-29 | 多Agent审查修复：CQ同步持久化、replay_mode机制优化、独立快照恢复、批量处理参数调整、RPO=0完整性修复 |
| v11.2 | 2026-05-29 | 完整修复：frozenAmountUsed计算公式、部分成交状态转换、买方差额退还、不变式验证失败处理、CQ文件损坏处理、3.4节详细恢复逻辑 |
| v11.3 | 2026-05-31 | 多Agent全面审查修复：9个P0阻塞性问题+24个P1问题，涵盖数据模型完整性、核心流程正确性、快照恢复一致性、SOD/EOD流程规范性 |
| v11.6 | 2026-06-01 | 多Agent第四轮审查修复：17个P0阻塞性问题，含frozenAmount统一（含预估佣金）、FAK/FOK冻结计算、部分成交佣金规则、幂等机制优化（ClearingWorker不做幂等检查）、快照简化（不保存订单）、SOD边界补充、归档失败处理修正 |
| v11.7 | 2026-06-01 | 多Agent第五轮审查修复：15个P0阻塞性问题，含processed_trade_ids来源统一（account_settlement.cq4）、FOK价格约束检查、FOK撤销冻结释放、佣金超出预估处理、买方结算流程修正、错误码4005新增、归档竞态修复、EOD动态等待、系统状态码定义、优雅关闭全局FLAG机制 |
| v11.8 | 2026-06-01 | 多Agent第六轮审查修复：48个P0问题。关键修复：1）佣金计算简化（取消最低佣金，纯比例计算）；2）Instrument初始化事件（SOD写入CQ）；3）PENDING订单EOD处理（走正常撤单流程）；4）frozenAmountUsed计算公式修正；5）买方结算原子操作；6）FOK/市价单解冻金额使用剩余frozenAmount；7）ClearingWorker恢复机制优化（不使用replay_mode，从MatchingEngine快照获取起始位置）；8）TRADE事件结构优化（删除buyCommission/sellCommission，新增orderQuantity和commissionRate） |

---

## 核心设计要点

| 要点 | 说明 |
|------|------|
| **数据存储** | JVM内存为主存储，Chronicle Map仅用于快照持久化 |
| **线程模型** | AccountWorker/MatchingEngine/ClearingWorker 单线程串行处理 |
| **批量处理** | AccountWorker批量50条，ClearingWorker批量100条 |
| **快照机制** | 各Worker独立快照，原子写入（tmp→rename），checksum校验 |
| **恢复机制** | 各Worker从各自最新快照恢复，独立重放CQ增量 |
| **Replay Mode** | 恢复时默认replay_mode=true，首次写入output CQ时设为false |
| **HTTP同步** | CompletableFuture + 超时机制，替代CountDownLatch |
| **CQ持久化** | 关键事件写入后强制刷盘（sync/force），确保RPO=0 |

**文件命名约定**：
- `.dat`: Chronicle Map快照文件后缀（如 `account_snapshot_12345.dat`）
- `.cq4`: Chronicle Queue事件队列文件后缀（如 `account_input.cq4`）
- `.checksum`: 校验和文件后缀（如 `account_snapshot_12345.dat.checksum`）

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
| IDEMPOTENT_SET_MAX_SIZE | 10000 | 幂等集合内存最大容量 |
| MAX_REPLAY_TRADE_IDS | 10000 | 恢复时从CQ读取的最大trade_id数量 |

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
| **交易时段** | A股交易时段（无夜盘），仅支持连续竞价 |
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
| **可恢复性** | 快照+增量恢复，RPO=0 |

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

**数据字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| accountId | long | 账户ID |
| userId | long | 用户ID |
| balance | long | 账户余额（分） |
| available | long | 可用资金（分） |
| frozen | long | 冻结资金（分） |
| marginFrozen | long | 保证金占用（分） |
| orderFrozen | long | 委托冻结（分） |
| debt | long | 负债/穿仓金额（分） |
| unrealizedPnl | long | 未实现盈亏（分） |
| riskRatio | double | 风险比率（期货/期权账户） |
| status | enum | ACTIVE/SUSPENDED/LIQUIDATING/NEGATIVE_BALANCE/CLOSED |
| updateTime | long | 更新时间戳 |

**不变式**：
- `balance = available + frozen`
- `frozen = marginFrozen + orderFrozen`

#### 3.1.2 Position（持仓）

**数据字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| accountId | long | 账户ID |
| symbol | String | 证券代码 |
| symbolCode | int | 数值代码，加速处理 |
| quantity | long | 持仓数量（正数=多头，负数=空头） |
| available | long | 可用数量（T+0） |
| frozen | long | 冻结数量 |
| t1Locked | long | T+1锁定数量 |
| avgPrice | long | 成本价（分） |
| unrealizedPnl | long | 未实现盈亏（分） |
| updateTime | long | 更新时间戳 |

**不变式**：`|quantity| = available + frozen + t1Locked`

#### 3.1.3 Order（订单）

**数据字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| orderId | long | 订单ID |
| symbol | String | 证券代码 |
| symbolCode | int | 数值代码 |
| side | enum | BUY/SELL |
| orderType | enum | LIMIT/MARKET/FAK/FOK |
| price | long | 价格（分），市价单为0 |
| quantity | long | 原始数量 |
| filledQuantity | long | 已成交数量 |
| remainingQty | long | 剩余数量 |
| frozenAmount | long | 冻结金额（分） |
| maxPrice | long | 市价买单最大价格限制（涨停价） |
| minPrice | long | 市价卖单最小价格限制（跌停价） |
| accountId | long | 账户ID |
| userId | long | 用户ID |
| status | enum | PENDING/PARTIAL_FILLED/FILLED/CANCELLED/REJECTED |
| cancelReason | String | 撤销原因 |
| idempotencyKey | String | 幂等键 |
| createTime | long | 创建时间戳 |
| updateTime | long | 更新时间戳 |

#### 3.1.4 Instrument（合约/证券信息）

**数据字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 证券代码 |
| symbolCode | int | 数值代码，加速处理 |
| type | enum | STOCK/FUTURE/OPTION |
| name | String | 证券名称 |
| prevClose | long | 前收盘价（分） |
| limitUpRatio | double | 涨停比例（如0.10表示10%） |
| limitDownRatio | double | 跌停比例 |
| lotSize | long | 每手数量（股票通常为100） |
| minPriceIncrement | long | 最小价格变动单位（分） |
| commissionPerLot | long | 每手佣金（分） |
| stampDutyRate | long | 印花税率（万分比，仅卖方收取） |
| transferFeeRate | long | 过户费率（万分比） |
| updateTime | long | 更新时间戳 |

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
    │ 写入account_input CQ
    │
    ▼ AccountWorker冻结资金
    │ 写入matching_input CQ
    │
    ▼ MatchingEngine放入OrderBook
    │ 写入ORDER_UPDATE到matching_output CQ
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

**订单状态详细转换**：

| 当前状态 | 触发事件 | 目标状态 | 条件 | 说明 |
|---------|---------|---------|------|------|
| - | HTTP下单请求 | REJECTED | 风控检查失败 | 订单被拒绝，不入库 |
| - | HTTP下单请求 | PENDING | 风控检查通过 | 已入库，等待入OrderBook |
| PENDING | ORDER_UPDATE(status=NEW) | NEW | MatchingEngine确认入OrderBook | 过渡状态结束（正常路径） |
| PENDING | TRADE(直接成交) | PARTIAL_FILLED 或 FILLED | 极端情况：MatchingEngine在ClearingWorker更新状态前已撮合 | 跳过NEW状态直接成交，ClearingWorker后续处理时直接更新为实际状态 |
| NEW | TRADE(部分成交) | PARTIAL_FILLED | filledQuantity < quantity | 继续在OrderBook中撮合 |
| NEW | TRADE(全部成交) | FILLED | filledQuantity == quantity | 撮合完成 |
| PARTIAL_FILLED | TRADE(继续成交) | PARTIAL_FILLED | filledQuantity < quantity | 继续在OrderBook中撮合 |
| PARTIAL_FILLED | TRADE(全部成交) | FILLED | filledQuantity == quantity | 撮合完成 |
| NEW/PARTIAL_FILLED | CANCEL_SUCCESS | CANCELLED | 撤单成功 | 剩余冻结金额解冻 |
| NEW/PARTIAL_FILLED | CANCEL_REJECT | 保持原状态 | 撤单失败（订单已成交或不存在） | - |
| PENDING | CANCEL_SUCCESS | CANCELLED | EOD撤单或用户撤单 | PENDING状态订单未入OrderBook，直接更新状态并解冻 |

**PENDING状态直接成交的处理原则**：
- 极端情况下，MatchingEngine可能在ClearingWorker处理ORDER_UPDATE之前就完成撮合
- ClearingWorker处理ORDER_UPDATE时，若发现订单已有filledQuantity > 0，应直接更新为PARTIAL_FILLED或FILLED
- 这种情况不会导致数据不一致，因为状态始终向最终状态演进

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

#### 3.1.5 OrderBook（订单簿）

**设计要求**：

OrderBook是MatchingEngine的核心组件，需满足以下技术要求：

**数据结构要求**：
- 使用TreeMap存储订单，支持按价格快速排序
- 买单（bidOrders）：价格从高到低排序（使用反向比较器）
- 卖单（askOrders）：价格从低到高排序（自然顺序）
- 同价格级别内，按createTime排序（价格-时间优先）
- 维护orderId到Order的映射，支持O(1)查找

**核心操作**：

| 操作 | 功能要求 | 复杂度要求 |
|------|---------|-----------|
| addOrder | 添加订单到订单簿，按价格-时间排序 | O(log n) |
| removeOrder | 根据orderId移除订单 | O(log n) |
| getBestCounterOrder | 获取最优对手方订单（买单取最低卖单，卖单取最高买单） | O(1) |
| getTopBidLevels | 获取顶部N档买盘（价格+总量） | O(N) |
| getTopAskLevels | 获取顶部N档卖盘（价格+总量） | O(N) |
| getAvailableQuantityAtPrice | FOK订单：计算指定价格的可成交量 | O(n) |

**撮合逻辑要求**：
- 价格优先：最优价格先成交
- 时间优先：同价格先到先成交
- 支持部分成交后更新订单数量

**行情生成要求**：
- 五档行情：取买盘前5档和卖盘前5档
- 空档位用价格=0、volume=0填充
- 每档volume为该价格所有订单remainingQty之和

### 3.2 内存数据存储类

#### 3.2.1 InMemoryAccountStore

**设计要求**：

账户存储组件，使用ConcurrentHashMap<Long, Account>存储账户数据。

**核心操作**：

| 操作 | 功能要求 | 错误处理 |
|------|---------|---------|
| getAccount | 根据accountId获取账户对象 | 账户不存在返回null |
| freeze | 冻结资金：检查账户状态和余额，扣减available，增加frozen和orderFrozen | 账户不存在返回错误2004；状态异常返回错误2003；余额不足返回错误2001 |
| unfreeze | 解冻资金：减少frozen和orderFrozen，增加available | 账户不存在时静默处理 |
| writeToChronicleMap | 快照时：遍历所有账户，转换为CM格式写入 | - |
| loadFromChronicleMap | 恢复时：从CM读取所有账户，加载到内存ConcurrentHashMap | - |

**不变式维护**：每次冻结/解冻操作后，验证 `balance = available + frozen`

**不变式验证失败处理**：

| 场景 | 检测条件 | 处理方式 | 告警级别 |
|------|---------|---------|---------|
| 余额不一致 | `balance != available + frozen` | 1. 记录详细错误日志（操作类型、前后状态、预期值vs实际值）<br>2. 尝试自动修复（可配置开关）<br>3. 若自动修复失败：发送P0告警，暂停账户（status=SUSPENDED） | P0 |
| 持仓不一致 | `\|quantity\| != available + frozen + t1Locked` | 同上 | P0 |
| 冻结金额不足 | `frozen < 0` 或 `orderFrozen < 0` | 同上 | P0 |
| 可用资金不足 | `available < 0` | 同上 | P0 |

**自动修复逻辑**（可配置开关，默认启用）：

| 场景 | 修复方式 | 验证 |
|------|---------|------|
| `available + frozen > balance` | 从frozen中扣减差额，调整frozen和orderFrozen | 重新验证不变式 |
| `available + frozen < balance` | 增加available补齐差额 | 重新验证不变式 |
| `|quantity| > available + frozen + t1Locked` | 从t1Locked中补齐差额（优先）或增加available | 重新验证不变式 |

**修复失败后的处理流程**：
1. 标记账户为SUSPENDED状态
2. 发送P0告警（包含accountId、错误类型、修复建议）
3. 记录到errors表供人工审查
4. 人工修复后通过管理员接口：
   - 验证不变式通过
   - 恢复账户为ACTIVE状态

**恢复后不变式验证**：

恢复完成后，遍历所有账户和持仓验证不变式：
1. 验证所有账户：`balance = available + frozen`
2. 验证所有持仓：`|quantity| = available + frozen + t1Locked`
3. 若发现不一致：
   - 尝试自动修复（同上）
   - 若修复失败：记录错误日志，发送告警，标记账户为SUSPENDED状态

#### 3.2.2 InMemoryPositionStore

**设计要求**：

持仓存储组件，使用ConcurrentHashMap<String, Position>存储持仓数据，key格式为`accountId:symbol`。

**核心操作**：

| 操作 | 功能要求 | 错误处理 |
|------|---------|---------|
| getPosition | 根据accountId和symbol获取持仓 | 不存在返回null |
| freezePosition | 冻结持仓：检查可用数量，扣减available，增加frozen | 可用不足返回错误2002 |
| unfreezePosition | 解冻持仓：减少frozen，增加available | 持仓不存在时静默处理 |
| addPosition | 添加新持仓（买入成交时） | - |
| writeToChronicleMap | 快照时：遍历所有持仓，转换为CM格式写入 | - |
| loadFromChronicleMap | 恢复时：从CM读取所有持仓，加载到内存 | - |

**不变式维护**：每次持仓变更后，验证 `|quantity| = available + frozen + t1Locked`

#### 3.2.3 InMemoryOrderStore

**设计要求**：

订单存储组件，使用ConcurrentHashMap<Long, Order>存储订单数据。

**核心操作**：

| 操作 | 功能要求 |
|------|---------|
| getOrder | 根据orderId获取订单 |
| addOrder | 添加新订单（HTTP线程下单时） |
| updateStatus | 更新订单状态和updateTime |
| updateFilledQuantity | 累加filledQuantity，扣减remainingQty |
| removeOrder | 移除订单（成交完成或撤销时） |
| clear | 清空所有订单（新交易日初始化时） |
| writeToChronicleMap | 快照时：遍历所有订单，转换为CM格式写入 |
| loadFromChronicleMap | 恢复时：从CM读取所有订单，加载到内存 |

### 3.3 Chronicle Queue 事件结构

#### 3.3.1 account_input CQ

**事件结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| eventType | enum | NEW_ORDER / CANCEL_ORDER / SETTLEMENT / SNAPSHOT / PHASE_CHANGE / DEPOSIT / INSTRUMENT_INIT |
| orderId | long | 订单ID |
| accountId | long | 账户ID |
| userId | long | 用户ID |
| symbol | String | 证券代码 |
| side | enum | BUY / SELL |
| orderType | enum | LIMIT / MARKET / FAK / FOK |
| price | long | 价格（分） |
| quantity | long | 数量 |
| frozenAmount | long | 本次冻结金额（分）。买入时=限价×数量+预估佣金或涨停价×数量+预估佣金；卖出时为0（冻结持仓） |
| tradeId | String | 成交ID（SETTLEMENT事件专用，用于幂等检查） |
| settlementType | enum | BUYER / SELLER (仅SETTLEMENT事件) |
| frozenAmountUsed | long | 本次结算使用的冻结金额/数量（仅SETTLEMENT事件） |
| commission | long | 佣金（仅SETTLEMENT事件） |
| phase | enum | CLOSED / CONTINUOUS_TRADING (仅PHASE_CHANGE事件) |
| isSystemOrder | boolean | 是否系统生成订单（强平/做市） |
| systemReason | String | 系统订单原因：LIQUIDATION/MAKER |
| reason | String | 撤单/拒绝原因 (CANCEL_ORDER事件) |
| depositAmount | long | 入金金额（仅DEPOSIT事件） |
| idempotencyKey | String | 幂等键 |
| timestamp | long | 时间戳 |
| cqIndex | long | SNAPSHOT事件专用：CQ写入位置 |
| instrumentData | Instrument | Instrument数据（仅INSTRUMENT_INIT事件） |

**INSTRUMENT_INIT事件说明**：

SOD时将Instrument数据写入account_input CQ，使AccountWorker获取佣金率等信息用于冻结金额计算。

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 证券代码 |
| symbolCode | int | 数值代码 |
| type | enum | STOCK/FUTURE/OPTION |
| name | String | 证券名称 |
| prevClose | long | 前收盘价（分） |
| limitUpRatio | double | 涨停比例 |
| limitDownRatio | double | 跌停比例 |
| lotSize | long | 每手数量 |
| minPriceIncrement | long | 最小价格变动单位（分） |
| commissionRate | int | 佣金率（万分比，如3表示万分之三） |
| stampDutyRate | int | 印花税率（万分比） |
| transferFeeRate | int | 过户费率（万分比） |

| 事件类型 | 来源 | 说明 |
|---------|------|------|
| NEW_ORDER | HTTP线程 | 下单请求 |
| CANCEL_ORDER | HTTP线程/EOD | 撤单请求（含EOD自动撤单） |
| SETTLEMENT | AccountWorker（从account_settlement CQ读取） | 结算请求（买卖双方各一条） |
| SNAPSHOT | SnapshotWorker | 触发快照保存 |
| PHASE_CHANGE | SnapshotWorker | 交易时段切换（开盘/收盘） |
| DEPOSIT | 管理员操作 | 入金请求 |
| INSTRUMENT_INIT | SOD流程 | Instrument数据初始化 |

#### 3.3.2 matching_input CQ

**事件结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| eventType | enum | NEW_ORDER / CANCEL_ORDER / SNAPSHOT / INSTRUMENT_INIT |
| orderId | long | 订单ID |
| symbol | String | 证券代码 |
| side | enum | BUY / SELL |
| orderType | enum | LIMIT / MARKET / FAK / FOK |
| price | long | 价格（分），市价单为0 |
| quantity | long | 数量 |
| maxPrice | long | 市价买单最大价格限制（涨停价），市价单专用，限价单为0 |
| minPrice | long | 市价卖单最小价格限制（跌停价），市价单专用，限价单为0 |
| accountId | long | 账户ID |
| userId | long | 用户ID |
| frozenAmount | long | 关联的冻结金额（分），用于撤单时解冻参考。买入时=限价×数量+预估佣金或涨停价×数量+预估佣金；卖出时为0（冻结持仓） |
| idempotencyKey | String | 幂等键 |
| timestamp | long | 时间戳 |
| cqIndex | long | SNAPSHOT事件专用：CQ写入位置 |
| instrumentData | Instrument | Instrument数据（仅INSTRUMENT_INIT事件） |

**INSTRUMENT_INIT事件说明**：

SOD时将Instrument数据写入matching_input CQ，使MatchingEngine获取涨跌停价格限制等信息。

| 事件类型 | 来源 | 说明 |
|---------|------|------|
| NEW_ORDER | AccountWorker | 下单请求 |
| CANCEL_ORDER | AccountWorker | 撤单请求 |
| SNAPSHOT | SnapshotWorker | 触发快照保存 |
| INSTRUMENT_INIT | SOD流程 | Instrument数据初始化 |

#### 3.3.3 matching_output CQ

**事件结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| eventType | enum | ORDER_UPDATE / TRADE / CANCEL_SUCCESS / CANCEL_REJECT |
| orderId | long | 订单ID（所有事件类型必填：ORDER_UPDATE/TRADE/CANCEL_SUCCESS/CANCEL_REJECT） |
| accountId | long | 账户ID |
| userId | long | 用户ID |
| symbol | String | 证券代码 |
| status | enum | NEW / PARTIAL_FILLED / FILLED / CANCELLED |
| filledQuantity | long | 已成交数量 |
| remainingQty | long | 剩余数量 |
| tradeId | String | 成交ID（TRADE专用）：格式为`takerOrderId_makerOrderId` |
| takerOrderId | long | 主动方订单ID |
| makerOrderId | long | 被动方订单ID |
| price | long | 成交价格 |
| quantity | long | 成交数量 |
| orderType | enum | LIMIT / MARKET / FAK / FOK（TRADE专用），ClearingWorker判断订单类型需要 |
| orderPrice | long | 原订单限价（TRADE专用），市价单为0 |
| frozenAmount | long | 原订单冻结金额（TRADE专用），ClearingWorker计算frozenAmountUsed需要 |
| orderQuantity | long | 原订单总数量（TRADE专用），ClearingWorker更新filledQuantity需要 |
| buyAccountId | long | 买方账户ID |
| sellAccountId | long | 卖方账户ID |
| buyOrderId | long | 买方订单ID |
| sellOrderId | long | 卖方订单ID |
| commissionRate | int | 佣金率（万分比，如3表示万分之三）（TRADE专用），ClearingWorker计算佣金需要 |
| unfreezeAmount | long | 解冻金额（CANCEL_SUCCESS专用） |
| unfreezeQty | long | 解冻数量（CANCEL_SUCCESS专用） |
| side | enum | BUY / SELL |
| rejectReason | enum | ORDER_NOT_FOUND / ORDER_ALREADY_FILLED / ORDER_ALREADY_CANCELLED / PENDING_ORDER_NOT_IN_ORDERBOOK |
| timestamp | long | 时间戳 |

**字段变更说明**：
- 删除`buyCommission`和`sellCommission`字段：佣金由ClearingWorker根据`commissionRate`计算
- 新增`orderQuantity`字段：原订单总数量，用于ClearingWorker更新filledQuantity
- 新增`commissionRate`字段：佣金率（万分比），从Instrument获取
- 新增`PENDING_ORDER_NOT_IN_ORDERBOOK`枚举值：用于EOD撤单时PENDING状态订单的处理

**事件类型说明**：

| 事件类型 | 触发时机 | 处理者 |
|---------|---------|--------|
| ORDER_UPDATE | 订单放入OrderBook时 | ClearingWorker更新状态PENDING→NEW |
| TRADE | 撮合成交时 | ClearingWorker执行清算 |
| CANCEL_SUCCESS | 撤销成功时 | ClearingWorker执行解冻 |
| CANCEL_REJECT | 撤销失败时 | ClearingWorker根据reason处理 |

**CANCEL_REJECT场景说明**：

| rejectReason | 触发场景 | 处理方式 |
|--------------|---------|---------|
| ORDER_NOT_FOUND | 订单在OrderBook中不存在 | ClearingWorker检查订单状态，若为PENDING/NEW则生成UNFREEZE事件 |
| ORDER_ALREADY_FILLED | 订单已全部成交 | 忽略，记录日志 |
| ORDER_ALREADY_CANCELLED | 订单已撤销 | 忽略，记录日志 |
| PENDING_ORDER_NOT_IN_ORDERBOOK | PENDING状态订单不在OrderBook中（EOD撤单场景） | ClearingWorker生成UNFREEZE事件解冻资金/持仓 |

**TradeId确定性生成规则**：
- 格式：`takerOrderId_makerOrderId`
- 保证重复撮合产生相同tradeId，支持幂等
- 示例：买单12345吃掉卖单67890 → tradeId = "12345_67890"

#### 3.3.4 account_settlement CQ

**事件结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| eventType | enum | SETTLEMENT / UNFREEZE |
| orderId | long | 订单ID（SETTLEMENT/UNFREEZE必填） |
| accountId | long | 账户ID |
| symbol | String | 证券代码（UNFREEZE必填，用于卖单解冻持仓） |
| tradeId | String | 成交ID，用于幂等检查（SETTLEMENT专用） |
| settlementType | enum | BUYER / SELLER |
| price | long | 成交价格 |
| quantity | long | 成交数量 |
| frozenAmountUsed | long | 买方：本次结算使用的冻结金额（分）；卖方：本次结算使用的冻结持仓数量（股） |
| actualAmount | long | 实际扣款金额（分）= price × quantity |
| refundAmount | long | 退还差额（分）= frozenAmountUsed - actualAmount - commission（买方专用，卖方为0） |
| commission | long | 佣金 |
| isT1Locked | boolean | 是否需要T+1锁定（买方为true，卖方为false） |
| unfreezeAmount | long | 解冻金额（UNFREEZE专用） |
| unfreezeQty | long | 解冻数量（UNFREEZE专用） |
| side | enum | BUY / SELL（UNFREEZE必填，区分买单解冻资金还是卖单解冻持仓） |
| timestamp | long | 时间戳 |

**字段计算说明**：

| 字段 | 买方计算方式 | 卖方计算方式 |
|------|-------------|-------------|
| frozenAmountUsed | 按比例计算的冻结金额（分） | 成交数量（股） |
| actualAmount | price × quantity | price × quantity |
| refundAmount | frozenAmountUsed - actualAmount - commission（若为正） | 0 |
| isT1Locked | true（A股T+1规则） | false |

**frozenAmountUsed字段说明**：
- **买方**：本次结算使用的冻结金额（单位：分）。计算公式：`frozenAmountUsed = ceil(成交金额 × order.frozenAmount / (order.price × order.quantity))`
- **卖方**：本次结算使用的冻结持仓数量（单位：股）。值等于本次成交数量。

| 事件类型 | 来源 | 说明 |
|---------|------|------|
| SETTLEMENT | ClearingWorker | 结算请求（买卖双方各一条） |
| UNFREEZE | ClearingWorker | 解冻请求（撤单时） |

#### 3.3.5 account_output CQ

**事件结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| eventType | enum | FREEZE / UNFREEZE / BALANCE_CHANGE / POSITION_CHANGE |
| accountId | long | 账户ID |
| symbol | String | 证券代码 |
| balanceDelta | long | 余额变动 |
| availableDelta | long | 可用资金变动 |
| frozenDelta | long | 冻结资金变动 |
| quantityDelta | long | 持仓数量变动 |
| positionAvailableDelta | long | 可用持仓变动 |
| positionFrozenDelta | long | 冻结持仓变动 |
| t1LockedDelta | long | T+1锁定变动 |
| reason | String | 变动原因 |
| timestamp | long | 时间戳 |

| 事件类型 | 触发时机 | 用途 |
|---------|---------|------|
| FREEZE | 下单冻结时 | WebSocket推送账户/持仓变动 |
| UNFREEZE | 撤单解冻时 | WebSocket推送账户/持仓变动 |
| BALANCE_CHANGE | 结算/入金时 | WebSocket推送账户变动 |
| POSITION_CHANGE | 结算时 | WebSocket推送持仓变动 |

### 3.4 快照索引文件

系统使用独立快照文件，各Worker独立恢复。

**3.4.1 AccountWorker快照文件**

**文件命名**: `account_snapshot_{account_input_cq_index}.dat`

**文件结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| magic | int | 魔数，用于文件类型识别 |
| version | int | 文件格式版本 |
| account_count | int | 账户数量 |
| accounts[] | Account序列化 | 所有账户状态 |
| position_count | int | 持仓数量 |
| positions[] | Position序列化 | 所有持仓状态 |
| account_input_index | long | account_input.cq4处理位置 |
| account_settlement_index | long | account_settlement.cq4处理位置 |
| matching_input_last_index | long | matching_input.cq4写入的最后位置 |
| create_time | long | 创建时间戳 |

**校验文件**: `account_snapshot_{account_input_cq_index}.dat.checksum`
- 内容：MD5校验和（32字节十六进制字符串）

**订单数据不保存说明**：
- AccountWorker快照不保存订单数据，减少快照大小和写入时间
- 恢复时从matching_input.cq4读取快照index到latest，重建已发送撮合的订单校验源
- 这避免了快照文件过大问题，订单数据可从CQ重建

**checksum统一使用MD5**：
- 快照文件不内嵌CRC32，仅使用外部MD5校验文件
- 恢复时计算MD5与.checksum文件比对
- MD5安全性更高，且外部存储便于独立验证

**恢复逻辑**：

```
1. 查找最新有效快照文件
   files = listFiles("data/snapshots/account_snapshot_*.dat")
   sort(files, descending by index)

   for file in files:
       checksum_file = file + ".checksum"
       if not exists(checksum_file):
           continue  // 校验文件缺失，跳过

       computed_md5 = computeMD5(file)
       stored_md5 = readFile(checksum_file)

       if computed_md5 == stored_md5:
           return file  // 找到有效快照
       else:
           log.warn("快照文件损坏: {}", file)
           continue  // 校验失败，尝试下一个

   return null  // 无有效快照

2. 加载快照到内存
   snapshot = readFile(valid_snapshot_file)
   validateCRC32(snapshot)

   // 加载账户
   for account in snapshot.accounts:
       accountStore.accounts.put(account.accountId, account)

   // 加载持仓
   for position in snapshot.positions:
       key = position.accountId + ":" + position.symbol
       positionStore.positions.put(key, position)

   // 记录索引
   account_input_index = snapshot.account_input_index
   account_settlement_index = snapshot.account_settlement_index
   matching_input_last_index = snapshot.matching_input_last_index

3. 生成幂等集合
   // 生成processed_order_ids（从matching_input快照index到latest）
   // 用于校验已发送撮合的订单，避免重复发送
   processed_order_ids = new LinkedHashSet<>(MAX_SIZE)
   
   // 获取matching_input快照的index（从matching_snapshot获取）
   matching_snapshot = findLatestMatchingSnapshot()
   matching_input_snapshot_index = matching_snapshot.matching_input_index
   
   // 从matching_input快照index读取到latest
   events = matching_input_cq.readFrom(matching_input_snapshot_index)
   for event in events:
       processed_order_ids.add(event.orderId)
       if processed_order_ids.size() > MAX_SIZE:
           processed_order_ids.removeFirst()  // 保持最新10,000个

   // 生成processed_settlement_ids（从account_settlement.cq4读取近10分钟）
   processed_settlement_ids = new LinkedHashSet<>(MAX_SIZE)
   
   // 获取matching_output快照index（从matching_snapshot获取）
   matching_output_snapshot_index = matching_snapshot.matching_output_last_index
   
   // 读取account_settlement.cq4，仅保留近10分钟的trade_id
   current_time = System.currentTimeMillis()
   events = account_settlement_cq.readFrom(matching_output_snapshot_index)
   for event in events:
       // 仅保留近10分钟内的trade_id
       if current_time - event.timestamp <= 10 * 60 * 1000:
           processed_trade_ids.add(event.tradeId)
           if processed_trade_ids.size() > MAX_SIZE:
               processed_trade_ids.removeFirst()

4. 设置replay_mode=true

5. 重放account_input CQ从account_input_index+1开始
   events = account_input_cq.readFrom(account_input_index + 1)
   for event in events:
       if event.type == NEW_ORDER:
           if replay_mode && event.orderId in processed_order_ids:
               skip  // 跳过已写入matching_input的订单
           else:
               if replay_mode:
                   replay_mode = false  // 首次遇到新订单
               // 执行冻结操作（无论replay_mode状态，必须执行）
               if event.side == BUY:
                   accountStore.freeze(event.accountId, event.frozenAmount)
               else:
                   positionStore.freezePosition(event.accountId, event.symbol, event.quantity)
               // 写入下游CQ
               write_to_matching_input(event)
               write_to_account_output(event)

6. 重放account_settlement CQ从account_settlement_index+1开始
   events = account_settlement_cq.readFrom(account_settlement_index + 1)
   for event in events:
       settlement_id = event.tradeId + "_" + event.orderId
       if settlement_id in processed_settlement_ids:
           skip  // 幂等检查
       else:
           process_settlement(event)
           processed_settlement_ids.add(settlement_id)
```

**3.4.2 MatchingEngine快照文件**

**文件命名**: `matching_snapshot_{matching_input_cq_index}.dat`

**文件结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| magic | int | 魔数 |
| version | int | 文件格式版本 |
| symbol_count | int | 交易品种数量 |
| orderbooks[] | OrderBook序列化 | 各symbol的OrderBook状态 |
| matching_input_index | long | matching_input.cq4处理位置 |
| matching_output_last_index | long | matching_output.cq4写入的最后位置 |
| create_time | long | 创建时间戳 |
| checksum | long | CRC32校验和 |

**OrderBook序列化结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 证券代码 |
| bid_count | int | 买单数量 |
| bids[] | Order序列化 | 买单列表（按价格排序） |
| ask_count | int | 卖单数量 |
| asks[] | Order序列化 | 卖单列表（按价格排序） |

**校验文件**: `matching_snapshot_{matching_input_cq_index}.dat.checksum`

**恢复逻辑**：

```
1. 查找最新有效快照文件
   （同AccountWorker，验证checksum）

2. 加载快照到内存
   snapshot = readFile(valid_snapshot_file)
   validateCRC32(snapshot)

   // 恢复各symbol的OrderBook
   for orderbook_data in snapshot.orderbooks:
       orderbook = new OrderBook(orderbook_data.symbol)
       for order in orderbook_data.bids:
           orderbook.addOrder(order)
       for order in orderbook_data.asks:
           orderbook.addOrder(order)
       orderbooks.put(orderbook_data.symbol, orderbook)

   // 记录索引
   matching_input_index = snapshot.matching_input_index
   matching_output_last_index = snapshot.matching_output_last_index

3. 生成existing_trade_ids
   // 从matching_output快照index读取到latest（而非从0开始）
   existing_trade_ids = new LinkedHashSet<>(MAX_SIZE)
   events = matching_output_cq.readFrom(matching_output_last_index)
   for event in events:
       if event.type == TRADE:
           existing_trade_ids.add(event.tradeId)
           if existing_trade_ids.size() > MAX_SIZE:
               existing_trade_ids.removeFirst()

4. 设置replay_mode=true

5. 重放matching_input CQ从matching_input_index+1开始
   events = matching_input_cq.readFrom(matching_input_index + 1)
   for event in events:
       if event.type == NEW_ORDER:
           orderbook = getOrCreateOrderBook(event.symbol)
           orderbook.addOrder(event)

           // 执行撮合
           trades = match(orderbook, event)

           for trade in trades:
               if replay_mode && trade.tradeId in existing_trade_ids:
                   skip  // 跳过已写入的成交
               else:
                   if replay_mode:
                       replay_mode = false  // 首次遇到新成交
                   write_to_matching_output(trade)

           write_order_update(event)

       else if event.type == CANCEL_ORDER:
           process_cancel(event)
           write_cancel_result(event)
```

**3.4.3 ClearingWorker恢复机制**

ClearingWorker不生成快照，不做幂等检查，直接重放matching_output CQ。幂等检查由AccountWorker处理SETTLEMENT事件时负责。

**恢复逻辑**：

```
1. ClearingWorker不做幂等检查，不生成processed_trade_ids

2. 不使用replay_mode机制（ClearingWorker不需要replay_mode）

3. 从MatchingEngine快照获取matching_output_last_index作为重放起始位置
   matching_output_start_index = matchingEngineSnapshot.matching_output_last_index

4. 重放matching_output CQ从matching_output_start_index+1开始
   events = matching_output_cq.readFrom(matching_output_start_index + 1)
   for event in events:
       if event.type == TRADE:
           // 直接处理，不做幂等检查
           process_trade(event)
           write_to_account_settlement(event)

       else if event.type == ORDER_UPDATE:
           process_order_update(event)

       else if event.type == CANCEL_SUCCESS:
           process_cancel_success(event)
           write_to_account_settlement(event)

       else if event.type == CANCEL_REJECT:
           process_cancel_reject(event)
           // 检查rejectReason，若为PENDING_ORDER_NOT_IN_ORDERBOOK则生成UNFREEZE事件
```

**幂等机制设计原则**：

1. **ClearingWorker不做幂等检查**：ClearingWorker直接处理TRADE事件生成SETTLEMENT事件，简化清算流程。

2. **AccountWorker做兜底幂等检查**：AccountWorker处理SETTLEMENT事件时，使用`processed_trade_ids`进行幂等检查，防止重复结算。

3. **processed_trade_ids来源**：从`account_settlement.cq4`读取最新10分钟内的trade_id，仅在AccountWorker中维护和使用。

**注意**：ClearingWorker不使用replay_mode机制，因为它是纯转发角色（将TRADE转换为SETTLEMENT），幂等检查由下游AccountWorker负责。

**3.4.4 快照文件原子写入流程**

各Worker写入快照时采用原子写入机制：

```
1. 创建临时文件
   tmp_file = "data/snapshots/account_snapshot_{index}.tmp"

2. 写入快照数据
   writeInt(tmp_file, MAGIC_NUMBER)
   writeInt(tmp_file, VERSION)
   writeAccounts(tmp_file, accountStore.accounts)
   writePositions(tmp_file, positionStore.positions)
   writeLong(tmp_file, account_input_index)
   writeLong(tmp_file, account_settlement_index)
   writeLong(tmp_file, matching_input_last_index)
   writeLong(tmp_file, System.currentTimeMillis())

3. 计算并写入CRC32校验和
   crc32 = computeCRC32(tmp_file)
   writeLong(tmp_file, crc32)

4. 计算MD5校验和
   md5 = computeMD5(tmp_file)

5. 写入校验文件
   checksum_file = tmp_file + ".checksum"
   writeFile(checksum_file, md5)

6. 原子重命名
   rename(tmp_file, "data/snapshots/account_snapshot_{index}.dat")
   rename(checksum_file, "data/snapshots/account_snapshot_{index}.dat.checksum")

7. 清理旧快照（保留最近3个）
   old_files = listFiles("data/snapshots/account_snapshot_*.dat")
   sort(old_files, descending by index)
   for file in old_files[3:]:
       deleteFile(file)
       deleteFile(file + ".checksum")
```

**3.4.5 恢复顺序**

恢复按以下顺序执行：

1. **AccountWorker恢复**（第一阶段）
   - 加载account_snapshot
   - 生成processed_order_ids、processed_settlement_ids
   - 重放account_input CQ
   - 写入matching_input CQ、account_output CQ

2. **MatchingEngine恢复**
   - 加载matching_snapshot
   - 生成existing_trade_ids
   - 重放matching_input CQ
   - 写入matching_output CQ

3. **ClearingWorker恢复**
   - 生成processed_trade_ids
   - 重放matching_output CQ
   - 写入account_settlement CQ

4. **AccountWorker恢复**（第二阶段）
   - 重放account_settlement CQ
   - 处理SETTLEMENT/UNFREEZE事件

**3.4.6 快照与CQ索引对应关系**

| Worker | 快照文件 | 快照记录的索引 | 恢复起始位置 |
|--------|---------|---------------|-------------|
| AccountWorker | `account_snapshot_{index}.dat` | account_input_index, account_settlement_index, matching_input_last_index | account_input_index+1, account_settlement_index+1 |
| MatchingEngine | `matching_snapshot_{index}.dat` | matching_input_index, matching_output_last_index | matching_input_index+1 |
| ClearingWorker | 无 | - | matching_output CQ起始位置 |

**索引用途说明**：

| 索引 | 来源 | 用途 |
|------|------|------|
| account_input_index | AccountWorker快照 | 恢复时从此位置+1重放account_input CQ |
| account_settlement_index | AccountWorker快照 | 恢复时从此位置+1重放account_settlement CQ |
| matching_input_last_index | AccountWorker快照 | 生成processed_order_ids的起始位置 |
| matching_input_index | MatchingEngine快照 | 恢复时从此位置+1重放matching_input CQ |
| matching_output_last_index | MatchingEngine快照 | 生成existing_trade_ids的结束位置 |

### 3.5 FutureManager

**设计要求**：

HTTP同步等待机制，替代CountDownLatch，使用ConcurrentHashMap<Long, CompletableFuture<ProcessResult>>管理等待中的请求。

**核心操作**：

| 操作 | 功能要求 |
|------|---------|
| createFuture | 为指定orderId创建CompletableFuture，设置5秒超时，超时后返回TIMEOUT结果 |
| complete | 根据orderId找到对应的Future并完成（移除Map中的条目） |
| completeBatch | 批量完成多个Future |

**超时处理**：使用CompletableFuture.orTimeout()实现超时机制，超时后返回错误码1002。

---

## 4. 线程模型

### 4.1 线程分类

| 线程类型 | 数量 | 职责 | 数据访问 |
|---------|------|------|----------|
| Tomcat HTTP (虚拟线程) | 无限 | 接收HTTP请求，写入CQ，等待唤醒 | 读内存，写CQ |
| WebSocket EventLoop | 8 | WebSocket推送 | 读内存行情 |
| AccountWorker | 1 | 冻结/解冻/结算 | 读写内存（快照时暂停） |
| MatchingEngine | 1 | 订单撮合、行情推送 | 读写内存（快照时暂停） |
| ClearingWorker | 1 | 清算计算、更新订单状态 | 读写内存（快照时暂停），批量100条可配置 |
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
   - 若idempotencyKey已存在且状态为CONFIRMED，返回已有orderId
   - 若状态为PROCESSING，返回错误1003"请求处理中"
   - 否则创建新条目，状态设为PROCESSING

2. 交易阶段检查
   - 若当前阶段不允许下单，返回错误4001"当前阶段不允许下单"

3. 参数校验
   - symbol格式、side枚举、orderType枚举
   - LIMIT单: price > 0
   - quantity > 0 && quantity % lotSize == 0
   - 涨跌停校验

4. 生成订单ID（雪花算法）

5. 创建CompletableFuture并注册到FutureManager

6. 写入account_input CQ（带异常处理）
   - 若写入失败：删除内存订单、释放幂等键、返回错误9999

7. 等待账户操作完成
   - result = future.get(5秒超时)
   - 若失败，返回相应错误码和消息

8. 返回orderId和status=PENDING


【阶段2: AccountWorker处理】

9. 批量读取account_input CQ（最多50条或等待1ms）

10. 批量处理
    对于NEW_ORDER事件：
    - 市价单特殊处理：
      - 买入：计算涨停价和预估佣金，冻结金额 = 涨停价×数量 + 预估佣金，设置maxPrice=涨停价
      - 卖出：计算跌停价，设置minPrice=跌停价。卖单冻结持仓，不冻结资金。佣金在成交时从卖方收入中扣除，不预先冻结。预估佣金仅用于展示参考，不参与冻结计算

    - 限价买单：冻结金额 = price×数量 + 预估佣金
    - FAK/FOK订单：冻结金额 = price×数量 + 预估佣金（与限价买单相同）
    - 买单：调用accountStore.freeze冻结资金
    - 卖单：调用positionStore.freezePosition冻结持仓
    
    - 冻结成功：生成matching_input事件和account_output事件
    - 冻结失败：更新订单状态为REJECTED，WebSocket推送拒绝通知

11. 批量写入matching_input CQ

12. 批量写入account_output CQ（用于WebSocket推送）

13. 批量唤醒HTTP线程（通过CompletableFuture.complete）
```

### 5.2 撮合流程

```
【MatchingEngine批量处理】

1. 批量读取matching_input CQ（最多50条或等待1ms）

2. 按symbol分组（提高缓存命中率）

3. 批量撮合
   对于NEW_ORDER事件：
   - 幂等检查：若orderId在processed_order_ids中，跳过
   - 将orderId加入processed_order_ids
   - 订单加入OrderBook
   - 根据订单类型执行撮合
   - 写入ORDER_UPDATE事件（status=NEW）

   对于CANCEL_ORDER事件：
   - 从OrderBook移除订单
   - 成功：生成CANCEL_SUCCESS事件
   - 失败：生成CANCEL_REJECT事件
   
   对于SNAPSHOT事件：
   - 执行订单簿快照（见17.3节）

4. 批量写入matching_output CQ

5. 五档行情推送（每3秒聚合）
```

**按订单类型撮合规则**：

| 订单类型 | 撮合规则 |
|---------|---------|
| LIMIT | 限价撮合，未成交部分保留在OrderBook |
| MARKET | 市价撮合，与最优对手方成交，未成交部分撤销，价格不超过涨跌停价 |
| FAK (Fill-And-Kill) | 限价立即成交，price字段必填，与最优对手方成交能成交的部分，剩余立即撤销 |
| FOK (Fill-Or-Kill) | 检查OrderBook中对手方可成交量总和（可跨多档价格聚合）是否 >= 订单数量，若满足则立即全部成交，否则全部撤销 |

**FOK订单价格约束检查**：

FOK订单检查可成交量时必须考虑价格约束：
- **FOK买单**：仅统计卖盘中 `price <= FOK订单.limitPrice` 的可成交量
- **FOK卖单**：仅统计买盘中 `price >= FOK订单.limitPrice` 的可成交量

若不检查价格约束，可能导致FOK订单以超出限价的价格成交，违反限价约束。

**FOK订单处理流程**：
1. FOK订单进入MatchingEngine时，检查OrderBook中对手方符合价格条件的可成交量总和
2. 若满足条件：立即全部成交（仅与符合价格条件的对手方订单成交），生成TRADE事件
3. 若不满足条件：生成CANCEL_REJECT事件，订单全部撤销，触发冻结金额释放

**FOK订单并发检查说明**：

由于MatchingEngine是单线程处理，FOK订单按顺序串行处理，不存在并发检查问题。

**市价单价格约定**：
- 用户端传入 `price=0`
- AccountWorker冻结时：
  - 买入：冻结金额 = 涨停价×数量 + 预估佣金，设置maxPrice=涨停价
  - 卖出：按跌停价计算预估佣金用于展示参考（不参与冻结），设置minPrice=跌停价
- 限价买单/FAK/FOK冻结金额 = price×数量 + 预估佣金
- 预估佣金计算：comm = max(amount × 3 / 10000, 500)，其中amount按涨停价或限价计算
- 订单入OrderBook时设置maxPrice/minPrice
- 撮合时：
  - 市价买单：只与价格 <= maxPrice 的卖单成交
  - 市价卖单：只与价格 >= minPrice 的买单成交

**按订单类型撮合规则**：

| 订单类型 | 撮合规则 | 未成交部分处理 |
|---------|---------|---------------|
| LIMIT | 限价撮合，未成交部分保留在OrderBook | 保留在OrderBook等待后续撮合 |
| MARKET | 市价撮合，与最优对手方成交，价格不超过涨跌停价 | 立即撤销：MatchingEngine生成CANCEL_SUCCESS，unfreezeAmount = order.frozenAmount（剩余冻结金额），unfreezeQty = remainingQty（卖单） |
| FAK (Fill-And-Kill) | 限价立即成交，price字段必填，与最优对手方成交能成交的部分 | 剩余立即撤销：MatchingEngine生成CANCEL_SUCCESS，unfreezeAmount = order.frozenAmount（剩余冻结金额），unfreezeQty = remainingQty |
| FOK (Fill-Or-Kill) | 检查OrderBook中对手方可成交量总和（可跨多档价格聚合）是否 >= 订单数量 | 若可成交量不足：全部撤销，生成CANCEL_SUCCESS（非CANCEL_REJECT），unfreezeAmount = order.frozenAmount（全部冻结金额）；若满足：立即全部成交 |

**FAK/FOK/MARKET订单撤销流程说明**：
- MatchingEngine撮合后若存在未成交部分，立即生成CANCEL_SUCCESS事件写入matching_output CQ
- **FOK订单全部撤销时也生成CANCEL_SUCCESS**（而非CANCEL_REJECT），因为需要触发冻结金额释放
- **unfreezeAmount计算原则**：
  - 使用订单当前的剩余冻结金额 `order.frozenAmount`，避免重复计算和舍入误差
  - 买单：unfreezeAmount = order.frozenAmount（剩余冻结金额）
  - 卖单：unfreezeAmount = 0（卖单冻结持仓，不冻结资金）
  - 冻结金额已在每次成交后递减：`order.frozenAmount -= frozenAmountUsed`
- **unfreezeQty计算**：
  - 买单：unfreezeQty = 0（买单冻结资金，不冻结持仓）
  - 卖单：unfreezeQty = remainingQty（FOK全部撤销时为quantity）
- ClearingWorker处理CANCEL_SUCCESS：生成UNFREEZE事件写入account_settlement CQ
- AccountWorker处理UNFREEZE：解冻资金/持仓，退还差额到available

**使用剩余冻结金额的优势**：
- 避免重复计算导致的精度误差
- 确保解冻金额与实际冻结金额完全一致
- 简化逻辑，减少出错可能

**CANCEL_REJECT事件说明**：
- CANCEL_REJECT仅用于用户主动撤单失败时（如订单已成交或已撤销）
- FOK/FAK/MARKET订单因撮合规则导致的撤销，均使用CANCEL_SUCCESS事件，确保触发解冻流程

**幂等保证**：

MatchingEngine 使用内存 `processed_order_ids` 集合记录已处理的订单ID，使用LinkedHashSet实现，最多保留最新10,000个。超过限制时移除最早添加的元素（FIFO淘汰）。恢复时从 `matching_input.cq4` 重放生成。

### 5.3 清算流程

```
【ClearingWorker批量处理】

1. 批量读取matching_output CQ（最多100条或等待1ms，批量大小可配置）

2. 批量处理
   对于ORDER_UPDATE事件：
   - 更新订单状态：
     - 若status=NEW且当前为PENDING：PENDING → NEW
     - 若status=PARTIAL_FILLED且当前为NEW/PARTIAL_FILLED：更新filledQuantity和remainingQty
     - 若status=FILLED且当前为PARTIAL_FILLED：更新filledQuantity和remainingQty
   - 更新filledQuantity和remainingQty
   - WebSocket推送订单状态更新

   对于TRADE事件：
   - 幂等检查：若tradeId在processed_trade_ids中，跳过
   - 将tradeId加入processed_trade_ids

   - 更新买卖双方订单的filledQuantity和remainingQty

   - 计算成交金额：amount = price × quantity

   - 计算佣金（详见15.4节）

   - 计算frozenAmountUsed（买方专用字段）：

     **买方frozenAmountUsed计算规则**：

     | 场景 | 条件 | 计算方式 | 说明 |
     |------|------|---------|------|
     | 全部成交（一次性） | remainingQty == 0 且 filledQuantity == quantity | frozenAmountUsed = order.frozenAmount | 一次性全部成交，使用全部冻结金额 |
     | 部分成交（首次/中间） | filledQuantity < quantity 且 remainingQty > 0 | frozenAmountUsed = ceil(成交金额 × order.frozenAmount / (order.price × order.quantity)) | 按成交金额比例计算，向上取整保护买方 |
     | 部分成交（最后一次） | remainingQty == 0 且 order.frozenAmount > 0 | frozenAmountUsed = order.剩余冻结金额 | 确保冻结金额全部释放，避免舍入误差累积 |

     **计算公式详解**：

     **公式（单位统一为分）**：
     ```
     frozenAmountUsed = ceil(成交金额(分) × order.frozenAmount(分) / (order.price(分) × order.quantity(股)))
                      = ceil(成交金额 × order.frozenAmount / 订单总价值)
     ```

     **计算步骤**：
     1. 计算成交金额：`tradeAmount = price × quantity`（单位：分）
     2. 计算订单总价值：`orderTotalValue = order.price × order.quantity`（单位：分，限价单）或 `涨停价 × order.quantity`（市价单）
     3. 计算比例：`ratio = tradeAmount / orderTotalValue`
     4. 计算冻结金额使用：`frozenAmountUsed = ceil(ratio × order.frozenAmount)`

     对于限价买单/FAK/FOK：
     - frozenAmount = order.price × order.quantity + 预估佣金（冻结金额=限价×数量+佣金）
     - 部分成交时frozenAmountUsed按比例计算，包含预估佣金部分
     - 若成交价格低于限价：frozenAmountUsed < frozenAmount，差额退还到available

     对于市价买单：
     - frozenAmount = 涨停价 × quantity + 预估佣金（冻结金额按涨停价+佣金计算）
     - 成交时按实际成交价格扣款，差额退还

     **部分成交佣金处理规则**：

     | 成交次数 | 佣金计算方式 | 说明 |
     |---------|-------------|------|
     | 每次成交 | commission = 成交金额 × 佣金率 / 10000 | 按比例计算，无最低佣金限制 |
     | 累计佣金 | 各次成交佣金之和 | 部分成交可能导致累计佣金与预估佣金略有差异 |

     **部分成交计算示例**：

     假设限价买单：order.price=1000分(10.00元), order.quantity=1000股, order.frozenAmount=100500分（含500分预估佣金）
     部分成交：price=980分(9.80元), quantity=300股

     frozenAmountUsed计算：
     ```
     成交金额 = 980 × 300 = 294000分
     订单总价值 = 1000 × 1000 = 1000000分
     frozenAmountUsed = ceil(294000 × 100500 / 1000000)
                      = ceil(29547000 / 1000000)
                      = ceil(29.547)
                      = 30分
     ```
     
     注：以上计算有误，正确计算如下：
     ```
     成交金额 = 980 × 300 = 294000分（2940元）
     订单总价值 = 1000 × 1000 = 1000000分（10000元）
     frozenAmountUsed = ceil(294000 × 100500 / 1000000)
                      = ceil(29547000000 / 1000000)
                      = ceil(29547)
                      = 29547分（295.47元）
     ```

     本次成交佣金：commission = 294000 × 3 / 10000 = 88.2分

     **差额退还计算**（ClearingWorker计算并写入SETTLEMENT事件）：

     买方差额退还公式：
     ```
     实际扣款 = price × quantity + commission
     退还金额 = frozenAmountUsed - 实际扣款
              = frozenAmountUsed - price × quantity - commission
     ```
     若退还金额 > 0，写入SETTLEMENT.refundAmount字段，退还到买方available。

   - 更新订单剩余冻结金额：
     - order.frozenAmount -= frozenAmountUsed

   - 生成两条SETTLEMENT事件（买方和卖方各一条）

   对于CANCEL_SUCCESS事件：
   - 生成UNFREEZE事件写入account_settlement CQ
   - 记录unfreezeAmount = order.frozenAmount（剩余冻结金额全部解冻）
   - 记录unfreezeQty = order.remainingQty（剩余数量解冻）

   对于CANCEL_REJECT事件：
   - WebSocket推送撤单失败通知

3. 批量写入account_settlement CQ
```

**幂等保证**：

ClearingWorker不做幂等检查，直接处理TRADE事件生成SETTLEMENT事件。AccountWorker处理SETTLEMENT事件时使用`processed_trade_ids`做兜底幂等检查，防止重复结算。`processed_trade_ids`来源于`account_settlement.cq4`，仅保留最新10分钟内的trade_id。

### 5.4 结算/解冻流程

```
【AccountWorker处理SETTLEMENT/UNFREEZE】

1. 批量读取account_settlement CQ（最多50条或等待1ms）

2. 批量处理
   对于SETTLEMENT事件：
   - 幂等检查：使用settlementId = tradeId + "_" + orderId
   - 若已在processed_settlement_ids中，跳过
   - 将settlementId加入processed_settlement_ids

   - 买方结算（原子操作，确保不变式始终成立）：
     **原子操作流程**（所有字段更新视为一个原子事务）：
     
     1. 计算实际扣款：
        - 成交金额 = price × quantity
        - 总扣款 = 成交金额 + commission
     
     2. 计算退还差额：
        - 退还金额 = frozenAmountUsed - 总扣款
     
     3. 同时更新账户字段（原子操作）：
        - frozen -= frozenAmountUsed
        - orderFrozen -= frozenAmountUsed
        - balance -= 总扣款
        - 若退还金额 > 0：available += 退还金额（差额退还）
        - 若退还金额 < 0：available -= |退还金额|（佣金超出预估，从可用资金扣除）
     
     4. 更新订单剩余冻结金额：
        - order.frozenAmount -= frozenAmountUsed
     
     5. 增加持仓：
        - 若持仓不存在：创建新持仓，quantity = quantity, t1Locked = quantity
        - 若持仓已存在：quantity += quantity, t1Locked += quantity
        - 说明：A股T+1规则，买入当日锁定
     
     6. 验证不变式：balance = available + frozen
     
     **不变式维护说明**：
     - 上述步骤3中，所有账户字段更新必须在一个原子操作中完成
     - 先解冻frozen、扣减balance，再退还差额到available，确保中间状态不违反不变式
     - 例如：若frozenUsed=100000分，总扣款=98000分，退还=2000分
       - frozen -= 100000（解冻）
       - balance -= 98000（扣款）
       - available += 2000（退还）
       - 结果：balance减少98000，available增加2000，frozen减少100000
       - 验证：(balance变化) = (available变化) + (frozen变化) → -98000 = +2000 + (-100000) ✓

   - 卖方结算：
     1. 解冻持仓
        - position.frozen -= quantity
        - positionFrozen -= quantity

     2. 减少持仓数量
        - position.quantity -= quantity
        - 若quantity变为0：移除持仓

     3. 解冻保证金（如有，期货/期权账户）
        - 若有保证金冻结：marginFrozen -= 保证金
        - frozen -= 保证金

     4. 入账净收入
        - 净收入 = 成交金额 - commission
        - balance += 净收入
        - available += 净收入

     5. 验证不变式：|quantity| = available + frozen + t1Locked

   - 写入account_output CQ用于WebSocket推送
   - WebSocket推送成交回报

   对于UNFREEZE事件：
   - 买单：解冻资金
     - frozenAmount = unfreezeAmount
     - available += frozenAmount
     - frozen -= frozenAmount
     - orderFrozen -= frozenAmount
     - 验证不变式：balance = available + frozen

   - 卖单：解冻持仓
     - position.available += unfreezeQty
     - position.frozen -= unfreezeQty
     - 验证不变式：|quantity| = available + frozen + t1Locked

   - 写入account_output CQ
   - WebSocket推送撤单结果

3. 批量写入account_output CQ
```

**关键设计说明**：

1. **幂等保证**：AccountWorker使用`processed_settlement_ids`记录已处理的结算ID（内存中维护最新10,000个），防止重复结算

2. **不变式维护**：每次结算后验证`balance = available + frozen`

3. **部分成交支持**：订单的`frozenAmount`随着成交递减，支持多次部分成交

4. **买方差额退还**：
   - 限价买单：若成交价格低于限价，冻结金额 > 实际扣款，差额退回available
   - 市价买单：涨停价冻结，实际成交价扣款，差额退回available
   - 确保买方不会因为价格波动损失资金

---

## 6. 并发控制

### 6.1 核心设计

**单线程串行处理 + 内存数据存储**

- AccountWorker：单线程处理所有账户/持仓状态变更
- MatchingEngine：单线程处理所有OrderBook操作
- ClearingWorker：单线程处理所有清算操作

**无锁设计**：各Worker单线程访问各自数据，无需加锁。

### 6.2 幂等性保证

**HTTP层幂等（IdempotencyService）**：

| 操作 | 功能要求 |
|------|---------|
| tryAcquire | 尝试获取幂等键：若key不存在则创建PROCESSING状态并返回true；若已存在则返回false |
| confirm | 确认幂等键：更新状态为CONFIRMED，记录orderId |
| fail | 失败处理：移除幂等键 |

**幂等集合**：

| 幂等集合 | 来源 | 内存限制 | 用途 |
|---------|------|---------|------|
| `processed_order_ids` | `matching_input.cq4` 重放生成 | 最新10,000个 | MatchingEngine跳过已处理订单 |
| `processed_trade_ids` | `account_settlement.cq4` 重放生成 | 最新10分钟内的trade_id | AccountWorker结算时兜底幂等检查 |
| `processed_settlement_ids` | 内存维护 | 最新10,000个 | AccountWorker跳过已处理结算 |

**幂等集合来源详细说明**：

| 幂等集合 | 生成时机 | 读取CQ范围 | 用途 |
|---------|---------|-----------|------|
| `processed_order_ids` | AccountWorker恢复时 | matching_input.cq4 lastIndex→latest | 检查订单是否已写入matching_input，避免重复写入 |
| `processed_trade_ids` | AccountWorker恢复时 | account_settlement.cq4 最新10分钟 | AccountWorker结算时兜底幂等检查 |
| `processed_settlement_ids` | AccountWorker运行时 | account_settlement.cq4 最新10,000个 | 检查结算是否已处理，避免重复转账 |

**幂等机制设计原则**：

1. **ClearingWorker不做幂等检查**：ClearingWorker从matching_output CQ读取TRADE事件后直接处理，生成SETTLEMENT事件写入account_settlement CQ，不做幂等检查。

2. **AccountWorker做兜底幂等检查**：AccountWorker处理SETTLEMENT事件时，使用processed_trade_ids进行幂等检查，防止重复结算。processed_trade_ids仅保留最新10分钟内的trade_id，避免内存无限增长。

3. **processed_trade_ids时间窗口限制**：
   - 从account_settlement CQ读取最新10分钟内的trade_id
   - 超过10分钟的trade_id从集合中移除（FIFO淘汰）
   - 时间窗口限制：避免内存无限增长，同时覆盖正常交易时段的重复处理场景

**说明**：`processed_trade_ids`来源于`account_settlement.cq4`，用于AccountWorker结算时的兜底幂等检查。ClearingWorker不做幂等检查，简化清算流程，由AccountWorker确保数据一致性。

### 6.3 volatile变量

| 变量 | 类型 | 用途 | 更新者 |
|------|------|------|--------|
| currentPhase | TradingPhase | 当前交易阶段 | PhaseManager |
| throttled | boolean | 背压标志 | MatchingEngine |
| marketOpen | boolean | 市场开关 | PhaseManager |
| systemStatus | SystemStatus | 系统级状态 | LifecycleManager |
| shuttingDown | boolean | 优雅关闭标志 | LifecycleManager |

### 6.4 系统级状态定义

**SystemStatus枚举**：

| 状态 | 含义 | HTTP响应 | 说明 |
|------|------|---------|------|
| STARTING | 系统启动中 | 错误码4005 | 初始化阶段，不接受请求 |
| RECOVERING | 系统恢复中 | 错误码4005 | 从快照+CQ恢复，不接受请求 |
| RUNNING | 正常运行 | 正常响应 | 接受并处理请求 |
| SHUTTING_DOWN | 关闭中 | 下单返回错误码4006，撤单正常 | 优雅关闭，停止接收新订单，处理现有订单 |
| DEGRADED | 降级模式 | 部分功能受限 | 异常场景下的降级运行 |

**shuttingDown标志用途**：
- 优雅关闭时设置 `shuttingDown = true`
- HTTP下单请求检查此标志，若为true则返回错误码4006（系统关闭中）
- 撤单请求不受影响，正常处理
- Worker继续处理已入队的订单和撤单

**状态转换流程**：
```
启动：STARTING → RECOVERING → RUNNING
关闭：RUNNING → SHUTTING_DOWN → （关闭完成）
异常：RUNNING → DEGRADED → （恢复后）RUNNING
```

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
   - 加载账户：accountCM → accountStore
   - 加载持仓：positionCM → positionStore
   - 加载订单：orderCM → orderStore
   - 加载订单簿：orderbookData → orderBooks

3. 重放CQ增量事件
   - 重放account_input CQ（从accountInputIndex+1开始）
   - 重放matching_input CQ（从matchingInputIndex+1开始）
   - 重放matching_output CQ（从matchingOutputIndex+1开始）
   - 重放account_settlement CQ（从accountSettlementIndex+1开始）

4. 启动工作线程
   accountWorker.start();
   matchingEngine.start();
   clearingWorker.start();
```

---

## 8. 行情服务

### 8.1 五档行情生成

**设计要求**：

根据OrderBook生成五档行情快照。

**生成规则**：
- 买盘：取价格最高的5档
- 卖盘：取价格最低的5档
- 每档volume为该价格所有订单remainingQty之和
- 空档位用price=0、volume=0填充

**快照数据结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 证券代码 |
| updateTime | long | 更新时间戳 |
| bidPrices | long[5] | 买盘价格（价格从高到低） |
| bidVolumes | long[5] | 买盘数量 |
| askPrices | long[5] | 卖盘价格（价格从低到高） |
| askVolumes | long[5] | 卖盘数量 |

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
  "price": "1050",              // 限价单必填，市价单传"0"
  "quantity": "100",
  "maxPrice": "1155",           // 可选，市价买单最大价格限制（涨停价），系统自动填充
  "minPrice": "945",            // 可选，市价卖单最小价格限制（跌停价），系统自动填充
  "idempotencyKey": "uuid-v4-string"
}
```

**字段说明**：
- `price`：限价单必填，市价单传"0"
- `maxPrice`：市价买单可选，系统自动填充为涨停价，用于控制成交价格上限
- `minPrice`：市价卖单可选，系统自动填充为跌停价，用于控制成交价格下限
- `idempotencyKey`：幂等键，建议使用UUID v4格式，5分钟内重复请求返回相同orderId

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

**说明**：撤单为异步操作，HTTP响应表示撤单请求已提交，实际撤单结果通过WebSocket推送。

响应：
```json
{
  "code": "0",
  "message": "success",
  "data": {
    "orderId": "1234567890123456789",
    "status": "PENDING_CANCEL"     // 撤单请求已提交，等待处理
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}
```

**撤单结果推送**（通过WebSocket order_update）：
```json
{
  "type": "order_update",
  "data": {
    "orderId": "1234567890123456789",
    "status": "CANCELLED",
    "unfreezeAmount": "105000",
    "unfreezeQty": "100",
    "reason": "USER_CANCEL",
    "timestamp": "1716460800000"
  }
}
```

**撤单失败推送**（若撤单失败）：
```json
{
  "type": "cancel_result",
  "data": {
    "orderId": "1234567890123456789",
    "result": "REJECT",
    "reason": "ORDER_ALREADY_FILLED"
  }
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

**Token刷新规则**：
1. **refreshToken一次性使用**：刷新后旧的refreshToken立即失效，返回新的accessToken和refreshToken
2. **并发刷新处理**：若同一refreshToken被多次并发刷新，只有第一个请求成功（返回新token），后续请求返回错误码1006（Token已失效）
3. **accessToken过渡期**：旧的accessToken在过期前仍可使用（不强制立即失效），客户端应在过期前主动刷新
4. **刷新时机建议**：客户端应在accessToken过期前5分钟主动刷新，避免请求中断
5. **refreshToken有效期**：7天，过期后需重新登录

**JWT刷新并发处理实现**：

使用内存ConcurrentHashMap记录refreshToken的使用状态：

| 字段 | 类型 | 说明 |
|------|------|------|
| refreshToken | String | JWT refreshToken字符串 |
| status | enum | UNUSED/USED/EXPIRED |
| usedTime | long | 使用时间戳 |
| newAccessToken | String | 刷新后生成的新accessToken（仅USED状态有效） |

刷新流程：
```
1. 接收刷新请求，提取refreshToken
2. 检查refreshToken状态：
   - 若status == UNUSED：
     a. 使用computeIfAbsent原子更新：status → USED，记录usedTime
     b. 生成新的accessToken和refreshToken
     c. 将旧的refreshToken标记为USED
     d. 返回新token
   - 若status == USED：
     a. 返回错误码1006（Token已失效）
     b. 记录并发刷新尝试日志（包含tokenId、时间戳）
   - 若status == EXPIRED：
     a. 返回错误码1005（Token过期）
     b. 要求客户端重新登录

3. 定期清理过期记录：
   - 每小时清理usedTime超过7天的记录
   - 避免内存无限增长
```

**并发刷新原子性保证**：
- 使用ConcurrentHashMap.computeIfAbsent()或.putIfAbsent()确保原子更新
- 多个并发请求同时到达时，只有一个能成功更新状态（返回新token）
- 其他请求发现状态已变为USED，返回错误码1006

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
  "messageId": "client-uuid-001",    // 客户端生成的唯一标识，用于关联响应
  "channel": "market_snapshot",
  "symbols": ["600000"]
}
```

**取消订阅消息**:
```json
{
  "type": "unsubscribe",
  "messageId": "client-uuid-002",    // 客户端生成的唯一标识
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

**心跳超时机制**：
- 客户端应每30秒发送一次ping
- 服务端收到ping后立即响应pong
- 若服务端90秒（3个心跳周期）未收到ping，主动断开连接
- 若客户端30秒未收到pong，视为连接异常，应重新连接
- WebSocket close code 1001（Going Away）用于正常超时断开

**错误消息**:
```json
{
  "type": "error",
  "messageId": "client-uuid-001",    // 关联原始请求的messageId
  "code": "1001",
  "message": "Invalid symbol format",
  "channel": "market_snapshot",
  "timestamp": "1716460800000"
}
```

**订阅确认消息**:
```json
{
  "type": "subscribe_confirm",
  "messageId": "client-uuid-001",    // 与订阅请求的messageId对应
  "channel": "market_snapshot",
  "symbols": ["600000"],
  "timestamp": "1716460800000"
}
```

**取消订阅确认消息**:
```json
{
  "type": "unsubscribe_confirm",
  "messageId": "client-uuid-002",    // 与取消订阅请求的messageId对应
  "channel": "market_snapshot",
  "symbols": ["600000"],
  "timestamp": "1716460800000"
}
```

**messageId使用规则**：
- messageId由客户端生成，建议使用UUID v4格式
- 每个请求消息必须包含唯一的messageId
- 服务端响应消息（subscribe_confirm、unsubscribe_confirm、error）会回传相同的messageId
- 客户端可通过messageId关联请求和响应，处理网络乱序场景

**messageId适用场景**：

| 消息类型 | 是否需要messageId | 说明 |
|---------|------------------|------|
| subscribe | ✅ 需要 | 请求-响应场景，客户端需要确认订阅结果 |
| unsubscribe | ✅ 需要 | 请求-响应场景，客户端需要确认取消结果 |
| error | ✅ 需要 | 错误响应关联原始请求 |
| subscribe_confirm | ✅ 需要 | 响应消息，回传请求的messageId |
| unsubscribe_confirm | ✅ 需要 | 响应消息，回传请求的messageId |
| trade_update | ❌ 不需要 | 服务端主动推送，无需客户端关联 |
| order_update | ❌ 不需要 | 服务端主动推送，无需客户端关联 |
| account_update | ❌ 不需要 | 服务端主动推送，无需客户端关联 |
| position_update | ❌ 不需要 | 服务端主动推送，无需客户端关联 |
| market_snapshot | ❌ 不需要 | 服务端主动推送，无需客户端关联 |
| kline | ❌ 不需要 | 服务端主动推送，无需客户端关联 |
| ping/pong | ❌ 不需要 | 心跳消息，无需关联 |

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

**kline K线数据推送**:
```json
{
  "type": "kline",
  "data": {
    "symbol": "600000",
    "interval": "1m",              // K线周期：1m/5m/15m/30m/1h/1d
    "open": "1050",
    "high": "1055",
    "low": "1048",
    "close": "1053",
    "volume": "10000",
    "timestamp": "1716460800000"
  }
}
```

**K线订阅方式**：
```json
{
  "type": "subscribe",
  "messageId": "client-uuid-003",
  "channel": "kline",
  "symbols": ["600000"],
  "interval": "1m"               // 指定K线周期
}
```

**支持的K线周期**：1m（1分钟）、5m（5分钟）、15m（15分钟）、30m（30分钟）、1h（1小时）、1d（1日）

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
| CQ写入 | account_input | 0.05-0.1ms |
| 队列等待 | CompletableFuture | 取决于队列深度 |
| AccountWorker冻结 | 内存更新+CQ写入 | 0.1-0.2ms |
| 唤醒HTTP | complete | 0.05ms |

**AccountWorker单次操作耗时**：

| 操作 | 耗时 |
|------|------|
| CQ读取 | ~5μs |
| 内存更新账户 | ~0.5μs |
| 内存更新持仓 | ~0.5μs |
| CQ写入matching_input | ~5μs |
| 唤醒HTTP | ~5μs |
| **总计** | ~15-20μs |

### 11.2 端到端延迟分解

**下单 → 成交回报 完整链路**：

```
阶段                          延迟(P99)      累计
───────────────────────────────────────────────────
HTTP接收+校验                  0.5ms         0.5ms
写入account_input CQ           0.1ms         0.6ms
队列等待+AccountWorker处理     1.0ms         1.6ms
写入matching_input CQ          0.1ms         1.7ms
队列等待+MatchingEngine撮合    2.0ms         3.7ms
写入matching_output CQ         0.1ms         3.8ms
队列等待+ClearingWorker清算    1.5ms         5.3ms
写入account_settlement CQ      0.1ms         5.4ms
队列等待+AccountWorker结算      1.0ms         6.4ms
WebSocket推送成交回报          0.5ms         6.9ms
───────────────────────────────────────────────────
端到端P99延迟                  ~7ms          < 15ms ✓
```

### 11.3 吞吐量分析

**逐个处理吞吐**：40-50K TPS（受限于ClearingWorker）

**批量处理吞吐**：

| 组件 | 逐个处理 | 批量处理(AccountWorker每批50/ClearingWorker每批100) | 瓶颈分析 |
|------|---------|------------------------------------------------------|---------|
| AccountWorker | 65K TPS | 100K+ TPS | 内存操作为主 |
| MatchingEngine | 65K TPS | 100K+ TPS | OrderBook操作 + 撮合 |
| ClearingWorker | 50K TPS | 80K+ TPS | 写入SETTLEMENT事件×2，批量100条优化 |
| **系统整体** | **40-50K TPS** | **100K TPS** | ClearingWorker是瓶颈 |

**ClearingWorker瓶颈分析**：

每笔成交需：
1. 读取trade事件
2. 更新买卖双方订单状态
3. 计算双方佣金
4. 写入两条SETTLEMENT事件到account_settlement CQ

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

### 11.6 幂等机制对性能的影响

| 组件 | 幂等存储 | 查询延迟 | 内存占用 |
|------|---------|---------|---------|
| MatchingEngine | 内存Set（最新10,000个） | ~0.5μs | ~500KB |
| ClearingWorker | 内存Set（最新10,000个） | ~0.5μs | ~500KB |
| AccountWorker | 内存Set（最新10,000个） | ~0.5μs | ~500KB |

**影响评估**：幂等检查为内存操作，延迟可忽略，对吞吐影响 <5%。

### 11.7 内存使用

| 组件 | 预估内存 |
|------|---------|
| Chronicle Map快照 | 2-4GB |
| 内存OrderBook | 500MB-1GB |
| JVM堆 | 4-6GB |
| CQ缓冲 | 1-2GB |
| 幂等集合（内存） | ~1.5MB |
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

| CQ名称 | path | blockSize | bufferSize | rollCycle | wireType | writeMode | epochOnRoll | sparseFile |
|--------|------|-----------|------------|-----------|----------|-----------|-------------|------------|
| account_input | data/chronicle/account_input | 128MB | 256KB | HOURLY | BINARY | SYNC | true | false |
| account_output | data/chronicle/account_output | 64MB | 128KB | HOURLY | BINARY | SYNC | true | false |
| matching_input | data/chronicle/matching_input | 64MB | 128KB | HOURLY | BINARY | SYNC | true | false |
| matching_output | data/chronicle/matching_output | 64MB | 128KB | HOURLY | BINARY | SYNC | true | false |
| account_settlement | data/chronicle/account_settlement | 64MB | 128KB | HOURLY | BINARY | SYNC | true | false |

**CQ参数说明**：

| 参数 | 说明 |
|------|------|
| blockSize | 内存映射文件块大小 |
| bufferSize | 写缓冲大小 |
| rollCycle | 滚动周期（每小时一个文件） |
| wireType | 序列化格式 |
| writeMode | SYNC表示同步写入，确保每条消息写入后立即刷盘 |
| epochOnRoll | 文件滚动时确保数据完整，防止滚动期间数据丢失 |
| sparseFile | false表示禁用稀疏文件，确保数据立即写入磁盘 |

**RPO=0关键配置**：

writeMode=SYNC + epochOnRoll=true + sparseFile=false 共同确保RPO=0：
- writeMode=SYNC：每条消息写入后立即持久化到磁盘
- epochOnRoll=true：文件滚动时确保数据完整，防止滚动期间数据丢失
- sparseFile=false：禁用稀疏文件，确保数据立即写入

性能优化应通过批量处理实现（AccountWorker批量50条，ClearingWorker批量100条），而非牺牲数据持久性。

### 12.4 Chronicle Map配置

**快照文件结构**：

| 文件 | entries | averageValueSize | 预估大小 | 说明 |
|------|---------|------------------|----------|------|
| account_snapshot_{index}.dat | 10,000 | 2KB | ~20MB | 账户+持仓+索引 |
| matching_snapshot_{index}.dat | 5,000 | 64KB | ~320MB | OrderBook+索引 |

**幂等集合内存配置**：

| 幂等集合 | 内存限制 | 来源 |
|---------|---------|------|
| processed_order_ids | 最新10,000个 | matching_input CQ重放生成 |
| processed_trade_ids | 最新10分钟内的trade_id | account_settlement CQ重放生成 |
| processed_settlement_ids | 最新10,000个 | 内存维护 |

**说明**：幂等集合不再使用Chronicle Map持久化，恢复时从CQ重放生成。processed_trade_ids使用时间窗口限制（10分钟），避免内存无限增长。

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
| CQ队列深度 | account_input积压 | > 200 |
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
| 1004 | Token无效 | JWT格式错误/签名验证失败 |
| 1005 | Token过期 | access token已过期 |
| 1006 | Token已失效 | refreshToken已使用或被撤销 |
| 1007 | 请求过于频繁 | 触发限流 |
| 1008 | 服务繁忙 | 系统负载过高 |
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
| 4005 | 非交易日/系统暂停 | 非交易日或系统未就绪（09:15启动等待开盘、非交易日启动等） |
| 4006 | 系统关闭中 | 优雅关闭期间拒绝新订单 |
| 5001 | 数据写入失败 | CQ/H2写入异常 |
| 9999 | 系统异常 | 未知错误 |

**错误码分类**：
- 1xxx：客户端错误（参数、认证、限流）
- 2xxx：账户相关错误
- 3xxx：订单相关错误
- 4xxx：交易规则错误
- 5xxx：服务端错误
- 9999：未知错误

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

**校验规则**：

1. 计算涨停价：`limitUp = prevClose × (1 + limitUpRatio)`
2. 计算跌停价：`limitDown = prevClose × (1 - limitDownRatio)`
3. 若订单价格 > 涨停价，拒绝订单（错误码4002）
4. 若订单价格 < 跌停价，拒绝订单（错误码4002）

### 15.2 自成交预防

**设计要求**：

自成交在订单写入account_input CQ前校验，撮合引擎不校验自成交。

**校验逻辑**：

1. 获取账户+品种锁（防止竞态条件）
2. 检查是否存在自己账户的对手方订单：
   - 买单：检查是否存在己方卖单且价格 ≤ 买单价格
   - 卖单：检查是否存在己方买单且价格 ≥ 卖单价格
3. 若存在，拒绝订单（错误码4004）

**撮合引擎不校验自成交**：订单进入撮合引擎后，假设已通过自成交校验，直接进行价格优先、时间优先撮合。

### 15.3 T+1规则

**买入锁定**：

A股买入后T+1日才能卖出：
- 买入成交时：`pos.t1Locked += quantity`
- 当日买入的股票被锁定，次日SOD解锁

**SOD解锁**：

每日SOD时执行T+1解锁：
- 遍历所有持仓
- `pos.available += pos.t1Locked`
- `pos.t1Locked = 0`

### 15.4 佣金计算

**计算规则**：

| 品种类型 | 计算方式 |
|---------|---------|
| 股票 | 万分之三，按成交金额比例计算 |
| 期货 | 按手数计算：commissionPerLot × 手数 |

**计算公式**（股票）：
- `comm = amount × 3 / 10000`（amount为成交金额，单位：分）
- 无最低佣金限制，按实际比例计算

**部分成交佣金说明**：
- 每次成交独立计算佣金
- 部分成交可能导致累计佣金略高于一次性成交（因多次按比例计算）
- 佣金在结算时从成交金额中扣除

### 15.5 风控与强平机制

**股票账户风控**：

股票账户不计算风险比率，无保证金机制。风控仅检查：
- 账户余额是否为负（`balance < 0` 时标记为 `NEGATIVE_BALANCE` 状态，禁止交易）
- 持仓是否合理（持仓数量不为负）

**期货/期权账户风控**：

计算风险比率，触发强平。

**风险度计算**：
- `riskRatio = marginFrozen / (balance + unrealizedPnl)`
- 若 `netAssets <= 0`，返回最大值

**阈值设定**：

| 阈值 | 值 | 处理 |
|------|-----|------|
| 强平阈值 | 100% | 触发强平 |
| 预警阈值 | 80% | 发送风险预警 |

**强平流程**：

1. 更新账户状态为 LIQUIDATING
2. 生成强平订单（卖出所有持仓）：
   - side = SELL
   - orderType = MARKET
   - quantity = available + frozen
   - isSystemOrder = true
   - reason = "LIQUIDATION"
3. 写入account_input CQ
4. WebSocket推送强平通知

**强平订单特殊处理**：

AccountWorker处理强平订单时：
- 跳过常规风控检查
- 直接冻结持仓（无可用持仓检查）
- 若可用不足，从冻结中划转

---

## 16. SOD与EOD流程

### 16.1 SOD流程

```
1. 检查是否存在当日CQ文件
   // CQ按小时滚动，目录结构为 data/chronicle/{cq_name}/
   // 检查account_input CQ是否有当日文件（格式：YYYYMMDD-HH.cq4）
   cqFiles = listFiles("data/chronicle/account_input/" + date + "*.cq4");

   if (cqFiles.isEmpty()) {
       // 新交易日，从上一交易日快照恢复
       // 加载上一交易日最终快照
       loadPrevDaySnapshot();

       // T+1解锁：昨日买入的股票今日可卖
       unlockT1Positions();

       // 清空订单簿和订单（上一日的订单已在EOD撤销）
       orderBooks.clear();
       orderStore.clear();

       // 清空幂等集合（新交易日从头开始）
       processed_order_ids.clear();
       processed_trade_ids.clear();
       processed_settlement_ids.clear();

   } else {
       // 当日CQ已存在（重启恢复）
       // 从快照 + CQ增量恢复
       recoverFromSnapshotAndCQ();
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

6. 进入连续竞价
   phaseManager.setPhase(CONTINUOUS_TRADING);
```

**新交易日启动流程**：

```
startNewTradingDay(date):
    // 检查是否有当日的CQ文件
    cqFiles = listFiles("data/chronicle/account_input/" + date + "*.cq4")

    if (cqFiles.isEmpty()):
        // 新交易日
        // 1. 加载上一交易日最终快照
        prevDate = getPreviousTradingDay(date)

        if (hasSnapshotForDate(prevDate)):
            loadPrevDaySnapshot(prevDate)

            // 2. T+1解锁
            unlockT1Positions()

        // 3. 清空订单簿和订单（上一日的订单已在EOD撤销）
        orderBooks.clear()
        orderStore.clear()

        // 4. 幂等集合初始化（新交易日清空）
        processed_order_ids.clear()       // 新交易日，从头开始
        processed_trade_ids.clear()       // 新交易日，从头开始
        processed_settlement_ids.clear()  // 新交易日，从头开始

        // CQ文件由Chronicle Queue按小时滚动自动创建

        log.info("New trading day {} initialized with T+1 unlock", date)
    else:
        // 当日重启
        recoverFromSnapshotAndCQ()
        // 幂等集合从CQ重放生成（在recoverFromSnapshotAndCQ内部处理）
        log.info("Trading day {} recovered from snapshot+CQ", date)
```

**CQ目录结构说明**：

CQ文件按小时滚动，目录结构为：
```
data/chronicle/
├── account_input/
│   ├── 20260531-09.cq4    # 9点时段的文件
│   ├── 20260531-10.cq4    # 10点时段的文件
│   └── metadata.cq4
├── account_output/
│   └── ...
├── matching_input/
│   └── ...
├── matching_output/
│   └── ...
└── account_settlement/
    └── ...
```

**交易日判断逻辑**：

SOD判断是否为新交易日，需结合多个条件：

| 条件 | 检查方法 | 说明 |
|------|---------|------|
| CQ文件检查 | 检查 `data/chronicle/account_input/{YYYYMMDD}*.cq4` 是否存在 | 若不存在，可能是新交易日 |
| 交易日历校验 | 查询H2交易日历表，确认当前日期是否为交易日 | 非交易日不启动交易流程 |
| 快照日期比对 | 读取最新快照文件的create_time，比对与当前日期 | 若快照日期 < 当前日期，为新交易日 |
| PHASE_CHANGE事件 | 恢复时检查是否有PHASE_CHANGE事件标记交易日 | 作为辅助判断 |

**判断流程**：
```
SOD判断交易日：
1. 获取当前日期和时间：currentDate = getSystemDate(), currentTime = getSystemTime()
2. 查询交易日历：若currentDate非交易日，等待下一交易日
3. 检查CQ文件：cqFiles = listFiles("data/chronicle/account_input/" + currentDate + "*.cq4")
4. 检查快照日期：snapshotDate = getLatestSnapshotDate()
5. 判断结果：
   - 若cqFiles.isEmpty() && snapshotDate < currentDate：新交易日启动
   - 若cqFiles非空 || snapshotDate == currentDate：当日重启恢复
   - 若cqFiles.isEmpty() && snapshotDate == currentDate：系统异常，需人工介入

6. 启动时刻边界处理：
   - 检查currentTime是否在交易时段内（如A股：09:30-11:30, 13:00-15:00）
   - 若在交易时段内：
     a. 恢复完成后直接进入CONTINUOUS_TRADING阶段
     b. 跳过waitForMarketOpen等待
   - 若在交易时段外：
     a. 恢复完成后进入CLOSED阶段
     b. 等待下一个交易时段开始
   - 若在非交易日启动：
     a. 恢复完成后进入CLOSED阶段
     b. 等待下一交易日

7. 特殊场景处理：
   - 系统在09:15启动（集合竞价时段，本系统不支持）：
     a. 恢复完成后进入CLOSED阶段
     b. HTTP接口返回错误码4005（系统未就绪，等待开盘）
     c. 等待至09:30进入CONTINUOUS_TRADING，开始接收订单
   - 系统在11:30启动（午休时段）：
     a. 恢复完成后进入CLOSED阶段
     b. HTTP接口返回错误码4005（系统未就绪，等待开盘）
     c. 等待至13:00进入CONTINUOUS_TRADING
   - 系统在15:00之后启动：
     a. 恢复完成后进入CLOSED阶段
     b. 等待下一交易日

8. 非交易日启动后等待逻辑：
   - PhaseManager每小时检查交易日历：
     a. 若当前日期非交易日，继续等待
     b. 若下一交易日到达，触发SOD流程
   - HTTP接口在非交易日返回错误码4005（非交易日，系统暂停）
   - 系统保持CLOSED阶段，不执行EOD归档（无交易数据）
```

**跨日边界处理**：
- CQ文件命名使用自然日期（YYYYMMDD-HH），而非交易日期
- 文件滚动时间：每小时整点（如09:00、10:00）
- 跨日场景：若前一交易日CQ文件存在（如20260530-15.cq4），且当前日期CQ文件不存在，判断为新交易日
- 特殊情况：若前一交易日跨越午夜（如夜盘交易），需特殊处理（本系统不支持夜盘）

### 16.2 EOD流程

```
1. 市场收盘
   phaseManager.setPhase(CLOSED);

2. 停止接收订单
   HTTP返回错误码4001（当前阶段不允许下单）;

3. 等待队列清空
   awaitQueueEmpty(5000);  // 最长5秒

4. 跨日清理：撤销所有未成交订单（通过CANCEL_ORDER事件）
   cancelAllPendingOrders();

5. 等待撤单处理完成
   // 动态计算等待时间：根据未成交订单数量
   pendingCount = getPendingOrderCount();
   waitTime = max(10000, pendingCount * 100);  // 至少10秒，每单额外100ms
   awaitQueueEmpty(waitTime);
   
   // 超时处理：若超时仍有未处理订单，记录告警并继续
   if (timeout && hasRemainingOrders()):
       log.warn("EOD撤单超时，剩余{}笔订单未处理", remainingCount);
       发送P1告警;

6. 执行最终快照
   snapshotWorker.forceSnapshot();

7. 计算并写入当日结算数据
   // 计算当日盈亏、更新持仓成本
   calculateDailyPnl();

   // 写入H2历史表（订单、成交、资金流水）
   writeToH2History();

8. 幂等集合处理
   // 内存中仅保留最新10,000个ID
   // 新交易日开始时通过CQ重放重新生成

9. 关闭工作线程
   accountWorker.stop();
   matchingEngine.stop();
   clearingWorker.stop();
   snapshotWorker.stop();

10. 归档CQ文件
    // 归档流程
    archiveDailyData():
    
    **归档目录结构**：
    ```
    data/archive/
    ├── 20260531/                     # 交易日期目录
    │   ├── account_input/            # CQ文件归档
    │   │   ├── 20260531-09.cq4
    │   │   ├── 20260531-10.cq4
    │   │   └── ...
    │   ├── matching_input/
    │   ├── matching_output/
    │   ├── account_output/
    │   ├── account_settlement/
    │   └── snapshots/                # 快照文件归档
    │       ├── account_snapshot_*.dat
    │       ├── matching_snapshot_*.dat
    │       └── *.checksum
    └── 20260530/
        └── ...
    ```
    
    **归档时机**：EOD完成后执行归档
    
    **归档步骤**：
    1. 创建当日归档目录：data/archive/{YYYYMMDD}/
    2. 移动当日CQ文件到归档目录（保留原目录结构）
    3. 移动当日快照文件到归档目录
    4. 更新归档索引文件：data/archive/archive_index.dat
    5. 清理data/chronicle/和data/snapshots/目录
    
    **归档失败处理**：
    - 记录错误日志（包含失败的文件名、错误信息）
    - 发送P1告警（归档失败）
    - 继续后续归档步骤（部分成功）
    - **不清理失败的原文件**：保留data/chronicle/和data/snapshots/中归档失败的文件，作为补归档来源
    - 仅清理归档成功的文件
    - 次日SOD时检查归档完整性，从残留文件补归档

    **归档清单文件**：

    为支持归档完整性检查，EOD归档时写入归档清单文件：

    文件路径：`data/archive/{YYYYMMDD}/archive_manifest.dat`

    清单内容：
    | 字段 | 类型 | 说明 |
    |------|------|------|
    | archiveDate | String | 归档日期（YYYYMMDD） |
    | archiveTime | long | 归档时间戳 |
    | cqFiles | List<String> | 应归档的CQ文件列表（相对路径） |
    | snapshotFiles | List<String> | 应归档的快照文件列表（相对路径） |
    | archivedFiles | List<String> | 实际已归档的文件列表 |
    | failedFiles | List<String> | 归档失败的文件列表 |
    | checksum | String | 清单文件MD5校验和 |

    **SOD归档完整性检查流程**：

    ```
    SOD时检查归档完整性：
    1. 读取上一交易日归档清单：manifest = readArchiveManifest(prevDate)
    2. 比对清单与实际文件：
       expectedFiles = manifest.cqFiles + manifest.snapshotFiles
       actualFiles = listFiles("data/archive/" + prevDate)
       missingFiles = expectedFiles - actualFiles

    3. 处理缺失文件：
       if (missingFiles非空):
           - 记录告警日志（缺失文件列表）
           - 尝试从以下来源补归档：
             a. data/chronicle/目录残留文件（文件名含日期前缀，避免与当日文件混淆）
             b. data/snapshots/目录残留文件（文件名含日期前缀）
             c. 备份存储（如有）
           - 补归档时按文件日期前缀匹配，确保不误归档当日文件
           - 若补归档成功：更新归档清单
           - 若补归档失败：发送P0告警，人工介入

    4. 清理残留文件：
       - 清理data/chronicle/目录中已归档的文件（按日期前缀匹配）
       - 清理data/snapshots/目录中已归档的文件（按日期前缀匹配）
       - 保留当日新产生的文件
    ```

    **CQ文件命名约定避免竞态**：
    - CQ文件命名格式：`{YYYYMMDD}-{HH}.cq4`（如`20260601-09.cq4`）
    - 归档目录格式：`data/archive/{YYYYMMDD}/`
    - 补归档时按日期前缀匹配，确保不同日期文件不会混淆
    - 即使EOD归档失败后立即重启，SOD也能通过日期前缀区分残留文件
    
    **历史数据查询**：
    - 通过H2历史表查询订单、成交、资金流水
    - 归档CQ文件仅用于灾难恢复，不参与日常查询

11. 关闭CQ和H2
    closeAll();

12. 生成日终报告
    generateDailyReport();
```

**跨日清理：通过CANCEL_ORDER事件撤销所有未成交订单**：

**设计要求**：

EOD时撤销所有未成交订单，所有订单（包括PENDING状态）都通过写入CANCEL_ORDER事件到account_input CQ，复用现有撤单清结算逻辑，确保所有撤单操作都经过CQ持久化，保证RPO=0。

**处理流程**：

1. 遍历所有订单
2. 对于所有未成交订单（PENDING/NEW/PARTIAL_FILLED状态）：
   * 写入CANCEL_ORDER事件到account_input CQ
   * 设置reason为"EOD_AUTO_CANCEL"
   * 正常走撤单清结算流程：
     - AccountWorker处理CANCEL_ORDER事件
     - 写入CANCEL_ORDER到matching_input CQ
     - MatchingEngine处理撤单：
       - 对于NEW/PARTIAL_FILLED状态订单：正常撤销，生成CANCEL_SUCCESS
       - 对于PENDING状态订单（不在OrderBook中）：生成CANCEL_REJECT，rejectReason = PENDING_ORDER_NOT_IN_ORDERBOOK
     - ClearingWorker处理撤单结果：
       - 对于CANCEL_SUCCESS：正常生成UNFREEZE事件
       - 对于CANCEL_REJECT（rejectReason = PENDING_ORDER_NOT_IN_ORDERBOOK）：
         * 检查订单当前状态，若为PENDING或NEW：
           - 生成UNFREEZE事件到account_settlement CQ
           - **unfreezeAmount计算**：买单 = order.frozenAmount，卖单 = 0
           - **unfreezeQty计算**：买单 = 0，卖单 = order.remainingQty
         * 若订单状态已变更（如已成交或已撤销）：忽略，记录日志
3. 等待队列清空（最长10秒）

**PENDING状态订单EOD撤单流程说明**：

```
EOD撤单流程（PENDING状态订单）：

1. EOD流程 → 写入CANCEL_ORDER到account_input CQ
2. AccountWorker → 处理CANCEL_ORDER → 写入CANCEL_ORDER到matching_input CQ
3. MatchingEngine → 处理CANCEL_ORDER：
   - 发现订单不在OrderBook中（因为PENDING状态订单未入OrderBook）
   - 生成CANCEL_REJECT事件，rejectReason = PENDING_ORDER_NOT_IN_ORDERBOOK
   - 写入matching_output CQ
4. ClearingWorker → 处理CANCEL_REJECT：
   - 检查rejectReason = PENDING_ORDER_NOT_IN_ORDERBOOK
   - 查询订单当前状态
   - 若状态为PENDING或NEW：
     * 生成UNFREEZE事件到account_settlement CQ
     * unfreezeAmount = order.frozenAmount（买单）或 0（卖单）
     * unfreezeQty = 0（买单）或 order.remainingQty（卖单）
   - 若状态已变更（FILLED/CANCELLED）：忽略，记录日志
5. AccountWorker → 处理UNFREEZE事件：
   - 解冻资金/持仓
   - 更新订单状态为CANCELLED
   - WebSocket推送撤单结果
```

**归档幂等存储**：

幂等集合内存中自动维护，使用LinkedHashSet实现：
- `processed_order_ids`: 从matching_input CQ lastIndex→latest重放生成，最新10,000个
- `processed_trade_ids`: 从account_settlement CQ重放生成，仅保留最新10分钟内的trade_id
- `processed_settlement_ids`: 从account_settlement CQ重放生成，最新10,000个

**EOD幂等集合处理**：
- EOD时幂等集合无需特殊处理
- 新交易日SOD时：
  - 若新交易日启动：清空所有幂等集合
  - 若当日重启：从CQ重放重新生成

**T+1锁定处理**：

EOD时，当日买入的股票已被标记为 `t1Locked`，无需额外处理。次日SOD时会解锁。

### 16.3 SOD恢复场景

| 场景 | CQ文件状态 | 恢复方式 |
|------|-----------|---------|
| **新交易日启动** | 不存在当日CQ | 从上一交易日快照恢复 + T+1解锁 |
| **当日重启** | 已存在当日CQ | 从CM快照恢复 + 重放CQ增量 |
| **Crash重启** | 已存在当日CQ | 从CM快照恢复 + 重放CQ增量（RPO=0） |

---

## 17. 快照与恢复机制

### 17.1 快照机制概述

**核心设计**：通过 SNAPSHOT 事件触发快照，各Worker独立维护快照文件，独立恢复。

| Worker | 输入CQ | 输出CQ | 快照文件 | 快照内容 |
|--------|--------|--------|---------|---------|
| AccountWorker | `account_input.cq4` | `account_output.cq4`, `matching_input.cq4` | `account_snapshot_{index}.dat` | accounts, positions, account_input index, account_settlement index, matching_input lastIndex |
| MatchingEngine | `matching_input.cq4` | `matching_output.cq4` | `matching_snapshot_{index}.dat` | matching_input index, matching_output lastIndex, active orders per symbol |
| ClearingWorker | `matching_output.cq4` | `account_settlement.cq4` | 无 | - |

**独立快照恢复设计**：

各Worker拥有独立的快照文件，恢复时从各自的最新快照开始独立重放CQ增量：

| Worker | 快照文件 | 恢复起始位置 | 说明 |
|--------|---------|-------------|------|
| AccountWorker | `account_snapshot_{index}.dat` | account_input_index + 1 | 从快照记录的account_input CQ位置开始重放 |
| MatchingEngine | `matching_snapshot_{index}.dat` | matching_input_index + 1 | 从快照记录的matching_input CQ位置开始重放 |
| ClearingWorker | 无 | matching_output CQ起始位置 | 直接重放matching_output CQ |

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

**Replay Mode机制**：

恢复时AccountWorker和MatchingEngine默认`replay_mode=true`，用于跳过已写入output CQ的重复数据。

| Worker | replay_mode初始值 | 退出条件 | 说明 |
|--------|------------------|---------|------|
| AccountWorker | true | 首次写入新数据到matching_input CQ时设为false | 恢复时跳过已写入matching_input的订单 |
| MatchingEngine | true | 首次写入新trade_id到matching_output CQ时设为false | 恢复时跳过已写入matching_output的成交 |

**Replay Mode行为说明**：

| replay_mode值 | AccountWorker行为 | MatchingEngine行为 |
|--------------|-------------------|-------------------|
| true | 写入matching_input前检查order_id是否已存在，已存在则跳过 | 撮合时检查trade_id是否已存在，已存在则跳过TRADE事件写入 |
| false | 正常处理，无需检查order_id是否已存在 | 正常撮合，无需检查trade_id |

**Replay Mode退出逻辑**：

AccountWorker恢复时：
- 从快照获取matching_input_last_index
- 读取matching_input CQ从lastIndex→latest，生成processed_order_ids集合
- 重放account_input CQ时，遇到订单事件：
  - 若replay_mode=true且order_id在processed_order_ids中，跳过写入matching_input
  - 若replay_mode=true且order_id不在processed_order_ids中，写入matching_input，设置replay_mode=false
  - 若replay_mode=false，正常写入matching_input

MatchingEngine恢复时：
- 从快照获取matching_output_last_index
- 读取matching_output CQ从起始→lastIndex，生成existing_trade_ids集合
- 重放matching_input CQ时，撮合生成TRADE事件：
  - 若replay_mode=true且trade_id在existing_trade_ids中，跳过写入matching_output
  - 若replay_mode=true且trade_id不在existing_trade_ids中，写入matching_output，设置replay_mode=false
  - 若replay_mode=false，正常写入matching_output

**幂等机制设计**：

| 幂等集合 | 来源 | 内存限制 | 用途 |
|---------|------|---------|------|
| `processed_order_ids` | `matching_input.cq4` lastIndex→latest | 最新10,000个 | AccountWorker在replay_mode时跳过已写入订单 |
| `existing_trade_ids` | `matching_output.cq4` lastIndex→latest | 最新10,000个 | MatchingEngine在replay_mode时跳过已写入成交 |
| `processed_trade_ids` | `account_settlement.cq4` | 最新10分钟内的trade_id | AccountWorker结算时兜底幂等检查 |
| `processed_settlement_ids` | `account_settlement.cq4` lastIndex→latest | 最新10,000个 | AccountWorker跳过已处理结算 |

**幂等集合说明**：
- `existing_trade_ids`：MatchingEngine恢复时检查，避免写入重复的TRADE事件
- `processed_trade_ids`：AccountWorker处理SETTLEMENT时做兜底幂等检查，防止重复结算。ClearingWorker不做幂等检查
- 幂等集合使用LinkedHashSet实现，processed_trade_ids使用时间窗口限制（10分钟），其他使用数量限制（FIFO淘汰）

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

**快照原子写入流程**：

1. 创建临时文件：`account_snapshot_{index}.tmp`
2. 写入快照数据到临时文件：
   - accounts序列化
   - positions序列化
   - account_input_index, account_settlement_index, matching_input_last_index
3. 计算MD5校验和，写入`account_snapshot_{index}.tmp.checksum`
4. 原子重命名：
   - `account_snapshot_{index}.tmp` → `account_snapshot_{index}.dat`
   - `account_snapshot_{index}.tmp.checksum` → `account_snapshot_{index}.dat.checksum`
5. 后续事件在下一批次处理

**订单数据不保存说明**：
- AccountWorker快照不保存订单数据，减少快照大小和写入时间
- 恢复时从matching_input.cq4读取快照index到latest，重建已发送撮合的订单校验源
- PENDING状态订单通过重放account_input CQ恢复（冻结操作会重放执行）

**快照文件命名**: `account_snapshot_{account_input_cq_index}.dat`

**恢复流程**：

1. 查找最新有效快照文件：
   - 搜索`data/snapshots/account_snapshot_*.dat`
   - 按index降序排列
   - 验证checksum（读取.dat文件，计算MD5，与.checksum文件比对）
   - 若校验失败，回退到上一个有效快照
   - 若无有效快照，初始化空状态

2. 加载快照到内存：
   - accounts → ConcurrentHashMap<Long, Account>
   - positions → ConcurrentHashMap<String, Position>
   - 获取account_input_index, account_settlement_index, matching_input_last_index

3. 设置replay_mode=true

4. 生成processed_order_ids：
   - 从matching_input CQ的matching_input_last_index位置读取到最新位置
   - 提取所有order_id，加入processed_order_ids集合
   - 内存中仅保留最新10,000个order_id

5. 生成processed_settlement_ids：
   - 从account_settlement CQ的account_settlement_index位置读取到最新位置
   - 提取所有settlement_id（tradeId + "_" + orderId），加入processed_settlement_ids集合
   - 内存中仅保留最新10,000个settlement_id

6. 重放account_input CQ从account_input_index+1开始：
   - 处理NEW_ORDER事件：
     - 若replay_mode=true：
       - 检查order_id是否在processed_order_ids中
       - 若在集合中，跳过写入matching_input CQ
       - 若不在集合中，写入matching_input CQ，设置replay_mode=false，正常处理后续事件
     - 若replay_mode=false：
       - 正常冻结资金/持仓
       - 写入matching_input CQ和account_output CQ
   - 处理CANCEL_ORDER/DEPOSIT等事件

7. 重放account_settlement CQ从account_settlement_index+1开始：
   - 处理SETTLEMENT事件：
     - 使用processed_settlement_ids幂等检查
     - 若已处理，跳过
     - 若未处理，执行结算转账，写入account_output CQ，加入processed_settlement_ids
   - 处理UNFREEZE事件：
     - 解冻资金/持仓
     - 写入account_output CQ

### 17.3 MatchingEngine 快照流程

**快照文件内容**：
- `matching_input_index`: `matching_input.cq4`处理位置
- `matching_output_last_index`: `matching_output.cq4`写入的最后位置（用于恢复时检测已存在的trade_id）
- `active_orders`: 各symbol的活跃订单（当前在OrderBook中的订单）

**快照原子写入流程**：

1. 创建临时文件：`matching_snapshot_{index}.tmp`
2. 写入快照数据到临时文件：
   - matching_input_index
   - matching_output_last_index
   - active_orders（各symbol的OrderBook状态）
3. 计算MD5校验和，写入`matching_snapshot_{index}.tmp.checksum`
4. 原子重命名：
   - `matching_snapshot_{index}.tmp` → `matching_snapshot_{index}.dat`
   - `matching_snapshot_{index}.tmp.checksum` → `matching_snapshot_{index}.dat.checksum`
5. 后续事件在下一批次处理

**快照文件命名**: `matching_snapshot_{matching_input_cq_index}.dat`

**恢复流程**：

1. 查找最新有效快照文件：
   - 搜索`data/snapshots/matching_snapshot_*.dat`
   - 按index降序排列
   - 验证checksum
   - 若校验失败，回退到上一个有效快照
   - 若无有效快照，初始化空OrderBook

2. 加载快照到内存：
   - active_orders → 各symbol的OrderBook（TreeMap）
   - 获取matching_input_index, matching_output_last_index

3. 设置replay_mode=true

4. 生成existing_trade_ids：
   - 从matching_output CQ起始位置读取到matching_output_last_index
   - 提取所有trade_id，加入existing_trade_ids集合
   - 内存中仅保留最新10,000个trade_id

5. 重放matching_input CQ从matching_input_index+1开始：
   - 处理NEW_ORDER事件：
     - 将订单加入OrderBook
     - 执行撮合，生成TRADE事件（tradeId = takerOrderId_makerOrderId）
     - 若replay_mode=true：
       - 检查trade_id是否在existing_trade_ids中
       - 若在集合中，跳过TRADE事件写入
       - 若不在集合中，写入TRADE事件到matching_output CQ，设置replay_mode=false
     - 若replay_mode=false：
       - 正常写入所有TRADE事件到matching_output CQ
     - 写入ORDER_UPDATE事件（status=NEW）
   - 处理CANCEL_ORDER事件：
     - 从OrderBook移除订单
     - 生成CANCEL_SUCCESS/CANCEL_REJECT事件写入matching_output CQ

**Replay Mode必要性**：
- 恢复时可能重复撮合已成交的订单（因为matching_input CQ重放）
- 通过trade_id确定性（takerOrderId_makerOrderId），可识别重复成交
- replay_mode确保不会写入重复TRADE事件，避免下游ClearingWorker重复处理

### 17.4 ClearingWorker 恢复机制

**ClearingWorker不生成快照**，不做幂等检查，恢复时直接重放matching_output CQ。

**恢复流程**：

1. ClearingWorker不使用replay_mode机制（不需要）

2. ClearingWorker不做幂等检查，直接处理TRADE事件生成SETTLEMENT事件

3. 从MatchingEngine快照获取matching_output_last_index作为重放起始位置：
   - matching_output_start_index = matchingEngineSnapshot.matching_output_last_index

4. 重放matching_output CQ从matching_output_start_index+1开始：
   - 处理ORDER_UPDATE事件：
     - 更新订单状态：PENDING → NEW 或 PARTIAL_FILLED → FILLED
     - 若订单已有filledQuantity > 0，根据实际情况更新为PARTIAL_FILLED/FILLED
     - WebSocket推送订单状态更新
   - 处理TRADE事件：
     - 直接处理，生成SETTLEMENT事件写入account_settlement CQ
   - 处理CANCEL_SUCCESS事件：
     - 生成UNFREEZE事件写入account_settlement CQ
   - 处理CANCEL_REJECT事件：
     - 检查rejectReason
     - 若为PENDING_ORDER_NOT_IN_ORDERBOOK：检查订单状态，若为PENDING/NEW则生成UNFREEZE事件
     - WebSocket推送撤单失败通知

**幂等机制**：
- ClearingWorker不做幂等检查，简化清算流程
- AccountWorker处理SETTLEMENT事件时使用processed_trade_ids做兜底幂等检查
- processed_trade_ids来源于account_settlement CQ，仅保留最新10分钟内的trade_id
- trade_id确定性：`trade_id = takerOrderId_makerOrderId`

### 17.5 快照事件生成

SnapshotWorker 定时向 account_input CQ 和 matching_input CQ 写入 SNAPSHOT 事件：

**快照间隔**: 60秒

**快照流程**：

1. 向account_input CQ写入SNAPSHOT事件（记录cqIndex）
2. 等待AccountWorker处理完快照：
   - 检查`account_snapshot_{cqIndex}.dat`文件是否存在
   - 验证checksum
   - 最长等待5秒
   - 若超时：
     * 记录警告日志（包含cqIndex、等待时间）
     * 发送告警（快照超时-P1级别）
     * 继续写入下一个SNAPSHOT事件（不阻塞系统运行）
     * 标记该快照点为"未确认"状态，下次快照时检查是否已完成
3. 向matching_input CQ写入SNAPSHOT事件（记录cqIndex）
4. 等待MatchingEngine处理完快照：
   - 检查`matching_snapshot_{cqIndex}.dat`文件是否存在
   - 验证checksum
   - 最长等待5秒
   - 若超时：同步骤2处理
5. 清理旧快照文件（保留最近3个）

**快照超时处理原则**：
- 快照超时不阻塞系统运行，避免影响正常交易
- 记录告警便于排查Worker积压问题
- 下次快照时检查上次未确认的快照是否已完成
- 若连续3次快照超时，升级为P0告警，触发人工介入

**校验和验证**：
- 每个快照文件写入后，计算MD5校验和并写入同名的.checksum文件
- 恢复时验证校验和，若校验失败则回退到上一个有效快照

**交易时段检测**：
- SnapshotWorker检测交易时段变化（如进入CONTINUOUS_TRADING或CLOSED状态）
- 写入PHASE_CHANGE事件到account_input CQ，触发相应状态转换

### 17.6 快照文件结构

```
data/snapshots/
├── account_snapshot_1001.dat         # AccountWorker在account_input_cq_index=1001时的快照
│   ├── accounts[]                    # 账户状态
│   ├── positions[]                   # 持仓状态
│   ├── account_input_index: long     # account_input.cq4处理位置
│   ├── account_settlement_index: long # account_settlement.cq4处理位置
│   └── matching_input_last_index: long # matching_input.cq4写入的最后位置
├── account_snapshot_1001.dat.checksum # MD5校验文件
├── matching_snapshot_2005.dat        # MatchingEngine在matching_input_cq_index=2005时的快照
│   ├── matching_input_index: long    # matching_input.cq4处理位置
│   ├── matching_output_last_index: long # matching_output.cq4写入的最后位置
│   └── active_orders: Map<symbol, List<Order>> # 各symbol活跃订单
├── matching_snapshot_2005.dat.checksum # MD5校验文件
```

**快照文件原子写入保证**：

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | 创建.tmp文件 | 先写临时文件，避免写入失败损坏原文件 |
| 2 | 写入快照数据 | 序列化accounts/positions/orders等 |
| 3 | 计算MD5校验和 | 确保数据完整性 |
| 4 | 写入.checksum文件 | 校验和单独存储 |
| 5 | 原子rename | tmp→dat原子操作，确保一致性 |

**恢复时checksum校验流程**：

1. 读取.dat文件内容
2. 计算MD5校验和
3. 读取.checksum文件内容
4. 比对校验和：
   - 若一致：快照有效，加载到内存
   - 若不一致：快照损坏，回退到上一个有效快照

**快照文件关联关系**：

| Worker | 快照文件 | 索引来源 | 恢复起始位置 |
|--------|---------|---------|-------------|
| AccountWorker | `account_snapshot_{index}.dat` | account_input CQ | account_input_index + 1 |
| MatchingEngine | `matching_snapshot_{index}.dat` | matching_input CQ | matching_input_index + 1 |
| ClearingWorker | 无 | - | matching_output CQ起始位置 |

**恢复时幂等集合生成**：

| 幂等集合 | 来源CQ | 读取范围 | 内存限制 | 用途 |
|---------|--------|---------|---------|------|
| `processed_order_ids` | `matching_input.cq4` | lastIndex → latest | 最新10,000个 | AccountWorker replay_mode检查 |
| `existing_trade_ids` | `matching_output.cq4` | 起始 → lastIndex | 最新10,000个 | MatchingEngine replay_mode检查 |
| `processed_trade_ids` | `account_settlement.cq4` | 最新10,000个 | 最新10,000个 | ClearingWorker replay_mode检查 |
| `processed_settlement_ids` | 内存维护 | - | 最新10,000个 | AccountWorker结算幂等 |

### 17.7 恢复流程

**恢复顺序**：AccountWorker → MatchingEngine → ClearingWorker → AccountWorker（第二轮处理SETTLEMENT）

各Worker独立从各自的最新快照恢复，独立重放CQ增量。

**1. AccountWorker恢复**：

- 查找最新有效快照文件（验证checksum，损坏则回退）
- 加载account_snapshot_{index}.dat到内存
- 设置replay_mode=true
- 生成processed_order_ids（从matching_input CQ lastIndex→latest）
- 生成processed_settlement_ids（从account_settlement CQ snapshot index→latest）
- 重放account_input CQ从account_input_index+1开始：
  - 处理NEW_ORDER事件：
    - 若replay_mode=true且order_id在processed_order_ids中：跳过写入matching_input
    - 若replay_mode=true且order_id不在processed_order_ids中：写入matching_input，设置replay_mode=false
    - 若replay_mode=false：正常处理，写入matching_input和account_output
  - 处理CANCEL_ORDER/DEPOSIT等事件

**2. MatchingEngine恢复**：

- 查找最新有效快照文件（验证checksum，损坏则回退）
- 加载matching_snapshot_{index}.dat到内存（恢复OrderBook）
- 设置replay_mode=true
- 生成existing_trade_ids（从matching_output CQ起始→lastIndex）
- 重放matching_input CQ从matching_input_index+1开始：
  - 处理NEW_ORDER事件：
    - 加入OrderBook，执行撮合
    - 若replay_mode=true且trade_id在existing_trade_ids中：跳过TRADE写入
    - 若replay_mode=true且trade_id不在existing_trade_ids中：写入TRADE，设置replay_mode=false
    - 若replay_mode=false：正常写入所有TRADE事件
  - 处理CANCEL_ORDER事件：生成CANCEL_SUCCESS/CANCEL_REJECT

**3. ClearingWorker恢复**：

- 设置replay_mode=true
- ClearingWorker不做幂等检查，直接处理TRADE事件
- 重放matching_output CQ从开始位置：
  - 处理TRADE事件：生成SETTLEMENT事件写入account_settlement CQ
  - 处理ORDER_UPDATE/CANCEL_SUCCESS/CANCEL_REJECT事件

**4. AccountWorker第二轮恢复**：

- 生成processed_trade_ids（从account_settlement CQ最新10分钟内的SETTLEMENT事件）
- 重放account_settlement CQ从account_settlement_index+1开始：
  - 处理SETTLEMENT事件：使用processed_trade_ids做兜底幂等检查后执行结算转账
  - 处理UNFREEZE事件：解冻资金/持仓

**恢复完成标志**：
- 所有CQ重放完成
- replay_mode全部设置为false
- 幂等集合建立完毕
- 内存状态与快照点之后的事件一致

### 17.8 RPO/RTO 保证

| 场景 | RPO | RTO | 说明 |
|------|-----|-----|------|
| 正常关闭 | 0 | ≤30秒 | 关闭前执行最终快照 |
| Crash重启 | 0 | ≤1分钟 | 从各Worker独立快照恢复 + 重放CQ增量 |
| 快照失败 | 0 | ≤5分钟 | CQ事件仍持久化，从上一个有效快照恢复或从头重放 |
| CQ文件损坏 | 取决于损坏程度 | 取决于损坏程度 | 从有效快照恢复，快照点到损坏点的数据可能丢失 |

**RPO=0的关键保证**（正常情况）：
- 所有CQ配置writeMode=SYNC，每条消息写入后立即刷盘
- 快照文件原子写入（先写tmp再rename），确保一致性
- checksum校验确保快照完整性，损坏可回退
- 各Worker独立快照，即使某Worker快照损坏也可从CQ从头重放

**CQ文件损坏的RPO影响说明**：

CQ文件损坏属于灾难性故障，RPO取决于损坏位置：
- 若CQ损坏发生在最新快照之后：RPO = 快照点到损坏点的时间
- 若CQ完全损坏且无有效快照：RPO = 全部数据

因此，定期快照（每60秒）和writeMode=SYNC配置是减少数据丢失风险的关键措施。

**CQ文件损坏处理机制**：

| 损坏场景 | 检测方式 | 处理方式 | RPO影响 |
|---------|---------|---------|--------|
| CQ文件部分损坏 | Chronicle Queue读取时抛出异常 | 1. 捕获异常，记录错误日志<br>2. 从上一个有效快照恢复<br>3. 快照点到损坏点之间的数据丢失 | 部分数据丢失（快照点之后） |
| CQ文件完全损坏 | 文件无法打开或读取 | 1. 记录错误日志，发送P0告警<br>2. 从上一个有效快照恢复<br>3. 快照点之后的所有数据丢失 | 数据丢失 |
| CQ文件缺失 | 文件不存在 | 1. 若为新交易日：正常初始化<br>2. 若为当日重启：发送告警，从上一交易日快照恢复 | 取决于场景 |
| CQ索引损坏 | 读取位置不正确 | Chronicle Queue内部自动恢复，或从头开始读取 | 无影响 |

**CQ损坏恢复流程**：

```
1. 尝试打开CQ文件
   if (CQ打开失败):
       - 记录错误日志（包含CQ文件名、错误信息）
       - 发送P0告警
       - 查找上一个有效快照
       - 从快照恢复（快照点之后的数据丢失）
       - 标记系统为DEGRADED状态（部分功能降级）
       - 人工介入排查

2. 尝试读取CQ内容
   while (读取CQ事件):
       if (读取异常):
           - 记录异常位置（cqIndex）
           - 尝试跳过损坏区域继续读取
           - 若无法跳过，停止读取
           - 发送告警，标记丢失的事件范围
           - 继续从快照恢复流程

3. 验证CQ完整性
   - 检查CQ起始位置和结束位置
   - 检查事件序列是否连续
   - 若发现断层：记录告警，标记缺失范围
```

**关键保证**：
- 所有 CQ 事件持久化到磁盘（writeMode=SYNC）
- 快照文件原子写入（先写tmp再rename）
- checksum校验确保快照完整性
- replay_mode机制确保不写入重复数据

---

## 18. 优雅关闭流程

### 18.1 关闭流程

```
ShutdownHook:

1. 设置全局关闭标志
   shuttingDown = true;
   systemStatus = SHUTTING_DOWN;
   // 下单请求检查此标志，返回错误码4006
   // 撤单请求不受影响，继续正常处理

2. 停止接收新订单（HTTP下单接口返回错误码4006）
   // 撤单接口继续正常服务

3. 等待现有HTTP请求完成（最长3秒）
   Thread.sleep(3000);

4. 等待所有CQ队列清空（确保已入队订单被处理）
   // 等待matching_input、matching_output、account_settlement清空
   awaitAllQueuesEmpty(timeout=30000);  // 最长30秒

5. 强制写入SNAPSHOT事件到CQ
   long accountIndex = accountInputCQ.writeSnapshotEvent();
   long matchIndex = matchingInputCQ.writeSnapshotEvent();

6. 等待Worker处理完SNAPSHOT（最长5秒）
   awaitSnapshotComplete("account_snapshot_" + accountIndex + ".dat", 5000);
   awaitSnapshotComplete("matching_snapshot_" + matchIndex + ".dat", 5000);

7. 停止Worker线程
   accountWorker.stop();
   matchingEngine.stop();
   clearingWorker.stop();

8. 检查是否有未处理事件（诊断用）
   long remainingAccountInput = accountInputQueue.remaining();
   long remainingMatchingInput = matchingInputQueue.remaining();
   long remainingMatchingOutput = matchingOutputQueue.remaining();
   long remainingAccountSettlement = accountSettlementQueue.remaining();

9. 关闭CQ文件
   accountInputQueue.close();
   accountOutputQueue.close();
   matchingInputQueue.close();
   matchingOutputQueue.close();
   accountSettlementQueue.close();

10. 关闭H2数据库
    h2DataSource.close();

11. 关闭WebSocket
    webSocketServer.close();
```

**全局FLAG机制说明**：
- `shuttingDown`：volatile boolean，关闭时首先设置为true
- HTTP下单请求检查此标志，若为true则返回错误码4006（系统关闭中）
- 撤单请求不检查此标志，继续正常处理
- 确保已入队的订单和撤单都能被正确处理

### 18.2 关闭时序图

```
时间线
    │
    ├─ T+0s  设置shuttingDown=true，下单返回错误码4006
    │
    ├─ T+0s  停止HTTP接收新请求
    │
    ├─ T+3s  等待CQ队列清空
    │
    ├─ T+3s~T+33s  队列清空或超时
    │
    ├─ T+33s  写入SNAPSHOT事件到CQ
    │
    ├─ T+33s~T+38s  Worker处理SNAPSHOT，写入快照文件
    │
    ├─ T+38s  停止所有Worker
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

- **文档版本**: v11.8
- **创建日期**: 2026-05-29
- **最后更新**: 2026-06-01
- **架构师**: Trading System Architect
- **状态**: 设计完成，可落地开发
