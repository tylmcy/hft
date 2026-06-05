package com.hft.trading.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnapshotFileUtils 单元测试
 *
 * 测试 MD5 校验和计算、验证，以及临时文件检测恢复功能
 */
class SnapshotFileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("计算文件的MD5校验和")
    void testCalculateMD5() throws IOException {
        // 创建测试文件
        Path testFile = tempDir.resolve("test.dat");
        Files.writeString(testFile, "Hello, World!");

        String md5 = SnapshotFileUtils.calculateMD5(testFile.toString());

        // "Hello, World!" 的MD5值
        assertEquals("65a8e27d8879283831b664bd8b7f0ad4", md5);
    }

    @Test
    @DisplayName("验证校验和 - 有效校验和")
    void testVerifyChecksum_ValidChecksum() throws IOException {
        // 创建测试文件
        Path dataFile = tempDir.resolve("snapshot_1000.dat");
        Files.writeString(dataFile, "snapshot data");

        // 计算并写入校验和文件
        String md5 = SnapshotFileUtils.calculateMD5(dataFile.toString());
        Path checksumFile = tempDir.resolve("snapshot_1000.dat.md5");
        Files.writeString(checksumFile, md5);

        assertTrue(SnapshotFileUtils.verifyChecksum(dataFile.toString(), checksumFile.toString()));
    }

    @Test
    @DisplayName("验证校验和 - 无效校验和")
    void testVerifyChecksum_InvalidChecksum() throws IOException {
        // 创建测试文件
        Path dataFile = tempDir.resolve("snapshot_1000.dat");
        Files.writeString(dataFile, "snapshot data");

        // 写入错误的校验和
        Path checksumFile = tempDir.resolve("snapshot_1000.dat.md5");
        Files.writeString(checksumFile, "invalid_checksum");

        assertFalse(SnapshotFileUtils.verifyChecksum(dataFile.toString(), checksumFile.toString()));
    }

    @Test
    @DisplayName("检测临时文件 - 有效校验和的tmp文件应重命名为dat")
    void testDetectAndRecoverTmpFiles_TmpWithValidChecksum() throws IOException {
        // 创建临时文件和校验和文件
        Path tmpFile = tempDir.resolve("account_snapshot_1000.dat.tmp");
        Files.writeString(tmpFile, "snapshot data");
        String md5 = SnapshotFileUtils.calculateMD5(tmpFile.toString());
        Path checksumFile = tempDir.resolve("account_snapshot_1000.dat.tmp.md5");
        Files.writeString(checksumFile, md5);

        SnapshotFileUtils.detectAndRecoverTmpFiles(tempDir.toString(), "account");

        // tmp文件应该被重命名为dat
        Path datFile = tempDir.resolve("account_snapshot_1000.dat");
        assertTrue(Files.exists(datFile));
        assertFalse(Files.exists(tmpFile));
    }

    @Test
    @DisplayName("检测临时文件 - 无效校验和的tmp文件应被删除")
    void testDetectAndRecoverTmpFiles_TmpWithInvalidChecksum() throws IOException {
        // 创建临时文件和校验和文件
        Path tmpFile = tempDir.resolve("account_snapshot_1000.dat.tmp");
        Files.writeString(tmpFile, "snapshot data");
        Path checksumFile = tempDir.resolve("account_snapshot_1000.dat.tmp.md5");
        Files.writeString(checksumFile, "invalid_checksum");

        SnapshotFileUtils.detectAndRecoverTmpFiles(tempDir.toString(), "account");

        // tmp文件应该被删除
        assertFalse(Files.exists(tmpFile));
        Path datFile = tempDir.resolve("account_snapshot_1000.dat");
        assertFalse(Files.exists(datFile));
    }

    @Test
    @DisplayName("检测临时文件 - 当tmp和dat都存在时，删除tmp文件")
    void testDetectAndRecoverTmpFiles_TmpAndDatBothExist() throws IOException {
        // 创建tmp文件和dat文件
        Path tmpFile = tempDir.resolve("account_snapshot_1000.dat.tmp");
        Files.writeString(tmpFile, "tmp snapshot data");
        Path datFile = tempDir.resolve("account_snapshot_1000.dat");
        Files.writeString(datFile, "dat snapshot data");

        SnapshotFileUtils.detectAndRecoverTmpFiles(tempDir.toString(), "account");

        // tmp文件应该被删除，dat文件保留
        assertFalse(Files.exists(tmpFile));
        assertTrue(Files.exists(datFile));
        assertEquals("dat snapshot data", Files.readString(datFile));
    }

    @Test
    @DisplayName("查找最新有效快照")
    void testFindLatestValidSnapshot() throws IOException {
        // 创建多个快照文件（带有效校验和）
        for (int i = 1000; i <= 3000; i += 1000) {
            Path dataFile = tempDir.resolve("account_snapshot_" + i + ".dat");
            Files.writeString(dataFile, "snapshot " + i);
            String md5 = SnapshotFileUtils.calculateMD5(dataFile.toString());
            Path checksumFile = tempDir.resolve("account_snapshot_" + i + ".dat.md5");
            Files.writeString(checksumFile, md5);
        }

        Optional<SnapshotFileUtils.SnapshotInfo> result =
            SnapshotFileUtils.findLatestValidSnapshot(tempDir.toString(), "account");

        assertTrue(result.isPresent());
        assertEquals(3000L, result.get().getIndex());
        assertTrue(result.get().getFilePath().endsWith("account_snapshot_3000.dat"));
    }

    @Test
    @DisplayName("查找最新有效快照 - 无有效快照")
    void testFindLatestValidSnapshot_NoValidSnapshot() throws IOException {
        // 创建没有校验和文件的快照
        Path dataFile = tempDir.resolve("account_snapshot_1000.dat");
        Files.writeString(dataFile, "snapshot data");

        Optional<SnapshotFileUtils.SnapshotInfo> result =
            SnapshotFileUtils.findLatestValidSnapshot(tempDir.toString(), "account");

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("查找最新有效快照 - 忽略无效校验和的文件")
    void testFindLatestValidSnapshot_IgnoreInvalidChecksum() throws IOException {
        // 创建带有效校验和的文件
        Path validFile = tempDir.resolve("account_snapshot_1000.dat");
        Files.writeString(validFile, "valid snapshot");
        String validMd5 = SnapshotFileUtils.calculateMD5(validFile.toString());
        Path validChecksum = tempDir.resolve("account_snapshot_1000.dat.md5");
        Files.writeString(validChecksum, validMd5);

        // 创建带无效校验和的文件（更大的index）
        Path invalidFile = tempDir.resolve("account_snapshot_2000.dat");
        Files.writeString(invalidFile, "invalid snapshot");
        Path invalidChecksum = tempDir.resolve("account_snapshot_2000.dat.md5");
        Files.writeString(invalidChecksum, "invalid_checksum");

        Optional<SnapshotFileUtils.SnapshotInfo> result =
            SnapshotFileUtils.findLatestValidSnapshot(tempDir.toString(), "account");

        assertTrue(result.isPresent());
        assertEquals(1000L, result.get().getIndex());
    }

    @Test
    @DisplayName("验证校验和 - 文件不存在时返回false")
    void testVerifyChecksum_FileNotExist() {
        assertFalse(SnapshotFileUtils.verifyChecksum(
            tempDir.resolve("not_exist.dat").toString(),
            tempDir.resolve("not_exist.md5").toString()
        ));
    }

    @Test
    @DisplayName("检测临时文件 - 无校验和文件的tmp应被删除")
    void testDetectAndRecoverTmpFiles_TmpWithoutChecksum() throws IOException {
        // 创建临时文件（无校验和文件）
        Path tmpFile = tempDir.resolve("account_snapshot_1000.dat.tmp");
        Files.writeString(tmpFile, "snapshot data");

        SnapshotFileUtils.detectAndRecoverTmpFiles(tempDir.toString(), "account");

        // 无校验和文件的tmp应该被删除
        assertFalse(Files.exists(tmpFile));
    }
}
