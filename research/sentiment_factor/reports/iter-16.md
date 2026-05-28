# Iter 16 - Anti-Phase Lead Branch Alignment

## Phase / Variable

- Phase: B9, phase/lag lead consistency.
- Variable changed: `lead_days_phase` branch alignment now preserves the selected lag-correlation sign and, for anti-phase negative lag peaks, also considers half-period phase branches. No evaluator threshold, eval package, output contract, or signal primitive changed.

## Business Flow

`lag correlation peak sign -> phase branch candidates -> full-period or anti-phase half-period branch -> ResonanceMetric.lead_days_phase -> ResonanceEvaluator`.

The previous iteration fixed whole-period phase wrapping. The remaining gate-4 failures were mostly near a half-period F2a gap, consistent with anti-phase relationships. This iteration keeps lag correlation as the primary lead estimate and only maps the phase-derived cross-check into the comparable signed phase branch.

## Metric

- Before: 44 qualified cards.
- After: 276 qualified cards.
- Verify command output: `276`.
- Cards written: 1349.
- Current level distribution: Reject 177, C 327, B 569, A 276.

Gate movements:

- Gate 4 failures: 854 -> 67.
- Gate 9 failures: unchanged at 707.
- Gate 5 failures: unchanged at 549.

## Gate Failure Top 3

- Gate 9: 707 failures (`q_value < 0.1`)
- Gate 5: 549 failures (`lead_days_lag` inside horizon range)
- Gate 7: 471 failures (`hit_rate > baseline + 0.05`)

## First Qualified Cards

Examples from verified A cards:

- `B6 -> Y3 h3 F2a trend=mid,disp=mid+high,vol=high`
- `B4 -> Y2 h3 F2a trend=low,disp=mid,vol=low`
- `A7 -> Y1 h3 F2a trend=high,disp=high,vol=high`
- `A7 -> Y1 h3 F2a trend=mid,disp=low+mid,vol=mid`
- `B7 -> Y1 h3 F1b trend=all,disp=all,vol=all`

## SSOT Check

- `research/01-research-methodology.md`: no conflict; lag correlation remains the primary lead estimate, phase remains the cross-check.
- `research/02-research-execution-handbook.md`: aligns with §6.5 by comparing phase-derived lead to lag-derived lead in equivalent phase branches, including anti-phase sign ambiguity.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table semantics changed.

No architecture reference update was needed: this is a Study-side phase metric normalization only and does not change database facts, production sentiment strategy flow, or runtime data ownership.

## Next Hypothesis

Gate 9 is now the dominant blocker. Next iteration should target Study-side significance estimation or candidate family construction, while keeping `BlockPermutation` and `ResonanceEvaluator` unchanged.
