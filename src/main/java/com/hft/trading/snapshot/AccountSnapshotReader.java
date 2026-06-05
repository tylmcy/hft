package com.hft.trading.snapshot;

import com.hft.trading.model.Account;
import com.hft.trading.model.AccountStatus;
import com.hft.trading.model.Position;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryPositionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * AccountSnapshotReader - 账户快照读取器
 *
 * 根据 V12 技术方案 3.4.1 节：
 * - 读取快照文件格式：magic → version → accounts → positions → indices
 * - 恢复到内存存储
 * - 返回 CQ 索引信息
 */
public class AccountSnapshotReader {

    private static final Logger logger = LoggerFactory.getLogger(AccountSnapshotReader.class);

    // 文件魔数和版本
    private static final int MAGIC = 0x41434354; // "ACCT"
    private static final int VERSION = 1;

    // 存储引用
    private final InMemoryAccountStore accountStore;
    private final InMemoryPositionStore positionStore;

    public AccountSnapshotReader(InMemoryAccountStore accountStore,
                                  InMemoryPositionStore positionStore) {
        this.accountStore = accountStore;
        this.positionStore = positionStore;
    }

    /**
     * 读取账户快照
     *
     * @param filePath 快照文件路径
     * @return 快照数据（含 CQ 索引）
     * @throws Exception 文件不存在/格式错误/读取失败
     */
    public SnapshotData readSnapshot(String filePath) throws Exception {
        logger.info("Reading account snapshot: {}", filePath);

        // 验证文件存在
        if (!Files.exists(Paths.get(filePath))) {
            throw new IllegalArgumentException("快照文件不存在: " + filePath);
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(filePath))) {
            // 1. 读取并验证魔数
            int magic = dis.readInt();
            if (magic != MAGIC) {
                throw new IllegalArgumentException("无效的快照文件魔数: " + Integer.toHexString(magic));
            }

            // 2. 读取并验证版本
            int version = dis.readInt();
            if (version != VERSION) {
                throw new IllegalArgumentException("不支持的快照文件版本: " + version);
            }

            // 3. 读取创建时间
            long createTime = dis.readLong();
            logger.debug("Snapshot createTime: {}", createTime);

            // 4. 读取账户数据
            int accountCount = dis.readInt();
            logger.debug("Reading {} accounts", accountCount);

            for (int i = 0; i < accountCount; i++) {
                long accountId = dis.readLong();
                long userId = dis.readLong();
                long balance = dis.readLong();
                long available = dis.readLong();
                long frozen = dis.readLong();
                long marginFrozen = dis.readLong();
                long orderFrozen = dis.readLong();
                String statusStr = dis.readUTF();
                AccountStatus status = AccountStatus.valueOf(statusStr);

                // 从快照恢复账户
                Account account = Account.fromSnapshot(accountId, userId, balance, available,
                        frozen, marginFrozen, orderFrozen, status);
                accountStore.addAccount(account);
            }

            // 5. 读取持仓数据
            int positionCount = dis.readInt();
            logger.debug("Reading {} positions", positionCount);

            for (int i = 0; i < positionCount; i++) {
                long accountId = dis.readLong();
                String symbol = dis.readUTF();
                int symbolCode = dis.readInt();
                long quantity = dis.readLong();
                long available = dis.readLong();
                long frozen = dis.readLong();
                long t1Locked = dis.readLong();
                long avgPrice = dis.readLong();

                // 从快照恢复持仓
                Position position = Position.fromSnapshot(accountId, symbol, symbolCode,
                        quantity, available, frozen, t1Locked, avgPrice);
                positionStore.addPosition(position);
            }

            // 6. 读取 CQ 索引
            long accountInputIndex = dis.readLong();
            long accountSettlementIndex = dis.readLong();
            long matchingInputLastIndex = dis.readLong();

            logger.info("Account snapshot read: accounts={}, positions={}, accountInputIndex={}, " +
                    "accountSettlementIndex={}, matchingInputLastIndex={}",
                    accountCount, positionCount, accountInputIndex, accountSettlementIndex, matchingInputLastIndex);

            return new SnapshotData(accountInputIndex, accountSettlementIndex, matchingInputLastIndex);
        }
    }

    /**
     * 快照数据
     * 包含 CQ 索引信息
     */
    public static class SnapshotData {
        private final long accountInputIndex;
        private final long accountSettlementIndex;
        private final long matchingInputLastIndex;

        public SnapshotData(long accountInputIndex, long accountSettlementIndex, long matchingInputLastIndex) {
            this.accountInputIndex = accountInputIndex;
            this.accountSettlementIndex = accountSettlementIndex;
            this.matchingInputLastIndex = matchingInputLastIndex;
        }

        public long getAccountInputIndex() {
            return accountInputIndex;
        }

        public long getAccountSettlementIndex() {
            return accountSettlementIndex;
        }

        public long getMatchingInputLastIndex() {
            return matchingInputLastIndex;
        }
    }
}