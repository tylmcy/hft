package com.hft.trading.controller;

import com.hft.trading.dto.AccountInfoResponse;
import com.hft.trading.dto.ApiResponse;
import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.model.Account;
import com.hft.trading.model.Position;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryPositionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 账户 REST API 控制器
 *
 * 根据 V12 技术方案 8.1 节实现
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private static final Logger logger = LoggerFactory.getLogger(AccountController.class);

    private final InMemoryAccountStore accountStore;
    private final InMemoryPositionStore positionStore;
    private final LifecycleManager lifecycleManager;

    public AccountController(InMemoryAccountStore accountStore,
                             InMemoryPositionStore positionStore,
                             LifecycleManager lifecycleManager) {
        this.accountStore = accountStore;
        this.positionStore = positionStore;
        this.lifecycleManager = lifecycleManager;
    }

    /**
     * 获取账户信息
     * GET /api/account/info
     */
    @GetMapping("/info")
    public ApiResponse<AccountInfoResponse> getAccountInfo(
            @RequestParam String accountId) {

        if (!lifecycleManager.isAcceptingRequests()) {
            return ApiResponse.error(4005, "系统暂停服务");
        }

        Account account = accountStore.getAccount(Long.parseLong(accountId));
        if (account == null) {
            return ApiResponse.error(2004, "账户不存在");
        }

        return ApiResponse.success(AccountInfoResponse.from(account));
    }

    /**
     * 获取持仓列表
     * GET /api/account/positions
     */
    @GetMapping("/positions")
    public ApiResponse<List<PositionResponse>> getPositions(
            @RequestParam String accountId) {

        if (!lifecycleManager.isAcceptingRequests()) {
            return ApiResponse.error(4005, "系统暂停服务");
        }

        List<Position> positions = positionStore.getPositionsByAccount(Long.parseLong(accountId));

        List<PositionResponse> response = positions.stream()
            .map(PositionResponse::from)
            .collect(Collectors.toList());

        return ApiResponse.success(response);
    }

    /**
     * 持仓响应 DTO
     */
    public static class PositionResponse {
        private String accountId;
        private String symbol;
        private String quantity;
        private String available;
        private String frozen;
        private String t1Locked;
        private String avgPrice;
        private String unrealizedPnl;

        public static PositionResponse from(Position position) {
            PositionResponse response = new PositionResponse();
            response.accountId = String.valueOf(position.getAccountId());
            response.symbol = position.getSymbol();
            response.quantity = String.valueOf(position.getQuantity());
            response.available = String.valueOf(position.getAvailable());
            response.frozen = String.valueOf(position.getFrozen());
            response.t1Locked = String.valueOf(position.getT1Locked());
            response.avgPrice = String.valueOf(position.getAvgPrice());
            response.unrealizedPnl = String.valueOf(position.getUnrealizedPnl());
            return response;
        }

        // Getters
        public String getAccountId() { return accountId; }
        public String getSymbol() { return symbol; }
        public String getQuantity() { return quantity; }
        public String getAvailable() { return available; }
        public String getFrozen() { return frozen; }
        public String getT1Locked() { return t1Locked; }
        public String getAvgPrice() { return avgPrice; }
        public String getUnrealizedPnl() { return unrealizedPnl; }

        // Setters
        public void setAccountId(String accountId) { this.accountId = accountId; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public void setQuantity(String quantity) { this.quantity = quantity; }
        public void setAvailable(String available) { this.available = available; }
        public void setFrozen(String frozen) { this.frozen = frozen; }
        public void setT1Locked(String t1Locked) { this.t1Locked = t1Locked; }
        public void setAvgPrice(String avgPrice) { this.avgPrice = avgPrice; }
        public void setUnrealizedPnl(String unrealizedPnl) { this.unrealizedPnl = unrealizedPnl; }
    }
}
