# 短持仓因子频域共振研究执行手册 v1.0

## 文档定位

本文档是 `01-research-methodology.md` 的配套执行手册。

- **方法学文档**回答"为什么这么做"。
- **本手册**回答"具体怎么做，每一步用什么参数，输出什么"。

读者拿到本手册应该可以直接进入工程实现，不需要二次解读方法论。

执行流程分为八个阶段：

```text
阶段一  数据准备 (sentiment_factor_daily 宽表)
阶段二  标签生成 (Y_next_* 与辅助标签)
阶段三  预处理 (winsorize / 标准化 / 去趋势)
阶段四  短周期发现 (带通 + 滚动相关)
阶段五  STFT 频域确认
阶段六  样本外验证 (walk-forward)
阶段七  因子对研究
阶段八  报告生成 (共振卡片 / 状态图谱)
```

---

## 1. 数据基础

### 1.1 因子宽表

落表名称：`sentiment_factor_daily`

字段：

```text
trade_date          日频交易日
A1 .. E2            38 个单因子在 T 日的取值
Y1_raw              T 日的市场加权平均涨幅
Y2_raw              T 日的上涨家数占比
Y3_raw              T 日的涨跌停净广度
Y_composite         综合情绪指标（按统一权重合成）
notes               备注（例如极端期标记）
```

落表只保存 T 日口径的 Y，**不在落表阶段提前错位**。T+h 标签在研究阶段动态生成。

### 1.2 涨跌幅归一化

统一压缩到 ±10% 等效坐标系：

```text
pct_chg_raw = close_qfq(T) / close_qfq(T-1) - 1
pct_norm    = pct_chg_raw / 个股预期涨跌幅上限 × 0.10
```

| 股票类型 | 预期上限 | 归一化系数 |
|---|---:|---:|
| 主板 | 10% | × 1.00 |
| 创业板 | 20% | × 0.50 |
| 科创板 | 20% | × 0.50 |
| 北交所 | 30% | × 0.33 |
| ST / *ST | 5% | × 2.00 |

类型优先级（一只股票同时符合多个时使用更严格的系数）：

```text
ST > 北交所 > 科创板 > 创业板 > 主板
```

### 1.3 样本过滤

| 规则 | 处理 |
|---|---|
| 上市 ≤ 20 个交易日的新股 / 次新股 | 剔除 |
| 当日停牌（无成交量） | 剔除 |
| `delist_date` 前 30 个交易日 | 剔除 |
| 当日异常涨跌幅（系统数据错误） | 剔除个股当日记录 |
| ST 股 | 保留，使用 ST 归一化系数 |

### 1.4 加权口径

截面均值默认使用**流通市值加权**：

```text
weighted_mean(x) = Σ(x_i × mv_circ_i) / Σmv_circ_i
```

Y 分量和因子的截面计算口径必须一致（都用流通市值加权或都用等权），不能混用。

---

## 2. 目标 Y 的具体定义

### 2.1 主目标

```text
Y_next_1(t) = Y(t+1)
Y_next_3(t) = mean(Y(t+1), Y(t+2), Y(t+3))
Y_next_5(t) = mean(Y(t+1), ..., Y(t+5))
```

其中 `Y` 是 `Y1 / Y2 / Y3` 中的一个，三个分量独立研究。

### 2.2 辅助标签

```text
Y_max_3(t)       = max(Y(t+1:t+3))
Y_min_3(t)       = min(Y(t+1:t+3))
Y_max_5(t)       = max(Y(t+1:t+5))
Y_min_5(t)       = min(Y(t+1:t+5))
Y_drawdown_5(t)  = Y_max_5(t) - Y(t+5)          # 冲高回落
Y_direction_3(t) = sign(Y_next_3(t))            # 分类目标
Y_direction_5(t) = sign(Y_next_5(t))            # 分类目标
```

研究阶段动态生成，不入宽表。

### 2.3 Y_composite 的合成口径

```text
Y_composite = w1·zscore(Y1) + w2·zscore(Y2) + w3·zscore(Y3)
默认 w1 = w2 = w3 = 1/3
zscore 使用 252 日滚动窗口
```

Y_composite 仅用于报告展示，不作为主研究目标。

---

## 3. 频带定义

