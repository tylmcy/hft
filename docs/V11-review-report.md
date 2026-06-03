# V11 技术方案多Agent审查报告

> 审查对象: [technical-solution-V11.md](technical-solution-V11.md) (v11.8, ~3960 行)
> 审查维度: 前后一致性 / 严谨性 / 完整性
> 审查方法: 3 个独立 Agent 并行审查
> 审查日期: 2026-06-02

---

## 统计概览

| 维度 | P0 | P1 | P2 | 合计 |
|------|----|----|----|----|
| 一致性 | 5 | 8 | 3 | 16 |
| 严谨性 | 2 | 9 | 12 | 23 |
| 完整性 | 4 | 5 | 2 | 11 |
| **合计** | **11** | **22** | **17** | **50** |

---

## P0 级问题（阻塞性，必须修复）— 共 11 项

---

### P0-1: Section 7.2 恢复流程使用已废弃的 index.dat 集中式模型

**维度**: 一致性
**位置**: 7.2 节 (行 1747-1770) vs 3.4 节 (行 845-1198), 17.7 节 (行 3703-3749)

**问题描述**:
Section 7.2 "恢复流程"描述了一套集中式恢复模型：
- 从 `data/chronicle/snapshots/index.dat` 读取单一索引文件
- 使用全局变量 `accountInputIndex`、`matchingInputIndex`、`matchingOutputIndex`、`accountSettlementIndex`
- 从 `orderCM` 加载订单（`加载订单：orderCM → orderStore`）
- 所有 Worker 启动后再统一恢复

但 Section 3.4 和 17 已完全采用独立快照设计：
- 每个 Worker 拥有独立的快照文件（`account_snapshot_{index}.dat`、`matching_snapshot_{index}.dat`）
- ClearingWorker 不生成快照
- AccountWorker 快照不保存订单
- 恢复按四阶段顺序执行（AccountWorker → MatchingEngine → ClearingWorker → AccountWorker 第二轮）
- **index.dat 在新设计中不存在**

Section 7.2 整节是旧架构的残留，与 3.4/17 的独立快照设计完全矛盾。

**修复建议**:
重写 Section 7.2，使其与 3.4 和 17.7 的四阶段恢复模型一致：

```
1. 恢复顺序：AccountWorker(第一阶段) → MatchingEngine → ClearingWorker → AccountWorker(第二阶段)

2. AccountWorker 第一阶段恢复：
   - 查找最新有效 account_snapshot_{index}.dat（验证 MD5 checksum）
   - 加载 accounts、positions 到内存
   - 记录 account_input_index、account_settlement_index、matching_input_last_index
   - 生成 processed_order_ids、processed_settlement_ids
   - 设置 replay_mode=true，重放 account_input CQ

3. MatchingEngine 恢复：
   - 查找最新有效 matching_snapshot_{index}.dat（验证 MD5 checksum）
   - 加载 OrderBook 到内存
   - 生成 existing_trade_ids
   - 设置 replay_mode=true，重放 matching_input CQ

4. ClearingWorker 恢复：
   - 不做幂等检查，不设 replay_mode
   - 从 MatchingEngine 快照的 matching_output_last_index+1 开始重放 matching_output CQ

5. AccountWorker 第二阶段恢复：
   - 生成 processed_trade_ids（从 account_settlement CQ 近 10 分钟）
   - 重放 account_settlement CQ 处理 SETTLEMENT/UNFREEZE 事件
```

删除所有 `index.dat`、`accountInputIndex`、`matchingInputIndex`、`matchingOutputIndex`、`accountSettlementIndex`、`orderCM` 的引用。

---

### P0-2: Section 3.4.4 原子写入流程嵌入 CRC32 但文件格式声明不内嵌

**维度**: 一致性
**位置**: 3.4.4 节 (行 1134-1137) vs 3.4.1 节 (行 849-864, 875)

**问题描述**:
Section 3.4.4 快照文件原子写入流程中：
```
3. 计算并写入CRC32校验和
   crc32 = computeCRC32(tmp_file)
   writeLong(tmp_file, crc32)
```

但 Section 3.4.1 明确声明：
- 行 875: "快照文件不内嵌CRC32，仅使用外部MD5校验文件"
- 行 849-864: AccountWorker 快照文件结构中无 `checksum` 字段

如果写入流程嵌入 CRC32 但文件结构不含此字段，读取时会多出 8 字节导致反序列化错误。反之，如果按 3.4.1 不嵌入 CRC32，恢复伪代码中的 `validateCRC32(snapshot)` 将失败。

**修复建议**:
1. 从 3.4.4 写入流程中删除步骤 3（CRC32 嵌入）
2. 仅保留外部 MD5 校验文件：步骤 4 → 计算 MD5，步骤 5 → 写入 .checksum 文件
3. 确保写入流程明确说明"快照文件 body 不含校验和，完整性由外部 .checksum 文件保证"

---

### P0-3: Section 3.4.1 恢复伪代码调用 validateCRC32(snapshot) 但文件不含 CRC32

**维度**: 一致性
**位置**: 3.4.1 节恢复逻辑步骤 2 (行 904) vs 3.4.1 文件结构 (行 849-864, 875)

**问题描述**:
AccountWorker 恢复伪代码：
```
2. 加载快照到内存
   snapshot = readFile(valid_snapshot_file)
   validateCRC32(snapshot)       // ← 行 904
```

但：
- AccountWorker 快照文件结构 (行 849-864) 不含 `checksum` 或 `crc32` 字段
- 行 875 声明"快照文件不内嵌CRC32"
- MD5 校验已在步骤 1 中通过外部 .checksum 文件完成 (行 891-897)

`validateCRC32` 调用是对不存在字段的校验，是一个遗留调用。

**修复建议**:
删除行 904 的 `validateCRC32(snapshot)` 调用。MD5 校验在步骤 1 已完成，无需重复校验。

---

### P0-4: Section 2.3 组件表称 ClearingWorker "快照时写CM"，但 3.4.3/17.4 明确 ClearingWorker 不生成快照

**维度**: 一致性
**位置**: 2.3 节组件表 (行 317) vs 3.4.3 节 (行 1079-1080, 1114-1115), 17.1 节 (行 3355), 17.4 节 (行 3576)

**问题描述**:
Section 2.3 组件表：
```
| ClearingWorker | 订单状态更新，结算计算 | 单线程 | 内存（快照时写CM） |
```
→ "快照时写CM" 表示 ClearingWorker 在快照时写 Chronicle Map

但其余章节一致说明：
- 3.4.3 行 1079: "ClearingWorker不做幂等检查，不生成processed_trade_ids"
- 3.4.3 行 1114: "ClearingWorker不使用replay_mode机制，因为它是纯转发角色"
- 17.1 行 3355: ClearingWorker 快照文件列为 "无 | -"
- 17.4 行 3576: "ClearingWorker不生成快照"

**修复建议**:
将 Section 2.3 组件表 ClearingWorker 的数据存储列从 `内存（快照时写CM）` 改为 `内存`。与其余章节保持一致。

---

### P0-5: ClearingWorker 恢复行为的三方矛盾（processed_trade_ids 和 replay_mode）

**维度**: 一致性
**位置**: 3.4.5 (行 1174) 和 17.7 节 (行 3737) vs 3.4.3 节 (行 1079-1081, 1114), 17.4 节 (行 3580)

**问题描述**:
三处对 ClearingWorker 恢复行为的描述互相矛盾：

| 位置 | 描述 |
|------|------|
| 3.4.5 步骤 3 (行 1174) | "ClearingWorker恢复 - 生成processed_trade_ids" |
| 17.7 步骤 3 (行 3737) | "设置replay_mode=true" |
| 3.4.3 (行 1079-1081) | "不做幂等检查，不生成processed_trade_ids" + "不使用replay_mode机制" |
| 17.4 (行 3580) | "ClearingWorker不使用replay_mode机制（不需要）" |

3.4.3 和 17.4 的设计是正确的（ClearingWorker 是纯转发器，幂等由下游 AccountWorker 兜底）。3.4.5 和 17.7 是错误的残留。

