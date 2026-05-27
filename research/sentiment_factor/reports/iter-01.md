# Iter 01 - Phase 1 Research Entry Baseline

## Phase / Variable

- Phase: A1, research input and runnable Verify chain baseline.
- Variable changed: infrastructure entry only. No research metric formula, signal primitive, product contract, or evaluator logic changed.

## Business Flow

`stock_daily_data / tushare_limit_list_d / calendar` remain upstream facts. This iteration adds `sentiment_factor_daily` as a research-only daily wide fact table for the later Study input. Research outputs still go to workspace files under `research/sentiment_factor/out`, and qualified card decisions still come only from `ResonanceEvaluator`.

## Metric

- Before: 0 qualified cards.
- After: 0 qualified cards.
- Verify command: `rm -rf research/sentiment_factor/out/resonance_cards/*.json && ./gradlew :strategy-server:research:runResearch --console=plain --quiet && cd research/sentiment_factor && ./scripts/count-resonance-cards.sh`

Phase 1 intentionally keeps the FakeStudy baseline, so the single skeleton card is rejected and the metric remains 0.

## Gate Failure Top 3

No meaningful gate ranking yet. The only card is the skeleton empty metric, so fail-closed behavior is expected and not a research signal.

## First Qualified Cards

None.

## SSOT Check

- `research/01-research-methodology.md`: no conflict; no Study-side methodology implemented yet.
- `research/02-research-execution-handbook.md`: Phase 1 run entry and `sentiment_factor_daily` table are aligned.
- `docs/architecture/market-sentiment-factor-research-v0.3.md`: factor names and table purpose are aligned.

The database runtime reference was updated to mark `sentiment_factor_daily` as research input only, not a production strategy confirmation table.

## Next Hypothesis

Implement Phase 2 A-group factor population from factual daily K-line and limit-list inputs, keeping Y labels at T-day raw values and leaving future horizon labels to the Study stage.
