package com.hft.trading.snapshot;

import com.hft.trading.model.Account;
import com.hft.trading.model.Position;
import com.hft.trading.service.AlertService;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryPositionStore;
import com.hft.trading.util.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AccountSnapshotWriter 测试类
 *
 * 根据 TDD 原则，先编写测试用例，再验证实现
 */
class AccountSnapshotWriterTest {

    @TempDir
    Path tempDir;

    private InMemoryAccountStore accountStore;
    private InMemoryPositionStore positionStore;
    private AccountSnapshotWriter writer;

    @BeforeEach
    void setUp() {
        AlertService alertService = TestUtils.createTestAlertService();
        accountStore = new InMemoryAccountStore(alertService);
        positionStore = new InMemoryPositionStore(alertService);
        writer = new AccountSnapshotWriter(accountStore, positionStore, tempDir.toString() + "/");
    }

    /**
     * 测试快照写入创建有效文件
     * - 验证 .dat 文件被创建
     * - 验证 .checksum 文件被创建
     * - 验证校验和正确
     */
    @Test
    void testWriteSnapshot_CreatesValidFiles() throws Exception {
        // Given: 准备测试数据
        Account account = new Account(1001L, 2001L);
        account.deposit(100000L); // 入金 1000 元
        accountStore.addAccount(account);

        Position position = new Position(1001L, "600000", 600000);
        position.settleBuy(100L, 1000L); // 买入 100 股
        positionStore.addPosition(position);

        // When: 写入快照
        long cqIndex = 12345L;
        writer.writeSnapshot(cqIndex, 100L, 50L, 75L);

        // Then: 验证文件创建
        String baseName = "account_snapshot_" + cqIndex;
        Path datFile = tempDir.resolve(baseName + ".dat");
        Path checksumFile = tempDir.resolve(baseName + ".dat.checksum");

        assertTrue(Files.exists(datFile), "快照数据文件应存在");
        assertTrue(Files.exists(checksumFile), "校验和文件应存在");

        // 验证校验和正确
        String expectedMd5 = SnapshotFileUtils.calculateMD5(datFile.toString());
        String actualMd5 = Files.readString(checksumFile).trim();
        assertEquals(expectedMd5, actualMd5, "校验和应匹配");

        // 验证文件内容基本结构
        try (DataInputStream dis = new DataInputStream(new FileInputStream(datFile.toFile()))) {
            int magic = dis.readInt();
            int version = dis.readInt();
            long timestamp = dis.readLong();

            assertEquals(0x41434354, magic, "文件魔数应为 ACCT");
            assertEquals(1, version, "文件版本应为 1");
            assertTrue(timestamp > 0, "时间戳应有效");
        }
    }

    /**
     * 测试原子写入
     * - 验证写入完成后没有 .tmp 文件残留
     * - 验证只有最终的 .dat 和 .checksum 文件
     */
    @Test
    void testWriteSnapshot_AtomicWrite() throws Exception {
        // Given: 准备测试数据
        Account account = new Account(1002L, 2002L);
        account.deposit(50000L);
        accountStore.addAccount(account);

        // When: 写入快照
        long cqIndex = 23456L;
        writer.writeSnapshot(cqIndex, 200L, 100L, 150L);

        // Then: 验证没有临时文件残留
        String baseName = "account_snapshot_" + cqIndex;

        // 不应有 .tmp 文件
        Path tmpFile = tempDir.resolve(baseName + ".tmp");
        Path tmpChecksumFile = tempDir.resolve(baseName + ".tmp.checksum");

        assertFalse(Files.exists(tmpFile), "不应存在 .tmp 文件");
        assertFalse(Files.exists(tmpChecksumFile), "不应存在 .tmp.checksum 文件");

        // 应只有 .dat 和 .checksum 文件
        Path datFile = tempDir.resolve(baseName + ".dat");
        Path checksumFile = tempDir.resolve(baseName + ".dat.checksum");

        assertTrue(Files.exists(datFile), "应存在 .dat 文件");
        assertTrue(Files.exists(checksumFile), "应存在 .checksum 文件");

        // 验证目录中只有这两个文件
        List<Path> files = Files.list(tempDir).toList();
        assertEquals(2, files.size(), "目录中应只有 2 个文件（.dat 和 .checksum）");
    }

    /**
     * 测试空存储的快照写入
     * - 验证没有账户和持仓时快照能正常工作
     * - 验证文件内容正确表示空状态
     */
    @Test
    void testWriteSnapshot_EmptyStores() throws Exception {
        // Given: 空存储（不添加任何数据）

        // When: 写入快照
        long cqIndex = 34567L;
        writer.writeSnapshot(cqIndex, 0L, 0L, 0L);

        // Then: 验证文件创建
        String baseName = "account_snapshot_" + cqIndex;
        Path datFile = tempDir.resolve(baseName + ".dat");
        Path checksumFile = tempDir.resolve(baseName + ".dat.checksum");

        assertTrue(Files.exists(datFile), "即使空存储，快照文件也应创建");
        assertTrue(Files.exists(checksumFile), "校验和文件应创建");

        // 验证文件内容：账户数和持仓数都为 0
        try (DataInputStream dis = new DataInputStream(new FileInputStream(datFile.toFile()))) {
            assertEquals(0x41434354, dis.readInt(), "魔数应为 ACCT");
            assertEquals(1, dis.readInt(), "版本应为 1");
            assertTrue(dis.readLong() > 0, "时间戳应有效");

            int accountCount = dis.readInt();
            assertEquals(0, accountCount, "账户数应为 0");

            int positionCount = dis.readInt();
            assertEquals(0, positionCount, "持仓数应为 0");

            // 验证 CQ 索引
            long accountInputIndex = dis.readLong();
            long accountSettlementIndex = dis.readLong();
            long matchingInputLastIndex = dis.readLong();

            assertEquals(0L, accountInputIndex);
            assertEquals(0L, accountSettlementIndex);
            assertEquals(0L, matchingInputLastIndex);
        }
    }
}
