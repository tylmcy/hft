# H2 数据库持久化 Skill

当用户需要设计和实现基于 H2 的异步持久化方案时使用此skill。

## 触发场景
- 数据库表结构设计
- 异步写入优化
- 数据查询实现
- 数据归档策略

## 核心原则

### 0. 时间字段设计规范

**重要**: 所有时间字段统一使用 `BIGINT` 存储毫秒级时间戳 (epoch milliseconds)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        时间字段设计规范                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  【后端存储】                                                                │
│  • 数据类型: BIGINT (Java long)                                             │
│  • 存储格式: 毫秒级时间戳 (epoch milliseconds)                               │
│  • 示例: 1716220800000 表示 2024-05-20 16:00:00 UTC                        │
│  • 获取方式: System.currentTimeMillis() 或 Instant.toEpochMilli()           │
│                                                                             │
│  【前端转换】                                                                │
│  • 接收: JavaScript number 类型                                             │
│  • 转换: new Date(timestamp).toLocaleString()                               │
│  • 格式化: dayjs(timestamp).format('YYYY-MM-DD HH:mm:ss')                  │
│  • 时区: 自动转换为用户本地时区                                              │
│                                                                             │
│  【优势】                                                                    │
│  ✓ 跨时区兼容: 时间戳是 UTC，前端按本地时区显示                              │
│  ✓ 比较高效: 数值比较，支持索引排序                                          │
│  ✓ 存储紧凑: BIGINT 8字节 vs TIMESTAMP 字符串                               │
│  ✓ 计算方便: 直接加减毫秒数                                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1. 表结构设计

#### 订单表
```sql
CREATE TABLE orders (
    order_id BIGINT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    direction TINYINT NOT NULL,        -- 0=买入, 1=卖出
    order_type TINYINT NOT NULL,       -- 0=限价, 1=市价, 2=FAK, 3=FOK
    price BIGINT NOT NULL,             -- 价格放大10000倍存储
    quantity BIGINT NOT NULL,
    filled_qty BIGINT DEFAULT 0,
    status TINYINT NOT NULL,           -- 0=待成交, 1=部分成交, 2=全部成交, 3=已撤销
    create_time BIGINT NOT NULL,       -- 时间戳 (毫秒)
    update_time BIGINT NOT NULL,       -- 时间戳 (毫秒)
    INDEX idx_account_time (account_id, create_time),
    INDEX idx_symbol_time (symbol, create_time),
    INDEX idx_cancelled (status, update_time)  -- 定时清理用
);
```

#### 成交表
```sql
CREATE TABLE trades (
    trade_id BIGINT PRIMARY KEY,
    buy_order_id BIGINT NOT NULL,
    sell_order_id BIGINT NOT NULL,
    buy_account_id BIGINT NOT NULL,
    sell_account_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    price BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    commission BIGINT DEFAULT 0,       -- 手续费
    trade_time BIGINT NOT NULL,        -- 时间戳 (毫秒)
    INDEX idx_trade_time (trade_time),
    INDEX idx_symbol_time (symbol, trade_time),
    INDEX idx_buy_account (buy_account_id, trade_time),
    INDEX idx_sell_account (sell_account_id, trade_time)
);
```

#### 账户表
```sql
CREATE TABLE accounts (
    account_id BIGINT PRIMARY KEY,
    balance BIGINT NOT NULL,           -- 总资产
    available BIGINT NOT NULL,         -- 可用资金
    frozen BIGINT NOT NULL,            -- 冻结资金
    margin BIGINT DEFAULT 0,           -- 保证金占用
    update_time BIGINT NOT NULL,       -- 时间戳 (毫秒)
    INDEX idx_update_time (update_time)
);
```

#### 持仓表
```sql
CREATE TABLE positions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    quantity BIGINT NOT NULL,          -- 总持仓
    available BIGINT NOT NULL,         -- 可用持仓 (T+1)
    frozen BIGINT DEFAULT 0,           -- 冻结持仓
    avg_price BIGINT NOT NULL,         -- 成本价
    update_time BIGINT NOT NULL,       -- 时间戳 (毫秒)
    UNIQUE KEY uk_account_symbol (account_id, symbol),
    INDEX idx_update_time (update_time)
);
```

#### 资金流水表
```sql
CREATE TABLE fund_flows (
    flow_id BIGINT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,            -- 变动金额
    flow_type TINYINT NOT NULL,        -- 1=入金, 2=出金, 3=买入, 4=卖出, 5=手续费
    ref_id BIGINT,                     -- 关联订单/成交ID
    balance_before BIGINT NOT NULL,    -- 变动前余额
    balance_after BIGINT NOT NULL,     -- 变动后余额
    create_time BIGINT NOT NULL,       -- 时间戳 (毫秒)
    INDEX idx_account_time (account_id, create_time),
    INDEX idx_ref (flow_type, ref_id)
);
```

### 2. 异步批量写入
```java
public class BatchPersistence {
    private final BlockingQueue<PersistTask> queue;
    private final H2ConnectionPool pool;
    private final int batchSize = 1000;

    public void start() {
        // 启动后台写入线程
        executor.submit(() -> {
            List<PersistTask> batch = new ArrayList<>();
            while (true) {
                queue.drainTo(batch, batchSize);
                if (!batch.isEmpty()) {
                    batchInsert(batch);
                    batch.clear();
                }
            }
        });
    }

    // 时间戳获取
    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
```

### 3. 定时清理任务
```java
/**
 * 定时清理已撤销订单 - 每分钟执行
 * 删除2小时前已撤销的订单
 */
public class OrderCleanupTask {

    private final JdbcTemplate jdbcTemplate;
    private final ScheduledExecutorService scheduler;

    public void start() {
        scheduler.scheduleAtFixedRate(
            this::cleanupCancelledOrders,
            60000, 60000, TimeUnit.MILLISECONDS
        );
    }

    private void cleanupCancelledOrders() {
        long twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000;
        String sql = "DELETE FROM orders WHERE status = 3 AND update_time < ?";
        int deleted = jdbcTemplate.update(sql, twoHoursAgo);
        if (deleted > 0) {
            log.info("Cleaned up {} cancelled orders older than 2 hours", deleted);
        }
    }
}
```

### 4. H2 配置 (磁盘持久化模式)
```java
// 磁盘持久化配置
String url = "jdbc:h2:./data/h2/trading;" +
    "MODE=MySQL;" +
    "DB_CLOSE_ON_EXIT=FALSE;" +
    "MV_STORE=true;" +
    "MAX_MEMORY_ROWS=100000;" +
    "CACHE_SIZE=65536";
```

### 5. 数据归档
- 按日期分区存储
- 定期归档历史数据
- 提供数据恢复机制

## 输出要求
- 完整的 DDL 脚本 (时间字段使用 BIGINT)
- 异步持久化实现代码
- 性能测试报告
- 数据迁移方案
