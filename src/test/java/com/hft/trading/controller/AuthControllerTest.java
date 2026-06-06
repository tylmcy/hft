package com.hft.trading.controller;

import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.security.JwtService;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AuthController Unit Tests
 *
 * Tests REST API endpoints for authentication using Mockito.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private InMemoryAccountStore accountStore;

    @Mock
    private SnowflakeIdGenerator idGenerator;

    @Mock
    private LifecycleManager lifecycleManager;

    private AuthController authController;

    @BeforeEach
    void setUp() {
        // Create a fresh controller for each test
        // Mock idGenerator to return predictable IDs for test user creation
        when(idGenerator.nextId())
            .thenReturn(2001L)  // userId for trader01
            .thenReturn(3001L)  // accountId for trader01
            .thenReturn(2002L)  // userId for admin
            .thenReturn(3002L); // accountId for admin

        authController = new AuthController(
            jwtService,
            accountStore,
            idGenerator,
            lifecycleManager
        );
    }

    // ==================== Login Tests ====================

    @Test
    @DisplayName("testLogin_Success - verify tokens returned")
    void testLogin_Success() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        when(jwtService.generateAccessToken(anyLong(), anyLong(), anyString(), anyString()))
            .thenReturn("access-token-123");
        when(jwtService.generateRefreshToken(anyLong()))
            .thenReturn("refresh-token-456");
        when(jwtService.getAccessTokenExpiration()).thenReturn(7200000L);
        when(jwtService.getRefreshTokenExpiration()).thenReturn(604800000L);

        // Create login request
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("trader01");
        request.setPassword("password123");

        // Act
        var response = authController.login(request);

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals("access-token-123", response.getData().getAccessToken());
        assertEquals("refresh-token-456", response.getData().getRefreshToken());
        assertEquals("trader01", response.getData().getUsername());
        assertEquals("TRADER", response.getData().getRole());

        // Verify JWT service was called
        verify(jwtService).generateAccessToken(anyLong(), anyLong(), eq("trader01"), eq("TRADER"));
        verify(jwtService).generateRefreshToken(anyLong());
    }

    @Test
    @DisplayName("testLogin_InvalidCredentials_UserNotFound - verify 401 error")
    void testLogin_InvalidCredentials_UserNotFound() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);

        // Create login request for non-existent user
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("nonexistent");
        request.setPassword("password123");

        // Act
        var response = authController.login(request);

        // Assert
        assertNotNull(response);
        assertEquals("1004", response.getCode());
        assertEquals("用户不存在", response.getMessage());
    }

    @Test
    @DisplayName("testLogin_InvalidCredentials_WrongPassword - verify 401 error")
    void testLogin_InvalidCredentials_WrongPassword() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);

        // Create login request with wrong password
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("trader01");
        request.setPassword("wrongpassword");

        // Act
        var response = authController.login(request);

        // Assert
        assertNotNull(response);
        assertEquals("1004", response.getCode());
        assertEquals("密码错误", response.getMessage());
    }

    @Test
    @DisplayName("testLogin_SystemPaused - verify error when system paused")
    void testLogin_SystemPaused() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(false);

        // Create login request
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("trader01");
        request.setPassword("password123");

        // Act
        var response = authController.login(request);

        // Assert
        assertNotNull(response);
        assertEquals("4005", response.getCode());
        assertEquals("系统暂停服务", response.getMessage());
    }

    // ==================== Register Tests ====================

    @Test
    @DisplayName("testRegister_Success - verify user created")
    void testRegister_Success() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        // ID generator already set up in @BeforeEach, need additional IDs for register
        when(idGenerator.nextId()).thenReturn(5001L, 5002L);

        // Create register request
        AuthController.RegisterRequest request = new AuthController.RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("password123");

        // Act
        var response = authController.register(request);

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals("5001", response.getData().getUserId());
        assertEquals("5002", response.getData().getAccountId());

        // Verify account was created
        verify(accountStore, atLeast(1)).addAccount(any());
    }

    @Test
    @DisplayName("testRegister_UsernameExists - verify error for duplicate username")
    void testRegister_UsernameExists() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);

        // First registration - username already exists from setUp (trader01)
        AuthController.RegisterRequest request = new AuthController.RegisterRequest();
        request.setUsername("trader01"); // Already exists from initTestUsers
        request.setPassword("password456");

        // Act
        var response = authController.register(request);

        // Assert
        assertNotNull(response);
        assertEquals("1001", response.getCode());
        assertEquals("用户名已存在", response.getMessage());
    }

    @Test
    @DisplayName("testRegister_SystemPaused - verify error when system paused")
    void testRegister_SystemPaused() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(false);

        // Create register request
        AuthController.RegisterRequest request = new AuthController.RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("password123");

        // Act
        var response = authController.register(request);

        // Assert
        assertNotNull(response);
        assertEquals("4005", response.getCode());
        assertEquals("系统暂停服务", response.getMessage());
    }

    // ==================== Refresh Token Tests ====================

    @Test
    @DisplayName("testRefreshToken_Success - verify new tokens")
    void testRefreshToken_Success() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        when(jwtService.validateToken("valid-refresh-token")).thenReturn(true);
        when(jwtService.extractUserId("valid-refresh-token")).thenReturn(2001L); // userId of trader01 from setUp
        when(jwtService.generateAccessToken(anyLong(), anyLong(), anyString(), anyString()))
            .thenReturn("new-access-token");
        when(jwtService.generateRefreshToken(anyLong()))
            .thenReturn("new-refresh-token");
        when(jwtService.getAccessTokenExpiration()).thenReturn(7200000L);
        when(jwtService.getRefreshTokenExpiration()).thenReturn(604800000L);

        // Create refresh request
        AuthController.RefreshRequest request = new AuthController.RefreshRequest();
        request.setRefreshToken("valid-refresh-token");

        // Act
        var response = authController.refresh(request);

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals("new-access-token", response.getData().getAccessToken());
        assertEquals("new-refresh-token", response.getData().getRefreshToken());
    }

    @Test
    @DisplayName("testRefreshToken_InvalidToken - verify error for invalid token")
    void testRefreshToken_InvalidToken() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        when(jwtService.validateToken("invalid-token")).thenReturn(false);

        // Create refresh request
        AuthController.RefreshRequest request = new AuthController.RefreshRequest();
        request.setRefreshToken("invalid-token");

        // Act
        var response = authController.refresh(request);

        // Assert
        assertNotNull(response);
        assertEquals("1006", response.getCode());
        assertEquals("Token已失效", response.getMessage());
    }

    @Test
    @DisplayName("testRefreshToken_EmptyToken - verify error for empty token")
    void testRefreshToken_EmptyToken() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);

        // Create refresh request with empty token
        AuthController.RefreshRequest request = new AuthController.RefreshRequest();
        request.setRefreshToken("");

        // Act
        var response = authController.refresh(request);

        // Assert
        assertNotNull(response);
        assertEquals("1004", response.getCode());
        assertEquals("Token无效", response.getMessage());
    }

    // ==================== Logout Tests ====================

    @Test
    @DisplayName("testLogout_Success - verify logout accepted")
    void testLogout_Success() {
        // Act
        var response = authController.logout("Bearer access-token");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
    }
}
