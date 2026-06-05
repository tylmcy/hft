package com.hft.trading.snapshot;

import com.hft.trading.matching.MatchingEngine;
import com.hft.trading.matching.OrderBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MatchingSnapshotWriter 测试类
 *
 * 根据 TDD 原则，先编写测试用例，再验证实现
 */
class MatchingSnapshotWriterTest {

    @TempDir
    Path tempDir;

    private Map<String, MatchingEngine> matchingEngines;
    private MatchingSnapshotWriter writer;

    @BeforeEach
    void setUp() {
        matchingEngines = new HashMap<>();
        writer = new MatchingSnapshotWriter(matchingEngines, tempDir.toString() + "/");
    }

    /**
     * 测试空订单簿的快照写入
     * - 验证 .dat 文件被创建
     * - 验证 .checksum 文件被创建
     * - 验证校验和正确
     * - 验证文件内容正确表示空状态
     */
    @Test
    void testWriteSnapshot_EmptyOrderBooks() throws Exception {
        // Given: 空订单簿（不添加任何数据）

        // When: 写入快照
        long cqIndex = 12345L;
        long matchingInputIndex = 100L;
        long matchingOutputLastIndex = 50L;
        writer.writeSnapshot(cqIndex, matchingInputIndex, matchingOutputLastIndex);

        // Then: 验证文件创建
        String baseName = "matching_snapshot_" + cqIndex;
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

            assertEquals(0x4D414354, magic, "文件魔数应为 MACT");
            assertEquals(1, version, "文件版本应为 1");
            assertTrue(timestamp > 0, "时间戳应有效");

            // 验证订单簿数量
            int symbolCount = dis.readInt();
            assertEquals(0, symbolCount, "订单簿数量应为 0");

            // 验证 CQ 索引
            long inputIndex = dis.readLong();
            long outputLastIndex = dis.readLong();
            assertEquals(matchingInputIndex, inputIndex, "matchingInputIndex 应匹配");
            assertEquals(matchingOutputLastIndex, outputLastIndex, "matchingOutputLastIndex 应匹配");
        }
    }

    /**
     * 测试有订单簿的快照写入
     * - 验证订单簿数据正确写入
     * - 验证 askLevels 和 bidLevels 数量正确
     */
    @Test
    void testWriteSnapshot_WithOrderBooks() throws Exception {
        // Given: 准备测试数据 - 创建两个订单簿
        MatchingEngine engine1 = new MatchingEngine("600000");
        MatchingEngine engine2 = new MatchingEngine("600001");
        matchingEngines.put("600000", engine1);
        matchingEngines.put("600001", engine2);

        // When: 写入快照
        long cqIndex = 23456L;
        long matchingInputIndex = 200L;
        long matchingOutputLastIndex = 150L;
        writer.writeSnapshot(cqIndex, matchingInputIndex, matchingOutputLastIndex);

        // Then: 验证文件创建
        String baseName = "matching_snapshot_" + cqIndex;
        Path datFile = tempDir.resolve(baseName + ".dat");

        assertTrue(Files.exists(datFile), "快照数据文件应存在");

        // 验证文件内容
        try (DataInputStream dis = new DataInputStream(new FileInputStream(datFile.toFile()))) {
            assertEquals(0x4D414354, dis.readInt(), "魔数应为 MACT");
            assertEquals(1, dis.readInt(), "版本应为 1");
            assertTrue(dis.readLong() > 0, "时间戳应有效");

            // 验证订单簿数量
            int symbolCount = dis.readInt();
            assertEquals(2, symbolCount, "订单簿数量应为 2");

            // 读取第一个订单簿
            String symbol1 = dis.readUTF();
            int askLevels1 = dis.readInt();
            int bidLevels1 = dis.readInt();

            // 读取第二个订单簿
            String symbol2 = dis.readUTF();
            int askLevels2 = dis.readInt();
            int bidLevels2 = dis.readInt();

            // 验证两个 symbol
            assertTrue(
                (symbol1.equals("600000") && symbol2.equals("600001")) ||
                (symbol1.equals("600001") && symbol2.equals("600000")),
                "两个 symbol 都应存在"
            );

            // 空订单簿，买卖档位都为 0
            assertEquals(0, askLevels1, "空订单簿卖档数应为 0");
            assertEquals(0, bidLevels1, "空订单簿买档数应为 0");
            assertEquals(0, askLevels2, "空订单簿卖档数应为 0");
            assertEquals(0, bidLevels2, "空订单簿买档数应为 0");

            // 验证 CQ 索引
            long inputIndex = dis.readLong();
            long outputLastIndex = dis.readLong();
            assertEquals(matchingInputIndex, inputIndex, "matchingInputIndex 应匹配");
            assertEquals(matchingOutputLastIndex, outputLastIndex, "matchingOutputLastIndex 应匹配");
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
        MatchingEngine engine = new MatchingEngine("600000");
        matchingEngines.put("600000", engine);

        // When: 写入快照
        long cqIndex = 34567L;
        writer.writeSnapshot(cqIndex, 300L, 250L);

        // Then: 验证没有临时文件残留
        String baseName = "matching_snapshot_" + cqIndex;

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
}