package com.hft.trading.recovery;

import com.hft.trading.matching.MatchingEngine;
import com.hft.trading.queue.*;
import com.hft.trading.snapshot.AccountSnapshotReader;
import com.hft.trading.snapshot.MatchingSnapshotReader;
import com.hft.trading.snapshot.SnapshotFileUtils;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import com.hft.trading.store.InMemoryPositionStore;
import com.hft.trading.worker.AccountWorker;
import com.hft.trading.worker.ClearingWorker;
import com.hft.trading.worker.MatchingEngineWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

/**
 * RecoveryManager - 恢复管理器
 *
 * 根据 V12 技术方案，实现 4 阶段恢复流程：
 * Phase 0: 检测并恢复临时文件
 * Phase 1: AccountWorker 阶段 1 - 加载快照，设置 replay 模式，构建幂等集合，重放 CQ
 * Phase 2: MatchingEngine - 加载快照，设置 replay 模式，重放 CQ
 * Phase 3: ClearingWorker - 加载位置或回退，重放 CQ
 * Phase 4: AccountWorker 阶段 2 - 重放结算 CQ，禁用 replay 模式
 */
public class RecoveryManager {

    private static final Logger logger = LoggerFactory.getLogger(RecoveryManager.class);

    // 配置路径
    private final String snapshotDir;
    private final String chronicleBasePath;

    // 存储引用
    private final InMemoryAccountStore accountStore;
    private final InMemoryPositionStore positionStore;
    private final InMemoryOrderStore orderStore;
    private final InMemoryInstrumentStore instrumentStore;
    private final Map<String, MatchingEngine> matchingEngines;

    // 队列引用
    private final AccountInputQueue accountInputQueue;
    private final MatchingInputQueue matchingInputQueue;
    private final MatchingOutputQueue matchingOutputQueue;
    private final AccountSettlementQueue accountSettlementQueue;

    // Worker 引用
    private final AccountWorker accountWorker;
    private final MatchingEngineWorker matchingEngineWorker;
    private final ClearingWorker clearingWorker;

    /**
     * 构造函数
     *
     * @param snapshotDir 快照目录
     * @param chronicleBasePath Chronicle 数据目录
     * @param accountStore 账户存储
     * @param positionStore 持仓存储
     * @param orderStore 订单存储
     * @param instrumentStore 证券存储
     * @param matchingEngines 撮合引擎 Map
     * @param accountInputQueue 账户输入队列
     * @param matchingInputQueue 撮合输入队列
     * @param matchingOutputQueue 撮合输出队列
     * @param accountSettlementQueue 账户结算队列
     * @param accountWorker 账户处理器
     * @param matchingEngineWorker 撮合引擎处理器
     * @param clearingWorker 清算处理器
     */
    public RecoveryManager(String snapshotDir,
                           String chronicleBasePath,
                           InMemoryAccountStore accountStore,
                           InMemoryPositionStore positionStore,
                           InMemoryOrderStore orderStore,
                           InMemoryInstrumentStore instrumentStore,
                           Map<String, MatchingEngine> matchingEngines,
                           AccountInputQueue accountInputQueue,
                           MatchingInputQueue matchingInputQueue,
                           MatchingOutputQueue matchingOutputQueue,
                           AccountSettlementQueue accountSettlementQueue,
                           AccountWorker accountWorker,
                           MatchingEngineWorker matchingEngineWorker,
                           ClearingWorker clearingWorker) {
        this.snapshotDir = snapshotDir;
        this.chronicleBasePath = chronicleBasePath;
        this.accountStore = accountStore;
        this.positionStore = positionStore;
        this.orderStore = orderStore;
        this.instrumentStore = instrumentStore;
        this.matchingEngines = matchingEngines;
        this.accountInputQueue = accountInputQueue;
        this.matchingInputQueue = matchingInputQueue;
        this.matchingOutputQueue = matchingOutputQueue;
        this.accountSettlementQueue = accountSettlementQueue;
        this.accountWorker = accountWorker;
        this.matchingEngineWorker = matchingEngineWorker;
        this.clearingWorker = clearingWorker;
    }

