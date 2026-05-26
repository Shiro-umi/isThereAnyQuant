SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'stock_daily_data'
      AND index_name = 'idx_stock_daily_code_date_desc'
);
SET @ddl = IF(
    @idx_exists = 0,
    'ALTER TABLE stock_daily_data ADD INDEX idx_stock_daily_code_date_desc (ts_code, trade_date DESC)',
    'SELECT ''idx_stock_daily_code_date_desc already exists'''
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'stock_daily_data'
      AND index_name = 'idx_stock_daily_date_code'
);
SET @ddl = IF(
    @idx_exists = 0,
    'ALTER TABLE stock_daily_data ADD INDEX idx_stock_daily_date_code (trade_date, ts_code)',
    'SELECT ''idx_stock_daily_date_code already exists'''
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'daily_target_portfolio'
      AND index_name = 'idx_daily_target_portfolio_target_selected'
);
SET @ddl = IF(
    @idx_exists = 0,
    'ALTER TABLE daily_target_portfolio ADD INDEX idx_daily_target_portfolio_target_selected (target_date, selected, selection_score, ts_code)',
    'SELECT ''idx_daily_target_portfolio_target_selected already exists'''
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
