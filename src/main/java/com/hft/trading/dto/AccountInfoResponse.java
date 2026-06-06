package com.hft.trading.dto;

/**
 * 账户信息响应 DTO
 */
public class AccountInfoResponse {
    private String accountId;
    private String userId;
    private String balance;
    private String available;
    private String frozen;
    private String marginFrozen;
    private String orderFrozen;
    private String unrealizedPnl;
    private String status;

    public static AccountInfoResponse from(com.hft.trading.model.Account account) {
        AccountInfoResponse response = new AccountInfoResponse();
        response.accountId = String.valueOf(account.getAccountId());
        response.userId = String.valueOf(account.getUserId());
        response.balance = String.valueOf(account.getBalance());
        response.available = String.valueOf(account.getAvailable());
        response.frozen = String.valueOf(account.getFrozen());
        response.marginFrozen = String.valueOf(account.getMarginFrozen());
        response.orderFrozen = String.valueOf(account.getOrderFrozen());
        response.unrealizedPnl = String.valueOf(account.getUnrealizedPnl());
        response.status = account.getStatus().name();
        return response;
    }

    // Getters
    public String getAccountId() { return accountId; }
    public String getUserId() { return userId; }
    public String getBalance() { return balance; }
    public String getAvailable() { return available; }
    public String getFrozen() { return frozen; }
    public String getMarginFrozen() { return marginFrozen; }
    public String getOrderFrozen() { return orderFrozen; }
    public String getUnrealizedPnl() { return unrealizedPnl; }
    public String getStatus() { return status; }

    // Setters
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setBalance(String balance) { this.balance = balance; }
    public void setAvailable(String available) { this.available = available; }
    public void setFrozen(String frozen) { this.frozen = frozen; }
    public void setMarginFrozen(String marginFrozen) { this.marginFrozen = marginFrozen; }
    public void setOrderFrozen(String orderFrozen) { this.orderFrozen = orderFrozen; }
    public void setUnrealizedPnl(String unrealizedPnl) { this.unrealizedPnl = unrealizedPnl; }
    public void setStatus(String status) { this.status = status; }
}
