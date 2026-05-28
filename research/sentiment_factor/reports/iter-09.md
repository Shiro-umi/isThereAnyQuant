# Iter 09 - Block Permutation P-Value Fix

## Phase / Variable

- Phase: B2, Study-side significance calculation.
- Variable changed: fixed `permutationPValue` so block permutation shuffles the future-Y sequence and recomputes IC against the fixed factor signal. No evaluator threshold, eval package, output contract, or signal primitive changed.

## Business Flow

`x_lfilter + future_y -> BlockPermutation(y blocks) -> recompute |IC(x, permuted_y)| -> p_value -> BH q_value -> ResonanceEvaluator`.

The previous implementation permuted the already multiplied `x*y` product and then averaged it. Block reordering preserves the average, so the null statistic was effectively identical to the observed statistic and p-values were not a meaningful significance test. This iteration restores the intended Study-side null distribution without touching the independent裁判器.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command output: `0`.
- Cards written: 2736.
- Raw significance now has signal: 108 cards with `p_value < 0.05`, 201 cards with `p_value < 0.1`.
- BH result remains strict: 0 cards with `q_value < 0.1`; minimum q-value is 1.0 under the current total test family.

Current level distribution: Reject 845, C 1343, B 548, A 0.

## Gate Failure Top 3

- Gate 9: 2736 failures (`q_value < 0.1`)
- Gate 7: 2676 failures (`hit_rate > baseline + 0.05`)
- Gate 5: 2406 failures (`lead_days_lag` inside horizon range)

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: no conflict; this fixes the null distribution used for multiple-test significance rather than changing acceptance criteria.
- `research/02-research-execution-handbook.md`: aligns with §10.1 by re-running the statistic after block permutation instead of using a permutation-invariant average.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table semantics changed.

No architecture reference update was needed: this is a Study-side metric calculation fix only.

## Next Hypothesis

Gate 9 is now a real multiple-testing pressure rather than a broken p-value calculation. Next iteration should reduce the tested family before BH using the handbook discovery funnel: only metrics that pass the rolling-correlation candidate rule should proceed to STFT/permutation/card output, while non-candidates should be summarized in the iteration report rather than inflating the card-level FDR family.
