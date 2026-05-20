# 撮合引擎设计 Skill

当用户需要设计或实现股票/期货/期权撮合引擎时使用此skill。

## 触发场景
- 撮合算法实现
- 订单簿设计
- 价格优先时间优先逻辑
- 支持多种订单类型

## 核心原则

### 0. 时间字段规范

**重要**: 所有时间字段使用 `long` 类型存储毫秒级时间戳

```java
// 订单时间戳
long timestamp = System.currentTimeMillis();

// 成交时间
trade.setTradeTime(System.currentTimeMillis());
```

### 1. 订单簿数据结构
```java
// 价格优先时间优先的订单簿
public class OrderBook {
    private final TreeMap<Long, LinkedList<Order>> bids; // 买单，价格降序
    private final TreeMap<Long, LinkedList<Order>> asks; // 卖单，价格升序

    // 使用 Long2ObjectHashMap 优化性能
    private final Long2ObjectHashMap<PriceLevel> priceLevels;
}
```

### 2. 撮合算法
```java
public MatchResult match(Order incomingOrder) {
    // 1. 价格检查
    // 2. 数量匹配
    // 3. 生成成交记录 (时间戳: System.currentTimeMillis())
    // 4. 更新订单簿
    // 5. 发送成交事件
}
```

### 3. 订单数据结构
```java
public class Order {
    long orderId;
    long accountId;
    String symbol;
    byte direction;      // 0=买入, 1=卖出
    byte orderType;      // 0=限价, 1=市价, 2=FAK, 3=FOK
    long price;
    long quantity;
    long filledQty;
    byte status;
    long createTime;     // 时间戳 (毫秒)
    long updateTime;     // 时间戳 (毫秒)
}

public class Trade {
    long tradeId;
    long buyOrderId;
    long sellOrderId;
    String symbol;
    long price;
    long quantity;
    long tradeTime;      // 时间戳 (毫秒)
}
```

### 4. 支持的订单类型
- **限价单 (Limit Order)**: 指定价格和数量
- **市价单 (Market Order)**: 立即成交
- **FAK (Fill and Kill)**: 部分成交后撤销
- **FOK (Fill or Kill)**: 全部成交或撤销
- **冰山单 (Iceberg)**: 隐藏部分数量

### 5. A股特殊规则
- 涨跌停价格限制
- 集合竞价机制
- 连续竞价时段
- 最小价格变动单位 (tick size)

### 6. 期货期权扩展
- 期货: 支持开仓/平仓方向
- 期权: Call/Put类型，行权价格，到期日

## 性能指标
- 撮合延迟: < 10μs
- 订单簿更新: < 5μs
- 吞吐量: > 100,000 TPS

## 输出要求
- 完整的订单簿实现代码
- 撮合算法流程图
- 性能测试方案
- 边界条件处理
