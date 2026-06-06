package com.hft.trading.controller;

import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.matching.OrderBook;
import com.hft.trading.model.Instrument;
import com.hft.trading.model.InstrumentType;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.worker.MatchingEngineWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * MarketController Unit Tests
 *
 * Tests REST API endpoints for market data using Mockito.
 */
@ExtendWith(MockitoExtension.class)
class MarketControllerTest {

    @Mock
    private InMemoryInstrumentStore instrumentStore;

    @Mock
    private MatchingEngineWorker matchingEngineWorker;

    @Mock
    private LifecycleManager lifecycleManager;

    private MarketController marketController;

    @BeforeEach
    void setUp() {
        marketController = new MarketController(
            instrumentStore,
            matchingEngineWorker,
            lifecycleManager
        );
    }

    // ==================== Get Symbols Tests ====================

    @Test
    @DisplayName("testGetSymbols - verify symbols list returned")
    void testGetSymbols() {
        // Arrange
        Instrument instrument1 = new Instrument(
            "600000", 600000, InstrumentType.STOCK, "Pufa Bank",
            105000L, 0.10, 0.10, 100L, 1L, 3
        );
        Instrument instrument2 = new Instrument(
            "600001", 600001, InstrumentType.STOCK, "SPDBank",
            100000L, 0.10, 0.10, 100L, 1L, 3
        );

        when(instrumentStore.getAllInstruments())
            .thenReturn(List.of(instrument1, instrument2));

        // Act
        var response = marketController.getSymbols();

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(2, response.getData().size());

        // Check first symbol
        var sym1 = response.getData().get(0);
        assertEquals("600000", sym1.getSymbol());
        assertEquals("Pufa Bank", sym1.getName());
        assertEquals("STOCK", sym1.getType());
        assertEquals("105000", sym1.getPrevClose());
        assertEquals("100", sym1.getLotSize());

        // Check second symbol
        var sym2 = response.getData().get(1);
        assertEquals("600001", sym2.getSymbol());
        assertEquals("SPDBank", sym2.getName());
    }

    @Test
    @DisplayName("testGetSymbols_EmptyList - verify empty list for no instruments")
    void testGetSymbols_EmptyList() {
        // Arrange
        when(instrumentStore.getAllInstruments())
            .thenReturn(Collections.emptyList());

        // Act
        var response = marketController.getSymbols();

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertTrue(response.getData().isEmpty());
    }

    @Test
    @DisplayName("testGetSymbols_LimitPrices - verify limit up/down prices included")
    void testGetSymbols_LimitPrices() {
        // Arrange
        // prevClose = 100000 (1000.00), 10% limit
        // limitUp = 110000 (1100.00), limitDown = 90000 (900.00)
        Instrument instrument = new Instrument(
            "600000", 600000, InstrumentType.STOCK, "Pufa Bank",
            100000L, 0.10, 0.10, 100L, 1L, 3
        );

        when(instrumentStore.getAllInstruments())
            .thenReturn(List.of(instrument));

        // Act
        var response = marketController.getSymbols();

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());

