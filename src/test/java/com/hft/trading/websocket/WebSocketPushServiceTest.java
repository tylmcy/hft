package com.hft.trading.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/**
 * WebSocketPushService Test
 *
 * Tests the push message encapsulation service.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketPushServiceTest {

    @Mock
    private WebSocketServer webSocketServer;

    private WebSocketPushService pushService;

    @BeforeEach
    void setUp() {
        pushService = new WebSocketPushService(webSocketServer);
    }

    @Test
    @DisplayName("testPushOrderUpdate - should call sendToUser with order_update channel")
    void testPushOrderUpdate() {
        // Given
        long userId = 1001L;
        WebSocketPushService.OrderUpdateMessage message = new WebSocketPushService.OrderUpdateMessage();
        message.setOrderId("ORD123456");
        message.setSymbol("600000");
        message.setSide("BUY");
        message.setOrderType("LIMIT");
        message.setQuantity(1000L);
        message.setPrice(10.5);
        message.setFilledQuantity(500L);
        message.setAvgPrice(10.48);
        message.setStatus("PARTIAL_FILLED");

        // When
        pushService.pushOrderUpdate(userId, message);

        // Then
        verify(webSocketServer).sendToUser(userId, "order_update", message);
    }

    @Test
    @DisplayName("testPushTradeUpdate - should call sendToUser with trade_update channel")
    void testPushTradeUpdate() {
        // Given
        long userId = 1001L;
        WebSocketPushService.TradeUpdateMessage message = new WebSocketPushService.TradeUpdateMessage();
        message.setTradeId("TRD789012");
        message.setOrderId("ORD123456");
        message.setSymbol("600000");
        message.setSide("BUY");
        message.setQuantity(500L);
        message.setPrice(10.48);
        message.setTradeTime(System.currentTimeMillis());

        // When
        pushService.pushTradeUpdate(userId, message);

        // Then
        verify(webSocketServer).sendToUser(userId, "trade_update", message);
    }

    @Test
    @DisplayName("testPushMarketSnapshot - should call broadcast with market_snapshot channel")
    void testPushMarketSnapshot() {
        // Given
        String symbol = "600000";
        WebSocketPushService.MarketSnapshotMessage message = new WebSocketPushService.MarketSnapshotMessage();
        message.setSymbol("600000");
        message.setLastPrice(10.5);
        message.setBidPrice1(10.49);
        message.setBidVolume1(10000L);
        message.setAskPrice1(10.51);
        message.setAskVolume1(8000L);
        message.setTimestamp(System.currentTimeMillis());

        // When
        pushService.pushMarketSnapshot(symbol, message);

        // Then
        verify(webSocketServer).broadcast("market_snapshot", symbol, message);
    }

    @Test
    @DisplayName("testPushAccountUpdate - should call sendToUser with account_update channel")
    void testPushAccountUpdate() {
        // Given
        long userId = 1001L;
        WebSocketPushService.AccountUpdateMessage message = new WebSocketPushService.AccountUpdateMessage();
        message.setUserId(userId);
        message.setBalance(100000.0);
        message.setAvailable(80000.0);
        message.setFrozen(20000.0);
        message.setUpdateTime(System.currentTimeMillis());

        // When
        pushService.pushAccountUpdate(userId, message);

        // Then
        verify(webSocketServer).sendToUser(userId, "account_update", message);
    }

    @Test
    @DisplayName("testPushPositionUpdate - should call sendToUser with position_update channel")
    void testPushPositionUpdate() {
        // Given
        long userId = 1001L;
        WebSocketPushService.PositionUpdateMessage message = new WebSocketPushService.PositionUpdateMessage();
        message.setUserId(userId);
        message.setSymbol("600000");
        message.setQuantity(5000L);
        message.setAvailable(3000L);
        message.setFrozen(2000L);
        message.setAvgCost(10.0);
        message.setUpdateTime(System.currentTimeMillis());

        // When
        pushService.pushPositionUpdate(userId, message);

        // Then
        verify(webSocketServer).sendToUser(userId, "position_update", message);
    }
}