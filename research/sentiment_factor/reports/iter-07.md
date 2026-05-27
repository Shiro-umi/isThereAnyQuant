# Iter 07 - Phase 7 Sentiment Resonance Study

## Phase / Variable

- Phase: A7, single-factor `SentimentResonanceStudy` and evaluator wiring.
- Variable changed: replaced the default skeleton run with a real Study that reads `sentiment_factor_daily`, builds dynamic future Y labels, computes raw `ResonanceMetric` fields, then sends them to the frozen `ResonanceEvaluator`. No signal primitive, product contract, output schema, evaluator threshold, or eval logic changed.

## Business Flow

`sentiment_factor_daily -> SentimentTargetLabelCalculator -> SentimentResonanceStudy -> SentimentEvaluation -> ResonanceEvaluator -> ResonanceCardWriter`.

The Study produces only uncensored raw metrics. `SentimentEvaluation` only calls `ResonanceEvaluator.evaluate(metric)` and passes the verdict into `ResonanceCard.from(...)`. The old skeleton path remains available only through explicit `runResearch --skeleton true`.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command output: `0`.
- Cards written by the real Study: 576.

I also rebuilt the local research fact table from the existing batch switches: each group wrote 1548 rows, then the default Verify cleared old cards and regenerated the 576 current cards.

## Gate Failure Top 3

- Gate 7: 576 failures (`hit_rate > baseline + 0.05`)
- Gate 9: 576 failures (`q_value < 0.1`)
- Gate 6: 546 failures (`oos_ic > 0.05`)

Current level distribution: Reject 229, C 296, B 51, A 0.

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: no conflict; Study now follows the split-frequency route before correlation, computes leading lag/phase signals, OOS stats, and causal filtfilt/lfilter comparison.
- `research/02-research-execution-handbook.md`: partial Phase 7 implementation is now live for single factors across Y1/Y2/Y3, horizons 1/3/5, and default F1b/F2a. State is currently `all`, so the 27-cell state split remains the next completeness gap.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor definition drift; Study consumes the 38-column fact table and does not persist research conclusions.

No architecture reference update was needed: the database table semantics are unchanged, and this adds only a research-side consumer plus file output.

## Next Hypothesis

The dominant failures are OOS hit-rate, FDR q-value, and OOS IC. Next iteration should stay on Study-side research variables: add the 27-cell state grouping with §9.4 hierarchical fallback, then compare whether state-conditioned samples reduce gate 7/6 failures before expanding bands or factor pairs.