| 频带 | 周期 (day) | 频率 ω (cycle/day) | 角色 |
|---|---:|---:|---|
| F1a | 2–3 | 0.333 – 0.500 | 二级核心 |
| F1b | 3–5 | 0.200 – 0.333 | **一级核心** |
| F2a | 5–8 | 0.125 – 0.200 | **一级核心** |
| F2b | 8–10 | 0.100 – 0.125 | 辅助 |
| F3 | 10–20 | 0.050 – 0.100 | 背景（不入主排名） |
| F4 | > 20 | < 0.050 | 背景（不入主排名） |

短持仓研究的主排名只使用 F1b / F2a，F1a 作为二级核心，F2b 辅助观察。

---

## 4. 预处理

### 4.1 极值处理

滚动 winsorize（截断而非删除）：

```text
window = 252 个交易日
lower(t) = rolling_quantile(X, 252)[1%]
upper(t) = rolling_quantile(X, 252)[99%]
X_clip(t) = clip(X(t), lower(t), upper(t))
```

### 4.2 滚动标准化（双版本）

```text
Z_60(t)  = [X_clip(t) - mean(X_clip[t-59:t])]  / std(X_clip[t-59:t])
Z_120(t) = [X_clip(t) - mean(X_clip[t-119:t])] / std(X_clip[t-119:t])
```

### 4.3 去趋势版本

```text
Z_60_detrend(t)  = Z_60(t)  - EMA(Z_60, span=20)(t)
Z_120_detrend(t) = Z_120(t) - EMA(Z_120, span=20)(t)
```

### 4.4 使用优先级

主发现阶段优先级（从高到低）：

```text
1. Z_60_detrend
2. Z_60
3. Z_120_detrend
4. Z_120
```

每张共振卡片必须记录使用的标准化版本（字段 `norm_version`）。

### 4.5 缺失值处理

| 情况 | 处理 |
|---|---|
| 单日缺失 | 前向填充最多 1 日 |
| 连续缺失 ≥ 2 日 | 该窗口剔除 |
| 因子前期不足窗口长度 | 不参与该阶段计算 |
| 二值因子缺失（D5 / D7 等） | 视业务含义填 0 或剔除，结论标注 |

---

## 5. 阶段四：短周期发现

### 5.1 带通滤波

```text
X_B(t) = bandpass(X, B)
Y_B(t) = bandpass(Y, B)
B ∈ {F1a, F1b, F2a, F2b}
```

**滤波器选择**：

```text
Butterworth 4 阶
样本内发现阶段：filtfilt（双向，零相位）
样本外验证阶段：lfilter（单向因果，强制）
```

发现阶段的 filtfilt 结果和验证阶段的 lfilter 结果都要保存，作为"是否依赖未来函数"的对照。

### 5.2 滚动相关

```text
Corr_B,h(t) = corr(X_B[t-w+1 : t], Y_B[t-w+h+1 : t+h])
```

窗口设置：

| 窗口 w | 用途 |
|---|---|
| 20 日 | 极短线敏感 |
| **30 日** | **主发现窗口** |
| 40 日 | 稳健短窗 |

### 5.3 发现指标

| 指标 | 定义 |
|---|---|
| `rolling_corr_mean` | 整段历史 rolling corr 的均值 |
| `rolling_corr_hit` | rolling corr 与目标符号一致的窗口占比 |
| `rolling_corr_stability` | rolling corr 符号一致性（衡量相关方向是否稳定） |
| `recent_corr` | 最近 30 日 rolling corr 强度 |
| `corr_decay` | 历史 corr - 近期 corr，衡量是否快速衰减 |

### 5.4 候选池入选规则

```text
进入 STFT 确认阶段的条件:
  |rolling_corr_mean| > 0.10
  AND rolling_corr_stability > 0.55
  AND recent_corr 方向与 rolling_corr_mean 一致
```

这一阶段**召回优先**，宁可放宽阈值多收候选，由 STFT 阶段做精度筛选。

---

## 6. 阶段五：STFT 频域确认

### 6.1 STFT 参数

| 参数 | 取值 |
|---|---|
| 窗口长度 | **30 / 40 / 60 日（多窗口交叉确认）** |
| 步长 | 1 日 |
| 窗函数 | Hann |
| n_fft | 64 |
| 主窗口 | **40 日** |

