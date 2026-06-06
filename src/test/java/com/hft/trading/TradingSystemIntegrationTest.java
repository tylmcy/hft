package com.hft.trading;

import com.hft.trading.model.*;
import com.hft.trading.service.AlertService;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import com.hft.trading.store.InMemoryPositionStore;
import com.hft.trading.util.SnowflakeIdGenerator;
import com.hft.trading.util.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 交易系统集成测试
 */
class TradingSystemIntegrationTest {

    private InMemoryAccountStore accountStore;
    private InMemoryPositionStore positionStore;
    private InMemoryOrderStore orderStore;
    private InMemoryInstrumentStore instrumentStore;
    private SnowflakeIdGenerator idGenerator;

    @BeforeEach
    void setUp() {
        AlertService alertService = TestUtils.createTestAlertService();
        accountStore = new InMemoryAccountStore(alertService);
        positionStore = new InMemoryPositionStore(alertService);
        orderStore = new InMemoryOrderStore();
        instrumentStore = new InMemoryInstrumentStore();
        idGenerator = new SnowflakeIdGenerator(1);

        // 初始化测试账户
        Account account = new Account(1001L, 2001L);
        account.deposit(1_000_000_00L); // 100万初始资金
        accountStore.addAccount(account);

        // 初始化测试证券
        Instrument instrument = new Instrument(
            "600000", 600000, InstrumentType.STOCK, "浦发银行",
            105000L, 0.10, 0.10, 100L, 1L, 3
        );
        instrumentStore.putInstrument(instrument);
    }

    @Test
    @DisplayName("完整下单流程测试")
    void testFullOrderFlow(TestInfo testInfo) {
        System.out.println("=== 测试: " + testInfo.getDisplayName() + " ===");

        // 1. 创建买单
        long orderId = idGenerator.nextId();
        Account account = accountStore.getAccount(1001L);
        Instrument instrument = instrumentStore.getInstrument("600000");

        // 计算冻结金额
        long price = 105000L; // 10.50元
        long quantity = 100L; // 100股
        long frozenAmount = price * quantity;
        long estimatedCommission = (frozenAmount * 3 + 9999) / 10000; // 万分之三
        frozenAmount += estimatedCommission;

        System.out.println("下单前账户余额: " + account.getAvailable() + " 分");

        // 2. 执行冻结
        accountStore.freeze(account.getAccountId(), frozenAmount);

        System.out.println("冻结金额: " + frozenAmount + " 分");
        System.out.println("冻结后可用余额: " + account.getAvailable() + " 分");

        // 验证冻结结果
        assertEquals(1_000_000_00L - frozenAmount, account.getAvailable());
        assertEquals(frozenAmount, account.getFrozen());

        // 3. 创建订单
        Order order = new Order(
            orderId, "600000", 600000, OrderSide.BUY, OrderType.LIMIT,
            price, quantity, account.getAccountId(), account.getUserId()
        );
        order.setFrozenAmount(frozenAmount);
        orderStore.addOrder(order);

        System.out.println("订单创建成功: orderId=" + orderId);

        // 验证订单状态
        assertEquals(OrderStatus.PENDING, order.getStatus());
        assertEquals(quantity, order.getQuantity());
        assertEquals(0L, order.getFilledQuantity());

        System.out.println("=== 测试通过 ===");
    }

    @Test
    @DisplayName("账户不变式验证测试")
    void testAccountInvariant(TestInfo testInfo) {
        System.out.println("=== 测试: " + testInfo.getDisplayName() + " ===");

        Account account = accountStore.getAccount(1001L);

        // 验证初始不变式: balance = available + frozen
        long balance = account.getBalance();
        long available = account.getAvailable();
        long frozen = account.getFrozen();

        System.out.println("balance=" + balance + ", available=" + available + ", frozen=" + frozen);

        assertEquals(balance, available + frozen, "账户不变式应满足");

        // 执行冻结操作
        long freezeAmount = 100_000_00L;
        accountStore.freeze(account.getAccountId(), freezeAmount);

        // 再次验证不变式
        balance = account.getBalance();
        available = account.getAvailable();
        frozen = account.getFrozen();

        System.out.println("冻结后: balance=" + balance + ", available=" + available + ", frozen=" + frozen);

        assertEquals(balance, available + frozen, "冻结后不变式仍应满足");

        System.out.println("=== 测试通过 ===");
    }

    @Test
    @DisplayName("卖出持仓冻结测试")
    void testSellPositionFreeze(TestInfo testInfo) {
        System.out.println("=== 测试: " + testInfo.getDisplayName() + " ===");

        // 初始化持仓
        Account account = accountStore.getAccount(1001L);
        Position position = new Position(account.getAccountId(), "600000", 600000);
        position.setQuantity(1000L); // 1000股
        position.setAvailable(1000L);
        positionStore.addPosition(position);

        System.out.println("初始持仓: quantity=" + position.getQuantity() + ", available=" + position.getAvailable());

        // 执行持仓冻结
        long sellQuantity = 500L;
        positionStore.freezePosition(account.getAccountId(), "600000", sellQuantity);

        System.out.println("冻结后: available=" + position.getAvailable() + ", frozen=" + position.getFrozen());

        // 验证冻结结果
        assertEquals(500L, position.getAvailable());
        assertEquals(500L, position.getFrozen());

        // 验证持仓不变式: |quantity| = available + frozen + t1Locked
        assertEquals(Math.abs(position.getQuantity()), position.getAvailable() + position.getFrozen() + position.getT1Locked());

        System.out.println("=== 测试通过 ===");
    }