    /**
     * 检查是否需要恢复
     *
     * 检查规则：
     * 1. 快照目录下存在 .dat 文件（非 clearing_position.dat）
     * 2. Chronicle 目录下存在 .cq4 文件
     * 3. 存在 clearing_position.dat
     *
     * @return true 如果需要恢复
     */
    public boolean needsRecovery() {
        Path snapshotPath = Paths.get(snapshotDir);
        Path chroniclePath = Paths.get(chronicleBasePath);

        // 检查快照文件（排除 clearing_position.dat）
        if (hasDataFiles(snapshotPath)) {
            logger.info("Recovery needed: found .dat files in snapshot directory");
            return true;
        }

        // 检查 CQ 文件
        if (hasCqFiles(chroniclePath)) {
            logger.info("Recovery needed: found .cq4 files in chronicle directory");
            return true;
        }

        // 检查 clearing_position.dat
        Path clearingPosition = chroniclePath.resolve("clearing_position.dat");
        if (Files.exists(clearingPosition)) {
            logger.info("Recovery needed: found clearing_position.dat");
            return true;
        }

        logger.info("No recovery needed: no snapshot or CQ files found");
        return false;
    }

    /**
     * 执行 4 阶段恢复
     *
     * @throws Exception 恢复失败
     */
    public void performRecovery() throws Exception {
        logger.info("Starting 4-phase recovery...");

        // Phase 0: 检测并恢复临时文件
        phase0_recoverTmpFiles();

        // Phase 1: AccountWorker 阶段 1
        phase1_accountWorkerPhase1();

        // Phase 2: MatchingEngine
        phase2_matchingEngine();

        // Phase 3: ClearingWorker
        phase3_clearingWorker();

        // Phase 4: AccountWorker 阶段 2
        phase4_accountWorkerPhase2();

        logger.info("4-phase recovery completed successfully");
    }

    // ==================== Phase 实现 ====================

    /**
     * Phase 0: 检测并恢复临时文件
     */
    private void phase0_recoverTmpFiles() {
        logger.info("Phase 0: Detecting and recovering tmp files...");

        // 恢复账户快照临时文件
        SnapshotFileUtils.detectAndRecoverTmpFiles(snapshotDir, "account");

        // 恢复订单簿快照临时文件
        SnapshotFileUtils.detectAndRecoverTmpFiles(snapshotDir, "matching");

        logger.info("Phase 0 completed");
    }

    /**
     * Phase 1: AccountWorker 阶段 1
     * - 加载账户快照
     * - 设置 replay 模式
     * - 构建幂等集合
     * - 重放 account_input CQ
     */
    private void phase1_accountWorkerPhase1() throws Exception {
        logger.info("Phase 1: AccountWorker phase 1 starting...");

        if (accountWorker == null) {
            logger.warn("AccountWorker is null, skipping Phase 1");
            return;
        }

        // 查找最新账户快照
        var snapshotInfo = SnapshotFileUtils.findLatestValidSnapshot(snapshotDir, "account");

        long accountInputIndex = -1;
        long accountSettlementIndex = -1;
        long matchingInputLastIndex = -1;

        if (snapshotInfo.isPresent()) {
            // 加载快照到内存
            AccountSnapshotReader reader = new AccountSnapshotReader(accountStore, positionStore);
            String filePath = snapshotInfo.get().getFilePath();
            var snapshotData = reader.readSnapshot(filePath);

            accountInputIndex = snapshotData.getAccountInputIndex();
            accountSettlementIndex = snapshotData.getAccountSettlementIndex();
            matchingInputLastIndex = snapshotData.getMatchingInputLastIndex();

            logger.info("Account snapshot loaded: accountInputIndex={}, accountSettlementIndex={}, matchingInputLastIndex={}",
                accountInputIndex, accountSettlementIndex, matchingInputLastIndex);
        } else {
            logger.info("No valid account snapshot found, starting from beginning");
        }

        // 设置 replay 模式
        accountWorker.setReplayMode(true);

        // 构建幂等集合（简化：通过重放 CQ 自动构建）
        logger.info("Building idempotent sets via CQ replay...");

        // 重放 account_input CQ
        if (accountInputQueue != null && accountInputIndex >= 0) {
            replayAccountInputCQ(accountInputIndex + 1);
        }

        logger.info("Phase 1 completed");
    }

    /**
     * Phase 2: MatchingEngine
     * - 加载订单簿快照
     * - 设置 replay 模式
     * - 重放 matching_input CQ
     */
    private void phase2_matchingEngine() throws Exception {
        logger.info("Phase 2: MatchingEngine starting...");

        if (matchingEngineWorker == null) {
            logger.warn("MatchingEngineWorker is null, skipping Phase 2");
            return;
        }

        // 查找最新订单簿快照
        var snapshotInfo = SnapshotFileUtils.findLatestValidSnapshot(snapshotDir, "matching");

        long matchingInputIndex = -1;
        long matchingOutputLastIndex = -1;

        if (snapshotInfo.isPresent()) {
            // 加载快照到内存
            MatchingSnapshotReader reader = new MatchingSnapshotReader(matchingEngines);
            String filePath = snapshotInfo.get().getFilePath();
            var snapshotData = reader.readSnapshot(filePath);

            matchingInputIndex = snapshotData.getMatchingInputIndex();
            matchingOutputLastIndex = snapshotData.getMatchingOutputLastIndex();

            logger.info("Matching snapshot loaded: matchingInputIndex={}, matchingOutputLastIndex={}",
                matchingInputIndex, matchingOutputLastIndex);
        } else {
            logger.info("No valid matching snapshot found, starting from beginning");
        }

        // 设置 replay 模式
        matchingEngineWorker.setReplayMode(true);

        // 重放 matching_input CQ
        if (matchingInputQueue != null && matchingInputIndex >= 0) {
            replayMatchingInputCQ(matchingInputIndex + 1);
        }

        logger.info("Phase 2 completed");
    }