**修复建议**:
1. 将 3.4.5 步骤 3 从 "ClearingWorker恢复 - 生成processed_trade_ids - 重放matching_output CQ - 写入account_settlement CQ" 改为 "ClearingWorker恢复 - 重放matching_output CQ（不做幂等检查，直接处理） - 写入account_settlement CQ"
2. 将 17.7 步骤 3 首行从 "设置replay_mode=true" 改为 "ClearingWorker不使用replay_mode机制，直接重放matching_output CQ"
3. 在 17.7 末尾加注释："幂等检查由 AccountWorker 处理 SETTLEMENT 事件时通过 processed_trade_ids 兜底"

---

### P0-6: AccountWorker 恢复跳过了已转发订单的冻结操作（数据丢失 Bug）

**维度**: 严谨性
**位置**: 3.4.1 节 (行 954-970), 17.2 节 (行 3492-3501)

**问题描述**:
AccountWorker 恢复重放伪代码：
```
if replay_mode && event.orderId in processed_order_ids:
    skip  // 跳过 BOTH freeze AND matching_input 写入
```

这里 `skip` 不仅跳过了 matching_input 的写入（正确），也跳过了冻结操作（错误）。

**具体危害场景**：
1. 快照在 account_input index=100 时保存账户状态（不含订单 101+ 的冻结效果）
2. AccountWorker 已处理事件 101-150：冻结了对应资金，matching_input 写到 145 时崩溃（146-150 未写入）
3. 恢复：加载快照（状态回到 index=100），processed_order_ids = {101-145 的 orderId}
4. 重放 account_input 从 101：事件 101-145 被**完全跳过** → 冻结操作不会重新执行
5. **结果：账户的 available 比实际应有的多**，冻结资金凭空消失

**修复建议**:
重构重放逻辑为**始终执行冻结，仅跳过 matching_input 写入**：

```
if event.type == NEW_ORDER:
    // ⚠️ 始终执行冻结操作（快照不包含这些冻结效果）
    if event.side == BUY:
        accountStore.freeze(event.accountId, event.frozenAmount)
    else:
        positionStore.freezePosition(event.accountId, event.symbol, event.quantity)
    write_to_account_output(event)

    // 仅在未转发时才写入 matching_input
    if !(replay_mode && event.orderId in processed_order_ids):
        write_to_matching_input(event)
        if replay_mode:
            replay_mode = false
```

同样逻辑应用于 17.2 节的恢复伪代码。

---

### P0-7: frozenAmount 计算公式示例值缩小 10 倍

**维度**: 严谨性
**位置**: 5.3 节 (行 1483-1504)

**问题描述**:
文档中的部分成交示例：
- `order.price = 1000分 (10.00元)`, `order.quantity = 1000股`
- `order.frozenAmount = 100500分`（含 500 分预估佣金）
- 但 `price × quantity = 1000 × 1000 = 1,000,000分`

frozenAmount 应该是约 `1,000,300分`（1,000,000 + 300 分佣金，按万分之三），**而不是 100,500**（仅为应有值的 10%）。

基于错误 frozenAmount 的计算都跟着错：
- 文档算出 `frozenAmountUsed = 29,547分`，但实际成交金额 `294,000分`
- 买方冻结资金不足以覆盖成交金额 → 若照此实现将导致负余额

**修复建议**:
修正示例参数，选择以下任一方案：

**方案 A**（改 frozenAmount，推荐）：保持 price=1000, quantity=1000
- `frozenAmount = 1,000,300分`（1,000,000 + 300 分佣金 @万分之三）
- 部分成交：price=980, quantity=300
- `frozenAmountUsed = ceil(294000 × 1000300 / 1000000) = ceil(294088.2) = 294,089分`
- `actualAmount = 980 × 300 = 294,000分`
- `commission = 294000 × 3 / 10000 = 88分` (整数除法)
- `refund = 294089 - 294000 - 88 = 1分`

**方案 B**（改 price）：保持 frozenAmount=100,500
- 改 `price = 100分 (1.00元)`, `quantity = 1000股`
- 这样 `price × quantity = 100,000分`，frozenAmount=100,500 合理
- 重算所有后续数字

---

### P0-8: AsyncPersistence 线程无行为描述

**维度**: 完整性
**位置**: 4.1 节 (行 1232), 4.2 节 (行 1246)

**问题描述**:
AsyncPersistence 在线程模型中列出（1 个线程，"H2批量写入，不访问内存"），但整个文档中：
- 没有描述其数据源（从哪个 CQ 读取？从内存读取？）
- 没有定义目标 H2 表结构（哪些表？什么字段？）
- 没有定义批量策略（批量大小？刷新间隔？）
- 没有定义错误处理（写入失败？DB 连接断开？重试？丢弃？告警？）
- 没有定义启动/停止流程

文档中唯一提到 H2 写入的是 16.2 EOD 步骤 7："写入H2历史表（订单、成交、资金流水）"，但这在 EOD 流程中，不是持续运行的线程。

**修复建议**:
明确 AsyncPersistence 的定位：

**方案 A**（持续写入）：如果 AsyncPersistence 在交易时段持续将成交/订单写入 H2，则需新增小节描述：
- 数据源：读取 matching_output CQ、account_output CQ
- 写入内容：每笔 TRADE → trades_history 表，每笔 ORDER_UPDATE → orders_history 表
- 批量策略：100 条/批或 1 秒间隔
- 错误处理：写入失败重试 3 次，仍失败则记录告警并跳过，由 EOD 补写
- 此方案可减轻 EOD 的 H2 写入压力

**方案 B**（仅 EOD）：如果 H2 写入仅在 EOD 执行，则从线程模型中删除 AsyncPersistence，将 H2 写入完全放在 16.2 EOD 步骤 7。

---

### P0-9: LifecycleManager 被引用但无定义

**维度**: 完整性
**位置**: 6.3 节 volatile 变量表 (行 1703-1704)

**问题描述**:
Section 6.3 volatile 变量表声明：
```
| systemStatus | SystemStatus | 系统级状态 | LifecycleManager |
| shuttingDown | boolean | 优雅关闭标志 | LifecycleManager |
```
LifecycleManager 是这两个关键系统状态变量的**权威更新者**，但全文无任何章节定义：
- LifecycleManager 的启动序列（Worker 启动顺序？依赖关系？）
- 状态转换的触发条件（STARTING→RECOVERING→RUNNING→SHUTTING_DOWN→DEGRADED）
- DEGRADED 模式的进入/退出条件
- 与 PhaseManager、SnapshotWorker 的协调关系

**修复建议**:
新增 6.5 节 "LifecycleManager 设计"：

```
LifecycleManager 职责：
1. 系统启动序列：
   a. 设置 systemStatus = STARTING
   b. 初始化 CQ 文件
   c. 按顺序恢复：AccountWorker → MatchingEngine → ClearingWorker
      （恢复期间 systemStatus = RECOVERING）
   d. 恢复完成后 systemStatus = RUNNING

2. 系统关闭序列：
   a. 设置 shuttingDown = true, systemStatus = SHUTTING_DOWN
   b. 停止接收新订单（HTTP 返回 4006）
   c. 等待队列清空（最长 30 秒）
   d. 写入最终 SNAPSHOT
   e. 停止所有 Worker
   f. 关闭 CQ/H2/WebSocket

3. 降级模式：
   - 触发条件：CQ 文件损坏 / 快照恢复失败 / 连续 3 次快照超时
   - 进入 DEGRADED 状态：部分功能受限，继续处理已有订单
   - 退出：人工介入修复后，通过管理接口恢复 RUNNING
```

---

### P0-10: H2 数据库 Schema 完全缺失

**维度**: 完整性
**位置**: 16.1 节 (行 2984: "从H2加载交易品种、交易时段、交易日历"), 16.2 节 (行 3150-3151: "写入H2历史表（订单、成交、资金流水）"), 13.1 节

**问题描述**:
文档多处引用 H2 作为数据源/目标，但没有任何表结构定义：
- `instruments`（证券信息）：symbol、name、type、prevClose、limitUpRatio 等
- `trading_calendar`（交易日历）：日期、是否交易日
- `trading_sessions`（交易时段）：开始/结束时间、对应阶段
- `users`（用户）：userId、username、passwordHash、role
- `accounts`（账户）：与内存 Account 的关系
- `orders_history`（历史订单）：与内存 Order 的映射
- `trades_history`（历史成交）
- `cashflow_history`（资金流水）
- `watchlists`（自选股）

无 Schema 意味着 AsyncPersistence 无法写、Query API 无法查、SOD 无法加载交易配置。

**修复建议**:
新增附录 C "H2 数据库 Schema"，至少包含：

