package com.hft.trading.snapshot;

import com.hft.trading.matching.MatchingEngine;
import com.hft.trading.matching.OrderBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;

/**
 * MatchingSnapshotWriter - 订单簿快照写入器
 *
 * 根据 V12 技术方案：
 * - 文件命名：matching_snapshot_{cq_index}.dat
 * - 文件结构：magic → version → createTime → symbolCount → [symbol → askLevels → bidLevels] → matchingInputIndex → matchingOutputLastIndex
 * - 原子写入：tmp → checksum → rename
 * - MD5 校验
 *
 * 简化实现：只保存 askLevels/bidLevels 数量，实际订单通过 CQ 重放恢复
 */
public class MatchingSnapshotWriter {

    private static final Logger logger = LoggerFactory.getLogger(MatchingSnapshotWriter.class);

    // 文件魔数和版本
    private static final int MAGIC = 0x4D414354; // "MACT"
    private static final int VERSION = 1;

    // 撮合引擎引用
    private final Map<String, MatchingEngine> matchingEngines;

    // 快照目录
    private final String snapshotDir;

    /**
     * 构造函数
     *
     * @param matchingEngines 撮合引擎 Map（symbol -> MatchingEngine）
     * @param snapshotDir 快照目录路径（以 / 结尾）
     */
    public MatchingSnapshotWriter(Map<String, MatchingEngine> matchingEngines, String snapshotDir) {
        this.matchingEngines = matchingEngines;
        this.snapshotDir = snapshotDir;
    }

    /**
     * 写入订单簿快照
     *
     * @param cqIndex CQ 索引
     * @param matchingInputIndex matching_input CQ 处理位置
     * @param matchingOutputLastIndex matching_output CQ 最后处理位置
     */
    public void writeSnapshot(long cqIndex, long matchingInputIndex, long matchingOutputLastIndex) {
        String baseName = "matching_snapshot_" + cqIndex;
        String tmpFile = snapshotDir + baseName + ".tmp";
        String datFile = snapshotDir + baseName + ".dat";
        String checksumTmpFile = snapshotDir + baseName + ".tmp.checksum";
        String checksumFile = snapshotDir + baseName + ".dat.checksum";

        logger.info("Writing matching snapshot: cqIndex={}", cqIndex);

        try {
            // 1. 写入数据到临时文件
            DataOutputStream dos = new DataOutputStream(new FileOutputStream(tmpFile));

            // 写入魔数和版本
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeLong(System.currentTimeMillis());

            // 写入订单簿数据
            dos.writeInt(matchingEngines.size());
            for (Map.Entry<String, MatchingEngine> entry : matchingEngines.entrySet()) {
                String symbol = entry.getKey();
                MatchingEngine engine = entry.getValue();
                OrderBook orderBook = engine.getOrderBook();

                dos.writeUTF(symbol);
                dos.writeInt(orderBook.getSellOrderCount());  // askLevels
                dos.writeInt(orderBook.getBuyOrderCount());   // bidLevels
            }

            // 写入 CQ 索引
            dos.writeLong(matchingInputIndex);
            dos.writeLong(matchingOutputLastIndex);

            dos.flush();
            dos.close();

            // 2. 计算 MD5 并写入校验文件
            String md5 = calculateMD5(tmpFile);
            try (FileOutputStream fos = new FileOutputStream(checksumTmpFile)) {
                fos.write(md5.getBytes());
            }

            // 3. 原子重命名
            Files.move(Paths.get(tmpFile), Paths.get(datFile), StandardCopyOption.ATOMIC_MOVE);
            Files.move(Paths.get(checksumTmpFile), Paths.get(checksumFile), StandardCopyOption.ATOMIC_MOVE);

            logger.info("Matching snapshot written: file={}, symbolCount={}",
                datFile, matchingEngines.size());

        } catch (Exception e) {
            logger.error("Failed to write matching snapshot: {}", e.getMessage(), e);
            // 清理临时文件
            try {
                Files.deleteIfExists(Paths.get(tmpFile));
                Files.deleteIfExists(Paths.get(checksumTmpFile));
            } catch (Exception ignored) {}
        }
    }

    /**
     * 计算文件 MD5
     */
    private String calculateMD5(String filePath) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] fileBytes = Files.readAllBytes(Paths.get(filePath));
        byte[] digest = md.digest(fileBytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}