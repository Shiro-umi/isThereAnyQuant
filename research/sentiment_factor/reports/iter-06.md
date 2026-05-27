# Iter 06 - Phase 6 Target Labels

## Phase / Variable

- Phase: A6, Y-composite and target-label preparation.
- Variable changed: completed `Y_composite` on `sentiment_factor_daily` and added dynamic horizon/auxiliary target-label generation for Study input. No Study metric formula, signal primitive, product contract, or evaluator logic changed.

## Business Flow

`stock_daily_data + stock_info + tushare_limit_list_d -> SentimentFactorDailyCalculator -> sentiment_factor_daily -> SentimentTargetLabelCalculator`.

The new explicit batch switch is `runResearch --rebuild-labels true`. It writes only raw Y fields and `Y_composite` to the research fact table. Future horizon labels and auxiliary labels are generated dynamically from the factual daily rows, so research labels and conclusions are not persisted to production tables.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command output: `0`.

Label completion prepares Study input only; the real Study is still not producing resonance metrics.

## Gate Failure Top 3

No meaningful gate ranking yet. The card output is still the skeleton empty metric and fail-closed rejection is expected.

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: no conflict; this phase is upstream fact and target preparation.
- `research/02-research-execution-handbook.md`: aligns with Phase 6 by keeping T-day raw labels in the fact table and deriving future horizon labels without shifting the stored facts.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: keeps `sentiment_factor_daily` as the only reusable research fact table and does not persist study conclusions or intermediate resonance state.

No architecture reference update was needed: the research-only table semantics remain unchanged, and production strategy/Provider/subscription semantics are untouched.

## Next Hypothesis

Implement Phase 7 by replacing the skeleton metric path with `SentimentResonanceStudy`: load completed factor/Y facts, compute raw `ResonanceMetric` fields through the three-layer funnel, and wire those metrics to the frozen `ResonanceEvaluator` without changing evaluation logic.
