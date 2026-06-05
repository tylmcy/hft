package com.hft.trading.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * ClearingWorker 恢复位置管理器
 *
 * 用于持久化 ClearingWorker 处理 trades CQ 的位置索引
 * 采用原子写入策略（tmp -> rename）保证 RPO=0
 */
public class ClearingPositionManager {

    private static final Logger log = LoggerFactory.getLogger(ClearingPositionManager.class);

    private static final String POSITION_FILE = "clearing_position.dat";
    private static final String TEMP_FILE = "clearing_position.dat.tmp";

    private final Path dataDir;

    /**
     * 构造函数
     *
     * @param dataDir 数据目录路径
     */
    public ClearingPositionManager(String dataDir) {
        this.dataDir = Paths.get(dataDir);
        // 确保目录存在
        try {
            if (!Files.exists(this.dataDir)) {
                Files.createDirectories(this.dataDir);
                log.info("Created data directory: {}", dataDir);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create data directory: " + dataDir, e);
        }
    }

    /**
     * 保存位置索引
     *
     * 采用原子写入策略：
     * 1. 写入 .tmp 文件
     * 2. 原子重命名为 .dat 文件
     *
     * @param position trades CQ 的位置索引
     */
    public void savePosition(long position) {
        Path tmpPath = dataDir.resolve(TEMP_FILE);
        Path datPath = dataDir.resolve(POSITION_FILE);

        try {
            // 1. 写入临时文件
            try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(tmpPath))) {
                dos.writeLong(position);
                dos.flush();
            }

            // 2. 原子重命名（POSIX 保证原子性）
            Files.move(tmpPath, datPath, StandardCopyOption.ATOMIC_MOVE);

            log.debug("Saved clearing position: {}", position);
        } catch (IOException e) {
            log.error("Failed to save clearing position: {}", position, e);
            // 尝试清理临时文件
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) {
            }
            throw new RuntimeException("Failed to save clearing position", e);
        }
    }

    /**
     * 加载位置索引
     *
     * @return 位置索引，如果文件不存在则返回 -1
     */
    public long loadPosition() {
        Path datPath = dataDir.resolve(POSITION_FILE);

        if (!Files.exists(datPath)) {
            log.debug("Position file not found, returning -1");
            return -1L;
        }

        try (DataInputStream dis = new DataInputStream(Files.newInputStream(datPath))) {
            long position = dis.readLong();
            log.debug("Loaded clearing position: {}", position);
            return position;
        } catch (IOException e) {
            log.error("Failed to load clearing position", e);
            throw new RuntimeException("Failed to load clearing position", e);
        }
    }
}