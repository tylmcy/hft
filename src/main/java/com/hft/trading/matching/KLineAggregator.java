package com.hft.trading.matching;

import com.hft.trading.model.KLine;
import com.hft.trading.websocket.WebSocketPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KLineAggregator - K线聚合器
 *
 * 将成交数据聚合为K线并推送
 */
public class KLineAggregator {

    private static final Logger logger = LoggerFactory.getLogger(KLineAggregator.class);

    // 支持的周期
    private static final String[] INTERVALS = {
        KLine.INTERVAL_1M, KLine.INTERVAL_5M, KLine.INTERVAL_15M,
        KLine.INTERVAL_30M, KLine.INTERVAL_1H, KLine.INTERVAL_1D
    };

    // 当前K线缓存: key = "symbol:interval"
    private final Map<String, KLine> currentKLines = new ConcurrentHashMap<>();

    private final WebSocketPushService webSocketPushService;

    public KLineAggregator(WebSocketPushService webSocketPushService) {
        this.webSocketPushService = webSocketPushService;
    }

    /**
     * 处理成交
     */
    public void onTrade(String symbol, long price, long quantity, long timestamp) {
        for (String interval : INTERVALS) {
            String key = symbol + ":" + interval;
            long intervalMs = KLine.getIntervalMs(interval);
            long openTime = (timestamp / intervalMs) * intervalMs;
            long closeTime = openTime + intervalMs - 1;

            KLine kline = currentKLines.computeIfAbsent(key, k ->
                createNewKLine(symbol, interval, openTime, closeTime, price));

            // 检查是否需要新建K线（时间窗口已切换）
            if (timestamp > kline.getCloseTime()) {
                // 推送完成的K线
                emitKLine(kline);

                // 创建新K线
                kline = createNewKLine(symbol, interval, openTime, closeTime, price);
                currentKLines.put(key, kline);
            }

            // 更新K线
            updateKLine(kline, price, quantity);
        }
    }

    /**
     * 创建新K线
     */
    private KLine createNewKLine(String symbol, String interval, long openTime, long closeTime, long price) {
        KLine kline = new KLine();
        kline.setSymbol(symbol);
        kline.setInterval(interval);
        kline.setOpenTime(openTime);
        kline.setCloseTime(closeTime);
        kline.setOpenPrice(price);
        kline.setHighPrice(price);
        kline.setLowPrice(price);
        kline.setClosePrice(price);
        kline.setVolume(0);
        kline.setAmount(0);
        kline.setTradeCount(0);
        return kline;
    }

    /**
     * 更新K线
     */
    private void updateKLine(KLine kline, long price, long quantity) {
        kline.setClosePrice(price);
        if (price > kline.getHighPrice()) {
            kline.setHighPrice(price);
        }
        if (price < kline.getLowPrice()) {
            kline.setLowPrice(price);
        }
        kline.setVolume(kline.getVolume() + quantity);
        kline.setAmount(kline.getAmount() + price * quantity);
        kline.setTradeCount(kline.getTradeCount() + 1);
    }

    /**
     * 推送K线
     */
    private void emitKLine(KLine kline) {
        if (webSocketPushService != null) {
            try {
                webSocketPushService.pushKLine(kline);
                logger.debug("Emitted KLine: symbol={}, interval={}, closeTime={}",
                    kline.getSymbol(), kline.getInterval(), kline.getCloseTime());
            } catch (Exception e) {
                logger.error("Failed to emit KLine: {}", e.getMessage());
            }
        }
    }

    /**
     * 获取当前K线
     */
    public KLine getCurrentKLine(String symbol, String interval) {
        return currentKLines.get(symbol + ":" + interval);
    }
}
