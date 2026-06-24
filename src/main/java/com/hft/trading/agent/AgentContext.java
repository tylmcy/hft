package com.hft.trading.agent;

import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.lifecycle.PhaseManager;
import com.hft.trading.event.TradingPhase;
import org.springframework.stereotype.Component;

/**
 * Agent 运行上下文
 *
 * 各 Agent 通过此组件感知系统状态，决定是否执行、
 * 是否降级、是否跳过当前周期。
 */
@Component
public class AgentContext {

    private final LifecycleManager lifecycleManager;
    private final PhaseManager phaseManager;
    private volatile boolean degradedMode = false;

    public AgentContext(LifecycleManager lifecycleManager, PhaseManager phaseManager) {
        this.lifecycleManager = lifecycleManager;
        this.phaseManager = phaseManager;
    }

    /**
     * 检查是否应该跳过当前周期
     *
     * 系统在 RUNNING 状态且为交易时段时，Agent 全功能运行。
     * 其他状态：Agent 跳过。
     */
    public boolean shouldSkip() {
        if (lifecycleManager == null) return true;

        LifecycleManager.SystemStatus status = lifecycleManager.getSystemStatus();
        if (status != LifecycleManager.SystemStatus.RUNNING) {
            return true;
        }

        TradingPhase phase = phaseManager.getCurrentPhase();
        return phase != TradingPhase.CONTINUOUS_TRADING;
    }

    public boolean isDegraded() {
        return degradedMode;
    }

    public void setDegraded(boolean degraded) {
        this.degradedMode = degraded;
    }
}
