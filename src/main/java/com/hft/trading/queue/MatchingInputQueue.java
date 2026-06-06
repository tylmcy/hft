package com.hft.trading.queue;

import com.hft.trading.event.AccountInputEvent;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.DocumentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Matching Input Chronicle Queue 管理器
 *
 * 根据 V12 技术方案：
 * - 文件路径：data/chronicle/matching_input/
 * - blockSize：64MB
 * - writeMode：SYNC（RPO关键）
 */
public class MatchingInputQueue {

    private static final Logger logger = LoggerFactory.getLogger(MatchingInputQueue.class);

    private final ChronicleQueue queue;
    private final ExcerptAppender appender;
    private volatile ExcerptTailer tailer;

    private final int batchSize;
    private final long timeoutMs;

    public MatchingInputQueue(String basePath, int batchSize, long timeoutMs) {
        this.batchSize = batchSize;
        this.timeoutMs = timeoutMs;

        this.queue = ChronicleQueue.singleBuilder(basePath + "matching_input")
            .blockSize(64 << 20) // 64MB
            .build();

        this.appender = queue.createAppender();
        this.tailer = queue.createTailer();

        logger.info("MatchingInputQueue initialized at: {}", basePath + "matching_input");
    }

    /**
     * 写入事件
     */
    public long writeEvent(AccountInputEvent event) {
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write("event").object(event);
            long index = dc.index();
            logger.debug("Written event to matching_input CQ: index={}, type={}",
                index, event.getEventType());
            return index;
        } catch (Exception e) {
            logger.error("Failed to write event to matching_input CQ: {}", e.getMessage());
            throw new RuntimeException("CQ write failed", e);
        }
    }

    /**
     * 批量写入事件
     */
    public List<Long> writeBatch(List<AccountInputEvent> events) {
        List<Long> indices = new ArrayList<>();
        for (AccountInputEvent event : events) {
            indices.add(writeEvent(event));
        }
        return indices;
    }

    /**
     * 批量读取事件
     */
    public List<AccountInputEvent> readBatch() {
        List<AccountInputEvent> events = new ArrayList<>();
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

                AccountInputEvent event = dc.wire().read("event").object(AccountInputEvent.class);
                if (event != null) {
                    events.add(event);
                }
            } catch (Exception e) {
                logger.error("Failed to read from matching_input CQ: {}", e.getMessage());
                break;
            }
        }

        return events;
    }

    /**
     * 批量读取事件（用于恢复，不等待）
     */
    public List<AccountInputEvent> readBatchForReplay() {
        List<AccountInputEvent> events = new ArrayList<>();

        while (events.size() < batchSize) {
            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) {
                    break;
                }

                AccountInputEvent event = dc.wire().read("event").object(AccountInputEvent.class);
                if (event != null) {
                    events.add(event);
                }
            } catch (Exception e) {
                logger.error("Failed to read from matching_input CQ: {}", e.getMessage());
                break;
            }
        }

        return events;
    }

    public long getLastIndex() {
        return appender.lastIndexAppended();
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
            logger.info("MatchingInputQueue closed");
        } catch (Exception e) {
            logger.error("Failed to close MatchingInputQueue: {}", e.getMessage());
        }
    }
}
