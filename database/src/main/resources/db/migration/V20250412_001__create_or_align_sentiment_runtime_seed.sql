CREATE TABLE IF NOT EXISTS sentiment_runtime_seed (
    scope VARCHAR(64) NOT NULL,
    for_trade_date DATE NOT NULL,
    source_trade_date DATE NOT NULL,
    signal_basis VARCHAR(16) NOT NULL,
    required_history INT NOT NULL,
    sample_size INT NOT NULL,
    sample_codes_json MEDIUMTEXT NOT NULL,
    symbol_states_json MEDIUMTEXT NOT NULL,
    bull_ratio_window_json MEDIUMTEXT NOT NULL,
    market_vol_window_json MEDIUMTEXT NOT NULL,
    accel_window_json MEDIUMTEXT NOT NULL,
    combined_history_json MEDIUMTEXT NOT NULL,
    total_days INT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (scope, for_trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE sentiment_runtime_seed
    MODIFY COLUMN sample_codes_json MEDIUMTEXT NOT NULL,
    MODIFY COLUMN symbol_states_json MEDIUMTEXT NOT NULL,
    MODIFY COLUMN bull_ratio_window_json MEDIUMTEXT NOT NULL,
    MODIFY COLUMN market_vol_window_json MEDIUMTEXT NOT NULL,
    MODIFY COLUMN accel_window_json MEDIUMTEXT NOT NULL,
    MODIFY COLUMN combined_history_json MEDIUMTEXT NOT NULL;
