# Iter 04 - Phase 4 C Group Limit-Board Factors

## Phase / Variable

- Phase: A4, C-group limit-board factor calculation.
- Variable changed: added C1/C2/C2p/C3/C4/C5/C6/C7 from `tushare_limit_list_d` plus `Y3_raw`. No Study metric formula, signal primitive, product contract, or evaluator logic changed.

## Business Flow

`tushare_limit_list_d + stock_daily_data + stock_info -> SentimentFactorDailyCalculator -> sentiment_factor_daily`.

The new explicit batch switch is `runResearch --rebuild-c-group true`. It writes only C fields plus `Y3_raw`, so it does not overwrite A/B/E fields or future D fields. Default Verify still avoids MySQL and runs the skeleton metric through `ResonanceEvaluator`.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command output: `0`.

C-group population prepares research input only; the real Study is still not producing resonance metrics.

## Gate Failure Top 3

No meaningful gate ranking yet. The card output is still the skeleton empty metric and fail-closed rejection is expected.

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: no conflict; this phase is upstream fact preparation.
- `research/02-research-execution-handbook.md`: aligns with T-day raw-label storage and direct `tushare_limit_list_d` fact usage.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: C-group formulas implemented from §3 and §4.3, including Kotlin-side `up_stat` parsing.

No architecture reference update was needed: the documented `sentiment_factor_daily` research-only table semantics did not change, and production strategy/Provider/subscription semantics are untouched.

## Next Hypothesis

Implement Phase 5 D-group time-series factors from the computed A1/A3/B3/B4 sequence, then add tests for D4 percentile rank, D6 signed streaks, and D7 symmetric momentum-decay signals.
