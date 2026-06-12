package com.hft.trading.controller;

import com.hft.trading.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 成交 REST API 控制器
 *
 * 根据 V12 技术方案 8.1 节实现
 */
@RestController
@RequestMapping("/api")
public class TradeController {

    /**
     * 获取历史成交
     * GET /api/trades
     */
    @GetMapping("/trades")
    public ApiResponse<?> getTrades(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        // Simplified: return empty paginated response
        // Full implementation reads from H2 trades_history table
        Map<String, Object> result = new HashMap<>();
        result.put("items", new ArrayList<>());
        result.put("total", 0);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("totalPages", 0);

        return ApiResponse.success(result);
    }
}
