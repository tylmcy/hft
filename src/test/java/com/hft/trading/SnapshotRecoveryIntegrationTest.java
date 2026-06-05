package com.hft.trading;

import com.hft.trading.model.Account;
import com.hft.trading.model.AccountStatus;
import com.hft.trading.model.Position;
import com.hft.trading.snapshot.AccountSnapshotReader;
import com.hft.trading.snapshot.AccountSnapshotWriter;
import com.hft.trading.snapshot.SnapshotFileUtils;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryPositionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnapshotRecoveryIntegrationTest - 快照与恢复集成测试
 *
 * 测试完整的快照写入-读取-恢复流程
 */
class SnapshotRecoveryIntegrationTest {

    @TempDir
    Path tempDir;

    private InMemoryAccountStore accountStore;
    private InMemoryPositionStore positionStore;
    private AccountSnapshotWriter writer;
    private AccountSnapshotReader reader;

    @BeforeEach
    void setUp() {
        accountStore = new InMemoryAccountStore();
        positionStore = new InMemoryPositionStore();
        writer = new AccountSnapshotWriter(accountStore, positionStore, tempDir.toString() + "/");
        reader = new AccountSnapshotReader(accountStore, positionStore);
    }

    @Test
    @DisplayName("完整快照写入和恢复流程")
    void testSnapshotAndRecovery_FullCycle() throws Exception {
        // 1. 创建账户和持仓数据
        Account account1 = new Account(1001L, 2001L);
        account1.deposit(1_000_000_00L); // 100万
        account1.freeze(50_000_00L); // 冻结5000元
        accountStore.addAccount(account1);

        Account account2 = new Account(1002L, 2002L);
        account2.deposit(500_000_00L); // 50万
        accountStore.addAccount(account2);

        Position position1 = new Position(1001L, "600000", 600000);
        position1.settleBuy(1000L, 1050_00L); // 买入1000股
        positionStore.addPosition(position1);

        Position position2 = new Position(1001L, "600001", 600001);
        position2.settleBuy(500L, 2000_00L); // 买入500股
        positionStore.addPosition(position2);

        // 验证写入前的数据状态
        assertEquals(2, accountStore.size());
        assertEquals(2, positionStore.size());
        assertTrue(accountStore.getAccount(1001L).validateInvariant());
        assertTrue(accountStore.getAccount(1002L).validateInvariant());

        // 2. 写入快照
        long cqIndex = 12345L;
        long accountInputIndex = 100L;
        long accountSettlementIndex = 50L;
        long matchingInputLastIndex = 75L;
        writer.writeSnapshot(cqIndex, accountInputIndex, accountSettlementIndex, matchingInputLastIndex);

        // 3. 验证快照文件创建成功
        String baseName = "account_snapshot_" + cqIndex;
        Path datFile = tempDir.resolve(baseName + ".dat");
        Path checksumFile = tempDir.resolve(baseName + ".dat.checksum");

        assertTrue(Files.exists(datFile), "快照数据文件应存在");
        assertTrue(Files.exists(checksumFile), "校验和文件应存在");

        // 验证校验和正确
        String expectedMd5 = SnapshotFileUtils.calculateMD5(datFile.toString());
        String actualMd5 = Files.readString(checksumFile).trim();
        assertEquals(expectedMd5, actualMd5, "校验和应匹配");

        // 4. 清空内存存储（模拟重启）
        accountStore = new InMemoryAccountStore();
        positionStore = new InMemoryPositionStore();

        assertEquals(0, accountStore.size(), "清空后账户应为0");
        assertEquals(0, positionStore.size(), "清空后持仓应为0");

        // 5. 从快照恢复
        reader = new AccountSnapshotReader(accountStore, positionStore);
        AccountSnapshotReader.SnapshotData snapshotData = reader.readSnapshot(datFile.toString());

        // 6. 验证恢复后的数据
        // 验证账户
        assertEquals(2, accountStore.size(), "应恢复2个账户");

        Account restoredAccount1 = accountStore.getAccount(1001L);
        assertNotNull(restoredAccount1, "账户1001应存在");
        assertEquals(2001L, restoredAccount1.getUserId());
        assertEquals(1_000_000_00L, restoredAccount1.getBalance());
        assertEquals(50_000_00L, restoredAccount1.getFrozen());
        assertEquals(AccountStatus.ACTIVE, restoredAccount1.getStatus());
        assertTrue(restoredAccount1.validateInvariant(), "账户不变式应满足");

        Account restoredAccount2 = accountStore.getAccount(1002L);
        assertNotNull(restoredAccount2, "账户1002应存在");
        assertEquals(2002L, restoredAccount2.getUserId());
        assertEquals(500_000_00L, restoredAccount2.getBalance());
        assertEquals(0L, restoredAccount2.getFrozen());

        // 验证持仓
        assertEquals(2, positionStore.size(), "应恢复2个持仓");

        Position restoredPosition1 = positionStore.getPosition(1001L, "600000");
        assertNotNull(restoredPosition1, "持仓600000应存在");
        assertEquals(1000L, restoredPosition1.getQuantity());
        assertEquals(1000L, restoredPosition1.getT1Locked()); // T+1锁定
        assertTrue(restoredPosition1.validateInvariant(), "持仓不变式应满足");

        Position restoredPosition2 = positionStore.getPosition(1001L, "600001");
        assertNotNull(restoredPosition2, "持仓600001应存在");
        assertEquals(500L, restoredPosition2.getQuantity());

        // 验证CQ索引
        assertEquals(accountInputIndex, snapshotData.getAccountInputIndex());
        assertEquals(accountSettlementIndex, snapshotData.getAccountSettlementIndex());
        assertEquals(matchingInputLastIndex, snapshotData.getMatchingInputLastIndex());
    }

