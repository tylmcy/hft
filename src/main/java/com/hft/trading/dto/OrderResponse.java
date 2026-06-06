package com.hft.trading.dto;

/**
 * 下单响应 DTO
 */
public class OrderResponse {
    private String orderId;
    private String status;

    public static OrderResponse pending(Long orderId) {
        OrderResponse response = new OrderResponse();
        response.orderId = String.valueOf(orderId);
        response.status = "PENDING";
        return response;
    }

    public static OrderResponse withStatus(Long orderId, String status) {
        OrderResponse response = new OrderResponse();
        response.orderId = String.valueOf(orderId);
        response.status = status;
        return response;
    }

    // Getters
    public String getOrderId() { return orderId; }
    public String getStatus() { return status; }

    // Setters
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public void setStatus(String status) { this.status = status; }
}
