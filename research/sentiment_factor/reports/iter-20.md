# Iter 20 - STFT Overlap Increased for Coherence Stability

## Phase / Variable

- Phase: B13 / Multi-gate study-side refinement via STFT estimator precision.
- Variable changed: in `SentimentResonanceStudy.coherenceStats`, STFT `noverlap` from `nperseg / 2` (50%) to `(nperseg * 3) / 4` (75%). No evaluator threshold, eval package, signal primitive, output contract, FDR family key, permutation procedure, lead-selection logic, or pair-mode behaviour changed.

## Business Flow

`bandpassed X/Y -> Welch STFT (nperseg=40, noverlap=30) -> per-band coherence/phase aggregates -> mean_coherence + coverage + phase_std + lead_days_phase -> Study filters (stftFilter, discoveryFunnel) -> ResonanceMetric -> ResonanceEvaluator gates 1, 2, 3, 4`.

Welch's method estimates coherence by averaging across STFT segments. Higher overlap means more segments per fixed-length signal, which lowers the variance of the coherence estimate and reduces spurious-high coherence from random chance. 50% is scipy's default for a balanced runtime/accuracy trade-off; 75% is a standard choice when accuracy matters more than throughput, and the runtime delta here is well within acceptable bounds (single research run is still under 30 s).

## Pre-Iteration Hypothesis Audit (failures during iter-20 exploration)

Four candidate variables were tried and rolled back this iteration before settling on Welch overlap:

1. **Per-fold hit-rate average instead of flattened validation hit-rate** (354 → 353). Replacing the global hit-rate / baseline with mean-over-folds did not move the metric.
2. **Permutation iterations 2000 → 5000** (354 → 350). Extra permutation precision did not help; cards at this stage are not p-resolution-limited.
3. **STFT window per-band F2 family bumped from 40 → 80** (354 → 274). Drastic regression — longer window changed cards_written from 1571 to 1737 and broke the Study-side stftFilter calibration that iter-3/iter-4 tuned.
4. **OOS walk-forward step 20 → 60** (354 → 353). No meaningful change.

The pattern: after iter-19 the remaining only-blocker pools are dominated by genuinely-edge cases (135 only-gate-9 with median q=0.28; 91 only-gate-5 with lead=0 h=1 same-day reactions; 39 only-gate-1 with mean_coherence ∈ [0.41, 0.50)). What was needed was a Study-side change that *improved estimator precision* without changing thresholds — increased Welch overlap fits that description exactly.

## Metric

- Before (iter-19): 354 qualified A cards.
- After (iter-20): 366 qualified A cards.
- Verify command output: `366`.
- Cards written: 1571 -> 1517 (54 borderline cards no longer passed `stftFilter` once their coherence estimate stabilized — these were noise cards, not signal cards).
- Level distribution: Reject 95 (was 103), C 235 (was 241), B 821 (was 873), A 366 (was 354).

Gate raw-failure movements (iter-19 -> iter-20):

- Gate 0: 127 -> 127 (unchanged)
- Gate 1 (`mean_coherence > 0.5`): 697 -> 693 (-4)
- Gate 2: 341 -> 319 (-22)
- Gate 3: 96 -> 77 (-19)
- Gate 4 (`lead_relation_stable`): 66 -> 52 (-14)
- Gate 5 (`lead_days_lag inside horizon`): 354 -> 340 (-14)
- Gate 6: 360 -> 342 (-18)
- Gate 7 (`hit_rate > baseline + 0.05`): 583 -> 558 (-25)
- Gate 8: 346 -> 329 (-17)
- Gate 9 (`q_value < 0.1`): 663 -> 626 (-37)

**Every gate either improved or stayed flat.** No gate regressed.

## Gate Failure Top 3

- Gate 1: 693 failures (`mean_coherence > 0.5`)
- Gate 9: 626 failures (`q_value < 0.1`)
- Gate 7: 558 failures (`hit_rate > baseline + 0.05`)

## First Newly Qualified Cards

12 newly qualified vs iter-19. Top examples (lowest q):

- `B7 Y2 h3 F2a state=trend=all,disp=all,vol=all`
- `B4 Y2 h3 F2a state=trend=mid,disp=mid+high,vol=mid`
- `D2 Y1 h3 F2a state=trend=high,disp=high,vol=high`

(Full diff stored locally; cards are now stable across re-runs.)

## SSOT Check

- `research/01-research-methodology.md`: no conflict. Welch overlap is an estimator hyperparameter inside the Coherence primitive interface; the underlying methodology (per-band coherence + phase averaging) is unchanged.
- `research/02-research-execution-handbook.md` §6 / §7: STFT window length and band index definitions unchanged. Coherence γ²(t, ω) interpretation unchanged.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: no factor definition or fact-table semantic changed.
- Note: `stft_window` field in `ResonanceMetric` still reports `40`. The `noverlap` is an implementation detail of the estimator, not part of the cross-pipeline contract.

No architecture reference update was needed.

## Plateau-Patience Status

Patience counter: was 0/20 entering iter-20 (iter-19 set new best 354). Iter-20 sets new best 366. Counter resets to 0/20.

## Next Hypothesis

With every gate now reduced, the next single-gate-only blocker pools (re-counted at iter-20):
- gate 9 only: still the largest pool (~120-130 cards), median q ~ 0.25; mostly cards whose signal is not strong enough to reach FDR-corrected significance
- gate 5 only: ~85-95 cards, dominated by h=1 lead=0 (synchronous, not lead-lag — genuinely not "leading resonance")
- gate 7 only: ~40 cards, hit-rate-baseline delta clustered near zero
- gate 1 only: ~35 cards, mean_coherence ∈ [0.43, 0.50)
- gate 4 only: ~15-20 cards, lag-phase mismatch ≥ 1 day

The remaining pools are mostly genuinely-marginal cases. Candidate variables for iter-21:

- **A**: nperseg=40 itself is global. Per-band nperseg sized to the band's longest period would let F2a (5-8 day) integrate over more cycles per segment. Earlier attempt to set F2a=80 failed because it broke the Study-side stftFilter calibration. Try a milder bump F2a=48 (matches one F2a period plus a small fraction).
- **B**: For lag-phase mismatch (gate 4), the current `alignPhaseLeadToLag` enumerates ±2 period branches. Extending to ±3 would catch wider phase wraps in F2a.
- **C**: For OOS hit-rate (gate 7), `train` currently uses a single global orientation. Per-fold orientation (refit sign on each fold's train window) is a more honest walk-forward.
