# WebSocket 实时推送 Skill

当用户需要设计和实现实时数据推送功能时使用此skill。

## 触发场景
- 行情数据推送
- 订单状态更新
- 成交回报推送
- 账户变动通知

## 核心原则

### 0. 时间字段规范

**重要**: 所有时间字段使用 `BIGINT` 毫秒级时间戳

```
后端:
  • 获取: System.currentTimeMillis()
  • 存储: long timestamp = System.currentTimeMillis();

前端:
  • 接收: message.timestamp (number类型)
  • 转换: new Date(message.timestamp)
  • 格式化: dayjs(message.timestamp).format('HH:mm:ss')
  • 自动转换为本地时区
```

### 1. WebSocket 服务端设计

```java
@Component
public class TradingWebSocketHandler extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String accountId = getAccountId(session);
        sessions.put(accountId, session);
        log.info("WebSocket connected: {}", accountId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 处理客户端订阅请求
        SubscribeRequest request = parseSubscribe(message.getPayload());
        handleSubscribe(session, request);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String accountId = getAccountId(session);
        sessions.remove(accountId);
        log.info("WebSocket disconnected: {}", accountId);
    }
}
```

### 2. 订阅管理

```java
@Service
public class SubscriptionManager {
    // 账户 -> 订阅列表
    private final Map<String, Set<Subscription>> accountSubscriptions;

    // 标的 -> 订阅账户列表
    private final Map<String, Set<String>> symbolSubscribers;

    public void subscribe(String accountId, Subscription subscription) {
        // 添加订阅
        accountSubscriptions.computeIfAbsent(accountId, k -> new HashSet<>())
            .add(subscription);

        // 更新标的订阅索引
        symbolSubscribers.computeIfAbsent(subscription.getSymbol(), k -> new HashSet<>())
            .add(accountId);
    }

    public void unsubscribe(String accountId, Subscription subscription) {
        // 移除订阅
    }

    public Set<String> getSubscribers(String symbol) {
        return symbolSubscribers.getOrDefault(symbol, Collections.emptySet());
    }
}
```

### 3. 数据推送类型

#### 行情推送
```java
public class MarketDataPusher {
    @Scheduled(fixedRate = 100) // 100ms 推送一次
    public void pushMarketData() {
        for (String symbol : getActiveSymbols()) {
            MarketData data = getLatestMarketData(symbol);

            // 推送给所有订阅该标的的账户
            Set<String> subscribers = subscriptionManager.getSubscribers(symbol);
            for (String accountId : subscribers) {
                WebSocketSession session = getSession(accountId);
                if (session != null && session.isOpen()) {
                    sendMessage(session, "MARKET_DATA", data);
                }
            }
        }
    }
}
```

#### 订单状态推送
```java
public class OrderStatusPusher {
    @EventListener
    public void onOrderUpdate(OrderUpdateEvent event) {
        String accountId = event.getAccountId();
        WebSocketSession session = getSession(accountId);

        if (session != null && session.isOpen()) {
            OrderStatusMessage message = OrderStatusMessage.builder()
                .orderId(event.getOrderId())
                .status(event.getStatus())
                .filledQty(event.getFilledQty())
                .avgPrice(event.getAvgPrice())
                .timestamp(System.currentTimeMillis())
                .build();

            sendMessage(session, "ORDER_STATUS", message);
        }
    }
}
```

#### 成交回报推送
```java
public class TradeReportPusher {
    @EventListener
    public void onTrade(TradeEvent event) {
        // 推送给买方
        pushToAccount(event.getBuyAccountId(), "TRADE_REPORT", event);

        // 推送给卖方
        pushToAccount(event.getSellAccountId(), "TRADE_REPORT", event);
    }
}
```

#### 账户变动推送
```java
public class AccountUpdatePusher {
    @EventListener
    public void onAccountUpdate(AccountUpdateEvent event) {
        WebSocketSession session = getSession(event.getAccountId());

        if (session != null && session.isOpen()) {
            AccountUpdateMessage message = AccountUpdateMessage.builder()
                .balance(event.getBalance())
                .available(event.getAvailable())
                .margin(event.getMargin())
                .positions(event.getPositions())
                .build();

            sendMessage(session, "ACCOUNT_UPDATE", message);
        }
    }
}
```

