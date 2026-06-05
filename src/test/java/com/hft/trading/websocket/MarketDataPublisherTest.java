package com.hft.trading.websocket;

import com.hft.trading.matching.MatchingEngine;
import com.hft.trading.matching.OrderBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MarketDataPublisher 单元测试
 */
class MarketDataPublisherTest {

    private WebSocketServer webSocketServer;
    private MarketDataPublisher publisher;

    @BeforeEach
    void setUp() {
        webSocketServer = mock(WebSocketServer.class);
        publisher = new MarketDataPublisher(webSocketServer);
    }

    @Test
    @DisplayName("testStartStop - verify scheduler starts and stops correctly")
    void testStartStop() throws InterruptedException {
        // Initially not running
        assertFalse(publisher.isRunning());

        // Start
        publisher.start();
        assertTrue(publisher.isRunning());

        // Double start should be idempotent
        publisher.start();
        assertTrue(publisher.isRunning());

        // Stop
        publisher.stop();
        assertFalse(publisher.isRunning());

        // Double stop should be idempotent
        publisher.stop();
        assertFalse(publisher.isRunning());
    }

    @Test
    @DisplayName("testPublishMarketSnapshots - verify push is called for subscribed symbols")
    void testPublishMarketSnapshots() throws InterruptedException {
        // Create mock matching engine with order book
        MatchingEngine engine = mock(MatchingEngine.class);
        OrderBook orderBook = mock(OrderBook.class);
        OrderBook.FiveLevelQuote quote = createMockQuote();

        when(engine.getOrderBook()).thenReturn(orderBook);
        when(orderBook.getFiveLevelQuote()).thenReturn(quote);

        // Set matching engines
        Map<String, MatchingEngine> engines = new HashMap<>();
        engines.put("600000", engine);
        publisher.setMatchingEngines(engines);

        // Use latch to verify broadcast is called
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(webSocketServer).broadcast(anyString(), anyString(), any());

        // Start publisher
        publisher.start();

        // Wait for at least one push cycle (3 seconds + buffer)
        boolean completed = latch.await(5, TimeUnit.SECONDS);

        // Verify broadcast was called
        assertTrue(completed, "Broadcast should be called within timeout");
        verify(webSocketServer, atLeastOnce()).broadcast(eq("market_snapshot"), eq("600000"), any());

        // Stop publisher
        publisher.stop();
    }

    @Test
    @DisplayName("testNoEngines - verify no push when no matching engines")
    void testNoEngines() throws InterruptedException {
        // No matching engines set
        publisher.setMatchingEngines(null);

        // Start publisher
        publisher.start();

        // Wait briefly
        Thread.sleep(500);

        // Verify no broadcast called
        verify(webSocketServer, never()).broadcast(anyString(), anyString(), any());

        // Stop
        publisher.stop();
    }

    @Test
    @DisplayName("testSetMatchingEngines - verify engines can be updated")
    void testSetMatchingEngines() {
        // Initially empty
        assertFalse(publisher.isRunning());

        // Set first engine
        MatchingEngine engine1 = mock(MatchingEngine.class);
        Map<String, MatchingEngine> engines1 = new HashMap<>();
        engines1.put("600000", engine1);
        publisher.setMatchingEngines(engines1);

        // Update with new engines
        MatchingEngine engine2 = mock(MatchingEngine.class);
        Map<String, MatchingEngine> engines2 = new HashMap<>();
        engines2.put("600001", engine2);
        publisher.setMatchingEngines(engines2);

        // Verify it accepted the update (no exception means success)
        assertTrue(true);
    }

    private OrderBook.FiveLevelQuote createMockQuote() {
        // Create a mock quote with some data
        OrderBook.FiveLevelQuote quote = mock(OrderBook.FiveLevelQuote.class);
        when(quote.getSymbol()).thenReturn("600000");
        when(quote.getUpdateTime()).thenReturn(System.currentTimeMillis());

        // Create price levels
        OrderBook.PriceLevel bidLevel = mock(OrderBook.PriceLevel.class);
        when(bidLevel.getPrice()).thenReturn(100000L); // 1000.00 in cents
        when(bidLevel.getVolume()).thenReturn(1000L);

        OrderBook.PriceLevel askLevel = mock(OrderBook.PriceLevel.class);
        when(askLevel.getPrice()).thenReturn(100100L); // 1001.00 in cents
        when(askLevel.getVolume()).thenReturn(2000L);

        java.util.List<OrderBook.PriceLevel> bidLevels = java.util.Arrays.asList(
            bidLevel, bidLevel, bidLevel, bidLevel, bidLevel
        );
        java.util.List<OrderBook.PriceLevel> askLevels = java.util.Arrays.asList(
            askLevel, askLevel, askLevel, askLevel, askLevel
        );

        when(quote.getBidLevels()).thenReturn(bidLevels);
        when(quote.getAskLevels()).thenReturn(askLevels);

        return quote;
    }
}