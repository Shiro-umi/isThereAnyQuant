# Short-Horizon Sentiment Resonance Final Summary

## 13.1 总览

- Research entry: `./gradlew :strategy-server:research:runResearch --console=plain --quiet`
- Card directory: `research/sentiment_factor/out/resonance_cards`
- Cards written: 1571
- Qualified A cards: 288
- Level distribution: A 288, B 720, C 380, Reject 183
- Factor types: single 1349, pair_diff 126, pair_product 56, pair_ratio 40
- Qualified factor types: single 284, pair_diff 3, pair_product 1

Current dominant blockers among rejected/non-A cards:

- Gate 9 (`q_value < 0.1`): 675
- Gate 5 (`lead_days_lag` inside horizon range): 608
- Gate 7 (`hit_rate > baseline + 0.05`): 583

## 13.2 单因子榜

Top qualified single factors by A-card count:

| Factor | A Cards |
|---|---:|
| B4 | 39 |
| A7 | 37 |
| A1 | 37 |
| B5 | 26 |
| D3 | 23 |
| D4 | 23 |
| A2 | 21 |
| D1 | 18 |
| B6 | 17 |
| B7 | 14 |

Strongest qualified target/horizon/band buckets:

| Target | Horizon | Band | A Cards |
|---|---:|---|---:|
| Y1 | 3 | F2a | 121 |
| Y2 | 3 | F2a | 83 |
| Y3 | 3 | F2a | 25 |
| Y2 | 5 | F2a | 16 |
| Y1 | 5 | F2a | 7 |
| Y3 | 1 | F2a | 7 |

Top A cards by OOS IC:

| Factor | Target | Horizon | Band | State | OOS IC | HitRate | q |
|---|---|---:|---|---|---:|---:|---:|
| B4 | Y2 | 3 | F2a | trend=mid,disp=mid,vol=low | 0.9818 | 0.8947 | 0.0781 |
| D3 | Y1 | 3 | F2a | trend=low,disp=mid,vol=low | 0.9763 | 0.9524 | 0.0644 |
| B4 | Y2 | 3 | F2a | trend=low,disp=mid,vol=mid | 0.9679 | 1.0000 | 0.0180 |
| B4 | Y2 | 3 | F2a | trend=low,disp=high,vol=mid | 0.9652 | 0.9200 | 0.0269 |
| A1 | Y1 | 3 | F2a | trend=high,disp=mid+high,vol=mid | 0.9651 | 0.9615 | 0.0242 |
| A7 | Y1 | 3 | F2a | trend=high,disp=mid+high,vol=mid | 0.9651 | 0.9615 | 0.0218 |

## 13.3 因子对榜

Qualified pair cards:

| Pair | Form | Target | Horizon | Band | State | Delta IC | Top-bottom Spread | HitRate | q |
|---|---|---|---:|---|---|---:|---:|---:|---:|
| A7_diff_D4 | pair_diff | Y1 | 3 | F2a | trend=all,disp=all,vol=all | 1.6504 | 0.7961 | 0.8181 | 0.0075 |
| A1_diff_D4 | pair_diff | Y1 | 3 | F2a | trend=all,disp=all,vol=all | 1.6504 | 0.7961 | 0.8181 | 0.0075 |
| A6_diff_A5 | pair_diff | Y3 | 3 | F2a | trend=all,disp=all,vol=all | 0.1498 | 0.8671 | 0.8099 | 0.0048 |
| B4_product_A2 | pair_product | Y2 | 1 | F2a | trend=all,disp=all,vol=all | 0.0218 | 0.6558 | 0.7140 | 0.0083 |

## 13.4 状态共振图谱

Top states by qualified A cards:

| Market State | A Cards | Main Reading |
|---|---:|---|
| trend=all,disp=all,vol=all | 30 | 全状态基线有效，主要来自强单因子与已通过因子对 |
| trend=low,disp=high,vol=high | 18 | 低趋势、高分化、高量能下，短周期情绪修复信号较集中 |
| trend=high,disp=low+mid+high,vol=high | 17 | 高趋势、放量状态下，Y1/Y2 的 3 日延续更集中 |
| trend=low,disp=mid,vol=mid | 17 | 低趋势但分化和量能中性时，F2a 领先关系更稳定 |
| trend=high,disp=low+mid,vol=mid | 16 | 高趋势、中性量能下，短线延续卡片较多 |
| trend=low,disp=high,vol=mid | 16 | 低趋势、高分化但未极端放量，修复/分化信号并存 |

## 13.5 共振卡片附录

Full JSON cards are written under `research/sentiment_factor/out/resonance_cards`.

Representative A cards:

```text
因子: B4
目标: Y2 / h=3
最强频带: F2a
状态: trend=mid,disp=mid,vol=low
OOS IC: 0.9818
HitRate: 0.8947
q_value: 0.0781
结论等级: A
```

```text
因子: A7_diff_D4
类型: pair_diff
目标: Y1 / h=3
最强频带: F2a
状态: trend=all,disp=all,vol=all
Delta IC: 1.6504
HitRate: 0.8181
q_value: 0.0075
结论等级: A
```

## Integrity Notes

- Study computes raw `ResonanceMetric` values only.
- `qualified` and `conclusion_level` come from the existing `ResonanceEvaluator` wiring.
- No evaluator thresholds, signal primitives, output contracts, production sentiment strategy flow, or database fact-table semantics were changed.
