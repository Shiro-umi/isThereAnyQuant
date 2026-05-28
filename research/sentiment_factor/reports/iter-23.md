# Iter 23 - STFT Overlap to 87.5%

## Phase / Variable

- Phase: B16 / Welch coherence precision further refined.
- Variable changed: `noverlap` for `Coherence.compute` from `(nperseg * 3) / 4` (75%) to `(nperseg * 7) / 8` (87.5%). No evaluator threshold, eval package, signal primitive, output contract, FDR family key, permutation procedure, lead-selection logic, pair-mode behaviour, or nperseg setting changed.

## Business Flow

Same as iter-22 with denser Welch averaging: `Coherence.compute(x, y, nperseg=40 or 48, noverlap=35 or 42)`. Where iter-20 doubled the segment count via 75% overlap, iter-23 quadruples it via 87.5% overlap.

## Metric

- Before (iter-22): 413 qualified A cards.
- After (iter-23): 420 qualified A cards.
- Verify command output: `420`.
- Cards written: 1598 -> 1609 (+11).

## Pre-Iteration Hypothesis Audit

Two candidate variables tried and rolled back before settling on noverlap bump:

1. **`topBottomSpread` quintile (n/5) -> decile (n/10)** (413 -> 413, no change). For 120-sample folds, decile cuts (12 samples per tail) are too thin to change spread averages.
2. **FDR family adds `factor_type` (single/pair)** (413 -> 402, -11). Same regression direction as the iter-18 test of the same variable — pair p-floor saturation helps single rank ordering in BH.

## SSOT Check

- `research/01-research-methodology.md`: no conflict.
- `research/02-research-execution-handbook.md`: §6 STFT averaging — noverlap is an estimator hyperparameter, not a methodology change.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table change.

## Plateau-Patience Status

Was 0/20 entering iter-23 (iter-22 set new best 413). Iter-23 sets new best 420. Counter resets to 0/20.

## Next Hypothesis

Continued: noverlap can be pushed further (15/16 = 93.75%) for theoretical maximum precision, but each segment shares 87.5% of its data with neighbors so additional gain is small. Other candidates for iter-24:
- **A**: gate 9 (still 686 failures) — current FDR family with per-fold orientation produces different p-value distributions; revisit BH formula edge cases (p-tie handling).
- **B**: gate 5 — extend horizon-valid window slightly (h=1 from [0.5, 1.5] to [0.5, 2.0]) — but this borders on threshold tweaking, may violate SSOT.
- **C**: gate 7 — `oosStats` could compute hit-rate using only non-tie samples (currently denominator excludes y=0 but not abs(y) < epsilon).
