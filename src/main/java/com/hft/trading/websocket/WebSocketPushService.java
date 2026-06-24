package com.hft.trading.websocket;

import com.hft.trading.model.KLine;
import org.springframework.stereotype.Service;

/**
 * WebSocketPushService - WebSocket 推送服务
 *
 * 封装 WebSocket 消息推送逻辑，提供以下推送能力：
 * - 订单状态更新推送 (order_update)
 * - 成交信息推送 (trade_update)
 * - 行情快照推送 (market_snapshot)
 * - 账户资金变动推送 (account_update)
 * - 持仓变动推送 (position_update)
 */
@Service
public class WebSocketPushService {

    private final WebSocketServer webSocketServer;

    public WebSocketPushService(WebSocketServer webSocketServer) {
        this.webSocketServer = webSocketServer;
    }

    /**
     * 推送订单状态更新
     */
    public void pushOrderUpdate(long userId, OrderUpdateMessage message) {
        webSocketServer.sendToUser(userId, "order_update", message);
    }

    /**
     * 推送成交信息
     */
    public void pushTradeUpdate(long userId, TradeUpdateMessage message) {
        webSocketServer.sendToUser(userId, "trade_update", message);
    }

    /**
     * 推送行情快照
     */
    public void pushMarketSnapshot(String symbol, MarketSnapshotMessage message) {
        webSocketServer.broadcast("market_snapshot", symbol, message);
    }

    /**
     * 推送账户资金变动
     */
    public void pushAccountUpdate(long userId, AccountUpdateMessage message) {
        webSocketServer.sendToUser(userId, "account_update", message);
    }

    /**
     * 推送持仓变动
     */
    public void pushPositionUpdate(long userId, PositionUpdateMessage message) {
        webSocketServer.sendToUser(userId, "position_update", message);
    }

    /**
     * 推送K线数据
     */
    public void pushKLine(KLine kline) {
        KLineMessage message = new KLineMessage();
        message.setSymbol(kline.getSymbol());
        message.setInterval(kline.getInterval());
        message.setOpenTime(kline.getOpenTime());
        message.setCloseTime(kline.getCloseTime());
        message.setOpenPrice(kline.getOpenPrice() / 10000.0); // 分转元
        message.setHighPrice(kline.getHighPrice() / 10000.0);
        message.setLowPrice(kline.getLowPrice() / 10000.0);
        message.setClosePrice(kline.getClosePrice() / 10000.0);
        message.setVolume(kline.getVolume());
        message.setAmount(kline.getAmount() / 10000.0);
        message.setTradeCount(kline.getTradeCount());
        webSocketServer.broadcast("kline", kline.getSymbol(), message);
    }

    /**
     * 推送 Agent 告警
     */
    public void pushAgentAlert(AgentAlertMessage message) {
        webSocketServer.broadcast("agent_alert", message);
    }

    // ========== Inner Message DTOs ==========

    /**
     * 订单状态更新消息
     */
    public static class OrderUpdateMessage {
        private String orderId;
        private String symbol;
        private String side;
        private String orderType;
        private Long quantity;
        private Double price;
        private Long filledQuantity;
        private Double avgPrice;
        private String status;

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public String getSide() {
            return side;
        }

        public void setSide(String side) {
            this.side = side;
        }

        public String getOrderType() {
            return orderType;
        }

        public void setOrderType(String orderType) {
            this.orderType = orderType;
        }

        public Long getQuantity() {
            return quantity;
        }

        public void setQuantity(Long quantity) {
            this.quantity = quantity;
        }

        public Double getPrice() {
            return price;
        }

        public void setPrice(Double price) {
            this.price = price;
        }

        public Long getFilledQuantity() {
            return filledQuantity;
        }

        public void setFilledQuantity(Long filledQuantity) {
            this.filledQuantity = filledQuantity;
        }

        public Double getAvgPrice() {
            return avgPrice;
        }

