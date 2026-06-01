# HFT Trading System - Project Context

## Behavioral Guidelines

**重要：这些准则优先于默认行为，适用于所有开发任务。**

### 1. 先思考再编码

**不要假设。不要隐藏困惑。呈现权衡。**

在实现之前：
- 明确陈述假设。如果不确定，先询问。
- 如果存在多种解释，呈现它们 - 不要静默选择。
- 如果有更简单的方法，说出来。必要时提出异议。
- 如果某事不清楚，停下来。指出困惑点。询问。

### 2. 简单优先

**解决问题的最小代码量。没有投机性设计。**

- 不要添加未被请求的功能。
- 不要为单次使用代码创建抽象。
- 不要添加未被请求的"灵活性"或"可配置性"。
- 不要为不可能发生的场景编写错误处理。
- 如果你写了200行代码而实际上50行就能解决，重写它。

自问："资深工程师会说这太复杂了吗？" 如果是，简化它。

### 3. 精准修改

**只修改必要的部分。只清理自己造成的混乱。**

编辑现有代码时：
- 不要"改进"相邻代码、注释或格式。
- 不要重构未损坏的部分。
- 匹配现有风格，即使你会用不同方式编写。
- 如果注意到无关的死代码，提及它 - 不要删除它。

当你的修改产生孤立代码时：
- 删除因你的修改而变得未使用的导入/变量/函数。
- 不要删除预先存在的死代码，除非被要求。

测试：每行修改都应直接追溯到用户的请求。

### 4. 目标驱动执行

**定义成功标准。循环验证直到确认。**

将任务转化为可验证的目标：
- "添加验证" → "为无效输入编写测试，然后让它们通过"
- "修复bug" → "编写重现它的测试，然后让它通过"
- "重构X" → "确保重构前后测试都通过"

对于多步骤任务，陈述简要计划：
```
1. [步骤] → 验证: [检查]
2. [步骤] → 验证: [检查]
3. [步骤] → 验证: [检查]
```

强成功标准让你能独立循环。弱标准（"让它工作"）需要持续澄清。

---

**准则生效标志：** diff中更少的不必要修改、更少因过度复杂导致的重写、澄清问题在实现前提出而非错误发生后。

---

## Project Overview

A股模拟交易系统，单JVM部署，目标 100K TPS，P99延迟 <15ms。

**系统定位**：
- 模拟交易系统，入金手工上账
- 交易、清结算逻辑完整正确
- 内部用户间撮合（含做市商策略用户）
- 五档行情来自撮合引擎订单簿，新浪行情仅展示

### 核心设计原则

| 原则 | 说明 |
|------|------|
| **数据一致性优先** | 所有状态变更通过CQ顺序处理，单线程内存操作，保证强一致 |
| **RPO = 0** | 重启后数据零丢失，依赖快照+增量恢复机制 |
| **简单可靠** | 单线程处理账户操作，避免并发复杂度 |

## Architecture

### Deployment Architecture

