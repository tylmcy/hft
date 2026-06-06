package com.hft.trading.snapshot;

import com.hft.trading.model.Account;
import com.hft.trading.model.AccountStatus;
import com.hft.trading.model.Position;
import com.hft.trading.service.AlertService;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryPositionStore;
import com.hft.trading.util.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AccountSnapshotReader 测试类
 *
 * 根据 TDD 原则，先编写测试用例，再验证实现
 */
class AccountSnapshotReaderTest {

    @TempDir
    Path tempDir;

    private InMemoryAccountStore accountStore;
    private InMemoryPositionStore positionStore;
    private AccountSnapshotWriter writer;
    private AccountSnapshotReader reader;

    @BeforeEach
    void setUp() {
        AlertService alertService = TestUtils.createTestAlertService();
        accountStore = new InMemoryAccountStore(alertService);
        positionStore = new InMemoryPositionStore(alertService);
        writer = new AccountSnapshotWriter(accountStore, positionStore, tempDir.toString() + "/");
        reader = new AccountSnapshotReader(accountStore, positionStore);
    }

    /**
     * 测试读取快照恢复账户和持仓数据
     * - 写入快照
     * - 清空存储
     * - 读取快照
     * - 验证数据恢复
     */
    @Test
    void testReadSnapshot_RestoresAccountsAndPositions() throws Exception {
        // Given: 准备测试数据
        Account account1 = new Account(1001L, 2001L);
        account1.deposit(100000L); // 入金 1000 元
        account1.freeze(10000L);   // 冻结 100 元
        accountStore.addAccount(account1);

        Account account2 = new Account(1002L, 2002L);
        account2.deposit(200000L); // 入金 2000 元
        accountStore.addAccount(account2);

        Position position1 = new Position(1001L, "600000", 600000);
        position1.settleBuy(100L, 1000L); // 买入 100 股
        positionStore.addPosition(position1);

        Position position2 = new Position(1001L, "600001", 600001);
        position2.settleBuy(200L, 2000L); // 买入 200 股
        positionStore.addPosition(position2);

        // 写入快照
        long cqIndex = 12345L;
        long accountInputIndex = 100L;
        long accountSettlementIndex = 50L;
        long matchingInputLastIndex = 75L;
        writer.writeSnapshot(cqIndex, accountInputIndex, accountSettlementIndex, matchingInputLastIndex);

        String snapshotFile = tempDir.resolve("account_snapshot_" + cqIndex + ".dat").toString();

        // 清空存储
        AlertService alertService2 = TestUtils.createTestAlertService();
        accountStore = new InMemoryAccountStore(alertService2);
        positionStore = new InMemoryPositionStore(alertService2);
        reader = new AccountSnapshotReader(accountStore, positionStore);

        // When: 读取快照
        AccountSnapshotReader.SnapshotData data = reader.readSnapshot(snapshotFile);

        // Then: 验证账户数据恢复
        assertEquals(2, accountStore.size(), "账户数量应为 2");

        Account restoredAccount1 = accountStore.getAccount(1001L);
        assertNotNull(restoredAccount1, "账户1应存在");
        assertEquals(1001L, restoredAccount1.getAccountId());
        assertEquals(2001L, restoredAccount1.getUserId());
        assertEquals(100000L, restoredAccount1.getBalance());
        assertEquals(90000L, restoredAccount1.getAvailable()); // 100000 - 10000
        assertEquals(10000L, restoredAccount1.getFrozen());
        assertEquals(0L, restoredAccount1.getMarginFrozen());
        assertEquals(10000L, restoredAccount1.getOrderFrozen());
        assertEquals(AccountStatus.ACTIVE, restoredAccount1.getStatus());
        assertTrue(restoredAccount1.validateInvariant(), "账户不变式应成立");

        Account restoredAccount2 = accountStore.getAccount(1002L);
        assertNotNull(restoredAccount2, "账户2应存在");
        assertEquals(1002L, restoredAccount2.getAccountId());
        assertEquals(2002L, restoredAccount2.getUserId());
        assertEquals(200000L, restoredAccount2.getBalance());
        assertEquals(AccountStatus.ACTIVE, restoredAccount2.getStatus());
        assertTrue(restoredAccount2.validateInvariant(), "账户不变式应成立");

        // 验证持仓数据恢复
        assertEquals(2, positionStore.size(), "持仓数量应为 2");

        Position restoredPosition1 = positionStore.getPosition(1001L, "600000");
        assertNotNull(restoredPosition1, "持仓1应存在");
        assertEquals(1001L, restoredPosition1.getAccountId());
        assertEquals("600000", restoredPosition1.getSymbol());
        assertEquals(600000, restoredPosition1.getSymbolCode());
        assertEquals(100L, restoredPosition1.getQuantity());
        assertEquals(0L, restoredPosition1.getAvailable()); // T+1 锁定
        assertEquals(0L, restoredPosition1.getFrozen());
        assertEquals(100L, restoredPosition1.getT1Locked());
        assertEquals(1000L, restoredPosition1.getAvgPrice());

        Position restoredPosition2 = positionStore.getPosition(1001L, "600001");
        assertNotNull(restoredPosition2, "持仓2应存在");
        assertEquals(200L, restoredPosition2.getQuantity());
        assertEquals(2000L, restoredPosition2.getAvgPrice());

        // 验证索引数据
        assertEquals(accountInputIndex, data.getAccountInputIndex());
        assertEquals(accountSettlementIndex, data.getAccountSettlementIndex());
        assertEquals(matchingInputLastIndex, data.getMatchingInputLastIndex());
    }

