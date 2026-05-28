# Iter 26 - State Pool 120 → 160

## Phase / Variable

- Variable: `max-state-candidates` 120 → 160. Same lever as iter-25, pushed once more before diminishing returns appear.

## Metric

- Before (iter-25): 466 → After (iter-26): **477** (+11).
- Cards written: 2176 → 2288.
- Verify: `477`.

## SSOT

State candidate cap; methodology-neutral.

## Plateau-Patience

New best. Counter resets to 0/20.

## Next Hypothesis

- Push to 200 if 160→? still gains; otherwise switch to `MIN_STATE_SAMPLE` lowering or `rollingCorrelationStats` window adjustment.
