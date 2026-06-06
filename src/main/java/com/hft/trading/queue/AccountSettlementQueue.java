package com.hft.trading.queue;

import com.hft.trading.event.EventType;
import com.hft.trading.event.SettlementEvent;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.DocumentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Account Settlement Chronicle Queue 管理器
 *
 * 根据 V12 技术方案：
 * - 文件路径：data/chronicle/account_settlement/
 * - blockSize：64MB
 * - writeMode：SYNC（RPO关键）
 * - 事件类型：SETTLEMENT / UNFREEZE
 */
public class AccountSettlementQueue {

    private static final Logger logger = LoggerFactory.getLogger(AccountSettlementQueue.class);

    private final ChronicleQueue queue;
    private final ExcerptAppender appender;
    private volatile ExcerptTailer tailer;

    private final int batchSize;
    private final long timeoutMs;

    public AccountSettlementQueue(String basePath, int batchSize, long timeoutMs) {
        this.batchSize = batchSize;
        this.timeoutMs = timeoutMs;

        this.queue = ChronicleQueue.singleBuilder(basePath + "account_settlement")
            .blockSize(64 << 20) // 64MB
            .build();

        this.appender = queue.createAppender();
        this.tailer = queue.createTailer();

        logger.info("AccountSettlementQueue initialized at: {}", basePath + "account_settlement");
    }

