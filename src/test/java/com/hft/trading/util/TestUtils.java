package com.hft.trading.util;

import com.hft.trading.service.AlertService;

/**
 * Test utilities for creating test instances
 */
public class TestUtils {

    /**
     * Create a no-op AlertService for tests
     */
    public static AlertService createTestAlertService() {
        return new AlertService();
    }
}
