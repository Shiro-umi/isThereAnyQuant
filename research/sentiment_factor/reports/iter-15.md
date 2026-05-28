# Iter 15 - Phase Lead Branch Alignment

## Phase / Variable

- Phase: B8, phase/lag lead consistency.
- Variable changed: `lead_days_phase` is now expanded by integer band-center periods and mapped to the branch closest to `lead_days_lag`. No evaluator threshold, eval package, output contract, or signal primitive changed.

## Business Flow

`STFT phase lead -> add k * band_period -> closest branch to lag lead -> ResonanceMetric.lead_days_phase -> ResonanceEvaluator`.

The largest gate-4 differences were near one F2a period, which indicates phase wrapping rather than a true disagreement. This iteration keeps lag correlation as the primary lead estimate and only normalizes the phase-derived cross-check into the comparable branch.

## Metric

- Before: 37 qualified cards.
- After: 44 qualified cards.
- Verify command output: `44`.
- Cards written: 1349.
- Current level distribution: Reject 177, C 327, B 801, A 44.

Gate movements:

- Gate 4 failures: 904 -> 854.
- Gate 9 failures: unchanged at 707.
- Gate 5 failures: unchanged at 549.

## Gate Failure Top 3

- Gate 4: 854 failures (`lead_relation_stable = true`)
- Gate 9: 707 failures (`q_value < 0.1`)
- Gate 5: 549 failures (`lead_days_lag` inside horizon range)

## First Qualified Cards

Examples from verified A cards:

- `B6 -> Y3 h3 F2a trend=mid,disp=mid+high,vol=high`
- `B6 -> Y2 h3 F2a trend=high,disp=low+mid+high,vol=mid`
- `B5 -> Y1 h1 F2a trend=low,disp=low,vol=low`
- `D4 -> Y1 h3 F2a trend=high,disp=low+mid+high,vol=high`
- `A2 -> Y2 h1 F2a trend=low,disp=high,vol=mid`

## SSOT Check

- `research/01-research-methodology.md`: no conflict; lag correlation remains the primary lead estimate, phase remains the cross-check.
- `research/02-research-execution-handbook.md`: aligns with §6.5 by comparing phase-derived lead to lag-derived lead in equivalent phase branches.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table semantics changed.

No architecture reference update was needed: this is a Study-side phase metric normalization only.

## Next Hypothesis

Gate 4 remains the largest blocker. Next iteration should make the phase cross-check robust to sign ambiguity in anti-phase relationships: when the selected lag peak uses a negative correlation, compare phase lead after adding a half-period branch as well as full-period branches.
