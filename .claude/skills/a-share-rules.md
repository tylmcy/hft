# A股交易规则 Skill

当用户需要实现符合A股交易规则的系统时使用此skill。

## 触发场景
- 交易时段控制
- 涨跌停价格计算
- 集合竞价逻辑
- 交易规则校验

## 核心原则

### 1. 交易时段

```java
public enum TradingSession {
    PRE_MARKET("09:15-09:25", "集合竞价"),
    MORNING("09:30-11:30", "上午连续竞价"),
    LUNCH_BREAK("11:30-13:00", "午间休市"),
    AFTERNOON("13:00-15:00", "下午连续竞价");

    public boolean isTradingTime(LocalTime time) {
        // 判断是否在交易时段
    }
}
```

### 2. 涨跌停规则

```java
public class PriceLimitRule {
    // 主板: ±10%
    // 创业板/科创板: ±20%
    // ST股票: ±5%
    // 北交所: ±30%

    public long calculateUpperLimit(String symbol, long prevClose) {
        double limit = getPriceLimitRatio(symbol);
        return (long)(prevClose * (1 + limit));
    }

    public long calculateLowerLimit(String symbol, long prevClose) {
        double limit = getPriceLimitRatio(symbol);
        return (long)(prevClose * (1 - limit));
    }

    private double getPriceLimitRatio(String symbol) {
        if (symbol.startsWith("ST")) return 0.05;
        if (symbol.startsWith("300")) return 0.20;  // 创业板
        if (symbol.startsWith("688")) return 0.20;  // 科创板
        if (symbol.startsWith("8")) return 0.30;    // 北交所
        return 0.10;  // 主板
    }
}
```

### 3. 最小价格变动单位

```java
public class TickSizeRule {
    // 股票: 0.01元
    // 基金: 0.001元
    // 债券: 0.001元

    public long roundToTickSize(long price, String instrumentType) {
        long tickSize = getTickSize(instrumentType);
        return (price / tickSize) * tickSize;
    }
}
```

### 4. 集合竞价规则

```java
public class CallAuction {
    // 09:15-09:25 集合竞价
    // 09:15-09:20 可申报可撤销
    // 09:20-09:25 可申报不可撤销

    public long calculateOpenPrice(List<Order> orders) {
        // 1. 计算虚拟撮合价格
        // 2. 选择成交量最大的价格
        // 3. 如有多个价格，选择最接近前收盘价的价格
        // 4. 确定开盘价
    }
}
```

### 5. 交易单位规则

```java
public class TradingUnitRule {
    // 股票: 100股 = 1手
    // 买入必须是100股的整数倍
    // 卖出可以零股(不足100股的部分)

    public boolean validateOrder(Order order) {
        if (order.getDirection() == Direction.BUY) {
            return order.getQuantity() % 100 == 0;
        } else {
            return order.getQuantity() > 0;
        }
    }
}
```

### 6. T+1 规则

```java
public class SettlementRule {
    // A股: T+1 交割
    // 当日买入的股票，次日才能卖出

    public boolean canSell(Account account, String symbol) {
        Position position = account.getPosition(symbol);
        // 检查持仓是否为T+1前买入
        return position.getAvailableQty() > 0;
    }
}
```

### 7. 融资融券规则

```java
public class MarginRule {
    // 融资买入: 借钱买股票
    // 融券卖出: 借股票卖出

    public boolean checkMarginBuy(Account account, long amount) {
        // 检查保证金充足性
        // 维持担保比例 >= 130%
    }

    public boolean checkShortSell(Account account, String symbol) {
        // 检查是否在可融券标的范围
        // 检查保证金充足性
    }
}
```

## 输出要求
- 完整的交易规则校验代码
- 各类股票的规则配置
- 集合竞价算法实现
- 异常情况处理方案
