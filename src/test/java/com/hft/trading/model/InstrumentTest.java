package com.hft.trading.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Instrument（合约/证券信息）数据模型测试
 *
 * 根据 V12 技术方案 3.1.4：
 * - 包含证券代码、名称、涨跌停比例、每手数量等
 * - 用于涨跌停校验和佣金计算
 */
class InstrumentTest {

    @Test
    @DisplayName("创建证券信息时应该正确初始化所有字段")
    void shouldInitializeAllFieldsWhenCreatingInstrument() {
        String symbol = "600000";
        int symbolCode = 600000;
        InstrumentType type = InstrumentType.STOCK;
        String name = "浦发银行";
        long prevClose = 105000L; // 10.50元
        double limitUpRatio = 0.10; // 10%涨停
        double limitDownRatio = 0.10; // 10%跌停
        long lotSize = 100L; // 每手100股
        long minPriceIncrement = 1L; // 最小变动单位1分
        int commissionRate = 3; // 万分之三

        Instrument instrument = new Instrument(symbol, symbolCode, type, name, prevClose,
            limitUpRatio, limitDownRatio, lotSize, minPriceIncrement, commissionRate);

        assertEquals(symbol, instrument.getSymbol());
        assertEquals(symbolCode, instrument.getSymbolCode());
        assertEquals(type, instrument.getType());
        assertEquals(name, instrument.getName());
        assertEquals(prevClose, instrument.getPrevClose());
        assertEquals(limitUpRatio, instrument.getLimitUpRatio());
        assertEquals(limitDownRatio, instrument.getLimitDownRatio());
        assertEquals(lotSize, instrument.getLotSize());
        assertEquals(minPriceIncrement, instrument.getMinPriceIncrement());
        assertEquals(commissionRate, instrument.getCommissionRate());
        assertEquals(0L, instrument.getCommissionPerLot()); // 股票为0
    }

    @Test
    @DisplayName("应该能够计算涨停价")
    void shouldCalculateLimitUpPrice() {
        Instrument instrument = createStockInstrument("600000", 100000L, 0.10);

        long limitUpPrice = instrument.getLimitUpPrice();

        // 涨停价 = 前收盘 × (1 + 涨停比例) = 100000 × 1.10 = 110000
        assertEquals(110000L, limitUpPrice);
    }

    @Test
    @DisplayName("应该能够计算跌停价")
    void shouldCalculateLimitDownPrice() {
        Instrument instrument = createStockInstrument("600000", 100000L, 0.10);

        long limitDownPrice = instrument.getLimitDownPrice();

        // 跌停价 = 前收盘 × (1 - 跌停比例) = 100000 × 0.90 = 90000
        assertEquals(90000L, limitDownPrice);
    }

    @Test
    @DisplayName("应该能够验证价格是否在涨跌停范围内")
    void shouldValidatePriceWithinRange() {
        Instrument instrument = createStockInstrument("600000", 100000L, 0.10);

        assertTrue(instrument.isPriceValid(100000L)); // 正常价格
        assertTrue(instrument.isPriceValid(110000L)); // 涨停价
        assertTrue(instrument.isPriceValid(90000L)); // 跌停价
        assertFalse(instrument.isPriceValid(110001L)); // 超过涨停
        assertFalse(instrument.isPriceValid(89999L)); // 低于跌停
    }

    @Test
    @DisplayName("应该能够验证价格是否为最小变动单位的整数倍")
    void shouldValidatePriceIncrement() {
        Instrument instrument = createStockInstrument("600000", 100000L, 0.10);

        assertTrue(instrument.isPriceIncrementValid(100000L)); // 整数倍
        assertTrue(instrument.isPriceIncrementValid(100001L)); // 整数倍（最小单位1分）
        // 对于最小变动单位为1分的情况，任何整数分都是有效的
        // 如果最小变动单位为5分，则可以测试 100005L 有效，100003L 无效
    }

    @Test
    @DisplayName("应该能够验证数量是否为每手数量的整数倍")
    void shouldValidateQuantityLotSize() {
        Instrument instrument = createStockInstrument("600000", 100000L, 0.10);

        assertTrue(instrument.isQuantityValid(100L)); // 1手
        assertTrue(instrument.isQuantityValid(200L)); // 2手
        assertFalse(instrument.isQuantityValid(150L)); // 1.5手，不是整数倍
        assertFalse(instrument.isQuantityValid(0L)); // 0无效
        assertFalse(instrument.isQuantityValid(-100L)); // 负数无效
    }

    @Test
    @DisplayName("应该能够计算预估佣金")
    void shouldCalculateEstimatedCommission() {
        Instrument instrument = createStockInstrument("600000", 100000L, 0.10);

        // 成交金额 = 10.50元 × 100股 = 1050元 = 105000分
        // 佣金 = 105000 × 3 / 10000 = 31.5 ≈ 31分（floor）
        long commission = instrument.calculateCommission(105000L);

        assertEquals(31L, commission);
    }

    @Test
    @DisplayName("科创板涨停比例应该为20%")
    void shouldHave20PercentLimitForKechuangBoard() {
        // 科创板股票以688开头
        Instrument instrument = new Instrument("688001", 688001, InstrumentType.STOCK,
            "科创板股票", 100000L, 0.20, 0.20, 100L, 1L, 3);

        assertEquals(0.20, instrument.getLimitUpRatio());
        assertEquals(0.20, instrument.getLimitDownRatio());
        assertEquals(120000L, instrument.getLimitUpPrice());
        assertEquals(80000L, instrument.getLimitDownPrice());
    }

    @Test
    @DisplayName("创业板涨停比例应该为20%")
    void shouldHave20PercentLimitForChiNext() {
        // 创业板股票以300开头
        Instrument instrument = new Instrument("300001", 300001, InstrumentType.STOCK,
            "创业板股票", 100000L, 0.20, 0.20, 100L, 1L, 3);

        assertEquals(0.20, instrument.getLimitUpRatio());
        assertEquals(0.20, instrument.getLimitDownRatio());
    }

    @Test
    @DisplayName("应该能够更新前收盘价")
    void shouldUpdatePrevClose() {
        Instrument instrument = createStockInstrument("600000", 100000L, 0.10);

        instrument.setPrevClose(110000L);

        assertEquals(110000L, instrument.getPrevClose());
        assertEquals(121000L, instrument.getLimitUpPrice()); // 新涨停价
    }

    @Test
    @DisplayName("期货应该有每手佣金")
    void shouldHaveCommissionPerLotForFuture() {
        Instrument instrument = new Instrument("IF2401", 2401, InstrumentType.FUTURE,
            "沪深300股指期货", 400000L, 0.10, 0.10, 1L, 200L, 0);

        assertEquals(InstrumentType.FUTURE, instrument.getType());
        // 期货每手佣金，单位：分
        instrument.setCommissionPerLot(3000L); // 30元/手
        assertEquals(3000L, instrument.getCommissionPerLot());
    }

    private Instrument createStockInstrument(String symbol, long prevClose, double limitRatio) {
        int symbolCode = Integer.parseInt(symbol);
        return new Instrument(symbol, symbolCode, InstrumentType.STOCK, "测试股票",
            prevClose, limitRatio, limitRatio, 100L, 1L, 3);
    }
}