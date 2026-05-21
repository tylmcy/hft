# A股交易系统技术方案 V7

> 版本: v7.0
> 日期: 2026-05-21
> 架构师: Trading System Architect
> 状态: **设计完成，可落地开发**

---

## 修订历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| v6.0 | 2026-05-21 | 落地审查修订：补充Chronicle Queue轮转、T+1 EOD解锁、保证金公式、自成交预防、集合竞价算法等 |
| v7.0 | 2026-05-21 | 全面审查修订：修复关键问题、补充实现细节、完善数据模型、增强安全性、优化核心流程 |

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
20. [Chronicle Queue轮转策略](#20-chronicle-queue轮转策略)
21. [T+1追踪机制](#21-t1追踪机制)
22. [保证金计算公式](#22-保证金计算公式)
23. [自成交预防算法](#23-自成交预防算法)
24. [集合竞价算法](#24-集合竞价算法)
25. [持仓限额与强制平仓](#25-持仓限额与强制平仓)
26. [涨跌停限制](#26-涨跌停限制)
27. [OpenAPI认证细节](#27-openapi认证细节)
28. [错误响应标准](#28-错误响应标准)
29. [幂等性支持](#29-幂等性支持)
30. [前端状态管理](#30-前端状态管理)
31. [安全设计](#31-安全设计)
32. [审计日志](#32-审计日志)

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
| **吞吐量** | ≥ 100,000 TPS | 订单+撤单总量 |
| **延迟** | ≤ 15ms (P99) | 端到端，从下单到成交回报 |
| **清算吞吐** | ≥ 10,000 TPS | 成交清算处理能力 |
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
| **服务器配置** | 阿里云 8核CPU / 32GB内存 / 1TB SSD |
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

#### 1.6.1 用户认证与安全

| 决策项 | 决策内容 |
|--------|---------|
| 用户认证方式 | 用户名密码登录 |
| 密码加密算法 | SHA-256 + 盐值 |
| 登录验证码 | 不需要 |
| JWT算法 | RS256（非对称加密） |
| Token刷新 | 支持Refresh Token机制 |

#### 1.6.2 ID生成策略

| 决策项 | 决策内容 |
|--------|---------|
| 账户ID生成 | 雪花算法，保证 < 2^32（约43亿）避免Key溢出 |
| 订单ID生成 | 雪花算法，workerId固定配置 |
| 成交ID生成 | 雪花算法，workerId固定配置 |
| SymbolCode生成 | 自增序列，从1开始 |

#### 1.6.3 数据处理

| 决策项 | 决策内容 |
|--------|---------|
| 资金精度 | 分（整数），足够 |
| 异常订单处理 | 记录日志跳过，不影响主流程 |
| H2数据库目录 | data/h2/ |
| Chronicle文件目录 | data/chronicle/{date}/ |

#### 1.6.4 做市商

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
│  │  │  idempotency.dat 订单幂等    │  │  account_snapshots 账户快照  │  │   │
│  │  │  symbol_registry.dat 标的码 │  │  position_snapshots 持仓快照 │  │   │
│  │  │                             │  │  audit_logs     审计日志    │  │   │
│  │  │                             │  │  trading_session_config     │  │   │
│  │  │                             │  │  trading_calendar           │  │   │
│  │  └─────────────────────────────┘  └─────────────────────────────┘  │   │
│  │                                                                     │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │                    Java 内存存储                             │   │   │
│  │  │  ConcurrentHashMap<String, Instrument>    交易品种           │   │   │
│  │  │  ConcurrentHashMap<String, MarketData>    行情快照           │   │   │
│  │  │  SymbolRegistry                           标的代码映射        │   │   │
│  │  │  volatile TradingPhase                    当前交易阶段        │   │   │
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
| **风险监控** | 实时风险度计算、强平触发 | 单线程定时任务 |
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
| idempotency.dat | 订单幂等键 | 订单提交幂等性检查 |
| symbol_registry.dat | 标的代码映射 | symbol与symbolCode双向映射 |

#### 3.1.1 账户状态 (accounts.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| accountId | long | 账户ID（Key），保证 < 2^32 |
| userId | long | 用户ID，用于自成交预防 |
| balance | long | 账户余额（分） |
| available | long | 可用资金（分） |
| frozen | long | 冻结资金（分），含委托冻结和保证金冻结 |
| unrealizedPnl | long | 未实现盈亏（分） |
| updateTime | long | 更新时间戳 |

**不变式**：
- `balance = available + frozen`（资金守恒）
- `总权益 = balance + unrealizedPnl`（含浮动盈亏）

**说明**：
- `balance`: 账户余额（不含浮动盈亏）
- `frozen`: 包含委托下单冻结的资金 + 期货/期权占用的保证金
- `unrealizedPnl`: 持仓浮动盈亏，实时更新

#### 3.1.2 持仓状态 (positions.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| key | long | Key = accountId × 100000 + symbolCode |
| accountId | long | 账户ID |
| symbolCode | int | 标的代码（数值） |
| symbol | String | 标的代码（字符串） |
| quantity | long | 持仓数量（正数=多头，负数=空头） |
| available | long | 可用数量（T+0，可卖出） |
| frozen | long | 冻结数量（委托卖出冻结） |
| t1Locked | long | T+1锁定数量（当日买入，EOD解锁） |
| avgPrice | long | 成本价（分） |
| updateTime | long | 更新时间戳 |

**不变式**：`|quantity| = available + frozen + t1Locked`

**说明**：
- `quantity`: 正数表示多头持仓，负数表示空头持仓（期货/期权）
- `available`: T+0可卖数量（之前买入且已过T+1）
- `t1Locked`: 当日买入数量，EOD解锁后变为available
- `frozen`: 当前卖出委托冻结的数量

#### 3.1.3 订单簿状态 (orderbook.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| orderId | long | 订单ID（Key），雪花算法生成 |
| symbol | String | 标的代码 |
| symbolCode | int | 标的代码（数值） |
| side | enum | BUY/SELL |
| orderType | enum | LIMIT/MARKET/FAK/FOK |
| price | long | 价格（分），MARKET单为0 |
| quantity | long | 原始数量 |
| filledQuantity | long | 已成交数量 |
| remainingQty | long | 剩余数量 = quantity - filledQuantity |
| accountId | long | 账户ID |
| status | enum | PENDING/PARTIAL_FILLED/FILLED/CANCELLED |
| createTime | long | 创建时间 |
| updateTime | long | 更新时间 |

**Key设计说明**：
- 使用`orderId`直接作为Key，因为雪花算法生成的ID全局唯一
- 避免复合Key的溢出问题

#### 3.1.4 处理进度 (progress.dat)

| Key | Value | 说明 |
|-----|-------|------|
| order_queue_index | long | 订单队列处理索引 |
| trade_queue_index | long | 成交队列处理索引 |

#### 3.1.5 成交去重表 (processed.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| tradeId | long | 成交ID（Key） |
| queueIndex | long | 队列索引 |
| processTime | long | 处理时间戳 |

#### 3.1.6 标的代码注册表 (symbol_registry.dat)

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 标的代码（Key） |
| symbolCode | int | 数值代码（Value） |

**反向映射**：内存中维护 `Map<Integer, String> codeToSymbol`

### 3.2 Chronicle Queue 数据结构

#### 3.2.1 订单队列 (order_queue.dat)

**事件类型**：NEW_ORDER / CANCEL_ORDER

```
OrderEvent {
    eventType: enum,      // NEW_ORDER / CANCEL_ORDER
    orderId: long,
    symbol: String,
    symbolCode: int,
    side: enum,           // BUY / SELL
    orderType: enum,      // LIMIT / MARKET / FAK / FOK
    price: long,
    quantity: long,
    accountId: long,
    userId: long,
    idempotencyKey: String,  // 幂等键（可选）
    timestamp: long
}
```

#### 3.2.2 成交队列 (trade_queue.dat)

```
TradeEvent {
    eventType: enum,      // TRADE / CANCEL
    tradeId: long,
    orderId: long,
    symbol: String,
    symbolCode: int,
    price: long,
    quantity: long,
    buyAccountId: long,
    sellAccountId: long,
    buyOrderId: long,
    sellOrderId: long,
    buyCommission: long,    // 买方佣金
    sellCommission: long,   // 卖方佣金
    timestamp: long,
    queueIndex: long       // 由消费者填充
}
```

**事件类型说明**：
- `TRADE`: 成交事件，触发清算流程
- `CANCEL`: 撤单事件，触发资金/持仓解冻

### 3.3 Java 内存数据结构

#### 3.3.1 交易品种 (Instrument)

**存储位置**：H2数据库 + Java内存（ConcurrentHashMap<String, Instrument>）

**加载时机**：SOD启动时从H2数据库加载到内存

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 标的代码（Key） |
| symbolCode | int | 数值代码 |
| name | String | 名称 |
| type | enum | STOCK / FUTURES / OPTION |
| exchange | enum | SH / SZ / CFFEX / SHFE |
| preClose | long | 昨收价（分） |
| limitUp | long | 涨停价（分） |
| limitDown | long | 跌停价（分） |
| limitUpRate | int | 涨幅限制（万分之） |
| limitDownRate | int | 跌幅限制（万分之） |
| minPriceUnit | long | 最小变动价位（分） |
| lotSize | int | 每手股数（A股100，期货各不同） |
| contractMultiplier | int | 合约乘数（期货/期权） |
| marginRatio | long | 保证金比例（万分之） |
| maintenanceMarginRatio | long | 维持保证金比例（万分之） |
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

#### 3.3.3 标的代码注册表 (SymbolRegistry)

```java
public class SymbolRegistry {
    private final ConcurrentHashMap<String, Integer> symbolToCode = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> codeToSymbol = new ConcurrentHashMap<>();
    private final AtomicInteger nextCode = new AtomicInteger(1);
    
    /**
     * 获取或创建标的代码
     * 线程安全，使用computeIfAbsent保证原子性
     */
    public int getOrCreateCode(String symbol) {
        return symbolToCode.computeIfAbsent(symbol, s -> {
            int code = nextCode.getAndIncrement();
            codeToSymbol.put(code, s);
            // 持久化到symbol_registry.dat
            symbolRegistryMap.put(s, code);
            return code;
        });
    }
    
    public int getCode(String symbol) {
        return symbolToCode.getOrDefault(symbol, -1);
    }
    
    public String getSymbol(int code) {
        return codeToSymbol.get(code);
    }
}
```

### 3.4 H2 数据库表结构

**数据库文件位置**：`data/h2/trading.mv.db`

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
    buy_commission BIGINT,
    sell_commission BIGINT,
    trade_time TIMESTAMP,
    create_time TIMESTAMP,
    INDEX idx_trades_buy_account (buy_account_id, trade_time),
    INDEX idx_trades_sell_account (sell_account_id, trade_time),
    INDEX idx_trades_symbol_time (symbol, trade_time),
    INDEX idx_trades_buy_order (buy_order_id),
    INDEX idx_trades_sell_order (sell_order_id)
);
```

#### 3.4.2 订单历史表 (orders)

```sql
CREATE TABLE orders (
    order_id BIGINT PRIMARY KEY,
    account_id BIGINT,
    user_id BIGINT,
    symbol VARCHAR(20),
    side VARCHAR(10),
    order_type VARCHAR(10),
    price BIGINT,
    quantity BIGINT,
    filled_quantity BIGINT,
    status VARCHAR(20),
    cancel_reason VARCHAR(50),
    idempotency_key VARCHAR(64),
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    INDEX idx_orders_account (account_id, create_time),
    INDEX idx_orders_user (user_id, create_time),
    INDEX idx_orders_symbol_time (symbol, create_time),
    INDEX idx_orders_status (status, create_time),
    INDEX idx_orders_idempotency (idempotency_key)
);
```

#### 3.4.3 API密钥表 (api_keys)

```sql
CREATE TABLE api_keys (
    key_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT,
    user_id BIGINT,
    api_key VARCHAR(64) UNIQUE,
    secret_key_encrypted VARCHAR(256),  -- AES-256-GCM加密后的Secret Key
    secret_key_hash VARCHAR(128),       -- SHA-256哈希（用于快速验证）
    salt VARCHAR(64),                   -- 加密盐值
    key_name VARCHAR(100),
    key_type VARCHAR(20),      -- READ_ONLY / TRADING / MARKET_MAKER
    symbols VARCHAR(100),      -- STOCK,FUTURES,OPTION
    is_market_maker BOOLEAN DEFAULT FALSE,
    status VARCHAR(20),        -- ACTIVE / DISABLED / EXPIRED
    create_time TIMESTAMP,
    expire_time TIMESTAMP,
    last_used_time TIMESTAMP,
    INDEX idx_api_keys_account (account_id),
    INDEX idx_api_keys_key (api_key)
);
```

**Secret Key存储说明**：
- `secret_key_encrypted`: 使用AES-256-GCM加密存储原始Secret Key，用于HMAC签名验证
- `secret_key_hash`: SHA-256哈希值，用于快速查找和比对
- 加密密钥由系统启动时从环境变量或密钥管理服务获取

#### 3.4.4 交易品种表 (instruments)

```sql
CREATE TABLE instruments (
    symbol VARCHAR(20) PRIMARY KEY,
    symbol_code INT UNIQUE,
    name VARCHAR(100),
    type VARCHAR(20),          -- STOCK / FUTURES / OPTION
    exchange VARCHAR(10),      -- SH / SZ / CFFEX / SHFE
    pre_close BIGINT,
    limit_up BIGINT,
    limit_down BIGINT,
    limit_up_rate INT,
    limit_down_rate INT,
    min_price_unit BIGINT,
    lot_size INT,
    contract_multiplier INT,
    margin_ratio BIGINT,
    maintenance_margin_ratio BIGINT,
    strike_price BIGINT,
    expiry_date INT,
    underlying VARCHAR(20),
    status VARCHAR(20),        -- ACTIVE / SUSPENDED / DELISTED / PRE_LISTING
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    INDEX idx_instruments_type (type, status),
    INDEX idx_instruments_exchange (exchange, status),
    INDEX idx_instruments_code (symbol_code)
);
```

#### 3.4.5 账户快照表 (account_snapshots)

```sql
CREATE TABLE account_snapshots (
    snapshot_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT,
    user_id BIGINT,
    snapshot_date DATE,
    balance BIGINT,
    available BIGINT,
    frozen BIGINT,
    unrealized_pnl BIGINT,
    create_time TIMESTAMP,
    INDEX idx_account_date (account_id, snapshot_date),
    INDEX idx_user_date (user_id, snapshot_date)
);
```

#### 3.4.6 持仓快照表 (position_snapshots)

```sql
CREATE TABLE position_snapshots (
    snapshot_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT,
    symbol VARCHAR(20),
    symbol_code INT,
    snapshot_date DATE,
    quantity BIGINT,
    available BIGINT,
    frozen BIGINT,
    t1_locked BIGINT,
    avg_price BIGINT,
    market_value BIGINT,
    create_time TIMESTAMP,
    INDEX idx_account_date (account_id, snapshot_date),
    INDEX idx_symbol_date (symbol, snapshot_date)
);
```

#### 3.4.7 审计日志表 (audit_logs)

```sql
CREATE TABLE audit_logs (
    log_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    log_type VARCHAR(30),      -- ORDER / TRADE / ACCOUNT / ADMIN / AUTH
    account_id BIGINT,
    user_id BIGINT,
    action VARCHAR(50),
    target_type VARCHAR(30),
    target_id BIGINT,
    old_value TEXT,
    new_value TEXT,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    create_time TIMESTAMP,
    INDEX idx_audit_account (account_id, create_time),
    INDEX idx_audit_user (user_id, create_time),
    INDEX idx_audit_type_time (log_type, create_time),
    INDEX idx_audit_target (target_type, target_id)
);
```

#### 3.4.8 交易时间段配置表 (trading_session_config)

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
    update_time TIMESTAMP,
    INDEX idx_config_type (instrument_type)
);
```

#### 3.4.9 交易日历表 (trading_calendar)

```sql
CREATE TABLE trading_calendar (
    calendar_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trade_date DATE UNIQUE,
    is_trading_day BOOLEAN,
    market_status VARCHAR(20),        -- OPEN / CLOSED / HOLIDAY
    remark VARCHAR(200),
    create_time TIMESTAMP,
    INDEX idx_calendar_date (trade_date)
);
```

#### 3.4.10 资金流水表 (cashflow)

```sql
CREATE TABLE cashflow (
    flow_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT,
    user_id BIGINT,
    type VARCHAR(20),           -- DEPOSIT / WITHDRAW / TRADE / COMMISSION / MARGIN
    amount BIGINT,
    balance_after BIGINT,
    reference_type VARCHAR(30), -- ORDER / TRADE / ADMIN
    reference_id BIGINT,
    remark VARCHAR(200),
    create_time TIMESTAMP,
    INDEX idx_account_time (account_id, create_time),
    INDEX idx_reference (reference_type, reference_id)
);
```

#### 3.4.11 JWT令牌黑名单表 (token_blacklist)

```sql
CREATE TABLE token_blacklist (
    token_id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT,
    reason VARCHAR(50),         -- LOGOUT / PASSWORD_CHANGE / ADMIN_REVOKE
    expire_time TIMESTAMP,
    create_time TIMESTAMP,
    INDEX idx_blacklist_expire (expire_time)
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
| PhaseManager | 1 | 交易阶段切换 | volatile阶段状态 |
| RiskMonitor | 1 | 风险度监控、强平触发 | 读accounts/positions |
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
│       │ 2. 检查交易阶段（volatile读）                                        │
│       │ 3. 参数校验                                                         │
│       │ 4. 风控检查 (读 Chronicle Map / 内存)                                │
│       │ 5. 自成交检查 (读 orderbook.dat)                                     │
│       │ 6. compute 原子冻结资金 (写 accounts.dat)                           │
│       │ 7. 写入 order_queue.dat                                             │
│       │ 8. 返回 orderId                                                     │
│       ▼                                                                     │
│  同步完成，无线程切换                                                         │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【订单撮合】                                                                │
│                                                                             │
│  MatchingEngine Thread (单线程)                                              │
│       │                                                                     │
│       │ 循环:                                                               │
│       │   1. 检查交易阶段（volatile读）                                      │
│       │   2. 检查背压标志，若激活则暂停10ms                                   │
│       │   3. 从 order_queue.dat 读取 OrderEvent                            │
│       │   4. 写入 orderbook.dat (PENDING)                                   │
│       │   5. 执行撮合逻辑（连续竞价/集合竞价）                               │
│       │   6. 生成 TradeEvent，写入 trade_queue.dat                          │
│       │   7. 更新 orderbook.dat (FILLED/PARTIAL_FILLED)                     │
│       │   8. 每3秒：聚合五档行情，更新内存，推送WebSocket                    │
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
│       │      - volatile写新阶段                                             │
│       │      - 通知 MatchingEngine                                          │
│       │      - 推送 WebSocket phase_update                                  │
│       ▼                                                                     │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【风险监控】                                                                │
│                                                                             │
│  RiskMonitor Thread (定时任务)                                               │
│       │                                                                     │
│       │ 每1秒检查:                                                          │
│       │   1. 遍历所有账户                                                   │
│       │   2. 计算风险度                                                     │
│       │   3. 如果风险度 >= 100%:                                            │
│       │      - 发送强平预警                                                 │
│       │      - 等待60秒                                                     │
│       │      - 若仍超限，执行强平                                           │
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
| RiskMonitor | 1 | 风险监控 |
| AsyncPersistence | 1 | H2持久化 |
| **总计** | **21-45** | 实际活跃约16-22 |

---

## 5. 核心流程

### 5.1 下单流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              下单流程                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  输入: OrderRequest { symbol, side, orderType, price, quantity, idempotencyKey } │
│  输出: OrderResult { orderId, status }                                      │
│                                                                             │
│  步骤:                                                                      │
│                                                                             │
│  1. 幂等键检查（如果提供）                                                   │
│     if (idempotencyKey != null) {                                           │
│         existing = idempotencyMap.get(idempotencyKey);                      │
│         if (existing != null) {                                             │
│             return OrderResult.success(existing.orderId);                   │
│         }                                                                   │
│     }                                                                       │
│                                                                             │
│  2. 交易阶段检查                                                            │
│     phase = phaseManager.getCurrentPhase();  // volatile读                  │
│     if (!phase.allowOrder) {                                                │
│         throw new OrderRejectException("当前阶段不允许下单: " + phase);      │
│     }                                                                       │
│                                                                             │
│  3. 参数校验                                                                │
│     - symbol 格式校验                                                       │
│     - side 枚举校验 (BUY/SELL)                                              │
│     - orderType 枚举校验 (LIMIT/MARKET/FAK/FOK)                             │
│     - LIMIT单: price > 0                                                   │
│     - quantity > 0                                                         │
│     - quantity % lotSize == 0 (整手校验)                                    │
│                                                                             │
│  4. 获取品种信息                                                            │
│     instrument = instrumentMap.get(symbol);                                 │
│     if (instrument == null || instrument.status != ACTIVE) {                │
│         throw new OrderRejectException("品种不可交易");                      │
│     }                                                                       │
│                                                                             │
│  5. 涨跌停价格校验（LIMIT单）                                                │
│     if (orderType == LIMIT) {                                               │
│         if (price > instrument.limitUp || price < instrument.limitDown) {    │
│             throw new OrderRejectException("价格超出涨跌停范围");            │
│         }                                                                   │
│     }                                                                       │
│                                                                             │
│  6. 持仓限额校验                                                            │
│     position = positionMap.get(accountId, symbolCode);                      │
│     newPosition = position.quantity + quantity;  // 买入                    │
│     if (newPosition > instrument.positionLimit) {                           │
│         throw new OrderRejectException("超过持仓限额");                      │
│     }                                                                       │
│                                                                             │
│  7. 自成交检查                                                              │
│     if (checkSelfTrade(accountId, userId, symbol, side, price)) {            │
│         throw new OrderRejectException("自成交检测: 存在冲突订单");          │
│     }                                                                       │
│                                                                             │
│  8. 计算冻结金额                                                            │
│     if (side == BUY) {                                                      │
│         if (orderType == MARKET) {                                          │
│             // 市价单按涨停价冻结（最坏情况）                                 │
│             freezeAmount = instrument.limitUp * quantity;                   │
│         } else {                                                            │
│             freezeAmount = price * quantity;                                │
│         }                                                                   │
│     }                                                                       │
│                                                                             │
│  9. 原子冻结 (compute)                                                       │
│     if (side == BUY) {                                                      │
│         accountMap.compute(accountId, (k, acc) -> {                         │
│             if (acc.available < freezeAmount) {                             │
│                 throw new InsufficientFundsException();                     │
│             }                                                               │
│             acc.available -= freezeAmount;                                  │
│             acc.frozen += freezeAmount;                                     │
│             acc.updateTime = now;                                           │
│             return acc;                                                     │
│         });                                                                 │
│     } else { // SELL                                                        │
│         positionMap.compute(positionKey, (k, pos) -> {                      │
│             if (pos.available < quantity) {                                 │
│                 throw new InsufficientPositionException();                  │
│             }                                                               │
│             pos.available -= quantity;                                      │
│             pos.frozen += quantity;                                         │
│             pos.updateTime = now;                                           │
│             return pos;                                                     │
│         });                                                                 │
│     }                                                                       │
│                                                                             │
│  10. 生成订单ID                                                             │
│      orderId = snowflakeIdGenerator.nextId();                              │
│                                                                             │
│  11. 存储幂等键（如果提供）                                                  │
│      if (idempotencyKey != null) {                                          │
│          idempotencyMap.put(idempotencyKey, new IdempotencyValue(orderId)); │
│      }                                                                      │
│                                                                             │
│  12. 写入订单队列                                                           │
│      orderQueue.write(OrderEvent { type: NEW_ORDER, ... });                 │
│                                                                             │
│  13. 记录审计日志                                                           │
│      auditLog.record(AuditType.ORDER, accountId, "CREATE", orderId);        │
│                                                                             │
│  14. 返回结果                                                               │
│      return OrderResult { orderId, status: PENDING };                       │
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
│  1. 检查背压标志                                                            │
│     if (throttled) {                                                        │
│         Thread.sleep(10);  // 暂停10ms                                      │
│     }                                                                       │
│                                                                             │
│  2. 读取订单事件                                                            │
│     orderEvent = orderQueue.read();                                         │
│                                                                             │
│  3. 判断事件类型                                                            │
│     if (NEW_ORDER): goto 步骤4                                              │
│     if (CANCEL_ORDER): goto 步骤9                                           │
│                                                                             │
│  4. 写入订单簿 (PENDING)                                                    │
│     orderbookMap.put(orderId, OrderState { status: PENDING, ... });         │
│                                                                             │
│  5. 根据订单类型执行撮合                                                    │
│                                                                             │
│     LIMIT (限价单):                                                         │
│       trades = matchLimit(order);                                           │
│                                                                             │
│     MARKET (市价单):                                                        │
│       trades = matchMarket(order);                                          │
│                                                                             │
│     FAK (Fill and Kill):                                                    │
│       trades = matchFAK(order);                                             │
│                                                                             │
│     FOK (Fill or Kill):                                                     │
│       trades = matchFOK(order);                                             │
│                                                                             │
│  6. 输出成交事件                                                            │
│     for (trade in trades):                                                 │
│         tradeQueue.write(trade);                                            │
│         updateOrderbookState(trade);                                        │
│                                                                             │
│  7. 更新订单状态                                                            │
│     if (order.remainingQty == 0):                                           │
│         order.status = FILLED;                                              │
│     else if (order.filledQuantity > 0):                                     │
│         order.status = PARTIAL_FILLED;                                      │
│                                                                             │
│  8. 未成交部分留在订单簿（LIMIT单）                                          │
│     if (order.orderType == LIMIT && order.remainingQty > 0) {               │
│         orderBook.addOrder(order);                                          │
│     }                                                                       │
│                                                                             │
│  9. 处理撤单                                                                │
│     order = orderBook.cancel(orderId);                                      │
│     if (order == null) return;  // 订单已成交或不存在                       │
│     orderbookMap.compute(orderId, (k, o) -> {                               │
│         o.status = CANCELLED;                                               │
│         return o;                                                           │
│     });                                                                     │
│     tradeQueue.write(CancelEvent { orderId, remainingQty });                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2.1 订单类型撮合逻辑详解

**LIMIT (限价单)**：
```java
public List<Trade> matchLimit(Order order) {
    List<Trade> trades = new ArrayList<>();
    OrderBook book = orderBooks.get(order.getSymbol());
    
    while (order.getRemainingQty() > 0) {
        Order counterOrder = book.getBestCounterOrder(order);
        if (counterOrder == null || !canMatch(order, counterOrder)) {
            break;  // 无对手方或价格不匹配
        }
        
        // 自成交检查
        if (isSelfTrade(order, counterOrder)) {
            book.skipOrder(counterOrder);  // 跳过自成交订单
            continue;
        }
        
        Trade trade = createTrade(order, counterOrder);
        trades.add(trade);
        updateOrderState(order, counterOrder, trade);
    }
    
    return trades;
}

private boolean canMatch(Order order, Order counterOrder) {
    if (order.getSide() == BUY) {
        return order.getPrice() >= counterOrder.getPrice();
    } else {
        return order.getPrice() <= counterOrder.getPrice();
    }
}
```

**MARKET (市价单)**：
```java
public List<Trade> matchMarket(Order order) {
    List<Trade> trades = new ArrayList<>();
    OrderBook book = orderBooks.get(order.getSymbol());
    Instrument instrument = instrumentMap.get(order.getSymbol());
    
    while (order.getRemainingQty() > 0) {
        Order counterOrder = book.getBestCounterOrder(order);
        if (counterOrder == null) {
            break;  // 无对手方
        }
        
        // 涨跌停保护
        if (order.getSide() == BUY && counterOrder.getPrice() > instrument.getLimitUp()) {
            break;  // 买入价格超过涨停，停止
        }
        if (order.getSide() == SELL && counterOrder.getPrice() < instrument.getLimitDown()) {
            break;  // 卖出价格低于跌停，停止
        }
        
        // 自成交检查
        if (isSelfTrade(order, counterOrder)) {
            book.skipOrder(counterOrder);
            continue;
        }
        
        Trade trade = createTrade(order, counterOrder);
        trades.add(trade);
        updateOrderState(order, counterOrder, trade);
    }
    
    // 市价单未成交部分自动取消
    if (order.getRemainingQty() > 0) {
        order.setStatus(OrderStatus.CANCELLED);
    }
    
    return trades;
}
```

**FAK (Fill and Kill)**：
```java
public List<Trade> matchFAK(Order order) {
    List<Trade> trades = new ArrayList<>();
    OrderBook book = orderBooks.get(order.getSymbol());
    
    // 立即撮合，不加入订单簿
    while (order.getRemainingQty() > 0) {
        Order counterOrder = book.getBestCounterOrder(order);
        if (counterOrder == null || !canMatch(order, counterOrder)) {
            break;
        }
        
        if (isSelfTrade(order, counterOrder)) {
            book.skipOrder(counterOrder);
            continue;
        }
        
        Trade trade = createTrade(order, counterOrder);
        trades.add(trade);
        updateOrderState(order, counterOrder, trade);
    }
    
    // 未成交部分立即取消
    if (order.getRemainingQty() > 0) {
        order.setStatus(OrderStatus.CANCELLED);
    }
    
    return trades;
}
```

**FOK (Fill or Kill)**：
```java
public List<Trade> matchFOK(Order order) {
    OrderBook book = orderBooks.get(order.getSymbol());
    
    // 先检查是否可全部成交
    long availableVolume = calculateAvailableVolume(book, order);
    if (availableVolume < order.getQuantity()) {
        // 无法全部成交，直接取消
        order.setStatus(OrderStatus.CANCELLED);
        return Collections.emptyList();
    }
    
    // 可全部成交，执行撮合
    return matchLimit(order);
}

private long calculateAvailableVolume(OrderBook book, Order order) {
    long volume = 0;
    if (order.getSide() == BUY) {
        // 累计所有卖单价格 <= 买入价的量
        for (Order sellOrder : book.getSellOrdersUpTo(order.getPrice())) {
            if (!isSelfTrade(order, sellOrder)) {
                volume += sellOrder.getRemainingQty();
            }
        }
    } else {
        // 累计所有买单价格 >= 卖出价的量
        for (Order buyOrder : book.getBuyOrdersDownTo(order.getPrice())) {
            if (!isSelfTrade(order, buyOrder)) {
                volume += buyOrder.getRemainingQty();
            }
        }
    }
    return volume;
}
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
│     trade = tradeQueue.read();                                              │
│     queueIndex = tailer.index();                                            │
│                                                                             │
│  2. 幂等检查                                                                │
│     existing = processedMap.putIfAbsent(tradeId, state);                    │
│     if (existing != null) return;  // 已处理，跳过                          │
│                                                                             │
│  3. 获取品种信息                                                            │
│     instrument = instrumentMap.get(trade.getSymbol());                      │
│                                                                             │
│  4. 计算佣金                                                                │
│     buyCommission = calculateCommission(instrument, trade, BUY);            │
│     sellCommission = calculateCommission(instrument, trade, SELL);          │
│                                                                             │
│  5. 买方清算                                                                │
│                                                                             │
│     a. 解冻并扣款 (compute)                                                 │
│        frozenAmount = trade.price * trade.quantity;                         │
│        accountMap.compute(buyAccountId, (k, acc) -> {                       │
│            acc.frozen -= frozenAmount;                                      │
│            acc.balance -= frozenAmount;                                     │
│            acc.available -= buyCommission;                                  │
│            acc.updateTime = now;                                            │
│            return acc;                                                      │
│        });                                                                  │
│                                                                             │
│     b. 增加持仓 (compute)                                                   │
│        positionMap.compute(buyerPosKey, (k, pos) -> {                       │
│            if (pos == null) pos = new Position();                           │
│            long oldQty = pos.quantity;                                      │
│            pos.quantity += trade.quantity;                                  │
│                                                                             │
│            // T+1处理                                                       │
│            if (instrument.type == STOCK) {                                  │
│                pos.t1Locked += trade.quantity;  // A股T+1锁定               │
│            } else {                                                         │
│                pos.available += trade.quantity;  // 期货期权T+0             │
│            }                                                                │
│                                                                             │
│            // 更新均价                                                      │
│            long cost = trade.price * trade.quantity;                        │
│            pos.avgPrice = (pos.avgPrice * oldQty + cost) / pos.quantity;    │
│            pos.updateTime = now;                                            │
│            return pos;                                                      │
│        });                                                                  │
│                                                                             │
│     c. 更新保证金（期货/期权）                                               │
│        if (instrument.type != STOCK) {                                      │
│            margin = calculateMargin(instrument, pos);                       │
│            accountMap.compute(buyAccountId, (k, acc) -> {                   │
│                acc.frozen += margin;                                        │
│                return acc;                                                  │
│            });                                                              │
│        }                                                                    │
│                                                                             │
│  6. 卖方清算                                                                │
│                                                                             │
│     a. 解冻持仓 (compute)                                                   │
│        positionMap.compute(sellerPosKey, (k, pos) -> {                      │
│            pos.frozen -= trade.quantity;                                    │
│            pos.quantity -= trade.quantity;                                  │
│            pos.updateTime = now;                                            │
│            return pos;                                                      │
│        });                                                                  │
│                                                                             │
│     b. 增加资金 (compute)                                                   │
│        sellAmount = trade.price * trade.quantity;                           │
│        accountMap.compute(sellAccountId, (k, acc) -> {                      │
│            acc.balance += sellAmount - sellCommission;                      │
│            acc.available += sellAmount - sellCommission;                    │
│            acc.updateTime = now;                                            │
│            return acc;                                                      │
│        });                                                                  │
│                                                                             │
│     c. 释放保证金（期货/期权平仓）                                           │
│        if (instrument.type != STOCK) {                                      │
│            marginReleased = calculateMarginReleased(instrument, trade);     │
│            accountMap.compute(sellAccountId, (k, acc) -> {                  │
│                acc.frozen -= marginReleased;                                │
│                acc.available += marginReleased;                             │
│                return acc;                                                  │
│            });                                                              │
│        }                                                                    │
│                                                                             │
│  7. 更新处理进度                                                            │
│     progressMap.put("trade_queue_index", queueIndex);                       │
│                                                                             │
│  8. 记录审计日志                                                            │
│     auditLog.record(AuditType.TRADE, trade);                                │
│                                                                             │
│  9. 推送WebSocket                                                           │
│     wsPushService.pushTradeReport(trade);                                   │
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
│     phase = phaseManager.getCurrentPhase();                                 │
│     if (!phase.allowCancel) {                                               │
│         throw new OrderRejectException("当前阶段不允许撤单");                │
│     }                                                                       │
│                                                                             │
│  2. 参数校验                                                                │
│     - orderId 存在性校验                                                    │
│     - 订单所有权校验 (accountId匹配)                                        │
│                                                                             │
│  3. 订单状态校验                                                            │
│     order = orderbookMap.get(orderId);                                      │
│     if (order.status == FILLED || order.status == CANCELLED) {              │
│         throw new OrderRejectException("订单不可撤销");                      │
│     }                                                                       │
│                                                                             │
│  4. 写入撤单请求                                                            │
│     orderQueue.write(OrderEvent { type: CANCEL_ORDER, orderId });           │
│                                                                             │
│  5. 返回结果                                                                │
│     return { status: CANCEL_REQUESTED };                                    │
│                                                                             │
│  阶段2: 撮合引擎处理 (MatchingEngine线程)                                    │
│                                                                             │
│  6. 读取撤单事件                                                            │
│     orderEvent = orderQueue.read();                                         │
│                                                                             │
│  7. 从订单簿移除                                                            │
│     order = orderBook.cancel(orderId);                                      │
│     if (order == null) return;  // 订单已成交或不存在                       │
│                                                                             │
│  8. 更新订单簿状态                                                          │
│     orderbookMap.compute(orderId, (k, o) -> {                               │
│         o.status = CANCELLED;                                               │
│         o.cancelReason = "USER_CANCEL";                                     │
│         o.updateTime = now;                                                 │
│         return o;                                                           │
│     });                                                                     │
│                                                                             │
│  9. 生成撤单事件                                                            │
│     cancelEvent = CancelEvent {                                             │
│         eventType: CANCEL,                                                  │
│         orderId,                                                            │
│         remainingQty: order.remainingQty,                                   │
│         price: order.price,                                                 │
│         side: order.side                                                    │
│     };                                                                      │
│     tradeQueue.write(cancelEvent);                                          │
│                                                                             │
│  阶段3: 清算服务处理 (ClearingWorker线程)                                    │
│                                                                             │
│  10. 读取撤单事件                                                           │
│      cancelEvent = tradeQueue.read();                                       │
│                                                                             │
│  11. 解冻资金/持仓 (compute)                                                │
│      if (cancelEvent.side == BUY) {                                         │
│          // 计算实际冻结金额（考虑部分成交）                                 │
│          frozenAmount = cancelEvent.price * cancelEvent.remainingQty;       │
│          accountMap.compute(accountId, (k, acc) -> {                        │
│              acc.frozen -= frozenAmount;                                    │
│              acc.available += frozenAmount;                                 │
│              acc.updateTime = now;                                          │
│              return acc;                                                    │
│          });                                                                │
│      } else { // SELL                                                       │
│          positionMap.compute(posKey, (k, pos) -> {                          │
│              pos.frozen -= cancelEvent.remainingQty;                        │
│              pos.available += cancelEvent.remainingQty;                     │
│              pos.updateTime = now;                                          │
│              return pos;                                                    │
│          });                                                                │
│      }                                                                      │
│                                                                             │
│  12. 记录审计日志                                                           │
│      auditLog.record(AuditType.ORDER, accountId, "CANCEL", orderId);        │
│                                                                             │
│  13. 推送WebSocket                                                          │
│      wsPushService.pushCancelResult(cancelEvent);                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.5 佣金计算

```java
public long calculateCommission(Instrument instrument, TradeEvent trade, Side side) {
    long amount = trade.price * trade.quantity;
    
    switch (instrument.getType()) {
        case STOCK:
            // A股：0.025%，最低5元
            long commission = amount * 25 / 100000;  // 万分之2.5
            return Math.max(commission, 500);  // 最低5元 = 500分
            
        case FUTURES:
            // 股指期货：0.0023%
            return amount * 23 / 1000000;  // 百万分之23
            
        case OPTION:
            // ETF期权：5元/张
            long lots = trade.quantity / instrument.getLotSize();
            return lots * 500;  // 5元 = 500分
            
        default:
            return 0;
    }
}
```

### 5.6 自成交检查

```java
public boolean checkSelfTrade(long accountId, long userId, String symbol, 
                               Side side, long price) {
    OrderBook book = orderBooks.get(symbol);
    if (book == null) return false;
    
    // 获取对手方订单
    List<Order> counterOrders;
    if (side == BUY) {
        counterOrders = book.getSellOrdersUpTo(price);
    } else {
        counterOrders = book.getBuyOrdersDownTo(price);
    }
    
    // 级别1: 同账户自成交（严格禁止）
    for (Order order : counterOrders) {
        if (order.getAccountId() == accountId) {
            return true;
        }
    }
    
    // 级别2: 同用户不同账户（可配置）
    if (stpConfig.isUserLevelEnabled()) {
        for (Order order : counterOrders) {
            if (order.getUserId() == userId) {
                switch (stpConfig.getStrategy()) {
                    case REJECT_NEW:
                        return true;
                    case CANCEL_OLD:
                        cancelOrder(order.getOrderId());
                        break;
                    case ALLOW_WITH_WARN:
                        log.warn("自成交警告: userId={}, 新订单={}, 旧订单={}", 
                            userId, "new", order.getOrderId());
                        break;
                }
            }
        }
    }
    
    return false;
}

// 撮合时的自成交检查
private boolean isSelfTrade(Order newOrder, Order existingOrder) {
    // 同账户
    if (newOrder.getAccountId() == existingOrder.getAccountId()) {
        return true;
    }
    
    // 同用户（如果启用）
    if (stpConfig.isUserLevelEnabled() && 
        newOrder.getUserId() == existingOrder.getUserId()) {
        return true;
    }
    
    return false;
}
```

---

## 6. 并发控制

### 6.1 Chronicle Map 并发策略

**核心原则**：使用 `compute()` 方法进行原子操作，Entry级别锁，避免全局锁。

```java
// 正确：原子更新账户
accountMap.compute(accountId, (key, account) -> {
    if (account.getAvailable() < amount) {
        throw new InsufficientFundsException();
    }
    account.setAvailable(account.getAvailable() - amount);
    account.setFrozen(account.getFrozen() + amount);
    return account;
});

// 错误：非原子操作，存在竞态条件
Account account = accountMap.get(accountId);
account.setAvailable(account.getAvailable() - amount);  // 竞态！
accountMap.put(accountId, account);
```

### 6.2 多账户操作顺序

**问题**：同时更新买方和卖方账户时，如何避免死锁？

**解决方案**：按账户ID排序加锁。

```java
public void transfer(long fromAccountId, long toAccountId, long amount) {
    // 按ID排序，确保加锁顺序一致
    long[] ids = { fromAccountId, toAccountId };
    Arrays.sort(ids);
    
    // 先锁小ID，再锁大ID
    accountMap.compute(ids[0], (k, acc1) -> {
        return accountMap.compute(ids[1], (k2, acc2) -> {
            // 执行转账逻辑
            if (ids[0] == fromAccountId) {
                acc1.setAvailable(acc1.getAvailable() - amount);
                acc2.setAvailable(acc2.getAvailable() + amount);
            } else {
                acc2.setAvailable(acc2.getAvailable() - amount);
                acc1.setAvailable(acc1.getAvailable() + amount);
            }
            return acc2;
        });
    });
}
```

### 6.3 读写分离策略

| 操作类型 | 线程 | 访问方式 | 说明 |
|---------|------|---------|------|
| 订单网关冻结 | HTTP线程 | compute写 | Entry级锁 |
| 撮合引擎读写 | MatchingEngine | 单线程写 | 无锁 |
| 清算服务更新 | ClearingWorker | compute写 | Entry级锁 |
| 行情推送读取 | WebSocket线程 | get读 | 无锁快照 |
| 风控检查读取 | HTTP线程 | get读 | 无锁快照 |

### 6.4 volatile 变量

| 变量 | 类型 | 用途 | 更新频率 |
|------|------|------|---------|
| currentPhase | TradingPhase | 当前交易阶段 | 阶段切换时 |
| throttled | boolean | 背压标志 | 动态调整 |
| marketOpen | boolean | 市场开关 | SOD/EOD |

```java
// PhaseManager线程更新
volatile TradingPhase currentPhase;

// HTTP线程读取（无锁）
TradingPhase phase = phaseManager.getCurrentPhase();
if (!phase.allowOrder()) {
    throw new OrderRejectException("当前阶段不允许下单");
}
```

### 6.5 并发安全保证

| 场景 | 保护机制 |
|------|---------|
| 账户资金更新 | compute原子操作 |
| 持仓数量更新 | compute原子操作 |
| 订单簿状态更新 | MatchingEngine单线程 |
| 成交去重 | putIfAbsent原子检查 |
| 幂等键检查 | putIfAbsent原子检查 |
| 处理进度更新 | 单线程ClearingWorker |

---

## 7. 持久化与恢复

### 7.1 Chronicle Map 持久化

**持久化机制**：内存映射文件，实时持久化，进程崩溃不丢数据。

**文件组织**：
```
data/
└── chronicle/
    └── 2026-05-21/           # 按日期目录
        ├── accounts.dat
        ├── positions.dat
        ├── orderbook.dat
        ├── progress.dat
        ├── processed.dat
        ├── idempotency.dat
        └── symbol_registry.dat
```

**启动加载**：
```java
public void loadChronicleMaps(LocalDate tradingDate) {
    String datePath = tradingDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
    String basePath = "data/chronicle/" + datePath + "/";
    
    // 检查目录是否存在
    Path dir = Paths.get(basePath);
    if (!Files.exists(dir)) {
        // SOD首次启动，创建新文件
        Files.createDirectories(dir);
        createNewChronicleMaps(basePath);
    } else {
        // 恢复：加载已有文件
        loadExistingChronicleMaps(basePath);
    }
}
```

### 7.2 Chronicle Queue 轮转

**轮转策略**：每日SOD创建新Queue文件，EOD关闭旧文件。

```
data/
└── chronicle/
    └── 2026-05-21/
        ├── order_queue/
        │   └── 19700101-1T0...cq4
        └── trade_queue/
            └── 19700101-1T0...cq4
```

**Queue配置**：
```java
SingleChronicleQueue orderQueue = SingleChronicleQueueBuilder
    .binary(basePath + "order_queue")
    .rollCycle(RollCycles.DAILY)  // 每日轮转
    .build();
```

### 7.3 处理进度恢复

**恢复机制**：从 `progress.dat` 读取上次处理位置，继续消费。

```java
public void recoverProgress() {
    // 读取上次处理索引
    long orderIndex = progressMap.get("order_queue_index");
    long tradeIndex = progressMap.get("trade_queue_index");
    
    // 创建Tailer，跳转到上次位置
    ExcerptTailer orderTailer = orderQueue.createTailer();
    if (orderIndex > 0) {
        orderTailer.moveToIndex(orderIndex);
    }
    
    ExcerptTailer tradeTailer = tradeQueue.createTailer();
    if (tradeIndex > 0) {
        tradeTailer.moveToIndex(tradeIndex);
    }
}
```

### 7.4 H2 数据库持久化

**持久化策略**：异步批量写入，不阻塞主链路。

```java
public class AsyncPersistenceWorker implements Runnable {
    private final BlockingQueue<PersistenceTask> taskQueue = new LinkedBlockingQueue<>();
    
    @Override
    public void run() {
        List<PersistenceTask> batch = new ArrayList<>(1000);
        while (true) {
            taskQueue.drainTo(batch, 1000);
            if (batch.isEmpty()) {
                Thread.sleep(100);  // 空闲等待
                continue;
            }
            
            // 批量写入H2
            h2Repository.batchInsert(batch);
            batch.clear();
        }
    }
    
    public void submit(PersistenceTask task) {
        taskQueue.offer(task);  // 非阻塞提交
    }
}
```

### 7.5 恢复流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              恢复流程                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 加载Chronicle Map                                                       │
│     - 检查日期目录是否存在                                                   │
│     - 存在：加载已有文件，恢复内存状态                                       │
│     - 不存在：创建新文件（SOD首次启动）                                      │
│                                                                             │
│  2. 恢复处理进度                                                            │
│     - 从progress.dat读取上次处理索引                                         │
│     - 创建Tailer，跳转到上次位置                                             │
│                                                                             │
│  3. 重放订单队列（如果需要）                                                 │
│     - 检查order_queue是否有未处理订单                                       │
│     - 从上次索引开始，重新处理                                               │
│                                                                             │
│  4. 重放成交队列（如果需要）                                                 │
│     - 检查trade_queue是否有未处理成交                                       │
│     - 从上次索引开始，重新清算                                               │
│     - 幂等检查确保不重复清算                                                 │
│                                                                             │
│  5. 加载内存数据                                                            │
│     - 从H2加载交易品种到内存                                                 │
│     - 从H2加载交易时间段配置                                                 │
│     - 从H2加载交易日历                                                       │
│                                                                             │
│  6. 启动工作线程                                                            │
│     - MatchingEngine                                                        │
│     - ClearingWorker                                                        │
│     - PhaseManager                                                          │
│     - RiskMonitor                                                           │
│     - AsyncPersistenceWorker                                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. 行情服务

### 8.1 行情数据来源

| 来源 | 用途 | 更新频率 |
|------|------|---------|
| 撮合引擎订单簿 | 五档行情（买卖盘） | 每3秒聚合 |
| 新浪财经 | 展示行情（K线、分时） | HTTP轮询 |

### 8.2 五档行情生成

```java
public class MarketDataGenerator {
    
    /**
     * 从订单簿生成五档行情
     * 每3秒由MatchingEngine调用
     */
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
        
        // 计算当前价（买一卖一中间价）
        if (!bidLevels.isEmpty() && !askLevels.isEmpty()) {
            snapshot.setCurrentPrice((bidLevels.get(0).getPrice() + askLevels.get(0).getPrice()) / 2);
        }
        
        return snapshot;
    }
}
```

### 8.3 行情推送

**WebSocket订阅**：
```json
{
  "type": "subscribe",
  "channel": "market_snapshot",
  "symbols": ["600000", "600001"]
}
```

**推送消息**：
```json
{
  "type": "market_snapshot",
  "data": {
    "symbol": "600000",
    "currentPrice": 1050,
    "bidPrices": [1049, 1048, 1047, 1046, 1045],
    "bidVolumes": [1000, 2000, 1500, 800, 3000],
    "askPrices": [1051, 1052, 1053, 1054, 1055],
    "askVolumes": [500, 1200, 2000, 800, 1500],
    "updateTime": 1716249600000
  }
}
```

### 8.4 新浪行情对接

**接口**：HTTP轮询，每3秒请求一次。

```java
public class SinaMarketDataService {
    private static final String SINA_URL = "https://hq.sinajs.cn/list=";
    
    public void fetchMarketData(List<String> symbols) {
        // 拼接请求URL
        String symbolList = String.join(",", symbols);
        String url = SINA_URL + symbolList;
        
        // HTTP请求
        String response = httpClient.get(url);
        
        // 解析响应
        parseAndCache(response);
    }
    
    private void parseAndCache(String response) {
        // 格式：var hq_str_sh600000="浦发银行,10.50,10.40,..."
        String[] lines = response.split(";");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            
            String[] parts = line.split("=\"");
            String symbol = parts[0].replace("var hq_str_", "");
            String[] data = parts[1].replace("\"", "").split(",");
            
            // 解析字段：名称、今开、昨收、当前价、最高、最低...
            MarketDataSnapshot snapshot = new MarketDataSnapshot();
            snapshot.setSymbol(symbol);
            snapshot.setName(data[0]);
            snapshot.setOpenPrice(parsePrice(data[1]));
            snapshot.setPreClose(parsePrice(data[2]));
            snapshot.setCurrentPrice(parsePrice(data[3]));
            snapshot.setHighPrice(parsePrice(data[4]));
            snapshot.setLowPrice(parsePrice(data[5]));
            
            // 缓存到内存
            marketDataCache.put(symbol, snapshot);
        }
    }
}
```

---

## 9. API设计

### 9.1 REST API 概览

| 模块 | 接口 | 方法 | 说明 |
|------|------|------|------|
| 认证 | /api/auth/login | POST | 用户登录 |
| 认证 | /api/auth/logout | POST | 用户登出 |
| 认证 | /api/auth/refresh | POST | 刷新Token |
| 账户 | /api/account/info | GET | 账户信息 |
| 账户 | /api/account/positions | GET | 持仓列表 |
| 订单 | /api/orders | POST | 下单 |
| 订单 | /api/orders/{orderId} | DELETE | 撤单 |
| 订单 | /api/orders/active | GET | 当前委托 |
| 历史 | /api/trades | GET | 历史成交 |
| 历史 | /api/account/cashflow | GET | 资金流水 |
| 行情 | /api/market/symbols | GET | 股票列表 |
| 行情 | /api/market/quote/{symbol} | GET | 实时行情 |
| 行情 | /api/market/kline | GET | K线数据 |

### 9.2 认证接口

**登录**：
```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "user001",
  "password": "password123"
}

Response 200:
{
  "code": 0,
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiIs...",
    "refreshToken": "eyJhbGciOiJSUzI1NiIs...",
    "expiresIn": 7200,
    "tokenType": "Bearer"
  }
}
```

**刷新Token**：
```http
POST /api/auth/refresh
Content-Type: application/json

{
  "refreshToken": "eyJhbGciOiJSUzI1NiIs..."
}

Response 200:
{
  "code": 0,
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiIs...",
    "expiresIn": 7200
  }
}
```

### 9.3 订单接口

**下单**：
```http
POST /api/orders
Authorization: Bearer {token}
Content-Type: application/json

{
  "symbol": "600000",
  "side": "BUY",
  "orderType": "LIMIT",
  "price": 1050,
  "quantity": 100,
  "idempotencyKey": "uuid-xxx-xxx"  // 可选，幂等键
}

Response 200:
{
  "code": 0,
  "data": {
    "orderId": 123456789012345,
    "status": "PENDING",
    "symbol": "600000",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 1050,
    "quantity": 100,
    "createTime": 1716249600000
  }
}
```

**撤单**：
```http
DELETE /api/orders/{orderId}
Authorization: Bearer {token}

Response 200:
{
  "code": 0,
  "data": {
    "orderId": 123456789012345,
    "status": "CANCEL_REQUESTED"
  }
}
```

### 9.4 WebSocket API

**连接**：
```
ws://host:port/ws?token={jwt_token}
```

**订阅消息**：
```json
{
  "type": "subscribe",
  "channel": "market_snapshot",
  "symbols": ["600000", "600001"]
}
```

**推送消息类型**：
| 类型 | 说明 |
|------|------|
| market_snapshot | 行情快照 |
| kline | K线数据 |
| orderbook | 订单簿Level2 |
| order_update | 订单状态更新 |
| trade_update | 成交回报 |
| account_update | 账户资金变动 |
| position_update | 持仓变动 |
| phase_update | 交易阶段变更 |

**心跳**：每30秒发送 `ping`，服务端回复 `pong`。

---

## 10. 前端设计

### 10.1 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | React 18 + TypeScript |
| 构建 | Vite |
| UI库 | Ant Design 5.x |
| 图表 | lightweight-charts（TradingView） |
| 状态管理 | Zustand |
| HTTP客户端 | Axios |
| WebSocket | 原生WebSocket |

### 10.2 页面布局

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Header: Logo | 用户信息 | 退出登录                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────┬────────────────────────────────┐   │
│  │                                     │                                │   │
│  │          K线图区域                   │        下单面板                │   │
│  │     (lightweight-charts)            │                                │   │
│  │                                     │   买入/卖出 Tab                 │   │
│  │                                     │   价格输入                      │   │
│  │                                     │   数量输入                      │   │
│  │                                     │   下单按钮                      │   │
│  │                                     │                                │   │
│  ├─────────────────────────────────────┼────────────────────────────────┤   │
│  │                                     │                                │   │
│  │          五档行情                    │        持仓/委托/成交           │   │
│  │                                     │        Tab切换                  │   │
│  │   买5-买1  |  卖1-卖5               │                                │   │
│  │                                     │   列表展示                      │   │
│  │                                     │                                │   │
│  └─────────────────────────────────────┴────────────────────────────────┘   │
│                                                                             │
│  Footer: 系统状态 | 连接状态 | 时间                                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 10.3 组件结构

```
src/
├── components/
│   ├── Header/
│   │   └── Header.tsx
│   ├── KLineChart/
│   │   └── KLineChart.tsx      # lightweight-charts封装
│   ├── OrderBook/
│   │   └── OrderBook.tsx       # 五档行情
│   ├── OrderPanel/
│   │   └── OrderPanel.tsx     # 下单面板
│   ├── PositionList/
│   │   └── PositionList.tsx   # 持仓列表
│   ├── OrderList/
│   │   └── OrderList.tsx       # 委托列表
│   └── TradeList/
│       └── TradeList.tsx       # 成交列表
├── stores/
│   ├── authStore.ts            # 认证状态
│   ├── accountStore.ts         # 账户状态
│   ├── marketStore.ts          # 行情状态
│   └── orderStore.ts           # 订单状态
├── services/
│   ├── api.ts                  # HTTP API
│   └── websocket.ts            # WebSocket服务
└── App.tsx
```

### 10.4 状态管理

```typescript
// stores/accountStore.ts
import { create } from 'zustand';

interface AccountState {
  balance: number;
  available: number;
  frozen: number;
  positions: Position[];
  updateAccount: (data: AccountData) => void;
  updatePosition: (symbol: string, position: Position) => void;
}

export const useAccountStore = create<AccountState>((set) => ({
  balance: 0,
  available: 0,
  frozen: 0,
  positions: [],
  
  updateAccount: (data) => set({
    balance: data.balance,
    available: data.available,
    frozen: data.frozen,
  }),
  
  updatePosition: (symbol, position) => set((state) => {
    const positions = state.positions.map(p => 
      p.symbol === symbol ? position : p
    );
    if (!positions.find(p => p.symbol === symbol)) {
      positions.push(position);
    }
    return { positions };
  }),
}));
```

---

## 11. 性能分析

### 11.1 延迟分解

| 阶段 | 操作 | 预期延迟 | 说明 |
|------|------|---------|------|
| HTTP接收 | Tomcat处理 | 0.1-0.5ms | 取决于请求大小 |
| 订单网关 | 风控+冻结 | 0.5-2ms | Chronicle Map compute |
| 队列写入 | Chronicle Queue | 0.1-0.5ms | 内存映射写入 |
| 撮合引擎 | 订单撮合 | 1-5ms | 取决于订单簿深度 |
| 队列写入 | 成交队列 | 0.1-0.5ms | 内存映射写入 |
| 清算服务 | 成交清算 | 1-3ms | Chronicle Map compute |
| WebSocket推送 | 成交回报 | 0.1-0.5ms | Netty推送 |
| **总计** | **端到端** | **3-12ms** | P99 < 15ms |

### 11.2 吞吐量分析

**瓶颈分析**：

| 组件 | 理论吞吐 | 瓶颈因素 |
|------|---------|---------|
| HTTP接收 | 200K+ TPS | Tomcat线程数 |
| 订单网关 | 100K+ TPS | Chronicle Map锁竞争 |
| 撮合引擎 | 50K+ TPS | 单线程，订单簿深度 |
| 清算服务 | 10K TPS | 单线程，compute开销 |
| H2持久化 | 5K TPS | 批量写入 |

**系统吞吐**：由清算服务瓶颈决定，约 10K TPS。

### 11.3 内存使用

| 组件 | 内存占用 | 说明 |
|------|---------|------|
| Chronicle Map | 2-4GB | 账户/持仓/订单簿 |
| Chronicle Queue | 1-2GB | 订单/成交队列 |
| Java Heap | 4-6GB | 对象/缓存 |
| JVM Native | 2-4GB | 线程栈/GC |
| **总计** | **10-16GB** | 32GB内存充足 |

### 11.4 GC优化

**ZGC配置**：
```bash
java -Xms10g -Xmx10g \
     -XX:+UseZGC \
     -XX:+ZGenerational \
     -XX:ConcGCThreads=2 \
     -XX:ParallelGCThreads=4 \
     -jar hft-trading.jar
```

**预期效果**：
- GC暂停时间 < 1ms
- GC频率：每秒1-2次
- 内存回收效率 > 99%

---

## 12. 部署配置

### 12.1 服务器配置

| 配置项 | 推荐值 | 说明 |
|--------|--------|------|
| CPU | 8核 | 阿里云 ecs.g6.xlarge |
| 内存 | 32GB | 充足内存避免GC压力 |
| 磁盘 | 1TB SSD | Chronicle文件 + H2数据库 |
| 网络 | 1Gbps | 内网带宽 |
| 操作系统 | CentOS 7.9 / Ubuntu 20.04 | Linux内核优化 |

### 12.2 JVM参数

```bash
java -Xms10g -Xmx10g \
     -XX:+UseZGC \
     -XX:+ZGenerational \
     -XX:ConcGCThreads=2 \
     -XX:ParallelGCThreads=4 \
     -XX:+UseLargePages \
     -XX:LargePageSizeInBytes=2m \
     -Djava.net.preferIPv4Stack=true \
     -Dfile.encoding=UTF-8 \
     -jar hft-trading.jar
```

### 12.3 Linux内核优化

```bash
# /etc/sysctl.conf

# 网络优化
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.ipv4.tcp_rmem = 4096 87380 16777216
net.ipv4.tcp_wmem = 4096 65536 16777216
net.core.netdev_max_backlog = 5000

# 文件描述符
fs.file-max = 1000000

# 内存映射
vm.max_map_count = 262144

# 应用
sysctl -p
```

### 12.4 文件描述符限制

```bash
# /etc/security/limits.conf
* soft nofile 65536
* hard nofile 65536
```

---

## 13. 监控与运维

### 13.1 Prometheus指标

| 指标 | 说明 |
|------|------|
| hft_orders_total | 订单总数 |
| hft_trades_total | 成交总数 |
| hft_order_latency_p99 | 订单延迟P99 |
| hft_trade_latency_p99 | 成交延迟P99 |
| hft_matching_engine_lag | 撮合引擎延迟 |
| hft_clearing_worker_lag | 清算服务延迟 |
| hft_websocket_connections | WebSocket连接数 |
| hft_chronicle_map_size | Chronicle Map大小 |
| hft_gc_pause_seconds | GC暂停时间 |

### 13.2 Grafana仪表盘

**系统概览仪表盘**：
- 订单TPS / 成交TPS
- 延迟P50/P95/P99
- WebSocket连接数
- JVM内存使用
- GC暂停时间

**业务监控仪表盘**：
- 各品种成交分布
- 用户活跃度
- 账户资金分布
- 持仓分布

### 13.3 日志规范

**日志级别**：
| 级别 | 用途 |
|------|------|
| ERROR | 系统错误，需要告警 |
| WARN | 异常情况，需要关注 |
| INFO | 关键业务事件（订单、成交） |
| DEBUG | 调试信息（生产环境关闭） |

**日志格式**：
```
[timestamp] [level] [traceId] [thread] [class] - message
```

**示例**：
```
2026-05-21 09:30:00.123 INFO [abc123] [http-1] OrderGateway - Order created: orderId=123456, symbol=600000, side=BUY, price=1050, qty=100
```

### 13.4 告警规则

| 告警 | 条件 | 级别 |
|------|------|------|
| 订单延迟过高 | P99 > 20ms | WARNING |
| 订单延迟过高 | P99 > 50ms | CRITICAL |
| 撮合引擎延迟 | lag > 1000 | WARNING |
| 清算服务延迟 | lag > 1000 | WARNING |
| GC暂停过长 | > 10ms | WARNING |
| WebSocket连接数 | < 10（交易时段） | WARNING |
| 磁盘空间 | > 80% | WARNING |

---

## 14. 业务规则

### 14.1 交易品种

| 品种 | 代码规则 | 交易所 |
|------|---------|--------|
| A股主板 | 60xxxx | 上交所 |
| A股主板 | 00xxxx | 深交所 |
| 创业板 | 30xxxx | 深交所 |
| 科创板 | 688xxx | 上交所 |
| IF股指期货 | IF + YYMM | 中金所 |
| 50ETF期权 | 510050 + 合约编码 | 上交所 |

### 14.2 交易时段

**A股**：
| 时段 | 时间 | 允许操作 |
|------|------|---------|
| 开盘集合竞价 | 09:15-09:25 | 下单、撤单（09:15-09:20）、不可撤（09:20-09:25） |
| 连续竞价 | 09:30-11:30 | 下单、撤单、撮合 |
| 午休 | 11:30-13:00 | 无操作 |
| 连续竞价 | 13:00-14:57 | 下单、撤单、撮合 |
| 收盘集合竞价 | 14:57-15:00 | 下单、不可撤单 |

**股指期货**：
| 时段 | 时间 | 允许操作 |
|------|------|---------|
| 开盘集合竞价 | 09:10-09:15 | 下单、撤单 |
| 连续竞价 | 09:15-11:30 | 下单、撤单、撮合 |
| 午休 | 11:30-13:00 | 无操作 |
| 连续竞价 | 13:00-15:15 | 下单、撤单、撮合 |

### 14.3 涨跌停规则

| 品种 | 涨跌幅限制 |
|------|-----------|
| A股主板 | ±10% |
| 创业板 | ±20% |
| 科创板 | ±20% |
| 股指期货 | ±10% |
| ETF期权 | ±20%（合约） |

### 14.4 T+1规则

| 品种 | 交收规则 |
|------|---------|
| A股 | T+1，当日买入不可卖出 |
| 股指期货 | T+0，当日可买卖 |
| ETF期权 | T+0，当日可买卖 |

---

## 15. SOD与EOD流程

### 15.1 SOD流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              SOD流程                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  时间：每个交易日 08:30                                                     │
│                                                                             │
│  步骤：                                                                     │
│                                                                             │
│  1. 创建日期目录                                                            │
│     datePath = "data/chronicle/" + tradingDate + "/"                       │
│     Files.createDirectories(datePath)                                       │
│                                                                             │
│  2. 创建Chronicle Map文件                                                   │
│     - accounts.dat（从H2快照加载初始值）                                     │
│     - positions.dat（从H2快照加载初始值）                                    │
│     - orderbook.dat（清空，新交易日）                                       │
│     - progress.dat（重置为0）                                               │
│     - processed.dat（清空）                                                 │
│     - idempotency.dat（清空）                                               │
│                                                                             │
│  3. 创建Chronicle Queue文件                                                 │
│     - order_queue/                                                          │
│     - trade_queue/                                                          │
│                                                                             │
│  4. 加载H2快照                                                              │
│     - 从account_snapshots加载昨日账户状态                                    │
│     - 从position_snapshots加载昨日持仓状态                                   │
│                                                                             │
│  5. 加载交易品种                                                             │
│     - 从instruments表加载到内存                                              │
│     - 更新涨跌停价格（根据昨收计算）                                         │
│                                                                             │
│  6. 启动工作线程                                                             │
│     - MatchingEngine                                                        │
│     - ClearingWorker                                                        │
│     - PhaseManager                                                          │
│     - RiskMonitor                                                           │
│     - AsyncPersistenceWorker                                                │
│                                                                             │
│  7. 等待开盘                                                                │
│     - PhaseManager监控时间，09:15切换到开盘集合竞价                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 15.2 EOD流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              EOD流程                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  时间：每个交易日 15:30                                                     │
│                                                                             │
│  步骤：                                                                     │
│                                                                             │
│  1. 等待队列清空                                                            │
│     - 等待order_queue处理完毕                                               │
│     - 等待trade_queue处理完毕                                               │
│     - 等待AsyncPersistenceWorker写入完毕                                    │
│                                                                             │
│  2. T+1解锁                                                                 │
│     - 遍历positions.dat                                                     │
│     - 将t1Locked转移到available                                             │
│     positionMap.compute(key, (k, pos) -> {                                   │
│         pos.available += pos.t1Locked;                                      │
│         pos.t1Locked = 0;                                                   │
│         return pos;                                                         │
│     });                                                                     │
│                                                                             │
│  3. 生成账户快照                                                             │
│     - 遍历accounts.dat，写入account_snapshots表                             │
│                                                                             │
│  4. 生成持仓快照                                                             │
│     - 遍历positions.dat，写入position_snapshots表                           │
│                                                                             │
│  5. 关闭Chronicle文件                                                        │
│     - 关闭所有Chronicle Map                                                  │
│     - 关闭所有Chronicle Queue                                                │
│                                                                             │
│  6. 停止工作线程                                                             │
│     - 停止MatchingEngine                                                    │
│     - 停止ClearingWorker                                                     │
│     - 停止PhaseManager                                                      │
│     - 停止RiskMonitor                                                       │
│                                                                             │
│  7. 归档日志                                                                 │
│     - 压缩当日日志文件                                                       │
│     - 移动到归档目录                                                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 16. 交易时间段配置

### 16.1 配置表结构

```sql
CREATE TABLE trading_session_config (
    config_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    instrument_type VARCHAR(20),      -- STOCK / FUTURES / OPTION
    phase VARCHAR(30),               -- CALL_AUCTION_OPEN / CONTINUOUS / CALL_AUCTION_CLOSE
    start_time VARCHAR(10),          -- HH:mm
    end_time VARCHAR(10),            -- HH:mm
    allow_order BOOLEAN,             -- 是否允许下单
    allow_cancel BOOLEAN,            -- 是否允许撤单
    allow_match BOOLEAN,             -- 是否允许撮合
    priority INT,                    -- 优先级（用于排序）
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
```

### 16.2 默认配置数据

**A股配置**：
```sql
INSERT INTO trading_session_config VALUES
(1, 'STOCK', 'CALL_AUCTION_OPEN', '09:15', '09:25', true, true, false, 1, NOW(), NOW()),
(2, 'STOCK', 'CALL_AUCTION_OPEN_NO_CANCEL', '09:20', '09:25', true, false, false, 2, NOW(), NOW()),
(3, 'STOCK', 'CONTINUOUS', '09:30', '11:30', true, true, true, 3, NOW(), NOW()),
(4, 'STOCK', 'CONTINUOUS', '13:00', '14:57', true, true, true, 4, NOW(), NOW()),
(5, 'STOCK', 'CALL_AUCTION_CLOSE', '14:57', '15:00', true, false, false, 5, NOW(), NOW());
```

**股指期货配置**：
```sql
INSERT INTO trading_session_config VALUES
(10, 'FUTURES', 'CALL_AUCTION_OPEN', '09:10', '09:15', true, true, false, 1, NOW(), NOW()),
(11, 'FUTURES', 'CONTINUOUS', '09:15', '11:30', true, true, true, 2, NOW(), NOW()),
(12, 'FUTURES', 'CONTINUOUS', '13:00', '15:15', true, true, true, 3, NOW(), NOW());
```

### 16.3 阶段管理器

```java
public class PhaseManager implements Runnable {
    private volatile TradingPhase currentPhase;
    private final List<TradingSessionConfig> configs;
    
    @Override
    public void run() {
        while (running) {
            LocalTime now = LocalTime.now();
            TradingPhase newPhase = determinePhase(now);
            
            if (newPhase != currentPhase) {
                currentPhase = newPhase;
                notifyPhaseChange(currentPhase);
            }
            
            Thread.sleep(1000);  // 每秒检查一次
        }
    }
    
    private TradingPhase determinePhase(LocalTime time) {
        for (TradingSessionConfig config : configs) {
            LocalTime start = LocalTime.parse(config.getStartTime());
            LocalTime end = LocalTime.parse(config.getEndTime());
            
            if (!time.isBefore(start) && time.isBefore(end)) {
                return new TradingPhase(
                    config.getPhase(),
                    config.isAllowOrder(),
                    config.isAllowCancel(),
                    config.isAllowMatch()
                );
            }
        }
        
        return TradingPhase.CLOSED;
    }
}
```

---

## 17. 品种数据管理

### 17.1 品种信息加载

```java
public class InstrumentLoader {
    
    public void loadInstruments() {
        List<Instrument> instruments = h2Repository.queryInstruments();
        
        for (Instrument inst : instruments) {
            // 计算涨跌停价格
            if (inst.getPreClose() > 0) {
                inst.setLimitUp(calculateLimitUp(inst));
                inst.setLimitDown(calculateLimitDown(inst));
            }
            
            // 放入内存
            instrumentMap.put(inst.getSymbol(), inst);
            
            // 注册SymbolCode
            int code = symbolRegistry.getOrCreateCode(inst.getSymbol());
            inst.setSymbolCode(code);
        }
    }
    
    private long calculateLimitUp(Instrument inst) {
        // 涨停价 = 昨收 × (1 + 涨幅限制)
        // 向上取整到最小变动价位
        long limitUp = inst.getPreClose() * (10000 + inst.getLimitUpRate()) / 10000;
        return roundToPriceUnit(limitUp, inst.getMinPriceUnit());
    }
    
    private long calculateLimitDown(Instrument inst) {
        // 跌停价 = 昨收 × (1 - 跌幅限制)
        // 向下取整到最小变动价位
        long limitDown = inst.getPreClose() * (10000 - inst.getLimitDownRate()) / 10000;
        return roundToPriceUnit(limitDown, inst.getMinPriceUnit());
    }
}
```

### 17.2 品种信息更新

**更新时机**：
- 每日SOD加载最新品种信息
- 盘中新增品种实时更新
- 品种状态变更实时生效

```java
public void updateInstrument(Instrument newInst) {
    Instrument oldInst = instrumentMap.get(newInst.getSymbol());
    
    // 更新内存
    instrumentMap.put(newInst.getSymbol(), newInst);
    
    // 更新H2
    h2Repository.updateInstrument(newInst);
    
    // 记录审计日志
    auditLog.record(AuditType.INSTRUMENT, newInst.getSymbol(), "UPDATE", 
        toJson(oldInst), toJson(newInst));
}
```

---

## 18. 期货期权规格

### 18.1 股指期货规格

| 合约 | IF | IC | IH |
|------|-----|-----|-----|
| 名称 | 沪深300 | 中证500 | 上证50 |
| 合约乘数 | 300 | 200 | 300 |
| 最小变动价位 | 0.2点 | 0.2点 | 0.2点 |
| 交易单位 | 手 | 手 | 手 |
| 涨跌停限制 | ±10% | ±10% | ±10% |
| 保证金比例 | 12% | 12% | 12% |
| 交割方式 | 现金交割 | 现金交割 | 现金交割 |

### 18.2 ETF期权规格

| 项目 | 50ETF期权 |
|------|----------|
| 标的 | 50ETF (510050) |
| 合约单位 | 10000份/张 |
| 最小变动价位 | 0.0001元 |
| 到期月份 | 当月、下月、下季、隔季 |
| 行权方式 | 欧式（到期日行权） |
| 涨跌停限制 | ±20%（合约） |
| 保证金（卖方） | 见22.3节 |

### 18.3 期货持仓计算

```java
public class FuturesPositionCalculator {
    
    /**
     * 计算期货持仓市值
     */
    public long calculateMarketValue(Position position, long currentPrice) {
        // 市值 = 持仓量 × 合约乘数 × 当前价
        Instrument inst = instrumentMap.get(position.getSymbol());
        return Math.abs(position.getQuantity()) * inst.getContractMultiplier() * currentPrice;
    }
    
    /**
     * 计算期货保证金
     */
    public long calculateMargin(Position position, long currentPrice) {
        Instrument inst = instrumentMap.get(position.getSymbol());
        long marketValue = Math.abs(position.getQuantity()) * 
            inst.getContractMultiplier() * currentPrice;
        return marketValue * inst.getMarginRatio() / 10000;
    }
}
```

---

## 19. 限流策略

### 19.1 限流配置

| 限流维度 | 限制 | 说明 |
|---------|------|------|
| 单账户下单频率 | 100 TPS | 每秒最多100笔订单 |
| 单账户撤单频率 | 100 TPS | 每秒最多100笔撤单 |
| 单账户查询频率 | 1000 QPS | 每秒最多1000次查询 |
| 全系统下单频率 | 10000 TPS | 系统总下单限制 |
| API Key频率 | 50 TPS | 单API Key限制 |

### 19.2 令牌桶实现

```java
public class RateLimiter {
    private final long capacity;
    private final long refillRate;  // tokens per second
    private final AtomicLong tokens;
    private final AtomicLong lastRefillTime;
    
    public RateLimiter(long capacity, long refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = new AtomicLong(capacity);
        this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
    }
    
    public boolean tryAcquire() {
        refill();
        while (true) {
            long current = tokens.get();
            if (current <= 0) {
                return false;
            }
            if (tokens.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }
    
    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime.get();
        if (elapsed < 1000) {
            return;
        }
        
        long newTokens = elapsed * refillRate / 1000;
        if (newTokens > 0) {
            tokens.updateAndGet(current -> Math.min(capacity, current + newTokens));
            lastRefillTime.set(now);
        }
    }
}
```

### 19.3 限流应用

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    
    private final RateLimiter orderRateLimiter = new RateLimiter(100, 100);
    
    @PostMapping
    public ResponseEntity<OrderResult> createOrder(@RequestBody OrderRequest request) {
        // 账户级限流
        String rateLimitKey = "order:" + request.getAccountId();
        if (!accountRateLimiters.get(rateLimitKey).tryAcquire()) {
            throw new RateLimitExceededException("下单频率超限");
        }
        
        // 处理订单...
    }
}
```

---

## 20. Chronicle Queue轮转策略

### 20.1 轮转配置

```java
SingleChronicleQueue queue = SingleChronicleQueueBuilder
    .binary(basePath)
    .rollCycle(RollCycles.DAILY)    // 每日轮转
    .timeoutMS(5000)                 // 超时5秒
    .build();
```

### 20.2 轮转时机

| 时机 | 操作 |
|------|------|
| SOD | 创建当日新Queue文件 |
| EOD | 关闭当日Queue文件 |
| 运行中 | 自动按RollCycle轮转 |

### 20.3 历史数据管理

```java
public class QueueArchiveService {
    
    /**
     * 归档历史Queue文件
     * 保留最近30天，超过30天的压缩归档
     */
    public void archiveOldQueues() {
        LocalDate cutoffDate = LocalDate.now().minusDays(30);
        
        // 遍历chronicle目录
        Files.list(Paths.get("data/chronicle/"))
            .filter(path -> isDateDirectory(path))
            .filter(path -> parseDate(path).isBefore(cutoffDate))
            .forEach(this::archiveDirectory);
    }
    
    private void archiveDirectory(Path dir) {
        // 压缩目录
        Path zipFile = Paths.get("data/archive/" + dir.getFileName() + ".zip");
        ZipUtils.compress(dir, zipFile);
        
        // 删除原目录
        FileUtils.deleteDirectory(dir);
    }
}
```

---

## 21. T+1追踪机制

### 21.1 T+1字段说明

持仓表中的 `t1Locked` 字段记录当日买入数量，EOD解锁后变为可用。

**字段关系**：
```
|quantity| = available + frozen + t1Locked

其中：
- quantity: 总持仓量
- available: T+0可卖数量
- frozen: 委托卖出冻结数量
- t1Locked: 当日买入锁定数量（A股）
```

### 21.2 买入时T+1锁定

```java
// 清算流程中，A股买入增加t1Locked
if (instrument.getType() == InstrumentType.STOCK) {
    position.setT1Locked(position.getT1Locked() + trade.getQuantity());
} else {
    // 期货期权T+0
    position.setAvailable(position.getAvailable() + trade.getQuantity());
}
```

### 21.3 EOD解锁

```java
public void unlockT1Positions() {
    positionMap.forEach((key, position) -> {
        if (position.getT1Locked() > 0) {
            positionMap.compute(key, (k, pos) -> {
                pos.setAvailable(pos.getAvailable() + pos.getT1Locked());
                pos.setT1Locked(0);
                pos.setUpdateTime(System.currentTimeMillis());
                return pos;
            });
        }
    });
}
```

### 21.4 卖出校验

```java
// 下单时检查可用数量（不含t1Locked）
public void checkAvailablePosition(long accountId, String symbol, long quantity) {
    Position position = positionMap.get(accountId, symbol);
    if (position == null || position.getAvailable() < quantity) {
        throw new InsufficientPositionException("可用持仓不足");
    }
}
```

---

## 22. 保证金计算公式

### 22.1 期货保证金

**开仓保证金**：
```
保证金 = 持仓量 × 合约乘数 × 价格 × 保证金比例

示例（IF合约）：
- 持仓量：1手
- 合约乘数：300
- 价格：4000点
- 保证金比例：12%

保证金 = 1 × 300 × 4000 × 12% = 144,000元
```

**维持保证金**：
```
维持保证金 = 持仓量 × 合约乘数 × 价格 × 维持保证金比例

通常维持保证金比例 < 开仓保证金比例（如10%）
```

### 22.2 期权保证金

**买方**：无需保证金，只需支付权利金。

**卖方保证金公式**：
```
保证金 = Max(权利金 + Max(M1 × 标的价格 - M2 × 虚值额, M3 × 标的价格), 最低保证金)

其中：
- M1: 保证金系数1（如12%）
- M2: 虚值额系数（如10%）
- M3: 保证金系数2（如10%）
- 虚值额 = Max(行权价 - 标的价格, 0)（认购）
- 虚值额 = Max(标的价格 - 行权价, 0)（认沽）
- 最低保证金：如5000元/张
```

**实现代码**：
```java
public long calculateOptionMargin(Position position, Instrument option, long optionPrice) {
    if (position.getQuantity() > 0) {
        // 买方无需保证金
        return 0;
    }
    
    // 卖方保证金
    long underlyingPrice = getUnderlyingPrice(option.getUnderlying());
    long strikePrice = option.getStrikePrice();
    
    // 计算虚值额
    long outOfTheMoneyAmount = 0;
    if (option.getOptionType() == OptionType.CALL) {
        outOfTheMoneyAmount = Math.max(strikePrice - underlyingPrice, 0);
    } else {
        outOfTheMoneyAmount = Math.max(underlyingPrice - strikePrice, 0);
    }
    
    // 合约单位
    long contractUnit = option.getContractMultiplier();
    
    // 保证金公式
    long premium = optionPrice * contractUnit;
    long m1 = (long)(underlyingPrice * contractUnit * 0.12);
    long m2 = (long)(outOfTheMoneyAmount * contractUnit * 0.10);
    long m3 = (long)(underlyingPrice * contractUnit * 0.10);
    
    long margin = Math.max(premium + Math.max(m1 - m2, m3), 5000 * contractUnit / 10000);
    
    return margin;
}
```

### 22.3 保证金占用

**账户资金分配**：
```
balance = available + frozen

其中：
- frozen包含：委托冻结资金 + 期货期权保证金占用
- available: 可用于开新仓的资金
```

---

## 23. 自成交预防算法

### 23.1 自成交级别

| 级别 | 检测范围 | 默认策略 |
|------|---------|---------|
| SAME_ACCOUNT | 同一账户 | 拒绝新订单 |
| SAME_USER | 同一用户的不同账户 | 可配置 |

### 23.2 下单时检查

```java
public boolean checkSelfTrade(long accountId, long userId, String symbol, 
                               Side side, long price) {
    OrderBook book = orderBooks.get(symbol);
    if (book == null) return false;
    
    // 获取对手方订单
    List<Order> counterOrders;
    if (side == BUY) {
        counterOrders = book.getSellOrdersUpTo(price);
    } else {
        counterOrders = book.getBuyOrdersDownTo(price);
    }
    
    // 级别1: 同账户自成交（严格禁止）
    for (Order order : counterOrders) {
        if (order.getAccountId() == accountId) {
            return true;  // 存在自成交风险
        }
    }
    
    // 级别2: 同用户不同账户
    if (stpConfig.isUserLevelEnabled()) {
        for (Order order : counterOrders) {
            if (order.getUserId() == userId) {
                switch (stpConfig.getStrategy()) {
                    case REJECT_NEW:
                        return true;
                    case CANCEL_OLD:
                        cancelOrder(order.getOrderId());
                        break;
                    case ALLOW_WITH_WARN:
                        log.warn("自成交警告: userId={}, 新订单将匹配旧订单={}", 
                            userId, order.getOrderId());
                        break;
                }
            }
        }
    }
    
    return false;
}
```

### 23.3 撮合时检查

```java
private boolean isSelfTrade(Order newOrder, Order existingOrder) {
    // 同账户
    if (newOrder.getAccountId() == existingOrder.getAccountId()) {
        return true;
    }
    
    // 同用户（如果启用）
    if (stpConfig.isUserLevelEnabled() && 
        newOrder.getUserId() == existingOrder.getUserId()) {
        return true;
    }
    
    return false;
}

// 撮合循环中跳过自成交订单
while (order.getRemainingQty() > 0) {
    Order counterOrder = book.getBestCounterOrder(order);
    if (counterOrder == null) break;
    
    if (isSelfTrade(order, counterOrder)) {
        book.skipOrder(counterOrder);  // 跳过，不撮合
        continue;
    }
    
    // 正常撮合
    Trade trade = createTrade(order, counterOrder);
    trades.add(trade);
}
```

---

## 24. 集合竞价算法

### 24.1 集合竞价原理

**成交价确定原则**：
1. 可实现最大成交量
2. 高于该价的买入申报与低于该价的卖出申报全部成交
3. 与该价相同的买方或卖方至少有一方全部成交

### 24.2 算法实现

```java
public class CallAuctionMatcher {
    
    /**
     * 集合竞价撮合
     * @param buyOrders 买入订单集合（按价格降序）
     * @param sellOrders 卖出订单集合（按价格升序）
     * @return 成交价格和成交量
     */
    public CallAuctionResult match(List<Order> buyOrders, List<Order> sellOrders) {
        if (buyOrders.isEmpty() || sellOrders.isEmpty()) {
            return CallAuctionResult.empty();
        }
        
        // 累计买卖量
        List<PriceLevel> buyLevels = aggregateByPrice(buyOrders, true);
        List<PriceLevel> sellLevels = aggregateByPrice(sellOrders, false);
        
        // 计算各价格档位的成交量
        List<MatchLevel> matchLevels = new ArrayList<>();
        long cumulativeBuy = 0;
        long cumulativeSell = 0;
        
        int buyIdx = 0;
        int sellIdx = 0;
        
        while (buyIdx < buyLevels.size() && sellIdx < sellLevels.size()) {
            PriceLevel buyLevel = buyLevels.get(buyIdx);
            PriceLevel sellLevel = sellLevels.get(sellIdx);
            
            if (buyLevel.price >= sellLevel.price) {
                // 可以成交的价格区间
                cumulativeBuy += buyLevel.volume;
                cumulativeSell += sellLevel.volume;
                
                long matchVolume = Math.min(cumulativeBuy, cumulativeSell);
                matchLevels.add(new MatchLevel(buyLevel.price, matchVolume));
                
                buyIdx++;
                sellIdx++;
            } else {
                break;
            }
        }
        
        // 找最大成交量对应的价格
        CallAuctionResult result = findBestMatchPrice(matchLevels, buyLevels, sellLevels);
        return result;
    }
    
    /**
     * 确定最终成交价
     */
    private CallAuctionResult findBestMatchPrice(
            List<MatchLevel> matchLevels, 
            List<PriceLevel> buyLevels,
            List<PriceLevel> sellLevels) {
        
        if (matchLevels.isEmpty()) {
            return CallAuctionResult.empty();
        }
        
        // 找最大成交量
        long maxVolume = matchLevels.stream()
            .mapToLong(m -> m.volume)
            .max()
            .orElse(0);
        
        // 筛选最大成交量的价格
        List<MatchLevel> maxVolumeLevels = matchLevels.stream()
            .filter(m -> m.volume == maxVolume)
            .collect(Collectors.toList());
        
        // 选择价格（优先选择使买卖双方成交量最大的价格）
        long matchPrice = maxVolumeLevels.get(maxVolumeLevels.size() / 2).price;
        
        return new CallAuctionResult(matchPrice, maxVolume);
    }
}
```

### 24.3 集合竞价执行

```java
public void executeCallAuction(String symbol) {
    OrderBook book = orderBooks.get(symbol);
    
    // 收集所有订单
    List<Order> buyOrders = book.getAllBuyOrders();
    List<Order> sellOrders = book.getAllSellOrders();
    
    // 执行集合竞价
    CallAuctionResult result = callAuctionMatcher.match(buyOrders, sellOrders);
    
    if (result.getVolume() > 0) {
        // 按成交价分配成交
        distributeTrades(book, result.getPrice(), result.getVolume());
    }
    
    // 清空订单簿，未成交订单留在订单簿
}
```

---

## 25. 持仓限额与强制平仓

### 25.1 持仓限额配置

| 品种类型 | 单账户限额 | 说明 |
|---------|-----------|------|
| A股主板 | 100万股 | 单品种持仓上限 |
| 创业板 | 50万股 | 单品种持仓上限 |
| 科创板 | 20万股 | 单品种持仓上限 |
| 股指期货 | 500手 | 单合约持仓上限 |

### 25.2 风险度计算

```java
public class RiskCalculator {
    
    /**
     * 计算账户风险度
     * @return 风险度百分比，100%表示达到强平线
     */
    public double calculateRiskDegree(Account account, List<Position> positions) {
        // 总权益 = 余额 + 浮动盈亏
        long totalEquity = account.getBalance() + account.getUnrealizedPnl();
        
        // 维持保证金 = Σ(持仓市值 × 维持保证金比例)
        long maintenanceMargin = 0;
        for (Position pos : positions) {
            Instrument inst = instrumentMap.get(pos.getSymbol());
            long marketValue = calculateMarketValue(pos, inst);
            
            if (inst.getType() == InstrumentType.STOCK) {
                // 股票无保证金概念，风险度基于市值
                maintenanceMargin += marketValue;
            } else {
                // 期货期权计算维持保证金
                maintenanceMargin += marketValue * inst.getMaintenanceMarginRatio() / 10000;
            }
        }
        
        if (maintenanceMargin == 0) return 0;
        
        // 风险度 = 维持保证金 / 总权益
        double riskDegree = (double) maintenanceMargin / totalEquity * 100;
        return riskDegree;
    }
}
```

### 25.3 强制平仓流程

```java
public class ForcedLiquidationService {
    
    /**
     * 检查并执行强平
     */
    public void checkAndLiquidate() {
        // 遍历所有账户
        for (Account account : accountMap.values()) {
            List<Position> positions = getPositions(account.getAccountId());
            double riskDegree = riskCalculator.calculateRiskDegree(account, positions);
            
            if (riskDegree >= 100) {
                // 触发强平
                executeForcedLiquidation(account, positions);
            } else if (riskDegree >= 90) {
                // 预警
                sendRiskWarning(account, riskDegree);
            }
        }
    }
    
    private void executeForcedLiquidation(Account account, List<Position> positions) {
        log.warn("触发强制平仓: accountId={}, riskDegree={}", 
            account.getAccountId(), "100%+");
        
        // 按风险度从高到低排序持仓
        positions.sort((p1, p2) -> {
            double r1 = calculatePositionRisk(p1);
            double r2 = calculatePositionRisk(p2);
            return Double.compare(r2, r1);
        });
        
        // 逐个平仓直到风险度降低
        for (Position pos : positions) {
            if (pos.getQuantity() == 0) continue;
            
            // 发送市价平仓单
            OrderRequest request = new OrderRequest();
            request.setSymbol(pos.getSymbol());
            request.setSide(pos.getQuantity() > 0 ? Side.SELL : Side.BUY);
            request.setOrderType(OrderType.MARKET);
            request.setQuantity(Math.abs(pos.getQuantity()));
            
            orderService.submitOrder(request, true);  // 强平标记
            
            // 检查风险度是否降低
            Account updatedAccount = accountMap.get(account.getAccountId());
            List<Position> updatedPositions = getPositions(account.getAccountId());
            double newRisk = riskCalculator.calculateRiskDegree(updatedAccount, updatedPositions);
            
            if (newRisk < 100) {
                break;  // 风险度已降低
            }
        }
    }
}
```

---

## 26. 涨跌停限制

### 26.1 涨跌停价格计算

```java
public class PriceLimitCalculator {
    
    /**
     * 计算涨停价
     */
    public long calculateLimitUp(Instrument inst) {
        if (inst.getPreClose() <= 0) return Long.MAX_VALUE;
        
        long limitUp = inst.getPreClose() * (10000 + inst.getLimitUpRate()) / 10000;
        return roundToPriceUnit(limitUp, inst.getMinPriceUnit(), RoundingMode.UP);
    }
    
    /**
     * 计算跌停价
     */
    public long calculateLimitDown(Instrument inst) {
        if (inst.getPreClose() <= 0) return 0;
        
        long limitDown = inst.getPreClose() * (10000 - inst.getLimitDownRate()) / 10000;
        return roundToPriceUnit(limitDown, inst.getMinPriceUnit(), RoundingMode.DOWN);
    }
    
    /**
     * 四舍五入到最小变动价位
     */
    private long roundToPriceUnit(long price, long minPriceUnit, RoundingMode mode) {
        if (minPriceUnit <= 0) return price;
        return (price / minPriceUnit) * minPriceUnit;
    }
}
```

### 26.2 涨跌停校验

```java
public void validatePriceLimit(OrderRequest request, Instrument inst) {
    if (request.getOrderType() == OrderType.MARKET) {
        // 市价单不校验价格，但在撮合时检查涨跌停
        return;
    }
    
    long price = request.getPrice();
    
    if (price > inst.getLimitUp()) {
        throw new OrderRejectException("价格超过涨停价: " + formatPrice(inst.getLimitUp()));
    }
    
    if (price < inst.getLimitDown()) {
        throw new OrderRejectException("价格低于跌停价: " + formatPrice(inst.getLimitDown()));
    }
}
```

### 26.3 涨跌停保护（市价单）

```java
// 市价单撮合时检查涨跌停
public List<Trade> matchMarket(Order order) {
    Instrument inst = instrumentMap.get(order.getSymbol());
    
    while (order.getRemainingQty() > 0) {
        Order counterOrder = book.getBestCounterOrder(order);
        if (counterOrder == null) break;
        
        // 涨跌停保护
        if (order.getSide() == BUY && counterOrder.getPrice() > inst.getLimitUp()) {
            break;  // 买入价格超过涨停，停止
        }
        if (order.getSide() == SELL && counterOrder.getPrice() < inst.getLimitDown()) {
            break;  // 卖出价格低于跌停，停止
        }
        
        // 正常撮合...
    }
}
```

---

## 27. OpenAPI认证细节

### 27.1 API Key管理

**API Key生成**：
```java
public ApiKey generateApiKey(long accountId, KeyType keyType) {
    // 生成API Key（32字节随机）
    String apiKey = generateRandomString(32);
    
    // 生成Secret Key（64字节随机）
    String secretKey = generateRandomString(64);
    
    // 加密存储Secret Key
    String encryptedSecret = aesEncrypt(secretKey, masterKey);
    String secretKeyHash = sha256Hash(secretKey);
    
    // 存储到数据库
    ApiKey key = new ApiKey();
    key.setAccountId(accountId);
    key.setApiKey(apiKey);
    key.setSecretKeyEncrypted(encryptedSecret);
    key.setSecretKeyHash(secretKeyHash);
    key.setKeyType(keyType);
    key.setStatus(Status.ACTIVE);
    key.setCreateTime(LocalDateTime.now());
    
    h2Repository.insertApiKey(key);
    
    // 返回明文Secret Key（仅此一次）
    return new ApiKey(apiKey, secretKey);
}
```

### 27.2 请求签名

**签名算法**：HMAC-SHA256

**签名步骤**：
1. 构造签名字符串：`timestamp + method + path + body`
2. 使用Secret Key计算HMAC-SHA256
3. Base64编码签名

```java
public String signRequest(long timestamp, String method, String path, 
                          String body, String secretKey) {
    // 构造签名字符串
    String signString = timestamp + "\n" + method + "\n" + path + "\n" + body;
    
    // HMAC-SHA256签名
    Mac mac = Mac.getInstance("HmacSHA256");
    SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(), "HmacSHA256");
    mac.init(keySpec);
    byte[] signature = mac.doFinal(signString.getBytes());
    
    // Base64编码
    return Base64.getEncoder().encodeToString(signature);
}
```

### 27.3 请求验证

```java
public boolean verifySignature(String apiKey, long timestamp, String method, 
                                String path, String body, String signature) {
    // 检查时间戳（5分钟有效）
    if (Math.abs(System.currentTimeMillis() - timestamp) > 5 * 60 * 1000) {
        return false;
    }
    
    // 获取API Key信息
    ApiKey key = h2Repository.getApiKey(apiKey);
    if (key == null || key.getStatus() != Status.ACTIVE) {
        return false;
    }
    
    // 解密Secret Key
    String secretKey = aesDecrypt(key.getSecretKeyEncrypted(), masterKey);
    
    // 计算签名
    String expectedSignature = signRequest(timestamp, method, path, body, secretKey);
    
    // 比较签名（常量时间比较，防止时序攻击）
    return MessageDigest.isEqual(signature.getBytes(), expectedSignature.getBytes());
}
```

### 27.4 请求头格式

```http
GET /openapi/v1/orders?symbol=600000 HTTP/1.1
Host: trading.example.com
X-Api-Key: your_api_key
X-Timestamp: 1716249600000
X-Signature: base64_encoded_signature
```

---

## 28. 错误响应标准

### 28.1 错误码定义

| 错误码 | 说明 |
|--------|------|
| 0 | 成功 |
| 1001 | 参数错误 |
| 1002 | 认证失败 |
| 1003 | 权限不足 |
| 1004 | Token过期 |
| 2001 | 账户不存在 |
| 2002 | 余额不足 |
| 2003 | 持仓不足 |
| 2004 | 订单不存在 |
| 3001 | 当前阶段不允许下单 |
| 3002 | 当前阶段不允许撤单 |
| 3003 | 价格超出涨跌停范围 |
| 3004 | 自成交检测 |
| 3005 | 超过持仓限额 |
| 4001 | 系统繁忙 |
| 4002 | 限流超限 |

### 28.2 错误响应格式

```json
{
  "code": 3001,
  "message": "当前阶段不允许下单",
  "data": {
    "currentPhase": "CALL_AUCTION_CLOSE",
    "allowOrder": false
  },
  "timestamp": 1716249600000,
  "traceId": "abc123"
}
```

### 28.3 异常处理

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(OrderRejectException.class)
    public ResponseEntity<ErrorResponse> handleOrderReject(OrderRejectException e) {
        ErrorResponse response = new ErrorResponse();
        response.setCode(e.getErrorCode());
        response.setMessage(e.getMessage());
        response.setTimestamp(System.currentTimeMillis());
        response.setTraceId(getTraceId());
        
        return ResponseEntity.badRequest().body(response);
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("系统异常", e);
        
        ErrorResponse response = new ErrorResponse();
        response.setCode(4001);
        response.setMessage("系统繁忙，请稍后重试");
        response.setTimestamp(System.currentTimeMillis());
        response.setTraceId(getTraceId());
        
        return ResponseEntity.status(500).body(response);
    }
}
```

---

## 29. 幂等性支持

### 29.1 幂等键设计

**存储结构**：
```
idempotency.dat:
  Key: idempotencyKey (String)
  Value: {
    orderId: long,
    status: String,
    createTime: long
  }
```

**幂等键生成建议**：
- UUID v4
- 业务唯一标识（如：clientId + timestamp + seq）

### 29.2 下单幂等性

```java
public OrderResult submitOrder(OrderRequest request) {
    String idempotencyKey = request.getIdempotencyKey();
    
    if (idempotencyKey != null) {
        // 幂等检查
        IdempotencyValue existing = idempotencyMap.get(idempotencyKey);
        if (existing != null) {
            // 返回已有订单
            return OrderResult.success(existing.getOrderId(), existing.getStatus());
        }
        
        // 存储幂等键（原子操作）
        IdempotencyValue newValue = new IdempotencyValue();
        newValue.setOrderId(snowflakeIdGenerator.nextId());
        newValue.setStatus("PENDING");
        newValue.setCreateTime(System.currentTimeMillis());
        
        IdempotencyValue prev = idempotencyMap.putIfAbsent(idempotencyKey, newValue);
        if (prev != null) {
            // 并发请求，返回已有订单
            return OrderResult.success(prev.getOrderId(), prev.getStatus());
        }
    }
    
    // 正常下单流程...
}
```

### 29.3 幂等键过期

```java
// EOD清理过期幂等键（保留24小时）
public void cleanupExpiredIdempotencyKeys() {
    long expireTime = System.currentTimeMillis() - 24 * 60 * 60 * 1000;
    
    idempotencyMap.forEach((key, value) -> {
        if (value.getCreateTime() < expireTime) {
            idempotencyMap.remove(key);
        }
    });
}
```

---

## 30. 前端状态管理

### 30.1 Zustand Store设计

```typescript
// stores/marketStore.ts
import { create } from 'zustand';

interface MarketState {
  symbols: string[];
  currentSymbol: string;
  marketData: Map<string, MarketDataSnapshot>;
  klineData: Map<string, KlineData[]>;
  
  setCurrentSymbol: (symbol: string) => void;
  updateMarketData: (symbol: string, data: MarketDataSnapshot) => void;
  updateKlineData: (symbol: string, data: KlineData[]) => void;
}

export const useMarketStore = create<MarketState>((set) => ({
  symbols: [],
  currentSymbol: '600000',
  marketData: new Map(),
  klineData: new Map(),
  
  setCurrentSymbol: (symbol) => set({ currentSymbol: symbol }),
  
  updateMarketData: (symbol, data) => set((state) => {
    const newMarketData = new Map(state.marketData);
    newMarketData.set(symbol, data);
    return { marketData: newMarketData };
  }),
  
  updateKlineData: (symbol, data) => set((state) => {
    const newKlineData = new Map(state.klineData);
    newKlineData.set(symbol, data);
    return { klineData: newKlineData };
  }),
}));
```

### 30.2 WebSocket状态同步

```typescript
// services/websocket.ts
export class WebSocketService {
  private ws: WebSocket | null = null;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  
  connect(token: string) {
    this.ws = new WebSocket(`ws://host/ws?token=${token}`);
    
    this.ws.onopen = () => {
      console.log('WebSocket connected');
      this.reconnectAttempts = 0;
      this.subscribe(['market_snapshot', 'order_update', 'trade_update']);
    };
    
    this.ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      this.handleMessage(message);
    };
    
    this.ws.onclose = () => {
      console.log('WebSocket closed');
      this.reconnect(token);
    };
    
    this.ws.onerror = (error) => {
      console.error('WebSocket error', error);
    };
  }
  
  private handleMessage(message: WebSocketMessage) {
    switch (message.type) {
      case 'market_snapshot':
        useMarketStore.getState().updateMarketData(
          message.data.symbol,
          message.data
        );
        break;
      case 'order_update':
        useOrderStore.getState().updateOrder(message.data);
        break;
      case 'trade_update':
        useOrderStore.getState().addTrade(message.data);
        break;
      case 'account_update':
        useAccountStore.getState().updateAccount(message.data);
        break;
    }
  }
  
  private reconnect(token: string) {
    if (this.reconnectAttempts < this.maxReconnectAttempts) {
      this.reconnectAttempts++;
      setTimeout(() => this.connect(token), 1000 * this.reconnectAttempts);
    }
  }
}
```

---

## 31. 安全设计

### 31.1 密码存储

**算法**：SHA-256 + 盐值

```java
public class PasswordEncoder {
    
    public String encode(String password) {
        // 生成随机盐值
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        
        // 计算哈希
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        byte[] hash = digest.digest(password.getBytes());
        
        // 存储：盐值 + 哈希
        return Base64.getEncoder().encodeToString(salt) + ":" +
               Base64.getEncoder().encodeToString(hash);
    }
    
    public boolean matches(String password, String encodedPassword) {
        String[] parts = encodedPassword.split(":");
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] storedHash = Base64.getDecoder().decode(parts[1]);
        
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        byte[] hash = digest.digest(password.getBytes());
        
        return MessageDigest.isEqual(hash, storedHash);
    }
}
```

### 31.2 JWT Token

**算法**：RS256（非对称加密）

```java
public class JwtService {
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    
    public String generateToken(long userId, long accountId) {
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("accountId", accountId)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 2 * 60 * 60 * 1000))  // 2小时
            .signWith(privateKey, SignatureAlgorithm.RS256)
            .compact();
    }
    
    public Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(publicKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
```

### 31.3 Token刷新机制

```java
public class TokenService {
    
    public TokenResponse refreshToken(String refreshToken) {
        // 验证Refresh Token
        Claims claims = jwtService.parseToken(refreshToken);
        if (claims == null) {
            throw new InvalidTokenException("Invalid refresh token");
        }
        
        // 检查是否在黑名单
        if (tokenBlacklistService.isBlacklisted(refreshToken)) {
            throw new InvalidTokenException("Token has been revoked");
        }
        
        // 生成新的Access Token
        long userId = Long.parseLong(claims.getSubject());
        long accountId = claims.get("accountId", Long.class);
        
        String newAccessToken = jwtService.generateToken(userId, accountId);
        
        return new TokenResponse(newAccessToken, refreshToken, 7200);
    }
    
    public void logout(String accessToken, String refreshToken) {
        // 将Token加入黑名单
        tokenBlacklistService.addToBlacklist(accessToken, "LOGOUT");
        tokenBlacklistService.addToBlacklist(refreshToken, "LOGOUT");
    }
}
```

### 31.4 Secret Key加密存储

**加密算法**：AES-256-GCM

```java
public class SecretKeyEncryptor {
    private final SecretKey masterKey;
    
    public SecretKeyEncryptor(String masterKeyHex) {
        byte[] keyBytes = Hex.decodeHex(masterKeyHex);
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
    }
    
    public String encrypt(String plaintext) {
        // 生成随机IV
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        
        // AES-GCM加密
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, spec);
        
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes());
        
        // 存储：IV + 密文
        return Base64.getEncoder().encodeToString(iv) + ":" +
               Base64.getEncoder().encodeToString(ciphertext);
    }
    
    public String decrypt(String encrypted) {
        String[] parts = encrypted.split(":");
        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec);
        
        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext);
    }
}
```

---

## 32. 审计日志

### 32.1 审计日志类型

| 类型 | 说明 | 示例 |
|------|------|------|
| ORDER | 订单相关 | 下单、撤单 |
| TRADE | 成交相关 | 成交回报 |
| ACCOUNT | 账户相关 | 入金、出金 |
| ADMIN | 管理员操作 | 创建用户、重置密码 |
| AUTH | 认证相关 | 登录、登出、Token刷新 |
| INSTRUMENT | 品种相关 | 新增、修改品种 |
| API_KEY | API密钥相关 | 创建、禁用密钥 |

### 32.2 审计日志记录

```java
public class AuditLogService {
    
    public void record(AuditType logType, long accountId, String action, 
                       Object target, Object oldValue, Object newValue) {
        AuditLog log = new AuditLog();
        log.setLogType(logType.name());
        log.setAccountId(accountId);
        log.setUserId(getCurrentUserId());
        log.setAction(action);
        log.setTargetType(target.getClass().getSimpleName());
        log.setTargetId(getTargetId(target));
        log.setOldValue(toJson(oldValue));
        log.setNewValue(toJson(newValue));
        log.setIpAddress(getClientIp());
        log.setUserAgent(getUserAgent());
        log.setCreateTime(LocalDateTime.now());
        
        // 异步写入H2
        asyncPersistenceWorker.submit(new AuditLogTask(log));
    }
    
    public void recordOrder(long accountId, String action, Order order) {
        record(AuditType.ORDER, accountId, action, order, null, order);
    }
    
    public void recordTrade(long accountId, Trade trade) {
        record(AuditType.TRADE, accountId, "TRADE", trade, null, trade);
    }
}
```

### 32.3 审计日志查询

```java
@RestController
@RequestMapping("/api/admin/audit-logs")
public class AuditLogController {
    
    @GetMapping
    public Page<AuditLog> queryAuditLogs(
            @RequestParam(required = false) String logType,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        return auditLogService.query(logType, accountId, userId, 
                                      startDate, endDate, page, size);
    }
}
```

### 32.4 审计日志保留

**保留策略**：
- 在线保留：90天
- 归档保留：3年
- 归档方式：压缩后存储到对象存储（OSS）

```java
public class AuditLogArchiveService {
    
    public void archiveOldLogs() {
        LocalDate cutoffDate = LocalDate.now().minusDays(90);
        
        // 查询需要归档的日志
        List<AuditLog> logs = auditLogService.queryByDateBefore(cutoffDate);
        
        // 压缩并上传到OSS
        byte[] compressed = compressLogs(logs);
        String objectKey = "audit-logs/" + cutoffDate.format(DateTimeFormatter.ISO_DATE) + ".zip";
        ossService.upload(objectKey, compressed);
        
        // 删除已归档的日志
        auditLogService.deleteByDateBefore(cutoffDate);
    }
}
```

---

## 附录

### A. 配置参数汇总

| 参数 | 默认值 | 说明 |
|------|--------|------|
| server.port | 8080 | 服务端口 |
| chronicle.base.path | data/chronicle/ | Chronicle文件目录 |
| h2.database.path | data/h2/trading | H2数据库路径 |
| jwt.access.token.expiration | 7200000 | Access Token过期时间（毫秒） |
| jwt.refresh.token.expiration | 604800000 | Refresh Token过期时间（毫秒） |
| rate.limit.order.tps | 100 | 单账户下单限流 |
| rate.limit.query.qps | 1000 | 单账户查询限流 |

### B. 监控指标汇总

| 指标 | 类型 | 说明 |
|------|------|------|
| hft_orders_total | Counter | 订单总数 |
| hft_trades_total | Counter | 成交总数 |
| hft_order_latency | Histogram | 订单延迟分布 |
| hft_trade_latency | Histogram | 成交延迟分布 |
| hft_matching_engine_lag | Gauge | 撮合引擎延迟 |
| hft_clearing_worker_lag | Gauge | 清算服务延迟 |
| hft_websocket_connections | Gauge | WebSocket连接数 |
| hft_gc_pause_seconds | Gauge | GC暂停时间 |

### C. 错误码汇总

| 错误码 | 说明 |
|--------|------|
| 0 | 成功 |
| 1001-1999 | 客户端错误 |
| 2001-2999 | 业务错误 |
| 3001-3999 | 交易规则错误 |
| 4001-4999 | 系统错误 |

---

> 文档结束
> 
> 本文档为 A股交易系统技术方案 V7，包含完整的系统设计、数据模型、核心流程、并发控制、持久化恢复、API设计、前端设计、性能分析、部署配置、监控运维、业务规则、安全设计等所有实现细节。
> 
> 状态：**设计完成，可落地开发**
