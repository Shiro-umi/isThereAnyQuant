ALTER TABLE daily_target_portfolio
    ADD COLUMN selection_score DOUBLE NOT NULL DEFAULT 0.0;

ALTER TABLE daily_target_portfolio
    MODIFY COLUMN rank_score DOUBLE NOT NULL DEFAULT 0.0;

UPDATE daily_target_portfolio
SET selection_score = rank_score
WHERE selection_score = 0.0
  AND rank_score IS NOT NULL;

ALTER TABLE daily_market_sentiment
    ADD COLUMN ratio_norm DOUBLE NOT NULL DEFAULT 0.0,
    ADD COLUMN vol_score DOUBLE NOT NULL DEFAULT 0.0,
    ADD COLUMN accel_score DOUBLE NOT NULL DEFAULT 0.0,
    ADD COLUMN absolute_floor DOUBLE NOT NULL DEFAULT 0.0,
    ADD COLUMN vol_cap DOUBLE NOT NULL DEFAULT 0.0;

ALTER TABLE daily_strategy_audit
    ADD COLUMN newly_selected_json TEXT NULL,
    ADD COLUMN dropped_json TEXT NULL,
    ADD COLUMN current_positions_json TEXT NULL;

ALTER TABLE daily_strategy_audit
    MODIFY COLUMN newly_selected VARCHAR(1024) NULL,
    MODIFY COLUMN dropped VARCHAR(1024) NULL,
    MODIFY COLUMN current_positions VARCHAR(1024) NULL;

UPDATE daily_strategy_audit
SET newly_selected_json = CASE
        WHEN newly_selected IS NULL OR newly_selected = '' THEN '[]'
        ELSE CONCAT('["', REPLACE(newly_selected, ',', '","'), '"]')
    END
WHERE newly_selected_json IS NULL;

UPDATE daily_strategy_audit
SET dropped_json = CASE
        WHEN dropped IS NULL OR dropped = '' THEN '[]'
        ELSE CONCAT('["', REPLACE(dropped, ',', '","'), '"]')
    END
WHERE dropped_json IS NULL;

UPDATE daily_strategy_audit
SET current_positions_json = CASE
        WHEN current_positions IS NULL OR current_positions = '' THEN '[]'
        ELSE CONCAT('["', REPLACE(current_positions, ',', '","'), '"]')
    END
WHERE current_positions_json IS NULL;
