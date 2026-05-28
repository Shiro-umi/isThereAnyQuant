# Iter 17 - Pair Factor Resonance Metrics

## Phase / Variable

- Phase: B10 / Phase 8 factor pairs.
- Variable changed: enabled pair-factor Study generation from pruned single-factor candidates. Pair forms: `diff`, `product`, `ratio`. Pair cards fill `delta_ic_vs_base`, `delta_score_vs_base`, and `beta3_stability` before the existing independent evaluator runs.

## Business Flow

`single-factor candidates -> top pairs per target/horizon/band -> diff/product/ratio pair series -> existing three-layer Study metrics -> pair extras -> ResonanceEvaluator`.

This iteration completes the factor-pair research path without changing evaluator logic. Pair baselines use the stronger of the two matched single-factor candidates for the same `Y/horizon/band`; `beta3_stability` is computed from rolling interaction-term regressions.

## Metric

- Before: 276 qualified cards.
- After: 288 qualified cards.
- Verify command output: `288`.
- Cards written: 1571.
- Pair cards written: 222.
- Pair qualified cards: 4.
- Current level distribution: Reject 183, C 380, B 720, A 288.

Gate movements:

- Gate 9 failures: 707 -> 675.
- Gate 5 failures: 549 -> 608.
- Gate 7 failures: 471 -> 583.
- New pair gate 12 failures: 301.

## Gate Failure Top 3

- Gate 9: 675 failures (`q_value < 0.1`)
- Gate 5: 608 failures (`lead_days_lag` inside horizon range)
- Gate 7: 583 failures (`hit_rate > baseline + 0.05`)

## First Qualified Pair Cards

- `A7_diff_D4 -> Y1 h3 F2a`
- `A1_diff_D4 -> Y1 h3 F2a`
- `A6_diff_A5 -> Y3 h3 F2a`
- `B4_product_A2 -> Y2 h1 F2a`

## SSOT Check

- `research/01-research-methodology.md`: no conflict; factor pairs are still evaluated by the same OOS, permutation, FDR, and causal-filter validation path.
- `research/02-research-execution-handbook.md`: aligns with Phase 8 factor-pair requirements by adding delta-vs-base and beta3-stability fields required for pair gate 12.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor definition or fact-table semantics changed.

No architecture reference update was needed: this is Study-side research metric generation only. It does not change production strategy flow, database schema, runtime ownership, or persisted fact semantics.

## Next Hypothesis

All requested research phases now have an implemented end-to-end path. Remaining quality work is scientific iteration rather than missing phase plumbing: gate 9 remains the largest blocker, followed by lead-window and hit-rate gates.
