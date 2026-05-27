# Iter 05 - Phase 5 D Group Time-Series Factors

## Phase / Variable

- Phase: A5, D-group time-series factor calculation.
- Variable changed: added D1/D2/D3/D4/D5/D6/D7 from the computed A1/A3 sequence. No Study metric formula, signal primitive, product contract, or evaluator logic changed.

## Business Flow

`stock_daily_data + stock_info + tushare_limit_list_d -> SentimentFactorDailyCalculator -> sentiment_factor_daily`.

The new explicit batch switch is `runResearch --rebuild-d-group true`. It writes only D fields, so it does not overwrite A/B/C/E fields or raw Y labels. Default Verify still avoids MySQL and runs the skeleton metric through `ResonanceEvaluator`.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command output: `0`.

D-group population prepares research input only; the real Study is still not producing resonance metrics.

## Gate Failure Top 3

No meaningful gate ranking yet. The card output is still the skeleton empty metric and fail-closed rejection is expected.

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: no conflict; this phase is upstream fact preparation.
- `research/02-research-execution-handbook.md`: aligns with the Phase 5 D-group scope.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: D-group formulas implemented from §4.4, including EMA5/EMA10, 20-window percentile rank, signed streak, and symmetric momentum-decay signal.

No architecture reference update was needed: the documented `sentiment_factor_daily` research-only table semantics did not change, and production strategy/Provider/subscription semantics are untouched.

## Next Hypothesis

Implement Phase 6 label completion: compute `Y_composite` from rolling z-scores after Y1/Y2/Y3 are present, then add auxiliary target helpers for Study-side dynamic horizon labels.
