# Iter 30 - Rolling Correlation Window 30 → 40

## Variable
`rollingCorrelationStats` window 30 → 40.

## Pre-Iteration Audit
- **state-window 100→120** (556→482, -74). Too wide.
- **rolling window 30→20** (556→450, -106). Too narrow.

## Metric
556 → **575** (+19). Cards written 2216 → 2376.

## Plateau
Was 2/20; new best resets to 0/20.

## Mechanism
Longer rolling correlation window stabilizes `rolling_corr_stability` and `rolling_corr_mean`, which feeds into the `discoveryFunnel` filter. More cards pass the funnel as stable, and more state-conditional analysis is triggered downstream.
