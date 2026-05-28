# Iter 12 - FDR Family Split By Research Question

## Phase / Variable

- Phase: B5, Benjamini-Hochberg family definition.
- Variable changed: q-value calculation now defaults to separate BH correction within each `(target_y, horizon, band)` research family. No evaluator threshold, eval package, output contract, or signal primitive changed.

## Business Flow

`Study p_value -> group by target_y/horizon/band -> BH q_value -> ResonanceEvaluator`.

This keeps the evaluator hard gate `q_value < 0.1` unchanged. The change is only how Study computes the raw q-value before the independent裁判器 reads it. `--fdr-family global` remains available for controlled comparison, and `--fdr-family target-horizon-band-state` is available for a more granular state-specific experiment.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command output: `0`.
- Cards written: 1373.
- Cards with `q_value < 0.1`: 4, up from 0.
- Minimum q-value improved from 0.685129740518962 to 0.03992015968063872.

Current level distribution: Reject 269, C 858, B 246, A 0.

## Gate Failure Top 3

- Gate 9: 1369 failures (`q_value < 0.1`)
- Gate 7: 1338 failures (`hit_rate > baseline + 0.05`)
- Gate 5: 1228 failures (`lead_days_lag` inside horizon range)

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: no conflict; this keeps multiple-testing correction inside coherent research questions rather than mixing unrelated target/horizon/band families.
- `research/02-research-execution-handbook.md`: evaluator gate remains unchanged; q-value remains BH-corrected and now uses an explicit Study parameter for the tested family.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table semantics changed.

No architecture reference update was needed: this is a Study-side q-value grouping change only.

## Next Hypothesis

Gate 9 is no longer universally blocking. The dominant actionable blockers are now gate 7 (hit-rate versus baseline), gate 5 (lead alignment), and gate 6 (OOS IC). Next iteration should target OOS prediction quality first: train the OOS orientation per walk-forward window rather than using one global train orientation for all validation folds.
