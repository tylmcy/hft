package com.hft.trading.recovery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecoveryManager 测试
 *
 * 测试 4 阶段恢复的核心逻辑
 */
class RecoveryManagerTest {

    @TempDir
    Path tempDir;

    private Path snapshotDir;
    private Path chronicleDir;
    private RecoveryManager recoveryManager;

    @BeforeEach
    void setUp() throws IOException {
        snapshotDir = tempDir.resolve("snapshots");
        chronicleDir = tempDir.resolve("chronicle");
        Files.createDirectories(snapshotDir);
        Files.createDirectories(chronicleDir);

        // Create RecoveryManager with test directories
        // Note: For tests, we pass null for stores/queues/workers since we only test needsRecovery()
        recoveryManager = new RecoveryManager(
            snapshotDir.toString(),
            chronicleDir.toString(),
            null, // accountStore
            null, // positionStore
            null, // orderStore
            null, // instrumentStore
            null, // matchingEngines
            null, // accountInputQueue
            null, // matchingInputQueue
            null, // matchingOutputQueue
            null, // accountSettlementQueue
            null, // accountWorker
            null, // matchingEngineWorker
            null  // clearingWorker
        );
    }

    @AfterEach
    void tearDown() {
        // Cleanup handled by @TempDir
    }

    /**
     * 测试：无快照文件时不需要恢复
     */
    @Test
    void testNeedsRecovery_NoSnapshots() {
        // Given: 空目录

        // When
        boolean needsRecovery = recoveryManager.needsRecovery();

        // Then
        assertFalse(needsRecovery, "Should not need recovery when no files exist");
    }

    /**
     * 测试：存在快照文件时需要恢复
     */
    @Test
    void testNeedsRecovery_WithSnapshots() throws IOException {
        // Given: 创建快照文件
        Path accountSnapshot = snapshotDir.resolve("account_snapshot_100.dat");
        Files.createFile(accountSnapshot);
        Path checksum = snapshotDir.resolve("account_snapshot_100.dat.md5");
        Files.writeString(checksum, "dummy-md5");

        // When
        boolean needsRecovery = recoveryManager.needsRecovery();

        // Then
        assertTrue(needsRecovery, "Should need recovery when snapshot files exist");
    }

    /**
     * 测试：存在 CQ 文件时需要恢复
     */
    @Test
    void testNeedsRecovery_WithCqFiles() throws IOException {
        // Given: 创建 CQ 文件（Chronicle Queue 使用 .cq4 后缀）
        Path cqFile = chronicleDir.resolve("account_input.cq4");
        Files.createFile(cqFile);

        // When
        boolean needsRecovery = recoveryManager.needsRecovery();

        // Then
        assertTrue(needsRecovery, "Should need recovery when CQ files exist");
    }

    /**
     * 测试：仅存在 clearing_position.dat 时需要恢复
     */
    @Test
    void testNeedsRecovery_WithClearingPosition() throws IOException {
        // Given: 创建 clearing_position.dat
        Path positionFile = chronicleDir.resolve("clearing_position.dat");
        Files.createFile(positionFile);

        // When
        boolean needsRecovery = recoveryManager.needsRecovery();

        // Then
        assertTrue(needsRecovery, "Should need recovery when clearing_position.dat exists");
    }

    /**
     * 测试：临时文件不应触发恢复（需要先恢复临时文件）
     */
    @Test
    void testNeedsRecovery_OnlyTmpFiles() throws IOException {
        // Given: 仅存在 .tmp 文件
        Path tmpFile = snapshotDir.resolve("account_snapshot_100.dat.tmp");
        Files.createFile(tmpFile);

        // When
        boolean needsRecovery = recoveryManager.needsRecovery();

        // Then: .tmp 文件不算有效快照，但 performRecovery 会先恢复它们
        // 所以这里返回 false（没有有效快照），但 performRecovery 会处理
        assertFalse(needsRecovery, "Should not need recovery when only .tmp files exist");
    }

    /**
     * 测试：同时存在快照和 CQ 文件时需要恢复
     */
    @Test
    void testNeedsRecovery_WithBothSnapshotsAndCq() throws IOException {
        // Given: 同时创建快照和 CQ 文件
        Path accountSnapshot = snapshotDir.resolve("account_snapshot_100.dat");
        Files.createFile(accountSnapshot);
        Path checksum = snapshotDir.resolve("account_snapshot_100.dat.md5");
        Files.writeString(checksum, "dummy-md5");

        Path cqFile = chronicleDir.resolve("account_input.cq4");
        Files.createFile(cqFile);

        // When
        boolean needsRecovery = recoveryManager.needsRecovery();

        // Then
        assertTrue(needsRecovery, "Should need recovery when both snapshots and CQ files exist");
    }
}
