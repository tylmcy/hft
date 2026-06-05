package com.hft.trading.recovery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClearingPositionManager 单元测试
 *
 * 测试 ClearingWorker 恢复位置管理器的保存、加载和原子写入功能
 */
class ClearingPositionManagerTest {

    @TempDir
    Path tempDir;

    private ClearingPositionManager positionManager;

    @BeforeEach
    void setUp() {
        positionManager = new ClearingPositionManager(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        // 清理测试文件
        Files.deleteIfExists(tempDir.resolve("clearing_position.dat"));
        Files.deleteIfExists(tempDir.resolve("clearing_position.dat.tmp"));
    }

    @Test
    @DisplayName("保存和加载位置 - 验证数据一致性")
    void testSaveAndLoad() {
        // 保存位置
        long position = 12345L;
        positionManager.savePosition(position);

        // 加载位置
        long loadedPosition = positionManager.loadPosition();

        // 验证
        assertEquals(position, loadedPosition, "加载的位置应与保存的位置一致");
    }

    @Test
    @DisplayName("加载位置 - 文件不存在时返回 -1")
    void testLoadWhenFileNotExists() {
        // 文件不存在时加载
        long loadedPosition = positionManager.loadPosition();

        // 验证返回 -1
        assertEquals(-1L, loadedPosition, "文件不存在时应返回 -1");
    }

    @Test
    @DisplayName("原子写入 - 保存后不应存在 .tmp 文件")
    void testAtomicWrite() {
        // 保存位置
        positionManager.savePosition(98765L);

        // 验证 .tmp 文件不存在
        Path tmpFile = tempDir.resolve("clearing_position.dat.tmp");
        assertFalse(Files.exists(tmpFile), ".tmp 文件在保存后应被删除或重命名");

        // 验证 .dat 文件存在
        Path datFile = tempDir.resolve("clearing_position.dat");
        assertTrue(Files.exists(datFile), ".dat 文件应存在");
    }

    @Test
    @DisplayName("覆盖保存 - 多次保存应更新位置")
    void testOverwriteSave() {
        // 第一次保存
        positionManager.savePosition(100L);
        assertEquals(100L, positionManager.loadPosition());

        // 第二次保存（覆盖）
        positionManager.savePosition(200L);
        assertEquals(200L, positionManager.loadPosition());

        // 第三次保存（覆盖）
        positionManager.savePosition(300L);
        assertEquals(300L, positionManager.loadPosition());
    }

    @Test
    @DisplayName("边界值测试 - 位置为 0")
    void testZeroPosition() {
        positionManager.savePosition(0L);
        assertEquals(0L, positionManager.loadPosition(), "位置为 0 时应正确保存和加载");
    }

    @Test
    @DisplayName("边界值测试 - 位置为最大值")
    void testMaxPosition() {
        long maxPosition = Long.MAX_VALUE;
        positionManager.savePosition(maxPosition);
        assertEquals(maxPosition, positionManager.loadPosition(), "位置为 Long.MAX_VALUE 时应正确保存和加载");
    }
}