    /**
     * 测试读取无效文件抛出异常
     * - 验证文件不存在时抛出异常
     * - 验证文件格式错误时抛出异常
     */
    @Test
    void testReadSnapshot_InvalidFile() throws Exception {
        // Test 1: 文件不存在
        assertThrows(Exception.class, () -> {
            reader.readSnapshot(tempDir.resolve("nonexistent.dat").toString());
        }, "文件不存在应抛出异常");

        // Test 2: 无效魔数
        String invalidFile = tempDir.resolve("invalid.dat").toString();
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(invalidFile))) {
            dos.writeInt(0x12345678); // 错误的魔数
            dos.writeInt(1);
            dos.writeLong(System.currentTimeMillis());
        }

        assertThrows(Exception.class, () -> {
            reader.readSnapshot(invalidFile);
        }, "无效魔数应抛出异常");

        // Test 3: 无效版本
        String invalidVersionFile = tempDir.resolve("invalid_version.dat").toString();
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(invalidVersionFile))) {
            dos.writeInt(0x41434354); // 正确魔数
            dos.writeInt(999);        // 无效版本
            dos.writeLong(System.currentTimeMillis());
        }

        assertThrows(Exception.class, () -> {
            reader.readSnapshot(invalidVersionFile);
        }, "无效版本应抛出异常");
    }

    /**
     * 测试读取空快照
     * - 验证空存储快照能正常读取
     */
    @Test
    void testReadSnapshot_EmptySnapshot() throws Exception {
        // Given: 写入空快照
        long cqIndex = 99999L;
        writer.writeSnapshot(cqIndex, 0L, 0L, 0L);
        String snapshotFile = tempDir.resolve("account_snapshot_" + cqIndex + ".dat").toString();

        // 清空存储
        AlertService alertService2 = TestUtils.createTestAlertService();
        accountStore = new InMemoryAccountStore(alertService2);
        positionStore = new InMemoryPositionStore(alertService2);
        reader = new AccountSnapshotReader(accountStore, positionStore);

        // When: 读取快照
        AccountSnapshotReader.SnapshotData data = reader.readSnapshot(snapshotFile);

        // Then: 验证空状态
        assertEquals(0, accountStore.size(), "账户数量应为 0");
        assertEquals(0, positionStore.size(), "持仓数量应为 0");
        assertEquals(0L, data.getAccountInputIndex());
        assertEquals(0L, data.getAccountSettlementIndex());
        assertEquals(0L, data.getMatchingInputLastIndex());
    }
}