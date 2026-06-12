package com.hft.trading.controller;

import com.hft.trading.dto.ApiResponse;
import com.hft.trading.dto.OrderRequest;
import com.hft.trading.dto.OrderResponse;
import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.TradingPhase;
import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.lifecycle.PhaseManager;
import com.hft.trading.model.*;
import com.hft.trading.queue.QueueManager;
import com.hft.trading.service.IdempotencyService;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import com.hft.trading.store.InMemoryPositionStore;
import com.hft.trading.util.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单 REST API 控制器
 *
 * 根据 V12 技术方案 8.1 节实现
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final SnowflakeIdGenerator idGenerator;
    private final InMemoryAccountStore accountStore;
    private final InMemoryPositionStore positionStore;
    private final InMemoryInstrumentStore instrumentStore;
    private final InMemoryOrderStore orderStore;
    private final IdempotencyService idempotencyService;
    private final QueueManager queueManager;
    private final PhaseManager phaseManager;
    private final LifecycleManager lifecycleManager;

    public OrderController(SnowflakeIdGenerator idGenerator,
                          InMemoryAccountStore accountStore,
                          InMemoryPositionStore positionStore,
                          InMemoryInstrumentStore instrumentStore,
                          InMemoryOrderStore orderStore,
                          IdempotencyService idempotencyService,
                          QueueManager queueManager,
                          PhaseManager phaseManager,
                          LifecycleManager lifecycleManager) {
        this.idGenerator = idGenerator;
        this.accountStore = accountStore;
        this.positionStore = positionStore;
        this.instrumentStore = instrumentStore;
        this.orderStore = orderStore;
        this.idempotencyService = idempotencyService;
        this.queueManager = queueManager;
        this.phaseManager = phaseManager;
        this.lifecycleManager = lifecycleManager;
    }

    /**
     * 下单
     * POST /api/orders
     */
    @PostMapping
    public ApiResponse<OrderResponse> submitOrder(@RequestBody OrderRequest request) {
        logger.info("Submit order request: accountId={}, symbol={}, side={}, type={}, price={}, qty={}",
            request.getAccountId(), request.getSymbol(), request.getSide(), request.getOrderType(),
            request.getPrice(), request.getQuantity());

        // 1. 系统状态检查
        if (!lifecycleManager.isAcceptingRequests()) {
            return ApiResponse.error(4005, "系统暂停服务");
        }

        // 2. 交易阶段检查
        if (!phaseManager.canPlaceOrder()) {
            TradingPhase phase = phaseManager.getCurrentPhase();
            if (phase == TradingPhase.CLOSED) {
                return ApiResponse.error(4001, "非交易时段");
            } else if (phase == TradingPhase.LUNCH_BREAK) {
                return ApiResponse.error(4001, "午休时段");
            } else {
                return ApiResponse.error(4005, "非连续竞价时段");
            }
        }

        // 3. 幂等检查
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            idempotencyKey = java.util.UUID.randomUUID().toString();
        }

        IdempotencyService.Result idempotentResult = idempotencyService.checkOrRegister(idempotencyKey, 0);
        if (idempotentResult.getStatus() == IdempotencyService.Status.CONFIRMED) {
            // 已处理的请求
            return ApiResponse.success("订单已处理", OrderResponse.withStatus(idempotentResult.getOrderId(), "EXISTS"));
        }
        if (idempotentResult.getStatus() == IdempotencyService.Status.PROCESSING && idempotentResult.getOrderId() != null) {
            return ApiResponse.error(1003, "请求处理中");
        }

        // 4. 参数校验
        Account account = accountStore.getAccount(request.getAccountIdAsLong());
        if (account == null) {
            return ApiResponse.error(2004, "账户不存在");
        }

        Instrument instrument = instrumentStore.getInstrument(request.getSymbol());
        if (instrument == null) {
            return ApiResponse.error(4003, "品种不存在");
        }

        // 5. 涨跌停校验
        long price = request.getPriceAsLong();
        long limitUpPrice = instrument.getLimitUpPrice();
        long limitDownPrice = instrument.getLimitDownPrice();

        if (request.getOrderTypeEnum() == OrderType.LIMIT) {
            if (price <= 0) {
                return ApiResponse.error(1001, "限价单价格必须大于0");
            }
            if (price > limitUpPrice || price < limitDownPrice) {
                return ApiResponse.error(4002, "价格超出涨跌停范围");
            }
        }

        // 6. 数量校验
        long quantity = request.getQuantityAsLong();
        if (quantity <= 0 || quantity % instrument.getLotSize() != 0) {
            return ApiResponse.error(1001, "数量必须为每手数量的整数倍");
        }

        // 7. 计算冻结金额
        long frozenAmount = 0;
        long maxPrice = request.getMaxPriceAsLong();
        long minPrice = request.getMinPriceAsLong();

        if (request.getSideEnum() == OrderSide.BUY) {
            // 买单：冻结资金
            long effectivePrice = request.getOrderTypeEnum() == OrderType.MARKET ? limitUpPrice : price;
            frozenAmount = effectivePrice * quantity;
            // 加预估佣金（ceil 向上取整）
            long estimatedCommission = (frozenAmount * instrument.getCommissionRate() + 9999) / 10000;
            frozenAmount += estimatedCommission;
            maxPrice = limitUpPrice;
        } else {
            // 卖单：冻结持仓
            Position position = positionStore.getPosition(account.getAccountId(), request.getSymbol());
            if (position == null || position.getAvailable() < quantity) {
                return ApiResponse.error(2002, "持仓不足");
            }
            minPrice = limitDownPrice;
        }

        // 8. 生成订单ID
        long orderId = idGenerator.nextId();

        // 9. 更新幂等键
        idempotencyService.checkOrRegister(idempotencyKey, orderId);

        // 10. 创建订单对象
        Order order = new Order(
            orderId,
            request.getSymbol(),
            instrument.getSymbolCode(),
            request.getSideEnum(),
            request.getOrderTypeEnum(),
            price,
            quantity,
            account.getAccountId(),
            account.getUserId()
        );
        order.setFrozenAmount(frozenAmount);
        order.setMaxPrice(maxPrice);
        order.setMinPrice(minPrice);
        order.setIdempotencyKey(idempotencyKey);
        orderStore.addOrder(order);

        // 11. 写入 CQ
        AccountInputEvent event = AccountInputEvent.newOrder(
            orderId,
            account.getAccountId(),
            account.getUserId(),
            request.getSymbol(),
            request.getSideEnum(),
            request.getOrderTypeEnum(),
            price,
            quantity,
            frozenAmount,
            idempotencyKey
        );
        event.setMaxPrice(maxPrice);
        event.setMinPrice(minPrice);

        queueManager.getAccountInputQueue().writeEvent(event);

        logger.info("Order submitted: orderId={}, status=PENDING", orderId);
        return ApiResponse.success(OrderResponse.pending(orderId));
    }

    /**
     * 撤单
     * DELETE /api/orders/{orderId}
     */
    @DeleteMapping("/{orderId}")
    public ApiResponse<OrderResponse> cancelOrder(@PathVariable String orderId) {
        logger.info("Cancel order request: orderId={}", orderId);

        // 系统状态检查
        if (!lifecycleManager.canCancelOrder()) {
            return ApiResponse.error(4005, "系统暂停服务");
        }

        long oid = Long.parseLong(orderId);
        Order order = orderStore.getOrder(oid);

        if (order == null) {
            return ApiResponse.error(3001, "订单不存在");
        }

        // 检查订单状态
        if (order.getStatus() == OrderStatus.FILLED) {
            return ApiResponse.error(3002, "订单已成交");
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return ApiResponse.error(3002, "订单已撤销");
        }
        if (order.getStatus() == OrderStatus.REJECTED) {
            return ApiResponse.error(3002, "订单已拒绝");
        }

        // 写入撤单事件
        AccountInputEvent event = AccountInputEvent.cancelOrder(oid, order.getAccountId(), CancelReason.USER_CANCEL);
        queueManager.getAccountInputQueue().writeEvent(event);

        logger.info("Cancel request submitted: orderId={}", orderId);
        return ApiResponse.success(OrderResponse.withStatus(oid, "PENDING_CANCEL"));
    }

    /**
     * 查询订单详情
     * GET /api/orders/{orderId}
     */
    @GetMapping("/{orderId}")
    public ApiResponse<OrderDetailResponse> getOrder(@PathVariable String orderId) {
        long oid = Long.parseLong(orderId);
        Order order = orderStore.getOrder(oid);

        if (order == null) {
            return ApiResponse.error(3001, "订单不存在");
        }

        return ApiResponse.success(OrderDetailResponse.from(order));
    }

    /**
     * 查询当前委托
     * GET /api/orders/active
     */
    @GetMapping("/active")
    public ApiResponse<List<OrderDetailResponse>> getActiveOrders(
            @RequestParam(required = false) String accountId) {

        List<Order> orders;
        if (accountId != null) {
            orders = orderStore.getOrdersByAccount(Long.parseLong(accountId));
        } else {
            orders = orderStore.getAllOrders();
        }

        // 过滤活跃订单
        List<OrderDetailResponse> activeOrders = orders.stream()
            .filter(o -> o.getStatus() == OrderStatus.PENDING ||
                         o.getStatus() == OrderStatus.NEW ||
                         o.getStatus() == OrderStatus.PARTIAL_FILLED)
            .map(OrderDetailResponse::from)
            .collect(Collectors.toList());

        return ApiResponse.success(activeOrders);
    }

    /**
     * 获取历史订单
     * GET /api/orders/history
     */
    @GetMapping("/history")
    public ApiResponse<?> getOrderHistory(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String side,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        // Simplified: return empty paginated response
        // Full implementation reads from H2 orders_history table
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("items", new java.util.ArrayList<>());
        result.put("total", 0);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("totalPages", 0);

        return ApiResponse.success(result);
    }

    /**
     * 订单详情响应 DTO
     */
    public static class OrderDetailResponse {
        private String orderId;
        private String symbol;
        private String side;
        private String orderType;
        private String price;
        private String quantity;
        private String filledQuantity;
        private String remainingQty;
        private String status;
        private String createTime;

        public static OrderDetailResponse from(Order order) {
            OrderDetailResponse response = new OrderDetailResponse();
            response.orderId = String.valueOf(order.getOrderId());
            response.symbol = order.getSymbol();
            response.side = order.getSide().name();
            response.orderType = order.getOrderType().name();
            response.price = String.valueOf(order.getPrice());
            response.quantity = String.valueOf(order.getQuantity());
            response.filledQuantity = String.valueOf(order.getFilledQuantity());
            response.remainingQty = String.valueOf(order.getRemainingQty());
            response.status = order.getStatus().name();
            response.createTime = String.valueOf(order.getCreateTime());
            return response;
        }

        // Getters
        public String getOrderId() { return orderId; }
        public String getSymbol() { return symbol; }
        public String getSide() { return side; }
        public String getOrderType() { return orderType; }
        public String getPrice() { return price; }
        public String getQuantity() { return quantity; }
        public String getFilledQuantity() { return filledQuantity; }
        public String getRemainingQty() { return remainingQty; }
        public String getStatus() { return status; }
        public String getCreateTime() { return createTime; }

        // Setters
        public void setOrderId(String orderId) { this.orderId = orderId; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public void setSide(String side) { this.side = side; }
        public void setOrderType(String orderType) { this.orderType = orderType; }
        public void setPrice(String price) { this.price = price; }
        public void setQuantity(String quantity) { this.quantity = quantity; }
        public void setFilledQuantity(String filledQuantity) { this.filledQuantity = filledQuantity; }
        public void setRemainingQty(String remainingQty) { this.remainingQty = remainingQty; }
        public void setStatus(String status) { this.status = status; }
        public void setCreateTime(String createTime) { this.createTime = createTime; }
    }
}
