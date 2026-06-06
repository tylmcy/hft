-- H2 数据库 Schema 初始化脚本
-- 根据 V12 技术方案 附录 C

-- 证券信息表
CREATE TABLE IF NOT EXISTS instruments (
    symbol VARCHAR(10) PRIMARY KEY,
    symbol_code INT,
    name VARCHAR(50),
    type VARCHAR(10),
    prev_close BIGINT,
    limit_up_ratio DOUBLE,
    limit_down_ratio DOUBLE,
    lot_size BIGINT,
    min_price_increment BIGINT,
    commission_rate INT,
    commission_per_lot BIGINT,
    stamp_duty_rate INT,
    transfer_fee_rate INT,
    update_time BIGINT
);

-- 交易日历表
CREATE TABLE IF NOT EXISTS trading_calendar (
    trade_date VARCHAR(8) PRIMARY KEY,
    is_trading_day BOOLEAN
);

-- 交易时段表
CREATE TABLE IF NOT EXISTS trading_sessions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    phase VARCHAR(30),
    start_time TIME,
    end_time TIME
);

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at BIGINT
);

-- 账户表
CREATE TABLE IF NOT EXISTS accounts (
    account_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    created_at BIGINT,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- 历史成交表
CREATE TABLE IF NOT EXISTS trades_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_id VARCHAR(50),
    order_id BIGINT,
    account_id BIGINT,
    user_id BIGINT,
    symbol VARCHAR(10),
    side VARCHAR(5),
    order_type VARCHAR(10),
    price BIGINT,
    order_price BIGINT,
    quantity BIGINT,
    amount BIGINT,
    commission BIGINT,
    frozen_amount_used BIGINT,
    trade_time BIGINT,
    trade_date VARCHAR(8)
);

-- 成交ID索引
CREATE INDEX IF NOT EXISTS idx_trade_id ON trades_history(trade_id);
CREATE INDEX IF NOT EXISTS idx_order_id ON trades_history(order_id);
CREATE INDEX IF NOT EXISTS idx_account_trade_date ON trades_history(account_id, trade_date);
CREATE INDEX IF NOT EXISTS idx_symbol_trade_date ON trades_history(symbol, trade_date);

-- 历史订单表
CREATE TABLE IF NOT EXISTS orders_history (
    order_id BIGINT PRIMARY KEY,
    account_id BIGINT,
    user_id BIGINT,
    symbol VARCHAR(10),
    side VARCHAR(5),
    order_type VARCHAR(10),
    price BIGINT,
    quantity BIGINT,
    filled_quantity BIGINT,
    status VARCHAR(20),
    commission BIGINT,
    frozen_amount BIGINT,
    cancel_reason VARCHAR(100),
    idempotency_key VARCHAR(50),
    create_time BIGINT,
    update_time BIGINT,
    trade_date VARCHAR(8)
);

CREATE INDEX IF NOT EXISTS idx_orders_account_date ON orders_history(account_id, trade_date);
CREATE INDEX IF NOT EXISTS idx_orders_user_date ON orders_history(user_id, trade_date);
CREATE INDEX IF NOT EXISTS idx_orders_symbol_date ON orders_history(symbol, trade_date, status);

-- 资金流水表
CREATE TABLE IF NOT EXISTS cashflow_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT,
    amount BIGINT,
    type VARCHAR(30),
    reason VARCHAR(100),
    trade_id VARCHAR(50),
    order_id BIGINT,
    created_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_cashflow_account_time ON cashflow_history(account_id, created_at);

-- 自选股表
CREATE TABLE IF NOT EXISTS watchlists (
    user_id BIGINT,
    symbol VARCHAR(10),
    created_at BIGINT,
    PRIMARY KEY (user_id, symbol),
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- K线历史表
CREATE TABLE IF NOT EXISTS kline_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10),
    kline_interval VARCHAR(5),
    open BIGINT,
    high BIGINT,
    low BIGINT,
    close BIGINT,
    volume BIGINT,
    open_time BIGINT,
    close_time BIGINT,
    trade_date VARCHAR(8)
);

CREATE INDEX IF NOT EXISTS idx_kline_symbol_interval ON kline_history(symbol, kline_interval, trade_date);

-- 入金历史表
CREATE TABLE IF NOT EXISTS deposit_history (
    deposit_id VARCHAR(50) PRIMARY KEY,
    account_id BIGINT,
    amount BIGINT,
    operator_id BIGINT,
    reason VARCHAR(200),
    is_correction BOOLEAN,
    original_deposit_id VARCHAR(50),
    status VARCHAR(20),
    created_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_deposit_account_time ON deposit_history(account_id, created_at);

-- 插入默认交易时段
MERGE INTO trading_sessions (phase, start_time, end_time) KEY(phase)
    VALUES ('PRE_MARKET', '09:15:00', '09:30:00');
MERGE INTO trading_sessions (phase, start_time, end_time) KEY(phase)
    VALUES ('CONTINUOUS_TRADING', '09:30:00', '11:30:00');
MERGE INTO trading_sessions (phase, start_time, end_time) KEY(phase)
    VALUES ('LUNCH_BREAK', '11:30:00', '13:00:00');
MERGE INTO trading_sessions (phase, start_time, end_time) KEY(phase)
    VALUES ('CONTINUOUS_TRADING', '13:00:00', '15:00:00');
MERGE INTO trading_sessions (phase, start_time, end_time) KEY(phase)
    VALUES ('CLOSED', '15:00:00', '23:59:59');

-- 插入示例证券数据
MERGE INTO instruments (symbol, symbol_code, name, type, prev_close, limit_up_ratio, limit_down_ratio, lot_size, min_price_increment, commission_rate, commission_per_lot, stamp_duty_rate, transfer_fee_rate, update_time) KEY(symbol)
    VALUES ('600000', 600000, '浦发银行', 'STOCK', 105000, 0.10, 0.10, 100, 1, 3, 0, 0, 0, CURRENT_TIMESTAMP() * 1000);
MERGE INTO instruments (symbol, symbol_code, name, type, prev_close, limit_up_ratio, limit_down_ratio, lot_size, min_price_increment, commission_rate, commission_per_lot, stamp_duty_rate, transfer_fee_rate, update_time) KEY(symbol)
    VALUES ('000001', 1, '平安银行', 'STOCK', 125000, 0.10, 0.10, 100, 1, 3, 0, 0, 0, CURRENT_TIMESTAMP() * 1000);
MERGE INTO instruments (symbol, symbol_code, name, type, prev_close, limit_up_ratio, limit_down_ratio, lot_size, min_price_increment, commission_rate, commission_per_lot, stamp_duty_rate, transfer_fee_rate, update_time) KEY(symbol)
    VALUES ('000002', 2, '万科A', 'STOCK', 180000, 0.10, 0.10, 100, 1, 3, 0, 0, 0, CURRENT_TIMESTAMP() * 1000);
