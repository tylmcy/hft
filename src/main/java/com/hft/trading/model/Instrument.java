package com.hft.trading.model;

/**
 * 合约/证券信息数据模型
 *
 * 根据 V12 技术方案 3.1.4：
 * - 包含证券代码、名称、涨跌停比例、每手数量等
 * - 用于涨跌停校验和佣金计算
 */
public class Instrument {
    private final String symbol;
    private final int symbolCode;
    private final InstrumentType type;
    private final String name;
    private long prevClose; // 前收盘价（分）
    private final double limitUpRatio; // 涨停比例
    private final double limitDownRatio; // 跌停比例
    private final long lotSize; // 每手数量
    private final long minPriceIncrement; // 最小价格变动单位（分）
    private final int commissionRate; // 佣金率（万分比）
    private long commissionPerLot; // 每手佣金（分），期货专用
    private final int stampDutyRate; // 印花税率（万分比），预留
    private final int transferFeeRate; // 过户费率（万分比），预留
    private long updateTime;

    public Instrument(String symbol, int symbolCode, InstrumentType type, String name,
                      long prevClose, double limitUpRatio, double limitDownRatio,
                      long lotSize, long minPriceIncrement, int commissionRate) {
        this.symbol = symbol;
        this.symbolCode = symbolCode;
        this.type = type;
        this.name = name;
        this.prevClose = prevClose;
        this.limitUpRatio = limitUpRatio;
        this.limitDownRatio = limitDownRatio;
        this.lotSize = lotSize;
        this.minPriceIncrement = minPriceIncrement;
        this.commissionRate = commissionRate;
        this.commissionPerLot = 0L;
        this.stampDutyRate = 0;
        this.transferFeeRate = 0;
        this.updateTime = System.currentTimeMillis();
    }

    public String getSymbol() {
        return symbol;
    }

    public int getSymbolCode() {
        return symbolCode;
    }

    public InstrumentType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public long getPrevClose() {
        return prevClose;
    }

    public double getLimitUpRatio() {
        return limitUpRatio;
    }

    public double getLimitDownRatio() {
        return limitDownRatio;
    }

    public long getLotSize() {
        return lotSize;
    }

    public long getMinPriceIncrement() {
        return minPriceIncrement;
    }

    public int getCommissionRate() {
        return commissionRate;
    }

    public long getCommissionPerLot() {
        return commissionPerLot;
    }

    public int getStampDutyRate() {
        return stampDutyRate;
    }

    public int getTransferFeeRate() {
        return transferFeeRate;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    /**
     * 计算涨停价
     */
    public long getLimitUpPrice() {
        return (long) (prevClose * (1 + limitUpRatio));
    }

    /**
     * 计算跌停价
     */
    public long getLimitDownPrice() {
        return (long) (prevClose * (1 - limitDownRatio));
    }

    /**
     * 验证价格是否在涨跌停范围内
     */
    public boolean isPriceValid(long price) {
        return price >= getLimitDownPrice() && price <= getLimitUpPrice();
    }

    /**
     * 验证价格是否为最小变动单位的整数倍
     */
    public boolean isPriceIncrementValid(long price) {
        return price % minPriceIncrement == 0;
    }

    /**
     * 验证数量是否为每手数量的整数倍
     */
    public boolean isQuantityValid(long quantity) {
        return quantity > 0 && quantity % lotSize == 0;
    }

    /**
     * 计算佣金
     *
     * @param amount 成交金额（分）
     * @return 佣金（分），floor向下取整
     */
    public long calculateCommission(long amount) {
        if (type == InstrumentType.FUTURE) {
            return commissionPerLot;
        }
        // 股票：佣金 = 成交金额 × 佣金率 / 10000（floor）
        return amount * commissionRate / 10000;
    }

    /**
     * 设置前收盘价（SOD更新）
     */
    public void setPrevClose(long prevClose) {
        this.prevClose = prevClose;
        this.updateTime = System.currentTimeMillis();
    }

    /**
     * 设置每手佣金（期货专用）
     */
    public void setCommissionPerLot(long commissionPerLot) {
        this.commissionPerLot = commissionPerLot;
        this.updateTime = System.currentTimeMillis();
    }
}