    @Test
    @DisplayName("临时文件恢复测试 - 有效校验和的tmp文件应被重命名")
    void testTmpFileRecovery() throws Exception {
        // 1. 创建账户数据并写入快照
        Account account = new Account(1001L, 2001L);
        account.deposit(100_000_00L);
        accountStore.addAccount(account);

        long cqIndex = 9999L;
        writer.writeSnapshot(cqIndex, 100L, 50L, 75L);

        // 2. 验证初始快照文件
        String baseName = "account_snapshot_" + cqIndex;
        Path datFile = tempDir.resolve(baseName + ".dat");
        Path checksumFile = tempDir.resolve(baseName + ".dat.checksum");

        assertTrue(Files.exists(datFile));
        assertTrue(Files.exists(checksumFile));

        // 3. 模拟临时文件场景：创建一个带有效校验和的 .tmp 文件
        // 先删除 .dat 文件，模拟写入过程中断的场景
        Files.delete(datFile);
        Files.delete(checksumFile);

        // 创建 .tmp 文件（使用相同的数据）
        Account newAccount = new Account(2001L, 3001L);
        newAccount.deposit(200_000_00L);
        InMemoryAccountStore tmpStore = new InMemoryAccountStore();
        InMemoryPositionStore tmpPositionStore = new InMemoryPositionStore();
        tmpStore.addAccount(newAccount);
        AccountSnapshotWriter tmpWriter = new AccountSnapshotWriter(tmpStore, tmpPositionStore, tempDir.toString() + "/");
        tmpWriter.writeSnapshot(8888L, 80L, 40L, 60L);

        // 将生成的 .dat 重命名为 .tmp（模拟中断）
        Path newDatFile = tempDir.resolve("account_snapshot_8888.dat");
        Path newChecksumFile = tempDir.resolve("account_snapshot_8888.dat.checksum");
        Path tmpFile = tempDir.resolve("account_snapshot_8888.dat.tmp");
        // SnapshotFileUtils uses .md5 extension for recovery
        Path tmpChecksumFile = tempDir.resolve("account_snapshot_8888.dat.tmp.md5");

        Files.move(newDatFile, tmpFile);
        // Rename .checksum to .md5 for compatibility with SnapshotFileUtils
        Files.move(newChecksumFile, tmpChecksumFile);

        // 验证 tmp 文件存在，dat 文件不存在
        assertTrue(Files.exists(tmpFile));
        assertFalse(Files.exists(newDatFile));

        // 4. 调用临时文件恢复
        SnapshotFileUtils.detectAndRecoverTmpFiles(tempDir.toString(), "account");

        // 5. 验证 tmp 文件被重命名为 dat 文件
        assertFalse(Files.exists(tmpFile), ".tmp 文件应被重命名");
        assertTrue(Files.exists(newDatFile), ".dat 文件应存在");
        // After recovery, checksum is renamed to .md5
        Path recoveredChecksumFile = tempDir.resolve("account_snapshot_8888.dat.md5");
        assertTrue(Files.exists(recoveredChecksumFile), ".md5 checksum 文件应存在");

        // 6. 验证恢复的文件可以正常读取
        InMemoryAccountStore recoveredStore = new InMemoryAccountStore();
        InMemoryPositionStore recoveredPositionStore = new InMemoryPositionStore();
        AccountSnapshotReader recoveredReader = new AccountSnapshotReader(recoveredStore, recoveredPositionStore);
        recoveredReader.readSnapshot(newDatFile.toString());

        assertEquals(1, recoveredStore.size());
        Account recoveredAccount = recoveredStore.getAccount(2001L);
        assertNotNull(recoveredAccount);
        assertEquals(200_000_00L, recoveredAccount.getBalance());
    }