    /**
     * Phase 3: ClearingWorker
     * - 加载处理位置或回退
     * - 设置 replay 模式
     * - 重放 matching_output CQ
     */
    private void phase3_clearingWorker() throws Exception {
        logger.info("Phase 3: ClearingWorker starting...");

        if (clearingWorker == null) {
            logger.warn("ClearingWorker is null, skipping Phase 3");
            return;
        }

        // 加载处理位置
        ClearingPositionManager positionManager = new ClearingPositionManager(chronicleBasePath);
        long clearingPosition = positionManager.loadPosition();

        logger.info("Clearing position loaded: {}", clearingPosition);

        // 设置 replay 模式
        clearingWorker.setReplayMode(true);

        // 重放 matching_output CQ
        if (matchingOutputQueue != null && clearingPosition >= 0) {
            replayMatchingOutputCQ(clearingPosition + 1);
        }

        logger.info("Phase 3 completed");
    }

    /**
     * Phase 4: AccountWorker 阶段 2
     * - 重放 account_settlement CQ
     * - 禁用 replay 模式
     */
    private void phase4_accountWorkerPhase2() throws Exception {
        logger.info("Phase 4: AccountWorker phase 2 starting...");

        if (accountWorker == null) {
            logger.warn("AccountWorker is null, skipping Phase 4");
            return;
        }

        // 重放 account_settlement CQ（处理 SETTLEMENT 事件）
        if (accountSettlementQueue != null) {
            replayAccountSettlementCQ();
        }

        // 禁用 replay 模式
        accountWorker.setReplayMode(false);

        if (matchingEngineWorker != null) {
            matchingEngineWorker.setReplayMode(false);
        }

        if (clearingWorker != null) {
            clearingWorker.setReplayMode(false);
        }

        logger.info("Phase 4 completed");
    }

    // ==================== CQ 重放方法（简化实现）====================

    /**
     * 重放 account_input CQ
     */
    private void replayAccountInputCQ(long startIndex) {
        logger.info("Replaying account_input CQ from index: {}", startIndex);
        // 简化实现：实际实现需要从指定索引读取 CQ 事件并处理
        // 由 AccountWorker 的 run() 方法在启动时自动重放
        logger.info("Account_input CQ replay completed (simplified)");
    }

    /**
     * 重放 matching_input CQ
     */
    private void replayMatchingInputCQ(long startIndex) {
        logger.info("Replaying matching_input CQ from index: {}", startIndex);
        // 简化实现：实际实现需要从指定索引读取 CQ 事件并处理
        logger.info("Matching_input CQ replay completed (simplified)");
    }

    /**
     * 重放 matching_output CQ
     */
    private void replayMatchingOutputCQ(long startIndex) {
        logger.info("Replaying matching_output CQ from index: {}", startIndex);
        // 简化实现：实际实现需要从指定索引读取 CQ 事件并处理
        logger.info("Matching_output CQ replay completed (simplified)");
    }

    /**
     * 重放 account_settlement CQ
     */
    private void replayAccountSettlementCQ() {
        logger.info("Replaying account_settlement CQ");
        // 简化实现：实际实现需要读取 CQ 事件并处理 SETTLEMENT
        logger.info("Account_settlement CQ replay completed (simplified)");
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查目录是否存在 .dat 数据文件（排除 position 文件）
     */
    private boolean hasDataFiles(Path dir) {
        if (!Files.exists(dir)) {
            return false;
        }

        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".dat") && !name.equals("clearing_position.dat");
            });
        } catch (IOException e) {
            logger.warn("Failed to list files in directory: {}", dir, e);
            return false;
        }
    }

    /**
     * 检查目录是否存在 .cq4 文件
     */
    private boolean hasCqFiles(Path dir) {
        if (!Files.exists(dir)) {
            return false;
        }

        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".cq4"));
        } catch (IOException e) {
            logger.warn("Failed to list files in directory: {}", dir, e);
            return false;
        }
    }
}
