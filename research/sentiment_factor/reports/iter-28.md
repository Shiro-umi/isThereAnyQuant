# Iter 28 - State Window 60 → 80

## Phase / Variable

- Variable: state-window param default 60 → 80. Wider lookback for state bucket assignment.

## Pre-Iteration Audit

- **MIN_STATE_SAMPLE 80→100** (520→460, -60). Too aggressive a floor folds too many states into the global default.

## Metric

- Before (iter-27): 520 → After (iter-28): **523** (+3).
- Cards written: 1974 → 1950.

## Plateau-Patience

Was 1/20. New best. Counter resets to 0/20.
