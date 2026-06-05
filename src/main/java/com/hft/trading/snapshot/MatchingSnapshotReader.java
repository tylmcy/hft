package com.hft.trading.snapshot;

import com.hft.trading.matching.MatchingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.util.Map;

/**
 * MatchingSnapshotReader - 订单簿快照读取器
 *
 * 根据 V12 技术方案：
 * - 文件结构：magic → version → createTime → symbolCount → [symbol → askLevels → bidLevels] → matchingInputIndex → matchingOutputLastIndex
 * - 为每个 symbol 创建 MatchingEngine（使用 computeIfAbsent）
 * - 返回 CQ 索引信息
 */
public class MatchingSnapshotReader {

    private static final Logger logger = LoggerFactory.getLogger(MatchingSnapshotReader.class);

    // 文件魔数和版本
    private static final int MAGIC = 0x4D414354; // "MACT"
    private static final int VERSION = 1;

    // 撮合引擎引用
    private final Map<String, MatchingEngine> matchingEngines;

    /**
     * 构造函数
     *
     * @param matchingEngines 撮合引擎 Map（symbol -> MatchingEngine）
     */
    public MatchingSnapshotReader(Map<String, MatchingEngine> matchingEngines) {
        this.matchingEngines = matchingEngines;
    }

    /**
     * 读取订单簿快照
     *
     * @param filePath 快照文件路径
     * @return 快照数据（包含 CQ 索引）
     * @throws Exception 读取失败
     */
    public SnapshotData readSnapshot(String filePath) throws Exception {
        logger.info("Reading matching snapshot: file={}", filePath);

        try (DataInputStream dis = new DataInputStream(new FileInputStream(filePath))) {
            // 验证魔数
            int magic = dis.readInt();
            if (magic != MAGIC) {
                throw new IllegalStateException("Invalid magic number: expected " + MAGIC + ", got " + magic);
            }

            // 验证版本
            int version = dis.readInt();
            if (version != VERSION) {
                throw new IllegalStateException("Unsupported version: " + version);
            }

            // 读取时间戳
            dis.readLong(); // createTime - not used in recovery

            // 读取订单簿数量
            int symbolCount = dis.readInt();

            // 读取每个 symbol 的订单簿
            for (int i = 0; i < symbolCount; i++) {
                String symbol = dis.readUTF();
                int askLevels = dis.readInt();
                int bidLevels = dis.readInt();

                // 使用 computeIfAbsent 创建 MatchingEngine
                matchingEngines.computeIfAbsent(symbol, MatchingEngine::new);

                logger.debug("Restored orderbook for symbol={}, askLevels={}, bidLevels={}",
                    symbol, askLevels, bidLevels);
            }

            // 读取 CQ 索引
            long matchingInputIndex = dis.readLong();
            long matchingOutputLastIndex = dis.readLong();

            logger.info("Matching snapshot read: symbolCount={}, matchingInputIndex={}, matchingOutputLastIndex={}",
                symbolCount, matchingInputIndex, matchingOutputLastIndex);

            return new SnapshotData(matchingInputIndex, matchingOutputLastIndex);
        }
    }

    /**
     * 快照数据
     */
    public static class SnapshotData {
        private final long matchingInputIndex;
        private final long matchingOutputLastIndex;

        public SnapshotData(long matchingInputIndex, long matchingOutputLastIndex) {
            this.matchingInputIndex = matchingInputIndex;
            this.matchingOutputLastIndex = matchingOutputLastIndex;
        }

        public long getMatchingInputIndex() {
            return matchingInputIndex;
        }

        public long getMatchingOutputLastIndex() {
            return matchingOutputLastIndex;
        }
    }
}
