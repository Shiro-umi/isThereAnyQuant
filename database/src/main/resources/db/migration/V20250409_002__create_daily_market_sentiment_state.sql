CREATE TABLE IF NOT EXISTS daily_market_sentiment_state (
    trade_date DATE NOT NULL,
    signal_basis VARCHAR(16) NOT NULL,
    sample_codes_json TEXT NOT NULL,
    symbol_states_json LONGTEXT NOT NULL,
    bull_ratio_history_json TEXT NOT NULL,
    market_vol_history_json TEXT NOT NULL,
    accel_history_json TEXT NOT NULL,
    combined_history_json TEXT NOT NULL,
    total_days INT NOT NULL,
    PRIMARY KEY (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
