package com.hft.trading.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一API响应格式
 *
 * 根据 V12 技术方案 8.1 节：
 * - 所有字段值为 STRING 类型（防止 JS int64 精度丢失）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private String code;
    private String message;
    private T data;
    private String timestamp;
    private String traceId;

    public ApiResponse() {
        this.timestamp = String.valueOf(System.currentTimeMillis());
        this.traceId = java.util.UUID.randomUUID().toString();
    }

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = "0";
        response.message = "success";
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = "0";
        response.message = message;
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = String.valueOf(code);
        response.message = message;
        return response;
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        return response;
    }

    // Getters and Setters
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