三个窗口的角色：

```text
30 日 STFT  -> 短线敏感性确认（必要时降级用）
40 日 STFT  -> 主确认窗口（结论以此为准）
60 日 STFT  -> 稳健性确认（必要时升级用）
```

只在 STFT-30 显著但 STFT-40 / 60 都不显著的因子，要降级到 B 类。

### 6.2 相干性与相位

逐时间点 t、逐频率 ω 计算：

```text
γ²(t, ω) = |S_xy(t, ω)|² / [S_xx(t, ω) · S_yy(t, ω)]
φ(t, ω)  = arg(S_xy(t, ω))
```

其中 `S_xy / S_xx / S_yy` 是 STFT 互谱与功率谱。

### 6.3 频带聚合

对每个频带 B：

```text
Coh_B(t) = Σ_{ω ∈ B} N(ω) · γ²(t, ω) / Σ_{ω ∈ B} N(ω)
Phase_B(t) = circular_mean(φ(t, ω), ω ∈ B)
```

`N(ω)` 是该频率下的有效观测数。频带宽度小的（如 F1a 只有 2–3 个频点）必须按频点数加权平均，避免单点主导。

### 6.4 STFT 评价指标

| 指标 | 定义 | 阈值（A 类候选） |
|---|---|---|
| `mean_coherence` | `mean(Coh_B(t))` 整段历史均值 | > 0.5 |
| `max_coherence` | `max(Coh_B(t))` 局部最大，仅辅助 | — |
| `coherence_coverage` | `Coh_B(t) > 0.5` 的时间占比 | > 30% |
| `phase_std` | `Phase_B(t)` 的圆方差（standard deviation on a circle） | < π/4 |
| `lead_days` | 由 `phase / (2πω_center)` 换算，并和 lag correlation 交叉验证 | 见 §6.5 |

### 6.5 领先天数双方法交叉验证

**方法 A（相位换算）**：

```text
lead_days_phase = mean(Phase_B(t)) / (2π · ω_center)
ω_center 为频带 B 的中心频率
```

**方法 B（时域 lag correlation）**：

```text
对 lag k ∈ {-5, -4, ..., 4, 5}:
    c_k = corr(X_B[t], Y_B[t + k])
lead_days_lag = argmax_k(c_k)
```

**裁决规则**：

```text
主结论以 lead_days_lag 为准（更鲁棒）。
lead_days_phase 用作一致性交叉验证。
当 |lead_days_phase - lead_days_lag| > 1 天时，
标记 lead_relation_stable = false，因子降级为 B 类。
```

### 6.6 领先天数 ↔ horizon 匹配

| horizon | 有效 lead_days 区间 |
|---|---:|
| h = 1 | 0.5 – 1.5 |
| h = 3 | 1 – 3 |
| h = 5 | 1 – 5 |

不在区间内的因子：

- 同步（lead_days ∈ [-0.5, 0.5]）：降级为 C 类（解释型）。
- 滞后（lead_days < 0）：Reject。
- 领先超过对应 horizon：降级为背景信号。

---

## 7. 阶段六：样本外验证（walk-forward）

### 7.1 滚动窗口结构

```text
train_window = 500 个交易日
valid_window = 120 个交易日
step         = 20  个交易日
```

流程：

```text
1. 用过去 500 日做候选选择、参数估计、阈值设定。
2. 在紧接其后的 120 日做纯前向预测。
3. 向前滚动 20 日，重复 1-2。
4. 直到样本结束。
```

每次滚动产出一组 OOS 指标，最终汇总为分布（均值、中位数、IQR）。

### 7.2 OOS 指标

| 指标 | 定义 | A 类阈值 |
|---|---|---|
| `oos_ic` | 因子值与未来 Y 的 Pearson IC | > 0.05 |
| `rank_ic_oos` | Spearman 排序 IC | > 0.04 |
| `hit_rate` | 方向命中率 (sign 一致占比) | > baseline + 5% |
| `top_bottom_spread` | 因子值前 20% 与后 20% 分组未来 Y 差异 | 稳定为正 |
| `oos_r2` | 连续目标解释能力 | 辅助 |
| `signal_turnover` | 信号跳变程度 | 辅助 |
| `drawdown_capture` | 是否能识别未来退潮 | 辅助 |

