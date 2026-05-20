# 清结算系统设计 Skill

当用户需要设计或实现交易清结算系统时使用此skill。

## 触发场景
- 资金清算逻辑
- 持仓结算计算
- 账户余额管理
- 风险指标计算

## 核心原则

### 1. 清算流程
```java
public class ClearingService {
    // T+0 实时清算
    public void clearTrade(Trade trade) {
        // 1. 冻结资金/持仓检查
        // 2. 计算交易金额
        // 3. 计算手续费
        // 4. 更新账户余额
        // 5. 更新持仓
        // 6. 生成流水记录
    }

    // T+1 日终结算
    public void dailySettlement() {
        // 1. 持仓市值重估
        // 2. 浮动盈亏计算
        // 3. 维持保证金检查
        // 4. 强制平仓处理
        // 5. 生成结算单
    }
}
```

### 2. 账户模型
```java
public class Account {
    private long accountId;
    private long availableBalance;    // 可用资金
    private long frozenBalance;       // 冻结资金
    private long marginBalance;       // 保证金
    private Map<String, Position> positions; // 持仓
}
```

### 3. 费用计算
- **股票**: 佣金、印花税、过户费
- **期货**: 手续费、交易所费用
- **期权**: 权利金、手续费

### 4. 风险控制
```java
public class RiskManager {
    // 实时风控检查
    public RiskResult checkRisk(Order order) {
        // 1. 资金充足性
        // 2. 持仓限额
        // 3. 涨跌停限制
        // 4. 自成交检测
        // 5. 频繁撤单检测
    }
}
```

### 5. 异步落库设计
```java
// 使用 Chronicle Queue 异步写入 H2
public class AsyncPersistence {
    private final ChronicleQueue persistQueue;

    public void asyncSave(TradeRecord record) {
        // 写入持久化队列
        // 后台线程批量写入 H2
    }
}
```

## 数据一致性
- 使用 Saga 模式处理分布式事务
- 实现幂等性保证
- 提供对账功能

## 输出要求
- 完整的清算服务代码
- 账户状态机设计
- 风控规则配置
- 对账报表模板