### 4. 消息格式设计

```java
public class WebSocketMessage<T> {
    private String type;      // MARKET_DATA, ORDER_STATUS, TRADE_REPORT
    private T data;
    private long timestamp;   // 时间戳 (毫秒)
    private String requestId; // 可选，用于请求响应
}

// 示例消息 (所有时间字段均为毫秒级时间戳)
{
    "type": "MARKET_DATA",
    "data": {
        "symbol": "600000",
        "lastPrice": 1000,
        "bidPrice": 999,
        "askPrice": 1001,
        "volume": 1000000,
        "timestamp": 1704067200000
    },
    "timestamp": 1704067200000
}

// 订单状态更新消息
{
    "type": "ORDER_UPDATE",
    "data": {
        "orderId": 100001,
        "status": 1,
        "filledQty": 50,
        "avgPrice": 100050,
        "updateTime": 1704067205000
    },
    "timestamp": 1704067205000
}

// 成交回报消息
{
    "type": "TRADE_REPORT",
    "data": {
        "tradeId": 200001,
        "orderId": 100001,
        "price": 100050,
        "quantity": 50,
        "commission": 15,
        "tradeTime": 1704067205000
    },
    "timestamp": 1704067205000
}
```

### 5. 客户端实现

```javascript
import dayjs from 'dayjs';

class TradingWebSocket {
    constructor(url) {
        this.ws = new WebSocket(url);
        this.subscriptions = new Map();
        this.handlers = new Map();

        this.ws.onmessage = (event) => {
            const message = JSON.parse(event.data);
            this.handleMessage(message);
        };
    }

    subscribe(symbol, callback) {
        this.send({
            action: 'subscribe',
            type: 'MARKET_DATA',
            symbol: symbol
        });

        this.handlers.set(`MARKET_DATA_${symbol}`, callback);
    }

    handleMessage(message) {
        // 时间戳转换为本地时区显示
        if (message.timestamp) {
            message.localTime = dayjs(message.timestamp).format('YYYY-MM-DD HH:mm:ss');
        }
        if (message.data && message.data.timestamp) {
            message.data.localTime = dayjs(message.data.timestamp).format('HH:mm:ss');
        }

        const handler = this.handlers.get(message.type);
        if (handler) {
            handler(message.data);
        }
    }

    send(data) {
        this.ws.send(JSON.stringify(data));
    }
}

// React Hook 示例
function useWebSocket(url) {
    const [lastMessage, setLastMessage] = useState(null);

    useEffect(() => {
        const ws = new TradingWebSocket(url);

        ws.ws.onmessage = (event) => {
            const message = JSON.parse(event.data);
            // 时间戳转换
            if (message.timestamp) {
                message.localTime = dayjs(message.timestamp).format('HH:mm:ss');
            }
            setLastMessage(message);
        };

        return () => ws.ws.close();
    }, [url]);

    return lastMessage;
}
```

### 6. 性能优化

```java
@Configuration
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        // 消息大小限制
        registry.setMessageSizeLimit(64 * 1024);

        // 发送缓冲区大小
        registry.setSendBufferSizeLimit(512 * 1024);

        // 发送时间限制
        registry.setSendTimeLimit(20 * 1000);
    }
}
```

### 7. 连接管理

```java
@Service
public class ConnectionManager {
    // 心跳检测
    @Scheduled(fixedRate = 30000)
    public void heartbeat() {
        for (WebSocketSession session : sessions.values()) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage("{\"type\":\"PING\"}"));
                } catch (Exception e) {
                    log.warn("Heartbeat failed: {}", session.getId());
                    closeSession(session);
                }
            }
        }
    }

    // 断线重连
    public void reconnect(String accountId) {
        // 清理旧连接
        // 等待客户端重连
        // 恢复订阅
    }
}
```

## 输出要求
- 完整的 WebSocket 服务端代码
- 客户端 SDK 实现
- 消息协议定义
- 性能优化方案
