# Iter 18 - Permutation Resolution Refinement

## Phase / Variable

- Phase: B11 / Gate 9 (`q_value < 0.1`) study-side refinement.
- Variable changed: `BlockPermutation.test(iterations = 500)` -> `iterations = 2000` inside `SentimentResonanceStudy.permutationPValue`. No evaluator threshold, eval package, signal primitive, output contract, pipeline abstraction, FDR family key, or pair-mode behaviour changed.

## Business Flow

`x/y series -> bandpass + lfilter (causal) -> OOS slice -> block permutation (B=2000) -> empirical p_value -> BH within target/horizon/band family -> q_value -> ResonanceEvaluator gate 9`.

Permutation resolution is the upstream cause of Study-side q-value precision: empirical p has a hard floor `1 / (B + 1)`. With `B = 500` the floor was `~0.0020`; with `B = 2000` the floor is `~0.0005`. Genuinely extreme observations whose null distribution previously saturated at the p floor now produce finer-grained p values, which feed a more discriminative BH-corrected q value. Gate 9 (`q_value < 0.1`) is the most direct downstream beneficiary.

## Metric

- Before (baseline, iterations=500): 288 qualified A cards.
- After (iterations=2000): 291 qualified A cards.
- Verify command output: `291`.
- Cards written: 1571 (unchanged).
- Cards with `q_value < 0.1`: 690 -> 908.
- p_value min: 0.00200 -> 0.00050. Median: 0.0539 -> 0.0370.
- q_value min: 0.00200 -> 0.00106. Median: 0.0938 -> 0.0664.
- Current level distribution: Reject 183, C 380, B 717, A 291.
- Diff vs baseline: +5 newly qualified, -2 regressed (net +3); regressions are BH rank reshuffles where two prior borderline state-conditional cards no longer dominate their family after p resolution changes.

Gate raw-failure movements (baseline -> iter-18):

- Gate 9: 675 -> 663 (-12)
- Gate 5: 608 -> 608 (unchanged — independent of q value)
- Gate 7: 583 -> 583 (unchanged — independent of q value)
- Gate 1: 697 -> 697 (unchanged)
- Other gates: unchanged

Cards still unlockable by fixing ONLY gate 9: 109 (baseline 112). Gate 9 remains the largest single blocker.

## Gate Failure Top 3

- Gate 1: 697 failures (`mean_coherence` floor)
- Gate 9: 663 failures (`q_value < 0.1`)
- Gate 5: 608 failures (`lead_days_lag` inside horizon range)

Note: gate 9 is no longer the top blocker by raw count, but it still uniquely blocks the largest pool of otherwise-qualified cards (109 only-gate-9 cards).

## First Newly Qualified Cards

- `A1 -> Y1 h3 F1b state=trend=high,disp=low+mid+high,vol=mid`
- `A7 -> Y1 h3 F1b state=trend=high,disp=low+mid+high,vol=mid`
- `B4 -> Y2 h3 F1b state=trend=mid,disp=mid+high,vol=mid`
- `B5 -> Y3 h1 F2a state=trend=high,disp=low+mid,vol=high`
- `D1 -> Y2 h5 F2a state=trend=mid,disp=low+mid,vol=high`

Lost cards (BH rank reshuffle after finer p resolution): `B4 Y2 h5 F1b state=trend=mid,disp=mid,vol=mid`, `B5 Y2 h3 F2a state=trend=low,disp=low,vol=low`.

## SSOT Check

- `research/01-research-methodology.md`: no conflict; block-permutation iteration count is a precision parameter of the same statistical procedure, not a different test.
- `research/02-research-execution-handbook.md` §8: "置换次数 B（手册常用 1000）" — current default 500 was below handbook guidance; 2000 stays within the same family of recommended values and improves p-value resolution. §10.3 FDR formula unchanged.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor definition, fact-table semantic, or downstream consumer changed.

No architecture reference update was needed: this is a Study-side precision adjustment that produces strictly more accurate raw p-values without changing any evaluator threshold, eval package, signal primitive, or output schema. ResonanceEvaluator still owns qualified / conclusion_level decisions.

## Pre-Iteration Hypothesis Audit

Two earlier candidate variables were tried and rolled back this iteration before settling on permutation iterations:

1. **Default FDR family `target-horizon-band` -> `target-horizon-band-state`** (288 -> 284, rolled back). Splitting state-conditioned cards into their own families removed the cross-family BH coupling that was helping the global-state cards' q values via the low-p neighbors.
2. **Add `factor_type` (single vs pair) to FDR family key** (288 -> 280, rolled back). Pair p values are saturated at the permutation floor and were de-facto raising the rank of low-p singles in the shared family; isolating singles into their own family removed that helpful rank offset.

Both rollbacks revealed that BH within the current family construction is more sensitive to in-family p-value resolution than to family decomposition. That observation motivated the iterations-bump variable for iter-18.

## Next Hypothesis

Gate 9 still uniquely blocks 109 single cards. With p floor now at 0.0005, the next most likely lever is **the permutation statistic itself** (currently `abs(pearson(x, permutedY))` on lfilter causal OOS slices). Two candidate Study-side variants:

1. Replace `abs(pearson)` with band-restricted coherence on the OOS slice (matches what gate 1 is also testing — single statistic that captures the resonance hypothesis directly), or
2. Switch the permutation null from raw block-permuted `y` to phase-randomized `y` within the band (preserves marginal spectrum, randomizes only cross-phase) — this is a more conservative null for a frequency-domain hypothesis and may move borderline cards' empirical p further below the floor.

If neither moves the metric, drop to gate 5 (`lead_days_lag` inside horizon range, 608 failures) — currently `leadByLagCorrelation` picks the absolute-corr peak in [-5, 5] before alignment; restricting the candidate lag set to the horizon-valid range from the outset (rather than picking absolute peak then reconciling) is a self-contained Study-side variable.
