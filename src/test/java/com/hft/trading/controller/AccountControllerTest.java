package com.hft.trading.controller;

import com.hft.trading.lifecycle.LifecycleManager;
import com.hft.trading.model.Account;
import com.hft.trading.model.Position;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryPositionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * AccountController Unit Tests
 *
 * Tests REST API endpoints for account management using Mockito.
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock
    private InMemoryAccountStore accountStore;

    @Mock
    private InMemoryPositionStore positionStore;

    @Mock
    private LifecycleManager lifecycleManager;

    private AccountController accountController;

    @BeforeEach
    void setUp() {
        accountController = new AccountController(
            accountStore,
            positionStore,
            lifecycleManager
        );
    }

    // ==================== Get Account Info Tests ====================

    @Test
    @DisplayName("testGetAccountInfo - verify account data returned")
    void testGetAccountInfo() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);

        // Create account
        Account account = new Account(1001L, 2001L);
        account.deposit(1_000_000_00L); // 100万
        when(accountStore.getAccount(1001L)).thenReturn(account);

        // Act
        var response = accountController.getAccountInfo("1001");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals("1001", response.getData().getAccountId());
        assertEquals("2001", response.getData().getUserId());
        assertEquals("100000000", response.getData().getBalance()); // 100万（分）
        assertEquals("100000000", response.getData().getAvailable());
        assertEquals("0", response.getData().getFrozen());
        assertEquals("ACTIVE", response.getData().getStatus());
    }

    @Test
    @DisplayName("testGetAccountInfo_FrozenAccount - verify frozen balance shown")
    void testGetAccountInfo_FrozenAccount() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);

        // Create account with frozen balance
        Account account = new Account(1001L, 2001L);
        account.deposit(1_000_000_00L);
        account.freeze(50_000_00L); // Freeze 5000 yuan
        when(accountStore.getAccount(1001L)).thenReturn(account);

        // Act
        var response = accountController.getAccountInfo("1001");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals("100000000", response.getData().getBalance());
        assertEquals("95000000", response.getData().getAvailable());
        assertEquals("5000000", response.getData().getFrozen());
    }

    @Test
    @DisplayName("testGetAccountInfo_AccountNotFound - verify error for non-existent account")
    void testGetAccountInfo_AccountNotFound() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        when(accountStore.getAccount(anyLong())).thenReturn(null);

        // Act
        var response = accountController.getAccountInfo("9999");

        // Assert
        assertNotNull(response);
        assertEquals("2004", response.getCode());
        assertEquals("账户不存在", response.getMessage());
    }

    @Test
    @DisplayName("testGetAccountInfo_SystemPaused - verify error when system paused")
    void testGetAccountInfo_SystemPaused() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(false);

        // Act
        var response = accountController.getAccountInfo("1001");

        // Assert
        assertNotNull(response);
        assertEquals("4005", response.getCode());
        assertEquals("系统暂停服务", response.getMessage());
    }

    // ==================== Get Positions Tests ====================

    @Test
    @DisplayName("testGetPositions - verify positions list returned")
    void testGetPositions() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);

        // Create positions
        Position position1 = new Position(1001L, "600000", 600000);
        position1.settleBuy(100L, 1050_00L);
        position1.unlockT1(); // Make available

        Position position2 = new Position(1001L, "600001", 600001);
        position2.settleBuy(200L, 2000_00L);
        position2.unlockT1(); // Make available

        when(positionStore.getPositionsByAccount(1001L))
            .thenReturn(List.of(position1, position2));

        // Act
        var response = accountController.getPositions("1001");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(2, response.getData().size());

        // Check first position
        var pos1 = response.getData().get(0);
        assertEquals("1001", pos1.getAccountId());
        assertEquals("600000", pos1.getSymbol());
        assertEquals("100", pos1.getQuantity());
        assertEquals("100", pos1.getAvailable());
        assertEquals("0", pos1.getFrozen());

        // Check second position
        var pos2 = response.getData().get(1);
        assertEquals("600001", pos2.getSymbol());
        assertEquals("200", pos2.getQuantity());
    }

    @Test
    @DisplayName("testGetPositions_EmptyList - verify empty list for no positions")
    void testGetPositions_EmptyList() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);
        when(positionStore.getPositionsByAccount(anyLong()))
            .thenReturn(Collections.emptyList());

        // Act
        var response = accountController.getPositions("1001");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertTrue(response.getData().isEmpty());
    }

    @Test
    @DisplayName("testGetPositions_FrozenPosition - verify frozen quantity shown")
    void testGetPositions_FrozenPosition() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);

        // Create position with frozen quantity
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 1050_00L);
        position.unlockT1(); // Make available (available = 100)
        position.freezePosition(50L); // Freeze 50 (available = 50, frozen = 50)

        when(positionStore.getPositionsByAccount(1001L))
            .thenReturn(List.of(position));

        // Act
        var response = accountController.getPositions("1001");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());

        var pos = response.getData().get(0);
        assertEquals("100", pos.getQuantity());
        assertEquals("50", pos.getAvailable());
        assertEquals("50", pos.getFrozen());
    }

    @Test
    @DisplayName("testGetPositions_T1Locked - verify T+1 locked quantity shown")
    void testGetPositions_T1Locked() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(true);

        // Create position with T+1 locked (settleBuy automatically locks T+1)
        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 1050_00L); // This sets t1Locked = 100
        position.unlockT1(); // Unlock all T+1
        position.settleBuy(30L, 1050_00L); // This sets t1Locked = 30, available = 70

        when(positionStore.getPositionsByAccount(1001L))
            .thenReturn(List.of(position));

        // Act
        var response = accountController.getPositions("1001");

        // Assert
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());

        var pos = response.getData().get(0);
        assertEquals("130", pos.getQuantity()); // 100 + 30
        assertEquals("100", pos.getAvailable()); // 100 unlocked + 0 from second buy
        assertEquals("30", pos.getT1Locked()); // 30 from second buy
    }

    @Test
    @DisplayName("testGetPositions_SystemPaused - verify error when system paused")
    void testGetPositions_SystemPaused() {
        // Arrange
        when(lifecycleManager.isAcceptingRequests()).thenReturn(false);

        // Act
        var response = accountController.getPositions("1001");

        // Assert
        assertNotNull(response);
        assertEquals("4005", response.getCode());
        assertEquals("系统暂停服务", response.getMessage());
    }
}