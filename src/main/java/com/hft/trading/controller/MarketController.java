package com.hft.trading.controller;

import com.hft.trading.dto.ApiResponse;
import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.matching.OrderBook;
import com.hft.trading.model.Instrument;
import com.hft.trading.store.InMemoryInstrumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 行情 REST API 控制器
 *
 * 根据 V12 技术方案 8.1 节实现
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private static final Logger log = LoggerFactory.getLogger(MarketController.class);

    private final InMemoryInstrumentStore instrumentStore;
    private final LifecycleManager lifecycleManager;
    private final RestTemplate restTemplate;

    // 新浪行情代理：股票前缀映射 sh/sz
    private static final String SINA_URL = "https://hq.sinajs.cn/list=";
    // 指数代码映射
    private static final Map<String, String> INDEX_MAP = new LinkedHashMap<>();
    static {
        INDEX_MAP.put("000001", "sh000001");  // 上证指数
        INDEX_MAP.put("399001", "sz399001");  // 深证成指
        INDEX_MAP.put("399006", "sz399006");  // 创业板指
        INDEX_MAP.put("000688", "sh000688");  // 科创50
        INDEX_MAP.put("000300", "sh000300");  // 沪深300
    }

    // 自选股存储: userId -> Set<symbol>
    private final Map<String, Set<String>> watchlists = new ConcurrentHashMap<>();

    // 全品种代码（用于排行榜）
    private static final List<String> ALL_STOCK_CODES = new ArrayList<>();
    static {
        // 沪市
        ALL_STOCK_CODES.add("sh600000"); ALL_STOCK_CODES.add("sh600519"); ALL_STOCK_CODES.add("sh600036");
        ALL_STOCK_CODES.add("sh601318"); ALL_STOCK_CODES.add("sh600900"); ALL_STOCK_CODES.add("sh601012");
        ALL_STOCK_CODES.add("sh600887"); ALL_STOCK_CODES.add("sh600276"); ALL_STOCK_CODES.add("sh600585");
        ALL_STOCK_CODES.add("sh601166"); ALL_STOCK_CODES.add("sh600030"); ALL_STOCK_CODES.add("sh600104");
        ALL_STOCK_CODES.add("sh600690"); ALL_STOCK_CODES.add("sh600809"); ALL_STOCK_CODES.add("sh600028");
        ALL_STOCK_CODES.add("sh601857"); ALL_STOCK_CODES.add("sh600941"); ALL_STOCK_CODES.add("sh601398");
        ALL_STOCK_CODES.add("sh600438"); ALL_STOCK_CODES.add("sh601899"); ALL_STOCK_CODES.add("sh601728");
        ALL_STOCK_CODES.add("sh600150"); ALL_STOCK_CODES.add("sh601088"); ALL_STOCK_CODES.add("sh601225");
        ALL_STOCK_CODES.add("sh600196"); ALL_STOCK_CODES.add("sh601668"); ALL_STOCK_CODES.add("sh601211");
        // 深市
        ALL_STOCK_CODES.add("sz000001"); ALL_STOCK_CODES.add("sz000858"); ALL_STOCK_CODES.add("sz000333");
        ALL_STOCK_CODES.add("sz000651"); ALL_STOCK_CODES.add("sz000002"); ALL_STOCK_CODES.add("sz000725");
        ALL_STOCK_CODES.add("sz000568"); ALL_STOCK_CODES.add("sz000063"); ALL_STOCK_CODES.add("sz000100");
        ALL_STOCK_CODES.add("sz000538"); ALL_STOCK_CODES.add("sz000625"); ALL_STOCK_CODES.add("sz000776");
        ALL_STOCK_CODES.add("sz000661"); ALL_STOCK_CODES.add("sz000596"); ALL_STOCK_CODES.add("sz000547");
        ALL_STOCK_CODES.add("sz000938"); ALL_STOCK_CODES.add("sz000423"); ALL_STOCK_CODES.add("sz000301");
        // 创业板
        ALL_STOCK_CODES.add("sz300750"); ALL_STOCK_CODES.add("sz300059"); ALL_STOCK_CODES.add("sz300124");
        ALL_STOCK_CODES.add("sz300274"); ALL_STOCK_CODES.add("sz300015"); ALL_STOCK_CODES.add("sz300413");
        ALL_STOCK_CODES.add("sz300433"); ALL_STOCK_CODES.add("sz300782"); ALL_STOCK_CODES.add("sz300408");
        ALL_STOCK_CODES.add("sz300498"); ALL_STOCK_CODES.add("sz300760"); ALL_STOCK_CODES.add("sz300122");
        ALL_STOCK_CODES.add("sz300347");
    }

    public MarketController(InMemoryInstrumentStore instrumentStore,
                             LifecycleManager lifecycleManager) {
        this.instrumentStore = instrumentStore;
        this.lifecycleManager = lifecycleManager;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 获取指数行情（从新浪拉取）
     * GET /api/market/indices
     */
    @GetMapping("/indices")
    public ApiResponse<List<IndexQuoteResponse>> getIndices() {
        try {
            String codes = String.join(",", INDEX_MAP.values());
            String response = fetchSina(codes);
            List<IndexQuoteResponse> list = parseSinaIndexResponse(response);
            return ApiResponse.success(list);
        } catch (Exception e) {
            log.warn("Failed to fetch index data from Sina: {}", e.getMessage());
            // 返回空列表，前端显示占位符
            return ApiResponse.success(new ArrayList<>());
        }
    }

    /**
     * 新浪行情代理（通用）
     * GET /api/market/sina?codes=sh600000,sz000001
     */
    @GetMapping("/sina")
    public ApiResponse<List<SinaQuoteResponse>> getSinaQuotes(@RequestParam String codes) {
        try {
            String response = fetchSina(codes);
            return ApiResponse.success(parseSinaQuoteResponse(response));
        } catch (Exception e) {
            log.warn("Failed to fetch Sina quote: {}", e.getMessage());
            return ApiResponse.success(new ArrayList<>());
        }
    }

    /**
     * 获取排行榜数据（从新浪拉取全部品种，按涨幅/跌幅/成交额排序）
     * GET /api/market/rankings
     */
    @GetMapping("/rankings")
    public ApiResponse<RankingResponse> getRankings() {
        try {
            String codes = String.join(",", ALL_STOCK_CODES);
            String raw = fetchSina(codes);
            List<SinaQuoteResponse> quotes = parseSinaQuoteResponse(raw);

            // 过滤有效数据
            List<SinaQuoteResponse> valid = new ArrayList<>();
            for (SinaQuoteResponse q : quotes) {
                double price = parseDoubleSafe(q.getPrice());
                double prevClose = parseDoubleSafe(q.getPrevClose());
                if (price > 0 && prevClose > 0) {
                    q.setChange(String.format("%.2f", price - prevClose));
                    q.setChangePercent(String.format("%.2f%%", (price - prevClose) / prevClose * 100));
                    valid.add(q);
                }
            }

            RankingResponse resp = new RankingResponse();
            resp.setUpdateTime(String.valueOf(System.currentTimeMillis()));

            // 涨幅榜（倒序）
            List<SinaQuoteResponse> gainers = new ArrayList<>(valid);
            gainers.sort((a, b) -> Double.compare(
                parseDoubleSafe(b.getChangePercent().replace("%", "")),
                parseDoubleSafe(a.getChangePercent().replace("%", ""))
            ));
            resp.setGainers(gainers.size() > 10 ? gainers.subList(0, 10) : gainers);

            // 跌幅榜（正序）
            List<SinaQuoteResponse> losers = new ArrayList<>(valid);
            losers.sort((a, b) -> Double.compare(
                parseDoubleSafe(a.getChangePercent().replace("%", "")),
                parseDoubleSafe(b.getChangePercent().replace("%", ""))
            ));
            resp.setLosers(losers.size() > 10 ? losers.subList(0, 10) : losers);

            // 成交量榜
            List<SinaQuoteResponse> volumeList = new ArrayList<>(valid);
            volumeList.sort((a, b) -> Double.compare(
                parseDoubleSafe(b.getVolume()),
                parseDoubleSafe(a.getVolume())
            ));
            resp.setTopVolume(volumeList.size() > 10 ? volumeList.subList(0, 10) : volumeList);

            return ApiResponse.success(resp);
        } catch (Exception e) {
            log.warn("Failed to fetch rankings from Sina: {}", e.getMessage());
            return ApiResponse.success(new RankingResponse());
        }
    }

    /**
     * 拉取新浪行情数据（带 Referer 头）
     */
    private String fetchSina(String codes) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", "https://finance.sina.com.cn");
        HttpEntity<String> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(SINA_URL + codes, HttpMethod.GET, entity, String.class).getBody();
    }

    /**
     * 解析新浪指数返回
     * var hq_str_sh000001="上证指数,3977.54,3959.34,3974.24,3979.60,3955.91,0,0,203362329,395462448693,...,日期,时间";
     * 字段: 0=名称,1=开盘,2=昨收,3=当前,4=最高,5=最低,8=成交量,9=成交额,30=日期,31=时间
     */
    private List<IndexQuoteResponse> parseSinaIndexResponse(String raw) {
        List<IndexQuoteResponse> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;

        // Reverse lookup: sh000001 -> 000001
        Map<String, String> codeToSymbol = new HashMap<>();
        INDEX_MAP.forEach((sym, sinaCode) -> codeToSymbol.put(sinaCode, sym));

        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (!line.startsWith("var hq_str_")) continue;
            try {
                // Extract code between "var hq_str_" and "="
                String code = line.substring("var hq_str_".length(), line.indexOf("=")).trim();
                String symbol = codeToSymbol.get(code);
                if (symbol == null) continue;

                // Extract quoted values
                String values = line.substring(line.indexOf("\"") + 1, line.lastIndexOf("\""));
                String[] parts = values.split(",");

                IndexQuoteResponse resp = new IndexQuoteResponse();
                resp.setSymbol(symbol);
                resp.setName(parts.length > 0 ? parts[0] : "");
                resp.setOpen(parts.length > 1 ? parts[1] : "0");
                resp.setPrevClose(parts.length > 2 ? parts[2] : "0");
                resp.setPrice(parts.length > 3 ? parts[3] : "0");
                resp.setHigh(parts.length > 4 ? parts[4] : "0");
                resp.setLow(parts.length > 5 ? parts[5] : "0");
                resp.setVolume(parts.length > 8 ? parts[8] : "0");
                resp.setAmount(parts.length > 9 ? parts[9] : "0");
                resp.setDate(parts.length > 30 ? parts[30] : "");
                resp.setTime(parts.length > 31 ? parts[31] : "");
                result.add(resp);
            } catch (Exception e) {
                log.warn("Failed to parse Sina index line: {}", line);
            }
        }
        return result;
    }

    /**
     * 解析新浪通用行情返回
     */
    private List<SinaQuoteResponse> parseSinaQuoteResponse(String raw) {
        List<SinaQuoteResponse> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;

        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (!line.startsWith("var hq_str_")) continue;
            try {
                String values = line.substring(line.indexOf("\"") + 1, line.lastIndexOf("\""));
                String[] parts = values.split(",");
                SinaQuoteResponse resp = new SinaQuoteResponse();
                resp.setName(parts.length > 0 ? parts[0] : "");
                resp.setOpen(parts.length > 1 ? parts[1] : "0");
                resp.setPrevClose(parts.length > 2 ? parts[2] : "0");
                resp.setPrice(parts.length > 3 ? parts[3] : "0");
                resp.setHigh(parts.length > 4 ? parts[4] : "0");
                resp.setLow(parts.length > 5 ? parts[5] : "0");
                resp.setVolume(parts.length > 8 ? parts[8] : "0");
                resp.setAmount(parts.length > 9 ? parts[9] : "0");
                resp.setDate(parts.length > 30 ? parts[30] : "");
                resp.setTime(parts.length > 31 ? parts[31] : "");
                result.add(resp);
            } catch (Exception e) {
                log.warn("Failed to parse Sina quote line: {}", line);
            }
        }
        return result;
    }

    /**
     * 获取股票列表
     * GET /api/market/symbols
     */
    @GetMapping("/symbols")
    public ApiResponse<List<SymbolResponse>> getSymbols() {
        List<Instrument> instruments = instrumentStore.getAllInstruments();

        List<SymbolResponse> response = instruments.stream()
            .map(SymbolResponse::from)
            .collect(Collectors.toList());

        return ApiResponse.success(response);
    }

    /**
     * 获取五档行情
     * GET /api/market/quote?symbol=600000
     */
    @GetMapping("/quote")
    public ApiResponse<QuoteResponse> getQuote(@RequestParam String symbol) {
        OrderBook.FiveLevelQuote quote = lifecycleManager.getMatchingEngineWorker().getFiveLevelQuote(symbol);

        if (quote == null) {
            return ApiResponse.error(4003, "品种不存在");
        }

        return ApiResponse.success(QuoteResponse.from(quote));
    }

    // ==================== 自选股管理 ====================

    /**
     * 获取自选股列表
     * GET /api/market/watchlist
     */
    @GetMapping("/watchlist")
    public ApiResponse<List<WatchlistResponse>> getWatchlist(@RequestParam String userId) {
        Set<String> symbols = watchlists.getOrDefault(userId, Collections.emptySet());
        List<WatchlistResponse> response = symbols.stream()
            .map(s -> new WatchlistResponse(s, System.currentTimeMillis()))
            .collect(Collectors.toList());
        return ApiResponse.success(response);
    }

    /**
     * 添加自选股
     * POST /api/market/watchlist
     */
    @PostMapping("/watchlist")
    public ApiResponse<Void> addToWatchlist(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String symbol = body.get("symbol");
        if (userId == null || symbol == null) {
            return ApiResponse.error(1001, "参数无效");
        }
        watchlists.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(symbol);
        return ApiResponse.success(null);
    }

    /**
     * 删除自选股
     * DELETE /api/market/watchlist
     */
    @DeleteMapping("/watchlist")
    public ApiResponse<Void> removeFromWatchlist(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String symbol = body.get("symbol");
        if (userId != null && symbol != null) {
            Set<String> symbols = watchlists.get(userId);
            if (symbols != null) symbols.remove(symbol);
        }
        return ApiResponse.success(null);
    }

    /**
     * 获取K线历史数据
     * GET /api/market/kline?symbol=600000&interval=1m&limit=100
     */
    @GetMapping("/kline")
    public ApiResponse<List<KlineResponse>> getKline(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime,
            @RequestParam(defaultValue = "100") int limit) {
        // Simplified: return empty kline data
        // Full implementation reads from H2 kline_history table
        return ApiResponse.success(new ArrayList<>());
    }

    private double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 证券响应 DTO
     */
    public static class SymbolResponse {
        private String symbol;
        private String name;
        private String type;
        private String prevClose;
        private String limitUpPrice;
        private String limitDownPrice;
        private String lotSize;

        public static SymbolResponse from(Instrument instrument) {
            SymbolResponse response = new SymbolResponse();
            response.symbol = instrument.getSymbol();
            response.name = instrument.getName();
            response.type = instrument.getType().name();
            response.prevClose = String.valueOf(instrument.getPrevClose());
            response.limitUpPrice = String.valueOf(instrument.getLimitUpPrice());
            response.limitDownPrice = String.valueOf(instrument.getLimitDownPrice());
            response.lotSize = String.valueOf(instrument.getLotSize());
            return response;
        }

        // Getters
        public String getSymbol() { return symbol; }
        public String getName() { return name; }
        public String getType() { return type; }
        public String getPrevClose() { return prevClose; }
        public String getLimitUpPrice() { return limitUpPrice; }
        public String getLimitDownPrice() { return limitDownPrice; }
        public String getLotSize() { return lotSize; }

        // Setters
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public void setName(String name) { this.name = name; }
        public void setType(String type) { this.type = type; }
        public void setPrevClose(String prevClose) { this.prevClose = prevClose; }
        public void setLimitUpPrice(String limitUpPrice) { this.limitUpPrice = limitUpPrice; }
        public void setLimitDownPrice(String limitDownPrice) { this.limitDownPrice = limitDownPrice; }
        public void setLotSize(String lotSize) { this.lotSize = lotSize; }
    }

    /**
     * 五档行情响应 DTO
     */
    public static class QuoteResponse {
        private String symbol;
        private String updateTime;
        private List<PriceLevelResponse> bidLevels;
        private List<PriceLevelResponse> askLevels;

        public static QuoteResponse from(OrderBook.FiveLevelQuote quote) {
            QuoteResponse response = new QuoteResponse();
            response.symbol = quote.getSymbol();
            response.updateTime = String.valueOf(quote.getUpdateTime());

            response.bidLevels = new ArrayList<>();
            for (OrderBook.PriceLevel level : quote.getBidLevels()) {
                response.bidLevels.add(PriceLevelResponse.from(level));
            }

            response.askLevels = new ArrayList<>();
            for (OrderBook.PriceLevel level : quote.getAskLevels()) {
                response.askLevels.add(PriceLevelResponse.from(level));
            }

            return response;
        }

        // Getters
        public String getSymbol() { return symbol; }
        public String getUpdateTime() { return updateTime; }
        public List<PriceLevelResponse> getBidLevels() { return bidLevels; }
        public List<PriceLevelResponse> getAskLevels() { return askLevels; }

        // Setters
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
        public void setBidLevels(List<PriceLevelResponse> bidLevels) { this.bidLevels = bidLevels; }
        public void setAskLevels(List<PriceLevelResponse> askLevels) { this.askLevels = askLevels; }
    }

    /**
     * 价格档位响应 DTO
     */
    public static class PriceLevelResponse {
        private String price;
        private String volume;

        public static PriceLevelResponse from(OrderBook.PriceLevel level) {
            PriceLevelResponse response = new PriceLevelResponse();
            response.price = String.valueOf(level.getPrice());
            response.volume = String.valueOf(level.getVolume());
            return response;
        }

        // Getters
        public String getPrice() { return price; }
        public String getVolume() { return volume; }

        // Setters
        public void setPrice(String price) { this.price = price; }
        public void setVolume(String volume) { this.volume = volume; }
    }

    /**
     * 自选股响应 DTO
     */
    public static class WatchlistResponse {
        private String symbol;
        private long createdAt;

        public WatchlistResponse(String symbol, long createdAt) {
            this.symbol = symbol;
            this.createdAt = createdAt;
        }

        public String getSymbol() { return symbol; }
        public long getCreatedAt() { return createdAt; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    }

    /**
     * K线响应 DTO
     */
    public static class KlineResponse {
        private String symbol;
        private String interval;
        private String open;
        private String high;
        private String low;
        private String close;
        private String volume;
        private long openTime;
        private long closeTime;

        public String getSymbol() { return symbol; }
        public String getInterval() { return interval; }
        public String getOpen() { return open; }
        public String getHigh() { return high; }
        public String getLow() { return low; }
        public String getClose() { return close; }
        public String getVolume() { return volume; }
        public long getOpenTime() { return openTime; }
        public long getCloseTime() { return closeTime; }

        public void setSymbol(String symbol) { this.symbol = symbol; }
        public void setInterval(String interval) { this.interval = interval; }
        public void setOpen(String open) { this.open = open; }
        public void setHigh(String high) { this.high = high; }
        public void setLow(String low) { this.low = low; }
        public void setClose(String close) { this.close = close; }
        public void setVolume(String volume) { this.volume = volume; }
        public void setOpenTime(long openTime) { this.openTime = openTime; }
        public void setCloseTime(long closeTime) { this.closeTime = closeTime; }
    }

    /**
     * 指数行情响应 DTO（来自新浪）
     */
    public static class IndexQuoteResponse {
        private String symbol;      // 指数代码
        private String name;        // 名称
        private String open;        // 开盘
        private String prevClose;   // 昨收
        private String price;       // 当前价
        private String high;        // 最高
        private String low;         // 最低
        private String volume;      // 成交量
        private String amount;      // 成交额
        private String date;        // 日期
        private String time;        // 时间

        public String getSymbol() { return symbol; }
        public String getName() { return name; }
        public String getOpen() { return open; }
        public String getPrevClose() { return prevClose; }
        public String getPrice() { return price; }
        public String getHigh() { return high; }
        public String getLow() { return low; }
        public String getVolume() { return volume; }
        public String getAmount() { return amount; }
        public String getDate() { return date; }
        public String getTime() { return time; }

        public void setSymbol(String symbol) { this.symbol = symbol; }
        public void setName(String name) { this.name = name; }
        public void setOpen(String open) { this.open = open; }
        public void setPrevClose(String prevClose) { this.prevClose = prevClose; }
        public void setPrice(String price) { this.price = price; }
        public void setHigh(String high) { this.high = high; }
        public void setLow(String low) { this.low = low; }
        public void setVolume(String volume) { this.volume = volume; }
        public void setAmount(String amount) { this.amount = amount; }
        public void setDate(String date) { this.date = date; }
        public void setTime(String time) { this.time = time; }
    }

    /**
     * 通用新浪行情响应 DTO
     */
    public static class SinaQuoteResponse {
        private String name;        // 名称
        private String open;        // 开盘
        private String prevClose;   // 昨收
        private String price;       // 当前价
        private String high;        // 最高
        private String low;         // 最低
        private String volume;      // 成交量
        private String amount;      // 成交额
        private String date;        // 日期
        private String time;        // 时间
        private String change;      // 涨跌额
        private String changePercent; // 涨跌幅

        public String getName() { return name; }
        public String getOpen() { return open; }
        public String getPrevClose() { return prevClose; }
        public String getPrice() { return price; }
        public String getHigh() { return high; }
        public String getLow() { return low; }
        public String getVolume() { return volume; }
        public String getAmount() { return amount; }
        public String getDate() { return date; }
        public String getTime() { return time; }
        public String getChange() { return change; }
        public String getChangePercent() { return changePercent; }

        public void setName(String name) { this.name = name; }
        public void setOpen(String open) { this.open = open; }
        public void setPrevClose(String prevClose) { this.prevClose = prevClose; }
        public void setPrice(String price) { this.price = price; }
        public void setHigh(String high) { this.high = high; }
        public void setLow(String low) { this.low = low; }
        public void setVolume(String volume) { this.volume = volume; }
        public void setAmount(String amount) { this.amount = amount; }
        public void setDate(String date) { this.date = date; }
        public void setTime(String time) { this.time = time; }
        public void setChange(String change) { this.change = change; }
        public void setChangePercent(String changePercent) { this.changePercent = changePercent; }
    }

    /**
     * 排行榜响应 DTO
     */
    public static class RankingResponse {
        private String updateTime;
        private List<SinaQuoteResponse> gainers = new ArrayList<>();
        private List<SinaQuoteResponse> losers = new ArrayList<>();
        private List<SinaQuoteResponse> topVolume = new ArrayList<>();

        public String getUpdateTime() { return updateTime; }
        public List<SinaQuoteResponse> getGainers() { return gainers; }
        public List<SinaQuoteResponse> getLosers() { return losers; }
        public List<SinaQuoteResponse> getTopVolume() { return topVolume; }

        public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
        public void setGainers(List<SinaQuoteResponse> gainers) { this.gainers = gainers; }
        public void setLosers(List<SinaQuoteResponse> losers) { this.losers = losers; }
        public void setTopVolume(List<SinaQuoteResponse> topVolume) { this.topVolume = topVolume; }
    }
}