```sql
-- 证券信息表
CREATE TABLE instruments (
    symbol VARCHAR(10) PRIMARY KEY,
    symbol_code INT NOT NULL,
    name VARCHAR(50) NOT NULL,
    type VARCHAR(10) NOT NULL,       -- STOCK/FUTURE/OPTION
    prev_close BIGINT NOT NULL,      -- 前收盘价(分)
    limit_up_ratio DOUBLE NOT NULL,  -- 涨停比例
    limit_down_ratio DOUBLE NOT NULL,-- 跌停比例
    lot_size BIGINT NOT NULL,        -- 每手数量
    min_price_increment BIGINT NOT NULL,
    commission_rate INT NOT NULL,    -- 佣金率(万分比)
    stamp_duty_rate INT DEFAULT 0,   -- 印花税率(万分比)，预留
    transfer_fee_rate INT DEFAULT 0, -- 过户费率(万分比)，预留
    update_time BIGINT NOT NULL
);

-- 交易日历表
CREATE TABLE trading_calendar (
    trade_date VARCHAR(8) PRIMARY KEY, -- YYYYMMDD
    is_trading_day BOOLEAN NOT NULL
);

-- 交易时段表
CREATE TABLE trading_sessions (
    id INT PRIMARY KEY,
    phase VARCHAR(30) NOT NULL,       -- CONTINUOUS_TRADING / CLOSED
    start_time TIME NOT NULL,
    end_time TIME NOT NULL
);

-- 用户表
CREATE TABLE users (
    user_id BIGINT PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) DEFAULT 'USER',
    created_at BIGINT NOT NULL
);

-- 历史成交表
CREATE TABLE trades_history (
    trade_id VARCHAR(50) PRIMARY KEY,
    order_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    symbol VARCHAR(10) NOT NULL,
    side VARCHAR(5) NOT NULL,
    price BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    commission BIGINT NOT NULL,
    trade_time BIGINT NOT NULL,
    trade_date VARCHAR(8) NOT NULL
);

-- 资金流水表
CREATE TABLE cashflow_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,         -- 变动金额(分)，正数=入账，负数=出账
    type VARCHAR(30) NOT NULL,      -- DEPOSIT/TRADE_BUY/TRADE_SELL/COMMISSION/FREEZE/UNFREEZE
    reason VARCHAR(100),
    trade_id VARCHAR(50),
    order_id BIGINT,
    created_at BIGINT NOT NULL
);
```

---

### P0-11: `/admin/deposit` 入金 API 未定义

**维度**: 完整性
**位置**: 3.3.1 节 DEPOSIT 事件类型, 1.1 节 "入金手工上账", CLAUDE.md API 表

**问题描述**:
入金是资金进入系统的**唯一入口**，DEPOSIT 事件在 account_input CQ 中定义且来源为"管理员操作"，但 Section 9 API 设计章节未包含此接口：
- 请求格式未定义（admin 认证？target userId？amount？reason？）
- 响应格式未定义（同步返回？异步？depositId？）
- 管理员权限模型未定义
- 入金金额限制未定义（最大限额？）

**修复建议**:
在 Section 9.2 新增接口定义：

```
POST /api/admin/deposit 入金

请求：
{
  "userId": "123",           // 目标用户ID
  "amount": "1000000",       // 入金金额(分)
  "reason": "手动入金"       // 备注
}

响应：
{
  "code": "0",
  "message": "success",
  "data": {
    "depositId": "9876543210987654321",
    "accountId": "456",
    "newBalance": "11000000",
    "newAvailable": "11000000"
  },
  "timestamp": "1716460800000",
  "traceId": "trace-uuid"
}

错误码：
- 2004: 用户不存在
- 1001: 金额无效（≤0 或超限）
- 4006: 系统关闭中
```

并在 Section 5 新增 5.x "入金流程" 小节，描述完整链路：
HTTP → account_input CQ (DEPOSIT) → AccountWorker (balance+=amount, available+=amount) → account_output CQ → WebSocket 推送。

---

## P1 级问题（需修复）— 共 22 项

---

### P1-12: Section 11.6 性能表中 ClearingWorker 幂等集合行残留

**维度**: 一致性
**位置**: 11.6 节 (行 2604)

**问题描述**:
行 2604 表中包含：
```
| ClearingWorker | 内存Set（最新10,000个）| ~0.5μs | ~500KB |
```
但 ClearingWorker 不做幂等检查（3.4.3 行 1079, 5.3 行 1536, 6.2 行 1685）。

**修复建议**:
删除 ClearingWorker 这一行，仅保留 MatchingEngine 和 AccountWorker。

---

### P1-13: Instrument 数据模型与 INSTRUMENT_INIT 事件字段不匹配

**维度**: 一致性
**位置**: 3.1.4 Instrument (行 411-413) vs 3.3.1 INSTRUMENT_INIT (行 654-666), 3.3.3 matching_output (行 739)

**问题描述**:
两类数据结构字段不对称：

| 字段 | 3.1.4 Instrument | 3.3.1 INSTRUMENT_INIT | 3.3.3 TRADE 事件 |
|------|:---:|:---:|:---:|
| commissionPerLot | ✅ long | ❌ | ❌ |
| commissionRate | ❌ | ✅ int | ✅ int（被引用） |
| stampDutyRate | ✅ long | ✅ int | ❌ |
| transferFeeRate | ✅ long | ✅ int | ❌ |

问题：
1. Instrument 内存模型缺少 `commissionRate`，但 ClearingWorker 需要它来计算佣金
2. INSTRUMENT_INIT 事件缺少 `commissionPerLot`，期货按手数计费无法传递
3. 类型不一致：Instrument 用 `long`，INSTRUMENT_INIT 用 `int`（万分比率 int 足够）

**修复建议**:
1. 3.1.4 Instrument 新增：`commissionRate | int | 佣金率（万分比）`
2. 3.3.1 INSTRUMENT_INIT 新增：`commissionPerLot | long | 每手佣金（分）`
3. 统一费率字段类型为 `int`（万分比场景下 int 完全足够）
4. 金额字段保持 `long`

---

### P1-14: InMemoryOrderStore 保留了已废弃的 CM 读写操作

**维度**: 一致性
**位置**: 3.2.3 节 InMemoryOrderStore 操作表 (行 614-615) vs 3.4.1 节 (行 869), 17.2 节 (行 3459)

**问题描述**:
InMemoryOrderStore 操作表列出：
```
| writeToChronicleMap | 快照时：遍历所有订单，转换为CM格式写入 |
| loadFromChronicleMap | 恢复时：从CM读取所有订单，加载到内存 |
```
但：
- 3.4.1 行 869: "AccountWorker快照不保存订单数据"
- 17.2 行 3459-3462: "AccountWorker快照不保存订单数据，减少快照大小和写入时间"
- 订单通过 CQ 重放和 MatchingEngine 快照恢复

**修复建议**:
从 InMemoryOrderStore 操作表中删除 `writeToChronicleMap` 和 `loadFromChronicleMap`。添加说明：
"订单不通过 CM 快照持久化。PENDING 订单通过 account_input CQ 重放恢复；活跃订单通过 MatchingEngine 快照恢复。"

---

### P1-15: INSTRUMENT_INIT 事件定义但无处理逻辑和写入流程

**维度**: 一致性
**位置**: 3.3.1 (行 625), 3.3.2 (行 684) vs 5.1 (行 1290-1302), 5.2 (行 1316-1337), 16.1 (行 2984)

**问题描述**:
INSTRUMENT_INIT 事件在 account_input 和 matching_input 的 eventType 枚举中定义，但：
- 5.1 AccountWorker 批量处理仅描述了 `NEW_ORDER` 的处理，无 `INSTRUMENT_INIT` 分支
- 5.2 MatchingEngine 批量处理仅描述了 `NEW_ORDER`/`CANCEL_ORDER`/`SNAPSHOT`，无 `INSTRUMENT_INIT` 分支
- 16.1 SOD 流程步骤 3 "从H2加载交易品种"但未提及写入 INSTRUMENT_INIT 到 CQ

这会导致 Instrument 数据无法进入 Worker 内存处理上下文。

**修复建议**:
1. 16.1 SOD 流程在步骤 3 后新增：
   ```
   4a. 遍历所有 Instrument，写入 INSTRUMENT_INIT 事件到 account_input CQ
   4b. 遍历所有 Instrument，写入 INSTRUMENT_INIT 事件到 matching_input CQ
   ```
