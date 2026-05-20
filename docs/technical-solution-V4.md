# A股交易系统技术方案 V4

> 版本: v4.1
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
| v3.4 | 2026-05-20 | 确认技术栈版本：Java21、SpringBoot3+虚拟线程、用户注册自动创建账户 |
| v3.5 | 2026-05-20 | 数据存储优化：instruments移至H2+内存、market_data改用Java内存、新增EOD快照流程 |
| v3.6 | 2026-05-20 | 新增交易时间段配置、交易阶段切换机制、新浪财经品种查询接口 |
| v4.0 | 2026-05-20 | 整合所有设计，消除冗余，形成完整严谨的技术方案 |
| v4.1 | 2026-05-20 | 确认22项设计决策：认证方式、ID生成、前端风格、监控配置等 |

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
15. [SOD与EOD流程](#15-sod与eod流程)
16. [交易时间段配置](#16-交易时间段配置)
17. [品种数据管理](#17-品种数据管理)
18. [期货期权规格](#18-期货期权规格)
19. [限流策略](#19-限流策略)

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
| **做市商** | 通过OpenAPI接入，做市商系统独立JVM（后续设计） |

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
| **吞吐量** | ≥ 100,000 TPS | 订单+撤单总量 |
| **延迟** | ≤ 15ms (P99) | 端到端，从下单到成交回报 |
| **可用性** | 99.99% | 年度可用性 |

### 1.4 技术约束

| 约束项 | 约束内容 |
|--------|---------|
| **部署模式** | 单机单JVM，前后端一体化部署 |
| **前端部署** | 打包到Tomcat静态资源目录，随JVM启动 |
| **客户端支持** | 仅Web端，不支持移动端 |
| **Java版本** | Java 21 LTS |
| **Spring Boot** | 3.x + 虚拟线程（Project Loom） |
| **Chronicle版本** | 最新稳定版 |
| **服务器配置** | 阿里云 8核CPU / 16GB内存 |
| **行情数据源** | 新浪财经（免费，HTTP轮询） |
| **持久化** | Chronicle Map/Queue 实时持久化 + H2 异步批量 |
| **GC策略** | ZGC（Generational），暂停时间 < 1ms |

### 1.5 设计原则

| 原则 | 说明 |
|------|------|
| **低延迟优先** | 所有设计决策以延迟为第一优先级 |
| **数据安全** | 进程崩溃不丢失数据，重启后自动恢复 |
| **简单可靠** | 避免过度设计，单线程优先，减少并发复杂度 |

### 1.6 确认的设计决策

**用户认证与安全**：

| 决策项 | 决策内容 |
|--------|---------|
| 用户认证方式 | 用户名密码登录 |
| 密码加密算法 | SHA-256 + 盐值 |
| 登录验证码 | 不需要 |

**ID生成策略**：

| 决策项 | 决策内容 |
|--------|---------|
| 账户ID生成 | 雪花算法 |
| 订单ID生成 | 雪花算法，workerId固定配置 |
| 成交ID生成 | 雪花算法，workerId固定配置 |

**数据处理**：

| 决策项 | 决策内容 |
|--------|---------|
| 资金精度 | 分（整数），足够 |
| 异常订单处理 | 记录日志跳过，不影响主流程 |
| H2数据库目录 | data/h2/ |
| Chronicle文件目录 | data/chronicle/ |

**做市商**：

| 决策项 | 决策内容 |
|--------|---------|
| 做市商策略 | 后续独立设计，本系统预留OpenAPI接入能力 |

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
│  │  │  processed.dat   成交去重    │  │  instruments    交易品种    │  │   │
│  │  │                             │  │  account_snapshots 账户快照  │  │   │
│  │  │                             │  │  position_snapshots 持仓快照 │  │   │
│  │  │                             │  │  trading_session_config     │  │   │
│  │  │                             │  │  trading_calendar           │  │   │
│  │  └─────────────────────────────┘  └─────────────────────────────┘  │   │
│  │                                                                     │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │                    Java 内存存储                             │   │   │
│  │  │  ConcurrentHashMap<String, Instrument>    交易品种           │   │   │
│  │  │  ConcurrentHashMap<String, MarketData>    行情快照           │   │   │
│  │  │  Map<String, TradingPhase>                 当前交易阶段      │   │   │
│  │  └─────────────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 访问地址映射

| 资源 | URL | 说明 |
|------|-----|------|
| 前端页面 | http://host:port/ | React SPA入口 |
| 静态资源 | http://host:port/static/* | JS/CSS/图片 |
| REST API | http://host:port/api/* | 业务接口 |
| OpenAPI | http://host:port/openapi/* | 量化/做市商接口 |
| WebSocket | ws://host:port/ws | 实时推送 |
| 健康检查 | http://host:port/actuator/health | 监控端点 |

### 2.3 组件职责

| 组件 | 职责 | 线程模型 |
|------|------|---------|
| **REST API** | 接收HTTP请求，处理订单提交、查询 | Tomcat线程池（多线程） |
| **WebSocket** | 实时推送行情、订单、成交 | Netty EventLoop（多线程） |
| **订单网关** | 风控检查、资金冻结、订单入队 | Tomcat线程（同步执行） |
| **撮合引擎** | 订单撮合、五档行情推送、交易阶段管理 | 单线程 |
| **清算服务** | 成交清算，更新账户持仓 | 单线程 |
| **阶段管理器** | 交易阶段切换、时间调度 | 单线程定时任务 |
| **异步持久化** | 批量写入H2 | 单线程 |

---

## 3. 数据模型

### 3.1 Chronicle Map 数据结构

**文件列表**：

| 文件 | 用途 | 说明 |
|------|------|------|
| accounts.dat | 账户状态 | 实时持久化，JVM重启恢复 |
| positions.dat | 持仓状态 | 实时持久化，JVM重启恢复 |
| orderbook.dat | 订单簿 | 实时持久化，JVM重启恢复 |
| progress.dat | 处理进度 | 订单/成交队列处理进度 |
| processed.dat | 成交去重 | 幂等检查，防止重复清算 |

#### 3.1.1 账户状态 (accounts.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| accountId | long | 账户ID（Key） |
| balance | long | 总资产（分） |
| available | long | 可用资金（分） |
| frozen | long | 冻结资金（分） |
| margin | long | 占用保证金（分） |
| updateTime | long | 更新时间戳 |

**不变式**：`balance = available + frozen + margin`

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

**不变式**：`quantity = available + frozen`

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

#### 3.1.5 成交去重表 (processed.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| tradeId | long | 成交ID（Key） |
| queueIndex | long | 队列索引 |
| processTime | long | 处理时间戳 |

### 3.2 Chronicle Queue 数据结构

#### 3.2.1 订单队列 (order_queue.dat)

**事件类型**：NEW_ORDER / CANCEL_ORDER

```
OrderEvent {
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

```
TradeEvent {
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

### 3.3 Java 内存数据结构

#### 3.3.1 交易品种 (Instrument)

**存储位置**：H2数据库 + Java内存（ConcurrentHashMap<String, Instrument>）

**加载时机**：SOD启动时从H2数据库加载到内存

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
| status | enum | ACTIVE / SUSPENDED / DELISTED |

#### 3.3.2 行情快照 (MarketDataSnapshot)

**存储位置**：Java内存（ConcurrentHashMap<String, MarketDataSnapshot>）

**数据来源**：撮合引擎每3秒推送最新五档行情

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 标的代码（Key） |
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

### 3.4 H2 数据库表结构

#### 3.4.1 成交记录表 (trades)

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

#### 3.4.2 订单历史表 (orders)

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

#### 3.4.3 API密钥表 (api_keys)

```sql
CREATE TABLE api_keys (
    key_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT,
    api_key VARCHAR(64),
    secret_key_hash VARCHAR(128),
    salt VARCHAR(64),
    key_name VARCHAR(100),
    key_type VARCHAR(20),      -- READ_ONLY / TRADING
    symbols VARCHAR(100),      -- STOCK,FUTURES,OPTION
    is_market_maker BOOLEAN DEFAULT FALSE,
    status VARCHAR(20),        -- ACTIVE / DISABLED
    create_time TIMESTAMP,
    last_used_time TIMESTAMP
);
```

#### 3.4.4 交易品种表 (instruments)

```sql
CREATE TABLE instruments (
    symbol VARCHAR(20) PRIMARY KEY,
    name VARCHAR(100),
    type VARCHAR(20),          -- STOCK / FUTURES / OPTION
    exchange VARCHAR(10),      -- SH / SZ / CFFEX / SHFE
    pre_close BIGINT,
    limit_up BIGINT,
    limit_down BIGINT,
    min_price_unit BIGINT,
    contract_multiplier INT,
    margin_ratio BIGINT,
    strike_price BIGINT,
    expiry_date INT,
    underlying VARCHAR(20),
    status VARCHAR(20),        -- ACTIVE / SUSPENDED / DELISTED / PRE_LISTING
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
```

#### 3.4.5 账户快照表 (account_snapshots)

```sql
CREATE TABLE account_snapshots (
    snapshot_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT,
    snapshot_date DATE,
    balance BIGINT,
    available BIGINT,
    frozen BIGINT,
    margin BIGINT,
    create_time TIMESTAMP,
    INDEX idx_account_date (account_id, snapshot_date)
);
```

#### 3.4.6 持仓快照表 (position_snapshots)

```sql
CREATE TABLE position_snapshots (
    snapshot_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT,
    symbol VARCHAR(20),
    snapshot_date DATE,
    quantity BIGINT,
    available BIGINT,
    frozen BIGINT,
    avg_price BIGINT,
    market_value BIGINT,
    create_time TIMESTAMP,
    INDEX idx_account_date (account_id, snapshot_date),
    INDEX idx_symbol_date (symbol, snapshot_date)
);
```

#### 3.4.7 交易时间段配置表 (trading_session_config)

```sql
CREATE TABLE trading_session_config (
    config_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    instrument_type VARCHAR(20),      -- STOCK / FUTURES / OPTION
    phase VARCHAR(30),
    start_time VARCHAR(10),           -- HH:mm
    end_time VARCHAR(10),             -- HH:mm
    allow_order BOOLEAN,
    allow_cancel BOOLEAN,
    allow_match BOOLEAN,
    priority INT,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
```

#### 3.4.8 交易日历表 (trading_calendar)

```sql
CREATE TABLE trading_calendar (
    calendar_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trade_date DATE,
    is_trading_day BOOLEAN,
    market_status VARCHAR(20),        -- OPEN / CLOSED / HOLIDAY
    remark VARCHAR(200),
    create_time TIMESTAMP
);
```

#### 3.4.9 资金流水表 (cashflow)

```sql
CREATE TABLE cashflow (
    flow_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT,
    type VARCHAR(20),           -- DEPOSIT / WITHDRAW / TRADE / COMMISSION
    amount BIGINT,
    balance_after BIGINT,
    remark VARCHAR(200),
    create_time TIMESTAMP,
    INDEX idx_account_time (account_id, create_time)
);
```

---

## 4. 线程模型

### 4.1 线程分类

| 线程类型 | 数量 | 职责 | 数据访问 |
|---------|------|------|----------|
| Tomcat HTTP-IO | 8-32 | 接收HTTP请求 | compute写入（订单网关） |
| WebSocket EventLoop | 8 | WebSocket推送 | 不访问 |
| MatchingEngine | 1 | 订单撮合、五档行情推送 | 写入（orderbook.dat）、内存行情 |
| ClearingWorker | 1 | 成交清算 | compute写入（accounts/positions） |
| PhaseManager | 1 | 交易阶段切换 | 内存阶段状态 |
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
│       │ 2. 检查交易阶段（是否允许下单）                                      │
│       │ 3. 参数校验                                                         │
│       │ 4. 风控检查 (读 accounts.dat)                                       │
│       │ 5. compute 原子冻结资金 (写 accounts.dat)                           │
│       │ 6. 写入 order_queue.dat                                             │
│       │ 7. 返回 orderId                                                     │
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
│       │   1. 检查交易阶段                                                   │
│       │   2. 从 order_queue.dat 读取 OrderEvent                            │
│       │   3. 写入 orderbook.dat (PENDING)                                   │
│       │   4. 执行撮合逻辑（连续竞价/集合竞价）                               │
│       │   5. 生成 TradeEvent，写入 trade_queue.dat                          │
│       │   6. 更新 orderbook.dat (FILLED/PARTIAL_FILLED)                     │
│       │   7. 每3秒：聚合五档行情，更新内存，推送WebSocket                    │
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
│       │   2. 幂等检查 (putIfAbsent processed.dat)                           │
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
│  【交易阶段切换】                                                            │
│                                                                             │
│  PhaseManager Thread (定时任务)                                              │
│       │                                                                     │
│       │ 每1秒检查:                                                          │
│       │   1. 获取当前时间 LocalTime.now()                                   │
│       │   2. 查询配置表，确定当前阶段                                        │
│       │   3. 如果阶段发生变化:                                               │
│       │      - 发布 PhaseChangeEvent                                        │
│       │      - 通知 MatchingEngine                                          │
│       │      - 通知 OrderGateway                                            │
│       │      - 推送 WebSocket phase_update                                  │
│       ▼                                                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.3 线程数量汇总

| 类别 | 线程数 | 说明 |
|------|--------|------|
| Tomcat HTTP | 8-32 | 动态调整 |
| WebSocket EventLoop | 8 | CPU核心数 |
| MatchingEngine | 1 | 撮合引擎 + 五档行情推送 |
| ClearingWorker | 1 | 清算服务 |
| PhaseManager | 1 | 交易阶段管理 |
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
│  1. 交易阶段检查                                                            │
│     - 获取当前交易阶段                                                      │
│     - 检查 allowOrder 是否为 true                                           │
│     - 若不允许，返回错误: "当前阶段不允许下单"                               │
│                                                                             │
│  2. 参数校验                                                                │
│     - symbol 格式校验                                                       │
│     - side 枚举校验                                                         │
│     - price > 0 (限价单)                                                   │
│     - quantity > 0                                                         │
│                                                                             │
│  3. 风控检查 (读 Chronicle Map / 内存)                                       │
│     - 涨跌停价格校验                                                        │
│     - 持仓限额校验                                                          │
│     - 资金充足性预检查                                                       │
│                                                                             │
│  4. 原子冻结 (compute)                                                       │
│                                                                             │
│     买入:                                                                   │
│       amount = price * quantity                                             │
│       compute(accountId) {                                                  │
│           if (available < amount) return REJECT                             │
│           available -= amount                                               │
│           frozen += amount                                                  │
│       }                                                                    │
│                                                                             │
│     卖出:                                                                   │
│       compute(positionKey) {                                                │
│           if (available < quantity) return REJECT                           │
│           available -= quantity                                             │
│           frozen += quantity                                                │
│       }                                                                    │
│                                                                             │
│  5. 生成订单ID                                                              │
│     orderId = snowflakeIdGenerator.nextId()                                │
│                                                                             │
│  6. 写入订单队列                                                            │
│     orderQueue.write(OrderEvent { type: NEW_ORDER, ... })                   │
│                                                                             │
│  7. 返回结果                                                                │
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
│     if (NEW_ORDER): goto 步骤3                                              │
│     if (CANCEL_ORDER): goto 步骤7                                           │
│                                                                             │
│  3. 写入订单簿 (PENDING)                                                    │
│     orderbookMap.put(orderKey, OrderState { status: PENDING, ... })         │
│                                                                             │
│  4. 加入内存订单簿                                                          │
│     orderBook = orderBooks.get(symbol)                                      │
│     orderBook.addOrder(order)                                               │
│                                                                             │
│  5. 执行撮合（根据交易阶段）                                                 │
│                                                                             │
│     连续竞价阶段 (MORNING_SECTION / AFTERNOON_SECTION):                     │
│       trades = orderBook.match()                                            │
│       撮合规则: 价格优先、时间优先                                           │
│                                                                             │
│     集合竞价阶段 (OPEN_MATCH / CLOSE_MATCH):                                │
│       等待阶段结束时统一撮合                                                 │
│       计算开盘价/收盘价（最大成交量原则）                                    │
│                                                                             │
│  6. 输出成交事件                                                            │
│     for (trade in trades):                                                 │
│         tradeQueue.write(trade)                                             │
│         updateOrderbookState(trade)                                         │
│                                                                             │
│  7. 处理撤单                                                                │
│     orderBook.cancel(orderId)                                               │
│     orderbookMap.compute(orderKey) { status = CANCELLED }                   │
│     tradeQueue.write(CancelEvent)                                           │
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
│     existing = processedMap.putIfAbsent(tradeId, state)                     │
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
│  1. 交易阶段检查                                                            │
│     - 检查 allowCancel 是否为 true                                          │
│     - 若不允许，返回错误: "当前阶段不允许撤单"                               │
│                                                                             │
│  2. 参数校验                                                                │
│     - orderId 存在性校验                                                    │
│     - 订单所有权校验                                                        │
│                                                                             │
│  3. 写入撤单请求                                                            │
│     orderQueue.write(OrderEvent { type: CANCEL_ORDER, orderId })            │
│                                                                             │
│  4. 返回结果                                                                │
│     return { status: CANCEL_REQUESTED }                                     │
│                                                                             │
│  阶段2: 撮合引擎处理 (MatchingEngine线程)                                    │
│                                                                             │
│  5. 读取撤单事件                                                            │
│     orderEvent = orderQueue.read()                                          │
│                                                                             │
│  6. 从订单簿移除                                                            │
│     order = orderBook.cancel(orderId)                                       │
│     if (order == null) return  // 订单已成交或不存在                        │
│                                                                             │
│  7. 更新订单簿状态                                                          │
│     orderbookMap.compute(orderKey) { status = CANCELLED }                   │
│                                                                             │
│  8. 生成撤单事件                                                            │
│     cancelEvent = CancelEvent { orderId, remainingQty, ... }                │
│     tradeQueue.write(cancelEvent)                                           │
│                                                                             │
│  阶段3: 清算服务处理 (ClearingWorker线程)                                    │
│                                                                             │
│  9. 读取撤单事件                                                            │
│     cancelEvent = tradeQueue.read()                                         │
│                                                                             │
│  10. 解冻资金/持仓 (compute)                                                │
│      if (买单):                                                             │
│          amount = order.price * cancelEvent.remainingQty                    │
│          accountMap.compute(accountId) {                                    │
│              frozen -= amount                                               │
│              available += amount                                            │
│          }                                                                  │
│      if (卖单):                                                             │
│          positionMap.compute(posKey) {                                      │
│              frozen -= cancelEvent.remainingQty                             │
│              available += cancelEvent.remainingQty                          │
│          }                                                                  │
│                                                                             │
│  11. 推送WebSocket                                                          │
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

**解决方案**：TradeId 去重表 (processed.dat)

```java
ProcessedTradeState existing = processedMap.putIfAbsent(
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
| 成交去重 | Chronicle Map | 清算前putIfAbsent |
| 订单事件 | Chronicle Queue | 下单时立即写入 |
| 成交事件 | Chronicle Queue | 撮合时立即写入 |
| 交易品种 | H2 Database | 新增品种时写入，SOD加载到内存 |
| 成交记录 | H2 Database | 异步批量（每100ms或1000条） |
| 订单历史 | H2 Database | 异步批量 |
| 账户快照 | H2 Database | EOD定时生成 |
| 持仓快照 | H2 Database | EOD定时生成 |

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
│     - processed.dat → 幂等去重表                                            │
│                                                                             │
│  2. 加载交易品种到内存                                                      │
│     - 从H2数据库读取所有ACTIVE状态的品种                                    │
│     - 加载到 ConcurrentHashMap<String, Instrument>                         │
│                                                                             │
│  3. 加载交易时间段配置                                                      │
│     - 从H2数据库读取 trading_session_config                                 │
│     - 加载到内存配置                                                        │
│                                                                             │
│  4. 恢复撮合引擎                                                            │
│     - 从 orderbook.dat 重建内存 OrderBook                                   │
│     - 状态为 PENDING/PARTIAL_FILLED 的订单加入撮合                          │
│                                                                             │
│  5. 恢复处理进度                                                            │
│     orderIndex = progressMap.get("order_queue_index")                       │
│     tradeIndex = progressMap.get("trade_queue_index")                       │
│                                                                             │
│  6. 启动 MatchingEngine                                                     │
│     - 从 orderIndex + 1 开始读取 order_queue                                │
│                                                                             │
│  7. 启动 ClearingWorker                                                     │
│     - 从 tradeIndex + 1 开始读取 trade_queue                                │
│     - 对每个成交执行幂等检查                                                 │
│                                                                             │
│  8. 启动 PhaseManager                                                       │
│     - 启动交易阶段切换定时任务                                              │
│                                                                             │
│  9. 启动其他服务                                                            │
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

### 8.1 行情数据来源

**双行情源设计**

| 数据类型 | 来源 | 用途 |
|---------|------|------|
| **五档行情** | 撮合引擎订单簿 | 交易决策、订单簿展示、下单参考 |
| **K线/历史** | 新浪财经 | 展示、技术分析、总览页面 |
| **指数行情** | 新浪财经 | 总览页面展示 |
| **涨跌排行** | 新浪财经 | 总览页面展示 |

### 8.2 五档行情生成

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        五档行情生成流程                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  MatchingEngine Thread (定时任务，每3秒)                                     │
│       │                                                                     │
│       │ 1. 遍历所有Symbol的OrderBook                                        │
│       │ 2. 聚合买卖五档行情                                                 │
│       │    - 买1-5: 价格从高到低，累计量                                    │
│       │    - 卖1-5: 价格从低到高，累计量                                    │
│       │ 3. 更新内存 MarketDataSnapshot                                      │
│       │ 4. 推送 WebSocket orderbook                                         │
│       ▼                                                                     │
│  五档行情来自撮合引擎订单簿，实时反映市场深度                                  │
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
            { "price": 1005, "volume": 1000 },
            { "price": 1004, "volume": 2500 },
            { "price": 1003, "volume": 1500 },
            { "price": 1002, "volume": 800 },
            { "price": 1001, "volume": 3200 }
        ],
        "ask": [
            { "price": 1006, "volume": 6000 },
            { "price": 1007, "volume": 1200 },
            { "price": 1008, "volume": 4500 },
            { "price": 1009, "volume": 2000 },
            { "price": 1010, "volume": 3800 }
        ]
    }
}
```

### 8.3 新浪财经数据源

**用途说明**：新浪财经数据仅用于展示，不参与撮合定价。

| 接口 | URL | 用途 |
|------|-----|------|
| 股票行情 | `https://hq.sinajs.cn/list={symbols}` | 实时行情+五档 |
| 指数行情 | `https://hq.sinajs.cn/list={index_codes}` | 指数数据 |
| 期货行情 | `https://hq.sinajs.cn/list=nf_{code}` | 期货数据 |
| 期权行情 | `https://hq.sinajs.cn/list=op_{code}` | 期权数据 |
| 涨跌排行 | `https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData` | 排行榜 |

### 8.4 轮询策略

| 数据类型 | 交易时段 | 非交易时段 |
|---------|---------|-----------|
| 自选股行情 | 1秒 | 30秒 |
| 指数行情 | 1秒 | 30秒 |
| 全市场行情 | 3秒 | 60秒 |
| 涨跌排行 | 3秒 | 60秒 |

---

## 9. API设计

### 9.1 Web API

| 模块 | 接口 | 方法 | 说明 |
|------|------|------|------|
| 认证 | /api/auth/login | POST | 用户登录，返回JWT |
| 认证 | /api/auth/logout | POST | 用户登出 |
| 账户 | /api/account/info | GET | 账户信息 |
| 账户 | /api/account/positions | GET | 持仓列表 |
| 订单 | /api/orders | POST | 下单 |
| 订单 | /api/orders/{orderId} | DELETE | 撤单 |
| 订单 | /api/orders/active | GET | 当前委托 |
| 历史 | /api/trades | GET | 历史成交 |
| 历史 | /api/account/cashflow | GET | 资金流水 |
| 行情 | /api/market/symbols | GET | 股票列表 |
| 行情 | /api/market/watchlist | GET/POST/DELETE | 自选股管理 |
| 行情 | /api/market/orderbook/{symbol} | GET | 五档行情 |

### 9.2 OpenAPI

**认证方式**：HMAC-SHA256 签名

```
签名字串 = Method + "\n" + Path + "\n" + Timestamp + "\n" + BodyHash
Signature = HMAC_SHA256(SecretKey, 签名字串)

请求Header:
  X-API-Key: ak_xxxxxxxxxxxx
  X-Timestamp: 1716307200
  X-Signature: 签名结果
```

**OpenAPI 接口**：

| 接口 | 方法 | 说明 | Key类型 |
|------|------|------|---------|
| /openapi/ws | WebSocket | 行情订阅+订单推送 | 只读/交易/做市商 |
| /openapi/account | GET | 账户信息 | 只读/交易/做市商 |
| /openapi/positions | GET | 持仓列表 | 只读/交易/做市商 |
| /openapi/orders | POST | 下单 | 交易/做市商 |
| /openapi/orders/{id} | DELETE | 撤单 | 交易/做市商 |
| /openapi/orders/active | GET | 当前委托 | 只读/交易/做市商 |
| /openapi/orderbook/{symbol} | GET | 五档行情查询 | 只读/交易/做市商 |

### 9.3 WebSocket

**连接**：`ws://host:port/ws?token=<jwt_token>`

**订阅消息**：

```json
{
    "type": "subscribe",
    "channel": "market_snapshot|orderbook|order_update|trade_update|phase_update",
    "symbols": ["600000"]
}
```

**推送消息类型**：

| 类型 | 说明 | 订阅者 |
|------|------|--------|
| market_snapshot | 行情快照 | 所有用户 |
| orderbook | 订单簿五档 | 所有用户 |
| order_update | 订单状态 | 订单所有者 |
| trade_update | 成交推送 | 成交双方 |
| account_update | 账户变动 | 账户所有者 |
| position_update | 持仓变动 | 持仓所有者 |
| phase_update | 交易阶段变更 | 所有用户 |

### 9.4 管理员接口

| 接口 | 方法 | 说明 | 权限 |
|------|------|------|------|
| /admin/deposit | POST | 入金上账 | ADMIN |
| /admin/accounts | GET | 账户列表查询 | ADMIN |
| /admin/account/{id} | GET | 账户详情 | ADMIN |
| /admin/trades | GET | 全部成交记录 | ADMIN |
| /admin/orders | GET | 全部订单记录 | ADMIN |
| /admin/system/status | GET | 系统状态 | ADMIN |
| /admin/instruments | GET/POST | 品种管理 | ADMIN |
| /admin/trading-calendar | GET/POST | 交易日历管理 | ADMIN |

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
| Vite | 5.x | 构建工具 |
| lightweight-charts | 4.x | K线图（TradingView官方开源） |
| Ant Design | 5.x | UI组件 |
| Zustand | 4.x | 状态管理 |
| WebSocket | - | 实时推送 |

### 10.3 前端配置决策

| 配置项 | 决策内容 |
|--------|---------|
| 主题风格 | 深色/浅色可切换 |
| K线默认周期 | 5分钟 |
| 下单面板默认值 | 限价单 |
| 订单簿深度 | 可配置（默认5档） |
| 自选股数量限制 | 最多20只 |
| 响应式设计 | 固定1920x1080分辨率 |

### 10.4 前端构建与部署

```bash
# 前端开发
cd frontend/
npm install
npm run dev         # 开发模式，热更新

# 前端构建
npm run build       # 生产构建
# → 生成 dist/ 目录

# 复制到后端
cp -r dist/* ../src/main/resources/static/

# 后端打包
mvn clean package -DskipTests
# → 生成 target/hft-trading.jar

# 启动运行
java -jar target/hft-trading.jar
# → 访问 http://localhost:8080/
```

---

## 11. 性能分析

### 11.1 延迟分解

| 阶段 | 延迟 | 说明 |
|------|------|------|
| HTTP接收解析 | 1-2ms | Tomcat处理 |
| 风控检查 | ~0.1ms | 读Chronicle Map/内存 |
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
| 账户状态 (1000账户) | ~1MB (Chronicle Map) |
| 持仓状态 (5000持仓) | ~5MB (Chronicle Map) |
| 订单簿 (10000订单) | ~10MB (Chronicle Map) |
| 成交去重表 | ~5MB (Chronicle Map) |
| 交易品种 (5000标的) | ~2MB (Java内存) |
| 行情快照 (5000标的) | ~50MB (Java内存) |
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

---

## 13. 监控与运维

### 13.1 监控配置决策

| 配置项 | 决策内容 |
|--------|---------|
| 日志格式 | JSON格式（便于ELK解析） |
| 日志级别 | INFO |
| 日志保留周期 | 7天 |
| 告警通知方式 | 钉钉 |
| Grafana仪表盘 | 系统总览1个 + 按模块分多个 |
| 链路追踪 | 不需要（单JVM简单架构） |

### 13.2 核心监控指标

| 指标 | 类型 | 说明 |
|------|------|------|
| trading_order_latency | Histogram | 订单处理延迟 |
| trading_clearing_latency | Histogram | 清算延迟 |
| trading_queue_progress | Gauge | 队列处理进度 |
| trading_idempotent_hit | Counter | 幂等命中次数 |
| trading_orderbook_depth | Gauge | 订单簿深度 |
| trading_phase | Gauge | 当前交易阶段 |
| jvm_gc_pause | Histogram | GC暂停时间 |

### 13.3 告警规则

| 告警 | 条件 | 级别 |
|------|------|------|
| 订单延迟过高 | P99 > 20ms | Warning |
| 清算延迟过高 | P99 > 1ms | Warning |
| GC暂停过长 | > 10ms | Warning |
| 队列积压 | > 10000 | Critical |

---

## 14. 业务规则

### 14.1 佣金规则

**仅收取佣金，不收印花税**

| 品种 | 佣金费率 | 最低佣金 | 说明 |
|------|---------|---------|------|
| A股 | 0.025% | 5元 | 按成交金额计算 |
| IF股指期货 | 0.0023% | 无最低 | 按成交金额计算 |
| 50ETF期权 | 5元/张 | 无最低 | 按张数计算 |

### 14.2 T+1 规则

| 品种 | 可卖出时间 | 说明 |
|------|-----------|------|
| A股 | T+1 | 当日买入，下一交易日可卖出 |
| 期货 | T+0 | 清算后立即可平仓 |
| 期权 | T+0 | 清算后立即可平仓 |

### 14.3 涨跌停规则

| 板块 | 涨幅限制 | 跌幅限制 |
|------|---------|---------|
| 主板 | ±10% | ±10% |
| 创业板 | ±20% | ±20% |
| 科创板 | ±20% | ±20% |
| ST/*ST | ±5% | ±5% |

### 14.4 风控检查

| 检查项 | 说明 | 拒绝条件 |
|--------|------|---------|
| 价格有效性 | 价格在涨跌停范围内 | price < limitDown 或 price > limitUp |
| 资金充足性 | 买入时可用资金足够 | available < price * quantity |
| 持仓充足性 | 卖出时可用持仓足够 | position.available < quantity |
| 持仓限额 | 单品种持仓上限 | 超过配置限额 |
| 交易阶段 | 当前阶段是否允许操作 | allowOrder/allowCancel 为 false |

---

## 15. SOD与EOD流程

### 15.1 SOD (Start of Day) 流程

**执行时机**：每日开盘前 09:10 执行

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              SOD流程                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 检查交易日历                                                            │
│     - 查询 trading_calendar 表                                              │
│     - 判断是否为交易日                                                      │
│     - 若非交易日，进入待机状态                                              │
│                                                                             │
│  2. 同步交易品种                                                            │
│     - 调用新浪接口获取股票/期货/期权列表                                    │
│     - 更新 H2 instruments 表                                                │
│     - 计算涨跌停价格                                                        │
│     - 加载到内存 ConcurrentHashMap                                          │
│                                                                             │
│  3. 加载交易时间段配置                                                      │
│     - 从 H2 读取 trading_session_config                                     │
│     - 加载到内存配置                                                        │
│                                                                             │
│  4. 更新持仓T+1可用                                                         │
│     - 遍历所有A股持仓                                                       │
│     - position.available = position.quantity - position.frozen              │
│                                                                             │
│  5. 重置日内状态                                                            │
│     - 清空日内统计                                                          │
│     - 重置限流计数器                                                        │
│                                                                             │
│  6. 数据校验                                                                │
│     - 校验账户不变式: balance = available + frozen + margin                 │
│     - 校验持仓不变式: quantity = available + frozen                         │
│                                                                             │
│  7. 启动交易阶段管理器                                                      │
│     - 启动 PhaseManager 定时任务                                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 15.2 EOD (End of Day) 流程

**执行时机**：每日收盘后 15:30 执行

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              EOD流程                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 等待清算完成                                                            │
│     - 确认所有成交已清算                                                    │
│     - 确认队列处理进度同步                                                  │
│                                                                             │
│  2. 生成账户快照                                                            │
│     - 遍历所有账户 (accounts.dat)                                           │
│     - INSERT INTO account_snapshots (...)                                   │
│                                                                             │
│  3. 生成持仓快照                                                            │
│     - 遍历所有持仓 (positions.dat)                                          │
│     - 计算持仓市值                                                          │
│     - INSERT INTO position_snapshots (...)                                  │
│                                                                             │
│  4. 数据归档                                                                │
│     - 归档当日成交记录                                                      │
│     - 归档当日订单记录                                                      │
│     - 清理过期的Chronicle Queue数据（保留7天）                              │
│                                                                             │
│  5. 数据校验                                                                │
│     - 校验快照数据完整性                                                    │
│     - 校验账户不变式                                                        │
│     - 校验持仓不变式                                                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 16. 交易时间段配置

### 16.1 交易阶段定义

**A股交易阶段**：

| 阶段代码 | 阶段名称 | 时间段 | allowOrder | allowCancel | allowMatch |
|---------|---------|--------|------------|-------------|------------|
| PRE_MARKET | 盘前准备 | 09:10-09:15 | false | false | false |
| OPEN_AUCTION_FREE | 开盘竞价自由申报 | 09:15-09:20 | true | true | false |
| OPEN_AUCTION_LOCK | 开盘竞价锁单 | 09:20-09:25 | true | false | false |
| OPEN_MATCH | 开盘撮合 | 09:25-09:30 | false | false | true |
| MORNING_SECTION | 上午连续竞价 | 09:30-11:30 | true | true | true |
| LUNCH_BREAK | 午间休市 | 11:30-13:00 | false | false | false |
| AFTERNOON_SECTION | 下午连续竞价 | 13:00-14:57 | true | true | true |
| CLOSE_AUCTION_FREE | 收盘竞价自由申报 | 14:57-15:00 | true | true | false |
| CLOSE_AUCTION | 收盘竞价 | 15:00-15:03 | true | false | false |
| CLOSE_MATCH | 收盘撮合 | 15:03-15:05 | false | false | true |
| POST_MARKET | 盘后处理 | 15:05-15:30 | false | false | false |
| CLOSED | 闭市 | 其他时间 | false | false | false |

**期货交易阶段**（IF/IC/IH）：

| 阶段代码 | 时间段 | 说明 |
|---------|--------|------|
| PRE_MARKET | 09:10-09:15 | SOD执行 |
| MORNING_SECTION | 09:15-11:30 | 连续竞价 |
| LUNCH_BREAK | 11:30-13:00 | 休市 |
| AFTERNOON_SECTION | 13:00-15:15 | 连续竞价 |
| POST_MARKET | 15:15-15:30 | EOD执行 |

**期权交易阶段**（50ETF期权）：

| 阶段代码 | 时间段 | 说明 |
|---------|--------|------|
| PRE_MARKET | 09:10-09:15 | SOD执行 |
| OPEN_AUCTION | 09:15-09:25 | 集合竞价 |
| MORNING_SECTION | 09:30-11:30 | 连续竞价 |
| LUNCH_BREAK | 11:30-13:00 | 休市 |
| AFTERNOON_SECTION | 13:00-14:57 | 连续竞价 |
| CLOSE_AUCTION | 14:57-15:00 | 集合竞价 |
| POST_MARKET | 15:00-15:30 | EOD执行 |

### 16.2 交易阶段切换机制

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          交易阶段切换流程                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  PhaseManager (单线程定时任务，每1秒检查)                                    │
│       │                                                                     │
│       │ 1. 获取当前时间 LocalTime.now()                                     │
│       │ 2. 查询配置表，确定当前阶段                                          │
│       │ 3. 如果阶段发生变化:                                                 │
│       │    - 发布 PhaseChangeEvent                                          │
│       │    - 通知 MatchingEngine                                            │
│       │    - 通知 OrderGateway                                              │
│       │    - 推送 WebSocket phase_update                                    │
│       ▼                                                                     │
│                                                                             │
│  MatchingEngine:                                                            │
│    - OPEN_MATCH/CLOSE_MATCH: 执行集合竞价撮合                               │
│    - MORNING_SECTION/AFTERNOON_SECTION: 连续竞价撮合                        │
│    - LUNCH_BREAK/CLOSED: 暂停撮合                                           │
│                                                                             │
│  OrderGateway:                                                              │
│    - 检查 allowOrder: 是否接受订单                                          │
│    - 检查 allowCancel: 是否接受撤单                                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 16.3 集合竞价撮合逻辑

**开盘集合竞价 (09:25-09:30)**：

```
输入: OPEN_AUCTION_FREE + OPEN_AUCTION_LOCK 阶段收集的所有订单

撮合规则:
1. 计算开盘价:
   - 最大成交量原则：找到使成交量最大的价格
   - 若有多个价格满足，选择离昨收价最近的价格
   
2. 撮合执行:
   - 所有有效订单按开盘价撮合
   - 价格优先、时间优先
   
3. 输出:
   - 开盘价
   - 成交列表
   - 未成交订单进入连续竞价订单簿
```

---

## 17. 品种数据管理

### 17.1 新浪财经品种查询接口

**股票列表接口**：

| 市场 | URL |
|------|-----|
| 沪市A股 | `https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData?page=1&num=1000&node=hs_a` |
| 深市A股 | `node=sz_a` |
| 创业板 | `node=cyb` |
| 科创板 | `node=kcb` |

**期货列表接口**：

| 类型 | URL |
|------|-----|
| 股指期货 | `node=gz_qh` |
| 商品期货 | `node=sp_qh` |

**期权列表接口**：

```
https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Option_Center.getOptionList?underlying=510050&exchange=sh
```

### 17.2 品种数据同步流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          品种数据同步流程                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【SOD品种同步】                                                             │
│                                                                             │
│  1. 检查交易日历                                                            │
│     - 查询 trading_calendar 表                                              │
│     - 确认当日为交易日                                                      │
│                                                                             │
│  2. 同步股票列表                                                            │
│     - 调用新浪接口获取沪深A股、创业板、科创板列表                            │
│     - 解析返回数据                                                          │
│     - 更新 H2 instruments 表（新增/更新）                                   │
│     - 计算涨跌停价格                                                        │
│                                                                             │
│  3. 同步期货合约                                                            │
│     - 调用新浪接口获取股指期货列表                                          │
│     - 筛选主力合约和近月合约                                                │
│     - 更新 H2 instruments 表                                                │
│     - 加载保证金比例                                                        │
│                                                                             │
│  4. 同步期权合约                                                            │
│     - 调用新浪接口获取50ETF期权列表                                         │
│     - 筛选当月、下月、下季、隔季合约                                        │
│     - 更新 H2 instruments 表                                                │
│     - 加载行权价、到期日                                                    │
│                                                                             │
│  5. 加载到内存                                                              │
│     - 从 H2 读取所有 ACTIVE 状态品种                                        │
│     - 加载到 ConcurrentHashMap<String, Instrument>                         │
│                                                                             │
│  6. 推送品种更新                                                            │
│     - WebSocket 推送 instrument_list_update                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 17.3 新浪接口限流策略

| 接口类型 | 限流策略 | 说明 |
|---------|---------|------|
| 股票列表 | 1次/分钟 | SOD时批量查询 |
| 期货列表 | 1次/分钟 | SOD时批量查询 |
| 期权列表 | 1次/分钟 | SOD时批量查询 |
| 单只行情 | 3秒/次 | 实时行情轮询 |

### 17.4 品种状态管理

| 状态 | 同步方式 | 说明 |
|------|---------|------|
| ACTIVE | SOD从新浪同步 | 正常交易品种 |
| SUSPENDED | 手动设置 | 停牌品种不接受订单 |
| DELISTED | 手动设置 | 退市品种移除 |
| PRE_LISTING | 手动添加 | 新股待上市 |

---

## 18. 期货期权规格

### 18.1 期货合约规格

| 合约 | 交易所 | 合约乘数 | 最小变动价位 | 交易时段 |
|------|--------|---------|-------------|---------|
| IF | CFFEX | 300 | 0.2点 | 9:15-11:30, 13:00-15:15 |
| IC | CFFEX | 200 | 0.2点 | 9:15-11:30, 13:00-15:15 |
| IH | CFFEX | 300 | 0.2点 | 9:15-11:30, 13:00-15:15 |

### 18.2 期货保证金

| 合约 | 交易所 | 保证金比例 |
|------|--------|-----------|
| IF股指期货 | CFFEX | 12% |
| IC中证500 | CFFEX | 14% |
| IH上证50 | CFFEX | 12% |

### 18.3 期权合约规格

**50ETF期权**：

| 项目 | 规格 |
|------|------|
| 标的 | 50ETF (510050) |
| 合约乘数 | 10000 |
| 最小变动价位 | 0.0001元 |
| 到期月份 | 当月、下月、下季、隔季 |
| 行权价格间距 | 标的价格×3%取整 |

### 18.4 期权Greeks计算

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
```

---

## 19. 限流策略

### 19.1 新浪财经限流

| 接口类型 | 限流策略 | 失败重试 |
|---------|---------|---------|
| 单只行情 | 3秒/次 | 3次，指数退避 |
| 批量行情 | 3秒/次 | 3次，指数退避 |
| 排行榜 | 3秒/次 | 3次，指数退避 |

### 19.2 OpenAPI限流

| 限制项 | 阈值 | 说明 |
|--------|------|------|
| 请求速率 | 500笔/秒 | 滑动窗口计数 |
| 超限处理 | 拒绝 | 返回 429 Too Many Requests |

### 19.3 WebSocket心跳

| 参数 | 值 | 说明 |
|------|-----|------|
| 心跳间隔 | 30秒 | 客户端发送ping |
| 超时断开 | 60秒 | 2个心跳周期无响应 |

### 19.4 JWT Token管理

| 参数 | 值 | 说明 |
|------|-----|------|
| 初始有效期 | 2小时 | 登录时生成 |
| 滑动延期 | 2小时 | 每次请求自动延期 |
| 最大有效期 | 24小时 | 单次登录最长有效期 |

### 19.5 API Key规格

| 项目 | 规格 |
|------|------|
| API Key格式 | UUID v4，32位，前缀 ak_ |
| Secret Key格式 | UUID v4，32位 |
| 存储方式 | Secret Key加盐哈希 SHA-256 |

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
| EOD | End of Day，日终流程 |
| Greeks | 期权风险指标（Delta、Gamma、Theta、Vega、Rho） |

### C. 数据持久化保证

| 保证项 | 说明 |
|--------|------|
| RPO | 0（无数据丢失） |
| Chronicle文件 | 实时持久化到磁盘 |
| 重启恢复 | 自动从Chronicle文件恢复 |
| 数据校验 | SOD时校验不变式 |
