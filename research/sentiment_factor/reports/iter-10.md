# Iter 10 - Discovery Funnel Before Card Output

## Phase / Variable

- Phase: B3, Study-side discovery funnel.
- Variable changed: default Study output now keeps only metrics passing the handbook §5.4 rolling-correlation discovery rule before card output and state expansion. No evaluator threshold, eval package, output contract, or signal primitive changed.

## Business Flow

`bandpass + rolling correlation -> discovery candidate -> STFT/OOS/permutation metrics -> ResonanceEvaluator`.

The filter is based only on Study-side discovery metrics:

- `abs(rolling_corr_mean) > 0.10`
- `rolling_corr_stability > 0.55`
- recent rolling correlation direction matches `rolling_corr_mean`

`--discovery-filter false` remains available for controlled comparisons.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command output: `0`.
- Cards written: 1761, down from 2736.
- Raw significance after filtering: 84 cards with `p_value < 0.05`, 139 cards with `p_value < 0.1`.
- BH result remains strict: 0 cards with `q_value < 0.1`; minimum q-value improved from 1.0 to 0.8787425149700598.

Current level distribution: Reject 397, C 993, B 371, A 0.

## Gate Failure Top 3

- Gate 9: 1761 failures (`q_value < 0.1`)
- Gate 7: 1718 failures (`hit_rate > baseline + 0.05`)
- Gate 5: 1539 failures (`lead_days_lag` inside horizon range)

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: aligns with the three-layer funnel by requiring discovery candidates before downstream confirmation/output.
- `research/02-research-execution-handbook.md`: implements §5.4 as the default Study output filter while preserving evaluator independence.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table semantics changed.

No architecture reference update was needed: this is a Study-side candidate selection change only.

## Next Hypothesis

Gate 9 is still dominant, but the family is smaller and q-values now move in the right direction. Next iteration should add the STFT confirmation candidate rule before permutation/card output: require `mean_coherence` and `coherence_coverage` to pass a recall-oriented floor, so weak discovery-only candidates do not inflate the final FDR family.
