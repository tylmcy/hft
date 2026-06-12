package com.hft.trading.service;

import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.model.Instrument;
import com.hft.trading.model.InstrumentType;
import com.hft.trading.queue.AccountInputQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * SinaInstrumentService - 从新浪财经拉取品种数据并初始化系统
 *
 * SOD（Start of Day）流程：
 * 1. 从新浪拉取品种列表的昨收价
 * 2. 生成 INSTRUMENT_INIT 事件写入 AccountInput CQ
 * 3. AccountWorker 处理并将品种注册到 InMemoryInstrumentStore
 */
public class SinaInstrumentService {

    private static final Logger log = LoggerFactory.getLogger(SinaInstrumentService.class);

    private static final String SINA_URL = "https://hq.sinajs.cn/list=";
    private static final long DEFAULT_LOT_SIZE = 100L;
    private static final long DEFAULT_MIN_PRICE_INCREMENT = 1L; // 1分
    private static final int DEFAULT_COMMISSION_RATE = 3; // 万分之3
    private static final double LIMIT_UP_RATIO = 0.10;  // A股涨停10%
    private static final double LIMIT_DOWN_RATIO = 0.10; // A股跌停10%

    private final RestTemplate restTemplate;

    public SinaInstrumentService() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * 从新浪拉取品种数据并初始化系统
     *
     * @param symbols  需要初始化的品种代码列表
     * @param accountInputQueue AccountInput CQ
     * @return 初始化的品种数量
     */
    public int initializeInstruments(List<String> symbols, AccountInputQueue accountInputQueue) {
        if (symbols == null || symbols.isEmpty()) {
            log.warn("No symbols to initialize");
            return 0;
        }

        // 分批拉取（新浪单次最多拉取几十个）
        List<List<String>> batches = partition(symbols, 30);
        int totalInitialized = 0;

        for (List<String> batch : batches) {
            try {
                List<Instrument> instruments = fetchBatch(batch);
                for (Instrument instrument : instruments) {
                    AccountInputEvent event = AccountInputEvent.instrumentInit(instrument);
                    accountInputQueue.writeEvent(event);
                    totalInitialized++;
                    log.info("INSTRUMENT_INIT written: symbol={}, name={}, prevClose={}",
                        instrument.getSymbol(), instrument.getName(), instrument.getPrevClose());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch instrument batch: {}", e.getMessage());
            }
        }

        log.info("Instrument initialization complete: total={}", totalInitialized);
        return totalInitialized;
    }

    /**
     * 拉取一批品种数据
     */
    private List<Instrument> fetchBatch(List<String> symbols) {
        // 构建新浪代码（sh前缀上海，sz前缀深圳）
        StringBuilder sb = new StringBuilder();
        for (String symbol : symbols) {
            if (sb.length() > 0) sb.append(",");
            sb.append(toSinaCode(symbol));
        }

        String raw = fetchSina(sb.toString());
        return parseSinaResponse(raw, symbols);
    }

    /**
     * 将A股代码转为新浪前缀代码
     * 600xxx/601xxx/603xxx/688xxx → sh
     * 000xxx/001xxx/002xxx/300xxx → sz
     */
    private String toSinaCode(String symbol) {
        if (symbol == null || symbol.isEmpty()) return symbol;
        if (symbol.startsWith("6")) {
            return "sh" + symbol;
        } else {
            return "sz" + symbol;
        }
    }

    /**
     * 从新浪拉取原始数据
     */
    private String fetchSina(String codes) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", "https://finance.sina.com.cn");
        HttpEntity<String> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(SINA_URL + codes, HttpMethod.GET, entity, String.class).getBody();
    }

    /**
     * 解析新浪返回的行情数据
     *
     * var hq_str_sh600000="浦发银行,10.50,10.00,10.30,10.55,10.20,...,日期,时间";
     * 字段: 0=名称, 1=开盘, 2=昨收, 3=当前价, 4=最高, 5=最低, 8=成交量, 9=成交额, 30=日期, 31=时间
     */
    private List<Instrument> parseSinaResponse(String raw, List<String> symbols) {
        List<Instrument> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;

        // symbol -> sinaCode lookup
        Map<String, String> symbolToCode = new HashMap<>();
        for (String sym : symbols) {
            symbolToCode.put(sym, toSinaCode(sym));
        }

        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (!line.startsWith("var hq_str_")) continue;
            try {
                // Extract code
                String sinaCode = line.substring("var hq_str_".length(), line.indexOf("=")).trim();

                // Find matching symbol
                String symbol = null;
                for (Map.Entry<String, String> entry : symbolToCode.entrySet()) {
                    if (entry.getValue().equals(sinaCode)) {
                        symbol = entry.getKey();
                        break;
                    }
                }
                if (symbol == null) continue;

                // Parse values
                String values = line.substring(line.indexOf("\"") + 1, line.lastIndexOf("\""));
                String[] parts = values.split(",");

                String name = parts.length > 0 ? parts[0] : symbol;
                double prevClose = parts.length > 2 ? parseDoubleSafe(parts[2]) : 10.0;
                double currentPrice = parts.length > 3 ? parseDoubleSafe(parts[3]) : prevClose;

                // 昨收价（分），如果为0则用当前价
                long prevCloseCents = Math.round((prevClose > 0 ? prevClose : currentPrice) * 100);
                if (prevCloseCents <= 0) {
                    prevCloseCents = 1000L; // 默认10元
                }

                // 确定品种类型
                InstrumentType type = determineType(symbol);

                // 创建 Instrument
                int symbolCode = parseSymbolCode(symbol);
                Instrument instrument = new Instrument(
                    symbol,
                    symbolCode,
                    type,
                    name,
                    prevCloseCents,
                    LIMIT_UP_RATIO,
                    LIMIT_DOWN_RATIO,
                    DEFAULT_LOT_SIZE,
                    DEFAULT_MIN_PRICE_INCREMENT,
                    DEFAULT_COMMISSION_RATE
                );

                result.add(instrument);

            } catch (Exception e) {
                log.warn("Failed to parse Sina instrument line: {}", line);
            }
        }

        return result;
    }

    /**
     * 确定品种类型
     */
    private InstrumentType determineType(String symbol) {
        if (symbol == null) return InstrumentType.STOCK;
        // 300xxx = 创业板（股票）
        // 688xxx = 科创板（股票）
        // 00xxxx = 深市股票
        // 60xxxx = 沪市股票
        // IF/IC/IH/IM = 期货
        if (symbol.startsWith("IF") || symbol.startsWith("IC") ||
            symbol.startsWith("IH") || symbol.startsWith("IM") ||
            symbol.startsWith("TS") || symbol.startsWith("T")) {
            return InstrumentType.FUTURE;
        }
        return InstrumentType.STOCK;
    }

    private double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private int parseSymbolCode(String symbol) {
        try {
            return Integer.parseInt(symbol);
        } catch (NumberFormatException e) {
            return symbol.hashCode();
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
