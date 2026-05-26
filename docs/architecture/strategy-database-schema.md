# Strategy Database Schema

The strategy pipeline is intentionally split into three layers.

## Input Facts

- `stock_basic`: authoritative listed-symbol metadata. The main-board universe is derived from this table at runtime.
- Daily candle, adjustment-factor, and daily-basic tables: authoritative market facts. Strategy code must convert these facts through `PreparedBarFactory` before factor calculation.

## Runtime State

- `daily_factor_rolling_state`: per-symbol rolling state at `trade_date`. Both post-market catch-up and intraday projection consume the previous trading day's state and one current `PreparedBar`.
- `daily_market_sentiment_state`: market-sentiment rolling state at `trade_date`.
- `sentiment_runtime_seed`: minimal seed produced after post-market calculation for the next trading day's intraday sentiment projection.

## Confirmed Results

- `daily_stock_factor`: confirmed daily factor snapshots. The `rank_score` column is the raw per-symbol factor score, not the final portfolio selection score.
- `daily_market_sentiment`: confirmed daily sentiment snapshots, including the derived guard inputs (`ratio_norm`, `vol_score`, `accel_score`, `absolute_floor`, `vol_cap`) used by clients and audit views.
- `daily_target_portfolio`: confirmed post-market target portfolio. `selection_score` is the final cross-sectional portfolio score after sentiment-weighted ranking.
- `daily_strategy_audit`: daily audit summary for UI and operational checks. Position lists are stored as JSON arrays and are not the source of truth for confirmed selections.

Intraday portfolio output is a runtime projection only. It is published through in-memory holders and WebSocket payloads, and must not be persisted as a long-lived strategy result.
