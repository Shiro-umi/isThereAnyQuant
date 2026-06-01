# research/sentiment_factor

短持仓因子频域共振研究（trend topic）的工程化产线工作区。`:strategy-server:research` 的运行产物落到这里。

## 目录约定

```
research/sentiment_factor/
├── scripts/                   # 脚本（Metric / Verify 依赖）
│   └── count-resonance-cards.sh
├── sql/                       # 因子计算用的 SQL 片段
├── notebook/                  # Kotlin Notebook (.ipynb) 调参环境（.ipynb 标 binary，不进 diff）
├── out/                       # 研究产物（共振卡片 JSON）—— 已 gitignore，仅本地
├── dataset_cache/             # 装配缓存 —— 已 gitignore，仅本地
├── baseline/                  # 实盘 baseline 快照 —— 已 gitignore，仅本地
└── tuner/                     # tuner trace / result —— 已 gitignore，仅本地
```

> 本工作区只放**工程脚手架**（脚本、占位、SQL 片段）与**本地运行产物**。
> 研究方法学、逐因子有效性结论、逐轮调优报告等"我们怎么研究"的核心资产**不在公开仓**，
> 统一归 private submodule，见下「研究资产位置」。

## 研究资产位置（private，不开源）

承载研究方法与结论的文档统一在 `private/research-docs/`：

- **trend 研究设计文档（SSOT）**：`private/research-docs/sentiment-next-day-formula.html`
- **研究方法学**：`private/research-docs/sentiment/01-research-methodology.md`
- **执行手册（参数 / 公式 / JSON schema / 12 项硬条件）**：`private/research-docs/sentiment/02-research-execution-handbook.md`
- **最终成果总结（合格卡片榜 / 状态共振图谱）**：`private/research-docs/sentiment/final-summary.md`
- **信号原语 scipy 数值对照**：`private/research-docs/sentiment/signal-validation.md`
- **图形化总结**：`private/research-docs/sentiment/iter-35-graphical-summary.html`

研究成果索引与各 topic 文档清单见 research-pipeline-architect skill 的 references。

## Metric 脚本

`scripts/count-resonance-cards.sh` 扫描 `out/resonance_cards/*.json`，统计 `"qualified": true` 的卡片数（一张卡片 = 一个 `(因子, 目标 Y, horizon, 频带, 状态)` 组合的研究结论）。空目录或无 qualified 卡片时输出 `0`。合格判定的硬条件由执行手册定义（见上「研究资产位置」）。

```bash
cd research/sentiment_factor
./scripts/count-resonance-cards.sh
```
