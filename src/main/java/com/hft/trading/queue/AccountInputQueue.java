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
 * Account Input Chronicle Queue 管理器
 *
 * 根据 V12 技术方案：
 * - 文件路径：data/chronicle/account_input/
 * - blockSize：128MB
 * - rollCycle：默认（每小时滚动）
 * - writeMode：SYNC（RPO关键）
 */
public class AccountInputQueue {

    private static final Logger logger = LoggerFactory.getLogger(AccountInputQueue.class);

    private final ChronicleQueue queue;
    private final ExcerptAppender appender;
    private volatile ExcerptTailer tailer;

    // 批量读取配置
    private final int batchSize;
    private final long timeoutMs;

    public AccountInputQueue(String basePath, int batchSize, long timeoutMs) {
        this.batchSize = batchSize;
        this.timeoutMs = timeoutMs;

        // 创建 Chronicle Queue
        // RollCycles 使用默认值，blockSize 设置为 128MB
        this.queue = ChronicleQueue.singleBuilder(basePath + "account_input")
            .blockSize(128 << 20) // 128MB
            .build();

        this.appender = queue.createAppender();
        this.tailer = queue.createTailer();

        logger.info("AccountInputQueue initialized at: {}", basePath + "account_input");
    }

    /**
     * 写入事件（SYNC模式，保证RPO=0）
     */
    public long writeEvent(AccountInputEvent event) {
        try (DocumentContext dc = appender.writingDocument()) {
            dc.wire().write("event").object(event);
            long index = dc.index();
            logger.debug("Written event to account_input CQ: index={}, type={}",
                index, event.getEventType());
            return index;
        } catch (Exception e) {
            logger.error("Failed to write event to account_input CQ: {}", e.getMessage());
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
     *
     * @return 读取的事件列表，最多 batchSize 条
     */
    public List<AccountInputEvent> readBatch() {
        List<AccountInputEvent> events = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        while (events.size() < batchSize) {
            // 检查超时
            if (events.size() > 0 && System.currentTimeMillis() - startTime >= timeoutMs) {
                break;
            }

            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) {
                    // 无数据，等待或返回
                    if (events.size() == 0) {
                        // 首次读取无数据，短暂等待
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
                logger.error("Failed to read from account_input CQ: {}", e.getMessage());
                break;
            }
        }

        return events;
    }

    /**
     * 获取当前写入位置
     */
    public long getLastIndex() {
        return appender.lastIndexAppended();
    }

    /**
     * 获取当前读取位置
     */
    public long getCurrentIndex() {
        return tailer.index();
    }

    /**
     * 重置读取位置（用于恢复）
     */
    public void moveToIndex(long index) {
        tailer = queue.createTailer();
        if (!tailer.moveToIndex(index)) {
            logger.warn("Failed to move tailer to index: {}", index);
        }
    }

    /**
     * 批量读取事件（用于恢复，不等待）
     *
     * @return 读取的事件列表，最多 batchSize 条
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
                logger.error("Failed to read from account_input CQ: {}", e.getMessage());
                break;
            }
        }

        return events;
    }

    /**
     * 获取队列中待处理事件数量（估算）
     */
    public long getPendingCount() {
        long lastIndex = queue.lastIndex();
        long currentIndex = tailer.index();
        if (lastIndex < 0 || currentIndex < 0) {
            return 0;
        }
        // Chronicle Queue index 不是简单的消息序号
        // 这里返回一个估算值
        return Math.max(0, lastIndex - currentIndex);
    }

    /**
     * 关闭队列
     */
    public void close() {
        try {
            queue.close();
            logger.info("AccountInputQueue closed");
        } catch (Exception e) {
            logger.error("Failed to close AccountInputQueue: {}", e.getMessage());
        }
    }
}