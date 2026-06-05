package com.hft.trading.model;

/**
 * KLine - K线数据模型
 *
 * 记录某一时间窗口内的OHLC数据
 */
public class KLine {
    private String symbol;           // 证券代码
    private String interval;         // 周期: 1m/5m/15m/30m/1h/1d
    private long openTime;           // 开盘时间戳
    private long closeTime;          // 收盘时间戳
    private long openPrice;          // 开盘价（分）
    private long highPrice;          // 最高价（分）
    private long lowPrice;           // 最低价（分）
    private long closePrice;         // 收盘价（分）
    private long volume;             // 成交量（股）
    private long amount;             // 成交金额（分）
    private int tradeCount;          // 成交笔数

    // Interval constants
    public static final String INTERVAL_1M = "1m";
    public static final String INTERVAL_5M = "5m";
    public static final String INTERVAL_15M = "15m";
    public static final String INTERVAL_30M = "30m";
    public static final String INTERVAL_1H = "1h";
    public static final String INTERVAL_1D = "1d";

    public KLine() {
    }

    public KLine(String symbol, String interval, long openTime, long closeTime,
                 long openPrice, long highPrice, long lowPrice, long closePrice,
                 long volume, long amount, int tradeCount) {
        this.symbol = symbol;
        this.interval = interval;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.closePrice = closePrice;
        this.volume = volume;
        this.amount = amount;
        this.tradeCount = tradeCount;
    }

    /**
     * 获取时间窗口大小（毫秒）
     */
    public static long getIntervalMs(String interval) {
        switch (interval) {
            case INTERVAL_1M: return 60_000L;
            case INTERVAL_5M: return 300_000L;
            case INTERVAL_15M: return 900_000L;
            case INTERVAL_30M: return 1_800_000L;
            case INTERVAL_1H: return 3_600_000L;
            case INTERVAL_1D: return 86_400_000L;
            default: return 60_000L;
        }
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    public long getOpenTime() {
        return openTime;
    }

    public void setOpenTime(long openTime) {
        this.openTime = openTime;
    }

    public long getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(long closeTime) {
        this.closeTime = closeTime;
    }

    public long getOpenPrice() {
        return openPrice;
    }

    public void setOpenPrice(long openPrice) {
        this.openPrice = openPrice;
    }

    public long getHighPrice() {
        return highPrice;
    }

    public void setHighPrice(long highPrice) {
        this.highPrice = highPrice;
    }

    public long getLowPrice() {
        return lowPrice;
    }

    public void setLowPrice(long lowPrice) {
        this.lowPrice = lowPrice;
    }

    public long getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(long closePrice) {
        this.closePrice = closePrice;
    }

    public long getVolume() {
        return volume;
    }

    public void setVolume(long volume) {
        this.volume = volume;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public int getTradeCount() {
        return tradeCount;
    }

    public void setTradeCount(int tradeCount) {
        this.tradeCount = tradeCount;
    }
}
