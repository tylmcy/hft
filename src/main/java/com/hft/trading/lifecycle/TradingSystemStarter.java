package com.hft.trading.lifecycle;

import com.hft.trading.queue.AccountInputQueue;
import com.hft.trading.queue.QueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * TradingSystemStarter - 交易系统启动协调器
 *
 * 在 Spring Boot 完成初始化后启动各个组件
 */
@Component
public class TradingSystemStarter {

    private static final Logger logger = LoggerFactory.getLogger(TradingSystemStarter.class);

    private final LifecycleManager lifecycleManager;
    private final PhaseManager phaseManager;
    private final QueueManager queueManager;

    public TradingSystemStarter(LifecycleManager lifecycleManager,
                                 PhaseManager phaseManager,
                                 QueueManager queueManager) {
        this.lifecycleManager = lifecycleManager;
        this.phaseManager = phaseManager;
        this.queueManager = queueManager;
    }

    /**
     * Spring Boot 初始化完成后启动交易系统
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("Application ready, starting trading system components...");

        // 启动 PhaseManager
        phaseManager.start(queueManager.getAccountInputQueue());

        logger.info("Trading system components started");
        logger.info("System status: {}", lifecycleManager.getSystemStatus());
        logger.info("Current phase: {}", phaseManager.getCurrentPhase());
    }
}