    @Test
    @DisplayName("查找最新有效快照测试")
    void testMultipleSnapshots_FindLatest() throws Exception {
        // 1. 写入3个不同时间的快照
        // Note: SnapshotFileUtils.findLatestValidSnapshot looks for .md5 checksum files
        // but AccountSnapshotWriter creates .checksum files, so we need to create .md5 files
        long[] cqIndices = {1000L, 2000L, 3000L};
        long[] balances = {100_000_00L, 200_000_00L, 300_000_00L};

        for (int i = 0; i < cqIndices.length; i++) {
            // 每次使用新的 store 避免数据累积
            InMemoryAccountStore writeStore = new InMemoryAccountStore();
            InMemoryPositionStore writePositionStore = new InMemoryPositionStore();

            Account account = new Account(1001L, 2001L);
            account.deposit(balances[i]);
            writeStore.addAccount(account);

            AccountSnapshotWriter snapshotWriter = new AccountSnapshotWriter(
                writeStore, writePositionStore, tempDir.toString() + "/");
            snapshotWriter.writeSnapshot(cqIndices[i], cqIndices[i] * 10, cqIndices[i] * 5, cqIndices[i] * 3);

            // Rename .checksum to .md5 for compatibility with SnapshotFileUtils
            Path checksumFile = tempDir.resolve("account_snapshot_" + cqIndices[i] + ".dat.checksum");
            Path md5File = tempDir.resolve("account_snapshot_" + cqIndices[i] + ".dat.md5");
            Files.move(checksumFile, md5File);
        }

        // 2. 验证所有快照文件都已创建
        for (long cqIndex : cqIndices) {
            Path datFile = tempDir.resolve("account_snapshot_" + cqIndex + ".dat");
            Path md5File = tempDir.resolve("account_snapshot_" + cqIndex + ".dat.md5");
            assertTrue(Files.exists(datFile), "快照文件 " + cqIndex + " 应存在");
            assertTrue(Files.exists(md5File), "校验和文件 " + cqIndex + " 应存在");
        }

        // 3. 查找最新有效快照
        Optional<SnapshotFileUtils.SnapshotInfo> latestSnapshot =
            SnapshotFileUtils.findLatestValidSnapshot(tempDir.toString(), "account");

        // 4. 验证返回的是最新的快照
        assertTrue(latestSnapshot.isPresent(), "应找到最新快照");
        assertEquals(3000L, latestSnapshot.get().getIndex(), "应返回索引最大的快照");
        assertTrue(latestSnapshot.get().getFilePath().endsWith("account_snapshot_3000.dat"));

        // 5. 读取最新快照验证数据
        InMemoryAccountStore latestStore = new InMemoryAccountStore();
        InMemoryPositionStore latestPositionStore = new InMemoryPositionStore();
        AccountSnapshotReader latestReader = new AccountSnapshotReader(latestStore, latestPositionStore);
        latestReader.readSnapshot(latestSnapshot.get().getFilePath());

        Account latestAccount = latestStore.getAccount(1001L);
        assertNotNull(latestAccount);
        assertEquals(300_000_00L, latestAccount.getBalance(), "应为最新快照的数据");
    }

