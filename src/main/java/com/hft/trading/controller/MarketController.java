package com.hft.trading.controller;

import com.hft.trading.dto.ApiResponse;
import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.matching.OrderBook;
import com.hft.trading.model.Instrument;
import com.hft.trading.store.InMemoryInstrumentStore;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 行情 REST API 控制器
 *
 * 根据 V12 技术方案 8.1 节实现
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final InMemoryInstrumentStore instrumentStore;
    private final LifecycleManager lifecycleManager;

    public MarketController(InMemoryInstrumentStore instrumentStore,
                             LifecycleManager lifecycleManager) {
        this.instrumentStore = instrumentStore;
        this.lifecycleManager = lifecycleManager;
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
}
