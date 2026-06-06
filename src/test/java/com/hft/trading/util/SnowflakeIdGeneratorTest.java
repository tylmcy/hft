package com.hft.trading.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnowflakeIdGenerator 测试
 *
 * 雪花算法ID生成器测试
 */
class SnowflakeIdGeneratorTest {

    @Test
    @DisplayName("应该生成唯一的ID")
    void shouldGenerateUniqueId() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);

        long id1 = generator.nextId();
        long id2 = generator.nextId();

        assertNotEquals(id1, id2);
        assertTrue(id1 > 0);
        assertTrue(id2 > 0);
    }

    @Test
    @DisplayName("生成的ID应该是递增的")
    void shouldGenerateIncrementalIds() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);

        long prevId = 0;
        for (int i = 0; i < 1000; i++) {
            long id = generator.nextId();
            assertTrue(id > prevId);
            prevId = id;
        }
    }

    @Test
    @DisplayName("不同 workerId 应该生成不同的ID")
    void shouldGenerateDifferentIdsForDifferentWorkers() {
        SnowflakeIdGenerator generator1 = new SnowflakeIdGenerator(1);
        SnowflakeIdGenerator generator2 = new SnowflakeIdGenerator(2);

        long id1 = generator1.nextId();
        long id2 = generator2.nextId();

        assertNotEquals(id1, id2);
    }

    @Test
    @DisplayName("生成的ID应该是正数")
    void shouldGeneratePositiveId() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);

        for (int i = 0; i < 100; i++) {
            long id = generator.nextId();
            assertTrue(id > 0);
        }
    }

    @Test
    @DisplayName("高并发下应该生成唯一ID")
    void shouldGenerateUniqueIdUnderConcurrency() throws InterruptedException {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);
        java.util.Set<Long> ids = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    long id = generator.nextId();
                    assertTrue(ids.add(id), "Duplicate ID: " + id);
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(10000, ids.size());
    }

    @Test
    @DisplayName("workerId 应该在有效范围内")
    void shouldValidateWorkerId() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
        assertDoesNotThrow(() -> new SnowflakeIdGenerator(0));
        assertDoesNotThrow(() -> new SnowflakeIdGenerator(1023));
    }
}