    /**
     * 写入 SETTLEMENT 事件
     */
    public long writeSettlementEvent(SettlementEvent event) {
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write("eventType").object(EventType.SETTLEMENT);
            dc.wire().write("event").object(event);
            long index = dc.index();
            logger.debug("Written SETTLEMENT event to account_settlement CQ: index={}, settlementId={}",
                index, event.getSettlementId());
            return index;
        } catch (Exception e) {
            logger.error("Failed to write SETTLEMENT event to account_settlement CQ: {}", e.getMessage());
            throw new RuntimeException("CQ write failed", e);
        }
    }

    /**
     * 写入 UNFREEZE 事件
     */
    public long writeUnfreezeEvent(long orderId, long accountId, long userId, String symbol,
                                    long unfreezeAmount, long unfreezeQty, EventType side) {
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write("eventType").object(EventType.UNFREEZE);
            dc.wire().write("orderId").object(orderId);
            dc.wire().write("accountId").object(accountId);
            dc.wire().write("userId").object(userId);
            dc.wire().write("symbol").object(symbol);
            dc.wire().write("unfreezeAmount").object(unfreezeAmount);
            dc.wire().write("unfreezeQty").object(unfreezeQty);
            dc.wire().write("side").object(side);
            dc.wire().write("timestamp").object(System.currentTimeMillis());
            long index = dc.index();
            logger.debug("Written UNFREEZE event to account_settlement CQ: index={}, orderId={}",
                index, orderId);
            return index;
        } catch (Exception e) {
            logger.error("Failed to write UNFREEZE event to account_settlement CQ: {}", e.getMessage());
            throw new RuntimeException("CQ write failed", e);
        }
    }

    /**
     * 批量读取事件
     */
    public List<SettlementQueueEvent> readBatch() {
        List<SettlementQueueEvent> events = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        while (events.size() < batchSize) {
            if (events.size() > 0 && System.currentTimeMillis() - startTime >= timeoutMs) {
                break;
            }

            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) {
                    if (events.size() == 0) {
                        try {
                            Thread.sleep(timeoutMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    break;
                }

                EventType eventType = dc.wire().read("eventType").object(EventType.class);
                Object eventData = null;

                switch (eventType) {
                    case SETTLEMENT:
                        eventData = dc.wire().read("event").object(SettlementEvent.class);
                        break;
                    case UNFREEZE:
                        Long orderId = dc.wire().read("orderId").object(Long.class);
                        Long accountId = dc.wire().read("accountId").object(Long.class);
                        Long userId = dc.wire().read("userId").object(Long.class);
                        String symbol = dc.wire().read("symbol").object(String.class);
                        Long unfreezeAmount = dc.wire().read("unfreezeAmount").object(Long.class);
                        Long unfreezeQty = dc.wire().read("unfreezeQty").object(Long.class);
                        EventType side = dc.wire().read("side").object(EventType.class);
                        Long timestamp = dc.wire().read("timestamp").object(Long.class);
                        eventData = new UnfreezeData(orderId, accountId, userId, symbol,
                            unfreezeAmount, unfreezeQty, side, timestamp);
                        break;
                    default:
                        logger.warn("Unknown event type in account_settlement CQ: {}", eventType);
                }

                if (eventData != null) {
                    events.add(new SettlementQueueEvent(eventType, eventData));
                }
            } catch (Exception e) {
                logger.error("Failed to read from account_settlement CQ: {}", e.getMessage());
                break;
            }
        }

        return events;
    }

    /**
     * 批量读取事件（用于恢复，不等待）
     */
    public List<SettlementQueueEvent> readBatchForReplay() {
        List<SettlementQueueEvent> events = new ArrayList<>();

        while (events.size() < batchSize) {
            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) {
                    break;
                }

                EventType eventType = dc.wire().read("eventType").object(EventType.class);
                Object eventData = null;

                switch (eventType) {
                    case SETTLEMENT:
                        eventData = dc.wire().read("event").object(SettlementEvent.class);
                        break;
                    case UNFREEZE:
                        Long orderId = dc.wire().read("orderId").object(Long.class);
                        Long accountId = dc.wire().read("accountId").object(Long.class);
                        Long userId = dc.wire().read("userId").object(Long.class);
                        String symbol = dc.wire().read("symbol").object(String.class);
                        Long unfreezeAmount = dc.wire().read("unfreezeAmount").object(Long.class);
                        Long unfreezeQty = dc.wire().read("unfreezeQty").object(Long.class);
                        EventType side = dc.wire().read("side").object(EventType.class);
                        Long timestamp = dc.wire().read("timestamp").object(Long.class);
                        eventData = new UnfreezeData(orderId, accountId, userId, symbol,
                            unfreezeAmount, unfreezeQty, side, timestamp);
                        break;
                    default:
                        logger.warn("Unknown event type in account_settlement CQ: {}", eventType);
                }

                if (eventData != null) {
                    events.add(new SettlementQueueEvent(eventType, eventData));
                }
            } catch (Exception e) {
                logger.error("Failed to read from account_settlement CQ: {}", e.getMessage());
                break;
            }
        }

        return events;
    }

    public long getLastIndex() {
        try {
            return appender.lastIndexAppended();
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    public long getCurrentIndex() {
        return tailer.index();
    }

    public void moveToIndex(long index) {
        tailer = queue.createTailer();
        if (!tailer.moveToIndex(index)) {
            logger.warn("Failed to move tailer to index: {}", index);
        }
    }

    public void close() {
        try {
            queue.close();
            logger.info("AccountSettlementQueue closed");
        } catch (Exception e) {
            logger.error("Failed to close AccountSettlementQueue: {}", e.getMessage());
        }
    }

    /**
     * Settlement 队列事件包装类
     */
    public static class SettlementQueueEvent {
        private final EventType eventType;
        private final Object eventData;

        public SettlementQueueEvent(EventType eventType, Object eventData) {
            this.eventType = eventType;
            this.eventData = eventData;
        }

        public EventType getEventType() { return eventType; }
        public Object getEventData() { return eventData; }

        public SettlementEvent asSettlementEvent() {
            return eventData instanceof SettlementEvent ? (SettlementEvent) eventData : null;
        }

        public UnfreezeData asUnfreezeData() {
            return eventData instanceof UnfreezeData ? (UnfreezeData) eventData : null;
        }
    }

    /**
     * UNFREEZE 事件数据
     */
    public static class UnfreezeData {
        private final Long orderId;
        private final Long accountId;
        private final Long userId;
        private final String symbol;
        private final Long unfreezeAmount;
        private final Long unfreezeQty;
        private final EventType side;
        private final Long timestamp;

        public UnfreezeData(Long orderId, Long accountId, Long userId, String symbol,
                            Long unfreezeAmount, Long unfreezeQty, EventType side, Long timestamp) {
            this.orderId = orderId;
            this.accountId = accountId;
            this.userId = userId;
            this.symbol = symbol;
            this.unfreezeAmount = unfreezeAmount;
            this.unfreezeQty = unfreezeQty;
            this.side = side;
            this.timestamp = timestamp;
        }

        public Long getOrderId() { return orderId; }
        public Long getAccountId() { return accountId; }
        public Long getUserId() { return userId; }
        public String getSymbol() { return symbol; }
        public Long getUnfreezeAmount() { return unfreezeAmount; }
        public Long getUnfreezeQty() { return unfreezeQty; }
        public EventType getSide() { return side; }
        public Long getTimestamp() { return timestamp; }
    }
}