# Iter 02 - Phase 2 A Group Factor Population

## Phase / Variable

- Phase: A2, A-group factor calculation.
- Variable changed: added the offline research input path for A1-A12/A9a/A11a. No Study metric formula, signal primitive, product contract, or evaluator logic changed.

## Business Flow

`stock_daily_data + stock_info + tushare_limit_list_d -> SentimentFactorDailyCalculator -> sentiment_factor_daily`.

The batch remains explicit: `runResearch --rebuild-a-group true`. The default Verify path does not touch MySQL, and still runs FakeStudy through `ResonanceEvaluator` to keep the Phase 1 metric baseline stable.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command output: `0`.

A-group population prepares Study input only; it does not produce `ResonanceMetric` yet, so no card can become qualified in this phase.

## Gate Failure Top 3

No meaningful gate ranking yet. The card output is still the skeleton empty metric and fail-closed rejection is expected.

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: no conflict; this phase is upstream fact preparation.
- `research/02-research-execution-handbook.md`: aligns with Phase 2 and T-day raw-label rule.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: A-group formulas implemented from §4.1, including pct_norm board caps, A5/A6 limit-list inputs, and T-1 turnover buckets.

No architecture reference update was needed beyond the existing Phase 1 database note: this iteration filled calculation code behind the already documented research-only table and did not change production strategy, Provider, subscription, or schema ownership semantics.

## Next Hypothesis

Implement Phase 3 B/E cross-section factors and raw Y2 support from the same daily stock fact slice, then keep Y_composite deferred until all Y raw components exist.