    @Test
    @DisplayName("买入结算流程测试")
    void testBuySettlement(TestInfo testInfo) {
        System.out.println("=== 测试: " + testInfo.getDisplayName() + " ===");

        // 初始化冻结状态
        Account account = accountStore.getAccount(1001L);
        long frozenAmount = 105_500_00L; // 10550000分 = 105500元冻结金额
        accountStore.freeze(account.getAccountId(), frozenAmount);

        System.out.println("冻结后: available=" + account.getAvailable() + ", frozen=" + account.getFrozen());
        // 冻结后: available = 89450000, frozen = 10550000

        // 执行买入结算
        // InMemoryAccountStore.settleBuy 参数是 (accountId, frozenUsed, actualAmount, commission)
        long price = 105000L; // 成交价格 10.50元 = 105000分
        long quantity = 100L; // 成交数量 100股
        long actualAmount = price * quantity; // 10500000分
        long commission = (actualAmount * 3) / 10000; // 佣金（万分之三）= 3150分

        System.out.println("结算参数: frozenUsed=" + frozenAmount + ", actualAmount=" + actualAmount + ", commission=" + commission);

        accountStore.settleBuy(account.getAccountId(), frozenAmount, actualAmount, commission);

        System.out.println("结算后: balance=" + account.getBalance() +
            ", available=" + account.getAvailable() + ", frozen=" + account.getFrozen());

        // 验证结算结果
        long totalDeduction = actualAmount + commission;
        long refund = frozenAmount - totalDeduction;

        // balance = 100000000 - totalDeduction
        assertEquals(1_000_000_00L - totalDeduction, account.getBalance());
        // available = 冻结后available(89450000) + refund
        assertEquals(89450000L + refund, account.getAvailable());
        // frozen = 冻结后frozen - frozenUsed = 0
        assertEquals(0L, account.getFrozen());

        // 验证不变式
        assertTrue(account.validateInvariant());

        // 创建持仓
        positionStore.settleBuyPosition(account.getAccountId(), "600000", quantity, price);

        Position position = positionStore.getPosition(account.getAccountId(), "600000");
        assertNotNull(position);
        assertEquals(100L, position.getQuantity());
        assertEquals(100L, position.getT1Locked()); // T+1锁定

        System.out.println("=== 测试通过 ===");
    }

    @Test
    @DisplayName("雪花ID生成测试")
    void testSnowflakeIdGeneration(TestInfo testInfo) {
        System.out.println("=== 测试: " + testInfo.getDisplayName() + " ===");

        // 生成多个ID
        long id1 = idGenerator.nextId();
        long id2 = idGenerator.nextId();
        long id3 = idGenerator.nextId();

        System.out.println("生成的ID: " + id1 + ", " + id2 + ", " + id3);

        // 验证ID唯一性
        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);

        // 验证ID递增
        assertTrue(id1 < id2);
        assertTrue(id2 < id3);

        // 验证ID为正数
        assertTrue(id1 > 0);
        assertTrue(id2 > 0);
        assertTrue(id3 > 0);

        System.out.println("=== 测试通过 ===");
    }

    @Test
    @DisplayName("订单状态机测试")
    void testOrderStateMachine(TestInfo testInfo) {
        System.out.println("=== 测试: " + testInfo.getDisplayName() + " ===");

        // 创建订单
        long orderId = idGenerator.nextId();
        Order order = new Order(
            orderId, "600000", 600000, OrderSide.BUY, OrderType.LIMIT,
            105000L, 100L, 1001L, 2001L
        );

        System.out.println("初始状态: " + order.getStatus());
        assertEquals(OrderStatus.PENDING, order.getStatus());

        // 状态转换: PENDING -> NEW
        order.updateStatus(OrderStatus.NEW);
        System.out.println("转换后状态: " + order.getStatus());
        assertEquals(OrderStatus.NEW, order.getStatus());

        // 模拟部分成交
        order.updateFilledQuantity(50L);
        System.out.println("部分成交后: status=" + order.getStatus() +
            ", filled=" + order.getFilledQuantity() + ", remaining=" + order.getRemainingQty());
        assertEquals(OrderStatus.PARTIAL_FILLED, order.getStatus());
        assertEquals(50L, order.getFilledQuantity());
        assertEquals(50L, order.getRemainingQty());

        // 全部成交
        order.updateFilledQuantity(50L);
        System.out.println("全部成交后: status=" + order.getStatus());
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(100L, order.getFilledQuantity());
        assertEquals(0L, order.getRemainingQty());

        System.out.println("=== 测试通过 ===");
    }
}