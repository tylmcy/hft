package com.hft.trading.store;

import com.hft.trading.model.Instrument;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Instrument 内存存储
 */
public class InMemoryInstrumentStore {

    private final ConcurrentHashMap<String, Instrument> instruments = new ConcurrentHashMap<>();

    /**
     * 获取 Instrument
     */
    public Instrument getInstrument(String symbol) {
        return instruments.get(symbol);
    }

    /**
     * 添加 Instrument
     */
    public void putInstrument(Instrument instrument) {
        instruments.put(instrument.getSymbol(), instrument);
    }

    /**
     * 获取所有 Instrument
     */
    public List<Instrument> getAllInstruments() {
        return new ArrayList<>(instruments.values());
    }

    /**
     * 获取佣金率（万分比）
     */
    public int getCommissionRate(String symbol) {
        Instrument instrument = instruments.get(symbol);
        return instrument != null ? instrument.getCommissionRate() : 0;
    }

    /**
     * 获取所有 Instrument 数量
     */
    public int size() {
        return instruments.size();
    }
}