# A股交易系统技术方案 V3

> 版本: v3.3
> 日期: 2026-05-20
> 架构师: Trading System Architect
> 状态: **设计完成，待开发**

---

## 修订历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| v1.0 | 2026-05-15 | 初始版本 |
| v2.0 | 2026-05-17 | 重构并发模型 |
| v2.1 | 2026-05-18 | Order Queue统一、单线程清算、多Symbol撮合 |
| v2.2 | 2026-05-19 | 新增总览页面、个人中心、OpenAPI设计 |
| v2.3 | 2026-05-19 | 新增新浪财经行情接口设计 |
| v3.0 | 2026-05-19 | 全面重构：逻辑严谨化、结构清晰化、消除矛盾 |
| v3.1 | 2026-05-19 | 补充业务规则：佣金、T+1、SOD、保证金、Greeks、限流策略 |
| v3.2 | 2026-05-20 | 明确系统定位：模拟交易、撮合模式、行情来源、做市商预留 |
| v3.3 | 2026-05-20 | 前后端一体化部署：前端打包到Tomcat静态资源，随JVM启动 |

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
14. [业务规则](#14-业务规则)
15. [SOD流程](#15-sod流程)
16. [期货期权规格](#16-期货期权规格)
17. [限流策略](#17-限流策略)

---

## 1. 需求与约束

### 1.0 系统定位

**模拟交易系统**

| 定位 | 说明 |
|------|------|
| **系统性质** | 模拟交易系统，非真实交易 |
| **资金来源** | 入金手工上账（管理员操作） |
| **交易逻辑** | 完整的交易、清结算逻辑，与真实交易一致 |
| **撮合模式** | 内部用户之间撮合（含做市商策略用户） |
| **行情来源** | 新浪行情仅展示，五档行情来自撮合引擎订单簿 |

**关键设计决策**：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          系统定位与数据流                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【资金流】                                                                  │
│                                                                             │
│  管理员手工上账 ──▶ 账户余额增加                                            │
│  用户交易      ──▶ 正常的冻结/扣款/清算逻辑                                  │
│  用户出金      ──▶ 管理员手工处理（暂不支持自动）                            │
│                                                                             │
│  【撮合流】                                                                  │
│                                                                             │
│  普通用户下单 ──┐                                                            │
│  做市商下单   ──┼──▶ 订单簿聚合 ──▶ 撮合引擎 ──▶ 成交                       │
│  (OpenAPI)    ──┘                                                            │
│                                                                             │
│  【行情流】                                                                  │
│                                                                             │
│  新浪财经 ──▶ K线、历史数据、总览页面展示（仅展示）                          │
│  撮合引擎 ──▶ 订单簿聚合 ──▶ 五档行情推送（交易用）                          │
│                                                                             │
│  【做市商预留】                                                              │
│                                                                             │
│  做市商系统（独立JVM）──▶ OpenAPI ──▶ 本系统订单簿                          │
│  （做市商系统后续独立设计，本系统预留OpenAPI接入能力）                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.1 业务需求

| 需求项 | 需求描述 |
|--------|---------|
| **系统性质** | 模拟交易系统，入金手工上账，交易逻辑完整正确 |
| **交易品种** | A股（主板/创业板/科创板）、IF股指期货、50ETF期权 |
| **账户体系** | 多用户，每个用户独立账户，支持1000+并发用户 |
| **撮合模式** | 内部撮合，用户间订单撮合（含做市商策略用户） |
| **行情来源** | 新浪行情仅展示，五档行情来自撮合引擎订单簿 |
| **做市商** | 通过OpenAPI接入，做市商系统独立JVM（后续设计） |
| **交易时段** | A股交易时段（无夜盘） |
| **前端界面** | 专业交易终端：TradingView K线图、订单簿、下单面板 |
| **API接入** | 支持量化交易用户和做市商通过OpenAPI下单 |

### 1.2 性能目标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| **吞吐量** | ≥ 100,000 TPS | 订单+撤单总量 |
| **延迟** | ≤ 15ms (P99) | 端到端，从下单到成交回报 |
| **可用性** | 99.99% | 年度可用性 |

### 1.3 技术约束

| 约束项 | 约束内容 |
|--------|---------|
| **部署模式** | 单机单JVM，前后端一体化部署 |
| **前端部署** | 打包到Tomcat静态资源目录，随JVM启动 |
| **服务器配置** | 阿里云 8核CPU / 16GB内存 |
| **行情数据源** | 新浪财经（免费，HTTP轮询） |
| **持久化** | Chronicle Map/Queue 实时持久化 + H2 异步批量 |
| **GC策略** | ZGC（Generational），暂停时间 < 1ms |

### 1.4 设计原则

| 原则 | 说明 |
|------|------|
| **低延迟优先** | 所有设计决策以延迟为第一优先级 |
| **数据安全** | 进程崩溃不丢失数据，重启后自动恢复 |
| **简单可靠** | 避免过度设计，单线程优先，减少并发复杂度 |

---

## 2. 系统架构

### 2.1 整体架构

**前后端一体化部署架构**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              单JVM进程                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         Tomcat容器                                   │   │
│  │                                                                     │   │
│  │  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐            │   │
│  │  │  静态资源     │  │  REST API     │  │  WebSocket    │            │   │
│  │  │  (前端SPA)    │  │  (Spring MVC) │  │  (Netty)      │            │   │
│  │  │               │  │               │  │               │            │   │
│  │  │  /            │  │  /api/*       │  │  /ws          │            │   │
│  │  │  /static/*    │  │  /openapi/*   │  │               │            │   │
│  │  └───────────────┘  └───────────────┘  └───────────────┘            │   │
│  │                                                                     │   │
│  │  前端React应用打包后放入 resources/static/ 目录                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│             │                  │                  │                         │
│             ▼                  ▼                  ▼                         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                          业务层                                     │   │
│  │                                                                     │   │
│  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐             │   │
│  │  │ 订单网关    │───▶│ 撮合引擎    │───▶│ 清算服务    │             │   │
│  │  │ (多线程)    │    │ (单线程)    │    │ (单线程)    │             │   │
│  │  └─────────────┘    └─────────────┘    └─────────────┘             │   │
│  │        │                  │                  │                      │   │
│  │        ▼                  ▼                  ▼                      │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │                    Chronicle Queue                          │   │   │
│  │  │  order_queue.dat  │  trade_queue.dat                        │   │   │
│  │  └─────────────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                     │                                       │
│                                     ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        存储层                                       │   │
│  │                                                                     │   │
│  │  ┌─────────────────────────────┐  ┌─────────────────────────────┐  │   │
│  │  │    Chronicle Map            │  │    H2 Database              │  │   │
│  │  │    (实时持久化)              │  │    (历史查询)               │  │   │
│  │  │                             │  │                             │  │   │
│  │  │  accounts.dat    账户状态    │  │  trades         成交记录    │  │   │
│  │  │  positions.dat   持仓状态    │  │  orders         订单历史    │  │   │
│  │  │  orderbook.dat   订单簿      │  │  cashflow       资金流水    │  │   │
│  │  │  progress.dat    处理进度    │  │  api_keys       API密钥     │  │   │
│  │  │  market_data.dat 行情缓存    │  │                             │  │   │
│  │  └─────────────────────────────┘  └─────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**访问地址映射**：

| 资源 | URL | 说明 |
|------|-----|------|
| 前端页面 | http://host:port/ | React SPA入口 |
| 静态资源 | http://host:port/static/* | JS/CSS/图片 |
| REST API | http://host:port/api/* | 业务接口 |
| OpenAPI | http://host:port/openapi/* | 量化/做市商接口 |
| WebSocket | ws://host:port/ws | 实时推送 |
| 健康检查 | http://host:port/actuator/health | 监控端点 |
│             │                  │                  │                         │
│             ▼                  ▼                  ▼                         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                          业务层                                     │   │
│  │                                                                     │   │
│  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐             │   │
│  │  │ 订单网关    │───▶│ 撮合引擎    │───▶│ 清算服务    │             │   │
│  │  │ (多线程)    │    │ (单线程)    │    │ (单线程)    │             │   │
│  │  └─────────────┘    └─────────────┘    └─────────────┘             │   │
│  │        │                  │                  │                      │   │
│  │        ▼                  ▼                  ▼                      │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │                    Chronicle Queue                          │   │   │
│  │  │  order_queue.dat  │  trade_queue.dat                        │   │   │
│  │  └─────────────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                     │                                       │
│                                     ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        存储层                                       │   │
│  │                                                                     │   │
│  │  ┌─────────────────────────────┐  ┌─────────────────────────────┐  │   │
│  │  │    Chronicle Map            │  │    H2 Database              │  │   │
│  │  │    (实时持久化)              │  │    (历史查询)               │  │   │
│  │  │                             │  │                             │  │   │
│  │  │  accounts.dat    账户状态    │  │  trades         成交记录    │  │   │
│  │  │  positions.dat   持仓状态    │  │  orders         订单历史    │  │   │
│  │  │  orderbook.dat   订单簿      │  │  cashflow       资金流水    │  │   │
│  │  │  progress.dat    处理进度    │  │  api_keys       API密钥     │  │   │
│  │  │  market_data.dat 行情缓存    │  │                             │  │   │
│  │  └─────────────────────────────┘  └─────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 组件职责

| 组件 | 职责 | 线程模型 |
|------|------|---------|
| **REST API** | 接收HTTP请求，处理订单提交、查询 | Tomcat线程池（多线程） |
| **WebSocket** | 实时推送行情、订单、成交 | Netty EventLoop（多线程） |
| **行情服务** | 从新浪财经拉取行情，推送给订阅者 | 单线程 |
| **订单网关** | 风控检查、资金冻结、订单入队 | Tomcat线程（同步执行） |
| **撮合引擎** | 订单撮合，生成成交 | 单线程 |
| **清算服务** | 成交清算，更新账户持仓 | 单线程 |
| **异步持久化** | 批量写入H2 | 单线程 |

---

## 3. 数据模型

### 3.1 Chronicle Map 数据结构

#### 3.1.1 账户状态 (accounts.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| accountId | long | 账户ID（Key） |
| balance | long | 总资产（分） |
| available | long | 可用资金（分） |
| frozen | long | 冻结资金（分） |
| margin | long | 占用保证金（分） |
| updateTime | long | 更新时间戳 |

**不变式**：
```
balance = available + frozen + margin
balance >= 0, available >= 0, frozen >= 0, margin >= 0
```

#### 3.1.2 持仓状态 (positions.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| key | long | Key = (accountId << 32) \| symbolCode |
| accountId | long | 账户ID |
| symbolCode | int | 标的代码 |
| quantity | long | 持仓数量 |
| available | long | 可用数量（T+1） |
| frozen | long | 冻结数量 |
| avgPrice | long | 成本价（分） |
| updateTime | long | 更新时间戳 |

**不变式**：
```
quantity = available + frozen
quantity >= 0, available >= 0, frozen >= 0
```

#### 3.1.3 订单簿状态 (orderbook.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| key | long | Key = (symbolCode << 32) \| orderId |
| orderId | long | 订单ID |
| symbol | String | 标的代码 |
| price | long | 价格（分） |
| quantity | long | 原始数量 |
| remainingQty | long | 剩余数量 |
| side | enum | BUY/SELL |
| accountId | long | 账户ID |
| status | enum | PENDING/PARTIAL_FILLED/FILLED/CANCELLED |
| createTime | long | 创建时间 |
| updateTime | long | 更新时间 |

#### 3.1.4 处理进度 (progress.dat)

| Key | Value | 说明 |
|-----|-------|------|
| order_queue_index | ProgressState | 订单队列处理进度 |
| trade_queue_index | ProgressState | 成交队列处理进度 |

**ProgressState 结构**：
```
{
    index: long,      // 已处理的index
    updateTime: long  // 更新时间戳
}
```

#### 3.1.5 行情缓存 (market_data.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 标的代码（Key） |
| name | String | 名称 |
| currentPrice | long | 当前价（分） |
| preClose | long | 昨收价（分） |
| openPrice | long | 开盘价（分） |
| highPrice | long | 最高价（分） |
| lowPrice | long | 最低价（分） |
| volume | long | 成交量 |
| amount | long | 成交额（分） |
| bidPrices | long[5] | 买1-5价（分） |
| bidVolumes | long[5] | 买1-5量 |
| askPrices | long[5] | 卖1-5价（分） |
| askVolumes | long[5] | 卖1-5量 |
| updateTime | long | 更新时间戳 |

#### 3.1.6 交易品种 (instruments.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 标的代码（Key） |
| name | String | 名称 |
| type | enum | STOCK / FUTURES / OPTION |
| exchange | enum | SH / SZ / CFFEX / SHFE |
| preClose | long | 昨收价（分） |
| limitUp | long | 涨停价（分） |
| limitDown | long | 跌停价（分） |
| minPriceUnit | long | 最小变动价位（分） |
| contractMultiplier | int | 合约乘数（期货/期权） |
| marginRatio | long | 保证金比例（万分之） |
| strikePrice | long | 行权价（期权，分） |
| expiryDate | int | 到期日（期权，YYYYMMDD） |
| underlying | String | 标的（期权） |

**注**: 由SOD流程每日生成，包含当日涨跌停价格和保证金设置

### 3.2 Chronicle Queue 数据结构

#### 3.2.1 订单队列 (order_queue.dat)

**事件类型**：
- NEW_ORDER：新订单
- CANCEL_ORDER：撤单请求

**OrderEvent 结构**：
```
{
    eventType: enum,      // NEW_ORDER / CANCEL_ORDER
    orderId: long,
    symbol: String,
    side: enum,           // BUY / SELL
    orderType: enum,      // LIMIT / MARKET / FAK / FOK
    price: long,
    quantity: long,
    accountId: long,
    timestamp: long
}
```

#### 3.2.2 成交队列 (trade_queue.dat)

**TradeEvent 结构**：
```
{
    tradeId: long,
    orderId: long,
    symbol: String,
    price: long,
    quantity: long,
    buyAccountId: long,
    sellAccountId: long,
    buyOrderId: long,
    sellOrderId: long,
    timestamp: long,
    queueIndex: long      // 由消费者填充
}
```

### 3.3 H2 数据库表结构

#### 3.3.1 成交记录表 (trades)

```sql
CREATE TABLE trades (
    trade_id BIGINT PRIMARY KEY,
    symbol VARCHAR(20),
    price BIGINT,
    quantity BIGINT,
    buy_account_id BIGINT,
    sell_account_id BIGINT,
    buy_order_id BIGINT,
    sell_order_id BIGINT,
    trade_time TIMESTAMP,
    create_time TIMESTAMP
);
```

#### 3.3.2 订单历史表 (orders)

```sql
CREATE TABLE orders (
    order_id BIGINT PRIMARY KEY,
    account_id BIGINT,
    symbol VARCHAR(20),
    side VARCHAR(10),
    order_type VARCHAR(10),
    price BIGINT,
    quantity BIGINT,
    filled_quantity BIGINT,
    status VARCHAR(20),
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
```

#### 3.3.3 API密钥表 (api_keys)

```sql
CREATE TABLE api_keys (
    key_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT,
    api_key VARCHAR(64),
    secret_key_hash VARCHAR(128),
    key_name VARCHAR(100),
    key_type VARCHAR(20),      -- READ_ONLY / TRADING
    symbols VARCHAR(100),      -- STOCK,FUTURES,OPTION
    status VARCHAR(20),        -- ACTIVE / DISABLED
    create_time TIMESTAMP,
    last_used_time TIMESTAMP
);
```

---

## 4. 线程模型

### 4.1 线程分类

| 线程类型 | 数量 | 职责 | Chronicle Map 访问 |
|---------|------|------|-------------------|
| Tomcat HTTP-IO | 8-32 | 接收HTTP请求 | compute写入（订单网关） |
| WebSocket EventLoop | 8 | WebSocket推送 | 不访问 |
| MarketDataWorker | 1 | 行情拉取与推送 | 写入（market_data.dat） |
| MatchingEngine | 1 | 订单撮合 | 写入（orderbook.dat） |
| ClearingWorker | 1 | 成交清算 | compute写入（accounts/positions） |
| AsyncPersistence | 1 | H2批量写入 | 不访问 |

### 4.2 线程交互图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              线程交互流程                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【HTTP请求处理】                                                            │
│                                                                             │
│  Tomcat HTTP-IO Thread                                                      │
│       │                                                                     │
│       │ 1. 接收 POST /api/orders                                            │
│       │ 2. 参数校验                                                         │
│       │ 3. 风控检查 (读 accounts.dat)                                       │
│       │ 4. compute 原子冻结资金 (写 accounts.dat)                           │
│       │ 5. 写入 order_queue.dat                                             │
│       │ 6. 返回 orderId                                                     │
│       ▼                                                                     │
│  同步完成，无线程切换                                                         │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【订单撮合】                                                                │
│                                                                             │
│  MatchingEngine Thread (单线程，busy-wait)                                   │
│       │                                                                     │
│       │ 循环:                                                               │
│       │   1. 从 order_queue.dat 读取 OrderEvent                            │
│       │   2. 写入 orderbook.dat (PENDING)                                   │
│       │   3. 执行撮合逻辑                                                    │
│       │   4. 生成 TradeEvent，写入 trade_queue.dat                          │
│       │   5. 更新 orderbook.dat (FILLED/PARTIAL_FILLED)                     │
│       ▼                                                                     │
│  无锁设计，单线程顺序处理                                                     │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【成交清算】                                                                │
│                                                                             │
│  ClearingWorker Thread (单线程)                                              │
│       │                                                                     │
│       │ 循环:                                                               │
│       │   1. 从 trade_queue.dat 读取 TradeEvent                            │
│       │   2. 幂等检查 (putIfAbsent processed_trades.dat)                    │
│       │   3. compute 更新买方账户 (accounts.dat)                            │
│       │   4. compute 更新卖方账户 (accounts.dat)                            │
│       │   5. compute 更新买方持仓 (positions.dat)                           │
│       │   6. compute 更新卖方持仓 (positions.dat)                           │
│       │   7. 更新处理进度 (progress.dat)                                    │
│       │   8. 推送 WebSocket 成交回报                                        │
│       ▼                                                                     │
│  单线程保证顺序，compute保证原子性                                            │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【行情推送】                                                                │
│                                                                             │
│  MarketDataWorker Thread (单线程)                                            │
│       │                                                                     │
│       │ 循环:                                                               │
│       │   1. HTTP请求新浪财经接口                                           │
│       │   2. 解析行情数据                                                   │
│       │   3. 写入 market_data.dat                                           │
│       │   4. 推送 WebSocket 行情                                            │
│       ▼                                                                     │
│  定时轮询，交易时段1秒/次，非交易时段30秒/次                                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.3 线程数量汇总

| 类别 | 线程数 | 说明 |
|------|--------|------|
| Tomcat HTTP | 8-32 | 动态调整 |
| WebSocket EventLoop | 8 | CPU核心数 |
| MarketDataWorker | 1 | 行情拉取 |
| MatchingEngine | 1 | 撮合引擎 |
| ClearingWorker | 1 | 清算服务 |
| AsyncPersistence | 1 | H2持久化 |
| **总计** | **20-44** | 实际活跃约15-20 |

---

## 5. 核心流程

### 5.1 下单流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              下单流程                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  输入: OrderRequest { symbol, side, orderType, price, quantity }            │
│  输出: OrderResult { orderId, status }                                      │
│                                                                             │
│  步骤:                                                                      │
│                                                                             │
│  1. 参数校验                                                                │
│     - symbol 格式校验                                                       │
│     - side 枚举校验                                                         │
│     - price > 0 (限价单)                                                   │
│     - quantity > 0                                                         │
│                                                                             │
│  2. 风控检查 (读 Chronicle Map)                                              │
│     - 涨跌停价格校验                                                        │
│     - 持仓限额校验                                                          │
│     - 资金充足性预检查                                                       │
│                                                                             │
│  3. 原子冻结 (compute)                                                       │
│                                                                             │
│     买入:                                                                   │
│       amount = price * quantity                                             │
│       compute(accountId) {                                                  │
│           if (available < amount) return REJECT                             │
│           available -= amount                                               │
│           frozen += amount                                                  │
│       }                                                                     │
│                                                                             │
│     卖出:                                                                   │
│       compute(positionKey) {                                                │
│           if (available < quantity) return REJECT                           │
│           available -= quantity                                             │
│           frozen += quantity                                                │
│       }                                                                     │
│                                                                             │
│  4. 生成订单ID                                                              │
│     orderId = snowflakeIdGenerator.nextId()                                │
│                                                                             │
│  5. 写入订单队列                                                            │
│     orderQueue.write(OrderEvent { type: NEW_ORDER, ... })                   │
│                                                                             │
│  6. 返回结果                                                                │
│     return OrderResult { orderId, status: PENDING }                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 撮合流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              撮合流程                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  输入: OrderEvent (从 order_queue.dat 读取)                                 │
│  输出: List<TradeEvent> (写入 trade_queue.dat)                              │
│                                                                             │
│  步骤:                                                                      │
│                                                                             │
│  1. 读取订单事件                                                            │
│     orderEvent = orderQueue.read()                                          │
│                                                                             │
│  2. 判断事件类型                                                            │
│                                                                             │
│     if (NEW_ORDER):                                                        │
│         goto 步骤3                                                          │
│     if (CANCEL_ORDER):                                                     │
│         goto 步骤7                                                          │
│                                                                             │
│  3. 写入订单簿 (PENDING)                                                    │
│     orderbookMap.put(orderKey, OrderState { status: PENDING, ... })         │
│                                                                             │
│  4. 加入内存订单簿                                                          │
│     orderBook = orderBooks.get(symbol)                                      │
│     orderBook.addOrder(order)                                               │
│                                                                             │
│  5. 执行撮合                                                                │
│     trades = orderBook.match()                                              │
│                                                                             │
│     撮合规则:                                                               │
│     - 价格优先: 买单高价优先，卖单低价优先                                    │
│     - 时间优先: 同价格按时间顺序                                             │
│                                                                             │
│  6. 输出成交事件                                                            │
│     for (trade in trades):                                                 │
│         tradeQueue.write(trade)                                             │
│         updateOrderbookState(trade)                                         │
│                                                                             │
│  7. 处理撤单                                                                │
│     orderBook.cancel(orderId)                                               │
│     orderbookMap.compute(orderKey) { status = CANCELLED }                   │
│     // 生成撤单事件到 trade_queue，由清算服务处理解冻                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.3 清算流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              清算流程                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  输入: TradeEvent (从 trade_queue.dat 读取)                                 │
│  输出: 更新账户/持仓，推送WebSocket                                          │
│                                                                             │
│  步骤:                                                                      │
│                                                                             │
│  1. 读取成交事件                                                            │
│     trade = tradeQueue.read()                                               │
│     queueIndex = tailer.index()                                             │
│                                                                             │
│  2. 幂等检查                                                                │
│     existing = processedTradesMap.putIfAbsent(tradeId, state)               │
│     if (existing != null) return  // 已处理，跳过                           │
│                                                                             │
│  3. 买方清算                                                                │
│                                                                             │
│     a. 解冻并扣款 (compute)                                                 │
│        frozenAmount = trade.price * trade.quantity                          │
│        commission = calculateCommission(symbol, trade.price, trade.quantity)│
│        accountMap.compute(buyAccountId) {                                   │
│            frozen -= frozenAmount                                           │
│            balance -= frozenAmount                                          │
│            available -= commission                                          │
│        }                                                                    │
│        // 注: 不收印花税                                                     │
│                                                                             │
│     b. 增加持仓 (compute)                                                   │
│        positionMap.compute(buyerPosKey) {                                   │
│            quantity += trade.quantity                                       │
│            // A股: available不变(T+1), 期货期权: available立即增加          │
│            if (instrument.type != STOCK):                                   │
│                available += trade.quantity                                  │
│            avgPrice = (avgPrice * oldQty + cost) / newQty                   │
│        }                                                                    │
│                                                                             │
│  4. 卖方清算                                                                │
│                                                                             │
│     a. 解冻持仓 (compute)                                                   │
│        positionMap.compute(sellerPosKey) {                                  │
│            frozen -= trade.quantity                                         │
│            quantity -= trade.quantity                                       │
│        }                                                                    │
│                                                                             │
│     b. 增加资金 (compute)                                                   │
│        sellAmount = trade.price * trade.quantity                            │
│        commission = calculateCommission(symbol, trade.price, trade.quantity)│
│        accountMap.compute(sellAccountId) {                                  │
│            balance += sellAmount - commission                               │
│            available += sellAmount - commission                             │
│        }                                                                    │
│        // 注: 不收印花税                                                     │
│                                                                             │
│  5. 更新处理进度                                                            │
│     progressMap.put("trade_queue_index", queueIndex)                        │
│                                                                             │
│  6. 推送WebSocket                                                           │
│     wsPushService.pushTradeReport(trade)                                    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.4 撤单流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              撤单流程                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  阶段1: HTTP请求处理 (Tomcat线程)                                            │
│                                                                             │
│  1. 参数校验                                                                │
│     - orderId 存在性校验                                                    │
│     - 订单所有权校验                                                        │
│                                                                             │
│  2. 写入撤单请求                                                            │
│     orderQueue.write(OrderEvent { type: CANCEL_ORDER, orderId })            │
│                                                                             │
│  3. 返回结果                                                                │
│     return { status: CANCEL_REQUESTED }                                     │
│                                                                             │
│  阶段2: 撮合引擎处理 (MatchingEngine线程)                                    │
│                                                                             │
│  4. 读取撤单事件                                                            │
│     orderEvent = orderQueue.read()                                          │
│                                                                             │
│  5. 从订单簿移除                                                            │
│     order = orderBook.cancel(orderId)                                       │
│     if (order == null) return  // 订单已成交或不存在                        │
│                                                                             │
│  6. 更新订单簿状态                                                          │
│     orderbookMap.compute(orderKey) { status = CANCELLED }                   │
│                                                                             │
│  7. 生成撤单事件                                                            │
│     cancelEvent = CancelEvent { orderId, remainingQty, ... }                │
│     tradeQueue.write(cancelEvent)                                           │
│                                                                             │
│  阶段3: 清算服务处理 (ClearingWorker线程)                                    │
│                                                                             │
│  8. 读取撤单事件                                                            │
│     cancelEvent = tradeQueue.read()                                         │
│                                                                             │
│  9. 解冻资金/持仓 (compute)                                                 │
│     if (买单):                                                              │
│         amount = order.price * cancelEvent.remainingQty                     │
│         accountMap.compute(accountId) {                                     │
│             frozen -= amount                                                │
│             available += amount                                             │
│         }                                                                   │
│     if (卖单):                                                              │
│         positionMap.compute(posKey) {                                       │
│             frozen -= cancelEvent.remainingQty                              │
│             available += cancelEvent.remainingQty                           │
│         }                                                                   │
│                                                                             │
│  10. 推送WebSocket                                                          │
│      wsPushService.pushCancelResult(cancelEvent)                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. 并发控制

### 6.1 compute 原子操作

Chronicle Map 的 `compute` 方法提供 Entry 级别锁，保证读取-修改-写入的原子性。

**特性**：
- 同一个 key 的操作串行化
- 不同 key 的操作可并发执行
- 锁粒度：Entry 级别，非全局锁

**使用场景**：

| 场景 | 操作 | 数据结构 |
|------|------|---------|
| 下单冻结资金 | compute | accounts.dat |
| 下单冻结持仓 | compute | positions.dat |
| 清算更新账户 | compute | accounts.dat |
| 清算更新持仓 | compute | positions.dat |
| 撤单解冻 | compute | accounts.dat / positions.dat |

### 6.2 幂等设计

**问题**：JVM 重启后，可能重复读取已处理的成交。

**解决方案**：TradeId 去重表

```
processed_trades.dat
Key: tradeId
Value: { tradeId, queueIndex, processTime }
```

**幂等检查流程**：
```java
ProcessedTradeState existing = processedTradesMap.putIfAbsent(
    tradeId,
    new ProcessedTradeState(tradeId, queueIndex, now)
);
if (existing != null) {
    // 已处理，跳过
    return;
}
// 执行清算
```

### 6.3 并发安全保证

| 场景 | 保护机制 |
|------|---------|
| 同一账户并发下单 | compute 串行化同一账户的资金操作 |
| 同一持仓并发卖出 | compute 串行化同一持仓的数量操作 |
| 清算时账户更新 | compute 保证原子性 |
| 重启后重复清算 | putIfAbsent 幂等检查 |

---

## 7. 持久化与恢复

### 7.1 持久化策略

| 数据 | 持久化方式 | 持久化时机 |
|------|-----------|-----------|
| 账户状态 | Chronicle Map | 实时（内存映射） |
| 持仓状态 | Chronicle Map | 实时（内存映射） |
| 订单簿 | Chronicle Map | 实时（内存映射） |
| 处理进度 | Chronicle Map | 每笔清算后立即写入 |
| 订单事件 | Chronicle Queue | 下单时立即写入 |
| 成交事件 | Chronicle Queue | 撮合时立即写入 |
| 成交记录 | H2 Database | 异步批量（每100ms或1000条） |
| 订单历史 | H2 Database | 异步批量 |

### 7.2 重启恢复流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              重启恢复流程                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 加载 Chronicle Map 文件                                                 │
│     - accounts.dat → 恢复账户状态                                           │
│     - positions.dat → 恢复持仓状态                                          │
│     - orderbook.dat → 恢复订单簿                                           │
│     - progress.dat → 读取处理进度                                           │
│     - processed_trades.dat → 幂等去重表                                     │
│                                                                             │
│  2. 恢复撮合引擎                                                            │
│     - 从 orderbook.dat 重建内存 OrderBook                                   │
│     - 状态为 PENDING/PARTIAL_FILLED 的订单加入撮合                          │
│                                                                             │
│  3. 恢复处理进度                                                            │
│     orderIndex = progressMap.get("order_queue_index")                       │
│     tradeIndex = progressMap.get("trade_queue_index")                       │
│                                                                             │
│  4. 启动 MatchingEngine                                                     │
│     - 从 orderIndex + 1 开始读取 order_queue                                │
│                                                                             │
│  5. 启动 ClearingWorker                                                     │
│     - 从 tradeIndex + 1 开始读取 trade_queue                                │
│     - 对每个成交执行幂等检查                                                 │
│                                                                             │
│  6. 启动其他服务                                                            │
│     - MarketDataWorker                                                      │
│     - AsyncPersistence                                                      │
│     - WebSocket 推送服务                                                    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.3 数据一致性保证

| 场景 | 保证机制 |
|------|---------|
| JVM 正常关闭 | Chronicle Map/Queue 自动刷盘 |
| JVM 崩溃 | OS 保证内存映射文件完整性 |
| 机器断电 | Chronicle 文件已持久化到磁盘 |
| 重启后重复处理 | 幂等检查跳过已处理成交 |
| 部分成交后崩溃 | 从 orderbook.dat 恢复订单状态 |

---

## 8. 行情服务

### 8.0 行情数据来源

**双行情源设计**

| 数据类型 | 来源 | 用途 |
|---------|------|------|
| **五档行情** | 撮合引擎订单簿 | 交易决策、订单簿展示、下单参考 |
| **K线/历史** | 新浪财经 | 展示、技术分析、总览页面 |
| **指数行情** | 新浪财经 | 总览页面展示 |
| **涨跌排行** | 新浪财经 | 总览页面展示 |

**五档行情生成流程**：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        五档行情生成流程                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  订单来源:                                                                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                         │
│  │ 普通用户    │  │ 量化用户    │  │ 做市商用户  │                         │
│  │ (Web/API)   │  │ (OpenAPI)   │  │ (OpenAPI)   │                         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘                         │
│         │                │                │                                 │
│         └────────────────┼────────────────┘                                 │
│                          ▼                                                  │
│                   ┌─────────────┐                                           │
│                   │  订单簿     │                                           │
│                   │ (OrderBook) │                                           │
│                   │             │                                           │
│                   │ 买单队列:   │                                           │
│                   │  价格优先   │                                           │
│                   │  时间优先   │                                           │
│                   │             │                                           │
│                   │ 卖单队列:   │                                           │
│                   │  价格优先   │                                           │
│                   │  时间优先   │                                           │
│                   └──────┬──────┘                                           │
│                          │                                                  │
│                          ▼                                                  │
│                   ┌─────────────┐                                           │
│                   │ 五档聚合    │                                           │
│                   │             │                                           │
│                   │ 买1: 最高价 │                                           │
│                   │   + 累计量  │                                           │
│                   │ 买2-5: ... │                                           │
│                   │             │                                           │
│                   │ 卖1: 最低价 │                                           │
│                   │   + 累计量  │                                           │
│                   │ 卖2-5: ... │                                           │
│                   └──────┬──────┘                                           │
│                          │                                                  │
│                          ▼                                                  │
│                   ┌─────────────┐                                           │
│                   │ WebSocket   │                                           │
│                   │ 推送        │                                           │
│                   └─────────────┘                                           │
│                                                                             │
│  推送时机:                                                                  │
│  - 每笔订单进入订单簿后立即推送                                              │
│  - 每笔成交后立即推送（订单簿变化）                                          │
│  - 订单撤销后立即推送                                                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**五档行情数据结构**：

```json
{
    "type": "orderbook",
    "data": {
        "symbol": "600000",
        "timestamp": 1716307200000,
        "bid": [
            { "price": 1005, "volume": 1000 },  // 买1
            { "price": 1004, "volume": 2500 },  // 买2
            { "price": 1003, "volume": 1500 },  // 买3
            { "price": 1002, "volume": 800 },   // 买4
            { "price": 1001, "volume": 3200 }   // 买5
        ],
        "ask": [
            { "price": 1006, "volume": 6000 },  // 卖1
            { "price": 1007, "volume": 1200 },  // 卖2
            { "price": 1008, "volume": 4500 },  // 卖3
            { "price": 1009, "volume": 2000 },  // 卖4
            { "price": 1010, "volume": 3800 }   // 卖5
        ]
    }
}
```

### 8.1 新浪财经数据源（仅展示用）

**用途说明**：新浪财经数据仅用于展示，不参与撮合定价。

| 数据类型 | 用途 | 说明 |
|---------|------|------|
| K线数据 | TradingView图表展示 | 历史行情展示 |
| 指数行情 | 总览页面指数栏 | 上证、深证、创业板等 |
| 涨跌排行 | 总览页面排行榜 | 上涨Top5、下跌Top5、成交量Top5 |
| 品种信息 | 品种名称、昨收价 | 用于计算涨跌幅展示 |

**新浪财经 HTTP 接口**

| 接口 | URL | 用途 |
|------|-----|------|
| 股票行情 | `https://hq.sinajs.cn/list={symbols}` | 实时行情+五档 |
| 指数行情 | `https://hq.sinajs.cn/list={index_codes}` | 指数数据 |
| 期货行情 | `https://hq.sinajs.cn/list=nf_{code}` | 期货数据 |
| 期权行情 | `https://hq.sinajs.cn/list=op_{code}` | 期权数据 |
| 涨跌排行 | `https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData` | 排行榜 |

### 8.2 轮询策略

| 数据类型 | 交易时段 | 非交易时段 |
|---------|---------|-----------|
| 自选股行情 | 1秒 | 30秒 |
| 指数行情 | 1秒 | 30秒 |
| 全市场行情 | 3秒 | 60秒 |
| 涨跌排行 | 3秒 | 60秒 |

### 8.3 数据解析

**股票行情返回格式**：
```
var hq_str_sh600000="浦发银行,10.01,9.98,10.05,9.90,9.90,10.05,12345678,123456789,1000,10.05,...";
```

**字段解析**：

| 序号 | 字段 | 说明 |
|------|------|------|
| 0 | name | 股票名称 |
| 1 | open | 开盘价 |
| 2 | preClose | 昨收价 |
| 3 | current | 当前价 |
| 4 | high | 最高价 |
| 5 | low | 最低价 |
| 6 | bid | 买一价 |
| 7 | ask | 卖一价 |
| 8 | volume | 成交量（股） |
| 9 | amount | 成交额（元） |
| 10-19 | bid1-5 | 买1-5价量 |
| 20-29 | ask1-5 | 卖1-5价量 |

### 8.4 行情推送

**WebSocket 推送格式**：
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
        "bid1": [1005, 1000],
        "ask1": [1006, 6000],
        "timestamp": 1716307200000
    }
}
```

---

## 9. API设计

### 9.1 Web API

#### 9.1.1 认证接口

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/auth/login | POST | 用户登录，返回JWT |
| /api/auth/logout | POST | 用户登出 |

#### 9.1.2 账户接口

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/account/info | GET | 账户信息 |
| /api/account/positions | GET | 持仓列表 |

#### 9.1.3 订单接口

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/orders | POST | 下单 |
| /api/orders/{orderId} | DELETE | 撤单 |
| /api/orders/active | GET | 当前委托 |

#### 9.1.4 历史接口

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/trades | GET | 历史成交 |
| /api/account/cashflow | GET | 资金流水 |

#### 9.1.5 行情接口

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/market/symbols | GET | 股票列表 |
| /api/market/watchlist | GET/POST/DELETE | 自选股管理 |

### 9.2 OpenAPI

#### 9.2.0 用户类型

| 用户类型 | 说明 | 接入方式 |
|---------|------|---------|
| **普通用户** | Web/移动端交易用户 | JWT Token认证 |
| **量化用户** | 通过API进行量化交易 | API Key认证 |
| **做市商用户** | 提供流动性的策略用户 | API Key认证（特殊标记） |

**做市商用户特点**：
- 通过OpenAPI接入，独立JVM运行
- 订单进入订单簿，参与撮合
- 可享受特殊费率（可配置）
- 后续独立设计做市商系统

**做市商用户标识**：
```sql
-- api_keys表增加字段
ALTER TABLE api_keys ADD COLUMN is_market_maker BOOLEAN DEFAULT FALSE;
```

#### 9.2.1 认证方式

**HMAC-SHA256 签名**

```
签名字串 = Method + "\n" + Path + "\n" + Timestamp + "\n" + BodyHash
Signature = HMAC_SHA256(SecretKey, 签名字串)

请求Header:
  X-API-Key: ak_xxxxxxxxxxxx
  X-Timestamp: 1716307200
  X-Signature: 签名结果
```

#### 9.2.2 API Key 类型

| 类型 | 权限 |
|------|------|
| 只读Key | 行情订阅、账户查询、持仓查询、委托查询 |
| 交易Key | 只读权限 + 下单、撤单 |

#### 9.2.3 OpenAPI 接口

| 接口 | 方法 | 说明 | Key类型 |
|------|------|------|---------|
| /openapi/ws | WebSocket | 行情订阅+订单推送 | 只读/交易/做市商 |
| /openapi/account | GET | 账户信息 | 只读/交易/做市商 |
| /openapi/positions | GET | 持仓列表 | 只读/交易/做市商 |
| /openapi/orders | POST | 下单 | 交易/做市商 |
| /openapi/orders/{id} | DELETE | 撤单 | 交易/做市商 |
| /openapi/orders/active | GET | 当前委托 | 只读/交易/做市商 |
| /openapi/orderbook | GET | 五档行情查询 | 只读/交易/做市商 |

**做市商专属推送**：
- 做市商用户订阅WebSocket时，可接收订单簿深度变化推送
- 推送频率：每笔订单变化即推送（高频）
- 普通用户：每秒聚合推送一次

### 9.3 WebSocket

#### 9.3.1 连接

```
ws://host:port/ws?token=<jwt_token>
```

#### 9.3.2 订阅消息

```json
{
    "type": "subscribe",
    "channel": "market_snapshot|orderbook|order_update|trade_update",
    "symbols": ["600000"]
}
```

#### 9.3.3 推送消息类型

| 类型 | 说明 | 订阅者 |
|------|------|--------|
| market_snapshot | 行情快照 | 所有用户 |
| kline | K线数据 | 所有用户 |
| orderbook | 订单簿 | 所有用户 |
| order_update | 订单状态 | 订单所有者 |
| trade_update | 成交推送 | 成交双方 |
| account_update | 账户变动 | 账户所有者 |
| position_update | 持仓变动 | 持仓所有者 |

---

## 10. 前端设计

### 10.1 页面结构

| 页面 | 路由 | 说明 |
|------|------|------|
| 总览 | /overview | 市场全景，指数、涨跌榜、成交量榜 |
| 股票 | /stock | 股票交易 |
| 期货 | /futures | 期货交易 |
| 期权 | /option | 期权交易 |
| 自选 | /watchlist | 自选股 |
| 个人中心 | /profile | 用户信息、API Key管理 |

### 10.2 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| React | 18 | 前端框架 |
| TypeScript | 5.x | 类型安全 |
| TradingView | latest | K线图 |
| Ant Design | 5.x | UI组件 |
| Zustand | 4.x | 状态管理 |
| WebSocket | - | 实时推送 |
| Vite | 5.x | 构建工具 |

### 10.3 组件结构

```
src/
├── pages/
│   ├── Overview/          # 总览页面
│   │   ├── IndexBar.tsx
│   │   ├── TopGainers.tsx
│   │   ├── TopLosers.tsx
│   │   └── ExchangeRanking.tsx
│   ├── Stock/             # 股票交易
│   │   ├── MarketList.tsx
│   │   ├── TradingViewChart.tsx
│   │   ├── OrderBook.tsx
│   │   ├── OrderPanel.tsx
│   │   └── AccountPanel.tsx
│   └── Profile/           # 个人中心
│       ├── UserInfo.tsx
│       └── ApiKeyManager.tsx
├── components/
│   ├── Header.tsx
│   └── AccountBar.tsx
└── stores/
    ├── accountStore.ts
    ├── marketStore.ts
    └── orderStore.ts
```

### 10.4 前端构建与部署

**前后端一体化部署流程**：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          前端构建部署流程                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 前端开发                                                                │
│     cd frontend/                                                            │
│     npm install                                                             │
│     npm run dev         # 开发模式，热更新                                   │
│                                                                             │
│  2. 前端构建                                                                │
│     npm run build       # 生产构建                                          │
│     → 生成 dist/ 目录                                                       │
│       ├── index.html                                                        │
│       ├── static/                                                           │
│       │   ├── js/                                                           │
│       │   ├── css/                                                          │
│       │   └── assets/                                                       │
│                                                                             │
│  3. 复制到后端                                                              │
│     cp -r dist/* ../src/main/resources/static/                              │
│                                                                             │
│  4. 后端打包                                                                │
│     mvn clean package -DskipTests                                           │
│     → 生成 target/hft-trading.jar                                           │
│                                                                             │
│  5. 启动运行                                                                │
│     java -jar target/hft-trading.jar                                        │
│     → 访问 http://localhost:8080/                                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**项目目录结构**：

```
hft-trading/
├── src/main/
│   ├── java/                    # 后端Java代码
│   │   └── com/hft/
│   │       ├── controller/      # REST API控制器
│   │       ├── service/         # 业务服务
│   │       ├── matching/        # 撮合引擎
│   │       ├── clearing/        # 清算服务
│   │       └── config/          # 配置类
│   └── resources/
│       ├── static/              # 前端静态资源（打包后）
│       │   ├── index.html       # SPA入口
│       │   └── static/
│       │       ├── js/
│       │       ├── css/
│       │       └── assets/
│       ├── application.yml      # Spring配置
│       └── logback.xml          # 日志配置
├── frontend/                    # 前端源码（独立目录）
│   ├── src/
│   │   ├── pages/
│   │   ├── components/
│   │   └── stores/
│   ├── package.json
│   ├── vite.config.ts
│   └── dist/                    # 构建输出
├── docs/                        # 文档
├── pom.xml                      # Maven配置
└── README.md
```

**Vite配置要点**：

```typescript
// vite.config.ts
export default defineConfig({
  base: '/',                    // 部署到根路径
  build: {
    outDir: 'dist',
    assetsDir: 'static',        // 资源目录名
    sourcemap: false,           // 生产不生成sourcemap
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',  // 开发时API代理
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
});
```

---

## 11. 性能分析

### 11.1 延迟分解

| 阶段 | 延迟 | 说明 |
|------|------|------|
| HTTP接收解析 | 1-2ms | Tomcat处理 |
| 风控检查 | ~0.1ms | 读Chronicle Map |
| compute冻结 | ~5μs | Entry锁 |
| Queue写入 | ~10μs | Chronicle Queue |
| 撮合处理 | ~10μs | 内存操作 |
| 清算处理 | ~50μs | compute更新 |
| WebSocket推送 | 1-2ms | Netty发送 |
| **总计** | **4-8ms** | P99 < 15ms |

### 11.2 吞吐量分析

| 组件 | 吞吐量 | 瓶颈 |
|------|--------|------|
| OrderGateway | 多线程并发 | compute锁竞争 |
| MatchingEngine | 500K+ TPS | 单线程CPU |
| ClearingWorker | 100K+ TPS | compute锁竞争 |
| Chronicle Queue | 1M+ msg/s | 磁盘IO |
| **系统总吞吐** | **> 100K TPS** | 满足目标 |

### 11.3 内存分析

| 数据 | 大小估算 |
|------|---------|
| 账户状态 (1000账户) | ~1MB |
| 持仓状态 (5000持仓) | ~5MB |
| 订单簿 (10000订单) | ~10MB |
| 行情缓存 (5000标的) | ~50MB |
| Chronicle Queue | ~1GB |
| JVM堆 | ~10GB |
| **总计** | **~12GB** |

---

## 12. 部署配置

### 12.1 服务器配置

| 配置项 | 值 |
|--------|-----|
| CPU | 8核 |
| 内存 | 16GB |
| 磁盘 | SSD 100GB |
| 网络 | 千兆网卡 |

### 12.2 JVM配置

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

### 12.3 Chronicle Map 配置

```java
ChronicleMap<Long, AccountState> accountMap = ChronicleMap
    .of(Long.class, AccountState.class)
    .name("accounts")
    .entries(10000)
    .averageValue(new AccountState())
    .createPersistedTo(new File("data/accounts.dat"));
```

---

## 13. 监控与运维

### 13.1 核心监控指标

| 指标 | 类型 | 说明 |
|------|------|------|
| trading_order_latency | Histogram | 订单处理延迟 |
| trading_clearing_latency | Histogram | 清算延迟 |
| trading_queue_progress | Gauge | 队列处理进度 |
| trading_idempotent_hit | Counter | 幂等命中次数 |
| trading_orderbook_depth | Gauge | 订单簿深度 |
| jvm_gc_pause | Histogram | GC暂停时间 |

### 13.2 告警规则

| 告警 | 条件 | 级别 |
|------|------|------|
| 订单延迟过高 | P99 > 20ms | Warning |
| 清算延迟过高 | P99 > 1ms | Warning |
| GC暂停过长 | > 10ms | Warning |
| 队列积压 | > 10000 | Critical |

### 13.3 日志规范

| 日志级别 | 使用场景 |
|---------|---------|
| ERROR | 系统错误、异常 |
| WARN | 业务告警、重试 |
| INFO | 关键业务节点 |
| DEBUG | 调试信息 |

---

## 14. 业务规则

### 14.1 佣金规则

**仅收取佣金，不收印花税**

| 品种 | 佣金费率 | 最低佣金 | 说明 |
|------|---------|---------|------|
| A股 | 0.025% | 5元 | 按成交金额计算 |
| IF股指期货 | 0.0023% | 无最低 | 按成交金额计算 |
| 50ETF期权 | 5元/张 | 无最低 | 按张数计算 |

**佣金计算公式**：
```
commission = max(amount * feeRate, minCommission)
amount = price * quantity
```

### 14.2 T+1 规则

| 品种 | 可卖出时间 | 说明 |
|------|-----------|------|
| A股 | T+1 | 当日买入，下一交易日可卖出 |
| 期货 | T+0 | 清算后立即可平仓 |
| 期权 | T+0 | 清算后立即可平仓 |

**持仓可用数量更新规则**：

```
A股买入清算后:
  position.quantity += trade.quantity
  position.available += 0           // 当日不可卖
  position.frozen += 0
  // T+1可用数量由SOD流程更新

期货/期权买入清算后:
  position.quantity += trade.quantity
  position.available += trade.quantity  // 立即可平
  position.frozen += 0
```

### 14.3 涨跌停规则

**A股涨跌停计算**：

| 板块 | 涨幅限制 | 跌幅限制 |
|------|---------|---------|
| 主板 | ±10% | ±10% |
| 创业板 | ±20% | ±20% |
| 科创板 | ±20% | ±20% |
| ST/*ST | ±5% | ±5% |

**涨跌停价格**：
```
limitUpPrice = round(preClose * (1 + upLimit))
limitDownPrice = round(preClose * (1 - downLimit))
```

**期货涨跌停**：由SOD流程从交易所规格加载

**期权涨跌停**：由SOD流程从交易所规格加载

### 14.4 风控检查

**下单前风控检查项**：

| 检查项 | 说明 | 拒绝条件 |
|--------|------|---------|
| 价格有效性 | 价格在涨跌停范围内 | price < limitDown 或 price > limitUp |
| 资金充足性 | 买入时可用资金足够 | available < price * quantity |
| 持仓充足性 | 卖出时可用持仓足够 | position.available < quantity |
| 持仓限额 | 单品种持仓上限 | 超过配置限额 |

---

## 15. SOD流程

### 15.1 SOD (Start of Day) 流程

**执行时机**：每日开盘前 9:10 执行

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              SOD流程                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 加载交易日历                                                            │
│     - 判断是否为交易日                                                      │
│     - 获取当日交易时段                                                      │
│                                                                             │
│  2. 生成交易品种 (INSTRUMENT)                                               │
│     - 从交易所接口/配置加载当日可交易品种                                   │
│     - 计算涨跌停价格                                                        │
│     - 加载期货保证金比例                                                    │
│     - 加载期权合约规格                                                      │
│                                                                             │
│  3. 更新持仓T+1可用                                                         │
│     - 遍历所有A股持仓                                                       │
│     - position.available = position.quantity - position.frozen              │
│                                                                             │
│  4. 重置日内状态                                                            │
│     - 清空日内统计                                                          │
│     - 重置限流计数器                                                        │
│                                                                             │
│  5. 数据校验                                                                │
│     - 校验账户不变式: balance = available + frozen + margin                 │
│     - 校验持仓不变式: quantity = available + frozen                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 15.2 INSTRUMENT 数据结构

**存储位置**: `instruments.dat` (Chronicle Map)

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 标的代码（Key） |
| name | String | 名称 |
| type | enum | STOCK / FUTURES / OPTION |
| exchange | enum | SH / SZ / CFFEX / SHFE |
| preClose | long | 昨收价（分） |
| limitUp | long | 涨停价（分） |
| limitDown | long | 跌停价（分） |
| minPriceUnit | long | 最小变动价位（分） |
| contractMultiplier | int | 合约乘数（期货/期权） |
| marginRatio | long | 保证金比例（万分之） |
| strikePrice | long | 行权价（期权，分） |
| expiryDate | int | 到期日（期权，YYYYMMDD） |
| underlying | String | 标的（期权） |

### 15.3 保证金规格

**期货保证金**（由交易所设置，SOD加载）：

| 合约 | 交易所 | 保证金比例 |
|------|--------|-----------|
| IF股指期货 | CFFEX | 12% |
| IC中证500 | CFFEX | 14% |
| IH上证50 | CFFEX | 12% |

**期权保证金计算**：

```
认购期权保证金 = (权利金 + max(标的价格 * 合约乘数 * 保证金比例 - 虚值, 0)) * 合约乘数
认沽期权保证金 = (权利金 + max(行权价 * 合约乘数 * 保证金比例 - 虚值, 0)) * 合约乘数
```

---

## 16. 期货期权规格

### 16.1 期货合约规格

| 合约 | 交易所 | 合约乘数 | 最小变动价位 | 交易时段 |
|------|--------|---------|-------------|---------|
| IF | CFFEX | 300 | 0.2点 | 9:30-11:30, 13:00-15:00 |
| IC | CFFEX | 200 | 0.2点 | 9:30-11:30, 13:00-15:00 |
| IH | CFFEX | 300 | 0.2点 | 9:30-11:30, 13:00-15:00 |

### 16.2 期权合约规格

**50ETF期权**：

| 项目 | 规格 |
|------|------|
| 标的 | 50ETF (510050) |
| 合约乘数 | 10000 |
| 最小变动价位 | 0.0001元 |
| 到期月份 | 当月、下月、下季、隔季 |
| 行权价格间距 | 标的价格×3%取整 |

### 16.3 期权Greeks计算

**使用Black-Scholes模型**

```
输入:
  S: 标的当前价格
  K: 行权价
  T: 到期时间（年）
  r: 无风险利率
  sigma: 波动率

计算:
  d1 = (ln(S/K) + (r + sigma²/2) * T) / (sigma * sqrt(T))
  d2 = d1 - sigma * sqrt(T)

认购期权:
  Delta = N(d1)
  Gamma = N'(d1) / (S * sigma * sqrt(T))
  Theta = -S * N'(d1) * sigma / (2 * sqrt(T)) - r * K * e^(-rT) * N(d2)
  Vega = S * N'(d1) * sqrt(T)
  Rho = K * T * e^(-rT) * N(d2)

认沽期权:
  Delta = N(d1) - 1
  Gamma = N'(d1) / (S * sigma * sqrt(T))
  Theta = -S * N'(d1) * sigma / (2 * sqrt(T)) + r * K * e^(-rT) * N(-d2)
  Vega = S * N'(d1) * sqrt(T)
  Rho = -K * T * e^(-rT) * N(-d2)
```

**Greeks推送**：随行情推送，每3秒更新一次

---

## 17. 限流策略

### 17.0 管理员操作

**入金手工上账流程**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          入金手工上账流程                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  触发条件: 管理员通过管理接口发起入金请求                                     │
│                                                                             │
│  步骤:                                                                      │
│                                                                             │
│  1. 管理员认证                                                              │
│     - 验证管理员Token                                                       │
│     - 检查管理员权限                                                        │
│                                                                             │
│  2. 参数校验                                                                │
│     - accountId: 目标账户ID                                                 │
│     - amount: 入金金额（正整数，分）                                         │
│     - remark: 备注                                                          │
│                                                                             │
│  3. 原子入账 (compute)                                                      │
│     accountMap.compute(accountId) {                                         │
│         balance += amount                                                   │
│         available += amount                                                 │
│     }                                                                       │
│                                                                             │
│  4. 记录资金流水                                                            │
│     INSERT INTO cashflow (account_id, type, amount, balance_after, remark)  │
│     VALUES (accountId, 'DEPOSIT', amount, newBalance, remark)               │
│                                                                             │
│  5. 推送WebSocket                                                           │
│     wsPushService.pushAccountUpdate(accountId)                              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**管理员接口**

| 接口 | 方法 | 说明 | 权限 |
|------|------|------|------|
| /admin/deposit | POST | 入金上账 | ADMIN |
| /admin/accounts | GET | 账户列表查询 | ADMIN |
| /admin/account/{id} | GET | 账户详情 | ADMIN |
| /admin/trades | GET | 全部成交记录 | ADMIN |
| /admin/orders | GET | 全部订单记录 | ADMIN |
| /admin/system/status | GET | 系统状态 | ADMIN |

**入金请求格式**：
```json
{
    "accountId": 10001,
    "amount": 10000000,  // 10万元（分）
    "remark": "模拟入金-20260520"
}
```

**管理员认证**：
- 使用独立的Admin JWT Token
- Admin Token有效期：8小时，不自动延期
- 权限校验：检查Token中的role字段为ADMIN

### 17.1 新浪财经限流

| 接口类型 | 限流策略 | 说明 |
|---------|---------|------|
| 单只行情 | 3秒/次 | 避免触发新浪封禁 |
| 批量行情 | 3秒/次 | 单次最多50只 |
| 排行榜 | 3秒/次 | 涨跌榜、成交量榜 |

**失败重试策略**：
- 重试次数：3次
- 重试间隔：1秒、2秒、4秒（指数退避）
- 超时时间：5秒

### 17.2 OpenAPI限流

**每个API Key独立限流**：

| 限制项 | 阈值 | 说明 |
|--------|------|------|
| 请求速率 | 500笔/秒 | 滑动窗口计数 |
| 超限处理 | 拒绝 | 返回 429 Too Many Requests |

**限流实现**：
```java
// 使用滑动窗口算法
public boolean allowRequest(String apiKey) {
    long now = System.currentTimeMillis();
    long windowStart = now - 1000;  // 1秒窗口

    AtomicInteger count = requestCounts.computeIfAbsent(apiKey, k -> new AtomicInteger(0));
    ConcurrentLinkedDeque<Long> timestamps = requestTimestamps.computeIfAbsent(apiKey, k -> new ConcurrentLinkedDeque<>());

    // 移除窗口外的时间戳
    while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
        timestamps.pollFirst();
        count.decrementAndGet();
    }

    if (count.get() >= 500) {
        return false;  // 限流
    }

    timestamps.addLast(now);
    count.incrementAndGet();
    return true;
}
```

### 17.3 WebSocket心跳

| 参数 | 值 | 说明 |
|------|-----|------|
| 心跳间隔 | 30秒 | 客户端发送ping |
| 超时断开 | 60秒 | 2个心跳周期无响应 |
| 心跳格式 | ping/pong | 文本帧 |

**服务端心跳检测**：
```
lastHeartbeatTime 记录最后收到心跳的时间

后台线程每10秒检查一次:
  if (now - lastHeartbeatTime > 60000):
      closeConnection()  // 超时断开
```

### 17.4 JWT Token管理

| 参数 | 值 | 说明 |
|------|-----|------|
| 初始有效期 | 2小时 | 登录时生成 |
| 滑动延期 | 2小时 | 每次请求自动延期 |
| 最大有效期 | 24小时 | 单次登录最长有效期 |
| 刷新机制 | 自动 | 有请求即向后延 |

**Token延期实现**：
```java
// 每次请求时检查并延期
public void extendToken(String token) {
    Claims claims = parseToken(token);
    long now = System.currentTimeMillis();
    long issuedAt = claims.getIssuedAt().getTime();
    long maxExpiry = issuedAt + 24 * 3600 * 1000;  // 最大有效期24小时

    if (now + 2 * 3600 * 1000 < maxExpiry) {
        // 可以延期，生成新Token
        String newToken = generateToken(claims.getSubject(), now, now + 2 * 3600 * 1000);
        return newToken;
    }
    // 已达最大有效期，返回原Token
    return token;
}
```

### 17.5 API Key规格

| 项目 | 规格 | 说明 |
|------|------|------|
| API Key格式 | UUID v4 | 32位，无连字符 |
| Secret Key格式 | UUID v4 | 32位，无连字符 |
| 存储方式 | Secret Key加盐哈希 | SHA-256 |
| Key前缀 | ak_ | API Key显示前缀 |

**生成示例**：
```
API Key: ak_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6
Secret Key: s1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6 (仅创建时显示一次)
```

**Secret Key哈希存储**：
```java
String salt = generateSalt();  // 16字节随机盐
String hash = SHA256(secretKey + salt);
// 存储: salt, hash
```

---

## 附录

### A. 参考文档

- [前端布局设计](frontend-layout.html)
- [HTTP API定义](http-api-definition.html)
- [线程工作流](thread-workflow.html)

### B. 术语表

| 术语 | 说明 |
|------|------|
| TPS | Transactions Per Second，每秒交易数 |
| P99 | 99分位延迟 |
| compute | Chronicle Map的原子操作方法 |
| Chronicle Queue | 高性能持久化队列 |
| Chronicle Map | 高性能持久化KV存储 |
| SOD | Start of Day，日初流程 |
| RPO | Recovery Point Objective，恢复点目标 |
| Greeks | 期权风险指标（Delta、Gamma、Theta、Vega、Rho） |

### C. 数据持久化保证

| 保证项 | 说明 |
|--------|------|
| RPO | 0（无数据丢失） |
| Chronicle文件 | 实时持久化到磁盘 |
| 重启恢复 | 自动从Chronicle文件恢复 |
| 数据校验 | SOD时校验不变式 |