**baseline** 定义：

```text
对 Y_next_h，baseline = max(P(Y > 0), P(Y < 0))
即"永远猜涨"或"永远猜跌"的命中率。
```

### 7.3 滤波因果性对照

每个因子在样本外验证阶段必须跑两遍：

- 一遍用 `filtfilt`（带未来信息）。
- 一遍用 `lfilter`（纯因果）。

如果 `filtfilt` 的 OOS IC 显著高于 `lfilter`：

```text
该因子的频域共振依赖未来信息，Reject。
```

如果两者基本一致，因子才被认为是"真实可用"的。

---

## 8. 阶段七：因子对研究

### 8.1 候选池剪枝

不做盲目全组合（703 × 3 = 2109）。剪枝规则：

```text
候选因子对必须满足:
  Zi 和 Zj 均已入选 A 类或 B 类，
  或两者业务含义明确有"差值 / 乘积"语义。
```

剪枝后预期候选数 ≈ 200 对。

### 8.2 组合形式

| 形式 | 公式 | 适用场景 |
|---|---|---|
| 差值型 | `Zi - Zj` | 背离、风格切换、热度质量差 |
| 乘积型 | `Zi · Zj` | 同向增强、共振放大 |
| 稳健比值 | `Zi / (\|Zj\| + ε)`，ε = 0.5 或 1.0 | 仅辅助探索，不进第一批 |

**禁止**直接使用 `Zi / Zj`（分母接近零会数值爆炸）。

### 8.3 重点候选清单

差值型重点：

| 组合 | 业务含义 |
|---|---|
| A5 - C1 | 涨停热度 vs 封板质量 |
| C7 - C4 | 断板率 vs 涨停股赚钱效应 |
| A7 - A9b | 小盘 vs 大盘 |
| A1 - A3 | 涨幅 vs 量能 |
| B4 - A1 | 广度 vs 加权涨幅 |
| B3' - B4 | 分化变化 vs 上涨广度 |

乘积型重点：

| 组合 | 业务含义 |
|---|---|
| C4 × B4 | 涨停股赚钱效应 × 市场广度 |
| B7 × C4 | 广度持续性 × 涨停赚钱效应 |
| C7 × B6 | 断板率 × 杀跌股占比 |
| B3' × B4 | 分化变化 × 上涨广度 |
| C3 × C6 | 连板梯队 × 连板赚钱效应 |

### 8.4 增量贡献检验

**基线（base）定义**：

```text
base = PCA(当前已入选 A/B 类因子)的前 5 个主成分
```

用 PCA 主成分而不是单因子，是为了避免"同族因子互相 cannibalize 增量"。

**简单增量**：

```text
IncrementScore_ij = Score(Gij) - max(Score(Zi), Score(Zj))
```

**回归增量**：

```text
基础模型:  Y_B(t+h) = α + β_base · base + ε
加入组合:  Y_B(t+h) = α + β_base · base + β3 · Gij_B(t) + ε

比较:
  ΔOOS_IC = OOS_IC(加入组合) - OOS_IC(基础)
  ΔTopBottomSpread
  ΔHitRate
  β3 方向稳定性 (滚动窗口内 β3 同号占比)
```

### 8.5 因子对入选条件

```text
1. Score(Gij) > max(Score(Zi), Score(Zj))
2. ΔOOS_IC > 0.02
3. ΔTopBottomSpread > 0
4. β3 方向稳定（同号占比 > 70%）
5. Block permutation 显著 (q < 0.1)
6. 状态内样本数 ≥ 80（因子对的门槛比单因子高）
```

不满足的因子对仅作为"解释性组合"，不进 A 类。

---

## 9. 状态条件分析

### 9.1 状态网格

三维状态网格（27 状态）：

| 维度 | 因子 | 分档 |
|---|---|---|
| 趋势 | D4 或 A1 近 20 日分位 | 低 / 中 / 高 |
| 分化 | B3 或 B3' 近 20 日分位 | 低 / 中 / 高 |
| 量能 | EMA(A3, span=5) 近 20 日分位 | 缩量 / 平量 / 放量 |

分档点：

