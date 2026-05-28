# Iter 11 - STFT Confirmation Candidate Filter

## Phase / Variable

- Phase: B4, STFT confirmation candidate filtering.
- Variable changed: default Study output now requires recall-oriented STFT confirmation after the rolling-correlation discovery funnel: `mean_coherence >= 0.40` and `coherence_coverage >= 0.15`. No evaluator threshold, eval package, output contract, or signal primitive changed.

## Business Flow

`discovery candidate -> STFT mean/coverage floor -> OOS/permutation metrics -> ResonanceEvaluator`.

This keeps the three-layer funnel explicit: weak frequency-domain candidates no longer inflate the final card and FDR family. `--stft-filter false` remains available for controlled comparisons, and the default floors can be varied through `--stft-coherence-floor` / `--stft-coverage-floor`.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command output: `0`.
- Cards written: 1373, down from 1761.
- Raw significance after filtering: 74 cards with `p_value < 0.05`, 117 cards with `p_value < 0.1`.
- BH result remains strict: 0 cards with `q_value < 0.1`; minimum q-value improved from 0.8787425149700598 to 0.685129740518962.

Current level distribution: Reject 269, C 858, B 246, A 0.

## Gate Failure Top 3

- Gate 9: 1373 failures (`q_value < 0.1`)
- Gate 7: 1338 failures (`hit_rate > baseline + 0.05`)
- Gate 5: 1228 failures (`lead_days_lag` inside horizon range)

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: aligns with the three-layer funnel by requiring STFT frequency confirmation before downstream validation/output.
- `research/02-research-execution-handbook.md`: uses §6.4 metrics as a recall-oriented candidate floor rather than replacing evaluator hard gates.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table semantics changed.

No architecture reference update was needed: this is a Study-side candidate selection change only.

## Next Hypothesis

Gate 9 is still dominant but continues to improve as the tested family shrinks. Next iteration should split Benjamini-Hochberg by research family `(target_y, horizon, band)` so unrelated Y/horizon/band questions do not penalize each other in one global q-value pool.
