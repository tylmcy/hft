package com.hft.trading.matching;

import com.hft.trading.model.KLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KLineAggregator 测试
 */
class KLineAggregatorTest {

    private KLineAggregator aggregator;
    private final String symbol = "600000";

    @BeforeEach
    void setUp() {
        aggregator = new KLineAggregator(null);
    }

    @Test
    @DisplayName("第一笔成交应该创建新K线")
    void testOnTrade_firstTrade() {
        long price = 105000L; // 10.50元
        long quantity = 100L;
        long timestamp = System.currentTimeMillis();

        aggregator.onTrade(symbol, price, quantity, timestamp);

        KLine kline = aggregator.getCurrentKLine(symbol, KLine.INTERVAL_1M);
        assertNotNull(kline);
        assertEquals(symbol, kline.getSymbol());
        assertEquals(KLine.INTERVAL_1M, kline.getInterval());
        assertEquals(price, kline.getOpenPrice());
        assertEquals(price, kline.getHighPrice());
        assertEquals(price, kline.getLowPrice());
        assertEquals(price, kline.getClosePrice());
        assertEquals(quantity, kline.getVolume());
        assertEquals(1, kline.getTradeCount());
    }

    @Test
    @DisplayName("多笔成交应该正确聚合K线")
    void testOnTrade_multipleTrades() {
        long timestamp = System.currentTimeMillis();

        // 第一笔: 价格10.50元，数量100股
        aggregator.onTrade(symbol, 105000L, 100L, timestamp);

        // 第二笔: 价格10.55元，数量200股
        aggregator.onTrade(symbol, 105500L, 200L, timestamp);

        // 第三笔: 价格10.48元，数量150股
        aggregator.onTrade(symbol, 104800L, 150L, timestamp);

        KLine kline = aggregator.getCurrentKLine(symbol, KLine.INTERVAL_1M);
        assertNotNull(kline);
        assertEquals(105000L, kline.getOpenPrice()); // 第一笔开盘价
        assertEquals(105500L, kline.getHighPrice()); // 最高价
        assertEquals(104800L, kline.getLowPrice());  // 最低价
        assertEquals(104800L, kline.getClosePrice()); // 最后一笔收盘价
        assertEquals(450L, kline.getVolume()); // 总成交量
        assertEquals(3, kline.getTradeCount()); // 成交笔数

        // 计算成交金额: 105000*100 + 105500*200 + 104800*150
        long expectedAmount = 105000L * 100L + 105500L * 200L + 104800L * 150L;
        assertEquals(expectedAmount, kline.getAmount());
    }

    @Test
    @DisplayName("时间窗口切换应该创建新K线")
    void testOnTrade_newInterval() {
        long baseTime = 1700000000000L; // 固定时间戳
        long intervalMs = KLine.getIntervalMs(KLine.INTERVAL_1M);

        // 第一笔在窗口内
        aggregator.onTrade(symbol, 105000L, 100L, baseTime);

        KLine kline1 = aggregator.getCurrentKLine(symbol, KLine.INTERVAL_1M);
        assertNotNull(kline1);
        long expectedOpenTime1 = (baseTime / intervalMs) * intervalMs;
        assertEquals(expectedOpenTime1, kline1.getOpenTime());

        // 第二笔在下一个时间窗口
        long nextWindowTime = baseTime + intervalMs + 1000;
        aggregator.onTrade(symbol, 106000L, 200L, nextWindowTime);

        KLine kline2 = aggregator.getCurrentKLine(symbol, KLine.INTERVAL_1M);
        assertNotNull(kline2);
        // 新K线的开盘价应该是新价格
        assertEquals(106000L, kline2.getOpenPrice());
        // 验证时间窗口不同
        assertNotEquals(kline1.getOpenTime(), kline2.getOpenTime());
    }

    @Test
    @DisplayName("应该正确获取当前K线")
    void testGetCurrentKLine() {
        long timestamp = System.currentTimeMillis();

        // 添加成交
        aggregator.onTrade(symbol, 105000L, 100L, timestamp);

        // 获取1分钟K线
        KLine kline1m = aggregator.getCurrentKLine(symbol, KLine.INTERVAL_1M);
        assertNotNull(kline1m);
        assertEquals(KLine.INTERVAL_1M, kline1m.getInterval());

        // 获取5分钟K线
        KLine kline5m = aggregator.getCurrentKLine(symbol, KLine.INTERVAL_5M);
        assertNotNull(kline5m);
        assertEquals(KLine.INTERVAL_5M, kline5m.getInterval());

        // 获取不存在的K线
        KLine kline1d = aggregator.getCurrentKLine("999999", KLine.INTERVAL_1D);
        assertNull(kline1d);
    }

    @Test
    @DisplayName("应该支持所有周期")
    void testOnTrade_allIntervals() {
        long timestamp = System.currentTimeMillis();
        long price = 105000L;
        long quantity = 100L;

        aggregator.onTrade(symbol, price, quantity, timestamp);

        // 验证所有周期都有K线
        String[] intervals = {
            KLine.INTERVAL_1M, KLine.INTERVAL_5M, KLine.INTERVAL_15M,
            KLine.INTERVAL_30M, KLine.INTERVAL_1H, KLine.INTERVAL_1D
        };

        for (String interval : intervals) {
            KLine kline = aggregator.getCurrentKLine(symbol, interval);
            assertNotNull(kline, "K线不应该为空: " + interval);
            assertEquals(interval, kline.getInterval());
        }
    }

    @Test
    @DisplayName("getIntervalMs应该返回正确的时间窗口大小")
    void testGetIntervalMs() {
        assertEquals(60_000L, KLine.getIntervalMs(KLine.INTERVAL_1M));
        assertEquals(300_000L, KLine.getIntervalMs(KLine.INTERVAL_5M));
        assertEquals(900_000L, KLine.getIntervalMs(KLine.INTERVAL_15M));
        assertEquals(1_800_000L, KLine.getIntervalMs(KLine.INTERVAL_30M));
        assertEquals(3_600_000L, KLine.getIntervalMs(KLine.INTERVAL_1H));
        assertEquals(86_400_000L, KLine.getIntervalMs(KLine.INTERVAL_1D));
    }
}
