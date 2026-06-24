package com.hft.trading.config;

import com.hft.trading.queue.QueueManager;
import com.hft.trading.service.AlertService;
import com.hft.trading.service.FutureManager;
import com.hft.trading.service.IdempotencyService;
import com.hft.trading.snapshot.SnapshotWorker;
import com.hft.trading.store.InMemoryAccountStore;
import com.hft.trading.store.InMemoryInstrumentStore;
import com.hft.trading.store.InMemoryOrderStore;
import com.hft.trading.store.InMemoryPositionStore;
import com.hft.trading.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 配置类
 */
@Configuration
public class TradingConfig {

    @Value("${trading.chronicle.base-path:./data/chronicle/}")
    private String chronicleBasePath;

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator(1);
    }

    @Bean
    public AlertService alertService() {
        return new AlertService();
    }

    @Bean
    public InMemoryAccountStore accountStore(AlertService alertService) {
        return new InMemoryAccountStore(alertService);
    }

    @Bean
    public InMemoryPositionStore positionStore(AlertService alertService) {
        return new InMemoryPositionStore(alertService);
    }

    @Bean
    public InMemoryOrderStore orderStore() {
        return new InMemoryOrderStore();
    }

    @Bean
    public InMemoryInstrumentStore instrumentStore() {
        return new InMemoryInstrumentStore();
    }

    @Bean
    public IdempotencyService idempotencyService() {
        return new IdempotencyService();
    }

    @Bean
    public FutureManager futureManager() {
        return new FutureManager();
    }

    @Bean
    public QueueManager queueManager() {
        return new QueueManager(chronicleBasePath);
    }

    @Bean
    public SnapshotWorker snapshotWorker(QueueManager queueManager, AlertService alertService) {
        return new SnapshotWorker(
            queueManager.getAccountInputQueue(),
            queueManager.getMatchingInputQueue(),
            "./data/snapshots/",
            alertService
        );
    }
}
