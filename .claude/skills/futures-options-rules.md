# 期货期权交易规则 Skill

当用户需要实现期货和期权交易功能时使用此skill。

## 触发场景
- 期货合约设计
- 期权合约设计
- 保证金计算
- 行权交割逻辑

## 核心原则

### 1. 期货合约设计

```java
public class FuturesContract {
    private String contractCode;      // 合约代码: IF2401
    private String underlying;        // 标的: 沪深300
    private long contractSize;        // 合约乘数: 300
    private long tickSize;            // 最小变动价位: 0.2
    private LocalDate deliveryDate;   // 交割日
    private DeliveryType deliveryType; // 现金/实物交割

    // 计算合约价值
    public long calculateValue(long price) {
        return price * contractSize;
    }
}
```

### 2. 期货保证金计算

```java
public class FuturesMargin {
    // 保证金 = 合约价值 × 保证金比例
    public long calculateMargin(FuturesContract contract, long price) {
        long value = contract.calculateValue(price);
        return (long)(value * getMarginRatio(contract));
    }

    // 不同合约的保证金比例
    private double getMarginRatio(FuturesContract contract) {
        // 股指期货: 12%
        // 商品期货: 5-15%
        // 国债期货: 2-3%
    }

    // 维持保证金检查
    public boolean checkMaintenanceMargin(Account account) {
        // 维持担保比例 >= 100%
    }
}
```

### 3. 期权合约设计

```java
public class OptionContract {
    private String optionCode;        // 期权代码: 10003720
    private String underlying;        // 标的: 510050
    private OptionType optionType;    // Call/Put
    private long strikePrice;         // 行权价
    private LocalDate expiryDate;     // 到期日
    private long contractSize;        // 合约单位: 10000
    private ExerciseType exerciseType; // 欧式/美式

    public enum OptionType {
        CALL, PUT
    }

    public enum ExerciseType {
        EUROPEAN,  // 仅到期日行权
        AMERICAN   // 任意交易日行权
    }
}
```

### 4. 期权定价模型

```java
public class OptionPricing {
    // Black-Scholes 模型
    public double calculatePriceBS(
        OptionContract option,
        double underlyingPrice,
        double riskFreeRate,
        double volatility,
        double timeToExpiry
    ) {
        if (option.getOptionType() == OptionType.CALL) {
            return calculateCallPriceBS(...);
        } else {
            return calculatePutPriceBS(...);
        }
    }

    // 希腊字母计算
    public Greeks calculateGreeks(OptionContract option, ...) {
        return Greeks.builder()
            .delta(calculateDelta(...))
            .gamma(calculateGamma(...))
            .theta(calculateTheta(...))
            .vega(calculateVega(...))
            .rho(calculateRho(...))
            .build();
    }
}
```

### 5. 期权保证金计算

```java
public class OptionMargin {
    // 卖出期权需要保证金
    public long calculateMargin(OptionContract option, long premium) {
        if (option.getOptionType() == OptionType.CALL) {
            return calculateCallMargin(option, premium);
        } else {
            return calculatePutMargin(option, premium);
        }
    }

    // ETF期权保证金公式
    private long calculateCallMargin(OptionContract option, long premium) {
        // 认购期权义务仓保证金
        // = [合约前结算价 + Max(12% × 合约标的前收盘价 - 认购期权虚值, 7% × 标的前收盘价)] × 合约单位
    }
}
```

### 6. 行权交割

```java
public class ExerciseService {
    // 期权行权
    public void exercise(Account account, OptionContract option, long qty) {
        // 1. 检查是否在行权时间
        // 2. 检查持仓是否充足
        // 3. 计算行权盈亏
        // 4. 执行行权交割
        // 5. 更新持仓和资金
    }

    // 到期自动行权
    public void autoExercise() {
        // 实值期权自动行权
        // 虚值期权自动放弃
    }
}
```

### 7. 期货交割

```java
public class DeliveryService {
    // 现金交割 (股指期货)
    public void cashDelivery(FuturesContract contract) {
        // 交割结算价 = 最后交易日标的指数最后2小时算术平均价
        // 计算盈亏 = (交割价 - 开仓价) × 合约乘数
    }

    // 实物交割 (商品期货)
    public void physicalDelivery(FuturesContract contract) {
        // 卖方交付仓单
        // 买方支付货款
    }
}
```

### 8. 持仓管理

> 注: 持仓数据存储在 Chronicle Map (positions.dat) 中，以下为期货期权的持仓数据结构

```java
// Chronicle Map 中的期货期权持仓状态
public interface FuturesPositionState {
    // 多头持仓
    long getLongQty();
    void setLongQty(long qty);

    // 空头持仓
    long getShortQty();
    void setShortQty(long qty);

    // 冻结多头
    long getFrozenLong();
    void setFrozenLong(long qty);

    // 冻结空头
    long getFrozenShort();
    void setFrozenShort(long qty);
}

public interface OptionPositionState {
    // 认购期权多头
    long getLongCall();
    void setLongCall(long qty);

    // 认购期权空头
    long getShortCall();
    void setShortCall(long qty);

    // 认沽期权多头
    long getLongPut();
    void setLongPut(long qty);

    // 认沽期权空头
    long getShortPut();
    void setShortPut(long qty);
}
```

## 输出要求
- 完整的期货期权合约定义
- 保证金计算实现
- 期权定价模型代码
- 行权交割流程实现
