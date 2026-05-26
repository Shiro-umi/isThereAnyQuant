# research/sentiment_factor

短持仓因子频域共振研究的工程化产线工作区。autoresearch 主循环的所有产物落到这里。

## 目录约定

```
research/sentiment_factor/
├── scripts/                   # 脚本（autoresearch 的 Metric / Verify 依赖）
│   └── count-resonance-cards.sh
├── sql/                       # 因子计算用的 SQL 片段
├── notebook/                  # Kotlin Notebook (.ipynb) 调参环境
│                              #   .ipynb 由 .gitattributes 标 binary，不进 diff
│                              #   实际 cell 内容双写到 src/.../notebook/Explore.kts
├── out/                       # 研究产物
│   └── resonance_cards/       # 共振卡片 JSON（autoresearch Metric 的统计源）
└── reports/                   # 阶段性研究报告 .md（iter-NN.md 每轮一份）
```

## 方法学锚点

- **方法学 + 有效性论证**：[../01-research-methodology.md](../01-research-methodology.md)
- **执行手册（参数 / 公式 / JSON schema）**：[../02-research-execution-handbook.md](../02-research-execution-handbook.md)
- **数据表 / 因子定义 SSOT**：[../../docs/architecture/market-sentiment-factor-research-v0.3.md](../../docs/architecture/market-sentiment-factor-research-v0.3.md)
- **autoresearch 调用 prompt**：[../../temp/autoresearch-sentiment-factor-prompt.md](../../temp/autoresearch-sentiment-factor-prompt.md)

## Metric 脚本

`scripts/count-resonance-cards.sh` 是 autoresearch 的 Metric 与 Verify 共用脚本：

- 扫描 `out/resonance_cards/*.json`，统计 `"qualified": true` 的卡片数。
- 一张卡片 = 一个 `(因子, 目标 Y, horizon, 频带, 状态)` 组合的研究结论。
- `qualified=true` 的 12 项硬条件由执行手册 §12.4 定义（mean_coherence > 0.5、coverage > 30%、phase_std < π/4、lead_relation_stable、lead_days 在 horizon 区间、oos_ic > 0.05、hit_rate > baseline+5%、top_bottom_spread 稳定为正、q < 0.1、状态内 N ≥ 60/80、filtfilt vs lfilter 一致）。
- 空目录或无 qualified 卡片时输出 `0`，不会 crash。

手动验证：

```bash
cd research/sentiment_factor
./scripts/count-resonance-cards.sh   # 应该输出 0
```

## 共振卡片命名约定

```
out/resonance_cards/{factor}__{Y}__h{horizon}__{band}__{state_id}.json
```

例如：

```
C4__Y2__h3__F2a__trend=mid,disp=low,vol=high.json
A5-C1__Y3__h1__F1a__trend=high,disp=high,vol=high.json
```

JSON schema 见执行手册 §12.2 / §12.3。

## 推进顺序

按执行手册 §15 / autoresearch prompt §二「迭代推进顺序建议」分八个 Phase 渐进展开，禁止一开始就全量跑。每个 Phase 结束必须：

1. 编译通过（`./gradlew :database:compileKotlin :database:test`）
2. 输出当前累计 qualified 卡片数（`./scripts/count-resonance-cards.sh`）
3. 提交一次原子 commit
4. 写一份 `reports/iter-NN.md` 摘要本轮假设、Metric 变化、下一轮方向