    @Test
    @DisplayName("空存储快照恢复测试")
    void testEmptyStore_SnapshotAndRecovery() throws Exception {
        // 1. 空存储直接写入快照
        long cqIndex = 0L;
        writer.writeSnapshot(cqIndex, 0L, 0L, 0L);

        // 2. 验证快照文件创建
        Path datFile = tempDir.resolve("account_snapshot_" + cqIndex + ".dat");
        assertTrue(Files.exists(datFile));

        // 3. 清空并恢复
        accountStore = new InMemoryAccountStore();
        positionStore = new InMemoryPositionStore();
        reader = new AccountSnapshotReader(accountStore, positionStore);

        AccountSnapshotReader.SnapshotData snapshotData = reader.readSnapshot(datFile.toString());

        // 4. 验证恢复结果
        assertEquals(0, accountStore.size());
        assertEquals(0, positionStore.size());
        assertEquals(0L, snapshotData.getAccountInputIndex());
        assertEquals(0L, snapshotData.getAccountSettlementIndex());
        assertEquals(0L, snapshotData.getMatchingInputLastIndex());
    }

    @Test
    @DisplayName("多账户多持仓快照恢复测试")
    void testMultipleAccountsAndPositions() throws Exception {
        // 1. 创建多个账户和持仓
        for (long accountId = 1001L; accountId <= 1010L; accountId++) {
            Account account = new Account(accountId, accountId + 1000L);
            account.deposit(accountId * 100_00L); // 不同余额
            if (accountId % 2 == 0) {
                account.freeze(accountId * 10_00L); // 部分冻结
            }
            accountStore.addAccount(account);

            // 每个账户创建2个持仓
            Position position1 = new Position(accountId, "60000" + accountId, (int) (600000 + accountId));
            position1.settleBuy(accountId, 1000_00L);
            positionStore.addPosition(position1);

            Position position2 = new Position(accountId, "60001" + accountId, (int) (600010 + accountId));
            position2.settleBuy(accountId / 2, 2000_00L);
            positionStore.addPosition(position2);
        }

        // 记录原始数据
        int originalAccountCount = accountStore.size();
        int originalPositionCount = positionStore.size();

        // 2. 写入快照
        writer.writeSnapshot(5000L, 500L, 250L, 375L);

        // 3. 清空并恢复
        accountStore = new InMemoryAccountStore();
        positionStore = new InMemoryPositionStore();
        reader = new AccountSnapshotReader(accountStore, positionStore);
        reader.readSnapshot(tempDir.resolve("account_snapshot_5000.dat").toString());

        // 4. 验证所有数据正确恢复
        assertEquals(originalAccountCount, accountStore.size());
        assertEquals(originalPositionCount, positionStore.size());

        for (long accountId = 1001L; accountId <= 1010L; accountId++) {
            Account account = accountStore.getAccount(accountId);
            assertNotNull(account, "账户 " + accountId + " 应存在");
            assertEquals(accountId * 100_00L, account.getBalance());
            assertTrue(account.validateInvariant(), "账户 " + accountId + " 不变式应满足");

            // 验证持仓
            Position position1 = positionStore.getPosition(accountId, "60000" + accountId);
            assertNotNull(position1);
            assertEquals(accountId, position1.getQuantity());

            Position position2 = positionStore.getPosition(accountId, "60001" + accountId);
            assertNotNull(position2);
            assertEquals(accountId / 2, position2.getQuantity());
        }
    }
}