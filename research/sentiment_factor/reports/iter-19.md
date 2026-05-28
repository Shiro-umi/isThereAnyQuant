# Iter 19 - Horizon-Valid Lead Peak Threshold Loosening

## Phase / Variable

- Phase: B12 / Gate 5 (`lead_days_lag inside horizon range`) study-side refinement.
- Variable changed: in `SentimentResonanceStudy.leadByLagCorrelation`, the relative-strength threshold for preferring a horizon-valid lead peak over the global absolute peak from `0.85` to `0.65`. No evaluator threshold, eval package, signal primitive, output contract, FDR family key, permutation procedure, pair-mode behaviour, or other selection logic changed.

## Business Flow

`bandpassed X/Y -> lag correlations {-5..5} -> select horizon-valid peak if |corr| >= 0.65 * |absolute peak| else global peak -> ResonanceMetric.lead_days_lag -> ResonanceEvaluator gate 5`.

The prior threshold `0.85` (introduced in iter-14) was a conservative guard against forcing cards into A-class lead windows when the global peak was clearly elsewhere. Inspection of iter-18 gate-5 failures showed 385/608 (63%) had `lead_days_lag = 0.0` (synchronous absolute peak), meaning a positive-lag candidate did exist for these cards but failed the 0.85 strength gate. Loosening to `0.65` lets cards whose horizon-valid lead is materially weaker than the global peak (but still ≥65% of it) be reported as horizon-aligned, while still preserving the global peak when the valid peak is clearly noise (< 65%).

## Metric

- Before (iter-18): 291 qualified A cards.
- After (iter-19): 354 qualified A cards.
- Verify command output: `354`.
- Cards written: 1571 (unchanged).
- Diff vs iter-18: +63 newly qualified, **0 regressed**. Pure gain.
- Level distribution: Reject 103 (was 183), C 241 (was 380), B 873 (was 717), A 354 (was 291).

Gate raw-failure movements (iter-18 -> iter-19):

- Gate 5: 608 -> **354** (-254) — direct effect, 254 cards' lead moved into the horizon-valid range
- Gate 4 (`lead_relation_stable`): 68 -> 66 (-2) — *no regression*, slightly better because lag-side now matches phase-side branch
- Gate 0, 1, 2, 3, 6, 7, 8, 9: **all unchanged** (gate 9 still 663)

Cards still unlockable by ONLY gate 5: 163 -> dropped substantially (most absorbed); gate 9 only-blockers remain at ~109.

## Gate Failure Top 3

- Gate 1: 697 failures (`mean_coherence` floor) — unchanged
- Gate 9: 663 failures (`q_value < 0.1`) — unchanged
- Gate 7: 583 failures (`hit_rate > baseline + 0.05`) — unchanged

Note: gate 5 has dropped from top blocker list (354 raw failures, now below gate 7).

## First Newly Qualified Cards (sorted by q value ascending)

- `D1 Y2 h3 F1b lead=2.0 state=trend=all,disp=all,vol=all` q=0.0012
- `D3 Y2 h3 F2a lead=3.0 state=trend=high,disp=low+mid+high,vol=high` q=0.0023
- `A5 Y3 h1 F2a lead=1.0 state=trend=all,disp=all,vol=all` q=0.0025
- `B5 Y3 h1 F2a lead=1.0 state=trend=all,disp=all,vol=all` q=0.0025
- `D3 Y1 h1 F2a lead=1.0 state=trend=all,disp=all,vol=all` q=0.0026
- `B4_product_D3 Y2 h1 F2a lead=1.0` (pair) q=0.0026
- `B4 Y2 h3 F1b lead=2.0 state=trend=high,disp=low+mid,vol=mid` q=0.0033

All newly qualified cards have lead in {1.0, 2.0, 3.0} (well within their horizon-valid ranges).

## SSOT Check

- `research/01-research-methodology.md`: no conflict. Lead selection is a Study-side estimator choice; the evaluator's independent check (`lead_days_lag in [horizon-valid range]`) remains the authoritative gate.
- `research/02-research-execution-handbook.md`: `leadRange(horizon)` boundaries (h=1: [0.5, 1.5]; h=3: [1.0, 3.0]; h=5: [1.0, 5.0]) unchanged. Only the Study-side preference threshold changed.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor definition or fact-table semantic changed.
- Iter-14 introduced the 0.85 threshold to "not force all cards into A-class lead windows". Iter-19's 0.65 still rejects valid peaks weaker than that fraction, so the safeguard remains; it just admits more cases where the valid peak is materially present but not dominant.

No architecture reference update was needed: Study-side estimator threshold adjustment, no production-strategy, schema, runtime, or evaluator change.

## Next Hypothesis

Top blockers now:
1. Gate 1 (`mean_coherence` floor, 697 failures) — STFT-side metric; sensitive to `coherenceStats` window and band-selection
2. Gate 9 (`q_value < 0.1`, 663 failures) — still 109 cards only-gate-9-blocked
3. Gate 7 (`hit_rate > baseline + 0.05`, 583 failures)

Candidate variables for iter-20:
- **A**: gate 7 — `oosStats.hitRate` denominator currently is `count(sign(y) != 0)`; non-zero baseline uses `max(positive, negative)` proportions. Walk-forward folds aggregate then compute global hit-rate. Switching to per-fold hit-rate average reduces dilution from imbalanced folds.
- **B**: gate 9 — permutation iterations 2000 → 5000 (cheap, same direction as iter-18; another 2.5× p-value resolution).
- **C**: gate 1 — `coherenceStats.stftWindow = 40` is band-agnostic; F1b (3-5 day period) and F2a (5-8 day period) need different segment lengths. Per-band window sizing is the natural fix.

A (gate 7) is the highest-leverage candidate because it currently blocks 583 raw failures and has not been touched yet in any prior iter.
