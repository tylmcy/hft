# HFT Trading System - Project Context

## Project Overview

A股交易系统，单JVM部署，目标 100K TPS，P99延迟 <15ms。

## Architecture

### Core Components

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
```

### Thread Model

| 线程池 | 线程数 | 职责 |
|--------|--------|------|
| HTTP请求处理 | N (CPU核心数) | REST API请求处理 |
| WebSocket处理 | N (CPU核心数) | WebSocket连接/消息处理 |
| OrderGatewayWorker | 1 | 订单请求处理，风控检查，资金冻结 |
| MatchingEngine | 1 | 多Symbol订单撮合 |
| ClearingWorker | 1 | 成交清算，资金/持仓更新 |
| MarketDataPublisher | 1 | 行情数据推送 |
| H2PersistenceWorker | 1 | 异步持久化到H2 |

### Data Storage

| 存储 | 技术 | 用途 |
|------|------|------|
| Chronicle Queue | 低延迟队列 | 订单流、成交流 (主链路) |
| Chronicle Map | 内存映射 | 账户/持仓/订单簿 (实时持久化) |
| H2 Database | 磁盘数据库 | 历史查询 (不参与主链路) |

### Key Chronicle Map Files

- `accounts.dat` - 账户状态 (Key: accountId)
- `positions.dat` - 持仓状态 (Key: accountId<<32 | symbolCode)
- `orderbook.dat` - 订单簿状态 (Key: symbol)
- `indexes.dat` - 各类Index (orderIdIndex, tradeIdIndex)

## API Definitions

### HTTP REST API

详见 [http-api-definition.html](docs/http-api-definition.html)

| 模块 | 接口 | 方法 | 说明 |
|------|------|------|------|
| 认证 | /api/auth/login | POST | 用户登录 |
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

### WebSocket API

**连接端点**: `ws://host:port/ws?token=<jwt_token>`

**订阅消息**:
```json
{
  "type": "subscribe",
  "channel": "market_snapshot|kline|orderbook|order_update|trade_update|account_update",
  "symbols": ["600000"]  // 行情订阅时
}
```

**推送消息类型**:
- `market_snapshot` - 行情快照推送
- `kline` - K线数据推送
- `orderbook` - 订单簿 Level 2 推送
- `order_update` - 订单状态推送
- `trade_update` - 成交推送
- `account_update` - 账户资金变动推送
- `position_update` - 持仓变动推送

**心跳**: ping/pong 每30秒

## Key Design Decisions

### 1. Chronicle Map 并发访问

使用 `compute()` 方法进行原子操作，Entry级别锁：

```java
// 原子冻结资金
accountMap.compute(accountId, (key, account) -> {
    if (account.getAvailable() < amount) return account;
    account.setAvailable(account.getAvailable() - amount);
    account.setFrozen(account.getFrozen() + amount);
    return account;
});
```

### 2. 单线程清算

ClearingWorker 单线程处理成交，避免 Index 冲突，无需滑动窗口。

### 3. 统一订单队列

Order Queue 合并 NEW_ORDER 和 CANCEL_ORDER 事件类型，单队列处理。

### 4. 多 Symbol 撮合

单 MatchingEngine 线程，维护 `Map<String, OrderBook>`，每个 Symbol 独立 OrderBook。

### 5. 订单簿持久化

OrderBook 状态存储在 `orderbook.dat` Chronicle Map，JVM重启后自动恢复。

### 6. 幂等性保证

清算前使用 `putIfAbsent()` 检查 TradeId，避免重复清算。

## Performance Targets

| 指标 | 目标值 |
|------|--------|
| 订单处理延迟 | < 100μs (P99) |
| 撮合延迟 | < 10μs |
| 系统吞吐量 | > 100,000 TPS |
| 数据持久化延迟 | < 10ms |
| 系统可用性 | 99.99% |

## Technology Stack

| 组件 | 技术选型 |
|------|---------|
| 消息队列 | Chronicle Queue |
| 内存存储 | Chronicle Map |
| 数据库 | H2 (磁盘模式) |
| GC策略 | ZGC (Generational) |
| Web容器 | Spring Boot 4 + Virtual Threads |
| 监控 | Prometheus + Grafana |
| 日志 | ELK Stack |
| 链路追踪 | Jaeger |
| 前端框架 | React 18 + TypeScript |
| 前端图表 | TradingView |
| 前端UI | Ant Design 5.x |
| 前端状态 | Zustand |

## Documentation

- [技术方案 V2](docs/technical-solution-V2.md) - 完整技术方案
- [HTTP API 定义](docs/http-api-definition.html) - REST API 和 WebSocket 接口定义
- [线程工作流](docs/thread-workflow.html) - 线程模型和数据流
- [前端布局](docs/frontend-layout.html) - 前端组件设计
