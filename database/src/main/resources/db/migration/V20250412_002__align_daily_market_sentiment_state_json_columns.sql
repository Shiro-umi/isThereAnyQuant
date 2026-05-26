ALTER TABLE daily_market_sentiment_state
    MODIFY COLUMN sample_codes_json MEDIUMTEXT NOT NULL,
    MODIFY COLUMN symbol_states_json MEDIUMTEXT NOT NULL,
    MODIFY COLUMN bull_ratio_history_json MEDIUMTEXT NOT NULL,
    MODIFY COLUMN market_vol_history_json MEDIUMTEXT NOT NULL,
    MODIFY COLUMN accel_history_json MEDIUMTEXT NOT NULL,
    MODIFY COLUMN combined_history_json MEDIUMTEXT NOT NULL;