2. 5.1 AccountWorker 处理新增 INSTRUMENT_INIT 分支：
   ```
   对于INSTRUMENT_INIT事件：
   - 将 Instrument 数据存入内存 InstrumentStore（供冻结金额计算使用）
   ```
3. 5.2 MatchingEngine 处理新增 INSTRUMENT_INIT 分支：
   ```
   对于INSTRUMENT_INIT事件：
   - 将 Instrument 数据存入内存（供涨跌停价格校验使用）
   ```
4. 3.2 节新增 InstrumentStore 组件定义

---

### P1-16: ORDER_UPDATE 处理含 PARTIAL_FILLED/FILLED 分支但仅生成 status=NEW

**维度**: 一致性
**位置**: 5.3 节 (行 1423-1429) vs 5.2 节 (行 1326), 3.3.3 (行 754)

**问题描述**:
Section 5.3 清算流程描述 ORDER_UPDATE 处理：
```
对于ORDER_UPDATE事件：
- 若status=NEW且当前为PENDING：PENDING → NEW
- 若status=PARTIAL_FILLED且当前为NEW/PARTIAL_FILLED：更新filledQuantity和remainingQty
- 若status=FILLED且当前为PARTIAL_FILLED：更新filledQuantity和remainingQty
```

但 Section 5.2 撮合流程只生成一种 ORDER_UPDATE：
```
写入ORDER_UPDATE事件（status=NEW）
```

3.3.3 行 754 也说 ORDER_UPDATE 的触发时机是"订单放入OrderBook时"。

PARTIAL_FILLED 和 FILLED 的状态变更实际上是通过 TRADE 事件路径处理的（5.3 行 1431-1529，更新 filledQuantity/remainingQty），而非通过 ORDER_UPDATE。含 PARTIAL_FILLED/FILLED 的处理分支是死代码。

**修复建议**:
**方案 A**（简化，推荐）：ORDER_UPDATE 仅处理 PENDING→NEW 转换
```
对于ORDER_UPDATE事件：
- 若status=NEW且当前为PENDING：更新订单状态 PENDING → NEW
- 更新 WebSocket 推送
```
删除 PARTIAL_FILLED 和 FILLED 的处理分支。

**方案 B**（扩展 ORDER_UPDATE 语义）：保持三个分支，但在 5.2 中补充 ORDER_UPDATE(PARTIAL_FILLED) 和 ORDER_UPDATE(FILLED) 的生成时机，与 TRADE 事件一起写入。这会让排序逻辑更复杂，不推荐。

---

### P1-17: 17.7.3 ClearingWorker 重放起始位置写为"从开始位置"

**维度**: 一致性
**位置**: 17.7 节步骤 3 (行 3739) vs 3.4.3 节 (行 1083-1087), 17.4 节 (行 3584-3587)

**问题描述**:
17.7 步骤 3 ClearingWorker 恢复：
```
重放matching_output CQ从开始位置
```
"从开始位置" = position 0 → 重放所有历史匹配输出。

但 3.4.3 行 1083-1087：
```
从MatchingEngine快照获取matching_output_last_index作为重放起始位置
matching_output_start_index = matchingEngineSnapshot.matching_output_last_index
```

17.4 行 3584-3587 同样从 `matching_output_last_index + 1` 开始。

从 position 0 重放会产生海量重复 SETTLEMENT 事件（虽被 AccountWorker 幂等拦截，但浪费处理时间和 CQ 空间）。

**修复建议**:
将 17.7 步骤 3 行 3739 改为：
```
重放matching_output CQ从matching_output_last_index+1开始
（matching_output_last_index 从 MatchingEngine 快照获取）
```

---

### P1-18: Section 7.2 恢复加载"orderCM → orderStore"

**维度**: 一致性
**位置**: 7.2 节步骤 2 (行 1758) vs 3.4.1 节 (行 869), 17.2 节 (行 3459)

**问题描述**:
7.2 恢复流程步骤 2：
```
加载订单：orderCM → orderStore
```
但订单不在 AccountWorker 快照中。与 P0-1 同属 7.2 节过期问题，单独列出于此因其涉及具体数据类型的丢失/重复。

**修复建议**:
删除此步骤。在修复 P0-1 重写 7.2 时，不包含订单的 CM 加载。订单恢复说明：PENDING 订单通过重放 account_input CQ 恢复，活跃订单通过 MatchingEngine 快照恢复。

---

### P1-19: 佣金计算产生小数（88.2 分）未指定取整方向

**维度**: 严谨性
**位置**: 5.3 节 (行 1506)

**问题描述**:
```
commission = 294000 × 3 / 10000 = 88.2分
```
分（fen）是最小货币单位，88.2 分为非法值。文档未指定：
- 截断（floor）：88 分
- 四舍五入：88 分
- 向上取整（ceil）：89 分

不同取整方式会影响后续退款计算和账户余额。

**修复建议**:
在 15.4 节佣金计算公式中明确指定取整方向：
```
佣金 = floor(成交金额 × 佣金率 / 10000)
```
推荐使用 floor（截断 / Java 整数除法），确保客户端不被多收费。

同时修正 5.3 节示例：
```
commission = 294000 × 3 / 10000 = 88分 (整数除法截断)
```

---

### P1-20: frozenAmountUsed 公式与独立佣金计算的不一致未文档化

**维度**: 严谨性
**位置**: 5.3 节 (行 1441-1472)

**问题描述**:
frozenAmountUsed 按比例包含预估佣金部分：
```
frozenAmountUsed = ceil(成交金额 × order.frozenAmount / 订单总价值)
```
其中 `order.frozenAmount` 包含佣金（如冻结=限价×数量+佣金）。

但 ClearingWorker 独立重新计算佣金：
```
commission = floor(成交金额 × 佣金率 / 10000)
```

两个路径计算出的佣金可能不同：
- 若成交价 < 限价：frozenAmountUsed 中的佣金比例偏大 → 退款多退（安全）
- 若成交价 > 限价（市价卖单可能）：frozenAmountUsed 中的佣金比例偏小 → 退款可能为负

文档未说明这种不一致是故意的（保守策略），还是需要修正的 bug。

**修复建议**:
在 5.3 节 frozenAmountUsed 公式后增加说明：
```
注意：frozenAmountUsed 中隐含的佣金比例可能与 ClearingWorker 独立计算的
实际佣金存在差异。
- 对于限价买单，成交价 ≤ 限价 → 隐含佣金 ≥ 实际佣金 → 退款 ≥ 0（安全边界）
- 这是有意为之的保守策略，确保买方冻结资金始终充足
- 市价买单冻结按涨停价计算，同样保证安全边界
```

---

### P1-21: 买方结算负退款可能产生负可用资金

**维度**: 严谨性
**位置**: 5.4 节 (行 1566)

**问题描述**:
```
若退还金额 < 0：available -= |退还金额|（佣金超出预估，从可用资金扣除）
```
如果 `available < |退款|`，则 available 变为负数。虽然 invariant 验证（行 557）会检测到 `available < 0` 并挂起账户，但这是一个本可预防的错误。

对于限价买单，成交价 ≤ 限价时不应出现负退款（见 P1-20）。但若出现计算 bug 或市价单场景，负退款可能导致账户异常挂起。

**修复建议**:
在负退款处理前增加防护：
```
若退还金额 < 0：
    若 available < |退还金额|：
        记录 P0 告警（包含 accountId, tradeId, frozenAmountUsed, actualAmount, commission）
        拒绝本次结算（不执行任何资金变动）
        标记账户为 SUSPENDED
        人工介入
    否则：
        available -= |退还金额|
```
同时文档化保证：在正确实现下，限价买单的退款始终 ≥ 0。

---

### P1-22: 状态机缺失 PENDING → REJECTED 转换

**维度**: 严谨性
**位置**: 3.1.4 节状态转换表 (行 457-471), 5.1 节 (行 1302)

**问题描述**:
5.1 行 1302 说明：
```
冻结失败：更新订单状态为REJECTED，WebSocket推送拒绝通知
```
即 AccountWorker 冻结失败（余额不足、账户状态异常）时，订单从 PENDING 变为 REJECTED。

但状态转换表仅定义了 HTTP 下单时"风控检查失败 → REJECTED"一条 REJECTED 路径，缺少 PENDING → REJECTED（余额不足）路径。

