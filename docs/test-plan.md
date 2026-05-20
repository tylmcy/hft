# A股交易系统测试计划

> 版本: v1.0
> 日期: 2026-05-19
> 状态: **待评审**

---

## 修订历史

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| v1.0 | 2026-05-19 | 初始版本 |

---

## 目录

1. [测试概述](#1-测试概述)
2. [测试范围](#2-测试范围)
3. [测试策略](#3-测试策略)
4. [单元测试](#4-单元测试)
5. [集成测试](#5-集成测试)
6. [性能测试](#6-性能测试)
7. [压力测试](#7-压力测试)
8. [稳定性测试](#8-稳定性测试)
9. [安全测试](#9-安全测试)
10. [业务场景测试](#10-业务场景测试)
11. [测试环境](#11-测试环境)
12. [测试数据](#12-测试数据)
13. [测试工具](#13-测试工具)
14. [测试进度](#14-测试进度)
15. [风险评估](#15-风险评估)

---

## 1. 测试概述

### 1.1 测试目标

| 目标 | 说明 |
|------|------|
| **功能正确性** | 验证所有业务功能按需求正确实现 |
| **性能达标** | 验证系统满足100K TPS、P99<15ms目标 |
| **数据一致性** | 验证数据不丢失、不变错、可恢复 |
| **系统稳定性** | 验证系统长时间运行稳定可靠 |
| **安全性** | 验证认证授权、数据保护机制有效 |

### 1.2 测试原则

| 原则 | 说明 |
|------|------|
| 自动化优先 | 核心测试用例自动化执行 |
| 持续集成 | 每次提交触发单元测试和集成测试 |
| 分层测试 | 单元测试 → 集成测试 → 系统测试 |
| 数据驱动 | 使用参数化测试覆盖边界条件 |
| 可重复性 | 测试环境可重置，测试可重复执行 |

---

## 2. 测试范围

### 2.1 功能模块

| 模块 | 测试内容 | 优先级 |
|------|---------|--------|
| **订单网关** | 下单、撤单、风控检查 | P0 |
| **撮合引擎** | 价格优先、时间优先、部分成交 | P0 |
| **清算服务** | 资金清算、持仓更新、佣金计算 | P0 |
| **行情服务** | 新浪数据拉取、解析、推送 | P0 |
| **WebSocket** | 连接、订阅、推送、心跳 | P0 |
| **OpenAPI** | HMAC认证、限流、下单撤单 | P1 |
| **SOD流程** | 品种生成、T+1更新、数据校验 | P1 |
| **持久化** | Chronicle读写、H2持久化 | P0 |
| **恢复机制** | 重启恢复、幂等处理 | P0 |

### 2.2 不在测试范围

| 项目 | 原因 |
|------|------|
| 前端UI测试 | 前端独立测试计划 |
| 第三方库测试 | 信任第三方库质量 |
| 硬件故障测试 | 单机部署，无硬件冗余 |

---

## 3. 测试策略

### 3.1 测试金字塔

```
                    ┌─────────┐
                    │  E2E    │  5%
                    │  测试    │
                ┌───┴─────────┴───┐
                │   集成测试       │  15%
                │                 │
            ┌───┴─────────────────┴───┐
            │       单元测试           │  80%
            │                         │
            └─────────────────────────┘
```

### 3.2 测试阶段

| 阶段 | 内容 | 执行时机 | 通过标准 |
|------|------|---------|---------|
| 单元测试 | 方法/类级别测试 | 每次提交 | 覆盖率≥80% |
| 集成测试 | 模块间交互测试 | 每日构建 | 全部通过 |
| 性能测试 | 延迟、吞吐量测试 | 每周 | 满足性能目标 |
| 压力测试 | 极限负载测试 | 每两周 | 无崩溃、无数据丢失 |
| 稳定性测试 | 长时间运行测试 | 每周 | 72小时无故障 |
| 验收测试 | 业务场景测试 | 发布前 | 全部通过 |

---

## 4. 单元测试

### 4.1 测试框架

| 组件 | 技术 |
|------|------|
| 测试框架 | JUnit 5 |
| Mock框架 | Mockito |
| 断言库 | AssertJ |
| 参数化测试 | JUnit 5 Parameterized |

### 4.2 核心单元测试用例

#### 4.2.1 订单网关测试

| 用例ID | 测试项 | 输入 | 预期结果 |
|--------|--------|------|---------|
| OG-001 | 正常买入下单 | 有效买入订单 | 返回orderId，资金冻结 |
| OG-002 | 正常卖出下单 | 有效卖出订单 | 返回orderId，持仓冻结 |
| OG-003 | 资金不足买入 | 可用资金<订单金额 | 返回REJECT |
| OG-004 | 持仓不足卖出 | 可用持仓<卖出数量 | 返回REJECT |
| OG-005 | 涨停价买入 | 价格=涨停价 | 允许下单 |
| OG-006 | 超涨停价买入 | 价格>涨停价 | 返回REJECT |
| OG-007 | 跌停价卖出 | 价格=跌停价 | 允许下单 |
| OG-008 | 低于跌停价卖出 | 价格<跌停价 | 返回REJECT |
| OG-009 | 无效价格 | price<=0 | 返回REJECT |
| OG-010 | 无效数量 | quantity<=0 | 返回REJECT |
| OG-011 | 无效Symbol | 不存在的symbol | 返回REJECT |
| OG-012 | 正常撤单 | 有效orderId | 返回CANCEL_REQUESTED |
| OG-013 | 撤销不存在的订单 | 无效orderId | 返回NOT_FOUND |
| OG-014 | 撤销他人订单 | 非订单所有者 | 返回FORBIDDEN |
| OG-015 | 并发下单同一账户 | 10个线程同时下单 | 全部正确处理，资金一致 |

```java
@Test
@DisplayName("OG-001: 正常买入下单")
void testNormalBuyOrder() {
    // Given
    Account account = createAccount(1000000L);  // 100万可用
    OrderRequest request = OrderRequest.builder()
        .symbol("600000")
        .side(Side.BUY)
        .price(1000L)    // 10.00元
        .quantity(1000L) // 1000股
        .build();

    // When
    OrderResult result = orderGateway.submitOrder(request);

    // Then
    assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(result.getOrderId()).isNotNull();

    // 验证资金冻结
    Account updated = accountMap.get(account.getAccountId());
    assertThat(updated.getAvailable()).isEqualTo(990000L);  // 100万 - 1万
    assertThat(updated.getFrozen()).isEqualTo(10000L);
}
```

#### 4.2.2 撮合引擎测试

| 用例ID | 测试项 | 输入 | 预期结果 |
|--------|--------|------|---------|
| ME-001 | 买单撮合卖单 | 买价≥卖价 | 生成成交 |
| ME-002 | 价格优先 | 高价买单先于低价买单 | 高价先成交 |
| ME-003 | 时间优先 | 同价格按时间顺序 | 先到先成交 |
| ME-004 | 部分成交 | 订单数量>对手方数量 | 生成部分成交 |
| ME-005 | 完全成交 | 订单数量=对手方数量 | 生成完全成交 |
| ME-006 | 限价单不成交 | 买价<卖价 | 订单挂单等待 |
| ME-007 | 市价单立即成交 | 市价买单 | 以最优价成交 |
| ME-008 | FAK订单 | 部分成交后撤销 | 剩余撤销 |
| ME-009 | FOK订单 | 无法完全成交 | 全部撤销 |
| ME-010 | 撤销挂单 | 撤销等待中的订单 | 订单状态CANCELLED |
| ME-011 | 撤销部分成交订单 | 撤销已部分成交订单 | 剩余撤销 |
| ME-012 | 多Symbol撮合 | 不同Symbol订单 | 各Symbol独立撮合 |

```java
@Test
@DisplayName("ME-002: 价格优先")
void testPricePriority() {
    // Given
    OrderBook orderBook = new OrderBook("600000");

    // 先提交低价买单
    Order buy1 = createOrder(Side.BUY, 1000L, 100L);  // 10.00元
    orderBook.addOrder(buy1);

    // 后提交高价买单
    Order buy2 = createOrder(Side.BUY, 1001L, 100L);  // 10.01元
    orderBook.addOrder(buy2);

    // 提交卖单
    Order sell = createOrder(Side.SELL, 1000L, 100L);  // 10.00元
    orderBook.addOrder(sell);

    // When
    List<Trade> trades = orderBook.match();

    // Then: 高价买单先成交
    assertThat(trades).hasSize(1);
    assertThat(trades.get(0).getBuyOrderId()).isEqualTo(buy2.getOrderId());
}
```

#### 4.2.3 清算服务测试

| 用例ID | 测试项 | 输入 | 预期结果 |
|--------|--------|------|---------|
| CL-001 | 买入清算 | 买方成交 | 资金扣除、持仓增加 |
| CL-002 | 卖出清算 | 卖方成交 | 持仓扣除、资金增加 |
| CL-003 | 佣金计算-A股 | A股成交 | 按费率计算佣金 |
| CL-004 | 佣金计算-期货 | 期货成交 | 按期货费率计算 |
| CL-005 | 佣金计算-期权 | 期权成交 | 按张数计算 |
| CL-006 | A股T+1 | A股买入 | available不变 |
| CL-007 | 期货T+0 | 期货买入 | available立即增加 |
| CL-008 | 期权T+0 | 期权买入 | available立即增加 |
| CL-009 | 幂等处理 | 重复成交事件 | 跳过处理 |
| CL-010 | 撤单解冻资金 | 买单撤销 | 资金解冻 |
| CL-011 | 撤单解冻持仓 | 卖单撤销 | 持仓解冻 |

```java
@Test
@DisplayName("CL-006: A股T+1")
void testStockTPlus1() {
    // Given
    Account account = createAccount(1000000L);
    Trade trade = createTrade("600000", Side.BUY, 1000L, 100L);

    // When
    clearingService.processTrade(trade);

    // Then
    Position position = positionMap.get(getPositionKey(account.getAccountId(), "600000"));
    assertThat(position.getQuantity()).isEqualTo(100L);
    assertThat(position.getAvailable()).isEqualTo(0L);  // T+1，当日不可用
}

@Test
@DisplayName("CL-007: 期货T+0")
void testFuturesTPlus0() {
    // Given
    Account account = createAccount(1000000L);
    Trade trade = createTrade("IF2506", Side.BUY, 400000L, 1L);  // 期货

    // When
    clearingService.processTrade(trade);

    // Then
    Position position = positionMap.get(getPositionKey(account.getAccountId(), "IF2506"));
    assertThat(position.getQuantity()).isEqualTo(1L);
    assertThat(position.getAvailable()).isEqualTo(1L);  // T+0，立即可用
}
```

#### 4.2.4 佣金计算测试

| 用例ID | 测试项 | 成交金额 | 预期佣金 |
|--------|--------|---------|---------|
| CC-001 | A股正常佣金 | 100,000元 | 25元 |
| CC-002 | A股最低佣金 | 10,000元 | 5元（最低） |
| CC-003 | 期货佣金 | 1,000,000元 | 23元 |
| CC-004 | 期权佣金 | 10张 | 50元 |

```java
@Test
@DisplayName("CC-002: A股最低佣金")
void testStockMinCommission() {
    // Given
    long amount = 10000_00L;  // 1万元

    // When
    long commission = CommissionCalculator.calculate("600000", amount, 100L);

    // Then
    assertThat(commission).isEqualTo(5_00L);  // 最低5元
}
```

#### 4.2.5 Greeks计算测试

| 用例ID | 测试项 | 输入 | 预期结果 |
|--------|--------|------|---------|
| GR-001 | 认购期权Delta | S=3.0, K=2.8, T=0.1 | Delta ≈ 0.7 |
| GR-002 | 认沽期权Delta | S=3.0, K=3.2, T=0.1 | Delta ≈ -0.3 |
| GR-003 | Gamma计算 | 标准输入 | Gamma > 0 |
| GR-004 | Theta计算 | 标准输入 | Theta < 0 |
| GR-005 | Vega计算 | 标准输入 | Vega > 0 |

```java
@Test
@DisplayName("GR-001: 认购期权Delta")
void testCallOptionDelta() {
    // Given
    double S = 3.0;   // 标的价格
    double K = 2.8;   // 行权价
    double T = 0.1;   // 到期时间（年）
    double r = 0.03;  // 无风险利率
    double sigma = 0.2; // 波动率

    // When
    Greeks greeks = BlackScholes.calculate(S, K, T, r, sigma, OptionType.CALL);

    // Then
    assertThat(greeks.getDelta()).isCloseTo(0.7, within(0.1));
    assertThat(greeks.getGamma()).isGreaterThan(0);
}
```

### 4.3 单元测试覆盖率目标

| 模块 | 行覆盖率 | 分支覆盖率 |
|------|---------|-----------|
| 订单网关 | ≥ 90% | ≥ 85% |
| 撮合引擎 | ≥ 95% | ≥ 90% |
| 清算服务 | ≥ 90% | ≥ 85% |
| 行情服务 | ≥ 80% | ≥ 75% |
| 工具类 | ≥ 95% | ≥ 90% |
| **总体** | **≥ 80%** | **≥ 75%** |

---

## 5. 集成测试

### 5.1 测试范围

集成测试验证模块间交互是否正确。

### 5.2 集成测试用例

#### 5.2.1 下单-撮合-清算集成测试

| 用例ID | 测试场景 | 步骤 | 验证点 |
|--------|---------|------|--------|
| IT-001 | 完整买入流程 | 下买单 → 撮合 → 清算 | 资金扣除、持仓增加 |
| IT-002 | 完整卖出流程 | 下卖单 → 撮合 → 清算 | 持仓扣除、资金增加 |
| IT-003 | 部分成交流程 | 下大单 → 小单撮合 | 部分成交、剩余挂单 |
| IT-004 | 撤单完整流程 | 下单 → 撤单 → 清算解冻 | 资金/持仓解冻 |
| IT-005 | 双边交易 | 买卖双方下单撮合 | 双方账户正确更新 |

```java
@Test
@DisplayName("IT-001: 完整买入流程")
void testCompleteBuyFlow() {
    // Given: 准备卖单
    Account seller = createAccount(1000000L);
    createPosition(seller.getAccountId(), "600000", 1000L);
    orderGateway.submitOrder(createSellOrder(seller, "600000", 1000L, 100L));

    // When: 买入
    Account buyer = createAccount(1000000L);
    OrderResult result = orderGateway.submitOrder(
        createBuyOrder(buyer, "600000", 1000L, 100L)
    );

    // 等待撮合和清算
    await().atMost(1, SECONDS).until(() -> isOrderFilled(result.getOrderId()));

    // Then: 验证买方
    Account buyerAccount = accountMap.get(buyer.getAccountId());
    assertThat(buyerAccount.getBalance()).isEqualTo(990000L);  // 扣除10万

    Position buyerPosition = positionMap.get(getPositionKey(buyer.getAccountId(), "600000"));
    assertThat(buyerPosition.getQuantity()).isEqualTo(100L);

    // Then: 验证卖方
    Account sellerAccount = accountMap.get(seller.getAccountId());
    assertThat(sellerAccount.getBalance()).isEqualTo(1090000L);  // 增加9万（扣佣金）

    Position sellerPosition = positionMap.get(getPositionKey(seller.getAccountId(), "600000"));
    assertThat(sellerPosition.getQuantity()).isEqualTo(900L);
}
```

#### 5.2.2 行情集成测试

| 用例ID | 测试场景 | 验证点 |
|--------|---------|--------|
| IT-010 | 行情拉取并推送 | WebSocket收到行情推送 |
| IT-011 | 行情写入Chronicle | market_data.dat数据正确 |
| IT-012 | 多客户端订阅 | 所有订阅者收到推送 |
| IT-013 | 取消订阅 | 取消后不再收到推送 |

#### 5.2.3 持久化集成测试

| 用例ID | 测试场景 | 验证点 |
|--------|---------|--------|
| IT-020 | Chronicle写入 | 数据正确写入文件 |
| IT-021 | H2异步持久化 | 成交记录写入H2 |
| IT-022 | 重启恢复 | 数据完整恢复 |
| IT-023 | 部分成交后重启 | 订单状态正确恢复 |

```java
@Test
@DisplayName("IT-022: 重启恢复")
void testRestartRecovery() {
    // Given: 下单并部分成交
    Order order = submitOrderAndPartialFill();

    // 记录状态
    long orderId = order.getOrderId();
    long expectedRemaining = order.getRemainingQty();

    // When: 模拟重启
    tradingSystem.shutdown();
    tradingSystem.startup();

    // Then: 验证恢复
    Order recovered = orderbookMap.get(orderId);
    assertThat(recovered).isNotNull();
    assertThat(recovered.getRemainingQty()).isEqualTo(expectedRemaining);
    assertThat(recovered.getStatus()).isEqualTo(OrderStatus.PARTIAL_FILLED);
}
```

#### 5.2.4 OpenAPI集成测试

| 用例ID | 测试场景 | 验证点 |
|--------|---------|--------|
| IT-030 | HMAC认证成功 | 正确签名返回200 |
| IT-031 | HMAC认证失败 | 错误签名返回401 |
| IT-032 | 时间戳过期 | 过期时间戳返回401 |
| IT-033 | 只读Key下单 | 返回403 Forbidden |
| IT-034 | 交易Key下单 | 返回200 |
| IT-035 | 限流触发 | 超过500/秒返回429 |

```java
@Test
@DisplayName("IT-030: HMAC认证成功")
void testHmacAuthSuccess() {
    // Given
    String apiKey = "ak_test123";
    String secretKey = "sk_test456";
    String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
    String body = "{\"symbol\":\"600000\",\"side\":\"BUY\",\"price\":1000,\"quantity\":100}";

    String signString = "POST\n/openapi/orders\n" + timestamp + "\n" + sha256(body);
    String signature = hmacSha256(secretKey, signString);

    // When
    Response response = given()
        .header("X-API-Key", apiKey)
        .header("X-Timestamp", timestamp)
        .header("X-Signature", signature)
        .body(body)
        .post("/openapi/orders");

    // Then
    assertThat(response.statusCode()).isEqualTo(200);
}
```

---

## 6. 性能测试

### 6.1 性能目标

| 指标 | 目标值 | 测试方法 |
|------|--------|---------|
| 订单处理延迟 | P99 < 15ms | 压测统计 |
| 撮合延迟 | P99 < 1ms | 压测统计 |
| 清算延迟 | P99 < 1ms | 压测统计 |
| 系统吞吐量 | > 100,000 TPS | 压测统计 |
| WebSocket推送延迟 | P99 < 5ms | 端到端测量 |

### 6.2 性能测试场景

#### 6.2.1 订单吞吐量测试

| 场景ID | 场景描述 | 并发数 | 持续时间 | 目标TPS |
|--------|---------|--------|---------|---------|
| PT-001 | 单Symbol买卖混合 | 100 | 5分钟 | 50,000 |
| PT-002 | 多Symbol买卖混合 | 100 | 5分钟 | 100,000 |
| PT-003 | 撤单压力测试 | 100 | 5分钟 | 50,000 |
| PT-004 | 下单+撤单混合 | 100 | 5分钟 | 80,000 |

#### 6.2.2 延迟测试

| 场景ID | 场景描述 | 目标P50 | 目标P99 |
|--------|---------|---------|---------|
| PT-010 | 下单延迟 | < 5ms | < 15ms |
| PT-011 | 撤单延迟 | < 5ms | < 15ms |
| PT-012 | 行情推送延迟 | < 2ms | < 5ms |
| PT-013 | 端到端延迟（下单到成交回报） | < 10ms | < 20ms |

#### 6.2.3 性能测试脚本

```java
@Test
@DisplayName("PT-002: 多Symbol吞吐量测试")
void testMultiSymbolThroughput() {
    // Given
    int threadCount = 100;
    int symbols = 50;
    AtomicInteger orderCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(threadCount);

    // When
    long startTime = System.currentTimeMillis();
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
            for (int j = 0; j < 10000; j++) {
                String symbol = "600" + String.format("%03d", j % symbols);
                OrderRequest request = createRandomOrder(symbol);
                orderGateway.submitOrder(request);
                orderCount.incrementAndGet();
            }
            latch.countDown();
        });
    }

    latch.await(5, MINUTES);
    long duration = System.currentTimeMillis() - startTime;

    // Then
    double tps = orderCount.get() * 1000.0 / duration;
    assertThat(tps).isGreaterThan(100000);
}
```

### 6.3 性能测试报告模板

```
=== 性能测试报告 ===
测试时间: YYYY-MM-DD HH:mm:ss
测试场景: PT-002 多Symbol吞吐量测试

测试配置:
- 并发线程: 100
- Symbol数量: 50
- 测试时长: 5分钟

测试结果:
- 总订单数: 1,000,000
- 成功订单: 999,998
- 失败订单: 2
- 实际TPS: 105,263

延迟分布:
- P50: 3.2ms
- P90: 8.5ms
- P95: 10.2ms
- P99: 12.8ms
- Max: 45.6ms

资源使用:
- CPU: 75%
- 内存: 12GB / 16GB
- GC暂停: P99 0.8ms

结论: 通过 ✓
```

---

## 7. 压力测试

### 7.1 压力测试目标

验证系统在极限负载下的表现。

### 7.2 压力测试场景

| 场景ID | 场景描述 | 压力条件 | 验证点 |
|--------|---------|---------|--------|
| ST-001 | 极限吞吐量 | 逐步增加TPS至系统极限 | 找到最大TPS |
| ST-002 | 队列积压 | 队列积压100万订单 | 系统不崩溃 |
| ST-003 | 内存压力 | 100万活跃订单 | 内存不溢出 |
| ST-004 | 连接压力 | 10,000 WebSocket连接 | 连接正常 |
| ST-005 | 恢复测试 | 高负载下重启 | 数据不丢失 |

### 7.3 压力测试通过标准

| 指标 | 标准 |
|------|------|
| 系统崩溃 | 不允许 |
| 数据丢失 | 不允许 |
| 内存溢出 | 不允许 |
| 响应超时 | < 1% |

---

## 8. 稳定性测试

### 8.1 稳定性测试目标

验证系统长时间运行的稳定性。

### 8.2 稳定性测试场景

| 场景ID | 场景描述 | 持续时间 | 负载 | 验证点 |
|--------|---------|---------|------|--------|
| SB-001 | 常规稳定性 | 72小时 | 50,000 TPS | 无故障 |
| SB-002 | 交易时段模拟 | 5个交易日 | 模拟真实负载 | 无故障 |
| SB-003 | 内存泄漏检测 | 24小时 | 10,000 TPS | 内存稳定 |
| SB-004 | GC稳定性 | 48小时 | 50,000 TPS | GC暂停正常 |

### 8.3 稳定性测试监控指标

| 指标 | 告警阈值 |
|------|---------|
| CPU使用率 | > 90% 持续5分钟 |
| 内存使用率 | > 95% |
| GC暂停时间 | P99 > 10ms |
| 队列积压 | > 100,000 |
| 错误率 | > 0.1% |

### 8.4 稳定性测试报告模板

```
=== 稳定性测试报告 ===
测试时间: YYYY-MM-DD ~ YYYY-MM-DD
测试场景: SB-001 常规稳定性测试

测试配置:
- 持续时间: 72小时
- 目标TPS: 50,000
- 实际平均TPS: 52,341

系统状态:
- 总订单数: 13,567,890,000
- 成功率: 99.998%
- 错误数: 271,234

资源使用:
- CPU平均: 68%
- CPU峰值: 89%
- 内存平均: 11.2GB
- 内存峰值: 13.5GB
- GC暂停P99: 0.9ms

异常情况:
- 无

结论: 通过 ✓
```

---

## 9. 安全测试

### 9.1 安全测试范围

| 测试项 | 测试内容 |
|--------|---------|
| 认证测试 | JWT有效性、过期处理 |
| 授权测试 | 权限边界、越权访问 |
| 注入测试 | SQL注入、命令注入 |
| 加密测试 | 敏感数据加密、签名验证 |
| 限流测试 | 限流有效性 |

### 9.2 安全测试用例

| 用例ID | 测试项 | 测试方法 | 预期结果 |
|--------|--------|---------|---------|
| SEC-001 | 无Token访问 | 不带Token访问API | 返回401 |
| SEC-002 | 过期Token访问 | 使用过期Token | 返回401 |
| SEC-003 | 伪造Token | 使用伪造Token | 返回401 |
| SEC-004 | 越权访问他人订单 | 访问他人订单 | 返回403 |
| SEC-005 | 只读Key下单 | 使用只读Key下单 | 返回403 |
| SEC-006 | SQL注入 | 输入SQL语句 | 无注入漏洞 |
| SEC-007 | 签名重放 | 使用旧签名请求 | 返回401 |
| SEC-008 | 时间戳篡改 | 使用未来时间戳 | 返回401 |
| SEC-009 | 暴力破解 | 连续错误密码 | 账户锁定 |
| SEC-010 | 限流绕过 | 尝试绕过限流 | 限流生效 |

```java
@Test
@DisplayName("SEC-007: 签名重放")
void testSignatureReplay() {
    // Given: 获取一个有效的签名请求
    String apiKey = "ak_test123";
    String secretKey = "sk_test456";
    String timestamp = String.valueOf(System.currentTimeMillis() / 1000 - 10);  // 10秒前
    String signature = generateSignature(apiKey, secretKey, timestamp);

    // When: 使用旧签名请求
    Response response = given()
        .header("X-API-Key", apiKey)
        .header("X-Timestamp", timestamp)
        .header("X-Signature", signature)
        .get("/openapi/account");

    // Then
    assertThat(response.statusCode()).isEqualTo(401);  // 时间戳过期
}
```

---

## 10. 业务场景测试

### 10.1 典型交易场景

| 场景ID | 场景描述 | 测试步骤 | 验证点 |
|--------|---------|---------|--------|
| BS-001 | 正常买入股票 | 登录→查询行情→下单→成交→查看持仓 | 持仓正确 |
| BS-002 | 正常卖出股票 | 登录→查询持仓→下单→成交→查看资金 | 资金正确 |
| BS-003 | 涨停买入 | 涨停价买入→等待成交 | 订单挂单 |
| BS-004 | 跌停卖出 | 跌停价卖出→等待成交 | 订单挂单 |
| BS-005 | 撤销挂单 | 下单→撤销→确认解冻 | 资金/持仓解冻 |
| BS-006 | 部分成交后撤单 | 下大单→部分成交→撤单 | 剩余解冻 |
| BS-007 | 期货开仓平仓 | 开多→平多 | 持仓归零 |
| BS-008 | 期权买入卖出 | 买入认购→卖出 | 持仓归零 |
| BS-009 | SOD流程 | 开盘前等待SOD | T+1可用更新 |
| BS-010 | 系统重启恢复 | 下单→重启→验证 | 数据完整 |

### 10.2 异常场景测试

| 场景ID | 场景描述 | 测试步骤 | 预期结果 |
|--------|---------|---------|---------|
| EX-001 | 资金不足买入 | 可用资金不足下单 | 返回REJECT |
| EX-002 | 持仓不足卖出 | 可用持仓不足下单 | 返回REJECT |
| EX-003 | 超涨停价买入 | 价格超过涨停价 | 返回REJECT |
| EX-004 | 低于跌停价卖出 | 价格低于跌停价 | 返回REJECT |
| EX-005 | 撤销已成交订单 | 撤销已完全成交订单 | 返回错误 |
| EX-006 | 重复撤单 | 对同一订单重复撤单 | 第二次返回错误 |
| EX-007 | WebSocket断线 | 模拟网络断开 | 自动重连 |
| EX-008 | 新浪接口超时 | 模拟新浪接口超时 | 重试成功 |
| EX-009 | 新浪接口返回异常数据 | 返回格式错误数据 | 解析失败不影响系统 |
| EX-010 | 限流触发 | 超过API限流 | 返回429 |

---

## 11. 测试环境

### 11.1 环境配置

| 环境 | 用途 | 配置 | 数据 |
|------|------|------|------|
| 开发环境 | 开发自测 | 本地 | 模拟数据 |
| 测试环境 | 集成测试 | 4核/8GB | 测试数据 |
| 预发环境 | 性能测试 | 8核/16GB | 生产镜像 |
| 生产环境 | 生产运行 | 8核/16GB | 真实数据 |

### 11.2 测试环境要求

| 要求 | 说明 |
|------|------|
| 独立性 | 测试环境与生产环境隔离 |
| 可重置 | 支持数据重置到初始状态 |
| 监控 | 完整的监控和日志 |
| 网络 | 与新浪财经网络连通 |

---

## 12. 测试数据

### 12.1 测试数据准备

| 数据类型 | 数量 | 说明 |
|---------|------|------|
| 测试账户 | 100 | 不同资金规模 |
| 测试持仓 | 500 | 不同股票持仓 |
| 测试Symbol | 100 | 股票+期货+期权 |
| 测试API Key | 50 | 只读+交易Key |

### 12.2 测试数据初始化脚本

```sql
-- 初始化测试账户
INSERT INTO accounts (account_id, balance, available, frozen, margin)
VALUES
(10001, 100000000, 100000000, 0, 0),  -- 100万
(10002, 10000000, 10000000, 0, 0),    -- 10万
(10003, 1000000, 1000000, 0, 0);      -- 1万

-- 初始化测试持仓
INSERT INTO positions (account_id, symbol, quantity, available, frozen, avg_price)
VALUES
(10001, '600000', 10000, 10000, 0, 1000),
(10001, '600001', 5000, 5000, 0, 2000);

-- 初始化测试API Key
INSERT INTO api_keys (account_id, api_key, secret_key_hash, key_type, symbols, status)
VALUES
(10001, 'ak_test_readonly', 'hash1', 'READ_ONLY', 'STOCK,FUTURES', 'ACTIVE'),
(10001, 'ak_test_trading', 'hash2', 'TRADING', 'STOCK,FUTURES,OPTION', 'ACTIVE');
```

---

## 13. 测试工具

### 13.1 测试工具清单

| 工具 | 用途 | 版本 |
|------|------|------|
| JUnit 5 | 单元测试框架 | 5.10 |
| Mockito | Mock框架 | 5.x |
| AssertJ | 断言库 | 3.x |
| JMH | 性能基准测试 | 1.37 |
| JMeter | 压力测试 | 5.6 |
| Gatling | 性能测试 | 3.9 |
| TestContainers | 容器化测试 | 1.19 |
| Awaitility | 异步测试 | 4.x |

### 13.2 CI/CD集成

```yaml
# .github/workflows/test.yml
name: Test Pipeline

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  unit-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
      - name: Run Unit Tests
        run: ./gradlew test
      - name: Upload Coverage
        uses: codecov/codecov-action@v3

  integration-test:
    runs-on: ubuntu-latest
    needs: unit-test
    steps:
      - uses: actions/checkout@v3
      - name: Run Integration Tests
        run: ./gradlew integrationTest

  performance-test:
    runs-on: ubuntu-latest
    needs: integration-test
    if: github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v3
      - name: Run Performance Tests
        run: ./gradlew performanceTest
```

---

## 14. 测试进度

### 14.1 测试里程碑

| 里程碑 | 计划日期 | 测试内容 | 状态 |
|--------|---------|---------|------|
| M1 | Week 1-2 | 单元测试框架搭建、核心模块单元测试 | 待开始 |
| M2 | Week 3-4 | 集成测试、持久化测试 | 待开始 |
| M3 | Week 5-6 | 性能测试、压力测试 | 待开始 |
| M4 | Week 7-8 | 稳定性测试、安全测试 | 待开始 |
| M5 | Week 9 | 验收测试、回归测试 | 待开始 |

### 14.2 测试进度跟踪

| 模块 | 单元测试 | 集成测试 | 性能测试 | 状态 |
|------|---------|---------|---------|------|
| 订单网关 | 0/15 | 0/5 | 0/4 | 未开始 |
| 撮合引擎 | 0/12 | 0/5 | 0/4 | 未开始 |
| 清算服务 | 0/11 | 0/5 | 0/4 | 未开始 |
| 行情服务 | 0/10 | 0/4 | 0/3 | 未开始 |
| WebSocket | 0/8 | 0/4 | 0/3 | 未开始 |
| OpenAPI | 0/10 | 0/6 | 0/3 | 未开始 |
| SOD流程 | 0/5 | 0/3 | 0/2 | 未开始 |
| 持久化 | 0/8 | 0/4 | 0/3 | 未开始 |

---

## 15. 风险评估

### 15.1 测试风险

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|---------|
| 测试环境不稳定 | 中 | 高 | 环境监控、快速恢复机制 |
| 测试数据准备不足 | 中 | 中 | 自动化数据生成脚本 |
| 性能测试工具限制 | 低 | 中 | 多工具备份 |
| 新浪接口限流影响测试 | 高 | 中 | Mock接口备份 |
| 测试时间不足 | 中 | 高 | 优先级排序、自动化 |

### 15.2 质量风险

| 风险 | 可能性 | 影响 | 缓解措施 |
|------|--------|------|---------|
| 并发Bug遗漏 | 中 | 高 | 增加并发测试用例 |
| 边界条件遗漏 | 中 | 中 | 代码审查、边界测试 |
| 性能回归 | 低 | 高 | 持续性能监控 |
| 数据一致性问题 | 低 | 高 | 完善持久化测试 |

---

## 附录

### A. 测试用例清单

详见: [test-cases.md](test-cases.md)

### B. 测试脚本

详见: [test-scripts/](test-scripts/)

### C. 测试报告模板

详见: [test-report-template.md](test-report-template.md)