        public void setAvgPrice(Double avgPrice) {
            this.avgPrice = avgPrice;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    /**
     * 成交信息消息
     */
    public static class TradeUpdateMessage {
        private String tradeId;
        private String orderId;
        private String symbol;
        private String side;
        private Long quantity;
        private Double price;
        private Long tradeTime;

        public String getTradeId() {
            return tradeId;
        }

        public void setTradeId(String tradeId) {
            this.tradeId = tradeId;
        }

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public String getSide() {
            return side;
        }

        public void setSide(String side) {
            this.side = side;
        }

        public Long getQuantity() {
            return quantity;
        }

        public void setQuantity(Long quantity) {
            this.quantity = quantity;
        }

        public Double getPrice() {
            return price;
        }

        public void setPrice(Double price) {
            this.price = price;
        }

        public Long getTradeTime() {
            return tradeTime;
        }

        public void setTradeTime(Long tradeTime) {
            this.tradeTime = tradeTime;
        }
    }

    /**
     * 行情快照消息
     */
    public static class MarketSnapshotMessage {
        private String symbol;
        private Double lastPrice;
        private Double bidPrice1;
        private Long bidVolume1;
        private Double askPrice1;
        private Long askVolume1;
        private Long timestamp;

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public Double getLastPrice() {
            return lastPrice;
        }

        public void setLastPrice(Double lastPrice) {
            this.lastPrice = lastPrice;
        }

        public Double getBidPrice1() {
            return bidPrice1;
        }

        public void setBidPrice1(Double bidPrice1) {
            this.bidPrice1 = bidPrice1;
        }

        public Long getBidVolume1() {
            return bidVolume1;
        }

        public void setBidVolume1(Long bidVolume1) {
            this.bidVolume1 = bidVolume1;
        }

        public Double getAskPrice1() {
            return askPrice1;
        }

        public void setAskPrice1(Double askPrice1) {
            this.askPrice1 = askPrice1;
        }

        public Long getAskVolume1() {
            return askVolume1;
        }

        public void setAskVolume1(Long askVolume1) {
            this.askVolume1 = askVolume1;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }
    }

    /**
     * 账户资金变动消息
     */
    public static class AccountUpdateMessage {
        private Long userId;
        private Double balance;
        private Double available;
        private Double frozen;
        private Long updateTime;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public Double getBalance() {
            return balance;
        }

        public void setBalance(Double balance) {
            this.balance = balance;
        }

        public Double getAvailable() {
            return available;
        }

        public void setAvailable(Double available) {
            this.available = available;
        }

        public Double getFrozen() {
            return frozen;
        }

        public void setFrozen(Double frozen) {
            this.frozen = frozen;
        }

        public Long getUpdateTime() {
            return updateTime;
        }

        public void setUpdateTime(Long updateTime) {
            this.updateTime = updateTime;
        }
    }

    /**
     * 持仓变动消息
     */
    public static class PositionUpdateMessage {
        private Long userId;
        private String symbol;
        private Long quantity;
        private Long available;
        private Long frozen;
        private Double avgCost;
        private Long updateTime;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public Long getQuantity() {
            return quantity;
        }

        public void setQuantity(Long quantity) {
            this.quantity = quantity;
        }

        public Long getAvailable() {
            return available;
        }

        public void setAvailable(Long available) {
            this.available = available;
        }

        public Long getFrozen() {
            return frozen;
        }

        public void setFrozen(Long frozen) {
            this.frozen = frozen;
        }

        public Double getAvgCost() {
            return avgCost;
        }

        public void setAvgCost(Double avgCost) {
            this.avgCost = avgCost;
        }

        public Long getUpdateTime() {
            return updateTime;
        }

        public void setUpdateTime(Long updateTime) {
            this.updateTime = updateTime;
        }
    }

    /**
     * K线数据消息
     */
    public static class KLineMessage {
        private String symbol;
        private String interval;
        private Long openTime;
        private Long closeTime;
        private Double openPrice;
        private Double highPrice;
        private Double lowPrice;
        private Double closePrice;
        private Long volume;
        private Double amount;
        private Integer tradeCount;

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

        public Long getOpenTime() {
            return openTime;
        }

        public void setOpenTime(Long openTime) {
            this.openTime = openTime;
        }

        public Long getCloseTime() {
            return closeTime;
        }

        public void setCloseTime(Long closeTime) {
            this.closeTime = closeTime;
        }

        public Double getOpenPrice() {
            return openPrice;
        }

        public void setOpenPrice(Double openPrice) {
            this.openPrice = openPrice;
        }

        public Double getHighPrice() {
            return highPrice;
        }

        public void setHighPrice(Double highPrice) {
            this.highPrice = highPrice;
        }

        public Double getLowPrice() {
            return lowPrice;
        }

        public void setLowPrice(Double lowPrice) {
            this.lowPrice = lowPrice;
        }

        public Double getClosePrice() {
            return closePrice;
        }

        public void setClosePrice(Double closePrice) {
            this.closePrice = closePrice;
        }

        public Long getVolume() {
            return volume;
        }

        public void setVolume(Long volume) {
            this.volume = volume;
        }

        public Double getAmount() {
            return amount;
        }

        public void setAmount(Double amount) {
            this.amount = amount;
        }

        public Integer getTradeCount() {
            return tradeCount;
        }

        public void setTradeCount(Integer tradeCount) {
            this.tradeCount = tradeCount;
        }
    }

    /**
     * Agent 告警消息
     */
    public static class AgentAlertMessage {
        private String agentName;
        private String alertLevel;
        private String checkType;
        private Long accountId;
        private String symbol;
        private String detail;
        private String suggestion;
        private long timestamp;

        public String getAgentName() {
            return agentName;
        }

        public void setAgentName(String agentName) {
            this.agentName = agentName;
        }

        public String getAlertLevel() {
            return alertLevel;
        }

        public void setAlertLevel(String alertLevel) {
            this.alertLevel = alertLevel;
        }

        public String getCheckType() {
            return checkType;
        }

        public void setCheckType(String checkType) {
            this.checkType = checkType;
        }

        public Long getAccountId() {
            return accountId;
        }

        public void setAccountId(Long accountId) {
            this.accountId = accountId;
        }

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }

        public String getSuggestion() {
            return suggestion;
        }

        public void setSuggestion(String suggestion) {
            this.suggestion = suggestion;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }
}
