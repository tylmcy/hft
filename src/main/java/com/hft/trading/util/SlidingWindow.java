package com.hft.trading.util;

import java.util.Arrays;

/**
 * 滑动窗口百分位计算
 *
 * 固定容量环形缓冲，维护一个有序副本用于百分位查询。
 * add() O(1)，getPercentile() 每次 O(n log n)，
 * 但 n=50 时可忽略。
 */
public class SlidingWindow {

    private final long[] buffer;
    private final long[] sorted;
    private int index = 0;
    private int size = 0;
    private final int capacity;

    public SlidingWindow(int capacity) {
        this.capacity = capacity;
        this.buffer = new long[capacity];
        this.sorted = new long[capacity];
    }

    public synchronized void add(long value) {
        buffer[index] = value;
        index = (index + 1) % capacity;
        if (size < capacity) size++;
    }

    public synchronized long getPercentile(int percentile) {
        if (size == 0) return 0;
        if (percentile < 0) percentile = 0;
        if (percentile > 100) percentile = 100;

        // 复制到 sorted 数组并排序
        System.arraycopy(buffer, 0, sorted, 0, size);
        Arrays.sort(sorted, 0, size);

        // 计算位置并返回
        int rank = (int) Math.ceil((percentile / 100.0) * size) - 1;
        if (rank < 0) rank = 0;
        if (rank >= size) rank = size - 1;
        return sorted[rank];
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }
}
