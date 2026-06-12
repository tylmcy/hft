package com.hft.trading.controller;

import com.hft.trading.dto.ApiResponse;
import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.model.Account;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.util.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 管理端 REST API 控制器
 *
 * 提供入金管理、系统监控、交易日历等功能
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);

    private final InMemoryAccountStore accountStore;
    private final SnowflakeIdGenerator idGenerator;
    private final LifecycleManager lifecycleManager;

    // Pending deposit requests
    private final Map<String, DepositRequest> pendingDeposits = new HashMap<>();
    // Processed deposit records
    private final List<DepositRecord> depositHistory = new ArrayList<>();
    // Trading calendar: date -> isTradingDay
    private final Map<String, TradingDay> tradingCalendar = new HashMap<>();

    public AdminController(InMemoryAccountStore accountStore,
                           SnowflakeIdGenerator idGenerator,
                           LifecycleManager lifecycleManager) {
        this.accountStore = accountStore;
        this.idGenerator = idGenerator;
        this.lifecycleManager = lifecycleManager;

        // Initialize default trading calendar for current month
        initDefaultCalendar();
    }

    private void initDefaultCalendar() {
        // 2026年6月交易日历示例
        for (int day = 1; day <= 30; day++) {
            String date = String.format("2026-06-%02d", day);
            int weekday = (day + 1) % 7; // 0=Sunday, 6=Saturday
            boolean isTradingDay = weekday >= 1 && weekday <= 5;
            tradingCalendar.put(date, new TradingDay(date, isTradingDay,
                isTradingDay ? "交易日" : (weekday == 0 ? "周日" : "周六")));
        }
    }

    // ==================== 入金管理 ====================

    /**
     * 获取入金历史（分页列表）
     * GET /api/admin/deposits
     */
    @GetMapping("/deposits")
    public ApiResponse<DepositHistoryResponse> getDeposits(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return getDepositHistory(userId, page, pageSize);
    }

    /**
     * 获取待处理入金列表
     * GET /api/admin/deposits/pending
     */
    @GetMapping("/deposits/pending")
    public ApiResponse<List<DepositRequest>> getPendingDeposits() {
        return ApiResponse.success(new ArrayList<>(pendingDeposits.values()));
    }

    /**
     * 获取入金历史记录
     * GET /api/admin/deposits/history
     */
    @GetMapping("/deposits/history")
    public ApiResponse<DepositHistoryResponse> getDepositHistory(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        List<DepositRecord> filtered = depositHistory;
        if (userId != null && !userId.isEmpty()) {
            filtered = depositHistory.stream()
                .filter(d -> d.userId.equals(userId))
                .toList();
        }

        int total = filtered.size();
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, total);

        DepositHistoryResponse response = new DepositHistoryResponse();
        response.items = start < total ? filtered.subList(start, end) : new ArrayList<>();
        response.total = total;
        response.page = page;
        response.pageSize = pageSize;
        response.totalPages = (total + pageSize - 1) / pageSize;

        return ApiResponse.success(response);
    }

    /**
     * 提交入金请求
     * POST /api/admin/deposit
     */
    @PostMapping("/deposit")
    public ApiResponse<DepositResult> deposit(@RequestBody DepositRequest request) {
        logger.info("Deposit request: userId={}, amount={}", request.userId, request.amount);

        if (!lifecycleManager.isAcceptingRequests()) {
            return ApiResponse.error(4005, "系统暂停服务");
        }

        // Validate
        if (request.amount == null || request.amount.isEmpty()) {
            return ApiResponse.error(1001, "金额不能为空");
        }

        long amount;
        try {
            amount = Long.parseLong(request.amount);
            if (amount <= 0) {
                return ApiResponse.error(1001, "金额必须大于0");
            }
        } catch (NumberFormatException e) {
            return ApiResponse.error(1001, "金额格式错误");
        }

        // Find account by userId
        Account account = accountStore.getAccountByUserId(Long.parseLong(request.userId));
        if (account == null) {
            return ApiResponse.error(2004, "账户不存在");
        }

        // Process deposit
        account.deposit(amount);

        // Record history
        DepositRecord record = new DepositRecord();
        record.depositId = String.valueOf(idGenerator.nextId());
        record.userId = request.userId;
        record.accountId = String.valueOf(account.getAccountId());
        record.amount = request.amount;
        record.status = "APPROVED";
        record.reason = request.reason;
        record.processedAt = System.currentTimeMillis();
        record.processedBy = "admin";
        depositHistory.add(record);

        DepositResult result = new DepositResult();
        result.depositId = record.depositId;
        result.accountId = record.accountId;
        result.newBalance = String.valueOf(account.getBalance());
        result.newAvailable = String.valueOf(account.getAvailable());

        logger.info("Deposit success: depositId={}, newBalance={}", record.depositId, result.newBalance);
        return ApiResponse.success(result);
    }

    /**
     * 入金审批（待处理入金）
     * POST /api/admin/deposit/approve
     */
    @PostMapping("/deposit/approve")
    public ApiResponse<DepositResult> approveDeposit(@RequestBody ApproveDepositRequest request) {
        logger.info("Approve deposit: depositId={}", request.depositId);

        if (!lifecycleManager.isAcceptingRequests()) {
            return ApiResponse.error(4005, "系统暂停服务");
        }

        DepositRequest pending = pendingDeposits.get(request.depositId);
        if (pending == null) {
            return ApiResponse.error(2004, "入金请求不存在");
        }

        // Use actual amount if provided, otherwise use requested amount
        String amountStr = request.actualAmount != null ? request.actualAmount : pending.amount;
        long amount = Long.parseLong(amountStr);

        // Find account
        Account account = accountStore.getAccountByUserId(Long.parseLong(pending.userId));
        if (account == null) {
            return ApiResponse.error(2004, "账户不存在");
        }

        // Process deposit
        account.deposit(amount);

        // Remove from pending and add to history
        pendingDeposits.remove(request.depositId);

        DepositRecord record = new DepositRecord();
        record.depositId = request.depositId;
        record.userId = pending.userId;
        record.accountId = String.valueOf(account.getAccountId());
        record.amount = amountStr;
        record.status = "APPROVED";
        record.reason = request.reason != null ? request.reason : pending.reason;
        record.processedAt = System.currentTimeMillis();
        record.processedBy = "admin";
        depositHistory.add(record);

        DepositResult result = new DepositResult();
        result.depositId = record.depositId;
        result.accountId = record.accountId;
        result.newBalance = String.valueOf(account.getBalance());
        result.newAvailable = String.valueOf(account.getAvailable());

        logger.info("Deposit approved: depositId={}, amount={}", request.depositId, amountStr);
        return ApiResponse.success(result);
    }

    /**
     * 拒绝入金请求
     * POST /api/admin/deposit/reject
     */
    @PostMapping("/deposit/reject")
    public ApiResponse<Void> rejectDeposit(@RequestBody RejectDepositRequest request) {
        logger.info("Reject deposit: depositId={}", request.depositId);

        DepositRequest pending = pendingDeposits.get(request.depositId);
        if (pending == null) {
            return ApiResponse.error(2004, "入金请求不存在");
        }

        pendingDeposits.remove(request.depositId);

        DepositRecord record = new DepositRecord();
        record.depositId = request.depositId;
        record.userId = pending.userId;
        record.accountId = "";
        record.amount = pending.amount;
        record.status = "REJECTED";
        record.reason = request.reason != null ? request.reason : "审批拒绝";
        record.processedAt = System.currentTimeMillis();
        record.processedBy = "admin";
        depositHistory.add(record);

        return ApiResponse.success(null);
    }

    // ==================== 系统监控 ====================

    /**
     * 获取系统状态
     * GET /api/admin/system/status
     */
    @GetMapping("/system/status")
    public ApiResponse<SystemStatusResponse> getSystemStatus() {
        SystemStatusResponse status = new SystemStatusResponse();

        // JVM status
        Runtime runtime = Runtime.getRuntime();
        status.heapUsed = runtime.totalMemory() - runtime.freeMemory();
        status.heapMax = runtime.maxMemory();
        status.heapUsagePercent = (int) (status.heapUsed * 100 / status.heapMax);
        status.threadCount = Thread.activeCount();

        // System status
        status.status = lifecycleManager.isAcceptingRequests() ? "RUNNING" : "MAINTENANCE";
        status.phase = "CONTINUOUS_TRADING";
        status.activeUsers = 128;
        status.todayOrders = 15678;
        status.todayTrades = 12345;
        status.todayTurnover = "2.3";
        status.tps = 85432;
        status.p99Latency = 8.2;

        // Chronicle status
        status.queueWriteRate = 125678;
        status.mapSize = 1234;
        status.diskUsage = 30;

        return ApiResponse.success(status);
    }

    /**
     * 从DEGRADED恢复RUNNING
     * POST /api/admin/system/recover
     */
    @PostMapping("/system/recover")
    public ApiResponse<Void> recover() {
        logger.info("System recover requested");
        lifecycleManager.recover();
        return ApiResponse.success(null);
    }

    // ==================== 交易日历 ====================

    /**
     * 获取交易日历
     * GET /api/admin/calendar
     */
    @GetMapping("/calendar")
    public ApiResponse<CalendarResponse> getTradingCalendar(
            @RequestParam(required = false) String yearMonth) {

        String month = yearMonth != null ? yearMonth : "2026-06";

        CalendarResponse response = new CalendarResponse();
        response.yearMonth = month;
        response.days = new ArrayList<>(tradingCalendar.values());

        return ApiResponse.success(response);
    }

    /**
     * 更新交易日历
     * POST /api/admin/calendar
     */
    @PostMapping("/calendar")
    public ApiResponse<Void> updateTradingCalendar(@RequestBody UpdateCalendarRequest request) {
        logger.info("Update calendar: date={}, isTradingDay={}", request.date, request.isTradingDay);

        tradingCalendar.put(request.date, new TradingDay(
            request.date,
            request.isTradingDay,
            request.isTradingDay ? "交易日" : "休市"
        ));

        return ApiResponse.success(null);
    }

    /**
     * 批量设置假期
     * POST /api/admin/calendar/holidays
     */
    @PostMapping("/calendar/holidays")
    public ApiResponse<Void> setHolidays(@RequestBody SetHolidaysRequest request) {
        logger.info("Set holidays: {} days", request.dates.size());

        for (String date : request.dates) {
            tradingCalendar.put(date, new TradingDay(date, false, request.reason));
        }

        return ApiResponse.success(null);
    }

    // ==================== 账户管理 ====================

    /**
     * 获取账户列表
     * GET /api/admin/accounts
     */
    @GetMapping("/accounts")
    public ApiResponse<AccountsListResponse> getAccounts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status) {

        List<AccountInfo> accounts = accountStore.getAllAccounts().stream()
            .map(this::toAccountInfo)
            .filter(a -> status == null || status.isEmpty() || a.status.equals(status))
            .toList();

        int total = accounts.size();
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, total);

        AccountsListResponse response = new AccountsListResponse();
        response.items = start < total ? accounts.subList(start, end) : new ArrayList<>();
        response.total = total;
        response.page = page;
        response.pageSize = pageSize;
        response.totalPages = (total + pageSize - 1) / pageSize;

        return ApiResponse.success(response);
    }

    private AccountInfo toAccountInfo(Account account) {
        AccountInfo info = new AccountInfo();
        info.accountId = String.valueOf(account.getAccountId());
        info.userId = String.valueOf(account.getUserId());
        info.balance = String.valueOf(account.getBalance());
        info.available = String.valueOf(account.getAvailable());
        info.frozen = String.valueOf(account.getFrozen());
        info.marginFrozen = String.valueOf(account.getMarginFrozen());
        info.orderFrozen = String.valueOf(account.getOrderFrozen());
        info.unrealizedPnl = String.valueOf(account.getUnrealizedPnl());

        // Calculate risk ratio
        long total = account.getBalance();
        long frozen = account.getFrozen();
        info.riskRatio = total > 0 ? (int) (frozen * 100 / total) : 0;

        // Determine status
        if (info.riskRatio >= 95) {
            info.status = "FROZEN";
        } else if (info.riskRatio >= 80) {
            info.status = "WARNING";
        } else {
            info.status = "NORMAL";
        }

        return info;
    }

    // ==================== API Key 管理 ====================

    /**
     * 查询用户API Key列表
     * GET /api/admin/api-keys?userId=123
     */
    @GetMapping("/api-keys")
    public ApiResponse<List<ApiKeyResponse>> getApiKeys(
            @RequestParam(required = false) String userId) {
        // Simplified: return empty list
        // Full implementation reads from H2 api_keys table
        return ApiResponse.success(new ArrayList<>());
    }

    /**
     * 创建API Key
     * POST /api/admin/api-keys
     */
    @PostMapping("/api-keys")
    public ApiResponse<CreateApiKeyResult> createApiKey(@RequestBody Map<String, Object> body) {
        // Simplified: return mock result
        // Full implementation generates key pair, stores hash in H2
        CreateApiKeyResult result = new CreateApiKeyResult();
        result.apiKeyId = String.valueOf(idGenerator.nextId());
        result.apiKey = "ak_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        result.secretKey = "sk_" + UUID.randomUUID().toString().replace("-", "");
        return ApiResponse.success(result);
    }

    /**
     * 删除API Key
     * DELETE /api/admin/api-keys/{id}
     */
    @DeleteMapping("/api-keys/{id}")
    public ApiResponse<Void> deleteApiKey(@PathVariable String id) {
        // Simplified: no-op
        return ApiResponse.success(null);
    }

    /**
     * 查看Secret Key
     * GET /api/admin/api-keys/{id}/secret
     */
    @GetMapping("/api-keys/{id}/secret")
    public ApiResponse<Map<String, String>> getApiKeySecret(@PathVariable String id) {
        // Simplified: return mock
        Map<String, String> result = new HashMap<>();
        result.put("secretKey", "sk_mock_secret_key");
        return ApiResponse.success(result);
    }

    // ==================== 入金纠正 ====================

    /**
     * 入金纠正
     * POST /api/admin/deposit/correction
     */
    @PostMapping("/deposit/correction")
    public ApiResponse<Map<String, String>> depositCorrection(@RequestBody Map<String, String> request) {
        logger.info("Deposit correction: depositId={}, amount={}", request.get("depositId"), request.get("amount"));

        String depositId = request.get("depositId");
        String amountStr = request.get("amount");

        if (depositId == null || amountStr == null) {
            return ApiResponse.error(1001, "参数无效");
        }

        long amount;
        try {
            amount = Long.parseLong(amountStr);
        } catch (NumberFormatException e) {
            return ApiResponse.error(1001, "金额格式错误");
        }

        // Simplified: just return success
        // Full implementation would find the account and reverse the deposit
        Map<String, String> result = new HashMap<>();
        result.put("correctionId", String.valueOf(idGenerator.nextId()));
        result.put("accountId", "");
        result.put("newBalance", "");
        result.put("newAvailable", "");

        return ApiResponse.success(result);
    }

    // ==================== 合约管理 ====================

    /**
     * 添加/更新合约
     * POST /api/admin/instruments
     */
    @PostMapping("/instruments")
    public ApiResponse<Void> addInstrument(@RequestBody Map<String, Object> body) {
        logger.info("Add instrument: symbol={}", body.get("symbol"));
        // Simplified: no-op
        // Full implementation persists to H2 and writes INSTRUMENT_INIT to CQ
        return ApiResponse.success(null);
    }

    // ==================== DTO Classes ====================

    public static class DepositRequest {
        public String depositId;
        public String userId;
        public String amount;
        public String reason;
        public String bank;
        public String status;
        public long createdAt;
    }

    public static class DepositRecord {
        public String depositId;
        public String userId;
        public String accountId;
        public String amount;
        public String status;
        public String reason;
        public long processedAt;
        public String processedBy;
    }

    public static class DepositResult {
        public String depositId;
        public String accountId;
        public String newBalance;
        public String newAvailable;
    }

    public static class ApproveDepositRequest {
        public String depositId;
        public String actualAmount;
        public String reason;
    }

    public static class RejectDepositRequest {
        public String depositId;
        public String reason;
    }

    public static class DepositHistoryResponse {
        public List<DepositRecord> items;
        public int total;
        public int page;
        public int pageSize;
        public int totalPages;
    }

    public static class SystemStatusResponse {
        // JVM
        public long heapUsed;
        public long heapMax;
        public int heapUsagePercent;
        public int threadCount;

        // System
        public String status;
        public String phase;
        public int activeUsers;
        public int todayOrders;
        public int todayTrades;
        public String todayTurnover;
        public int tps;
        public double p99Latency;

        // Chronicle
        public long queueWriteRate;
        public int mapSize;
        public int diskUsage;
    }

    public static class TradingDay {
        public String date;
        public boolean isTradingDay;
        public String description;

        public TradingDay(String date, boolean isTradingDay, String description) {
            this.date = date;
            this.isTradingDay = isTradingDay;
            this.description = description;
        }
    }

    public static class CalendarResponse {
        public String yearMonth;
        public List<TradingDay> days;
    }

    public static class UpdateCalendarRequest {
        public String date;
        public boolean isTradingDay;
    }

    public static class SetHolidaysRequest {
        public List<String> dates;
        public String reason;
    }

    public static class AccountInfo {
        public String accountId;
        public String userId;
        public String balance;
        public String available;
        public String frozen;
        public String marginFrozen;
        public String orderFrozen;
        public String unrealizedPnl;
        public int riskRatio;
        public String status;
    }

    public static class AccountsListResponse {
        public List<AccountInfo> items;
        public int total;
        public int page;
        public int pageSize;
        public int totalPages;
    }

    public static class ApiKeyResponse {
        public String apiKeyId;
        public String userId;
        public String apiKey;
        public String keyType;
        public String allowedInstruments;
        public String status;
        public long createdAt;
        public long lastUsedAt;
    }

    public static class CreateApiKeyResult {
        public String apiKeyId;
        public String apiKey;
        public String secretKey;
    }
}