**修复建议**:
在状态转换表中新增一行：
```
| PENDING | AccountWorker冻结失败 | REJECTED | 余额不足/账户状态异常/持仓不足 | 冻结失败，订单被拒绝 |
```

---

### P1-23: UNFREEZE 事件未列入状态转换触发器

**维度**: 严谨性
**位置**: 3.1.4 节状态转换表 (行 457-471), 16.2 EOD 撤单流程 (行 3293-3316)

**问题描述**:
EOD 撤单对 PENDING 订单的路径为：
AccountWorker → CANCEL_ORDER → matching_input → MatchingEngine → CANCEL_REJECT(PENDING_ORDER_NOT_IN_ORDERBOOK) → ClearingWorker → UNFREEZE → account_settlement → AccountWorker → 更新状态为 CANCELLED

即最终状态转换是 **PENDING → CANCELLED**，触发事件是 **UNFREEZE**。

但状态转换表只列出了 PENDING → CANCEL_SUCCESS → CANCELLED（行 471），没有 UNFREEZE 作为触发器。

此外，FAK/FOK/市价单未成交部分也通过 CANCEL_SUCCESS → UNFREEZE 路径（这些订单在 ClearingWorker 生成 UNFREEZE，在 AccountWorker 处理 UNFREEZE 时切到 CANCELLED）。

**修复建议**:
在状态转换表中新增：
```
| PENDING | UNFREEZE | CANCELLED | EOD/用户撤单走PENDING_ORDER_NOT_IN_ORDERBOOK路径 | AccountWorker处理UNFREEZE时更新 |
| NEW | UNFREEZE | CANCELLED | 撤单解冻 | 同上 |
| PARTIAL_FILLED | UNFREEZE | CANCELLED | 撤单解冻 | 同上 |
```

---

### P1-24: processed_order_ids 来源快照索引错误

**维度**: 严谨性
**位置**: 3.4.1 节 (行 920-934)

**问题描述**:
恢复伪代码中 processed_order_ids 的生成：
```
matching_snapshot = findLatestMatchingSnapshot()
matching_input_snapshot_index = matching_snapshot.matching_input_index
events = matching_input_cq.readFrom(matching_input_snapshot_index)
```

使用 **MatchingEngine 快照中的 matching_input_index** 作为读取起点。

但 matching_input_index 记录的是 MatchingEngine 处理 matching_input CQ 的位置，不是 AccountWorker 写入 matching_input CQ 的位置。如果：
- AccountWorker 写入事件 101-145 到 matching_input
- MatchingEngine 只处理到 90
- MatchingEngine 快照的 matching_input_index = 90

则 processed_order_ids 从 90 开始读取，包含 90-100 的**无关旧订单**，但**遗漏** 101-145 中 MatchingEngine 尚未处理的订单。

由于 AccountWorker 快照已保存了 `matching_input_last_index`（行 863），应使用此字段。

**修复建议**:
将 processed_order_ids 的读取起点从 MatchingEngine 快照改为 AccountWorker 自己的快照：
```
matching_input_last_index = accountSnapshot.matching_input_last_index
events = matching_input_cq.readFrom(matching_input_last_index)
```

---

### P1-25: CANCEL_ORDER 事件重放不受 replay_mode 保护

**维度**: 严谨性
**位置**: 3.4.1 节 (行 954-970), 17.2 节 (行 3492-3501)

**问题描述**:
AccountWorker 恢复时仅有 NEW_ORDER 事件受 replay_mode 保护（检查 orderId 是否在 processed_order_ids 中）。CANCEL_ORDER 事件不受保护，会无条件重新写入 matching_input CQ，导致 MatchingEngine 收到重复撤单请求：
- 如果订单已在之前被成功撤销：MatchingEngine 收到第二次 CANCEL_ORDER，订单已不在 OrderBook → 生成 CANCEL_REJECT（可能进一步触发 UNFREEZE，造成重复解冻）
- 如果订单从未存在于 OrderBook（PENDING 状态）：每次重放都生成 CANCEL_REJECT + UNFREEZE

**修复建议**:
将 replay_mode 检查扩展到 CANCEL_ORDER：
```
if event.type == CANCEL_ORDER:
    // 始终执行撤单逻辑
    process_cancel(event)
    // 仅在未转发时才写入 matching_input
    if !(replay_mode && event.orderId in processed_order_ids):
        write_to_matching_input(event)
```

---

### P1-26: 市价单/FAK 未成交部分使用的 frozenAmount 已过时

**维度**: 严谨性
**位置**: 5.2 节 (行 1384-1406)

**问题描述**:
对于市价单/FAK，MatchingEngine 在同一批次中既生成 TRADE 事件（已成交部分）又生成 CANCEL_SUCCESS 事件（未成交部分）。CANCEL_SUCCESS 使用的 `unfreezeAmount = order.frozenAmount` 是 MatchingEngine 内存中的当前值。

但 ClearingWorker 处理 TRADE 事件时会递减 `order.frozenAmount`。由于 MatchingEngine 和 ClearingWorker 是异步的，CANCEL_SUCCESS 事件中的 frozenAmount 可能包含了**已成交部分的冻结金额**，导致 ClearingWorker 生成 UNFREEZE 时解冻金额过大。

**修复建议**:
MatchingEngine 在生成 CANCEL_SUCCESS 时应自行计算剩余冻结金额：
```
成交部分已用冻结金额 = Σ(frozenAmountUsed for each TRADE generated)
剩余冻结金额 = order.frozenAmount - 成交部分已用冻结金额
unfreezeAmount = max(0, 剩余冻结金额)
```

同时明确规定：在 MatchingEngine 同批次写入 matching_output CQ 时，同一订单的 TRADE 事件必须排在 CANCEL_SUCCESS 事件前面。ClearingWorker 按顺序处理，确保先递减 frozenAmount 再解冻。

---

### P1-27: 跨 CQ 写入无原子性保证

**维度**: 严谨性
**位置**: 2.2 节数据流图 (行 246-307), 5.1 节 (行 1304-1305)

**问题描述**:
AccountWorker 一个批次内同时写入 matching_input CQ 和 account_output CQ：
```
11. 批量写入matching_input CQ
12. 批量写入account_output CQ
```

如果 matching_input 写入成功但 account_output 写入失败（或反之），系统状态不一致：
- matching_input 已写入 → MatchingEngine 会撮合订单 → 但 account_output 事件丢失 → 前端收不到冻结通知
- account_output 已写入 → 前端显示冻结 → 但 matching_input 未写入 → 订单永远不会被撮合

文档未说明批量写入的原子性策略。

**修复建议**:
在 5.1 节新增原子性说明：
```
批量写入原子性保证：
1. matching_input 和 account_output 的写入在同一 try-catch 中
2. 若任一下游 CQ 写入失败：
   - 回滚当前批次所有内存操作（解冻已冻结的资金/持仓）
   - 所有 CompletableFuture 返回错误 5001
   - 记录 P0 告警
3. Chronicle Queue 不支持跨 CQ 事务，因此依赖回滚机制保证最终一致
```

---

### P1-28: PhaseManager 交易阶段枚举和转换图缺失

**维度**: 完整性
**位置**: 2.3 节, 3.3.1 节 PHASE_CHANGE 事件

**问题描述**:
PHASE_CHANGE 事件仅列出 `CLOSED / CONTINUOUS_TRADING` 两个阶段值。但 SOD 流程（16.1）和系统状态（6.4）暗示更多阶段相关状态：等待开盘（09:15→09:30）、午休（11:30→13:00）、收盘后、非交易日。这些是 CLOSED 的子状态，还是独立阶段？

缺失内容：
- 完整阶段枚举
- 各阶段允许的操作
- 阶段转换触发条件（时间驱动？SnapshotWorker 检测？）
- 各阶段拒绝下单时返回的错误码

**修复建议**:
在 16.1 节前或新增小节，定义完整的交易阶段状态机：

```
TradingPhase 枚举：
- PRE_OPEN (09:15-09:30, 系统等待开盘)
- CONTINUOUS_TRADING (09:30-11:30, 13:00-15:00)
- LUNCH_BREAK (11:30-13:00)
- CLOSED (15:00-次日09:15, 非交易日)

阶段转换：
    09:15 → PRE_OPEN
    09:30 → CONTINUOUS_TRADING
    11:30 → LUNCH_BREAK
    13:00 → CONTINUOUS_TRADING
    15:00 → CLOSED

阶段允许操作：
- PRE_OPEN: 仅查询，不允许下单
- CONTINUOUS_TRADING: 全部操作
- LUNCH_BREAK: 仅查询，不允许下单
- CLOSED: 仅查询，不允许下单

拒绝下单错误码：
- PRE_OPEN/LUNCH_BREAK/CLOSED: 4001 (交易阶段不允许)
- 非交易日: 4005 (非交易日/系统暂停)
```

