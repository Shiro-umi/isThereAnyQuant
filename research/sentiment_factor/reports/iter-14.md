# Iter 14 - Horizon-Aware Lead Peak Selection

## Phase / Variable

- Phase: B7, lead alignment.
- Variable changed: `lead_days_lag` selection now prefers a horizon-compatible positive-lag peak when its absolute correlation is at least 85% of the global absolute peak. No evaluator threshold, eval package, output contract, or signal primitive changed.

## Business Flow

`bandpassed X/Y -> lag correlations {-5..5} -> horizon-compatible lead candidate -> ResonanceMetric.lead_days_lag -> ResonanceEvaluator`.

The prior lead selector always chose the global absolute peak, which caused many otherwise useful cards to be marked synchronous (`lead=0`) even when a nearby positive lead peak existed. This iteration still keeps the global peak when the horizon-compatible peak is materially weaker, so it does not force all cards into A-class lead windows.

## Metric

- Before: 8 qualified cards.
- After: 37 qualified cards.
- Verify command output: `37`.
- Cards written: 1349.
- Current level distribution: Reject 177, C 327, B 808, A 37.

Gate movements:

- Gate 5 failures: 1194 -> 549.
- Gate 9 failures: unchanged at 707.
- Gate 7 failures: unchanged at 471.

## Gate Failure Top 3

- Gate 4: 904 failures (`lead_relation_stable = true`)
- Gate 9: 707 failures (`q_value < 0.1`)
- Gate 5: 549 failures (`lead_days_lag` inside horizon range)

## First Qualified Cards

Examples from the first verified A cards:

- `B6 -> Y3 h3 F2a trend=mid,disp=mid+high,vol=high`
- `B5 -> Y1 h1 F2a trend=low,disp=low,vol=low`
- `D4 -> Y1 h3 F2a trend=high,disp=low+mid+high,vol=high`
- `A2 -> Y2 h1 F2a trend=low,disp=high,vol=mid`
- `A7 -> Y2 h3 F2a trend=low,disp=high,vol=high`

## SSOT Check

- `research/01-research-methodology.md`: aligns with the requirement that prediction needs positive leading relation, while keeping lag correlation as the primary method.
- `research/02-research-execution-handbook.md`: preserves horizon intervals from §6.6 and only changes Study-side peak selection when an interval-compatible peak is nearly as strong as the global peak.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table semantics changed.

No architecture reference update was needed: this is a Study-side lead metric calculation refinement only.

## Next Hypothesis

Gate 4 is now the largest blocker because phase-derived lead often differs from the horizon-aware lag peak by more than one day. Next iteration should improve `lead_days_phase` by using the same horizon-compatible phase branch or by falling back to lag-based stability when the phase estimate is near a wrapped equivalent.
