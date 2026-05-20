# 测试策略 Skill

当用户需要设计和实现交易系统的测试方案时使用此skill。

## 触发场景
- 单元测试设计
- 集成测试实现
- 性能测试方案
- 回归测试策略

## 核心原则

### 1. 测试金字塔

```
        /\
       /  \  E2E测试 (10%)
      /────\
     /      \  集成测试 (20%)
    /────────\
   /          \  单元测试 (70%)
  /────────────\
```

### 2. 单元测试

#### 撮合引擎测试
```java
public class MatchingEngineTest {
    @Test
    public void testLimitOrderMatch() {
        OrderBook book = new OrderBook("600000");

        // 添加买单
        Order buyOrder = Order.builder()
            .orderId(1L)
            .price(1000L)
            .quantity(100L)
            .direction(Direction.BUY)
            .build();
        book.addOrder(buyOrder);

        // 添加卖单，应该立即撮合
        Order sellOrder = Order.builder()
            .orderId(2L)
            .price(1000L)
            .quantity(100L)
            .direction(Direction.SELL)
            .build();

        MatchResult result = book.match(sellOrder);

        assertEquals(100L, result.getFilledQty());
        assertEquals(0L, book.getBidDepth());
        assertEquals(0L, book.getAskDepth());
    }

    @Test
    public void testPriceTimePriority() {
        // 测试价格优先时间优先
    }

    @Test
    public void testPartialFill() {
        // 测试部分成交
    }
}
```

#### 清算服务测试
```java
public class ClearingServiceTest {
    @Test
    public void testTradeClearing() {
        Account buyer = createAccount(100000L);
        Account seller = createAccount(0L);
        seller.addPosition("600000", 100L);

        Trade trade = Trade.builder()
            .symbol("600000")
            .price(1000L)
            .quantity(100L)
            .buyAccountId(buyer.getAccountId())
            .sellAccountId(seller.getAccountId())
            .build();

        clearingService.clearTrade(trade);

        assertEquals(90000L, buyer.getBalance());
        assertEquals(100000L, seller.getBalance());
        assertEquals(100L, buyer.getPosition("600000").getQuantity());
        assertEquals(0L, seller.getPosition("600000").getQuantity());
    }
}
```

### 3. 集成测试

```java
@SpringBootTest
public class TradingIntegrationTest {
    @Autowired
    private OrderGateway orderGateway;

    @Autowired
    private MatchingEngine matchingEngine;

    @Autowired
    private ClearingService clearingService;

    @Test
    public void testFullTradingFlow() {
        // 1. 提交订单
        OrderRequest request = OrderRequest.builder()
            .accountId(1L)
            .symbol("600000")
            .price(1000L)
            .quantity(100L)
            .direction(Direction.BUY)
            .build();

        OrderResult result = orderGateway.submitOrder(request);
        assertNotNull(result.getOrderId());

        // 2. 等待撮合
        await().atMost(1, SECONDS).until(() ->
            matchingEngine.getOrderBook("600000").getBidDepth() > 0
        );

        // 3. 提交卖单触发撮合
        OrderRequest sellRequest = createSellOrder(...);
        orderGateway.submitOrder(sellRequest);

        // 4. 验证清算结果
        await().atMost(2, SECONDS).until(() ->
            getAccountBalance(1L) == expectedBalance
        );
    }
}
```

### 4. 性能测试

```java
public class PerformanceTest {
    @Test
    public void testOrderThroughput() {
        int threadCount = 10;
        int ordersPerThread = 10000;
        CountDownLatch latch = new CountDownLatch(threadCount);

        long start = System.nanoTime();

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                for (int j = 0; j < ordersPerThread; j++) {
                    orderGateway.submitOrder(createRandomOrder());
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        long duration = System.nanoTime() - start;

        double tps = (threadCount * ordersPerThread) / (duration / 1e9);
        assertTrue(tps > 100000, "TPS should > 100,000");
    }

    @Test
    public void testMatchingLatency() {
        // 测试撮合延迟 P99 < 10μs
        List<Long> latencies = new ArrayList<>();

        for (int i = 0; i < 10000; i++) {
            long start = System.nanoTime();
            matchingEngine.match(createOrder());
            long latency = System.nanoTime() - start;
            latencies.add(latency);
        }

        Collections.sort(latencies);
        long p99 = latencies.get((int)(latencies.size() * 0.99));
        assertTrue(p99 < 10000, "P99 latency should < 10μs");
    }
}
```

### 5. 压力测试

```java
public class StressTest {
    @Test
    public void testHighLoad() {
        // 模拟高峰期压力
        // 1. 大量订单涌入
        // 2. 观察系统表现
        // 3. 检查资源使用
        // 4. 验证数据一致性
    }

    @Test
    public void testMemoryLeak() {
        // 长时间运行测试
        // 监控内存使用
        // 检查是否有内存泄漏
    }
}
```

### 6. 回归测试

```java
public class RegressionTest {
    // 自动化回归测试套件
    @Test
    public void testAllOrderTypes() {
        // 测试所有订单类型
    }

    @Test
    public void testAllTradingRules() {
        // 测试所有交易规则
    }

    @Test
    public void testAllClearingScenarios() {
        // 测试所有清算场景
    }
}
```

### 7. 测试数据管理

```java
public class TestDataFactory {
    public static Order createTestOrder() {
        return Order.builder()
            .orderId(generateOrderId())
            .symbol("600000")
            .price(1000L)
            .quantity(100L)
            .direction(Direction.BUY)
            .build();
    }

    public static Account createTestAccount(long balance) {
        return Account.builder()
            .accountId(generateAccountId())
            .balance(balance)
            .build();
    }
}
```

## 测试覆盖率目标

| 模块 | 行覆盖率 | 分支覆盖率 |
|------|---------|-----------|
| 撮合引擎 | > 90% | > 85% |
| 清算服务 | > 85% | > 80% |
| 风控服务 | > 90% | > 85% |
| 订单网关 | > 80% | > 75% |

## 输出要求
- 完整的测试用例代码
- 性能测试报告
- 测试覆盖率报告
- CI/CD 集成配置
