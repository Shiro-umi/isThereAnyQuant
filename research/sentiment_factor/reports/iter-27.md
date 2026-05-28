# Iter 27 - Raise MIN_STATE_SAMPLE to 80

## Phase / Variable

- Variable: `MIN_STATE_SAMPLE` 60 → 80. Wider sample-size floor before a market-state bucket is considered analyzable directly; smaller buckets get merged across adjacent levels.

## Pre-Iteration Hypothesis Audit

- **max-state-candidates 160→200** (477 → 477, no gain) — cap is already above the actual filter-passing candidate count.
- **MIN_STATE_SAMPLE 60→40** (477 → 324, severe regression). Smaller floor causes mergeState to recursively split fragmented states into low-quality slices.

## Metric

- Before (iter-26): 477 → After (iter-27): **520** (+43).
- Cards written: 2288 → 1974 (-314). Fewer fragmented state slices but each is more robust.
- Verify: `520`.

## Mechanism

Raising the floor forces low-sample state buckets to merge into adjacent-level neighbors more eagerly (per `mergeState` candidate enumeration). The resulting merged states have more observations, leading to:
- Higher hit-rate stability (gate 7 benefits)
- More reliable rolling-corr-mean (discoveryFunnel pass-through)
- Less noise from over-segmented state-slicing

This is consistent with handbook §11.2 ("三口径并跑") expecting state buckets to have enough samples to be meaningful.

## SSOT

- Methodology preserves state-conditional analysis. Sample-size floor is an implementation parameter, not a thresholds change.

## Plateau-Patience

Was 2/20 entering iter-27 (200 and 40 both failed). Iter-27 sets new best 520. Counter resets to 0/20.

## Next Hypothesis

- **A**: try MIN_STATE_SAMPLE 80 → 100 to see if push further still helps.
- **B**: `state-window` (currently 60) — extending to 80 captures longer regime persistence.
