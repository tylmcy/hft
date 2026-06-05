package com.hft.trading.websocket;

import com.hft.trading.matching.MatchingEngine;
import com.hft.trading.matching.OrderBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * MarketDataPublisher - 行情数据推送服务
 *
 * 定时推送五档行情到订阅的 WebSocket 客户端
 */
@Service
public class MarketDataPublisher {

    private static final Logger logger = LoggerFactory.getLogger(MarketDataPublisher.class);
    private static final long PUBLISH_INTERVAL_MS = 3000; // 3 seconds

    private final WebSocketServer webSocketServer;
    private final Map<String, MatchingEngine> matchingEngines;

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public MarketDataPublisher(WebSocketServer webSocketServer) {
        this.webSocketServer = webSocketServer;
        this.matchingEngines = new ConcurrentHashMap<>();
    }

    /**
     * 设置匹配引擎引用（由 LifecycleManager 注入）
     */
    public void setMatchingEngines(Map<String, MatchingEngine> engines) {
        this.matchingEngines.clear();
        if (engines != null) {
            this.matchingEngines.putAll(engines);
        }
    }

    /**
     * 启动定时推送
     */
    public void start() {
        if (running) return;
        running = true;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MarketDataPublisher");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
            this::publishMarketSnapshots,
            PUBLISH_INTERVAL_MS,
            PUBLISH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        logger.info("MarketDataPublisher started, interval={}ms", PUBLISH_INTERVAL_MS);
    }

    /**
     * 停止推送
     */
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.info("MarketDataPublisher stopped");
    }

    /**
     * 推送行情快照
     */
    private void publishMarketSnapshots() {
        if (!running || matchingEngines.isEmpty()) return;

        try {
            for (Map.Entry<String, MatchingEngine> entry : matchingEngines.entrySet()) {
                String symbol = entry.getKey();
                MatchingEngine engine = entry.getValue();

                // 检查是否有订阅
                if (!hasSubscribers(symbol)) continue;

                // 获取五档行情
                OrderBook.FiveLevelQuote quote = engine.getOrderBook().getFiveLevelQuote();
                if (quote == null) continue;

                // 构建推送消息
                WebSocketPushService.MarketSnapshotMessage message = buildMessage(symbol, quote);

                // 广播
                webSocketServer.broadcast("market_snapshot", symbol, message);
            }
        } catch (Exception e) {
            logger.error("Error publishing market snapshots: {}", e.getMessage());
        }
    }

    /**
     * 检查是否有订阅者
     */
    private boolean hasSubscribers(String symbol) {
        // 简化实现：假设总是有订阅
        // 实际应检查 webSocketServer 的 channelSubscriptions
        return true;
    }

    /**
     * 构建行情消息
     */
    private WebSocketPushService.MarketSnapshotMessage buildMessage(String symbol, OrderBook.FiveLevelQuote quote) {
        WebSocketPushService.MarketSnapshotMessage msg = new WebSocketPushService.MarketSnapshotMessage();
        msg.setSymbol(symbol);
        msg.setTimestamp(System.currentTimeMillis());

        // 设置五档行情
        List<OrderBook.PriceLevel> bidLevels = quote.getBidLevels();
        List<OrderBook.PriceLevel> askLevels = quote.getAskLevels();

        if (bidLevels.size() >= 1 && bidLevels.get(0).getPrice() > 0) {
            msg.setBidPrice1(bidLevels.get(0).getPrice() / 100.0);
            msg.setBidVolume1(bidLevels.get(0).getVolume());
        }
        if (bidLevels.size() >= 2 && bidLevels.get(1).getPrice() > 0) {
            // 可扩展：设置更多档位
        }

        if (askLevels.size() >= 1 && askLevels.get(0).getPrice() > 0) {
            msg.setAskPrice1(askLevels.get(0).getPrice() / 100.0);
            msg.setAskVolume1(askLevels.get(0).getVolume());
        }

        return msg;
    }

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return running;
    }
}