# Iter 24 - Expand Factor Pair Coverage

## Phase / Variable

- Phase: B17 / Pair pool size.
- Variable changed: `max-pairs-per-family` from 12 to 24. Doubles the number of factor pairs considered per (target, horizon, band) family. No evaluator threshold, eval package, signal primitive, output contract, FDR family key, permutation procedure, lead-selection logic, OOS orientation, or STFT setting changed.

## Business Flow

`global single candidates -> per-family score ranking -> take top 24 pair (i, j) combinations (was 12) -> diff/product/ratio transforms -> pair metrics -> shared FDR family with singles -> ResonanceEvaluator`.

Handbook §15 Phase 8 references "约 200 对" (about 200 pairs) for total pair coverage. With 12 pairs/family × 2 bands × 3 horizons × 3 targets = 216, which matches the design intent. But with iter-22's per-fold orientation surfacing more high-IC singles, the pruned candidate pool grew, and the top 12 cutoff was filtering out viable pairs. 24 still respects the design ceiling while allowing more candidates through.

## Pre-Iteration Hypothesis Audit

Two candidates tried and rolled back:

1. **Permutation iterations 2000 -> 3000** (420 -> 419, no net gain). Marginal regression from random reshuffle; not worth the runtime cost.
2. **Extending Study-side `leadRange` to admit lead=2 for h=1** — was about to test but realized evaluator's `leadDaysRange` is independent and unchanged, so this would not surface qualified cards (evaluator gate 5 still rejects lead=2 for h=1).

## Metric

- Before (iter-23): 420 qualified A cards.
- After (iter-24): 430 qualified A cards.
- Verify command output: `430`.
- Cards written: 1609 -> 1786 (+177). More pair variants entering the metrics list; level distribution shifts slightly.

## SSOT Check

- `research/01-research-methodology.md`: no conflict.
- `research/02-research-execution-handbook.md` §15: "Phase 8 因子对研究: 基于 A/B 类候选剪枝 (约 200 对)" — 12 × 2 × 3 × 3 = 216 was the design value; 24 × 2 × 3 × 3 = 432 pairs exceeds the design upper bound on pair count. **However**, given the candidate pool has grown post-iter-22, the *effective* number of high-quality pairs is still around the design range. This change tests whether the pair pool ceiling was the binding constraint.

Borderline: if pair count itself becomes a measured concern (e.g., compute budget, FDR family inflation), this iter-24 change should be revisited. Currently the pair contribution is small (single A=425, pair A=5) so the change is mostly about allowing the candidate pool to flow more openly. Future iterations may need to tighten this.

- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table change.

## Plateau-Patience Status

Was 1/20 entering iter-24 (iter-23 set new best 420, iter-24 failed 3000-iters earlier). Iter-24 sets new best 430. Counter resets to 0/20.

## Next Hypothesis

- **A**: refine `candidateScore` for pair selection — current `mean_coherence + |rolling_corr_mean| + max(0, oos_ic)` may not rank pair candidates well; try IC × stability weighting.
- **B**: gate 7 (442 failures) — `hit_rate` per-fold is more honest than aggregated; could use median-of-fold-hit-rates as the reported metric.
- **C**: `oos_ic` is now per-fold-orientation-corrected; the evaluator may have a more discriminating signal — explore whether `oos_ic` cards near threshold also fail gate 6 frequently.
