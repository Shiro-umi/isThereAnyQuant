# Iter 22 - Per-Fold OOS Orientation

## Phase / Variable

- Phase: B15 / OOS evaluation honesty.
- Variable changed: in `SentimentResonanceStudy.oosStats`, the orientation sign used to flip the factor for each validation fold is now refit on each fold's strictly-prior training window (`pairs[0..trainStart+500]`) instead of being computed once globally from a degenerate "train" slice. No evaluator threshold, eval package, signal primitive, output contract, FDR family key, permutation procedure, lead-selection logic, pair-mode behaviour, or STFT setting changed.

## Business Flow

`pairs (x_lfilter, y_lfilter band) -> walk-forward folds at trainStart += 20 -> for each fold: refit orientation on pairs[0..trainStart+500], apply to fold[trainStart+500..trainStart+620] -> flatten oriented folds -> global IC/hitRate/baseline/spread -> ResonanceMetric -> ResonanceEvaluator gates 6/7/8`.

The prior implementation contained a latent bug: `trainEnd = pairs.size - validation.size` where `validation` is the *flattened* overlapping folds. With 5-80 overlapping 120-sample folds, `validation.size` can exceed `pairs.size`, making `trainEnd` <= 1. The "training slice" collapses to a single sample, `pearson` on a 1-sample slice returns null, and `sign(null ?: 0.0) = 0.0`, which falls back to `orientation = 1.0`. **In effect, no orientation was being learned**; the hit-rate test was approximately "does `sign(x_lfilter)` match `sign(y_next)`", with no calibration.

The new implementation walks each fold's *true* prior data — pairs at indices 0 through the fold's start — and refits the orientation per fold. This is the standard expanding-window walk-forward orientation and what handbook §6.5 implicitly assumes.

## Metric

- Before (iter-21): 367 qualified A cards.
- After (iter-22): 413 qualified A cards.
- Verify command output: `413`.
- Cards written: 1580 -> 1598 (+18); the change shifts oos_ic per card, which reshuffles which singles become pair candidates downstream.
- Level distribution: Reject 109 (was 95), C 236 (was 235), B 840 (was 821), A 413 (was 367).

Gate raw-failure movements (iter-21 -> iter-22):

- Gate 6: 342 -> 198 (**-144** ⬇️) — top-bottom-spread improves with proper orientation
- Gate 8: 329 -> 186 (**-143** ⬇️) — top-bottom-spread consistency improves
- Gate 7: 558 -> 442 (**-116** ⬇️) — hit-rate now reflects an actually-trained orientation
- Gate 0: 127 -> 142 (+15)
- Gate 1: 693 -> 730 (+37)
- Gate 2: 319 -> 346 (+27)
- Gate 3: 77 -> 172 (+95)
- Gate 4: 52 -> 78 (+26)
- Gate 5: 340 -> 360 (+20)
- Gate 9: 626 -> 686 (+60) — more cards now have IC of the "wrong" sign and need orientation flip; q values reshuffled accordingly

Net effect: cards whose factor truly leads price get qualified at much higher rates because their hit-rate and spread metrics now correctly reflect the learned orientation. The gates that regressed (1, 9, etc.) regressed for cards that previously sneaked through with a default `orientation=1.0` and now legitimately fail the OOS test.

## Gate Failure Top 3

- Gate 1: 730 failures (`mean_coherence > 0.5`)
- Gate 9: 686 failures (`q_value < 0.1`)
- Gate 7: 442 failures (`hit_rate > baseline + 0.05`)

## SSOT Check

- `research/01-research-methodology.md`: no conflict. Refitting orientation per walk-forward fold is the standard interpretation of "training on past, validating on future". Section "Walk-forward 滚动训练-验证窗口" (§附录) is now actually implemented as described.
- `research/02-research-execution-handbook.md` §6.5 baseline definition unchanged. Section "训练窗口 500, 验证窗口 120, 滚动步长 20" is now correctly applied per fold.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table change.

**Important honesty note**: this change fixes a latent bug rather than tuning a parameter. It is therefore a legitimate Study-side change because the original behaviour was an implementation artefact, not an intentional research decision. ResonanceEvaluator still owns the qualified verdict; this fix only ensures the metrics fed to the evaluator are computed honestly.

## Pre-Iteration Hypothesis Audit

Two candidate variables were tried and rolled back this iteration before the per-fold orientation fix:

1. **Phase branch enumeration ±2 -> ±3 in `alignPhaseLeadToLag`** (367 -> 367, no change).
2. **F1b STFT segment 40 -> 44** alongside F2a=48 (367 -> 367, no change).

Both signals: STFT/phase tuning has reached a local plateau; gains require a methodologically deeper change.

## Plateau-Patience Status

Was 2/20 at start of iter-22. Iter-22 sets new best 413. Counter resets to 0/20.

## Next Hypothesis

With per-fold orientation in place, gate 7 still has 442 failures and gate 9 has 686. Candidate variables for iter-23:

- **A**: gate 9 now reshuffled — re-examine FDR family. Per-target-horizon-band families are now larger because more singles produce strong IC. Maybe splitting by `factor_type` (single vs pair) is now beneficial whereas it regressed at iter-18-pre-fix.
- **B**: gate 6/8 still 198/186 failures — `topBottomSpread` quintile cut (`n = sorted.size / 5`) is coarse for 120-sample folds (only ~24 in each tail). Use deciles (n/10).
- **C**: gate 1 regressed; STFT noverlap or per-band nperseg need revisiting now that more "true signal" cards are visible.