---

### P1-29: RiskMonitor 轮询行为未定义

**维度**: 完整性
**位置**: 2.3 节, 4.1 节, 15.5 节

**问题描述**:
RiskMonitor 在组件表和线程表中列出（1 线程，"风险度监控、强平触发"），15.5 节定义了风险阈值和强平订单流，但缺失：
- 轮询间隔（每秒？每 5 秒？）
- 扫描范围（所有期货/期权账户？全部账户？）
- 检测到风险事件时的操作路径（直接写 account_input CQ 还是直接操作内存？）
- RiskMonitor 自身故障时的行为（线程死掉是否告警？是否影响交易？）
- 同时多个账户触发强平时的处理顺序

**修复建议**:
新增 15.5.1 "RiskMonitor 运行机制"：

```
RiskMonitor 运行参数：
- 轮询间隔：1 秒
- 扫描范围：所有 type=FUTURE/OPTION 的账户
- 检测逻辑：
  1. 计算 riskRatio = marginFrozen / (balance + unrealizedPnl)
  2. 若 riskRatio >= 1.0 (100%) → 触发强平
  3. 若 riskRatio >= 0.8 (80%) → WebSocket 发送风险预警
- 强平操作：写入 LIQUIDATION 订单到 account_input CQ
  （通过 CQ 而非直接操作内存，保证一致性和事件溯源）
- 故障处理：
  - RiskMonitor 线程异常退出 → 发送 P0 告警
  - 系统继续运行但风险监控降级
  - 人工检查后重启 RiskMonitor
```

---

### P1-30: account_output CQ → WebSocket 推送缺少消费组件

**维度**: 完整性
**位置**: 2.1 节架构图 (行 189-191)

**问题描述**:
架构图中 account_output CQ 标注为"用于WebSocket推送 (账户/持仓变动)"，但未命名任何消费该 CQ 的组件。线程模型中也没有对应的线程。Section 5.4 行 1608 和 1624 说 AccountWorker "写入account_output CQ"同时"WebSocket推送"，暗示 AccountWorker 直接做 WebSocket 推送，但 AccountWorker 是单线程，若直接做 Netty 写操作可能阻塞核心路径。

**修复建议**:
明确 account_output CQ 的消费模型：

**方案 A**（轻量，推荐）：在 AccountWorker 中内联 Netty 非阻塞写入。Netty 的 `channel.writeAndFlush()` 是非阻塞的，不会显著影响 AccountsWorker 延迟。在 5.1 和 5.4 中注明"WebSocket 推送使用 Netty 非阻塞写入，不阻塞 AccountWorker 主循环"。

**方案 B**（解耦）：新增 UpdatePublisher 组件（1 个线程），从 account_output CQ 读取事件并推送到 WebSocket。线程表中增加此项。优点是完全解耦，缺点是增加 1 个线程和端到端延迟。

---

### P1-31: K 线生成逻辑缺失

**维度**: 完整性
**位置**: 8.2 节, 9.3 节 K 线订阅

**问题描述**:
WebSocket API 支持 6 种 K 线周期的订阅和推送，但未描述：
- 哪个组件生成 K 线？（MatchingEngine？独立组件？）
- OHLCV 值如何从 TRADE 事件聚合？
- K 线状态存储在哪里（内存？）
- 启动时如何初始化（从 H2 历史回放？从当日首笔成交开始？）
- 无成交的周期是否推送平顶 K 线？

**修复建议**:
在 8.2 节新增 "8.3 K 线生成"：

```
K线生成机制：
- 组件：KlineAggregator（在 MatchingEngine 同一线程中）
- 数据源：每笔 TRADE 事件（从 matching_output CQ）
- 聚合逻辑：
  - 维护每个 symbol × interval 的当前 K 线状态
  - open = 周期内第一笔成交价
  - high = 周期内最高成交价
  - low = 周期内最低成交价
  - close = 周期内最后一笔成交价
  - volume = 周期内累计成交量
- 推送时机：每笔 TRADE 时推送更新后的当前 K 线
- 周期切换：到达周期边界时关闭当前 K 线，开启新 K 线
- 无成交周期：不推送 K 线（客户端自行判断空周期）
- 启动初始化：从 H2 历史数据加载当日已闭合的 K 线，当前未闭合周期从 matching_output CQ 增量构建
```

---

### P1-32: processed_settlement_ids 来源描述在 3 处不一致

**维度**: 完整性
**位置**: 6.2 节 (行 1681), 3.4.1 节 (行 944), 17.2 节 (行 3488)

**问题描述**:
processed_settlement_ids 的生成来源在三处描述不同：

| 位置 | 描述 |
|------|------|
| 6.2 行 1681 | "account_settlement.cq4 最新10,000个"（数量限制） |
| 3.4.1 行 944 | "从 matching_output_snapshot_index 读取 account_settlement.cq4"（从 MatchingEngine 快照位置） |
| 17.2 行 3488 | "从account_settlement CQ的account_settlement_index位置读取到最新位置"（从 AccountWorker 自己的快照位置） |

三个描述互不相同，实现者无法确定正确方案。最合理的方案是 17.2 的描述（从 AccountWorker 自己的 account_settlement_index 到最新）。

**修复建议**:
统一为：processed_settlement_ids 在 AccountWorker 恢复时，从 account_settlement CQ 的 account_settlement_index 位置读取到最新位置，提取所有 `tradeId_orderId`，用 LinkedHashSet 存储，保留最新 10,000 个。同步更新 3.4.1 和 6.2 中对应描述。

---

### P1-33: 撤单全链路流程分散在 5 个章节

**维度**: 完整性
**位置**: 2.2 节, 5.2 节, 5.3 节, 5.4 节, 9.2 节, 16.2 节

**问题描述**:
与下单流程有专门的 5.1 节"下单流程"不同，下单后的撤单全链路被拆散在多个章节中。实现者需要在 5 个地方拼凑信息：
- 5.2 MatchingEngine 的 CANCEL_ORDER 处理
- 5.3 ClearingWorker 的 CANCEL_SUCCESS/CANCEL_REJECT 处理
- 5.4 AccountWorker 的 UNFREEZE 处理
- 9.2 HTTP 撤单 API 和 WebSocket 推送
- 16.2 EOD 撤单特殊流程

缺失集中描述：
- PENDING 状态撤单的完整路径
- NEW/PARTIAL_FILLED 状态撤单的完整路径
- 撤单 HTTP 返回 vs WebSocket 推送之间的时序

**修复建议**:
新增 5.5 节"撤单全链路流程"，参照 5.1 的格式：

```
【阶段1：HTTP撤单请求】
1. 校验 orderId 存在且属于当前用户
2. 写入 CANCEL_ORDER 到 account_input CQ
3. HTTP 立即返回 PENDING_CANCEL（异步处理）

【阶段2：AccountWorker 处理】
4. AccountWorker 批量读取 CANCEL_ORDER 事件
5. 写入 CANCEL_ORDER 到 matching_input CQ

【阶段3：MatchingEngine 处理】
6. MatchingEngine 处理 CANCEL_ORDER：
   - 订单在 OrderBook 中 → 移除 → 生成 CANCEL_SUCCESS
   - 订单不在 OrderBook（PENDING/已成交/已撤销）→ 生成 CANCEL_REJECT
   
【阶段4：ClearingWorker 处理】
7. 对 CANCEL_SUCCESS：生成 UNFREEZE 事件写入 account_settlement CQ
8. 对 CANCEL_REJECT(PENDING_ORDER_NOT_IN_ORDERBOOK)：
   - 检查订单状态，若 PENDING/NEW → 生成 UNFREEZE
   - 其他状态 → 忽略

【阶段5：AccountWorker 处理UNFREEZE】
9. 解冻资金/持仓
10. 更新订单状态为 CANCELLED
11. WebSocket 推送 order_update(status=CANCELLED)
```

---

## P2 级问题（建议优化）— 共 17 项

---

