package com.hft.trading.util;

/**
 * Snowflake ID 生成器（雪花算法）
 *
 * 64位ID结构：
 * - 1位：符号位（始终为0）
 * - 41位：时间戳（毫秒级）
 * - 10位：机器ID（workerId + datacenterId）
 * - 12位：序列号
 */
public class SnowflakeIdGenerator {
    private final long workerId;
    private final long datacenterId = 0L; // 单机部署，固定为0

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    // 时间戳起始点（2024-01-01）
    private final long twepoch = 1704067200000L;

    // 位数分配
    private final long workerIdBits = 10L;
    private final long sequenceBits = 12L;

    // 最大值
    private final long maxWorkerId = ~(-1L << workerIdBits);
    private final long maxSequence = ~(-1L << sequenceBits);

    // 位移
    private final long workerIdShift = sequenceBits;
    private final long timestampShift = sequenceBits + workerIdBits;

    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > maxWorkerId) {
            throw new IllegalArgumentException(
                "workerId must be between 0 and " + maxWorkerId);
        }
        this.workerId = workerId;
    }

    /**
     * 生成下一个ID（线程安全）
     */
    public synchronized long nextId() {
        long timestamp = timeGen();

        // 时钟回拨检测
        if (timestamp < lastTimestamp) {
            throw new RuntimeException(
                "Clock moved backwards. Refusing to generate id");
        }

        // 同一毫秒内
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & maxSequence;
            // 序列号溢出，等待下一毫秒
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 组装ID
        return ((timestamp - twepoch) << timestampShift)
            | (datacenterId << workerIdShift)
            | (workerId << workerIdShift)
            | sequence;
    }

    /**
     * 阻塞到下一毫秒
     */
    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    /**
     * 获取当前时间戳
     */
    private long timeGen() {
        return System.currentTimeMillis();
    }
}