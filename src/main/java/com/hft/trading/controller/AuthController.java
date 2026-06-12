package com.hft.trading.controller;

import com.hft.trading.dto.ApiResponse;
import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.model.Account;
import com.hft.trading.security.JwtService;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.util.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证 REST API 控制器
 *
 * 根据 V12 技术方案 8.1 节实现
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final JwtService jwtService;
    private final InMemoryAccountStore accountStore;
    private final SnowflakeIdGenerator idGenerator;
    private final LifecycleManager lifecycleManager;

    // 简化的用户存储（实际应使用数据库）
    private final Map<String, UserInfo> users = new HashMap<>();

    public AuthController(JwtService jwtService,
                          InMemoryAccountStore accountStore,
                          SnowflakeIdGenerator idGenerator,
                          LifecycleManager lifecycleManager) {
        this.jwtService = jwtService;
        this.accountStore = accountStore;
        this.idGenerator = idGenerator;
        this.lifecycleManager = lifecycleManager;

        // 初始化测试用户
        initTestUsers();
    }

    private void initTestUsers() {
        // 创建测试用户
        long userId1 = idGenerator.nextId();
        long accountId1 = idGenerator.nextId();
        users.put("trader01", new UserInfo(userId1, accountId1, "trader01", "TRADER"));

        Account account1 = new Account(accountId1, userId1);
        account1.deposit(1_000_000_00L); // 100万
        accountStore.addAccount(account1);

        logger.info("Test user created: username=trader01, userId={}, accountId={}", userId1, accountId1);

        // 创建管理员用户
        long adminUserId = idGenerator.nextId();
        long adminAccountId = idGenerator.nextId();
        users.put("admin", new UserInfo(adminUserId, adminAccountId, "admin", "ADMIN"));

        Account adminAccount = new Account(adminAccountId, adminUserId);
        accountStore.addAccount(adminAccount);
    }

    /**
     * 用户登录
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        logger.info("Login request: username={}", request.getUsername());

        if (!lifecycleManager.isAcceptingRequests()) {
            return ApiResponse.error(4005, "系统暂停服务");
        }

        // 简化验证：检查用户是否存在
        UserInfo user = users.get(request.getUsername());
        if (user == null) {
            return ApiResponse.error(1004, "用户不存在");
        }

        // 简化密码验证（实际应使用加密验证）
        if (!"password123".equals(request.getPassword())) {
            return ApiResponse.error(1004, "密码错误");
        }

        // 生成 Token
        String accessToken = jwtService.generateAccessToken(
            user.userId, user.accountId, user.username, user.role);
        String refreshToken = jwtService.generateRefreshToken(user.userId);

        LoginResponse response = new LoginResponse();
        response.accessToken = accessToken;
        response.refreshToken = refreshToken;
        response.accessExpireTime = String.valueOf(System.currentTimeMillis() + jwtService.getAccessTokenExpiration());
        response.refreshExpireTime = String.valueOf(System.currentTimeMillis() + jwtService.getRefreshTokenExpiration());
        response.userId = String.valueOf(user.userId);
        response.accountId = String.valueOf(user.accountId);
        response.username = user.username;
        response.role = user.role;

        logger.info("Login success: username={}, userId={}", request.getUsername(), user.userId);
        return ApiResponse.success(response);
    }

    /**
     * 用户注册
     * POST /api/auth/register
     */
    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@RequestBody RegisterRequest request) {
        logger.info("Register request: username={}", request.getUsername());

        if (!lifecycleManager.isAcceptingRequests()) {
            return ApiResponse.error(4005, "系统暂停服务");
        }

        // 检查用户名是否已存在
        if (users.containsKey(request.getUsername())) {
            return ApiResponse.error(1001, "用户名已存在");
        }

        // 创建用户
        long userId = idGenerator.nextId();
        long accountId = idGenerator.nextId();

        users.put(request.getUsername(), new UserInfo(userId, accountId, request.getUsername(), "TRADER"));

        // 创建账户（初始余额为0）
        Account account = new Account(accountId, userId);
        accountStore.addAccount(account);

        RegisterResponse response = new RegisterResponse();
        response.userId = String.valueOf(userId);
        response.accountId = String.valueOf(accountId);

        logger.info("Register success: username={}, userId={}, accountId={}",
            request.getUsername(), userId, accountId);
        return ApiResponse.success(response);
    }

    /**
     * 刷新 Token
     * POST /api/auth/refresh
     */
    @PostMapping("/refresh")
    public ApiResponse<RefreshResponse> refresh(@RequestBody RefreshRequest request) {
        logger.info("Refresh token request");

        if (!lifecycleManager.isAcceptingRequests()) {
            return ApiResponse.error(4005, "系统暂停服务");
        }

        String refreshToken = request.getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            return ApiResponse.error(1004, "Token无效");
        }

        // 验证 Refresh Token
        if (!jwtService.validateToken(refreshToken)) {
            return ApiResponse.error(1006, "Token已失效");
        }

        // 提取用户信息
        Long userId = jwtService.extractUserId(refreshToken);

        // 查找用户
        UserInfo user = findUserById(userId);
        if (user == null) {
            return ApiResponse.error(2004, "用户不存在");
        }

        // 生成新的 Access Token
        String newAccessToken = jwtService.generateAccessToken(
            user.userId, user.accountId, user.username, user.role);
        String newRefreshToken = jwtService.generateRefreshToken(userId);

        RefreshResponse response = new RefreshResponse();
        response.accessToken = newAccessToken;
        response.refreshToken = newRefreshToken;
        response.accessExpireTime = String.valueOf(System.currentTimeMillis() + jwtService.getAccessTokenExpiration());
        response.refreshExpireTime = String.valueOf(System.currentTimeMillis() + jwtService.getRefreshTokenExpiration());

        logger.info("Token refresh success: userId={}", userId);
        return ApiResponse.success(response);
    }

    /**
     * 用户登出
     * POST /api/auth/logout
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authorization) {
        logger.info("Logout request");

        // 简化：登出不做特殊处理（Token 自然过期）
        return ApiResponse.success(null);
    }

    /**
     * 修改密码
     * POST /api/auth/change-password
     */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(
            @RequestHeader("Authorization") String authorization,
            @RequestBody ChangePasswordRequest request) {
        logger.info("Change password request");

        if (!lifecycleManager.isAcceptingRequests()) {
            return ApiResponse.error(4005, "系统暂停服务");
        }

        // 从 Token 提取用户信息
        String token = authorization.replace("Bearer ", "");
        if (!jwtService.validateToken(token)) {
            return ApiResponse.error(1006, "Token已失效");
        }

        Long userId = jwtService.extractUserId(token);
        UserInfo user = findUserById(userId);
        if (user == null) {
            return ApiResponse.error(2004, "用户不存在");
        }

        // 验证旧密码（简化验证）
        if (!"password123".equals(request.getOldPassword())) {
            return ApiResponse.error(1004, "旧密码错误");
        }

        // 验证新密码
        if (request.getNewPassword() == null || request.getNewPassword().length() < 6) {
            return ApiResponse.error(1001, "新密码长度至少6位");
        }

        if (request.getNewPassword().equals(request.getOldPassword())) {
            return ApiResponse.error(1001, "新密码不能与旧密码相同");
        }

        // 简化：实际应更新数据库中的密码
        // 这里仅返回成功，实际生产环境需要持久化新密码
        logger.info("Password changed successfully for user: {}", user.username);
        return ApiResponse.success(null);
    }

    private UserInfo findUserById(Long userId) {
        for (UserInfo user : users.values()) {
            if (user.userId.equals(userId)) {
                return user;
            }
        }
        return null;
    }

    // DTO 类

    public static class LoginRequest {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class LoginResponse {
        private String accessToken;
        private String refreshToken;
        private String accessExpireTime;
        private String refreshExpireTime;
        private String userId;
        private String accountId;
        private String username;
        private String role;

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        public String getAccessExpireTime() { return accessExpireTime; }
        public void setAccessExpireTime(String accessExpireTime) { this.accessExpireTime = accessExpireTime; }
        public String getRefreshExpireTime() { return refreshExpireTime; }
        public void setRefreshExpireTime(String refreshExpireTime) { this.refreshExpireTime = refreshExpireTime; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    public static class RegisterRequest {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class RegisterResponse {
        private String userId;
        private String accountId;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
    }

    public static class RefreshRequest {
        private String refreshToken;

        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    }

    public static class RefreshResponse {
        private String accessToken;
        private String refreshToken;
        private String accessExpireTime;
        private String refreshExpireTime;

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        public String getAccessExpireTime() { return accessExpireTime; }
        public void setAccessExpireTime(String accessExpireTime) { this.accessExpireTime = accessExpireTime; }
        public String getRefreshExpireTime() { return refreshExpireTime; }
        public void setRefreshExpireTime(String refreshExpireTime) { this.refreshExpireTime = refreshExpireTime; }
    }

    public static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;

        public String getOldPassword() { return oldPassword; }
        public void setOldPassword(String oldPassword) { this.oldPassword = oldPassword; }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    }

    private static class UserInfo {
        final Long userId;
        final Long accountId;
        final String username;
        final String role;

        UserInfo(Long userId, Long accountId, String username, String role) {
            this.userId = userId;
            this.accountId = accountId;
            this.username = username;
            this.role = role;
        }
    }
}