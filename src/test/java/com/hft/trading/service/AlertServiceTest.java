package com.hft.trading.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AlertService 单元测试
 *
 * 测试 P0/P1/P2 告警功能
 */
class AlertServiceTest {

    @Test
    @DisplayName("P0告警应该正常执行不抛出异常")
    void testSendP0Alert_doesNotThrow() {
        AlertService alertService = new AlertService();
        assertDoesNotThrow(() -> alertService.sendP0Alert("TestComponent", "Test P0 message"));
    }

    @Test
    @DisplayName("P1告警应该正常执行不抛出异常")
    void testSendP1Alert_doesNotThrow() {
        AlertService alertService = new AlertService();
        assertDoesNotThrow(() -> alertService.sendAlert(AlertService.AlertLevel.P1, "TestComponent", "Test P1 message"));
    }

    @Test
    @DisplayName("P2告警应该正常执行不抛出异常")
    void testSendP2Alert_doesNotThrow() {
        AlertService alertService = new AlertService();
        assertDoesNotThrow(() -> alertService.sendAlert(AlertService.AlertLevel.P2, "TestComponent", "Test P2 message"));
    }

    @Test
    @DisplayName("带详细信息的P0告警应该正常执行不抛出异常")
    void testSendP0AlertWithDetails_doesNotThrow() {
        AlertService alertService = new AlertService();
        Object details = new Object();
        assertDoesNotThrow(() -> alertService.sendP0Alert("TestComponent", "Test P0 message", details));
    }

    @Test
    @DisplayName("AlertLevel枚举应该包含P0/P1/P2")
    void testAlertLevelEnum() {
        AlertService.AlertLevel[] levels = AlertService.AlertLevel.values();
        assertEquals(3, levels.length);
        assertEquals(AlertService.AlertLevel.P0, AlertService.AlertLevel.valueOf("P0"));
        assertEquals(AlertService.AlertLevel.P1, AlertService.AlertLevel.valueOf("P1"));
        assertEquals(AlertService.AlertLevel.P2, AlertService.AlertLevel.valueOf("P2"));
    }
}