```text
低 / 缩量 : 分位 < 33%
中 / 平量 : 33% ≤ 分位 < 67%
高 / 放量 : 分位 ≥ 67%
```

### 9.2 状态窗口

```text
主状态窗口: 60 日 (短持仓研究主用)
辅助窗口:   20 日 (敏感切换捕捉)
背景窗口:   252 日 (长期环境，不直接决定排名)
```

### 9.3 状态内样本数门槛

```text
N < 30:           不输出结论
30 ≤ N < 60:      仅作"观察"，不进 A 类
60 ≤ N < 80:      单因子可进 A 类，因子对不可
N ≥ 80:           单因子和因子对都可进 A 类
```

### 9.4 状态合并规则（样本不足时）

层级化合并，按以下优先级：

```text
第一优先: 合并分化度相邻档 (低↔中、中↔高)
第二优先: 合并量能相邻档 (缩量↔平量、平量↔放量)
最后:     合并趋势相邻档 (低位↔中位、中位↔高位)
```

合并后的状态在卡片上明确标注（如 `分化=低+中`、`量能=平+放量`），并记录合并原因（样本不足）。

合并的优先级反映维度的"可塑性"：

- 分化度档位的差异最连续，合并后业务含义损失小。
- 趋势档位的差异最显著（低位和高位是不同的市场），合并代价高。

---

## 10. 显著性检验

### 10.1 Block Permutation

**block_size 按频带自适应**：

| 频带 | block_size (day) |
|---|---:|
| F1a | 5 |
| F1b | 10 |
| F2a | 20 |
| F2b | 20 |

permutation 次数：500。

流程：

```text
1. 按 block_size 切块。
2. 随机打乱块顺序。
3. 重新计算 Score / Coherence / IC。
4. 重复 500 次得到 H0 分布。
5. 计算 p_value = P(score_random ≥ score_actual)。
```

### 10.2 相位随机化检验（频域稳健性）

```text
1. 计算原序列功率谱。
2. 保留功率谱模长，随机化相位。
3. 反 FFT 得到 surrogate 时间序列。
4. 重新计算 coherence。
5. 重复 200 次，得到"频谱结构相似但相位无关"的 H0 分布。
```

用于排除"高 coherence 仅来自频谱结构相似"的伪结果。

### 10.3 FDR 多重检验修正

```text
方法: Benjamini-Hochberg
探索性阶段阈值: q < 0.1
收敛阶段阈值:   q < 0.05
```

q 值按"因子 × 频带 × horizon × Y 分量 × 状态"的总测试数计算。

---

## 11. 极端期处理

### 11.1 极端期标记

```text
2020-01-23 至 2020-04-30: 疫情冲击期
2022 全年的系统性下跌阶段
2024-09 之后的快速大涨阶段
任何千股涨停 / 千股跌停的极端交易日 (单独标记)
```

宽表 `notes` 字段标注。

### 11.2 三口径并跑

```text
口径 A: 全样本           (总体表现)
口径 B: 剔除极端期       (常规市场表现)
口径 C: 仅极端期         (极端状态下的特殊因子)
```

最终卡片标注：

- `regime`: `regular_effective` / `extreme_effective` / `extreme_only` / `extreme_failure`

---

## 12. 共振卡片输出

### 12.1 卡片是研究的最终产出

研究的最终产出不是排行榜，而是一张张共振卡片。每张卡片**必须包含**五个维度：

```text
因子: <name>
频带: <F1a / F1b / F2a / F2b>
走势: <Y1 / Y2 / Y3 / 辅助标签>
horizon: <1 / 3 / 5>
状态: <趋势 × 分化 × 量能>
```

任何缺少其中任一维度的"结论"都是不合格的。

### 12.2 单因子卡片 JSON Schema