### P2-34: MatchingEngine 快照文件结构含嵌入式 CRC32 字段

**维度**: 一致性
**位置**: 3.4.2 节 (行 998)

**问题描述**:
MatchingEngine 快照文件结构中：
```
| checksum | long | CRC32校验和 |
```
但 3.4.1 行 875 声明"checksum统一使用MD5：快照文件不内嵌CRC32"。外部 .checksum 文件（行 1010）又提供了 MD5 校验。

MatchingEngine 快照同时有内嵌 CRC32 和外挂 MD5，与 AccountWorker 快照（仅外挂 MD5）不对称。内嵌 CRC32 是冗余的。

**修复建议**:
从 MatchingEngine 快照文件结构中删除嵌入式 `checksum` 字段。仅保留外部 `.checksum` 文件（MD5），与 AccountWorker 快照一致。

---

### P2-35: account_output CQ 缺少 marginFrozenDelta 字段

**维度**: 一致性
**位置**: 3.3.5 节 (行 822-834) vs 5.4 节 (行 1598)

**问题描述**:
5.4 行 1598 卖方结算可能修改 `marginFrozen`（期货/期权账户）：
```
若有保证金冻结：marginFrozen -= 保证金
frozen -= 保证金
```

但 account_output CQ 只有 `frozenDelta`（包含保证金变化），无 `marginFrozenDelta` 字段。前端无法区分保证金变动和委托冻结变动。

**修复建议**:
在 account_output CQ 字段表中新增：
```
| marginFrozenDelta | long | 保证金冻结变动 |
```

---

### P2-36: 印花税率字段在模型中有定义但 API 注释"不涉及"

**维度**: 一致性
**位置**: 3.1.4 节 (行 412) vs 9.2 节 Trade Update API (行 2344)

**问题描述**:
Instrument 模型和 INSTRUMENT_INIT 都包含 `stampDutyRate`（印花税率，仅卖方收取），但 9.2 节行 2344 注释：
```
注意：本系统仅计算手续费，不涉及印花税逻辑。
```

存在矛盾：
- 如果确实"不涉及"，stampDutyRate 和 transferFeeRate 为死字段
- 如果"涉及"，需更新佣金计算逻辑和 API 注释

**修复建议**:
选择并统一：

**方案 A**（推荐当前阶段）：保留 stampDutyRate/transferFeeRate 字段但标注为预留
- 3.1.4 Instrument 注释改为：`stampDutyRate | int | 印花税率（万分比），预留字段，当前版本为 0`
- 9.2 行 2344 注释改为：`当前版本仅计算券商佣金（万分之三），印花税和过户费为预留字段，后续版本启用`

**方案 B**：删除这两个字段，待需要时再加（遵循"简单优先"原则）

---

### P2-37: commissionPerLot 仅定义但从未被任何计算使用

**维度**: 严谨性
**位置**: 3.1.4 节 (行 411), 15.4 节 (行 2885-2892)

**问题描述**:
Instrument 有 `commissionPerLot | long | 每手佣金（分）`。15.4 佣金计算：
- 股票：万分之三按成交金额比例
- 期货："按手数计算：commissionPerLot × 手数"

但 INSTRUMENT_INIT 事件不含 commissionPerLot（见 P1-13），且文档正文中没有任何 freezing 或 settlement 逻辑引用 commissionPerLot。当前系统聚焦 A 股交易。

**修复建议**:
保留 commissionPerLot 字段，但在 15.4 节显式标注：
```
期货：commissionPerLot × lots（期货品种暂未上线，字段为预留）
```
如果追求简单，可以删除此字段待期货上线时再加。

---

### P2-38: FOK 订单不满足条件时的状态转换路径描述不完整

**维度**: 严谨性
**位置**: 5.2 节 (行 1387-1409)

**问题描述**:
FOK 不能成交时，MatchingEngine 生成 CANCEL_SUCCESS（而非 CANCEL_REJECT），但：
- FOK 从未收到 ORDER_UPDATE(NEW)（它从未进入 OrderBook）
- ClearingWorker 处理 CANCEL_SUCCESS 生成 UNFREEZE → AccountWorker 设置 CANCELLED
- 实际状态路径：PENDING → (通过 UNFREEZE) → CANCELLED

这与 LIMIT 订单的取消路径不同（LIMIT 订单先 NEW，再 CANCEL_SUCCESS）。文档暗示但未明确说明 FOK 可以跳过 NEW 状态。

**修复建议**:
在 5.2 节 FOK 撮合规则中补充：
```
FOK订单特殊状态路径：
- FOK全部成交：PENDING → NEW(立即) → FILLED（正常路径）
- FOK无法成交：PENDING → （跳过NEW，MatchingEngine直接生成CANCEL_SUCCESS） → CANCELLED
  这种跳过 NEW 状态的路径也适用于 EOD 撤单场景中对 PENDING 订单的处理
```

---

### P2-39: processed_trade_ids 10 分钟时间窗口在深度恢复场景可能不足

**维度**: 严谨性
**位置**: 6.2 节 (行 1689-1693)

**问题描述**:
processed_trade_ids 仅保留近 10 分钟内的 trade_id。正常运行时（快照每 60 秒）足够。但如果连续 3 个快照损坏需要回退到更早的快照（如 15 分钟前），则会有一部分 trade_id 超出 10 分钟窗口而被遗漏，导致这些 SETTLEMENT 事件重放时无幂等保护。

**修复建议**:
增加兜底逻辑：
```
生成 processed_trade_ids 时：
1. 计算恢复范围 = latest - account_settlement_index
2. 若恢复范围的时间跨度 > 10 分钟：
   - 读取恢复范围内的全部 trade_id（而非仅近 10 分钟）
   - 若数量超过 MAX_SIZE，记录告警并使用数量限制 + 时间范围两者中较大者
3. 若恢复范围的时间跨度 ≤ 10 分钟：
   - 使用现有 10 分钟时间窗口
```

---

### P2-40: IdempotencyService 并发原子性未指定

**维度**: 严谨性
**位置**: 5.1 节 (行 1258-1261), 6.2 节

**问题描述**:
HTTP 幂等检查流程：
```
1. 若idempotencyKey已存在且状态为CONFIRMED，返回已有orderId
2. 若状态为PROCESSING，返回错误1003
3. 否则创建新条目，状态设为PROCESSING
```

这个"检查-创建"模式不是原子的。两个并发 HTTP 请求使用同一 idempotencyKey 可能同时通过步骤 3 的检查（都发现 key 不存在），分别创建两个订单。

**修复建议**:
在 6.2 节 IdempotencyService 的 `tryAcquire` 方法描述中显式指定：
```
tryAcquire 使用 ConcurrentHashMap.computeIfAbsent 实现原子操作：
- key 不存在 → 创建新条目（status=PROCESSING），返回 true
- key 已存在 → 返回 false
调用方根据返回值决定是否继续下单流程
```

---

### P2-41: EOD 动态等待公式粗糙

**维度**: 严谨性
**位置**: 16.2 节 (行 3135)

**问题描述**:
```
waitTime = max(10000, pendingCount × 100)
```
当 pendingCount=1000 时 waitTime=110 秒。但撤单处理速度取决于是否有新订单混入队列、队列深度、CQ 积压等因素。110 秒可能不足或过度。

**修复建议**:
改为轮询模式：
```
等待撤单处理完成：
1. 计算最大等待时间：maxWait = max(10000, pendingCount × 100)
2. 轮询检查：每 500ms 检查 remainingPendingOrders
3. 退出条件（满足任一）：
   a. remainingPendingOrders == 0（全部处理完毕）
   b. elapsed > maxWait（超时）
4. 超时后：记录告警，执行快照（保留未处理事件在 CQ 中供下次恢复）
```
此方式在订单少时快速退出，在订单多时按实际进度判断而非固定等待。

---

### P2-42: FAK/MARKET 全部成交后应标记 FILLED 而非生成 CANCEL_SUCCESS

**维度**: 严谨性
**位置**: 5.2 节 (行 1385, 1346)

**问题描述**:
市价单/FAK 撮合规则说"未成交部分立即撤销"。但如果全部成交（remainingQty=0），则不应生成 CANCEL_SUCCESS。

