# Iter 33 - STFT Coverage Floor 0.15 → 0.10

## Variable
`stft-coverage-floor` 0.15 → 0.10. Lower bar for "what counts as coherent enough at this band" in the Study filter.

## Pre-Iteration Audit
- **fdr-family → target-horizon-band-state** (600→596). Family decomposition still hurts after the per-fold orientation fix.
- **stft-coherence-floor 0.40→0.35** (600→597). Too lenient lets in noisy cards.

## Metric
600 → **602** (+2). Cards written 2701 → 2770.

## Plateau
Was 2/20; new best resets to 0/20.

## Note
Diminishing returns are very pronounced now. Further plateaus are likely near.