```json
{
  "factor_name": "C4",
  "factor_type": "single",
  "factor_i": "C4",
  "factor_j": null,
  "target_y": "Y2",
  "horizon": 3,
  "band": "F2a",
  "stft_window": 40,
  "norm_version": "Z_60_detrend",
  "state_window": 60,
  "state_id": "trend=mid,disp=low,vol=high",
  "rolling_corr_mean": 0.18,
  "rolling_corr_stability": 0.72,
  "mean_coherence": 0.58,
  "max_coherence": 0.81,
  "coherence_coverage": 0.36,
  "phase_std": 0.62,
  "lead_days_lag": 2.1,
  "lead_days_phase": 1.9,
  "lead_relation_stable": true,
  "beta": 0.31,
  "oos_ic": 0.08,
  "rank_ic_oos": 0.07,
  "hit_rate": 0.61,
  "top_bottom_spread": 0.42,
  "delta_score_vs_base": null,
  "delta_ic_vs_base": null,
  "p_value": 0.018,
  "q_value": 0.04,
  "sample_count": 96,
  "filtfilt_lfilter_consistent": true,
  "regime": "regular_effective",
  "conclusion_level": "A",
  "qualified": true,
  "interpretation": "涨停股赚钱效应在该状态下向市场广度扩散，对未来 3 日情绪延续有预测贡献。",
  "failure_states": "低位高分化杀跌状态下失效"
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `factor_type` | `single` / `pair_diff` / `pair_product` / `pair_ratio` |
| `state_id` | 状态字符串编码（合并后的状态也用此格式） |
| `lead_days_lag` / `lead_days_phase` | 两种方法的领先天数，必须都记录 |
| `lead_relation_stable` | 两种方法是否一致（差距 ≤ 1 天） |
| `delta_score_vs_base` / `delta_ic_vs_base` | 因子对相对基础模型的增量（单因子卡片为 null） |
| `regime` | 极端期分类 |
| `conclusion_level` | A / B / C / Reject |
| `qualified` | 是否进 A 类（true / false），用于机械计数 |

### 12.3 因子对卡片差异字段

因子对卡片额外字段：

```json
{
  "factor_type": "pair_diff",
  "factor_i": "A5",
  "factor_j": "C1",
  "combine_form": "diff",          // diff / product / ratio
  "delta_score_vs_base": 0.12,
  "delta_ic_vs_base": 0.03,
  "delta_topbottom_vs_base": 0.08,
  "beta3_stability": 0.78,         // β3 同号占比
  ...
}
```

### 12.4 A 类卡片硬条件 (qualified = true)

```text
1. mean_coherence > 0.5
2. coherence_coverage > 0.30
3. phase_std < π/4 (≈ 0.785)
4. lead_relation_stable = true
5. lead_days_lag 落在 horizon 对应区间内
6. oos_ic > 0.05
7. hit_rate > baseline + 0.05
8. top_bottom_spread 稳定为正 (滚动窗口同号占比 > 70%)
9. q_value < 0.1
10. sample_count >= 60 (单因子) 或 80 (因子对)
11. filtfilt_lfilter_consistent = true
12. 因子对额外: delta_ic_vs_base > 0.02, beta3_stability > 0.70
```

任何一条不满足，`qualified = false`。

---

## 13. 报告结构

### 13.1 总览页

```text
样本区间
因子数量 (38 单因子 + 入选因子对)
Y 定义 (Y1 / Y2 / Y3 / 辅助标签)
频带定义 (F1a / F1b / F2a / F2b)
horizon 定义 (1 / 3 / 5)
状态定义 (3×3×3 = 27)
验证方法 (walk-forward 500/120/20)
最终 A 类卡片数 / B 类 / C 类 / Reject 数
```

### 13.2 单因子排行榜

按以下维度分表：

```text
Y1 / Y2 / Y3 各一张表
h = 1 / 3 / 5 各一张表
F1b / F2a 各一张表
```

每张表内显示：A / B / C / Reject 四档因子分组排序。

### 13.3 因子对排行榜

只展示**有增量**的因子对（`delta_ic_vs_base > 0.02`）。

字段：

```text
因子对名称
组合形式
最强频带
有效状态
OOS IC 增量
Top-bottom 增量
方向命中率
业务解释
风险说明
```

### 13.4 状态共振图谱

按状态分组，列出该状态下入选的因子和因子对：

| 市场状态 | 有效因子 | 有效组合 | 主要频带 | 短线含义 |
|---|---|---|---|---|
| 低位 + 高分化 + 缩量 | C7, B6, E2 | C7 × B6 | F1a | 恐慌释放 / 杀跌尾段 |
| 低位 + 放量 + 低分化 | A7, B4, A3 | A7 - A9b | F1b / F2a | 小盘修复 / 普涨启动 |
| 中位 + 放量 + 中分化 | C4, C3, B7 | C4 × B4 | F2a | 情绪延续 |
| 高位 + 高分化 + 放量 | C1, C7, B3' | A5 - C1 | F1a / F1b | 炸板增多 / 退潮预警 |
| 高位 + 缩量 + 高分化 | D7, B6, E2 | D7 × B6 | F1a | 动量衰减 / 回撤风险 |

### 13.5 共振卡片附录

每张 A 类卡片输出一份格式化文本，模板示例：

```text
因子: C4 近 3 日涨停股今表现
目标: Y2 / h=3
最强频带: F2a (5-8 天)
标准化版本: Z_60_detrend
STFT 窗口: 40 日