**修复建议**:
在 5.2 节市价单/FAK 撮合规则中增加判断条件：
```
市价单/FAK 撮合后处理：
if remainingQty == 0:
    订单状态 = FILLED，不生成 CANCEL_SUCCESS
else:
    生成 CANCEL_SUCCESS，unfreezeAmount = 剩余冻结金额，unfreezeQty = remainingQty
```

---

### P2-43: 快照超时可累积间隙扩大 RPO

**维度**: 严谨性
**位置**: 17.5 节 (行 3632-3637)

**问题描述**:
快照超时后 SnapshotWorker 继续写入下一个 SNAPSHOT 事件。如果连续超时（例如 Worker 积压严重），有效快照之间的间隔可能远超 60 秒，RPO 随之增大。

**修复建议**:
增加最大间隙保护：
```
快照超时处理策略：
1. 连续超时计数：consecutiveTimeoutCount++
2. 若 consecutiveTimeoutCount == 1：
   - 记录 P1 告警
   - 跳过下一快照周期（等 2×interval），给 Worker 追赶时间
3. 若 consecutiveTimeoutCount == 3：
   - 升级为 P0 告警
   - 触发同步快照：暂停 HTTP 请求接收，强制 Worker 处理完当前队列后快照
   - 同步快照完成后再恢复 HTTP 接收
4. 若快照成功，consecutiveTimeoutCount 重置为 0
```

---

### P2-44: PENDING 订单的并发 NEW_ORDER + CANCEL_ORDER 可能导致订单在 UNFREEZE 后进入 OrderBook

**维度**: 严谨性
**位置**: 5.2 节 (行 1328-1333), 3.1.4 节 (行 463-476)

**问题描述**:
用户下单后立即撤单（PENDING 状态）。AccountWorker 将 NEW_ORDER 和 CANCEL_ORDER 顺序写入 matching_input CQ。MatchingEngine 按序处理：

**场景 A**（正常）：NEW_ORDER 先到 → 入 OrderBook → CANCEL_ORDER 后到 → 正常撤销 ✓

**场景 B**（问题）：虽然写入顺序是 NEW_ORDER 先 CANCEL_ORDER 后，但如果 NEW_ORDER 的处理被同一批次中的其他 symbol 订单延迟，而 CANCEL_ORDER 在下一批次中先被处理（这不可能，因为 MatchingEngine 单线程串行处理 matching_input CQ）。等等，单线程保证顺序。所以场景 B 实际不会发生。

但边界情况：如果 EOD 的 CANCEL_ORDER 和用户下单的 NEW_ORDER 同时进入匹配，它们进入 matching_input 的顺序是不确定的。EOD 的 CANCEL_ORDER 可能在 NEW_ORDER 之前。

**修复建议**:
最简单的方案：AccountWorker 在处理 PENDING 状态订单的 CANCEL_ORDER 时，直接解冻并设置 CANCELLED，不再转发到 matching_input。避免 PENDING 状态订单绕一大圈走 matching_input → MatchingEngine → CANCEL_REJECT → ClearingWorker → UNFREEZE → AccountWorker 的路径。具体：

```
AccountWorker 处理 CANCEL_ORDER：
if order.status == PENDING:
    // 直接处理，不转发到 matching_input
    unfreeze(order)
    order.status = CANCELLED
    write account_output (UNFREEZE)
else:
    // 转发到 matching_input，走正常撤单流程
    write cancel_event to matching_input
```

这样同时避免了 P2-44 和 P1-25 的问题。

---

### P2-45: frozenAmountUsed 公式对市价单分母为 0

**维度**: 严谨性
**位置**: 3.3.4 节 (行 809) vs 5.2 节 (行 1368)

**问题描述**:
3.3.4 行 809：
```
frozenAmountUsed = ceil(成交金额 × order.frozenAmount / (order.price × order.quantity))
```
市价单 `order.price = 0`（行 1368："用户端传入 price=0"），分母为 0。

5.3 节（行 1461-1463）已经意识到了这个问题并给出了正确处理（市价单分母用涨停价×数量），但 3.3.4 的公式没有更新。

**修复建议**:
将 3.3.4 行 809 的公式扩展为：
```
买方 frozenAmountUsed 计算：
- 限价单：frozenAmountUsed = ceil(成交金额 × order.frozenAmount / (order.price × order.quantity))
- 市价买单：frozenAmountUsed = ceil(成交金额 × order.frozenAmount / (maxPrice × order.quantity))
  其中 maxPrice = 涨停价（AccountWorker 冻结时设置）
```

---

### P2-46: 卖方结算时持仓移除缺少 invariant 检查

**维度**: 严谨性
**位置**: 5.4 节 (行 1594-1595)

**问题描述**:
卖方结算中：
```
position.quantity -= quantity
若quantity变为0：移除持仓
```
如果 invariant 被破坏（bug、数据损坏），quantity 可能为 0 但 available/frozen/t1Locked 中仍有非零值，此时移除持仓会导致这些值丢失。

**修复建议**:
移除持仓前加断言：
```
if position.quantity == 0:
    assert available + frozen + t1Locked == 0 : "持仓移除前invariant检查失败"
    if (available + frozen + t1Locked != 0):
        记录 P0 告警（accountId, symbol, available, frozen, t1Locked）
        暂停账户（SUSPENDED）
        不移除持仓（保留供人工审查）
    else:
        移除持仓
```

---

### P2-47: archive_index.dat 格式未定义

**维度**: 完整性
**位置**: 16.2 节 (行 3193)

**问题描述**:
归档步骤中提到"更新归档索引文件：data/archive/archive_index.dat"，但与 archive_manifest.dat（行 3208 有完整字段定义）不同，archive_index.dat 没有任何格式定义。不清楚它索引什么内容、与 archive_manifest.dat 是什么关系。

**修复建议**:
**方案 A**（推荐）：删除 archive_index.dat 引用。archive_manifest.dat 已足够（每个归档日期一个，包含完整文件清单）。SOD 时遍历 `data/archive/` 目录下各日期的 manifest 即可获知所有归档信息。

**方案 B**：如果确实需要 archive_index.dat，定义其格式：
```
archive_index.dat 格式：
| 字段 | 类型 | 说明 |
|------|------|------|
| archive_count | int | 已归档日期数量 |
| archives[] | ArchiveEntry | 各日期归档元数据 |

ArchiveEntry：
| archiveDate | String | 归档日期（YYYYMMDD） |
| manifestPath | String | 清单文件路径 |
| fileCount | int | 归档文件总数 |
| status | enum | COMPLETE/PARTIAL |
```

---

### P2-48: WebSocket cancel_result 消息类型未列入正式枚举

**维度**: 完整性
**位置**: 9.2 节 (行 1905) vs 9.3 节 (行 2215-2223, 2307-2322)

**问题描述**:
9.2 节撤单失败推送示例使用了 `"type": "cancel_result"`：
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

但 `cancel_result` 未在 9.3 节的 WebSocket 消息类型枚举或 messageId 适用表中出现。同时，撤单成功使用 `order_update`（行 1888），撤单失败却用 `cancel_result`，不一致。

**修复建议**:
统一为一种方式：

**方案 A**（推荐）：撤单失败也走 `order_update`
```json
{
  "type": "order_update",
  "data": {
    "orderId": "1234567890123456789",
    "status": "CANCEL_REJECTED",
    "rejectReason": "ORDER_ALREADY_FILLED",
    "timestamp": "1716460800000"
  }
}
```
在 order_update 的 status 枚举中增加 CANCEL_REJECTED。

**方案 B**：保留 `cancel_result` 作为独立类型，但需在 9.3 节 Channel 枚举和 messageId 表中正式注册。

---

## 修复优先级建议

### 第一轮修复（阻塞开发）— 11 项 P0
全部 P0 问题必须在编码开始前解决。特别关注：
- **P0-6（数据丢失）**和 **P0-7（公式错误）** 直接影响数据正确性
- **P0-1（7.2 节过期）** 是整个恢复系统的基础设计

### 第二轮修复（避免逻辑错误）— 22 项 P1
P1 问题会导致实现困难、逻辑错误或歧义。按依赖关系排序：
1. **P1-24/25/26/27**（恢复/幂等逻辑缺陷）
2. **P1-13/15**（数据模型与事件不匹配）
3. **P1-28/29**（组件行为缺失）
4. 其余 P1

### 第三轮优化 — 17 项 P2
P2 问题影响清晰度和边界情况处理，可在前两轮完成后集中处理。

---

*报告由 3 个独立审查 Agent 自动生成，2026-06-02*
