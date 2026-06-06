package com.hft.trading.snapshot;

import com.hft.trading.event.AccountInputEvent;
import com.hft.trading.queue.AccountInputQueue;
import com.hft.trading.queue.MatchingInputQueue;
import com.hft.trading.service.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SnapshotWorker - 快照管理器
 *
 * 根据 V12 技术方案第 6 节：
 * - 定时写入 SNAPSHOT 事件到 account_input/matching_input CQ
 * - 各 Worker 处理 SNAPSHOT 事件时执行快照
 * - 快照原子写入：tmp → rename
 * - MD5 校验保证完整性
 */
public class SnapshotWorker {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotWorker.class);

    // 配置
    private static final long SNAPSHOT_INTERVAL_MS = 60_000; // 60秒
    private static final int MAX_RETRY = 3;
    private static final int KEEP_SNAPSHOT_COUNT = 3;

    // 队列引用
    private final AccountInputQueue accountInputQueue;
    private final MatchingInputQueue matchingInputQueue;

    // 快照目录
    private final String snapshotDir;

    // 告警服务
    private final AlertService alertService;

    // 定时任务
    private ScheduledExecutorService scheduler;

    // 状态
    private volatile boolean running = false;
    private final AtomicInteger consecutiveTimeoutCount = new AtomicInteger(0);
    private volatile long lastSnapshotTime = 0;

    public SnapshotWorker(AccountInputQueue accountInputQueue,
                          MatchingInputQueue matchingInputQueue,
                          String snapshotDir,
                          AlertService alertService) {
        this.accountInputQueue = accountInputQueue;
        this.matchingInputQueue = matchingInputQueue;
        this.snapshotDir = snapshotDir;
        this.alertService = alertService;

        // 确保快照目录存在
        try {
            Files.createDirectories(Paths.get(snapshotDir));
        } catch (Exception e) {
            logger.warn("Failed to create snapshot directory: {}", e.getMessage());
        }
    }

    /**
     * 启动快照任务
     */
    public void start() {
        if (running) {
            logger.warn("SnapshotWorker is already running");
            return;
        }

        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SnapshotWorker");
            t.setDaemon(true);
            return t;
        });

        // 定时快照任务
        scheduler.scheduleAtFixedRate(
            this::performSnapshot,
            SNAPSHOT_INTERVAL_MS,
            SNAPSHOT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        logger.info("SnapshotWorker started, interval={}ms", SNAPSHOT_INTERVAL_MS);
    }

    /**
     * 停止快照任务
     */
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.info("SnapshotWorker stopped");
    }

    /**
     * 执行快照
     */
    private void performSnapshot() {
        if (!running) {
            return;
        }

        logger.debug("Starting snapshot cycle...");
        long startTime = System.currentTimeMillis();

        try {
            // 1. 写入 SNAPSHOT 到 account_input CQ
            long accountCqIndex = accountInputQueue.getLastIndex();
            AccountInputEvent accountSnapshotEvent = AccountInputEvent.snapshot(accountCqIndex);
            accountInputQueue.writeEvent(accountSnapshotEvent);
            logger.debug("SNAPSHOT event written to account_input CQ at index: {}", accountCqIndex);

            // 2. 写入 SNAPSHOT 到 matching_input CQ
            long matchingCqIndex = matchingInputQueue.getLastIndex();
            AccountInputEvent matchingSnapshotEvent = AccountInputEvent.snapshot(matchingCqIndex);
            matchingInputQueue.writeEvent(matchingSnapshotEvent);
            logger.debug("SNAPSHOT event written to matching_input CQ at index: {}", matchingCqIndex);

            // 3. 等待快照完成（通过检查快照文件是否存在）
            boolean accountSnapshotOk = waitForSnapshot("account", accountCqIndex, 5000);
            boolean matchingSnapshotOk = waitForSnapshot("matching", matchingCqIndex, 5000);

            if (accountSnapshotOk && matchingSnapshotOk) {
                consecutiveTimeoutCount.set(0);
                lastSnapshotTime = System.currentTimeMillis();

                long elapsed = System.currentTimeMillis() - startTime;
                logger.info("Snapshot completed successfully in {}ms", elapsed);

                // 清理旧快照
                cleanupOldSnapshots("account");
                cleanupOldSnapshots("matching");
            } else {
                handleSnapshotTimeout();
            }

        } catch (Exception e) {
            logger.error("Snapshot failed: {}", e.getMessage(), e);
            handleSnapshotTimeout();
        }
    }

    /**
     * 等待快照完成
     */
    private boolean waitForSnapshot(String type, long cqIndex, long timeoutMs) {
        String snapshotFile = snapshotDir + type + "_snapshot_" + cqIndex + ".dat";
        String checksumFile = snapshotFile + ".checksum";

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (Files.exists(Paths.get(snapshotFile)) && Files.exists(Paths.get(checksumFile))) {
                // 验证 MD5
                if (verifyChecksum(snapshotFile, checksumFile)) {
                    return true;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        logger.warn("Snapshot timeout: type={}, index={}", type, cqIndex);
        return false;
    }

    /**
     * 处理快照超时
     */
    private void handleSnapshotTimeout() {
        int count = consecutiveTimeoutCount.incrementAndGet();
        logger.warn("Consecutive snapshot timeout count: {}", count);

        if (count >= 3) {
            alertService.sendP0Alert("SnapshotWorker", "3 consecutive snapshot timeouts!");
        }
    }

    /**
     * 强制快照（用于关闭前）
     */
    public void forceSnapshot() {
        logger.info("Forcing final snapshot...");
        performSnapshot();
    }

    /**
     * 清理旧快照文件
     */
    private void cleanupOldSnapshots(String type) {
        try {
            File dir = new File(snapshotDir);
            File[] files = dir.listFiles((d, name) ->
                name.startsWith(type + "_snapshot_") && name.endsWith(".dat"));

            if (files != null && files.length > KEEP_SNAPSHOT_COUNT) {
                // 按文件名排序（包含时间戳）
                java.util.Arrays.sort(files);

                // 删除最旧的文件
                int toDelete = files.length - KEEP_SNAPSHOT_COUNT;
                for (int i = 0; i < toDelete; i++) {
                    File datFile = files[i];
                    File checksumFile = new File(datFile.getPath() + ".checksum");

                    Files.deleteIfExists(datFile.toPath());
                    Files.deleteIfExists(checksumFile.toPath());
                    logger.debug("Deleted old snapshot: {}", datFile.getName());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup old snapshots: {}", e.getMessage());
        }
    }

    /**
     * 验证 MD5 校验和
     */
    private boolean verifyChecksum(String dataFile, String checksumFile) {
        try {
            byte[] data = Files.readAllBytes(Paths.get(dataFile));
            byte[] expectedChecksum = Files.readAllBytes(Paths.get(checksumFile));

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] actualChecksum = md.digest(data);

            // 比较校验和（十六进制字符串）
            String expected = bytesToHex(expectedChecksum).trim();
            String actual = bytesToHex(actualChecksum);

            return expected.equalsIgnoreCase(actual);
        } catch (Exception e) {
            logger.error("Checksum verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // Getters
    public boolean isRunning() { return running; }
    public long getLastSnapshotTime() { return lastSnapshotTime; }
    public int getConsecutiveTimeoutCount() { return consecutiveTimeoutCount.get(); }
}
