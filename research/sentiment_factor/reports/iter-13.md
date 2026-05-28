# Iter 13 - OOS Validation On Causal Band Target

## Phase / Variable

- Phase: B6, OOS validation target alignment.
- Variable changed: OOS IC / hit-rate / top-bottom spread / block permutation now validate `X_B(t) -> Y_B(t+h)` using causal `lfilter` target bands instead of raw `Y_next_h`. No evaluator threshold, eval package, output contract, or signal primitive changed.

## Business Flow

`x_lfilter(t) + y_lfilter(t+h) -> OOS metrics + block permutation -> q_value -> ResonanceEvaluator`.

This restores the core methodology redline: after frequency split, validation must stay in the same band and must use causal filtering. Future raw Y labels are still required only to ensure the horizon is available for the row; the validated target is now the causal band target.

## Metric

- Before: 0 qualified cards.
- After: 8 qualified cards.
- Verify command output: `8`.
- Cards written: 1349.
- Cards with `q_value < 0.1`: 642.
- Minimum q-value: 0.006843455945252352.

Current level distribution: Reject 266, C 833, B 242, A 8.

## Gate Failure Top 3

- Gate 5: 1194 failures (`lead_days_lag` inside horizon range)
- Gate 9: 707 failures (`q_value < 0.1`)
- Gate 7: 471 failures (`hit_rate > baseline + 0.05`)

## First Qualified Cards

- `B6 -> Y3 h3 F2a trend=mid,disp=mid+high,vol=high`
- `A6 -> Y3 h3 F2a trend=mid,disp=mid,vol=low`
- `B6 -> Y2 h3 F2a trend=mid,disp=mid+high,vol=high`
- `A5 -> Y3 h1 F2a trend=high,disp=low+mid,vol=high`
- `B6 -> Y2 h3 F2a trend=mid,disp=mid,vol=low`

## SSOT Check

- `research/01-research-methodology.md`: aligns with the redline `X_B(t) -> Y_B(t+h)` and causal validation.
- `research/02-research-execution-handbook.md`: aligns with §7.3 by using `lfilter` in validation and with §5.1 by validating band-split series.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table semantics changed.

No architecture reference update was needed: this is a Study-side metric calculation correction only.

## Next Hypothesis

Gate 5 is now the largest blocker. Next iteration should target lead alignment: compute the OOS target for each horizon using the same lead candidate window and prefer horizon-compatible positive-lag peaks when absolute correlations are close, without changing evaluator thresholds.