**前后端一体化部署**：前端静态资源打包后部署在后端Tomcat中，随JVM一起启动。

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
│  │  │  (前端SPA)    │  │  (虚拟线程)   │  │  (Netty)      │            │   │
│  │  │  /index.html  │  │  /api/*       │  │  /ws          │            │   │
│  │  └───────────────┘  └───────────────┘  └───────────────┘            │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     incoming_event CQ                                │   │
│  │           (NEW_ORDER/CANCEL_ORDER/UNFREEZE/SETTLEMENT/SNAPSHOT)      │   │
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
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │              MatchingEngine (单线程，批量处理)                        │   │
│  │  • 更新内存OrderBook • 订单撮合 • 生成成交事件                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                       trade CQ                                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │              ClearingWorker (单线程，批量处理)                        │   │
│  │  • 更新订单状态 • 计算结算金额 • 发送SETTLEMENT/UNFREEZE事件          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                SnapshotWorker (定时，暂停所有Worker)                  │   │
│  │  内存 → CM快照文件 → index.dat (原子rename)                           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Thread Model

| 线程池 | 线程数 | 职责 |
|--------|--------|------|
| HTTP请求处理 | N (虚拟线程) | REST API请求处理，写入CQ后等待CompletableFuture |
| WebSocket处理 | N (Netty EventLoop) | WebSocket连接/消息处理 |
| AccountWorker | 1 | 冻结/解冻/结算，单线程串行处理 |
| MatchingEngine | 1 | 多Symbol订单撮合 |
| ClearingWorker | 1 | 成交清算，订单状态更新 |
| MarketDataPublisher | 1 | 行情数据推送 |
| SnapshotWorker | 1 | 定时快照，暂停所有Worker后写入CM |

### Data Storage

**核心原则：JVM内存为主存储，Chronicle Map仅用于快照持久化**

| 存储 | 技术 | 用途 |
|------|------|------|
| JVM内存 | ConcurrentHashMap | 账户/持仓/订单/订单簿主存储 |
| Chronicle Queue | 低延迟队列 | 事件流（incoming/match/trade CQ） |
| Chronicle Map | 内存映射 | 快照持久化文件（accounts_{ver}.dat等） |
| H2 Database | 磁盘数据库 | 历史查询（不参与主链路） |

### Key Files

**Chronicle Queue 文件**：
- `incoming_event.cq` - 入口事件队列（NEW_ORDER/CANCEL_ORDER/UNFREEZE/SETTLEMENT）
- `match_orders.cq` - 待撮合订单队列
- `trade.cq` - 成交事件队列

**Chronicle Map 快照文件**：
- `account_snapshot_{incoming_cq_index}.dat` - 账户快照（含持仓、订单）
- `orderbook_snapshot_{match_cq_index}.dat` - 订单簿快照
- `clearing_index.dat` - ClearingWorker处理的trades CQ位置

## Data Consistency Requirements

### 强一致性保证

| 约束 | 实现方式 |
|------|---------|
| **账户不变式** | `balance = available + frozen`，`frozen = marginFrozen + orderFrozen` |
| **持仓不变式** | `|quantity| = available + frozen + t1Locked` |
| **单线程处理** | AccountWorker/MatchingEngine/ClearingWorker 各单线程串行处理 |
| **顺序一致性** | 所有状态变更通过CQ顺序传播 |
| **幂等性** | 清算前使用 `putIfAbsent()` 检查 TradeId |

### RPO = 0 保证

| 机制 | 说明 |
|------|------|
| **事件溯源** | 所有状态变更写入CQ，持久化到磁盘 |
| **SNAPSHOT事件** | AccountWorker/MatchingEngine处理SNAPSHOT事件时触发快照 |
| **独立快照文件** | account_snapshot_{cq_index}.dat, match_snapshot_{cq_index}.dat, clearing_index.dat |
| **ClearingWorker索引** | 每批次后写入clearing_index.dat（原子：tmp→rename） |
| **恢复机制** | 读取各快照文件 → 加载到内存 → 从对应CQ index重放增量 |

### 快照流程

```
1. SnapshotWorker定时写入SNAPSHOT事件：
   - incoming_event CQ 写入 SNAPSHOT 事件
   - match_orders CQ 写入 SNAPSHOT 事件

2. AccountWorker处理SNAPSHOT事件：
   - 批量读取时发现SNAPSHOT事件，立即处理当前批次普通事件
   - 执行快照：写入 account_snapshot_{cq_index}.dat
   - 后续事件在下一批次处理（自然暂停）

3. MatchingEngine处理SNAPSHOT事件：
   - 同上，写入 orderbook_snapshot_{cq_index}.dat

4. ClearingWorker每批次后：
   - 写入 clearing_index.dat 记录 trades CQ 处理位置
```

### 恢复流程

```
1. 查找最新快照文件：
   - account_snapshot_*.dat → 获取 incoming_cq_index
   - orderbook_snapshot_*.dat → 获取 match_cq_index
   - clearing_index.dat → 获取 trades_cq_index

2. 加载快照到内存：
   - account/position/order snapshot → ConcurrentHashMap
   - orderbook snapshot → TreeMap

3. 重放CQ增量：
   - incoming_event CQ 从 incoming_cq_index + 1 开始
   - match_orders CQ 从 match_cq_index + 1 开始
   - trades CQ 从 trades_cq_index + 1 开始

4. 系统就绪，开始接收请求
```

## Batch Processing

### 批量处理模式

所有 Worker 采用批量处理模式提升吞吐量：

```
while (running) {
    // 1. 批量读取事件 (最多50个或等待1ms)
    List<Event> events = cq.readBatch(maxSize=50, timeoutMs=1);
    
    // 2. 批量处理（内存操作）
    for (Event e : events) {
        process(e);
    }
    
    // 3. 批量写入下游 CQ
    downstreamCq.writeBatch(results);
    
    // 4. 批量唤醒 HTTP 线程（AccountWorker）
    for (CompletableFuture f : futures) {
        f.complete(result);
    }
}
```

### 性能目标

| 指标 | 目标值 |
|------|--------|
| 系统吞吐量 | ≥ 100,000 TPS |
| HTTP返回延迟 | ≤ 5ms (P99) |
| 端到端延迟 | ≤ 15ms (P99) |
| 批量大小 | 50事件/批次 |
| 快照间隔 | 60秒 |

## API Definitions

### HTTP REST API

详见 [http-api-definition.html](docs/http-api-definition.html)

| 模块 | 接口 | 方法 | 说明 |
|------|------|------|------|
| 认证 | /api/auth/login | POST | 用户登录 |
| 认证 | /api/auth/logout | POST | 用户登出 |
| 账户 | /api/account/info | GET | 账户信息 |
| 账户 | /api/account/positions | GET | 持仓列表 |
| 订单 | /api/orders | POST | 下单（同步返回orderId，异步成交） |
| 订单 | /api/orders/{orderId} | DELETE | 撤单 |
| 订单 | /api/orders/active | GET | 当前委托 |
| 历史 | /api/trades | GET | 历史成交 |
| 历史 | /api/account/cashflow | GET | 资金流水 |
| 行情 | /api/market/symbols | GET | 股票列表 |
| 行情 | /api/market/watchlist | GET/POST/DELETE | 自选股管理 |

### HTTP 同步机制

下单请求使用 CompletableFuture 实现同步等待：

```java
// HTTP 线程
CompletableFuture<ProcessResult> future = new CompletableFuture<>();
String orderId = generateOrderId();

// 写入 CQ
incomingEventCq.write(new IncomingEvent(NEW_ORDER, order, future));

// 同步等待（虚拟线程阻塞）
ProcessResult result = future.get(5, TimeUnit.SECONDS);
return result.isSuccess() ? orderId : error(result);
```

```java
// AccountWorker 批量处理后唤醒
for (ProcessedItem item : batch) {
    item.future.complete(item.result);
}
```

### WebSocket API

**连接端点**: `ws://host:port/ws?token=<jwt_token>`

**订阅消息**:
```json
{
  "type": "subscribe",
  "channel": "market_snapshot|kline|orderbook|order_update|trade_update|account_update",
  "symbols": ["600000"]
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

## Technology Stack

| 组件 | 技术选型 |
|------|---------|
| Java | Java 21 LTS |
| Spring Boot | 3.x + 虚拟线程（Project Loom） |
| 消息队列 | Chronicle Queue（最新稳定版） |
| 快照存储 | Chronicle Map（最新稳定版） |
| 数据库 | H2 (磁盘模式) |
| GC策略 | ZGC (Generational) |
| Web容器 | Spring Boot + Tomcat |
| 前端部署 | 打包到 static/ 目录，随JVM启动 |
| 前端框架 | React 18 + TypeScript + Vite |
| 前端图表 | lightweight-charts（TradingView官方开源） |
| 前端UI | Ant Design 5.x |
| 前端状态 | Zustand |
| 客户端支持 | 仅Web端 |
| 监控 | Prometheus + Grafana |
| 日志 | ELK Stack |
| 链路追踪 | Jaeger |

## User System

**用户账户流程**：
- 用户注册时自动创建账户，初始余额为0
- 管理员通过 `/admin/deposit` 接口手动入金
- 无审批流程

## Build & Deploy

### 前端构建

```bash
cd frontend
npm install
npm run build
cp -r dist/* ../src/main/resources/static/
```

### 后端构建

```bash
mvn clean package -DskipTests
java -Xms10g -Xmx10g -XX:+UseZGC -jar target/hft-trading.jar
```

### 访问地址

| 资源 | 地址 |
|------|------|
| 前端页面 | http://host:port/ |
| REST API | http://host:port/api/* |
| WebSocket | ws://host:port/ws |
| OpenAPI | http://host:port/openapi/* |
| 健康检查 | http://host:port/actuator/health |

## Documentation

- [技术方案 V9](docs/technical-solution-V9.md) - 完整技术方案
- [交易全链路图](docs/trading-full-pipeline-v9.html) - 下单到清结算完整流程
- [HTTP API 定义](docs/http-api-definition.html) - REST API 和 WebSocket 接口定义
- [前端布局](docs/frontend-layout.html) - 前端组件设计

## Git 操作规范

**重要**：所有Git操作必须等待用户明确指令，不要主动执行任何git命令。

| 操作 | 执行条件 |
|------|---------|
| git add / git commit | 用户说"提交到本地"或"commit" |
| git push | 用户说"提交到远程"或"push" |

**禁止行为**：
- 不要在完成代码修改后自动执行git命令
- 不要主动提示用户是否需要提交
- 严格等待用户的明确指令

## 问题反馈规范

**重要**：当审查文档、代码或设计方案发现问题时，必须同时给出修复建议。

| 要求 | 说明 |
|------|------|
| **问题描述** | 清晰说明问题所在（文件、行号、具体内容） |
| **严重性评估** | P0（阻塞性）/ P1（需修复）/ P2（建议优化） |
| **修复建议** | 给出具体的修复方案，而非仅指出问题 |
| **示例代码** | 如涉及代码/结构变更，提供修改后的示例 |

**示例格式**：
```
问题：ORDER_UPDATE事件结构未定义（P0）
位置：docs/technical-solution-V9.md 3.3.3节
建议：在TradeEvent结构中新增ORDER_UPDATE事件类型，补充字段定义：
  - eventType: ORDER_UPDATE
  - orderId: long
  - status: NEW/PARTIAL_FILLED/FILLED/CANCELLED
  - ...
```
