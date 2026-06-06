package com.hft.trading.lifecycle;

import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.event.TradingPhase;
import com.hft.trading.queue.AccountInputQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * PhaseManager - 交易阶段管理器
 *
 * 根据 V12 技术方案 14.1 节：
 * - CLOSED: 09:15前, 15:00后, 非交易日
 * - PRE_MARKET: 09:15-09:30（集合竞价，首版不支持）
 * - CONTINUOUS_TRADING: 09:30-11:30, 13:00-15:00
 * - LUNCH_BREAK: 11:30-13:00
 */
@Component
public class PhaseManager {

    private static final Logger logger = LoggerFactory.getLogger(PhaseManager.class);

    // 当前交易阶段（volatile 保证跨线程可见性）
    private volatile TradingPhase currentPhase = TradingPhase.CLOSED;

    // 市场开关
    private volatile boolean marketOpen = false;

    // 队列引用
    private AccountInputQueue accountInputQueue;

    // 定时任务执行器
    private ScheduledExecutorService scheduler;

    // 是否为交易日
    private volatile boolean isTradingDay = true;

    /**
     * 启动阶段管理器
     */
    public void start(AccountInputQueue accountInputQueue) {
        this.accountInputQueue = accountInputQueue;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PhaseManager");
            t.setDaemon(true);
            return t;
        });

        // 初始化当前阶段
        updateCurrentPhase();

        // 每秒检查一次交易阶段
        scheduler.scheduleAtFixedRate(this::checkAndTransitionPhase, 1, 1, TimeUnit.SECONDS);

        logger.info("PhaseManager started, current phase: {}", currentPhase);
    }

    /**
     * 停止阶段管理器
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.info("PhaseManager stopped");
    }

    /**
     * 检查并执行阶段转换
     */
    private void checkAndTransitionPhase() {
        TradingPhase newPhase = calculatePhase();

        if (newPhase != currentPhase) {
            transitionTo(newPhase);
        }
    }

    /**
     * 根据当前时间计算交易阶段
     */
    private TradingPhase calculatePhase() {
        if (!isTradingDay) {
            return TradingPhase.CLOSED;
        }

        LocalTime now = LocalTime.now();
        int hour = now.getHour();
        int minute = now.getMinute();

        // 09:15 之前
        if (hour < 9 || (hour == 9 && minute < 15)) {
            return TradingPhase.CLOSED;
        }

        // 09:15 - 09:30 盘前（集合竞价）
        if (hour == 9 && minute >= 15 && minute < 30) {
            return TradingPhase.PRE_MARKET;
        }

        // 09:30 - 11:30 上午连续竞价
        if ((hour == 9 && minute >= 30) || (hour == 10) || (hour == 11 && minute < 30)) {
            return TradingPhase.CONTINUOUS_TRADING;
        }

        // 11:30 - 13:00 午休
        if ((hour == 11 && minute >= 30) || (hour == 12)) {
            return TradingPhase.LUNCH_BREAK;
        }

        // 13:00 - 15:00 下午连续竞价
        if (hour == 13 || hour == 14) {
            return TradingPhase.CONTINUOUS_TRADING;
        }

        // 15:00 之后
        return TradingPhase.CLOSED;
    }

    /**
     * 更新当前阶段（启动时调用）
     */
    private void updateCurrentPhase() {
        currentPhase = calculatePhase();
        marketOpen = (currentPhase == TradingPhase.CONTINUOUS_TRADING);
    }

    /**
     * 执行阶段转换
     */
    private void transitionTo(TradingPhase newPhase) {
        TradingPhase oldPhase = currentPhase;
        logger.info("Phase transition: {} -> {}", oldPhase, newPhase);

        // 仅对 CONTINUOUS_TRADING 和 CLOSED 写入 PHASE_CHANGE 事件
        if (newPhase == TradingPhase.CONTINUOUS_TRADING || newPhase == TradingPhase.CLOSED) {
            writePhaseChangeEvent(newPhase);
        }

        currentPhase = newPhase;
        marketOpen = (newPhase == TradingPhase.CONTINUOUS_TRADING);

        logger.info("Phase updated: currentPhase={}, marketOpen={}", currentPhase, marketOpen);
    }

    /**
     * 写入 PHASE_CHANGE 事件到 account_input CQ
     */
    private void writePhaseChangeEvent(TradingPhase phase) {
        if (accountInputQueue == null) {
            logger.warn("AccountInputQueue not initialized, skipping PHASE_CHANGE event");
            return;
        }

        AccountInputEvent event = AccountInputEvent.phaseChange(phase);
        accountInputQueue.writeEvent(event);
        logger.info("PHASE_CHANGE event written: phase={}", phase);
    }

    // ==================== Getter ====================

    public TradingPhase getCurrentPhase() {
        return currentPhase;
    }

    public boolean isMarketOpen() {
        return marketOpen;
    }

    public boolean canPlaceOrder() {
        return marketOpen && isTradingDay;
    }

    public boolean canCancelOrder() {
        return marketOpen;
    }

    public void setTradingDay(boolean tradingDay) {
        this.isTradingDay = tradingDay;
        if (!tradingDay) {
            currentPhase = TradingPhase.CLOSED;
            marketOpen = false;
        }
    }

    /**
     * 等待市场开盘
     */
    public void waitForMarketOpen() {
        while (!marketOpen) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
