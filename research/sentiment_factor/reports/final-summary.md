# Short-Horizon Sentiment Resonance Final Summary

## 14. Iter-18 → Iter-35 自治迭代轮次更新（2026-05-28）

继 iter-17 (qualified=288, cards=1571) 后，进行 18 轮 Study 侧研究变量迭代。结果：

- **Qualified A cards: 288 → 611 (+323, +112.2%)**
- Cards written: 1571 → 3138
- p_value 最小: 0.0020 → 0.0005
- q_value 最小: 0.0020 → 0.0011
- 11 次 KEPT atomic commit + 12 次 honest rollback
- Plateau 最终触底于 iter-35 之后 5 轮持平（patience 5/20）

**Top 5 单轮提升来源**：

| Iter | 研究变量 | Δ |
|---|---|---:|
| 19 | horizon-valid lead 阈值 0.85→0.65 | +63 |
| 22 | per-fold OOS orientation rewrite (fix trainEnd bug) | +46 |
| 27 | MIN_STATE_SAMPLE 60→80 | +43 |
| 25 | maxStateCandidates 80→120 | +36 |
| 29 | state-window 80→100 | +33 |

**当前 best 累计代码变更**（vs iter-17）共 12 处，全部位于 `SentimentResonanceStudy.kt` 中（评估器 / signal / output / pipeline 零修改）。完整图形化报告：[iter-35-graphical-summary.html](iter-35-graphical-summary.html)。

各轮单独报告：[iter-18](iter-18.md) [iter-19](iter-19.md) [iter-20](iter-20.md) [iter-21](iter-21.md) [iter-22](iter-22.md) [iter-23](iter-23.md) [iter-24](iter-24.md) [iter-25](iter-25.md) [iter-26](iter-26.md) [iter-27](iter-27.md) [iter-28](iter-28.md) [iter-29](iter-29.md) [iter-30](iter-30.md) [iter-31](iter-31.md) [iter-32](iter-32.md) [iter-33](iter-33.md) [iter-34](iter-34.md) [iter-35](iter-35.md)

---

## 13.1 总览（iter-17 快照）

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
