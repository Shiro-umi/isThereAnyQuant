# Iter 08 - State-Conditioned Resonance Slices

## Phase / Variable

- Phase: B1, state-conditioned single-factor cards.
- Variable changed: added 3x3x3 market state slicing with hierarchical fallback for sparse states. No signal primitive, output contract, evaluator threshold, or eval logic changed.

## Business Flow

`sentiment_factor_daily -> state buckets(D4/A1, B3p/B3, EMA(A3)) -> SentimentResonanceStudy -> SentimentEvaluation -> ResonanceEvaluator -> ResonanceCardWriter`.

The Study still produces only raw `ResonanceMetric`. State-conditioned expansion is gated by the Study-side candidate funnel: global raw metrics with basic frequency/OOS structure are expanded into state slices, then judged by the frozen evaluator. This keeps Verify bounded while moving the research question from global `all` to actual state-specific cards.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command output: `0`.
- Cards written: 2736.
- State IDs written: 28 (`all` plus exact/merged state ids).

Current level distribution: Reject 845, C 1343, B 548, A 0.

## Gate Failure Top 3

- Gate 9: 2736 failures (`q_value < 0.1`)
- Gate 7: 2676 failures (`hit_rate > baseline + 0.05`)
- Gate 5: 2406 failures (`lead_days_lag` inside horizon range)

Compared with Iter 07, state slicing reduced the relative OOS IC failure pressure but made FDR and lead alignment the dominant blockers.

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: aligns with §2.7 / §3.3 by treating market state as a condition variable and merging sparse states by hierarchy instead of lowering evaluator thresholds.
- `research/02-research-execution-handbook.md`: implements the §9 state grid and §9.4 merge priority in Study-side slicing. Merged `state_id` values explicitly include `+` in the merged dimension.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor definition drift; the state buckets consume already persisted research facts and do not add tables.

No architecture reference update was needed: no database schema, production Provider, subscription, or strategy runtime semantics changed.

## Next Hypothesis

Gate 9 is now universal, so the next Study-side variable should target significance rather than evaluator thresholds: reduce the FDR family by splitting q-value adjustment by `(target_y, horizon, band)` research family, or add a stricter pre-STFT candidate filter before permutation so only plausible raw metrics enter the FDR pool.
