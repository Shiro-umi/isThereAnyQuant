# Iter 21 - F2a STFT Window Sized to Band Period

## Phase / Variable

- Phase: B14 / Per-band STFT segment length for F2a.
- Variable changed: `coherenceStats` STFT segment length for band F2a from 40 to 48; F1b and others unchanged at 40. No evaluator threshold, eval package, signal primitive, output contract, FDR family key, permutation procedure, lead-selection logic, pair-mode behaviour, or noverlap fraction changed.

## Business Flow

`bandpassed X/Y -> Welch STFT (F2a: nperseg=48; else: nperseg=40, noverlap=75% of nperseg) -> per-band coherence aggregates -> ResonanceMetric -> ResonanceEvaluator gates 1/2/3/4`.

F2a covers 5-8 day periods. With nperseg=40 the segment fits ~5 full F2a periods (40/8) to ~8 full periods (40/5). With nperseg=48 it fits 6 to ~10 full periods, which slightly improves frequency-domain localization for the longest F2a periods without breaking the Study-side stftFilter calibration (earlier 80-sample attempt regressed by 80 cards because the filter calibration assumed shorter segments).

## Metric

- Before (iter-20): 366 qualified A cards.
- After (iter-21): 367 qualified A cards.
- Verify command output: `367`.
- Cards written: 1517 -> 1580. F2a state-conditioned slices now retain a few more borderline-coherent cards, but only one extra makes it through the full 12-gate evaluator.

## Gate Failure Top 3

(Largely unchanged from iter-20.)
- Gate 1: ~693 failures (`mean_coherence > 0.5`)
- Gate 9: ~626 failures (`q_value < 0.1`)
- Gate 7: ~558 failures (`hit_rate > baseline + 0.05`)

## SSOT Check

- `research/01-research-methodology.md`: no conflict. Per-band STFT segment length is an estimator hyperparameter, not a methodology change.
- `research/02-research-execution-handbook.md`: §6 STFT band index unchanged; segment-length tuning is implementation detail.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table change.

## Plateau-Patience Status

Patience counter: was 0/20. Iter-21 sets new best 367. Counter resets to 0/20.

## Next Hypothesis

Marginal improvement signals we are approaching the practical ceiling for purely Study-side changes given the current methodology constraints. Candidate variables for iter-22:
- **A**: extend phase branch enumeration in `alignPhaseLeadToLag` from ±2 to ±3 to catch wider F2a phase wraps (gate 4 still has ~52 failures, several only-gate-4 cards have lag-phase diff 1.5-2.0 days)
- **B**: per-fold OOS orientation (refit sign on each fold's train window instead of one global orientation)
- **C**: F1b STFT also bumped from 40 to 44 (small tweak — F1b periods 3-5 days)
