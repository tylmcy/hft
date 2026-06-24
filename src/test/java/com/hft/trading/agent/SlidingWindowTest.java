package com.hft.trading.agent;

import com.hft.trading.util.SlidingWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SlidingWindow 滑动窗口百分位计算测试
 */
class SlidingWindowTest {

    @Test
    @DisplayName("空窗口应返回 0")
    void shouldReturnZeroForEmptyWindow() {
        SlidingWindow window = new SlidingWindow(50);
        assertEquals(0, window.getPercentile(50));
        assertEquals(0, window.getPercentile(99));
        assertTrue(window.isEmpty());
    }

    @Test
    @DisplayName("添加数据后应更新 size")
    void shouldUpdateSizeWhenAddingValues() {
        SlidingWindow window = new SlidingWindow(50);
        window.add(10);
        window.add(20);
        assertEquals(2, window.size());
        assertFalse(window.isEmpty());
    }

    @Test
    @DisplayName("P50 应返回中位数")
    void shouldCalculateP50Correctly() {
        SlidingWindow window = new SlidingWindow(50);
        window.add(10);
        window.add(20);
        window.add(30);
        assertEquals(20, window.getPercentile(50));
    }

    @Test
    @DisplayName("P99 应返回接近最大值的值")
    void shouldCalculateP99Correctly() {
        SlidingWindow window = new SlidingWindow(50);
        for (int i = 1; i <= 100; i++) {
            window.add(i * 10);
        }
        long p99 = window.getPercentile(99);
        assertTrue(p99 >= 990, "P99 should be near max, got " + p99);
    }

    @Test
    @DisplayName("固定值窗口的 P99 应等于该值")
    void shouldHandleConstantValues() {
        SlidingWindow window = new SlidingWindow(50);
        for (int i = 0; i < 20; i++) {
            window.add(42);
        }
        assertEquals(42, window.getPercentile(50));
        assertEquals(42, window.getPercentile(99));
    }

    @Test
    @DisplayName("环形缓冲应覆盖旧值")
    void shouldOverwriteOldValues() {
        SlidingWindow window = new SlidingWindow(5);
        for (int i = 0; i < 10; i++) {
            window.add(i * 10);
        }
        assertEquals(5, window.size());
        assertEquals(70, window.getPercentile(50));
        assertEquals(90, window.getPercentile(99));
    }

    @Test
    @DisplayName("百分位边界值 0 和 100 应返回最小/最大值")
    void shouldHandleBoundaryPercentiles() {
        SlidingWindow window = new SlidingWindow(50);
        window.add(5);
        window.add(15);
        window.add(25);
        assertEquals(5, window.getPercentile(0));
        assertEquals(25, window.getPercentile(100));
    }

    @Test
    @DisplayName("并发添加应线程安全")
    void shouldBeThreadSafe() throws InterruptedException {
        SlidingWindow window = new SlidingWindow(100);
        Thread t1 = new Thread(() -> { for (int i = 0; i < 50; i++) window.add(i); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 50; i++) window.add(i + 50); });
        t1.start(); t2.start();
        t1.join(); t2.join();
        assertEquals(100, window.size());
        assertDoesNotThrow(() -> window.getPercentile(50));
        assertDoesNotThrow(() -> window.getPercentile(99));
    }

    @Test
    @DisplayName("单个值应返回到所有百分位查询")
    void shouldReturnSingleValueForAllPercentiles() {
        SlidingWindow window = new SlidingWindow(10);
        window.add(77);
        assertEquals(77, window.getPercentile(0));
        assertEquals(77, window.getPercentile(50));
        assertEquals(77, window.getPercentile(99));
        assertEquals(77, window.getPercentile(100));
    }
}
