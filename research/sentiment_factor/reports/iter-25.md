# Iter 25 - Expand State-Conditional Candidate Pool

## Phase / Variable

- Phase: B18 / State-conditional metric coverage.
- Variable changed: `max-state-candidates` from 80 to 120. State-conditional metrics now consider the top 120 single candidates (by `candidateScore`) for state-slicing, instead of 80. No evaluator threshold, eval package, signal primitive, output contract, FDR family key, permutation procedure, lead-selection logic, OOS orientation, STFT setting, or pair-mode behaviour changed.

## Business Flow

`global candidates (post discoveryFilter + stftFilter) -> sort by candidateScore desc -> take top 120 (was 80) -> for each, build state-conditional slices (~10-15 per candidate) -> filter, BH-correct, evaluate -> ResonanceMetric -> ResonanceEvaluator`.

State-conditional cards are where the strongest qualified-A signals come from: cards conditioned on `trend=high,disp=...,vol=...` capture regime-specific resonance. The previous 80-candidate cap was tuned at iter-7 era when single qualified A was much lower. With iter-22's per-fold OOS orientation now exposing 425 single A-class cards, the cap was holding back the next tier of state-conditional candidates that could surface.

## Metric

- Before (iter-24): 430 qualified A cards.
- After (iter-25): 466 qualified A cards.
- Verify command output: `466`.
- Cards written: 1786 -> 2176 (+390). Extra state-conditional slices from the new 40 candidates.

## SSOT Check

- `research/01-research-methodology.md`: no conflict. State-conditional analysis is part of the methodology; the candidate count is an implementation-level coverage parameter.
- `research/02-research-execution-handbook.md` §15 Phase 7: "状态条件化共振" specifies conditional analysis on viable single candidates without prescribing an exact count.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor or table change.

## Plateau-Patience Status

Was 0/20 entering iter-25. Iter-25 sets new best 466. Counter resets to 0/20.

## Next Hypothesis

- **A**: continue lifting `max-state-candidates` to 160 — diminishing returns expected, but worth testing.
- **B**: lower `MIN_STATE_SAMPLE` threshold for state slicing — currently 30 (in companion object). Smaller minimum admits more granular state buckets, which could capture more conditioned signals.
- **C**: `rollingCorrelationStats` window 30 -> 20 — shorter rolling correlation may register more recent regime-specific behavior.