短线发现:
  rolling_corr_mean      = 0.18
  rolling_corr_stability = 72%

频域确认:
  mean_coherence         = 0.58
  coherence_coverage     = 36%
  phase_std              = 0.62
  lead_days_lag          = 2.1
  lead_days_phase        = 1.9
  lead_relation_stable   = true

样本外兑现:
  OOS IC                 = 0.08
  HitRate                = 61% (baseline 54%)
  Top-bottom spread      = 0.42
  q_value                = 0.04
  filtfilt vs lfilter    = consistent

有效状态:
  趋势: 中位
  分化: 低
  量能: 放量
  sample_count: 96

解释:
  涨停股赚钱效应在该状态下会向市场广度扩散，
  对未来 3 日情绪延续有预测贡献。

失效状态:
  低位高分化杀跌状态下失效。

结论等级: A
```

---

## 14. 默认参数总表

| 模块 | 参数 | 值 |
|---|---|---|
| 因子数量 | `factor_count` | 38 |
| 主目标 | `target` | Y1 / Y2 / Y3 |
| 辅助目标 | `target_aux` | Y_max / Y_min / Y_drawdown / Y_direction |
| horizon | `h` | 1 / 3 / 5 |
| 核心频带 | `band_core` | F1b / F2a |
| 辅助频带 | `band_aux` | F1a / F2b |
| winsorize 窗口 | `winsor_window` | 252 |
| winsorize 分位 | `winsor_q` | 1% / 99% |
| 标准化窗口 | `zscore_window` | 60 / 120 |
| 去趋势 EMA | `detrend_span` | 20 |
| 滚动相关窗口 | `roll_corr_window` | 20 / **30** / 40 |
| 滤波器 | `filter_type` | Butterworth 4 阶 |
| 滤波因果性 | `filter_mode` | filtfilt (发现) + lfilter (验证) |
| STFT 窗口 | `stft_window` | 30 / **40** / 60 |
| STFT 步长 | `stft_step` | 1 |
| FFT 点数 | `n_fft` | 64 |
| 窗函数 | `window_func` | Hann |
| 高相干阈值 | `coh_threshold` | 0.5 |
| 覆盖率阈值 | `coverage_threshold` | 0.30 |
| 相位稳定阈值 | `phase_std_threshold` | π/4 |
| 领先区间 h=1 | `lead_range_h1` | 0.5 – 1.5 |
| 领先区间 h=3 | `lead_range_h3` | 1 – 3 |
| 领先区间 h=5 | `lead_range_h5` | 1 – 5 |
| 状态主窗口 | `state_window` | **60** |
| 状态辅助窗口 | `state_window_aux` | 20 / 252 |
| 状态档位 | `state_quantile` | 33% / 67% |
| 状态内样本门槛 (单因子) | `min_state_n_single` | 60 |
| 状态内样本门槛 (因子对) | `min_state_n_pair` | 80 |
| Block permutation 次数 | `permutation_n` | 500 |
| block_size F1a | `block_size_f1a` | 5 |
| block_size F1b | `block_size_f1b` | 10 |
| block_size F2a | `block_size_f2a` | 20 |
| block_size F2b | `block_size_f2b` | 20 |
| FDR 阈值（探索） | `fdr_q_explore` | 0.1 |
| FDR 阈值（收敛） | `fdr_q_final` | 0.05 |
| 训练窗口 | `train_window` | 500 |
| 验证窗口 | `valid_window` | 120 |
| 滚动步长 | `oos_step` | 20 |
| OOS IC 阈值 | `oos_ic_threshold` | 0.05 |
| Hit rate 提升阈值 | `hit_rate_lift` | 0.05 |
| 因子对入选 ΔIC | `delta_ic_threshold` | 0.02 |
| 因子对 β3 稳定阈值 | `beta3_stability` | 0.70 |

---

## 15. 推荐执行顺序

研究分五轮渐进展开，**不要一开始就全量跑**：

### 第一轮：最小闭环

```text
38 个单因子
× Y1 / Y2 / Y3
× h = 1 / 3 / 5
× F1b / F2a (只用一级核心频带)
× Z_60_detrend (只用主优先标准化版本)
× 30 日 rolling corr (只用主发现窗口)
```

目标：找到短线有反应的因子（候选池），不追求最终结论。

### 第二轮：STFT 频域确认

```text
对候选因子做 STFT-30 / 40 / 60 三窗口确认。
保留 F1b / F2a 主结论，扩展到 F1a 二级核心。
```

### 第三轮：状态条件 + 样本外

```text
对 STFT 候选做 27 状态分析。
做 walk-forward 样本外验证。
做 filtfilt vs lfilter 因果性对照。
```

### 第四轮：因子对

```text
基于第一-三轮的 A/B 类候选构造因子对。
做差值 / 乘积组合，比值仅辅助。
做增量贡献检验。
```

### 第五轮：报告输出

```text
单因子排行榜 (按 Y × horizon × 频带分表)。
因子对排行榜 (仅有增量者)。
状态共振图谱。
共振卡片附录。
```

---

## 16. 工程实现清单（与 autoresearch prompt 对接）

落地时建议的 Phase 划分，与 autoresearch prompt 的 Phase 编号一一对应：

```text
Phase 1  引入 jSciPy + DataFrame + Kandy 依赖
         建表 sentiment_factor_daily
         实现 Repository
         实现 count-resonance-cards.sh 桩

