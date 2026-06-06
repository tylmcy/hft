package com.hft.trading.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Chronicle Queue 管理器
 *
 * 统一管理所有 CQ 队列的生命周期
 */
@Component
public class QueueManager {

    private static final Logger logger = LoggerFactory.getLogger(QueueManager.class);

    private final AccountInputQueue accountInputQueue;
    private final MatchingInputQueue matchingInputQueue;
    private final MatchingOutputQueue matchingOutputQueue;
    private final AccountSettlementQueue accountSettlementQueue;

    // 配置参数
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int CLEARING_BATCH_SIZE = 100;
    private static final long DEFAULT_TIMEOUT_MS = 1;

    public QueueManager(String basePath) {
        logger.info("Initializing QueueManager with basePath: {}", basePath);

        this.accountInputQueue = new AccountInputQueue(basePath, DEFAULT_BATCH_SIZE, DEFAULT_TIMEOUT_MS);
        this.matchingInputQueue = new MatchingInputQueue(basePath, DEFAULT_BATCH_SIZE, DEFAULT_TIMEOUT_MS);
        this.matchingOutputQueue = new MatchingOutputQueue(basePath, CLEARING_BATCH_SIZE, DEFAULT_TIMEOUT_MS);
        this.accountSettlementQueue = new AccountSettlementQueue(basePath, DEFAULT_BATCH_SIZE, DEFAULT_TIMEOUT_MS);
    }

    public AccountInputQueue getAccountInputQueue() {
        return accountInputQueue;
    }

    public MatchingInputQueue getMatchingInputQueue() {
        return matchingInputQueue;
    }

    public MatchingOutputQueue getMatchingOutputQueue() {
        return matchingOutputQueue;
    }

    public AccountSettlementQueue getAccountSettlementQueue() {
        return accountSettlementQueue;
    }

    /**
     * 关闭所有队列
     */
    @PreDestroy
    public void closeAll() {
        logger.info("Closing all Chronicle Queues...");

        accountInputQueue.close();
        matchingInputQueue.close();
        matchingOutputQueue.close();
        accountSettlementQueue.close();

        logger.info("All Chronicle Queues closed");
    }

    /**
     * 获取所有队列的统计信息
     */
    public QueueStats getStats() {
        return new QueueStats(
            accountInputQueue.getCurrentIndex(),
            accountInputQueue.getLastIndex(),
            matchingInputQueue.getCurrentIndex(),
            matchingInputQueue.getLastIndex(),
            matchingOutputQueue.getCurrentIndex(),
            matchingOutputQueue.getLastIndex(),
            accountSettlementQueue.getCurrentIndex(),
            accountSettlementQueue.getLastIndex()
        );
    }

    /**
     * 队列统计信息
     */
    public static class QueueStats {
        private final long accountInputCurrentIndex;
        private final long accountInputLastIndex;
        private final long matchingInputCurrentIndex;
        private final long matchingInputLastIndex;
        private final long matchingOutputCurrentIndex;
        private final long matchingOutputLastIndex;
        private final long accountSettlementCurrentIndex;
        private final long accountSettlementLastIndex;

        public QueueStats(long accountInputCurrentIndex, long accountInputLastIndex,
                          long matchingInputCurrentIndex, long matchingInputLastIndex,
                          long matchingOutputCurrentIndex, long matchingOutputLastIndex,
                          long accountSettlementCurrentIndex, long accountSettlementLastIndex) {
            this.accountInputCurrentIndex = accountInputCurrentIndex;
            this.accountInputLastIndex = accountInputLastIndex;
            this.matchingInputCurrentIndex = matchingInputCurrentIndex;
            this.matchingInputLastIndex = matchingInputLastIndex;
            this.matchingOutputCurrentIndex = matchingOutputCurrentIndex;
            this.matchingOutputLastIndex = matchingOutputLastIndex;
            this.accountSettlementCurrentIndex = accountSettlementCurrentIndex;
            this.accountSettlementLastIndex = accountSettlementLastIndex;
        }

        // Getters
        public long getAccountInputCurrentIndex() { return accountInputCurrentIndex; }
        public long getAccountInputLastIndex() { return accountInputLastIndex; }
        public long getMatchingInputCurrentIndex() { return matchingInputCurrentIndex; }
        public long getMatchingInputLastIndex() { return matchingInputLastIndex; }
        public long getMatchingOutputCurrentIndex() { return matchingOutputCurrentIndex; }
        public long getMatchingOutputLastIndex() { return matchingOutputLastIndex; }
        public long getAccountSettlementCurrentIndex() { return accountSettlementCurrentIndex; }
        public long getAccountSettlementLastIndex() { return accountSettlementLastIndex; }

        @Override
        public String toString() {
            return String.format(
                "QueueStats{accountInput=[%d/%d], matchingInput=[%d/%d], " +
                "matchingOutput=[%d/%d], accountSettlement=[%d/%d]}",
                accountInputCurrentIndex, accountInputLastIndex,
                matchingInputCurrentIndex, matchingInputLastIndex,
                matchingOutputCurrentIndex, matchingOutputLastIndex,
                accountSettlementCurrentIndex, accountSettlementLastIndex
            );
        }
    }
}
