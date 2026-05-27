# Iter 03 - Phase 3 B and E Cross-Section Factors

## Phase / Variable

- Phase: A3, B-group and E-group factor calculation.
- Variable changed: added B1/B3/B3p/B4/B5/B6/B7 and E1/E2 from the same daily stock fact slice. No Study metric formula, signal primitive, product contract, or evaluator logic changed.

## Business Flow

`stock_daily_data + stock_info -> SentimentFactorDailyCalculator -> sentiment_factor_daily`.

The new explicit batch switch is `runResearch --rebuild-b-e-group true`. It writes only B/E fields plus `Y2_raw`, so it does not overwrite A-group fields or future C/D fields. Default Verify still avoids MySQL and runs the skeleton metric through `ResonanceEvaluator`.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command output: `0`.

B/E population prepares research input only; the real Study is still not producing resonance metrics.

## Gate Failure Top 3

No meaningful gate ranking yet. The card output is still the skeleton empty metric and fail-closed rejection is expected.

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: no conflict; this phase is upstream fact preparation.
- `research/02-research-execution-handbook.md`: aligns with T-day raw-label storage and weighted cross-section convention.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: B/E formulas implemented from §4.2 and §4.5; `B2` remains intentionally absent.

No architecture reference update was needed: the documented `sentiment_factor_daily` research-only table semantics did not change, and production strategy/Provider/subscription semantics are untouched.

## Next Hypothesis

Implement Phase 4 C-group limit-board factors from `tushare_limit_list_d`, including `Y3_raw`, then add tests around `up_stat` parsing, triggered/clean board counts, and prior-limit continuation inputs.