Phase 2  A 组 14 因子计算 + 单元测试

Phase 3  B 组 + E 组截面因子

Phase 4  C 组涨停因子 (SQL JOIN tushare_limit_list_d)

Phase 5  D 组时序因子

Phase 6  Y1 / Y2 / Y3 + Y_composite + 辅助标签计算

Phase 7  频域共振分析
         - 双版本标准化 + 去趋势
         - 带通滤波 (filtfilt / lfilter 双版本)
         - 滚动相关
         - STFT-30/40/60 + coherence + phase
         - lead_days 双方法交叉验证
         - 状态分组 + 样本数检查 + 合并
         - walk-forward OOS 验证
         - block permutation + FDR
         - 输出单因子共振卡片 JSON

Phase 8  因子对研究
         - 基于 A/B 类候选剪枝 (约 200 对)
         - 差值 / 乘积 / 稳健比值
         - PCA 主成分作为增量基线
         - 输出因子对共振卡片 JSON
```

每个 Phase 结束都应当：

```text
1. 跑通编译 (Guard: ./gradlew :database:compileKotlin :database:test)
2. 输出当前累计的 A 类卡片数 (Metric: count-resonance-cards.sh)
3. 提交一次原子 commit
```

---

## 附录　术语对照

| 术语 | 含义 |
|---|---|
| Coherence γ²(t, ω) | 时频相干性，[0, 1] |
| Phase φ(t, ω) | 互谱相位 |
| Lead days | 因子领先 Y 的天数 |
| F1a / F1b / F2a / F2b | 2–3 / 3–5 / 5–8 / 8–10 天频带 |
| Y1 / Y2 / Y3 | 加权涨幅 / 上涨家数占比 / 涨跌停净广度 |
| Y_composite | Y1/Y2/Y3 的复合情绪指标 |
| Walk-forward | 滚动训练-验证窗口的样本外验证 |
| Block permutation | 块状随机重排的显著性检验 |
| Filtfilt vs Lfilter | 零相位双向滤波 vs 因果单向滤波 |
| PCA base | 已入选因子的 PCA 主成分构成的增量基线 |
| Resonance card | 共振卡片，研究的最终产出单位 |
| Qualified | 卡片是否进 A 类的二值标记 |
