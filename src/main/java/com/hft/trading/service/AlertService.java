package com.hft.trading.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AlertService - P0告警服务
 *
 * 当前实现：记录日志
 * 后续扩展：可添加邮件/Slack/Webhook通知
 */
@Service
public class AlertService {

    private static final Logger logger = LoggerFactory.getLogger(AlertService.class);

    public enum AlertLevel {
        P0,  // Critical - immediate attention required
        P1,  // Warning - needs investigation
        P2   // Info - for awareness
    }

    /**
     * 发送告警
     *
     * @param level 告警级别
     * @param component 组件名称
     * @param message 告警消息
     * @param details 详细信息（可选）
     */
    public void sendAlert(AlertLevel level, String component, String message, Object... details) {
        String alertMsg = String.format("[ALERT][%s][%s] %s", level, component, message);

        switch (level) {
            case P0:
                if (details.length > 0) {
                    logger.error(alertMsg, details);
                } else {
                    logger.error(alertMsg);
                }
                break;
            case P1:
                if (details.length > 0) {
                    logger.warn(alertMsg, details);
                } else {
                    logger.warn(alertMsg);
                }
                break;
            case P2:
                if (details.length > 0) {
                    logger.info(alertMsg, details);
                } else {
                    logger.info(alertMsg);
                }
                break;
        }

        // TODO: Future extension - send to external system
        // e.g., emailService.sendAlert(level, message);
        // e.g., slackService.postAlert(level, message);
    }

    /**
     * 发送 P0 告警（便捷方法）
     *
     * @param component 组件名称
     * @param message 告警消息
     */
    public void sendP0Alert(String component, String message) {
        sendAlert(AlertLevel.P0, component, message);
    }

    /**
     * 发送 P0 告警（带详细信息）
     *
     * @param component 组件名称
     * @param message 告警消息
     * @param details 详细信息
     */
    public void sendP0Alert(String component, String message, Object details) {
        sendAlert(AlertLevel.P0, component, message, details);
    }
}
