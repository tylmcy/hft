package com.hft.trading.queue;

import com.hft.trading.event.CancelSuccessEvent;
import com.hft.trading.event.EventType;
import com.hft.trading.event.TradeEvent;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.DocumentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Matching Output Chronicle Queue 管理器
 *
 * 根据 V12 技术方案：
 * - 文件路径：data/chronicle/matching_output/
 * - blockSize：64MB
 * - writeMode：SYNC（RPO关键）
 * - 事件类型：ORDER_UPDATE / TRADE / CANCEL_SUCCESS / CANCEL_REJECT
 */
public class MatchingOutputQueue {

    private static final Logger logger = LoggerFactory.getLogger(MatchingOutputQueue.class);

    private final ChronicleQueue queue;
    private final ExcerptAppender appender;
    private volatile ExcerptTailer tailer;

    private final int batchSize;
    private final long timeoutMs;

    public MatchingOutputQueue(String basePath, int batchSize, long timeoutMs) {
        this.batchSize = batchSize;
        this.timeoutMs = timeoutMs;

        this.queue = ChronicleQueue.singleBuilder(basePath + "matching_output")
            .blockSize(64 << 20) // 64MB
            .build();

        this.appender = queue.createAppender();
        this.tailer = queue.createTailer();

        logger.info("MatchingOutputQueue initialized at: {}", basePath + "matching_output");
    }

    /**
     * 写入 TRADE 事件
     */
    public long writeTradeEvent(TradeEvent event) {
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write("eventType").object(EventType.TRADE);
            dc.wire().write("event").object(event);
            long index = dc.index();
            logger.debug("Written TRADE event to matching_output CQ: index={}, tradeId={}",
                index, event.getTradeId());
            return index;
        } catch (Exception e) {
            logger.error("Failed to write TRADE event to matching_output CQ: {}", e.getMessage());
            throw new RuntimeException("CQ write failed", e);
        }
    }

    /**
     * 写入 CANCEL_SUCCESS 事件
     */
    public long writeCancelSuccessEvent(CancelSuccessEvent event) {
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write("eventType").object(EventType.CANCEL_SUCCESS);
            dc.wire().write("event").object(event);
            long index = dc.index();
            logger.debug("Written CANCEL_SUCCESS event to matching_output CQ: index={}, orderId={}",
                index, event.getOrderId());
            return index;
        } catch (Exception e) {
            logger.error("Failed to write CANCEL_SUCCESS event to matching_output CQ: {}", e.getMessage());
            throw new RuntimeException("CQ write failed", e);
        }
    }

    /**
     * 写入 ORDER_UPDATE 事件
     */
    public long writeOrderUpdateEvent(long orderId, String symbol, String status) {
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write("eventType").object(EventType.ORDER_UPDATE);
            dc.wire().write("orderId").object(orderId);
            dc.wire().write("symbol").object(symbol);
            dc.wire().write("status").object(status);
            long index = dc.index();
            logger.debug("Written ORDER_UPDATE event to matching_output CQ: index={}, orderId={}, status={}",
                index, orderId, status);
            return index;
        } catch (Exception e) {
            logger.error("Failed to write ORDER_UPDATE event to matching_output CQ: {}", e.getMessage());
            throw new RuntimeException("CQ write failed", e);
        }
    }

    /**
     * 批量读取事件（返回 Object 类型，由调用方根据 eventType 判断）
     */
    public List<MatchingOutputEvent> readBatch() {
        List<MatchingOutputEvent> events = new ArrayList<>();
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
                    case TRADE:
                        eventData = dc.wire().read("event").object(TradeEvent.class);
                        break;
                    case CANCEL_SUCCESS:
                        eventData = dc.wire().read("event").object(CancelSuccessEvent.class);
                        break;
                    case ORDER_UPDATE:
                        Long orderId = dc.wire().read("orderId").object(Long.class);
                        String symbol = dc.wire().read("symbol").object(String.class);
                        EventType status = dc.wire().read("status").object(EventType.class);
                        eventData = new OrderUpdateData(orderId, symbol, status);
                        break;
                    default:
                        logger.warn("Unknown event type in matching_output CQ: {}", eventType);
                }

                if (eventData != null) {
                    events.add(new MatchingOutputEvent(eventType, eventData));
                }
            } catch (Exception e) {
                logger.error("Failed to read from matching_output CQ: {}", e.getMessage());
                break;
            }
        }

        return events;
    }

    /**
     * 批量读取事件（用于恢复，不等待）
     */
    public List<MatchingOutputEvent> readBatchForReplay() {
        List<MatchingOutputEvent> events = new ArrayList<>();

        while (events.size() < batchSize) {
            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) {
                    break;
                }

                EventType eventType = dc.wire().read("eventType").object(EventType.class);
                Object eventData = null;

                switch (eventType) {
                    case TRADE:
                        eventData = dc.wire().read("event").object(TradeEvent.class);
                        break;
                    case CANCEL_SUCCESS:
                        eventData = dc.wire().read("event").object(CancelSuccessEvent.class);
                        break;
                    case ORDER_UPDATE:
                        Long orderId = dc.wire().read("orderId").object(Long.class);
                        String symbol = dc.wire().read("symbol").object(String.class);
                        EventType status = dc.wire().read("status").object(EventType.class);
                        eventData = new OrderUpdateData(orderId, symbol, status);
                        break;
                    default:
                        logger.warn("Unknown event type in matching_output CQ: {}", eventType);
                }

                if (eventData != null) {
                    events.add(new MatchingOutputEvent(eventType, eventData));
                }
            } catch (Exception e) {
                logger.error("Failed to read from matching_output CQ: {}", e.getMessage());
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
            logger.info("MatchingOutputQueue closed");
        } catch (Exception e) {
            logger.error("Failed to close MatchingOutputQueue: {}", e.getMessage());
        }
    }

    /**
     * 匹配输出事件包装类
     */
    public static class MatchingOutputEvent {
        private final EventType eventType;
        private final Object eventData;

        public MatchingOutputEvent(EventType eventType, Object eventData) {
            this.eventType = eventType;
            this.eventData = eventData;
        }

        public EventType getEventType() { return eventType; }
        public Object getEventData() { return eventData; }

        public TradeEvent asTradeEvent() {
            return eventData instanceof TradeEvent ? (TradeEvent) eventData : null;
        }

        public CancelSuccessEvent asCancelSuccessEvent() {
            return eventData instanceof CancelSuccessEvent ? (CancelSuccessEvent) eventData : null;
        }

        public OrderUpdateData asOrderUpdate() {
            return eventData instanceof OrderUpdateData ? (OrderUpdateData) eventData : null;
        }
    }

    /**
     * ORDER_UPDATE 事件数据
     */
    public static class OrderUpdateData {
        private final Long orderId;
        private final String symbol;
        private final EventType status;

        public OrderUpdateData(Long orderId, String symbol, EventType status) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.status = status;
        }

        public Long getOrderId() { return orderId; }
        public String getSymbol() { return symbol; }
        public EventType getStatus() { return status; }
    }
}
