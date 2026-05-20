# Chronicle Queue 架构设计 Skill

当用户需要设计或实现基于 Chronicle Queue 的高性能交易链路时使用此skill。

## 触发场景
- 讨论主链路架构设计
- Chronicle Queue 配置和优化
- 低延迟消息传递实现
- 事件溯源模式设计

## 核心原则

### 1. Chronicle Queue 配置
```java
// 核心配置模板
SingleChronicleQueue queue = SingleChronicleQueueBuilder
    .binary(path)
    .rollCycle(RollCycles.DAILY)
    .indexSpacing(256)
    .indexCount(8)
    .create();
```

### 2. 关键组件设计
- **订单网关**: 接收外部订单请求，序列化到Chronicle
- **撮合引擎**: 从Chronicle读取订单，执行撮合逻辑
- **清算服务**: 订阅成交事件，计算资金变动
- **风控服务**: 实时监控订单风险

### 3. 性能优化要点
- 使用 `Bytes` 进行零拷贝序列化
- 避免在热路径上创建对象
- 使用 CPU 亲和性绑定关键线程
- 配置合适的 roll cycle 和 index 参数

### 4. 数据结构设计
```java
// 订单事件结构
interface OrderEvent {
    long orderId();
    String symbol();
    long price();
    long quantity();
    int direction(); // 买入/卖出
    int orderType(); // 限价/市价
    long timestamp();
}
```

## 输出要求
- 提供完整的 Chronicle 配置代码
- 说明线程模型和数据流
- 标注关键性能指标
- 给出容量规划建议
