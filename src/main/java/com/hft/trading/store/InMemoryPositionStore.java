package com.hft.trading.store;

import com.hft.trading.model.Position;
import com.hft.trading.service.AlertService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持仓内存存储
 *
 * 根据 V12 技术方案 3.2.2：
 * - 使用 ConcurrentHashMap<String, Position> 存储，key=accountId:symbol
 * - 支持 freezePosition/unfreezePosition/settleBuyPosition/settleSellPosition 操作
 * - 每次操作后验证不变式
 */
public class InMemoryPositionStore {

    private final ConcurrentHashMap<String, Position> positions = new ConcurrentHashMap<>();
    private final AlertService alertService;

    public InMemoryPositionStore(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 生成存储key
     */
    private String key(long accountId, String symbol) {
        return accountId + ":" + symbol;
    }

    /**
     * 添加持仓
     */
    public void addPosition(Position position) {
        positions.put(key(position.getAccountId(), position.getSymbol()), position);
    }

    /**
     * 获取持仓
     */
    public Position getPosition(long accountId, String symbol) {
        return positions.get(key(accountId, symbol));
    }

    /**
     * 移除持仓
     */
    public void removePosition(long accountId, String symbol) {
        positions.remove(key(accountId, symbol));
    }

    /**
     * 冻结持仓
     *
     * @param accountId 账户ID
     * @param symbol    证券代码
     * @param qty       冻结数量
     * @throws IllegalStateException 持仓不存在/可用不足
     */
    public void freezePosition(long accountId, String symbol, long qty) {
        Position position = positions.get(key(accountId, symbol));
        if (position == null) {
            throw new IllegalStateException("持仓不存在: accountId=" + accountId + ", symbol=" + symbol);
        }
        position.freezePosition(qty);
        validateInvariant(position);
    }

    /**
     * 解冻持仓（静默处理不存在持仓）
     *
     * @param accountId 账户ID
     * @param symbol    证券代码
     * @param qty       解冻数量
     */
    public void unfreezePosition(long accountId, String symbol, long qty) {
        Position position = positions.get(key(accountId, symbol));
        if (position == null) {
            // 静默处理
            return;
        }
        position.unfreezePosition(qty);
        validateInvariant(position);
    }

    /**
     * 买方结算持仓（自动创建不存在持仓）
     *
     * @param accountId 账户ID
     * @param symbol    证券代码
     * @param qty       成交数量
     * @param price     成交价格（分）
     */
    public void settleBuyPosition(long accountId, String symbol, long qty, long price) {
        String key = key(accountId, symbol);
        Position position = positions.get(key);

        if (position == null) {
            // 自动创建持仓
            int symbolCode = Integer.parseInt(symbol);
            position = new Position(accountId, symbol, symbolCode);
            positions.put(key, position);
        }

        position.settleBuy(qty, price);
        validateInvariant(position);
    }

    /**
     * 卖方结算持仓
     *
     * @param accountId 账户ID
     * @param symbol    证券代码
     * @param qty       成交数量
     * @throws IllegalStateException 持仓不存在
     */
    public void settleSellPosition(long accountId, String symbol, long qty) {
        String key = key(accountId, symbol);
        Position position = positions.get(key);

        if (position == null) {
            throw new IllegalStateException("持仓不存在: accountId=" + accountId + ", symbol=" + symbol);
        }

        position.settleSell(qty);

        // 全部卖出后移除持仓
        if (position.isEmpty()) {
            positions.remove(key);
        } else {
            validateInvariant(position);
        }
    }

    /**
     * T+1 解锁单个持仓
     *
     * @param accountId 账户ID
     * @param symbol    证券代码
     */
    public void unlockT1(long accountId, String symbol) {
        Position position = positions.get(key(accountId, symbol));
        if (position != null) {
            position.unlockT1();
            validateInvariant(position);
        }
    }

    /**
     * T+1 解锁账户所有持仓（批量）
     *
     * @param accountId 账户ID
     */
    public void unlockT1ForAccount(long accountId) {
        positions.values().stream()
            .filter(p -> p.getAccountId() == accountId)
            .forEach(p -> {
                p.unlockT1();
                validateInvariant(p);
            });
    }

    /**
     * 获取账户所有持仓
     *
     * @param accountId 账户ID
     * @return 持仓列表
     */
    public List<Position> getPositionsByAccount(long accountId) {
        List<Position> result = new ArrayList<>();
        positions.values().stream()
            .filter(p -> p.getAccountId() == accountId)
            .forEach(result::add);
        return result;
    }

    /**
     * 验证不变式
     */
    private void validateInvariant(Position position) {
        if (!position.validateInvariant()) {
            // 自动修复尝试（未来版本实现）
            // 当前版本：记录告警
            alertService.sendP0Alert("InMemoryPositionStore",
                "Position invariant violation: accountId=" + position.getAccountId() +
                ", symbol=" + position.getSymbol());
        }
    }

    /**
     * 获取所有持仓数量
     */
    public int size() {
        return positions.size();
    }

    /**
     * 获取所有持仓
     */
    public List<Position> getAllPositions() {
        return new ArrayList<>(positions.values());
    }
}