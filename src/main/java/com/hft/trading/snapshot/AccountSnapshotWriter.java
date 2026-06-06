package com.hft.trading.snapshot;

import com.hft.trading.model.Account;
import com.hft.trading.model.Position;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryPositionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;

/**
 * AccountSnapshotWriter - 账户快照写入器
 *
 * 根据 V12 技术方案 3.4.1 节：
 * - 文件命名：account_snapshot_{account_input_cq_index}.dat
 * - 文件结构：magic → version → accounts → positions → indices
 * - 原子写入：tmp → rename
 * - MD5 校验
 */
public class AccountSnapshotWriter {

    private static final Logger logger = LoggerFactory.getLogger(AccountSnapshotWriter.class);

    // 文件魔数和版本
    private static final int MAGIC = 0x41434354; // "ACCT"
    private static final int VERSION = 1;

    // 存储引用
    private final InMemoryAccountStore accountStore;
    private final InMemoryPositionStore positionStore;

    // 快照目录
    private final String snapshotDir;

    public AccountSnapshotWriter(InMemoryAccountStore accountStore,
                                  InMemoryPositionStore positionStore,
                                  String snapshotDir) {
        this.accountStore = accountStore;
        this.positionStore = positionStore;
        this.snapshotDir = snapshotDir;
    }

    /**
     * 写入账户快照
     *
     * @param cqIndex CQ 索引
     * @param accountInputIndex account_input CQ 处理位置
     * @param accountSettlementIndex account_settlement CQ 处理位置
     * @param matchingInputLastIndex matching_input CQ 最后处理位置
     */
    public void writeSnapshot(long cqIndex, long accountInputIndex,
                               long accountSettlementIndex, long matchingInputLastIndex) {
        String baseName = "account_snapshot_" + cqIndex;
        String tmpFile = snapshotDir + baseName + ".tmp";
        String datFile = snapshotDir + baseName + ".dat";
        String checksumTmpFile = snapshotDir + baseName + ".tmp.checksum";
        String checksumFile = snapshotDir + baseName + ".dat.checksum";

        logger.info("Writing account snapshot: cqIndex={}", cqIndex);

        try {
            // 1. 写入数据到临时文件
            DataOutputStream dos = new DataOutputStream(new FileOutputStream(tmpFile));

            // 写入魔数和版本
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeLong(System.currentTimeMillis());

            // 写入账户数据
            List<Account> accounts = accountStore.getAllAccounts();
            dos.writeInt(accounts.size());
            for (Account account : accounts) {
                dos.writeLong(account.getAccountId());
                dos.writeLong(account.getUserId());
                dos.writeLong(account.getBalance());
                dos.writeLong(account.getAvailable());
                dos.writeLong(account.getFrozen());
                dos.writeLong(account.getMarginFrozen());
                dos.writeLong(account.getOrderFrozen());
                dos.writeUTF(account.getStatus().name());
            }

            // 写入持仓数据
            List<Position> positions = positionStore.getAllPositions();
            dos.writeInt(positions.size());
            for (Position position : positions) {
                dos.writeLong(position.getAccountId());
                dos.writeUTF(position.getSymbol());
                dos.writeInt(position.getSymbolCode());
                dos.writeLong(position.getQuantity());
                dos.writeLong(position.getAvailable());
                dos.writeLong(position.getFrozen());
                dos.writeLong(position.getT1Locked());
                dos.writeLong(position.getAvgPrice());
            }

            // 写入 CQ 索引
            dos.writeLong(accountInputIndex);
            dos.writeLong(accountSettlementIndex);
            dos.writeLong(matchingInputLastIndex);

            dos.flush();
            dos.close();

            // 2. 计算 MD5 并写入校验文件
            String md5 = calculateMD5(tmpFile);
            FileWriter fw = new FileWriter(checksumTmpFile);
            fw.write(md5);
            fw.close();

            // 3. 原子重命名
            Files.move(Paths.get(tmpFile), Paths.get(datFile), StandardCopyOption.ATOMIC_MOVE);
            Files.move(Paths.get(checksumTmpFile), Paths.get(checksumFile), StandardCopyOption.ATOMIC_MOVE);

            logger.info("Account snapshot written: file={}, accounts={}, positions={}",
                datFile, accounts.size(), positions.size());

        } catch (Exception e) {
            logger.error("Failed to write account snapshot: {}", e.getMessage(), e);
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