        var sym = response.getData().get(0);
        assertEquals("110000", sym.getLimitUpPrice());
        assertEquals("90000", sym.getLimitDownPrice());
    }

    // ==================== Get Quote Tests ====================

    @Test
    @DisplayName("testGetQuote - verify quote data returned")
    void testGetQuote() {
        // Arrange
        OrderBook.PriceLevel bid1 = mock(OrderBook.PriceLevel.class);
        when(bid1.getPrice()).thenReturn(105000L);
        when(bid1.getVolume()).thenReturn(1000L);

        OrderBook.PriceLevel bid2 = mock(OrderBook.PriceLevel.class);
        when(bid2.getPrice()).thenReturn(104900L);
        when(bid2.getVolume()).thenReturn(2000L);

        OrderBook.PriceLevel ask1 = mock(OrderBook.PriceLevel.class);
        when(ask1.getPrice()).thenReturn(105100L);
        when(ask1.getVolume()).thenReturn(1500L);

        OrderBook.PriceLevel ask2 = mock(OrderBook.PriceLevel.class);
        when(ask2.getPrice()).thenReturn(105200L);
        when(ask2.getVolume()).thenReturn(2500L);

        OrderBook.FiveLevelQuote quote = mock(OrderBook.FiveLevelQuote.class);
        when(quote.getSymbol()).thenReturn("600000");
        when(quote.getUpdateTime()).thenReturn(System.currentTimeMillis());
        when(quote.getBidLevels()).thenReturn(List.of(bid1, bid2));
        when(quote.getAskLevels()).thenReturn(List.of(ask1, ask2));

        when(matchingEngineWorker.getFiveLevelQuote("600000")).thenReturn(quote);

        // Act
        var response = marketController.getQuote("600000");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals("600000", response.getData().getSymbol());

        // Check bid levels
        assertNotNull(response.getData().getBidLevels());
        assertEquals(2, response.getData().getBidLevels().size());
        assertEquals("105000", response.getData().getBidLevels().get(0).getPrice());
        assertEquals("1000", response.getData().getBidLevels().get(0).getVolume());

        // Check ask levels
        assertNotNull(response.getData().getAskLevels());
        assertEquals(2, response.getData().getAskLevels().size());
        assertEquals("105100", response.getData().getAskLevels().get(0).getPrice());
        assertEquals("1500", response.getData().getAskLevels().get(0).getVolume());
    }

    @Test
    @DisplayName("testGetQuote_NotFound - verify error for non-existent symbol")
    void testGetQuote_NotFound() {
        // Arrange
        when(matchingEngineWorker.getFiveLevelQuote(anyString())).thenReturn(null);

        // Act
        var response = marketController.getQuote("999999");

        // Assert
        assertNotNull(response);
        assertEquals("4003", response.getCode());
        assertEquals("品种不存在", response.getMessage());
    }

    @Test
    @DisplayName("testGetQuote_EmptyOrderBook - verify quote with empty levels")
    void testGetQuote_EmptyOrderBook() {
        // Arrange
        OrderBook.FiveLevelQuote quote = mock(OrderBook.FiveLevelQuote.class);
        when(quote.getSymbol()).thenReturn("600000");
        when(quote.getUpdateTime()).thenReturn(System.currentTimeMillis());
        when(quote.getBidLevels()).thenReturn(Collections.emptyList());
        when(quote.getAskLevels()).thenReturn(Collections.emptyList());

        when(matchingEngineWorker.getFiveLevelQuote("600000")).thenReturn(quote);

        // Act
        var response = marketController.getQuote("600000");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals("600000", response.getData().getSymbol());
        assertTrue(response.getData().getBidLevels().isEmpty());
        assertTrue(response.getData().getAskLevels().isEmpty());
    }

    @Test
    @DisplayName("testGetQuote_FiveLevels - verify all five levels returned")
    void testGetQuote_FiveLevels() {
        // Arrange
        OrderBook.PriceLevel[] bidLevels = new OrderBook.PriceLevel[5];
        OrderBook.PriceLevel[] askLevels = new OrderBook.PriceLevel[5];

        for (int i = 0; i < 5; i++) {
            bidLevels[i] = mock(OrderBook.PriceLevel.class);
            when(bidLevels[i].getPrice()).thenReturn(105000L - i * 100L);
            when(bidLevels[i].getVolume()).thenReturn(1000L + i * 100L);

            askLevels[i] = mock(OrderBook.PriceLevel.class);
            when(askLevels[i].getPrice()).thenReturn(105100L + i * 100L);
            when(askLevels[i].getVolume()).thenReturn(1000L + i * 100L);
        }

        OrderBook.FiveLevelQuote quote = mock(OrderBook.FiveLevelQuote.class);
        when(quote.getSymbol()).thenReturn("600000");
        when(quote.getUpdateTime()).thenReturn(System.currentTimeMillis());
        when(quote.getBidLevels()).thenReturn(List.of(bidLevels));
        when(quote.getAskLevels()).thenReturn(List.of(askLevels));

        when(matchingEngineWorker.getFiveLevelQuote("600000")).thenReturn(quote);

        // Act
        var response = marketController.getQuote("600000");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(5, response.getData().getBidLevels().size());
        assertEquals(5, response.getData().getAskLevels().size());

        // Verify bid levels are ordered (highest first)
        assertEquals("105000", response.getData().getBidLevels().get(0).getPrice());
        assertEquals("104900", response.getData().getBidLevels().get(1).getPrice());

        // Verify ask levels are ordered (lowest first)
        assertEquals("105100", response.getData().getAskLevels().get(0).getPrice());
        assertEquals("105200", response.getData().getAskLevels().get(1).getPrice());
    }
}