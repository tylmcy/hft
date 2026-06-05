package com.hft.trading.snapshot;

import com.hft.trading.matching.MatchingEngine;
import com.hft.trading.matching.OrderBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MatchingSnapshotReader 测试类
 *
 * 根据 TDD 原则，先编写测试用例，再验证实现
 */
class MatchingSnapshotReaderTest {

    @TempDir
    Path tempDir;

    private Map<String, MatchingEngine> matchingEngines;
    private MatchingSnapshotWriter writer;
    private MatchingSnapshotReader reader;

    @BeforeEach
    void setUp() {
        matchingEngines = new HashMap<>();
        writer = new MatchingSnapshotWriter(matchingEngines, tempDir.toString() + "/");
        reader = new MatchingSnapshotReader(matchingEngines);
    }

    /**
     * 测试读取快照并恢复订单簿
     * - 写入快照
     * - 清空 engines
     * - 读取快照
     * - 验证 indices 恢复正确
     */
    @Test
    void testReadSnapshot_RestoresOrderBooks() throws Exception {
        // Given: 准备测试数据 - 创建两个订单簿
        MatchingEngine engine1 = new MatchingEngine("600000");
        MatchingEngine engine2 = new MatchingEngine("600001");
        matchingEngines.put("600000", engine1);
        matchingEngines.put("600001", engine2);

        // 写入快照
        long cqIndex = 12345L;
        long matchingInputIndex = 100L;
        long matchingOutputLastIndex = 50L;
        writer.writeSnapshot(cqIndex, matchingInputIndex, matchingOutputLastIndex);

        // 等待文件写入完成
        String baseName = "matching_snapshot_" + cqIndex;
        Path datFile = tempDir.resolve(baseName + ".dat");
        Path checksumFile = tempDir.resolve(baseName + ".dat.checksum");

        assertTrue(Files.exists(datFile), "快照文件应存在");
        assertTrue(Files.exists(checksumFile), "校验和文件应存在");

        // 清空 engines
        matchingEngines.clear();
        assertTrue(matchingEngines.isEmpty(), "engines 应为空");

        // When: 读取快照
        MatchingSnapshotReader.SnapshotData result = reader.readSnapshot(datFile.toString());

        // Then: 验证结果
        assertNotNull(result, "读取结果不应为 null");
        assertEquals(matchingInputIndex, result.getMatchingInputIndex(), "matchingInputIndex 应恢复");
        assertEquals(matchingOutputLastIndex, result.getMatchingOutputLastIndex(), "matchingOutputLastIndex 应恢复");

        // 验证 engines 被恢复
        assertEquals(2, matchingEngines.size(), "应有 2 个 engines");
        assertTrue(matchingEngines.containsKey("600000"), "应包含 600000");
        assertTrue(matchingEngines.containsKey("600001"), "应包含 600001");

        // 验证每个 engine 的订单簿存在
        for (Map.Entry<String, MatchingEngine> entry : matchingEngines.entrySet()) {
            MatchingEngine engine = entry.getValue();
            OrderBook orderBook = engine.getOrderBook();
            assertNotNull(orderBook, "订单簿不应为 null");
        }
    }

    /**
     * 测试读取空快照
     * - 验证空快照读取正确
     */
    @Test
    void testReadSnapshot_EmptySnapshot() throws Exception {
        // Given: 写入空快照
        long cqIndex = 23456L;
        long matchingInputIndex = 200L;
        long matchingOutputLastIndex = 150L;
        writer.writeSnapshot(cqIndex, matchingInputIndex, matchingOutputLastIndex);

        String baseName = "matching_snapshot_" + cqIndex;
        Path datFile = tempDir.resolve(baseName + ".dat");

        assertTrue(Files.exists(datFile), "快照文件应存在");

        // 清空 engines
        matchingEngines.clear();

        // When: 读取快照
        MatchingSnapshotReader.SnapshotData result = reader.readSnapshot(datFile.toString());

        // Then: 验证结果
        assertNotNull(result, "读取结果不应为 null");
        assertEquals(matchingInputIndex, result.getMatchingInputIndex(), "matchingInputIndex 应恢复");
        assertEquals(matchingOutputLastIndex, result.getMatchingOutputLastIndex(), "matchingOutputLastIndex 应恢复");

        // 空 snapshot，没有 engines
        assertTrue(matchingEngines.isEmpty(), "engines 应为空");
    }
}
