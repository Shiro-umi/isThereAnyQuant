from __future__ import annotations

import argparse
import bisect
import json
import math
import random
import re
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlencode
from urllib.request import urlopen

import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, TensorDataset


@dataclass(frozen=True)
class ModelManifest:
    topic: str
    model_type: str
    format_version: int
    feature_schema_path: str
    normalization_path: str
    weights_path: str
    thresholds_path: str | None
    device: str
    seed: int
    seq_len: int
    feature_dim: int
    market_dim: int
    d_model: int
    heads: int
    layers: int
    num_states: int
    loss: str


# ──────────────────────────────────────────────────────────────────────────────
# 模型：个股时序 Transformer × 市场 regime 门控（条件状态 >> 全局状态）
#
# 业务依据（真实交易中指数下跌日的三种 case）：
#   case1 热度不高的票大幅跟跌（多数）—— β 驱动普跌
#   case2 热度高、主力借机出货 → 大幅跟跌（较多）—— 高热度 + 量价偏离/资金流出
#   case3 热度高、主力借势继续拉升（少数，反例）—— 高热度 + 量价配合/资金流入
# 判别这三者需要：① 个股热度谱（case1 ↔ case2/3）② 个股主力行为方向（case2 ↔ case3）
# ③ 市场状态作为「触发开关」（这套判别只在指数下跌/高危 regime 下激活）。
#
# 因此市场情绪因子**不再平铺拼进个股序列**（每天全市场共享同一值，截面零区分度，只贡献
# 过拟合容量——已实测 train↑ val↓），而是改成 **FiLM regime 门控**：市场因子经小 MLP
# 生成逐通道调制 (γ, β)，对个股序列表示做 h' = (1+γ)⊙h + β。市场状态由此**调制**个股
# 判别器的行为，而非和个股因子平起平坐。这才是「条件状态 >> 全局状态」的正确数学形态。
#
# 输入三路：
#   x        : [B, T, F]   每天的个股 Δ/level/热度因子（仅个股级，有截面区分度）
#   market_x : [B, M]      被预测日 t 的市场情绪因子（regime 门控输入，不进时序）
#   state_id : [B]         被预测日 t 的离散状态格 id（D4×B3'×A3 → [0,27)，与连续 FiLM 互补）
# ──────────────────────────────────────────────────────────────────────────────
class CrashTransformer(nn.Module):
    """
    个股时序 Transformer × 市场 regime 门控。门控有两种形态（gate_mode）：

      'film'（统一门控，旧）：(γ,β)=MLP(market)，同一天所有股票用同一组调制。简单但丢失
        「个股对同样市场环境反应敏感度因股而异」的交互信息（高β票剧烈/防御票几乎不动）。

      'cross'（交叉注意力，新，默认）：把市场状态做成 K 个市场 token，个股序列表示 h 作为
        query 对市场 token 做 cross-attention。注意力分数 = 个股表示·市场token，**因股而异**——
        高β票的 h 会强烈 attend「市场趋势」token，防御票几乎不 attend。既保留个股×市场交互，
        又仍是注意力形式（参数受约束，不像平铺拼接那样用市场常数背历史日期答案册→过拟合）。

    输入三路：x[B,T,F] 个股因子时序 / market_x[B,M] 当日市场情绪因子 / state_id[B] 离散状态格。
    """
    def __init__(
        self,
        feature_dim: int,
        market_dim: int,
        d_model: int,
        heads: int,
        layers: int,
        dropout: float,
        num_states: int,
        use_state_gate: bool,
        gate_mode: str = "cross",
        market_tokens: int = 4,
        moe_experts: int = 0,
        num_quantiles: int = 1,
    ) -> None:
        super().__init__()
        self.use_state_gate = use_state_gate and (market_dim > 0 or num_states > 1)
        self.gate_mode = gate_mode
        self.moe_experts = moe_experts
        self.num_quantiles = num_quantiles  # 1=二分类(单 logit)；>1=分位数回归(多分位头)
        self.input = nn.Linear(feature_dim, d_model)
        encoder_layer = nn.TransformerEncoderLayer(
            d_model=d_model,
            nhead=heads,
            dim_feedforward=d_model * 4,
            dropout=dropout,
            batch_first=True,
            activation="gelu",
            norm_first=True,
        )
        self.encoder = nn.TransformerEncoder(encoder_layer, num_layers=layers)
        self.output = nn.Linear(d_model, num_quantiles)
        self.film = None
        self.cross_attn = None
        self.state_embedding = None
        if self.use_state_gate:
            self.state_embedding = nn.Embedding(num_states, d_model)
            regime_dim = market_dim + d_model
            if gate_mode == "film":
                self.film = nn.Sequential(
                    nn.Linear(regime_dim, d_model), nn.GELU(), nn.Linear(d_model, 2 * d_model),
                )
                # warm 初始化（修复"门控权重学不动"）：诊断发现全零初始化下 FiLM 梯度信号被
                # 个股因子主导，(γ,β) 长期停在 0 → regime 调制形同虚设。改为给最后一层一个
                # 小尺度初始化，让 regime 通路起步即有非平凡梯度，打破零梯度僵局。
                # γ 段（前 d_model）小正态、β 段（后 d_model）保持零（偏移起步中性）。
                nn.init.normal_(self.film[-1].weight, std=1e-3)
                with torch.no_grad():
                    self.film[-1].weight[d_model:].zero_()   # β 段权重置零
                nn.init.zeros_(self.film[-1].bias)
            else:  # 'cross'：市场状态 → K 个市场 token，个股序列 cross-attend
                self.market_proj = nn.Linear(regime_dim, market_tokens * d_model)
                self.market_tokens = market_tokens
                self.d_model = d_model
                self.cross_attn = nn.MultiheadAttention(d_model, heads, dropout=dropout, batch_first=True)
                self.cross_norm = nn.LayerNorm(d_model)
                # gate 残差缩放零初始化 → 起步时门控≈恒等，稳定后再学市场调制
                self.cross_gate = nn.Parameter(torch.zeros(1))
        # MoE 多专家：活跃投机股下跌分多种模式（连板炸板/题材退潮/高位出货）。
        # K 个专家 FFN 各学一种模式，gating 网络按个股表示 soft 路由。
        if moe_experts > 0:
            self.experts = nn.ModuleList(
                nn.Sequential(nn.Linear(d_model, d_model * 2), nn.GELU(), nn.Linear(d_model * 2, d_model))
                for _ in range(moe_experts)
            )
            self.moe_gate = nn.Linear(d_model, moe_experts)
            self.moe_norm = nn.LayerNorm(d_model)

    def _pool(self, x: torch.Tensor, market_x: torch.Tensor, state_id: torch.Tensor) -> torch.Tensor:
        """编码 + regime 门控，返回被预测日的个股表示 pooled [B, d]。"""
        h = self.encoder(self.input(x))            # [B, T, d]
        if self.use_state_gate:
            regime = torch.cat([market_x, self.state_embedding(state_id)], dim=-1)
            if self.gate_mode == "film":
                gamma, beta = self.film(regime).chunk(2, dim=-1)
                return (1.0 + gamma) * h[:, -1, :] + beta
            mk = self.market_proj(regime).view(-1, self.market_tokens, self.d_model)
            attended, _ = self.cross_attn(h, mk, mk)
            h = h + self.cross_gate * self.cross_norm(attended)
        return h[:, -1, :]

    def forward(self, x: torch.Tensor, market_x: torch.Tensor, state_id: torch.Tensor) -> torch.Tensor:
        pooled = self._pool(x, market_x, state_id)   # [B, d]
        if self.moe_experts > 0:
            # soft MoE：gating 按个股表示路由（个股表示已编码题材/连板/动量状态）
            weights = torch.softmax(self.moe_gate(pooled), dim=-1)         # [B, K]
            expert_out = torch.stack([e(pooled) for e in self.experts], dim=1)  # [B, K, d]
            mixed = torch.einsum("bk,bkd->bd", weights, expert_out)        # 加权混合专家
            pooled = pooled + self.moe_norm(mixed)                          # 残差
        out = self.output(pooled)                                          # [B, Q]
        if self.num_quantiles == 1:
            return out.squeeze(-1)                                          # 二分类：[B]
        return out                                                          # 分位回归：[B, Q]


# ──────────────────────────────────────────────────────────────────────────────
# 损失：可微 soft-Fβ（β>1 偏召回，对齐「召回率是一等公民」）
#
# 用 sigmoid(logit) 作软预测概率 p，软 TP=Σ p·y、软 FP=Σ p·(1−y)、软 FN=Σ (1−p)·y，
# 直接优化 Fβ 工作点而非全局排序 AUC。β=2 时 recall 权重是 precision 的 4 倍。
# 配合类别加权 α 处理正例不平衡（剥β残差正例 ~7%）。
# ──────────────────────────────────────────────────────────────────────────────
class SoftFBetaLoss(nn.Module):
    def __init__(self, beta: float, pos_weight: float, eps: float = 1e-7) -> None:
        super().__init__()
        self.beta2 = beta * beta
        self.pos_weight = pos_weight
        self.eps = eps

    def forward(self, logits: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        p = torch.sigmoid(logits)
        w = targets * self.pos_weight + (1.0 - targets)  # 正例样本在软计数里加权
        tp = torch.sum(w * p * targets)
        fp = torch.sum(p * (1.0 - targets))
        fn = torch.sum(w * (1.0 - p) * targets)
        f_beta = (1.0 + self.beta2) * tp / (
            (1.0 + self.beta2) * tp + self.beta2 * fn + fp + self.eps
        )
        return 1.0 - f_beta


class FocalLoss(nn.Module):
    """
    Focal Loss：聚焦难样本，down-weight 易分样本。FL = −α·(1−p_t)^γ·log(p_t)。
    γ 越大越聚焦难样本；对"高把握工作点 precision"有利——模型在最有把握的正例上更纯。
    α 处理类别不平衡（正例权重）。
    """
    def __init__(self, gamma: float = 2.0, alpha: float = 0.5) -> None:
        super().__init__()
        self.gamma = gamma
        self.alpha = alpha

    def forward(self, logits: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        p = torch.sigmoid(logits)
        ce = nn.functional.binary_cross_entropy_with_logits(logits, targets, reduction="none")
        p_t = p * targets + (1.0 - p) * (1.0 - targets)         # 正确类概率
        alpha_t = self.alpha * targets + (1.0 - self.alpha) * (1.0 - targets)
        loss = alpha_t * (1.0 - p_t).pow(self.gamma) * ce
        return loss.mean()


class RankBceLoss(nn.Module):
    """
    BCE + batch 内 pairwise ranking。BCE 保留概率校准，pairwise 项直接优化排序：
    对每个 batch 中的正例 logit p 和负例 logit n，最小化 softplus(-(p-n))，
    推动正例整体排到负例前面，目标更贴近 recall60 precision 这类覆盖度工作点。
    """
    def __init__(self, pos_weight: float, rank_weight: float = 0.2, max_pairs: int = 4096) -> None:
        super().__init__()
        self.register_buffer("pos_weight", torch.tensor([pos_weight], dtype=torch.float32))
        self.rank_weight = rank_weight
        self.max_pairs = max_pairs

    def forward(self, logits: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        bce = nn.functional.binary_cross_entropy_with_logits(
            logits, targets, pos_weight=self.pos_weight.to(logits.device)
        )
        pos = logits[targets > 0.5]
        neg = logits[targets <= 0.5]
        if pos.numel() == 0 or neg.numel() == 0:
            return bce
        diffs = pos[:, None] - neg[None, :]
        flat = diffs.reshape(-1)
        if flat.numel() > self.max_pairs:
            idx = torch.linspace(0, flat.numel() - 1, self.max_pairs, device=flat.device).long()
            flat = flat.index_select(0, idx)
        rank = nn.functional.softplus(-flat).mean()
        return bce + self.rank_weight * rank


class DateRankBceLoss(nn.Module):
    """
    BCE + 同交易日截面 pairwise ranking。

    急杀预警的实盘动作是在同一天的股票池里排序并挑高危票，验证指标也是全验证集 PR 曲线。
    batch 内跨日期 pairwise 会把不同市场 regime 的基准风险混在一起；这里只比较同一 end_di 内
    正例与负例，直接优化「当日谁更危险」的截面排序。
    """
    def __init__(self, pos_weight: float, rank_weight: float = 0.2, max_pairs: int = 4096) -> None:
        super().__init__()
        self.register_buffer("pos_weight", torch.tensor([pos_weight], dtype=torch.float32))
        self.rank_weight = rank_weight
        self.max_pairs = max_pairs

    def forward(self, logits: torch.Tensor, targets: torch.Tensor, date_ids: torch.Tensor) -> torch.Tensor:
        bce = nn.functional.binary_cross_entropy_with_logits(
            logits, targets, pos_weight=self.pos_weight.to(logits.device)
        )
        terms: list[torch.Tensor] = []
        for di in torch.unique(date_ids):
            mask = date_ids == di
            pos = logits[mask & (targets > 0.5)]
            neg = logits[mask & (targets <= 0.5)]
            if pos.numel() == 0 or neg.numel() == 0:
                continue
            flat = (pos[:, None] - neg[None, :]).reshape(-1)
            if flat.numel() > self.max_pairs:
                idx = torch.linspace(0, flat.numel() - 1, self.max_pairs, device=flat.device).long()
                flat = flat.index_select(0, idx)
            terms.append(nn.functional.softplus(-flat).mean())
        if not terms:
            return bce
        return bce + self.rank_weight * torch.stack(terms).mean()


class NeymanPearsonLoss(nn.Module):
    """
    Neyman-Pearson recall60 工作点损失（剑走偏锋，数学可证）。

    业务目标不是全局 AUC，而是「召回 60% 急杀时的 precision」——即固定 TPR=0.6
    这一个工作点上压低 FPR。Neyman-Pearson 引理：在 TPR≥α 约束下最小化 FPR 的最优
    检验是似然比阈值检验。这里用拉格朗日松弛把"约束优化"变成可微损失：

      1. batch 内对正例 logits 取经验 (1−recall_target) 分位数 θ —— 即让 recall_target
         比例的正例落在 θ 之上的软阈值（recall_target=0.6 → 取正例 logit 的 40% 分位）。
      2. 工作点假阳惩罚：Σ_neg softplus((logit_neg − θ)/τ) —— 只惩罚 recall60 阈值 θ
         以上的负例（这些正是该工作点的 FP），其余工作点不参与，模型容量全集中到目标点。
      3. 工作点真阳奖励：拉开 θ 之上正例与 θ 的间距，让正例在 θ 上方更稳。
      4. BCE 保概率校准（否则 logit 尺度漂移、θ 无意义）。

    θ 用 torch.quantile（对正例排序，分位点本身随分布平移可导）。τ 是软化温度。
    数学上这是「固定工作点的经验 NP 风险」的无偏可微 surrogate：约束（TPR=0.6）由 θ 的
    分位定义精确满足，目标（min FPR）由 softplus 项单调上界。
    """
    def __init__(self, pos_weight: float, recall_target: float = 0.6,
                 np_weight: float = 1.0, margin_weight: float = 0.1, tau: float = 0.5) -> None:
        super().__init__()
        self.register_buffer("pos_weight", torch.tensor([pos_weight], dtype=torch.float32))
        self.recall_target = recall_target
        self.np_weight = np_weight
        self.margin_weight = margin_weight
        self.tau = tau

    def forward(self, logits: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        bce = nn.functional.binary_cross_entropy_with_logits(
            logits, targets, pos_weight=self.pos_weight.to(logits.device)
        )
        pos = logits[targets > 0.5]
        neg = logits[targets <= 0.5]
        if pos.numel() < 4 or neg.numel() < 4:
            return bce
        # θ = 正例 logits 的 (1−recall_target) 分位数：recall_target 比例正例在其之上
        theta = torch.quantile(pos, 1.0 - self.recall_target)
        # 工作点假阳：θ 以上的负例（softplus 软计数，τ 温度）
        fp_term = nn.functional.softplus((neg - theta) / self.tau).mean()
        # 工作点真阳间距：θ 之上的正例越高越稳（鼓励正例分布右尾拉开）
        margin_term = nn.functional.softplus((theta - pos) / self.tau).mean()
        return bce + self.np_weight * fp_term + self.margin_weight * margin_term


class PinballLoss(nn.Module):
    """
    分位数回归 pinball（quantile）损失，多分位联合（剑走偏锋，信息论可证）。

    二值标签 1[severity>0] 丢掉了"急杀多深"的信息——severity 连续值是它的充分统计量。
    与其拟合伯努利，不如直接回归 severity 的条件分位数：模型对每个 τ 输出一个分位预测，
    pinball 损失  L_τ(e, ê) = max(τ·(e−ê), (τ−1)·(e−ê))  的最优解就是条件 τ 分位数
    （Koenker & Bassett 1978 证明）。多 τ 联合 → 模型学到 severity 的整条条件分布，
    再用某个高分位（如 τ=0.9，对应"这只票急杀强度的乐观估计仍很高"）排序选最危险票。

    急杀预警关心左尾深度，severity>0 即正例、越大越严重，故用**高 τ 分位**（severity 的
    上分位）做排序打分：高 τ 分位高 ⟺ 即便保守看该票 severity 仍大 ⟺ 高危确定性强。
    单调性软约束防止分位交叉（quantile crossing），保证 ê 可解释。
    """
    def __init__(self, quantiles: tuple[float, ...] = (0.5, 0.7, 0.9), rank_q_index: int = -1) -> None:
        super().__init__()
        self.register_buffer("taus", torch.tensor(quantiles, dtype=torch.float32))
        self.rank_q_index = rank_q_index  # 用哪个分位做排序打分（默认最高 τ）

    def forward(self, preds: torch.Tensor, severity: torch.Tensor) -> torch.Tensor:
        # preds: [B, Q] 各分位预测；severity: [B]
        taus = self.taus.to(preds.device).view(1, -1)            # [1, Q]
        e = severity.unsqueeze(-1) - preds                        # [B, Q] 残差
        pinball = torch.maximum(taus * e, (taus - 1.0) * e).mean()
        # 单调性软约束：高分位预测应 ≥ 低分位预测（防 quantile crossing）
        if preds.shape[1] > 1:
            cross = nn.functional.relu(preds[:, :-1] - preds[:, 1:]).mean()
            return pinball + 0.1 * cross
        return pinball


STOCK_FEATURE_NAMES = [
    "换手·骤升",
    "换手·短长比",
    "波动·抬升",
    "估值·偏离",
    "估值·变化率",
    "动量·加速度",
    "换手·level",
    # 热度谱（case1 热度不高 ↔ case2/3 热度高 的分野，零新数据，从日K/换手算）
    "换手·历史分位",   # 当日换手在过去 long_w 窗内的分位（0~1），热度的连续刻画
    # 放量方向（case2 放量跌=出货 ↔ case3 放量涨=拉升 的方向代理，无资金流数据时的最佳近似）
    "量价·放量方向",   # 短期动量 × 放量强度：放量上涨为正(拉升嫌疑)、放量下跌为负(出货嫌疑)
]

# 资金流因子（moneyflow，直击 case2 出货净流出 ↔ case3 拉升净流入 的主力方向真信号）
MONEYFLOW_FEATURE_NAMES = [
    "资金·主力净流入率",   # net_mf_amount / 流通市值代理，净流入强度（正=吸筹，负=出货）
    "资金·大单净额方向",   # (大单+特大单)买 − 卖，主力净方向
    "资金·特大单净占比",   # 特大单净额 / 总主动盘，机构/游资主力的净方向
]

# 个股级 VV/VP 量价因子（factor topic 算子复用到个股序列，有截面区分度）
VP_FEATURE_NAMES = [
    "量价·脉冲z(VV5)",      # 量能脉冲偏离，高位连续=派发见顶
    "量价·游程(VV3)",       # 放量/缩量持续天数（带符号）
    "量价·量加速度(VP2v)",  # 量异动二阶差分，放量加速/衰竭
    "量价·配合度(VP0)",     # sign(ret)·量异动，放量方向
    "量价·弹性β(VPbeta)",   # 量推动价的效率，推不动=出货
    "量价·领先占比(VV8)",   # 量拐点领先价拐点，超前性
]

# RSI 及高阶因子（正向预警：超买亢奋/快速放大 → 容易回落）。有界非线性饱和，正交于线性动量。
RSI_FEATURE_NAMES = [
    "RSI·水平",       # RSI(14)，超买亢奋度
    "RSI·变化率",     # ΔRSI，快速放大=亢奋突变
    "RSI·加速度",     # RSI 二阶，放大在加速还是减速
]

# 涨跌停制度因子（A 股特有，制度离散事件，正交于连续量价——唯一非量价信号源）。
LIMIT_FEATURE_NAMES = [
    "涨跌停·跌停命中",   # 当日跌停（Wan: 跌停后次日延续概率高）
    "涨跌停·炸板",       # 封板被打开，承接不足
    "涨跌停·涨停命中",   # 涨停（接力失败=次日杀跌风险）
    "涨跌停·打开次数",   # 封板质量，越多越弱
    "涨跌停·连板高度",   # 连板透支度
]

SEAL_QUALITY_FEATURE_NAMES = [
    "封板·封单流通占比",   # fd_amount / float_mv，封单对自由流通盘的支撑强度
    "封板·封单板成交比",   # fd_amount / limit_amount，板上承接/封单质量
    "封板·开板弱度",       # open_times，反复开板=承接不足
    "封板·封板次数",       # limit_times，反复回封/脉冲封板结构
    "封板·炸板弱封组合",   # 炸板 × 开板弱度，封板失败的强负反馈
]

# 龙虎榜方向极性因子（top_list 汇总 + top_inst 营业部明细，当期外生方向信号，正交于个股量价强度）。
# 暴涨前抢筹上榜 vs 暴跌前出逃上榜形态不对称；席位结构（散户 vs 机构）携带方向偏好。
# 缺失（未上榜/无席位明细）= 全 0，不丢样本（上榜事件位自带"有没有上榜"标记）。
LONGHUBAN_FEATURE_NAMES = [
    "龙虎榜·上榜事件",       # 0/1，该股当日是否上龙虎榜（资金异动门）
    "龙虎榜·净买入占比",     # net_amount / amount，龙虎榜整体净买入方向（+抢筹 / −出逃）
    "龙虎榜·散户席位净额",   # Σ(含「拉萨」营业部 买−卖) / amount，东财拉萨系散户大本营净方向
    "龙虎榜·机构席位净额",   # Σ(「机构专用」席位 买−卖) / amount，机构净方向
]

# 题材群体因子（开盘啦，中观维度，正交于个股量价+市场级）。
THEME_FEATURE_NAMES = [
    "题材·热度",       # 所在题材涨停数（热门有护盘，非热门易跟跌）
    "题材·最高连板",   # 所在题材最高连板（透支度）
    "题材·负反馈密度", # 题材内跌停/炸板占比（动能枯竭→共振杀跌）
    "题材·活跃天数",   # 题材运行时间（越久越易杀跌）
]

# 轨迹因子（活跃股判别力在"生命周期位置"，非当日快照——连了几天/距首板/炸板历史）。
TRAJ_FEATURE_NAMES = [
    "轨迹·连板持续天数",   # 当前连板已持续几天（越久越透支）
    "轨迹·距上次涨停天数", # 距最近一次涨停隔了几天（题材降温信号）
    "轨迹·窗内炸板次数",   # 近 N 日累计炸板次数（反复炸板=承接持续走弱）
    "轨迹·窗内涨停密度",   # 近 N 日涨停天数占比（活跃强度轨迹）
]

EXTERNAL_DOC_FEATURE_NAMES = [
    "外部·残差动量5",       # 个股5日动量剥离市场β后的特质动量
    "外部·残差动量20",      # 个股20日动量剥离市场β后的特质动量
    "外部·市场β20",        # 20日市场敏感度，高β活跃股在系统下跌中更脆
    "外部·换手退潮",        # 前期高换手后当日降温，拥挤资金撤退
    "外部·拥挤加速度",      # 当日换手相对短均与短均相对长均的二阶变化
]

BEHAVIORAL_FEATURE_NAMES = [
    "行为·距52周高点",       # 52周高点锚定，越接近心理锚/筹码压力越强
    "行为·距60日高点",       # 短线锚定，活跃题材近期高点附近的兑现压力
    "行为·彩票化强度",       # 高换手高波动组合，散户彩票偏好/过度自信代理
    "行为·涨停后锚定天数",   # 距上次涨停的衰减锚，涨停后数日内兑现/分歧压力
]

INTRADAY15_FEATURE_NAMES = [
    "15m·开盘收益",        # 首根15min close/open，开盘承接方向
    "15m·开盘振幅",        # 首根15min high-low/open，开盘分歧
    "15m·开盘上影",        # 首根15min 上影占比，冲高回落压力
    "15m·尾盘收益",        # 尾根15min close/open，尾盘资金方向
    "15m·尾盘振幅",        # 尾根15min high-low/open，尾盘分歧
    "15m·尾盘收盘位置",    # 尾根 close 在 high-low 区间的位置，越低越弱
    "15m·尾盘成交占比",    # 尾根成交额 / 当日15min成交额，尾盘抛压/抢筹强度
    "15m·覆盖标记",        # 当日是否有15min结构数据，回填期防止缺失被误读
]


def stock_feature_names(
    use_moneyflow: bool,
    use_vp: bool = False,
    use_rsi: bool = False,
    use_limit: bool = False,
    use_longhuban: bool = False,
    use_theme: bool = False,
    use_traj: bool = False,
    use_seal_quality: bool = False,
    use_external_doc: bool = False,
    use_behavioral: bool = False,
    use_intraday15: bool = False,
) -> list[str]:
    return (STOCK_FEATURE_NAMES
            + (MONEYFLOW_FEATURE_NAMES if use_moneyflow else [])
            + (VP_FEATURE_NAMES if use_vp else [])
            + (RSI_FEATURE_NAMES if use_rsi else [])
            + (LIMIT_FEATURE_NAMES if use_limit else [])
            + (LONGHUBAN_FEATURE_NAMES if use_longhuban else [])
            + (THEME_FEATURE_NAMES if use_theme else [])
            + (TRAJ_FEATURE_NAMES if use_traj else [])
            + (SEAL_QUALITY_FEATURE_NAMES if use_seal_quality else [])
            + (EXTERNAL_DOC_FEATURE_NAMES if use_external_doc else [])
            + (BEHAVIORAL_FEATURE_NAMES if use_behavioral else [])
            + (INTRADAY15_FEATURE_NAMES if use_intraday15 else []))

# 市场情绪因子：trend topic 成果，市场级日频（单 PK=tradeDate）。
# D4(趋势) / B3p(分化) / A3(量能) 既进特征拼接，又驱动状态门控的 27 状态格。
MARKET_FEATURE_NAMES = [
    "A1", "A2", "A3", "A4", "A5",
    "B1", "B3", "B3p", "B4", "B5",
    "C1", "C2", "C2p", "C3",
    "D1", "D2", "D3", "D4", "D5",
    "E1", "E2", "VPM_ret", "VPM_turn",
]

META_TIMING_FEATURE_NAMES = [
    "元择时·趋势持续度",      # 近5日 D4 符号一致性，状态越稳定模型越可预测
    "元择时·量价一致性",      # D4 × A3，趋势与量能同向时信号更可靠
    "元择时·波动稳定度",      # - std(VPM_ret,5)/std(VPM_ret,20)，波动结构越稳定越可预测
    "元择时·情绪修复",        # E1 自近5日低点的修复幅度，捕捉低情绪拐点
]


def market_feature_names(use_meta_timing: bool = False) -> list[str]:
    return MARKET_FEATURE_NAMES + (META_TIMING_FEATURE_NAMES if use_meta_timing else [])

# 状态门控用的三个 regime 轴（连续值，装配后用训练段分位三等分离散化）
STATE_AXES = ["D4", "B3p", "A3"]
NUM_STATES = 3 ** len(STATE_AXES)  # 27
KPL_ACTIVE_FALLBACK_BEFORE_DATE = "2019-01-01"


def main() -> None:
    parser = argparse.ArgumentParser(description="Train pivot-crash-stock Transformer with native PyTorch.")
    parser.add_argument("--dataset", type=Path, help="NPZ exported by the research Input stage.")
    parser.add_argument("--cache-out", type=Path, help="API 装配完把样本张量存为 NPZ 缓存，后续训练用 --dataset 秒读，免重复装配。")
    parser.add_argument("--api-base", help="Ktor server base URL for direct Source/Input fetch, e.g. http://127.0.0.1:9871.")
    parser.add_argument("--start", default="2020-01-01")
    parser.add_argument("--end", default="2026-06-30")
    parser.add_argument("--fwd", type=int, default=3)
    parser.add_argument("--label", choices=["abs", "rel", "resid", "dynamic"], default="resid")
    parser.add_argument("--dyn-k", type=float, default=1.0, help="动态大跌阈值的市场波动倍数 k（业务参数，固定不可学）")
    parser.add_argument("--roll-w", type=int, default=20, help="历史滚动市场波动窗口（动态阈值平滑项）")
    parser.add_argument("--market", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--moneyflow", action=argparse.BooleanOptionalAction, default=False)
    parser.add_argument("--vp-factors", action=argparse.BooleanOptionalAction, default=False, help="启用个股级 VV/VP 量价因子（VV5/VV3/VP2v/VP0/VPbeta/VV8）")
    parser.add_argument("--rsi-factors", action=argparse.BooleanOptionalAction, default=False, help="启用 RSI 及高阶因子（RSI水平/变化率/加速度，正向预警）")
    parser.add_argument("--limit-factors", action=argparse.BooleanOptionalAction, default=False, help="启用涨跌停制度因子（跌停/炸板/涨停/打开/连板，正交于量价）")
    parser.add_argument("--longhuban", action=argparse.BooleanOptionalAction, default=False, help="启用龙虎榜方向极性因子（上榜事件/净买入占比/散户席位净额/机构席位净额，当期外生方向信号）")
    parser.add_argument("--composite-label", action=argparse.BooleanOptionalAction, default=False, help="复合标签：回撤 OR 未来fwd日内跌停命中（实盘损失=跌幅+卖不掉）")
    parser.add_argument("--theme-factors", action=argparse.BooleanOptionalAction, default=False, help="启用题材群体因子（热度/最高连板/负反馈密度/活跃天数，中观正交维度）")
    parser.add_argument("--traj-factors", action=argparse.BooleanOptionalAction, default=False, help="启用轨迹因子（连板持续天数/距上次涨停/窗内炸板次数/涨停密度，生命周期位置）")
    parser.add_argument("--seal-quality-factors", action=argparse.BooleanOptionalAction, default=False,
                        help="启用封板质量因子（封单/流通盘、封单/板成交、开板弱度、回封次数、炸板弱封组合），严格因果")
    parser.add_argument("--external-doc-factors", action=argparse.BooleanOptionalAction, default=False,
                        help="启用外部文档启发因子（残差动量/市场β/换手拥挤退潮），严格因果，只用 t 及以前日线事实")
    parser.add_argument("--behavioral-factors", action=argparse.BooleanOptionalAction, default=False,
                        help="启用短线行为金融因子（52周/60日高点锚定、彩票化、高换手涨停后锚定），严格因果")
    parser.add_argument("--intraday15-factors", action=argparse.BooleanOptionalAction, default=False,
                        help="启用15分钟级别开盘/尾盘结构因子（首根/尾根15min与尾盘成交占比），严格因果，只用 t 日已知结构")
    parser.add_argument("--meta-timing-factors", action=argparse.BooleanOptionalAction, default=False,
                        help="启用外部文档启发的元择时市场门控因子（状态持续/量价一致/波动稳定/情绪修复），仅扩展 market_x")
    parser.add_argument("--kpl-active-only", action=argparse.BooleanOptionalAction, default=False, help="预测域收窄：仅近 N 天上过 kpl 榜单的活跃投机股（同质子集）")
    parser.add_argument("--kpl-active-window", type=int, default=14, help="kpl 活跃判定回看窗口（天）")
    parser.add_argument("--high-risk-only", action=argparse.BooleanOptionalAction, default=False, help="激进收窄：仅保留近窗内出现过高危信号的高危子集")
    parser.add_argument("--high-risk-board", type=float, default=2.0, help="高危连板阈值（题材最高连板 ≥ 此值算高危）")
    parser.add_argument("--high-risk-mode", choices=["negfeedback", "board", "either"], default="negfeedback",
                        help="高危定义：negfeedback=题材负反馈(炸板/题材内跌停,见顶拐点); board=高连板; either=任一")
    parser.add_argument("--moe-experts", type=int, default=0, help=">0 启用 MoE 多专家（按题材/连板/涨跌停状态路由分杀跌模式）")
    parser.add_argument("--state-gate", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--gate-mode", choices=["cross", "film"], default="cross", help="市场门控形态：cross=交叉注意力(个股×市场交互)，film=统一调制")
    parser.add_argument("--max-samples", type=int, default=500_000)
    parser.add_argument("--page-limit", type=int, default=50_000)
    parser.add_argument("--out", required=True, type=Path, help="Model package output directory.")
    parser.add_argument("--feature-schema", type=Path)
    parser.add_argument("--normalization", type=Path)
    parser.add_argument("--seq-len", type=int, default=20)
    parser.add_argument("--d-model", type=int, default=64)
    parser.add_argument("--heads", type=int, default=4)
    parser.add_argument("--layers", type=int, default=2)
    parser.add_argument("--dropout", type=float, default=0.1)
    parser.add_argument("--batch-size", type=int, default=1024)
    parser.add_argument("--epochs", type=int, default=10)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--weight-decay", type=float, default=1e-2)
    parser.add_argument("--val-fraction", type=float, default=0.2)
    parser.add_argument("--purge-days", type=int, default=0, help="验证集起点向后再清洗 N 天，杜绝 fwd 标签窗跨界泄漏。默认= fwd+seqLen 自动推导。")
    parser.add_argument("--loss", choices=["bce", "fbeta", "focal", "rankbce", "date_rankbce", "np"], default="bce")
    parser.add_argument("--pos-weight-scale", type=float, default=1.0,
                        help="BCE/soft-Fβ 正例权重缩放。<1 更保守，减少中高分段误报；默认 1 保持历史行为。")
    parser.add_argument("--rank-weight", type=float, default=0.2, help="rankbce 的 pairwise ranking loss 权重")
    parser.add_argument("--focal-gamma", type=float, default=2.0)
    parser.add_argument("--focal-alpha", type=float, default=0.5)
    parser.add_argument("--beta", type=float, default=2.0)
    # Neyman-Pearson recall60 工作点 loss 超参
    parser.add_argument("--np-recall", type=float, default=0.6, help="NP-loss 锁定的召回工作点（默认 0.6 对齐真双60）")
    parser.add_argument("--np-weight", type=float, default=1.0, help="NP-loss 工作点假阳惩罚权重")
    parser.add_argument("--np-margin", type=float, default=0.1, help="NP-loss 工作点真阳间距权重")
    parser.add_argument("--np-tau", type=float, default=0.5, help="NP-loss 软化温度")
    parser.add_argument("--select-metric", choices=["auc", "prec_at_r60"], default="prec_at_r60",
                        help="early-stopping 选轮指标。默认按 recall60 工作点 precision（对齐真双60目标），而非全局 AUC。")
    # 分位数回归（第四拳：左尾建模）：拟合连续 severity 而非二值标签，信息量更大
    parser.add_argument("--objective", choices=["classify", "quantile"], default="classify",
                        help="classify=二分类BCE；quantile=分位数回归(pinball)拟合连续severity，按最高分位排序选高危票")
    parser.add_argument("--quantiles", type=str, default="0.5,0.7,0.9",
                        help="分位数回归的 τ 列表（逗号分隔），排序打分用最高 τ")
    # 第九招：截面相对标签——每日截面 severity 最大的 frac 比例为正例。
    # p 被 frac 结构性锁定（绝不崩），消除每日 market-wide 齐涨齐跌漂移，留纯个股相对弱势。
    # 用缓存现有 severity+end_di 即时重写二值标签，零重装。0=关闭（用原绝对标签）。
    parser.add_argument("--cs-rank-label-frac", type=float, default=0.0,
                        help="截面相对标签：每日截面 severity 后 frac 比例标正例（0=关闭，用缓存原标签）。"
                             "打破真双60 的 p-AUC 负相关：锁住正例率 p=frac，定理分子不崩。")
    # 标签去插针：drop = w·最深回撤 + (1−w)·终点收益。w=1.0 退回旧版(纯回撤,含插针假急杀)，
    # w=0.5(默认)综合回撤与终点剔除"跌一下就修复创新高"的假命中，w=0.0 纯看终点(太严易误伤)。
    parser.add_argument("--label-dd-weight", type=float, default=0.5,
                        help="下跌标签的最深回撤权重 w∈[0,1]。drop=w·最深回撤+(1−w)·终点收益。"
                             "默认0.5剔除插针式假急杀(盘中跌后立刻修复);1.0=旧版纯最深回撤。")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--device", choices=["auto", "mps", "cpu"], default="auto")
    args = parser.parse_args()

    set_seed(args.seed)
    device = select_device(args.device)
    args.out.mkdir(parents=True, exist_ok=True)

    # 缓存优先：若 --dataset 缓存存在则直接读（秒级），免走 API 重装配（20-30 分钟）。
    use_cache = args.dataset is not None and Path(args.dataset).exists()
    if args.api_base and not use_cache:
        dataset = build_dataset_from_api(args)
        x = torch.from_numpy(dataset["x"])
        market_x = torch.from_numpy(dataset["market_x"])
        y = torch.from_numpy(dataset["y"])
        severity = torch.from_numpy(dataset["severity"])
        state_ids = torch.from_numpy(dataset["state_ids"])
        end_di = dataset["end_di"]  # 每个样本被预测日的 di（时间硬边界切分用）
        feature_schema_path = args.feature_schema or (args.out / "feature_schema.json")
        normalization_path = args.normalization or (args.out / "normalization.json")
        feature_schema_path.write_text(json.dumps(dataset["feature_schema"], ensure_ascii=False, indent=2), encoding="utf-8")
        normalization_path.write_text(json.dumps(dataset["normalization"], ensure_ascii=False, indent=2), encoding="utf-8")
        # 事实数据不变 → 装配后存 NPZ 缓存，后续实验用 --dataset 秒读，免每次重拉 API（装配 20-30 分钟）
        if args.cache_out is not None:
            args.cache_out.parent.mkdir(parents=True, exist_ok=True)
            np.savez_compressed(
                args.cache_out,
                x=dataset["x"], market_x=dataset["market_x"], y=dataset["y"],
                severity=dataset["severity"],
                state_ids=dataset["state_ids"], end_di=dataset["end_di"],
                ts_codes=dataset["ts_codes"], pred_dates=dataset["pred_dates"],
            )
            args.cache_out.with_suffix(".feature_schema.json").write_text(
                json.dumps(dataset["feature_schema"], ensure_ascii=False, indent=2), encoding="utf-8")
            args.cache_out.with_suffix(".normalization.json").write_text(
                json.dumps(dataset["normalization"], ensure_ascii=False, indent=2), encoding="utf-8")
            log(f"样本缓存已存：{args.cache_out}（后续 --dataset {args.cache_out} 秒读复用）")
    else:
        if args.dataset is None:
            raise ValueError("Either --dataset or --api-base is required")
        # feature_schema/normalization 优先用显式参数，否则用缓存旁文件（--cache-out 存的）
        ds_path = Path(args.dataset)
        feature_schema_path = args.feature_schema or ds_path.with_suffix(".feature_schema.json")
        normalization_path = args.normalization or ds_path.with_suffix(".normalization.json")
        if not Path(feature_schema_path).exists() or not Path(normalization_path).exists():
            raise ValueError("--feature-schema/--normalization 缺失且缓存旁文件不存在")
        log(f"从缓存读样本（秒级）：{args.dataset}")
        data = np.load(args.dataset)
        x = torch.from_numpy(data["x"].astype(np.float32))
        y = torch.from_numpy(data["y"].astype(np.float32))
        market_x = torch.from_numpy(data["market_x"].astype(np.float32)) if "market_x" in data else torch.zeros(len(y), 0)
        state_ids = torch.from_numpy(data["state_ids"].astype(np.int64)) if "state_ids" in data else torch.zeros(len(y), dtype=torch.int64)
        end_di = data["end_di"].astype(np.int64) if "end_di" in data else np.arange(len(y))
        # severity 连续急杀强度（分位数回归目标）。旧缓存无此字段时回退用二值 y（退化为分类）。
        severity = torch.from_numpy(data["severity"].astype(np.float32)) if "severity" in data else y.clone()
        feature_schema_path = args.feature_schema
        normalization_path = args.normalization
    if x.ndim != 3:
        raise ValueError(f"dataset x must have shape [n, seq_len, feature_dim], got {tuple(x.shape)}")
    if x.shape[1] != args.seq_len:
        raise ValueError(f"seq_len mismatch: dataset={x.shape[1]} args={args.seq_len}")

    # 第九招：截面相对标签重写（打破真双60 的 p-AUC 负相关）。用缓存现有 severity+end_di
    # 即时重算二值 y，零重装。每日截面 severity 后 frac 比例为正，p 锁定不崩。
    if args.cs_rank_label_frac > 0.0:
        new_y = cross_sectional_label(severity.numpy(), end_di, args.cs_rank_label_frac)
        log(f"截面相对标签：每日后 {args.cs_rank_label_frac:.0%} 为正，p={float(new_y.mean()):.4f}"
            f"（原 p={float(y.mean()):.4f}）")
        y = torch.from_numpy(new_y)

    if not 0.0 <= args.val_fraction < 0.8:
        raise ValueError("--val-fraction must be in [0.0, 0.8)")

    # ── 时间硬边界切分（消除同股相邻窗泄漏）─────────────────────────────────────
    # 样本已按 (end_date, ts) 升序排。按被预测日 di 的分位取 split 边界，
    # 训练 = 边界前；验证 = 边界 + purge gap 之后。purge≥fwd+seqLen 确保验证序列的
    # 全部输入窗 + 标签窗都不与训练样本时间重叠。
    purge = args.purge_days if args.purge_days > 0 else (args.fwd + args.seq_len)
    train_mask, val_mask = time_split(end_di, args.val_fraction, purge)
    sev_train = severity[train_mask]
    x_train, m_train, y_train, s_train, di_train = (
        x[train_mask],
        market_x[train_mask],
        y[train_mask],
        state_ids[train_mask],
        torch.from_numpy(end_di[train_mask].astype(np.int64)),
    )
    x_val, m_val, y_val, s_val = x[val_mask], market_x[val_mask], y[val_mask], state_ids[val_mask]

    # 训练目标：分位回归用 severity，分类用二值 y。DataLoader 同时携带 y/severity/di。
    train_loader = DataLoader(
        TensorDataset(x_train, m_train, y_train, s_train, di_train, sev_train),
        batch_size=args.batch_size,
        shuffle=args.loss != "date_rankbce",
        drop_last=False,
        num_workers=0,
    )
    quantiles = tuple(float(q) for q in args.quantiles.split(",")) if args.objective == "quantile" else (0.5,)
    model = CrashTransformer(
        feature_dim=x.shape[2],
        market_dim=market_x.shape[1],
        d_model=args.d_model,
        heads=args.heads,
        layers=args.layers,
        dropout=args.dropout,
        num_states=NUM_STATES,
        use_state_gate=args.state_gate,
        gate_mode=args.gate_mode,
        moe_experts=args.moe_experts,
        num_quantiles=len(quantiles) if args.objective == "quantile" else 1,
    ).to(device)
    pos = float(y_train.sum().item())
    neg = float(len(y_train) - pos)
    if args.pos_weight_scale <= 0.0:
        raise ValueError("--pos-weight-scale must be > 0")
    pos_weight_value = max(1e-6, (neg / max(1.0, pos)) * args.pos_weight_scale)
    if args.objective == "quantile":
        loss_fn = PinballLoss(quantiles=quantiles)
        log(f"分位数回归模式：quantiles={quantiles}，target=severity，排序打分用最高分位")
    elif args.loss == "fbeta":
        loss_fn = SoftFBetaLoss(beta=args.beta, pos_weight=pos_weight_value)
    elif args.loss == "focal":
        loss_fn = FocalLoss(gamma=args.focal_gamma, alpha=args.focal_alpha)
    elif args.loss == "rankbce":
        loss_fn = RankBceLoss(pos_weight=pos_weight_value, rank_weight=args.rank_weight)
    elif args.loss == "date_rankbce":
        loss_fn = DateRankBceLoss(pos_weight=pos_weight_value, rank_weight=args.rank_weight)
    elif args.loss == "np":
        loss_fn = NeymanPearsonLoss(pos_weight=pos_weight_value, recall_target=args.np_recall,
                                    np_weight=args.np_weight, margin_weight=args.np_margin, tau=args.np_tau)
    else:
        loss_fn = nn.BCEWithLogitsLoss(pos_weight=torch.tensor([pos_weight_value], device=device))
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    log(f"训练开始：device={device} feat_dim={x.shape[2]} train={len(y_train)} val={len(y_val)} 正例率={float(y_train.mean()):.4f} loss={args.loss}")

    # 选最优 val 轮的权重（early stopping）：训练后期普遍过拟合，末轮非最优。
    # 选轮指标默认 prec_at_r60（对齐真双60目标），而非全局 AUC——AUC 最优轮 ≠ 工作点最优轮。
    import copy
    metrics: list[dict[str, Any]] = []
    best_select = -1.0
    best_state = copy.deepcopy(model.state_dict())
    best_epoch = 0
    for epoch in range(args.epochs):
        model.train()
        total_loss = 0.0
        total_n = 0
        for xb, mb, yb, sb, db, sev_b in train_loader:
            xb = xb.to(device); mb = mb.to(device); yb = yb.to(device); sb = sb.to(device); db = db.to(device); sev_b = sev_b.to(device)
            opt.zero_grad(set_to_none=True)
            out = model(xb, mb, sb)
            if args.objective == "quantile":
                loss = loss_fn(out, sev_b)          # 分位回归：target=severity 连续值
            elif args.loss == "date_rankbce":
                loss = loss_fn(out, yb, db)
            else:
                loss = loss_fn(out, yb)
            loss.backward()
            opt.step()
            total_loss += float(loss.item()) * len(yb)
            total_n += len(yb)
        train_eval = evaluate_model(model, x_train, m_train, y_train, s_train, device, args.batch_size)
        val_eval = evaluate_model(model, x_val, m_val, y_val, s_val, device, args.batch_size) if len(y_val) > 0 else None
        val_auc = val_eval["auc"] if val_eval else float("nan")
        # 选轮分数：按 select_metric 取 prec_at_r60 或 auc
        select_score = (
            (val_eval["precision_at_recall60"] if args.select_metric == "prec_at_r60" else val_auc)
            if val_eval else float("-inf")
        )
        if val_eval and select_score > best_select:
            best_select = select_score
            best_state = copy.deepcopy(model.state_dict())
            best_epoch = epoch + 1
        metrics.append({
            "epoch": epoch + 1,
            "loss": total_loss / max(1, total_n),
            "train_auc": train_eval["auc"],
            "val_auc": val_auc,
            "val_prec_at_r60": val_eval["precision_at_recall60"] if val_eval else None,
        })
        print(json.dumps({
            "epoch": epoch + 1,
            "loss": round(total_loss / max(1, total_n), 6),
            "train_auc": round(train_eval["auc"], 4),
            "val_auc": round(val_auc, 4) if val_eval else None,
            "val_prec@r60": round(val_eval["precision_at_recall60"], 4) if val_eval else None,
        }, ensure_ascii=False))

    # 回滚到最优 val 轮
    model.load_state_dict(best_state)
    final_eval = {
        "best_epoch": best_epoch,
        "train": evaluate_model(model, x_train, m_train, y_train, s_train, device, args.batch_size),
        "validation": evaluate_model(model, x_val, m_val, y_val, s_val, device, args.batch_size) if len(y_val) > 0 else None,
        "split": {
            "train_samples": int(train_mask.sum()),
            "val_samples": int(val_mask.sum()),
            "purge_days": purge,
        },
    }
    # regime 分组评估 + 校准度（AUC 看不见市场因子价值，这里专门暴露）
    if len(y_val) > 0:
        final_eval["regime_breakdown"] = evaluate_by_regime(model, x_val, m_val, y_val, s_val, device, args.batch_size)
        log("regime 分组评估完成（高危/中性/低危桶 precision/recall + 校准度）")
    weights_path = args.out / "model.pt"
    torch.save(model.state_dict(), weights_path)
    (args.out / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    (args.out / "eval.json").write_text(json.dumps(final_eval, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest = ModelManifest(
        topic="pivot-crash-stock",
        model_type="transformer_encoder_film_regime_gated_binary",
        format_version=3,
        feature_schema_path=str(feature_schema_path),
        normalization_path=str(normalization_path),
        weights_path=str(weights_path),
        thresholds_path=None,
        device=str(device),
        seed=args.seed,
        seq_len=args.seq_len,
        feature_dim=int(x.shape[2]),
        market_dim=int(market_x.shape[1]),
        d_model=args.d_model,
        heads=args.heads,
        layers=args.layers,
        num_states=NUM_STATES if args.state_gate else 1,
        loss=args.loss,
    )
    (args.out / "manifest.json").write_text(
        json.dumps(asdict(manifest), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(json.dumps({"metrics": metrics, "eval": final_eval, "manifest": asdict(manifest)}, ensure_ascii=False, indent=2))


def time_split(end_di: np.ndarray, val_fraction: float, purge: int) -> tuple[np.ndarray, np.ndarray]:
    """按被预测日 di 的时间硬边界切分。train=边界前；val=边界+purge 之后（中间 purge 段丢弃，防泄漏）。"""
    if val_fraction <= 0.0:
        return np.ones(len(end_di), dtype=bool), np.zeros(len(end_di), dtype=bool)
    order = np.argsort(end_di, kind="stable")
    sorted_di = end_di[order]
    split_idx = int(len(sorted_di) * (1.0 - val_fraction))
    split_idx = max(1, min(split_idx, len(sorted_di) - 1))
    split_di = int(sorted_di[split_idx])
    train_mask = end_di < split_di
    val_mask = end_di >= (split_di + purge)
    return train_mask, val_mask


def cross_sectional_label(severity: np.ndarray, end_di: np.ndarray, frac: float) -> np.ndarray:
    """第九招：每日截面内 severity 最大的 frac 比例标正例（1），其余 0。

    因果安全：标签只用「被预测日同一截面」的 severity 横向比较，不跨时间、不看未来个股。
    每日正例数 = round(当日样本数 · frac)，故全局 p≈frac 被结构性锁定，绝不随阈值崩塌。
    消除每日 market-wide 漂移（整天齐跌被截面排名归一），留纯个股相对弱势 = 空头端纯净信号。
    """
    lab = np.zeros(len(severity), dtype=np.float32)
    order = np.argsort(end_di, kind="stable")
    gs = end_di[order]
    segs = np.split(order, np.where(np.diff(gs) != 0)[0] + 1)
    for seg in segs:
        m = len(seg)
        if m < 5:
            continue
        k = max(1, int(round(m * frac)))
        top = seg[np.argsort(-severity[seg])[:k]]  # 该日 severity 最大的 k 个
        lab[top] = 1.0
    return lab


def build_dataset_from_api(args: argparse.Namespace) -> dict[str, Any]:
    base = args.api_base.rstrip("/")
    log("装配开始：拉 profiles / sentiment-factors …")
    profiles = api_get(base, "/api/internal/research/pivot-crash-stock/profiles")["data"]
    sentiment_rows = api_get(
        base,
        "/api/internal/research/pivot-crash-stock/sentiment-factors",
        {"start": args.start, "end": args.end},
    )["data"]
    log(f"profiles={len(profiles)} sentiment={len(sentiment_rows)} → 拉 daily-projection …")
    daily_by_ts: dict[str, list[dict[str, Any]]] = {}
    dates: set[str] = set()
    daily_rows = 0
    for row in fetch_paged(base, "/api/internal/research/pivot-crash-stock/daily-projection", args.start, args.end, args.page_limit):
        daily_by_ts.setdefault(row["tsCode"], []).append(row)
        dates.add(row["tradeDate"])
        daily_rows += 1
        if daily_rows % 1_000_000 == 0:
            log(f"  daily 已拉 {daily_rows} 行（{len(daily_by_ts)} 股）")
    log(f"daily-projection 完成：{daily_rows} 行 / {len(daily_by_ts)} 股 / {len(dates)} 日")

    # 个股资金流（主力行为方向：case2 出货净流出 ↔ case3 拉升净流入），可选
    mf_by_ts: dict[str, dict[str, dict[str, Any]]] = {}
    if args.moneyflow:
        log("拉 moneyflow …")
        mf_rows = 0
        for row in fetch_paged(base, "/api/internal/research/pivot-crash-stock/moneyflow", args.start, args.end, args.page_limit):
            mf_by_ts.setdefault(row["tsCode"], {})[row["tradeDate"]] = row
            mf_rows += 1
            if mf_rows % 1_000_000 == 0:
                log(f"  moneyflow 已拉 {mf_rows} 行")
        log(f"moneyflow 完成：{mf_rows} 行 / {len(mf_by_ts)} 股")

    # 15min 开盘/尾盘结构（服务端已聚合成每股每日一行），可选。
    intraday15_by_ts: dict[str, dict[str, list[float]]] = {}
    if args.intraday15_factors:
        log("拉 minute15-structure（15min 开盘/尾盘结构）…")
        i15_count = 0
        for row in fetch_paged(base, "/api/internal/research/pivot-crash-stock/minute15-structure", args.start, args.end, args.page_limit):
            intraday15_by_ts.setdefault(row["tsCode"], {})[row["tradeDate"]] = intraday15_feature_values(row)
            i15_count += 1
            if i15_count % 1_000_000 == 0:
                log(f"  minute15-structure 已拉 {i15_count} 行")
        log(f"minute15-structure 完成：{i15_count} 日结构 / {len(intraday15_by_ts)} 股")

    # 涨跌停制度事件（稀疏表，每日仅命中股；正交于连续量价，唯一非量价信号源），可选
    limit_by_ts: dict[str, dict[str, dict[str, Any]]] = {}
    if args.limit_factors or args.composite_label or args.theme_factors or args.traj_factors or args.seal_quality_factors:
        log("拉 limit-list（涨跌停制度事件）…")
        lrows = api_get(base, "/api/internal/research/pivot-crash-stock/limit-list",
                        {"start": args.start, "end": args.end})["data"]
        for row in lrows:
            limit_by_ts.setdefault(row["tsCode"], {})[row["tradeDate"]] = row
        log(f"limit-list 完成：{len(lrows)} 条事件 / {len(limit_by_ts)} 股")

    # 龙虎榜方向极性维度（top_list 汇总 + top_inst 营业部明细），可选。
    # 在此把 top-inst 多条营业部记录按散户(含「拉萨」)/机构(含「机构」)分类聚合成股级净额，
    # 营业部分类是研究参数 → 放装配层（改字样匹配不用重跑入库）。缺失日不丢样本，特征计算层填 0。
    lhb_by_ts: dict[str, dict[str, dict[str, Any]]] = {}
    if args.longhuban:
        log("拉 top-list（龙虎榜汇总：上榜事件 + 净买入方向）…")
        trows = api_get(base, "/api/internal/research/pivot-crash-stock/top-list",
                        {"start": args.start, "end": args.end})["data"]
        for row in trows:
            lhb_by_ts.setdefault(row["tsCode"], {})[row["tradeDate"]] = {
                "onList": 1.0,
                "netRate": row.get("netRate"),
                "netAmount": row.get("netAmount"),
                "amount": row.get("amount"),
                "retailNet": 0.0,
                "instNet": 0.0,
            }
        log(f"top-list 完成：{len(trows)} 条 / {len(lhb_by_ts)} 股")
        log("拉 top-inst（营业部明细：散户/机构席位分类聚合）…")
        irows = api_get(base, "/api/internal/research/pivot-crash-stock/top-inst",
                        {"start": args.start, "end": args.end})["data"]
        retail_acc: dict[tuple[str, str], float] = {}
        inst_acc: dict[tuple[str, str], float] = {}
        for row in irows:
            ts = row["tsCode"]; date = row["tradeDate"]; exalter = row.get("exalter") or ""
            net = row.get("netBuy")
            if net is None:
                buy = row.get("buy") or 0.0; sell = row.get("sell") or 0.0
                net = float(buy) - float(sell)
            else:
                net = float(net)
            if "拉萨" in exalter:                      # 东财拉萨系 = 散户大本营
                retail_acc[(ts, date)] = retail_acc.get((ts, date), 0.0) + net
            elif "机构" in exalter:                    # 机构专用席位
                inst_acc[(ts, date)] = inst_acc.get((ts, date), 0.0) + net
            # 其余营业部（游资等）drop，不进特征
        for (ts, date), v in retail_acc.items():
            rec = lhb_by_ts.setdefault(ts, {}).setdefault(date, {"onList": 1.0, "netRate": None, "netAmount": None, "amount": None, "retailNet": 0.0, "instNet": 0.0})
            rec["retailNet"] = v
        for (ts, date), v in inst_acc.items():
            rec = lhb_by_ts.setdefault(ts, {}).setdefault(date, {"onList": 1.0, "netRate": None, "netAmount": None, "amount": None, "retailNet": 0.0, "instNet": 0.0})
            rec["instNet"] = v
        log(f"top-inst 完成：{len(irows)} 条明细 → 散户席位 {len(retail_acc)} 股日 / 机构席位 {len(inst_acc)} 股日")

    # 题材群体维度（开盘啦榜单）：个股所在题材的集体状态（中观，正交于个股量价），可选。
    # kpl-active-only 时也需要 kpl 数据（判定哪些票近期活跃 + 题材因子全员有效）。
    theme_by_ts: dict[str, dict[str, dict[str, float]]] = {}
    kpl_dates_by_ts: dict[str, set[str]] = {}   # 每只票上过 kpl 榜单的 ISO 日期集合
    kpl_min_date: str | None = None
    if args.theme_factors or args.kpl_active_only:
        log("拉 kpl-list（题材群体 / 活跃域）…")
        krows = api_get(base, "/api/internal/research/pivot-crash-stock/kpl-list",
                        {"start": args.start, "end": args.end})["data"]
        log(f"kpl-list 完成：{len(krows)} 条涨停事件 → 构建题材聚合")
        theme_by_ts = build_theme_factors(krows, limit_by_ts)
        for row in krows:
            kpl_dates_by_ts.setdefault(row["tsCode"], set()).add(row["tradeDate"])
        kpl_min_date = min((row["tradeDate"] for row in krows), default=None)
        log(f"题材因子构建完成：覆盖 {len(theme_by_ts)} 股")

    sorted_dates = sorted(dates)
    di_of_date = {d: i for i, d in enumerate(sorted_dates)}
    date_of_di = {i: d for i, d in enumerate(sorted_dates)}
    # kpl 活跃 di 集合（用于近 N 天活跃域过滤）：ISO 日期 → di
    kpl_active_di_by_ts: dict[str, set[int]] = {}
    if args.kpl_active_only:
        fallback_before = kpl_min_date or KPL_ACTIVE_FALLBACK_BEFORE_DATE
        fallback = build_price_limit_active_dates(daily_by_ts, before_date=fallback_before)
        fallback_events = 0
        for ts, isodates in fallback.items():
            target = kpl_dates_by_ts.setdefault(ts, set())
            before = len(target)
            target.update(isodates)
            fallback_events += len(target) - before
        log(f"pre-KPL 活跃域 fallback：KPL 起点={fallback_before}，日线近似涨停补 {fallback_events} 个活跃锚点 / {len(fallback)} 股")
        for ts, isodates in kpl_dates_by_ts.items():
            if args.high_risk_only:
                # 激进收窄：高危日锚点。negfeedback=题材见顶拐点（炸板/题材内跌停），
                # 这才是「动能枯竭→共振杀跌」的真信号；高连板≠会跌（题材正热，短期反而强势）。
                hr = set()
                for d in isodates:
                    if d not in di_of_date:
                        continue
                    tf = theme_by_ts.get(ts, {}).get(d)
                    if tf is None:
                        continue
                    neg = tf.get("theme_neg", 0.0) > 0.0
                    board = tf.get("theme_board", 0.0) >= args.high_risk_board
                    hit = {"negfeedback": neg, "board": board, "either": neg or board}[args.high_risk_mode]
                    if hit:
                        hr.add(di_of_date[d])
                if hr:
                    kpl_active_di_by_ts[ts] = hr
            else:
                kpl_active_di_by_ts[ts] = {di_of_date[d] for d in isodates if d in di_of_date}
        mode = f"高危子集({args.high_risk_mode})" if args.high_risk_only else "全活跃"
        log(f"活跃域[{mode}]：{len(kpl_active_di_by_ts)} 股近 {args.kpl_active_window} 天可入样本")
    log("build_series（按股构建时序）…")
    series_by_ts = build_series(daily_by_ts, di_of_date, mf_by_ts, limit_by_ts, intraday15_by_ts, lhb_by_ts)
    st_profile = {p["tsCode"]: ("ST" in p["name"].upper()) for p in profiles}
    list_di_by_ts = build_list_di(profiles, sorted_dates)
    log(f"assemble_samples（截面装配 + zscore，{len(sorted_dates)} 个交易日）…")
    samples = assemble_samples(series_by_ts, sorted_dates, date_of_di, st_profile, list_di_by_ts, args.fwd, args.label, args.moneyflow, args.dyn_k, args.roll_w, args.vp_factors, args.rsi_factors, args.limit_factors, args.longhuban, args.composite_label, args.theme_factors, theme_by_ts, kpl_active_di_by_ts if args.kpl_active_only else None, args.kpl_active_window, args.traj_factors, args.seal_quality_factors, args.external_doc_factors, args.behavioral_factors, args.intraday15_factors, args.label_dd_weight)
    log(f"assemble 完成：{len(samples)} 个原始样本")

    # ── 时间硬边界（split_date）：情绪归一化 + 状态分档阈值只能用训练段统计量 ──────
    split_date = time_boundary_date([s[1] for s in samples], args.val_fraction)
    use_market = args.market
    mkt_names = market_feature_names(args.meta_timing_factors) if use_market else []
    market_norm, market_mean, market_sd = normalize_market(
        sentiment_rows, mkt_names, split_date,
    )
    state_edges = state_quantile_edges(sentiment_rows, STATE_AXES, split_date)
    state_by_date = compute_state_ids(sentiment_rows, STATE_AXES, state_edges)
    log(f"split_date={split_date}，build_sequences（滚动 {args.seq_len} 日窗口）…")

    x, market_x, y, state_ids, end_di, severity, ts_codes, pred_dates = build_sequences(
        samples, market_norm, state_by_date, di_of_date, args.seq_len, args.max_samples,
    )
    log(f"装配完成：x={x.shape} market_x={market_x.shape} 正例率={float(y.mean()):.4f} severity均值={float(severity.mean()):.3f}")
    return {
        "x": x.astype(np.float32),
        "market_x": market_x.astype(np.float32),
        "y": y.astype(np.float32),
        "severity": severity.astype(np.float32),
        "state_ids": state_ids.astype(np.int64),
        "end_di": end_di.astype(np.int64),
        "ts_codes": ts_codes,        # 可视化定位：被预测日股票代码
        "pred_dates": pred_dates,    # 可视化定位：被预测日日期

        "feature_schema": {
            "topic": "pivot-crash-stock",
            "formatVersion": 3,
            "source": "ktor-server",
            "seqLen": args.seq_len,
            "labelKind": args.label.upper(),
            "targetHorizon": args.fwd,
            "stockFeatureNames": stock_feature_names(args.moneyflow, args.vp_factors, args.rsi_factors, args.limit_factors, args.longhuban, args.theme_factors, args.traj_factors, args.seal_quality_factors, args.external_doc_factors, args.behavioral_factors, args.intraday15_factors),
            "marketFeatureNames": mkt_names,
            "regimeGate": "FiLM(market_factors ∥ state_embedding) → (γ,β) modulate stock representation",
            "stateAxes": STATE_AXES,
            "numStates": NUM_STATES,
            "splitDate": split_date,
        },
        "normalization": {
            "formatVersion": 2,
            "owner": "quant_research_training.crash_transformer",
            "method": "cross_section_zscore (stock) + train_only_zscore (market)",
            "winsor": [-5.0, 5.0],
            "missingFill": 0.0,
            "marketMean": market_mean,
            "marketSd": market_sd,
            "stateEdges": {axis: edges for axis, edges in zip(STATE_AXES, state_edges)},
        },
    }


def time_boundary_date(sample_dates: list[str], val_fraction: float) -> str:
    """样本被预测日的 (1−val_fraction) 分位日期，作为训练/验证时间硬边界。"""
    if not sample_dates or val_fraction <= 0.0:
        return sample_dates[-1] if sample_dates else "9999-12-31"
    ordered = sorted(sample_dates)
    idx = int(len(ordered) * (1.0 - val_fraction))
    idx = max(0, min(idx, len(ordered) - 1))
    return ordered[idx]


_T0 = time.time()


def log(msg: str) -> None:
    """装配/训练阶段进度日志（带累计秒），任何阶段卡住都能从日志秒级定位，不再盲等。"""
    print(f"[+{time.time() - _T0:7.1f}s] {msg}", flush=True)


def api_get(base: str, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    query = "" if not params else "?" + urlencode(params)
    with urlopen(base + path + query, timeout=600) as res:
        payload = json.loads(res.read().decode("utf-8"))
    if not payload.get("success", False):
        raise RuntimeError(f"API failed: {payload.get('code')} {payload.get('message')}")
    return payload


def fetch_paged(base: str, path: str, start: str, end: str, page_limit: int):
    """keyset 游标分页拉取（(tsCode, tradeDate) 续传），逐行 yield。daily-projection / moneyflow 同构。"""
    after_ts: str | None = None
    after_date: str | None = None
    while True:
        params: dict[str, Any] = {"start": start, "end": end, "limit": page_limit}
        if after_ts is not None and after_date is not None:
            params["afterTsCode"] = after_ts
            params["afterTradeDate"] = after_date
        page = api_get(base, path, params)["data"]
        rows = page["rows"]
        if not rows:
            break
        yield from rows
        next_cursor = page.get("next")
        if next_cursor is None or len(rows) < page_limit:
            break
        # 防御：游标未推进（端点游标格式与入参解析不一致会导致无限拉首页）立即中止，绝不死循环
        if next_cursor["tsCode"] == after_ts and next_cursor["tradeDate"] == after_date:
            raise RuntimeError(f"分页游标未推进，疑似游标格式不匹配：{path} cursor={next_cursor}")
        after_ts = next_cursor["tsCode"]
        after_date = next_cursor["tradeDate"]


def _parse_up_stat(up_stat: str | None) -> float:
    """解析连板状态 upStat 'n/m'：取连板数 n（如 '3/5'=3连板）。无则 0。"""
    if not up_stat:
        return 0.0
    head = up_stat.split("/")[0].strip()
    try:
        return float(head)
    except ValueError:
        return 0.0


def _parse_board_height(status: str | None) -> float:
    """解析连板高度「N天M板」取 M（板数）；「首板」=1。无则 0。"""
    if not status:
        return 0.0
    if "首板" in status:
        return 1.0
    m = re.search(r"(\d+)\s*板", status)
    if m:
        return float(m.group(1))
    m2 = re.search(r"(\d+)", status)
    return float(m2.group(1)) if m2 else 0.0


def build_theme_factors(
    krows: list[dict[str, Any]],
    limit_by_ts: dict[str, dict[str, dict[str, Any]]],
) -> dict[str, dict[str, dict[str, float]]]:
    """
    构建题材群体因子（个股所在题材的集体状态）。返回 {ts: {date: {factor: value}}}。

    逻辑（用户洞察）：
      ① 题材运行越久/连板越高 → 透支 → 越易共振杀跌（时间维度）
      ② 题材内负反馈（跌停/炸板）越多 → 动能枯竭 → 越易共振杀跌
      ③ 个股所在题材越热（涨停数多）→ 资金护盘；非热门题材股更易跟跌

    数据：kpl_list 给涨停股的 theme/status（题材活跃/连板）；
         limit_by_ts 给全市场跌停/炸板（题材内负反馈）。题材是 ts 的中观群体信号。
    """
    # 按 date 聚合每个题材的状态
    # theme_stat[date][theme] = {"zt": 涨停数, "max_board": 最高连板, "members": set(ts)}
    theme_stat: dict[str, dict[str, dict[str, Any]]] = {}
    # ts 在每个 date 的题材归属（取该股当日涨停记录的 theme 列表）
    ts_themes: dict[str, dict[str, list[str]]] = {}
    # 题材首次出现日期（算活跃天数）
    theme_first_date: dict[str, str] = {}

    for row in krows:
        ts = row["tsCode"]; date = row["tradeDate"]
        themes = [t.strip() for t in (row.get("theme") or "").split("、") if t.strip()]
        board = _parse_board_height(row.get("status"))
        ts_themes.setdefault(ts, {})[date] = themes
        for th in themes:
            theme_first_date.setdefault(th, date)
            d = theme_stat.setdefault(date, {}).setdefault(th, {"zt": 0, "max_board": 0.0, "members": set()})
            d["zt"] += 1
            d["max_board"] = max(d["max_board"], board)
            d["members"].add(ts)

    # 题材活跃天数：该题材从首现到当前 date 的自然区间内出现次数（用出现过的日期数近似）
    theme_active_dates: dict[str, set[str]] = {}
    for date, themes in theme_stat.items():
        for th in themes:
            theme_active_dates.setdefault(th, set()).add(date)

    # 题材内负反馈密度：题材成员当日在 limit_by_ts 跌停/炸板的占比
    def neg_feedback(date: str, members: set[str]) -> float:
        if not members:
            return 0.0
        neg = 0
        for m in members:
            lim = limit_by_ts.get(m, {}).get(date)
            if lim and lim.get("limitType") in ("D", "Z"):
                neg += 1
        return neg / len(members)

    out: dict[str, dict[str, dict[str, float]]] = {}
    sorted_all_dates = sorted(theme_stat.keys())
    for ts, by_date in ts_themes.items():
        for date, themes in by_date.items():
            if not themes:
                continue
            # 取个股所在题材里最强的那个（涨停数最多）作为代表
            best_zt = 0.0; best_board = 0.0; best_neg = 0.0; best_active = 0.0
            for th in themes:
                st = theme_stat.get(date, {}).get(th)
                if st is None:
                    continue
                zt = float(st["zt"])
                if zt >= best_zt:
                    best_zt = zt
                    best_board = st["max_board"]
                    best_neg = neg_feedback(date, st["members"])
                    # 活跃天数：该题材出现过的总天数中 ≤ date 的部分
                    active = sum(1 for d in theme_active_dates.get(th, ()) if d <= date)
                    best_active = float(active)
            out.setdefault(ts, {})[date] = {
                "theme_heat": best_zt,         # 题材热度（涨停数）
                "theme_board": best_board,     # 题材最高连板（透支度）
                "theme_neg": best_neg,         # 题材内负反馈密度（枯竭度）
                "theme_active": best_active,   # 题材活跃天数（运行时间）
            }
    return out


def build_price_limit_active_dates(
    daily_by_ts: dict[str, list[dict[str, Any]]],
    before_date: str,
    limit_up_threshold: float = 0.095,
) -> dict[str, set[str]]:
    """
    pre-KPL 活跃域 fallback：KPL 题材榜没有覆盖 2000..2018，但研究总窗口可能覆盖到 2000。
    对 KPL 起点之前的区间，用日线 close_qfq 单日涨幅近似涨停/强封板活跃锚点。

    该 fallback 只服务 active-only 过滤，不生成题材/连板特征；避免把“数据源缺失”误判为“不活跃”。
    """
    out: dict[str, set[str]] = {}
    for ts, rows in daily_by_ts.items():
        prev_close: float | None = None
        for row in sorted(rows, key=lambda r: r["tradeDate"]):
            date = row["tradeDate"]
            close = float(row.get("closeQfq") or 0.0)
            if prev_close is not None and date < before_date and prev_close > 0.0 and close > 0.0:
                ret = close / prev_close - 1.0
                if math.isfinite(ret) and ret >= limit_up_threshold:
                    out.setdefault(ts, set()).add(date)
            if close > 0.0:
                prev_close = close
    return out


def build_series(
    daily_by_ts: dict[str, list[dict[str, Any]]],
    di_of_date: dict[str, int],
    mf_by_ts: dict[str, dict[str, dict[str, Any]]] | None = None,
    limit_by_ts: dict[str, dict[str, dict[str, Any]]] | None = None,
    intraday15_by_ts: dict[str, dict[str, list[float]]] | None = None,
    lhb_by_ts: dict[str, dict[str, dict[str, Any]]] | None = None,
) -> dict[str, dict[str, dict[int, float]]]:
    mf_by_ts = mf_by_ts or {}
    limit_by_ts = limit_by_ts or {}
    intraday15_by_ts = intraday15_by_ts or {}
    lhb_by_ts = lhb_by_ts or {}
    out: dict[str, dict[str, dict[int, float]]] = {}
    for ts, rows in daily_by_ts.items():
        rows.sort(key=lambda r: r["tradeDate"])
        close_by_di: dict[int, float] = {}
        ret: dict[int, float] = {}
        tnr: dict[int, float] = {}
        pe: dict[int, float] = {}
        amount: dict[int, float] = {}
        mf_net: dict[int, float] = {}    # 主力净流入额（万元，正=吸筹/拉升，负=出货）
        mf_lg: dict[int, float] = {}     # 大单净额 = buy_lg+buy_elg − sell_lg−sell_elg
        mf_elg_ratio: dict[int, float] = {}  # 特大单净额占总主动盘比（机构/游资主力净方向）
        lim_down: dict[int, float] = {}  # 跌停命中（D）
        lim_blast: dict[int, float] = {} # 炸板（Z，封板被打开，承接弱）
        lim_up: dict[int, float] = {}    # 涨停命中（U，接力风险）
        lim_open: dict[int, float] = {}  # 打开次数（封板质量，越多越弱）
        lim_conn: dict[int, float] = {}  # 连板高度（透支度）
        lim_fd_amount: dict[int, float] = {}      # 封单额
        lim_limit_amount: dict[int, float] = {}   # 板上成交额
        lim_float_mv: dict[int, float] = {}       # 流通市值
        lim_limit_times: dict[int, float] = {}    # 封板/回封次数
        i15: list[dict[int, float]] = [dict() for _ in INTRADAY15_FEATURE_NAMES]
        lhb_on: dict[int, float] = {}        # 上榜事件 0/1
        lhb_net_rate: dict[int, float] = {}  # 龙虎榜净买入占比
        lhb_retail: dict[int, float] = {}    # 散户席位净额 / amount
        lhb_inst: dict[int, float] = {}      # 机构席位净额 / amount
        prev_close: float | None = None
        mf_dates = mf_by_ts.get(ts, {})
        lim_dates = limit_by_ts.get(ts, {})
        i15_dates = intraday15_by_ts.get(ts, {})
        lhb_dates = lhb_by_ts.get(ts, {})
        for row in rows:
            date = row["tradeDate"]
            di = di_of_date[date]
            close = float(row["closeQfq"])
            if close > 0.0:
                close_by_di[di] = close
            if row.get("turnover") is not None:
                tnr[di] = float(row["turnover"])
            if row.get("peTtm") is not None:
                pe[di] = float(row["peTtm"])
            if row.get("mvCirc") is not None:
                amount[di] = float(row["mvCirc"])
            if prev_close is not None and prev_close > 0.0 and close > 0.0:
                r = math.log(close / prev_close)
                if math.isfinite(r) and abs(r) < 0.5:
                    ret[di] = r
            if close > 0.0:
                prev_close = close
            mf = mf_dates.get(date)
            if mf is not None:
                net = mf.get("netMfAmount")
                if net is not None:
                    mf_net[di] = float(net)
                blg = float(mf.get("buyLgAmount") or 0.0); slg = float(mf.get("sellLgAmount") or 0.0)
                belg = float(mf.get("buyElgAmount") or 0.0); selg = float(mf.get("sellElgAmount") or 0.0)
                mf_lg[di] = (blg + belg) - (slg + selg)
                denom = abs(blg) + abs(slg) + abs(belg) + abs(selg) + 1e-6
                mf_elg_ratio[di] = (belg - selg) / denom
            lim = lim_dates.get(date)
            if lim is not None:
                lt = lim.get("limitType")
                lim_down[di] = 1.0 if lt == "D" else 0.0
                lim_blast[di] = 1.0 if lt == "Z" else 0.0
                lim_up[di] = 1.0 if lt == "U" else 0.0
                lim_open[di] = float(lim.get("openTimes") or 0.0)
                lim_conn[di] = _parse_up_stat(lim.get("upStat"))
                lim_fd_amount[di] = float(lim.get("fdAmount") or 0.0)
                lim_limit_amount[di] = float(lim.get("limitAmount") or 0.0)
                lim_float_mv[di] = float(lim.get("floatMv") or 0.0)
                lim_limit_times[di] = float(lim.get("limitTimes") or 0.0)
            i15_row = i15_dates.get(date)
            if i15_row is not None:
                for idx, value in enumerate(i15_row):
                    i15[idx][di] = value
            lhb = lhb_dates.get(date)
            if lhb is not None:
                lhb_on[di] = 1.0
                amt = lhb.get("amount")
                denom = abs(float(amt)) + 1e-6 if amt else None
                nr = lhb.get("netRate")
                if nr is not None:
                    # net_rate 原值(净买额占比%)，不除100：实测它是龙虎榜最强方向特征
                    # (纯净口径 AUC 0.63，方向明确子集 0.73)，除100会压缩尺度削弱信号；zscore 统一标准化。
                    lhb_net_rate[di] = float(nr)
                elif denom is not None and lhb.get("netAmount") is not None:
                    lhb_net_rate[di] = float(lhb["netAmount"]) / denom * 100.0   # 回退也对齐%量级
                # 散户/机构席位净额 / 当日总成交额（去量纲、跨股可比，与资金流同口径）
                if denom is not None:
                    lhb_retail[di] = float(lhb.get("retailNet") or 0.0) / denom
                    lhb_inst[di] = float(lhb.get("instNet") or 0.0) / denom
        out[ts] = {"close": close_by_di, "ret": ret, "tnr": tnr, "pe": pe, "amount": amount,
                   "mf_net": mf_net, "mf_lg": mf_lg, "mf_elg_ratio": mf_elg_ratio,
                   "lim_down": lim_down, "lim_blast": lim_blast, "lim_up": lim_up,
                   "lim_open": lim_open, "lim_conn": lim_conn,
                   "lim_fd_amount": lim_fd_amount, "lim_limit_amount": lim_limit_amount,
                   "lim_float_mv": lim_float_mv, "lim_limit_times": lim_limit_times,
                   "lhb_on": lhb_on, "lhb_net_rate": lhb_net_rate,
                   "lhb_retail": lhb_retail, "lhb_inst": lhb_inst,
                   **{f"i15_{idx}": col for idx, col in enumerate(i15)}}
    return out


def _safe_log_ret(close: float, open_: float) -> float:
    if close > 0.0 and open_ > 0.0:
        value = math.log(close / open_)
        return value if math.isfinite(value) and abs(value) < 0.5 else 0.0
    return 0.0


def intraday15_feature_values(row: dict[str, Any]) -> list[float]:
    """15min 日内结构聚合行 → 特征。只用 t 日已知开盘/尾盘结构，预测 t+1..。"""
    eps = 1e-6
    fo = float(row.get("firstOpen") or 0.0)
    fh = float(row.get("firstHigh") or 0.0)
    fl = float(row.get("firstLow") or 0.0)
    fc = float(row.get("firstClose") or 0.0)
    lo = float(row.get("lastOpen") or 0.0)
    lh = float(row.get("lastHigh") or 0.0)
    ll = float(row.get("lastLow") or 0.0)
    lc = float(row.get("lastClose") or 0.0)
    last_amount = float(row.get("lastAmount") or 0.0)
    total_amount = float(row.get("totalAmount") or 0.0)
    first_range = max(fh - fl, 0.0)
    last_range = max(lh - ll, 0.0)
    return [
        _safe_log_ret(fc, fo),
        first_range / (fo + eps) if fo > 0.0 else 0.0,
        max(fh - max(fo, fc), 0.0) / (fo + eps) if fo > 0.0 else 0.0,
        _safe_log_ret(lc, lo),
        last_range / (lo + eps) if lo > 0.0 else 0.0,
        (lc - ll) / (last_range + eps) if last_range > 0.0 else 0.5,
        last_amount / (total_amount + eps) if total_amount > 0.0 else 0.0,
        1.0,
    ]


def build_intraday15_factors(rows: list[dict[str, Any]]) -> dict[str, dict[str, list[float]]]:
    out: dict[str, dict[str, list[float]]] = {}
    for row in rows:
        out.setdefault(row["tsCode"], {})[row["tradeDate"]] = intraday15_feature_values(row)
    return out


def build_list_di(profiles: list[dict[str, Any]], sorted_dates: list[str]) -> dict[str, int]:
    out: dict[str, int] = {}
    for profile in profiles:
        raw = profile.get("listDate")
        if not raw:
            continue
        idx = bisect.bisect_left(sorted_dates, raw)
        if idx < len(sorted_dates):
            out[profile["tsCode"]] = idx
    return out


def assemble_samples(
    series_by_ts: dict[str, dict[str, dict[int, float]]],
    sorted_dates: list[str],
    date_of_di: dict[int, str],
    st_profile: dict[str, bool],
    list_di_by_ts: dict[str, int],
    fwd: int,
    label_kind: str,
    use_moneyflow: bool = False,
    dyn_k: float = 1.0,
    roll_w: int = 20,
    use_vp: bool = False,
    use_rsi: bool = False,
    use_limit: bool = False,
    use_longhuban: bool = False,
    composite_label: bool = False,
    use_theme: bool = False,
    theme_by_ts: dict[str, dict[str, dict[str, float]]] | None = None,
    kpl_active_di_by_ts: dict[str, set[int]] | None = None,
    kpl_active_window: int = 14,
    use_traj: bool = False,
    use_seal_quality: bool = False,
    use_external_doc: bool = False,
    use_behavioral: bool = False,
    use_intraday15: bool = False,
    label_dd_weight: float = 0.5,
) -> list[tuple[str, str, list[float], float]]:
    """
    标签：动态大跌阈值（通用大跌预警，非选股）。
      threshold_t = −k · σ_blend,t          （μ_drop≈0，剥β后典型 excess 中枢约 0）
      label = 1  当  excess ≤ threshold_t  且  drop < 0
    σ_blend,t = 0.5·σ_cs,t + 0.5·σ_roll,t
      σ_cs,t   = 当日全市场 fwd 日 excess 的横截面标准差（同期分化度，恐慌日自动放宽）
      σ_roll,t = 过去 roll_w 日市场指数收益的滚动波动率（历史 VIX 式，作平滑）
    阈值只依赖市场整体、不依赖被预测个股自身 → 避免循环定义；k 为业务参数，固定不可学。
    业务含义：相对当时市场环境的【异常下跌】——同样跌 6%，恐慌日是跟跌(不标1)、平稳日是个股自崩(标1)。
    label_kind='dynamic' 时启用动态阈值；其余('abs'/'rel'/'resid')保持固定阈值（对照）。
    """
    long_w = 20
    short_w = 5
    pe_hist_w = 60
    warm = max(long_w, pe_hist_w) + 1
    n_feat = len(stock_feature_names(
        use_moneyflow,
        use_vp,
        use_rsi,
        use_limit,
        use_longhuban,
        use_theme,
        use_traj,
        use_seal_quality,
        use_external_doc,
        use_behavioral,
        use_intraday15,
    ))
    traj_win = 10  # 轨迹回看窗
    theme_by_ts = theme_by_ts or {}
    mkt_day_ret = market_day_returns(series_by_ts)
    # 市场未来 fwd 日【区间内最大回撤】(路径依赖)：min over k 的累计收益，而非终点收益。
    # 与个股 drop 同口径，剥β才一致。
    market_fwd: dict[int, float] = {}
    for di in range(len(sorted_dates)):
        vals = [mkt_day_ret.get(di + k) for k in range(1, fwd + 1)]
        if all(v is not None for v in vals):
            cum = 0.0
            worst = 0.0
            for v in vals:
                cum += v
                worst = min(worst, cum)
            market_fwd[di] = math.exp(worst) - 1.0
    # σ_roll,t：过去 roll_w 日市场指数日收益的滚动波动率（历史项，平滑用；以 fwd 累计同量纲）
    sigma_roll: dict[int, float] = {}
    for di in range(len(sorted_dates)):
        hist = [mkt_day_ret.get(di - k) for k in range(1, roll_w + 1)]
        hist = [h for h in hist if h is not None]
        if len(hist) >= roll_w // 2:
            daily_sd = float(np.std(np.array(hist, dtype=np.float64), ddof=1))
            sigma_roll[di] = daily_sd * math.sqrt(fwd)   # 日波动 → fwd 日累计同量纲
    samples: list[tuple[str, str, list[float], float]] = []
    total_di = len(sorted_dates)
    for di in range(warm, total_di):
        if (di - warm) % 200 == 0:
            log(f"  assemble 截面进度 {di}/{total_di}（已积累 {len(samples)} 样本）")
        raw_rows: list[tuple[str, list[float], float]] = []
        for ts, s in series_by_ts.items():
            # 预测域收窄：仅近 kpl_active_window 天内上过 kpl 榜单的活跃投机股入样本
            if kpl_active_di_by_ts is not None:
                active = kpl_active_di_by_ts.get(ts)
                if active is None or not any((di - w) in active for w in range(0, kpl_active_window + 1)):
                    continue
            tnr_today = s["tnr"].get(di)
            pe_today = s["pe"].get(di)
            if tnr_today is None or pe_today is None:
                continue
            tnr_avg_l = win_avg(s["tnr"], di, long_w)
            tnr_avg_s = win_avg(s["tnr"], di, short_w)
            vol_s = win_vol(s["ret"], di, short_w)
            vol_l = win_vol(s["ret"], di, long_w)
            pe_avg_h = win_avg(s["pe"], di, pe_hist_w)
            pe_prev = s["pe"].get(di - long_w)
            mom_s = win_sum(s["ret"], di, short_w)
            mom_l = win_sum(s["ret"], di, long_w)
            tnr_pct = win_pct_rank(s["tnr"], di, long_w, tnr_today)  # 换手历史分位（热度连续谱）
            if None in (tnr_avg_l, tnr_avg_s, vol_s, vol_l, pe_avg_h, pe_prev, mom_s, mom_l, tnr_pct):
                continue
            if tnr_avg_l <= 0.0 or vol_l <= 0.0 or pe_avg_h <= 0.0 or pe_prev <= 0.0:
                continue
            vals = [s["ret"].get(di + k) for k in range(1, fwd + 1)]
            if not all(v is not None for v in vals):
                continue
            # drop = 未来 fwd 日下跌强度。旧版只取【区间内最大回撤】(路径最低点)，会把
            # 「盘中插针一次、随即修复甚至创新高」的票误判为大跌（实证 s5 域约 1/5 命中是
            # 这种假急杀，如 603332.SH 跌5.5%后立刻反弹创新高）。
            # 修正：drop = w_dd·最深回撤 + (1−w_dd)·终点收益。真急杀=跌下去且没修复
            # (回撤深 且 终点也低)；插针修复票=回撤深但终点收回 → 综合后不算大跌，被剔除。
            cum = 0.0
            max_dd = 0.0
            for v in vals:
                cum += v
                max_dd = min(max_dd, cum)
            end_ret = cum                    # fwd 日终点累计对数收益
            w_dd = label_dd_weight           # 最深回撤权重（默认 0.5，可调）
            drop_log = w_dd * max_dd + (1.0 - w_dd) * end_ret
            drop = math.exp(drop_log) - 1.0
            mkt = market_fwd.get(di)
            if mkt is None:
                continue
            excess = drop - mkt
            if st_profile.get(ts, False):
                continue
            list_di = list_di_by_ts.get(ts)
            if list_di is not None and (di - list_di) < 60:
                continue
            features = [
                tnr_today / tnr_avg_l,
                tnr_avg_s / tnr_avg_l,
                vol_s / vol_l,
                (pe_today - pe_avg_h) / pe_avg_h,
                (pe_today - pe_prev) / pe_prev,
                mom_s - mom_l * (short_w / long_w),
                math.log(tnr_today + 1e-6),
                tnr_pct,                                       # 换手·历史分位
                mom_s * (tnr_today / tnr_avg_l),               # 量价·放量方向（短动量×放量倍数：放量涨>0，放量跌<0）
            ]
            if use_moneyflow:
                mf_net = s["mf_net"].get(di)
                mf_lg = s["mf_lg"].get(di)
                mf_elg = s["mf_elg_ratio"].get(di)
                if mf_net is None or mf_lg is None or mf_elg is None:
                    continue   # 资金流缺失则跳过该样本，保证列对齐
                mv = s["amount"].get(di)   # 流通市值（万元同量纲代理），用于把净流入额无量纲化
                inflow_rate = mf_net / (abs(mv) + 1e-6) if mv else mf_net
                features += [inflow_rate, mf_lg, mf_elg]
            if use_vp:
                # 个股级 VV/VP 量价因子（算子作用到本股 ret/tnr 序列）
                vp_vals = (
                    vv5_pulse_z(s["tnr"], di),
                    vv3_run_length(s["tnr"], di),
                    vp2v_accel(s["tnr"], di),
                    vp0_coordination(s["ret"], s["tnr"], di),
                    vpbeta_elasticity(s["ret"], s["tnr"], di),
                    vv8_lead_share(s["ret"], s["tnr"], di),
                )
                if any(x is None for x in vp_vals):
                    continue   # 量价因子缺口则跳过样本，保证列对齐
                features += list(vp_vals)
            if use_rsi:
                # RSI 及高阶（正向预警：超买亢奋/快速放大 → 容易回落）
                rsi_vals = (
                    rsi(s["ret"], di),
                    rsi_delta(s["ret"], di),
                    rsi_accel(s["ret"], di),
                )
                if any(x is None for x in rsi_vals):
                    continue
                features += list(rsi_vals)
            if use_limit:
                # 涨跌停制度因子（当日事件，缺省=0=未命中；因果，只用 ≤di 的 t-1 状态？
                # 注意：这里用【当日 di】的涨跌停状态作特征——它在 di 收盘后已知，预测 di+1..di+fwd，不泄漏。
                features += [
                    s["lim_down"].get(di, 0.0),
                    s["lim_blast"].get(di, 0.0),
                    s["lim_up"].get(di, 0.0),
                    math.log1p(s["lim_open"].get(di, 0.0)),
                    s["lim_conn"].get(di, 0.0),
                ]
            if use_longhuban:
                # 龙虎榜方向极性因子（当日 di 收盘后已知，预测 di+1..di+fwd，不泄漏）。
                # 缺省=0：未上榜则上榜事件=0、其余维度=0（上榜事件位自带"有没有上榜"标记，不丢样本）。
                features += [
                    s["lhb_on"].get(di, 0.0),         # 上榜事件 0/1
                    s["lhb_net_rate"].get(di, 0.0),   # 龙虎榜净买入占比（方向）
                    s["lhb_retail"].get(di, 0.0),     # 散户席位净额 / 成交额
                    s["lhb_inst"].get(di, 0.0),       # 机构席位净额 / 成交额
                ]
            if use_theme:
                # 题材群体因子（个股所在题材集体状态；非活跃题材股全 0=中性，正合"非热门易跟跌"语义）
                tf = theme_by_ts.get(ts, {}).get(date_of_di[di])
                if tf is None:
                    features += [0.0, 0.0, 0.0, 0.0]
                else:
                    features += [
                        math.log1p(tf["theme_heat"]),
                        tf["theme_board"],
                        tf["theme_neg"],
                        math.log1p(tf["theme_active"]),
                    ]
            if use_traj:
                # 轨迹因子：生命周期位置（连了几天/距上次涨停/炸板历史/涨停密度），非当日快照
                lim_up = s["lim_up"]; lim_blast = s["lim_blast"]; lim_conn = s["lim_conn"]
                # 连板持续天数：从 di 回溯连续有涨停记录的天数
                conn_days = 0
                k = di
                while lim_up.get(k, 0.0) > 0.5 or lim_conn.get(k, 0.0) >= 1.0:
                    conn_days += 1; k -= 1
                    if conn_days > traj_win:
                        break
                # 距上次涨停天数（窗内）
                last_up = traj_win + 1
                for w in range(0, traj_win + 1):
                    if lim_up.get(di - w, 0.0) > 0.5:
                        last_up = w; break
                # 窗内炸板次数 + 涨停密度
                blast_cnt = sum(lim_blast.get(di - w, 0.0) for w in range(0, traj_win + 1))
                up_density = sum(lim_up.get(di - w, 0.0) for w in range(0, traj_win + 1)) / (traj_win + 1)
                features += [float(conn_days), float(last_up), float(blast_cnt), float(up_density)]
            if use_seal_quality:
                fd = s["lim_fd_amount"].get(di, 0.0)
                limit_amt = s["lim_limit_amount"].get(di, 0.0)
                float_mv = s["lim_float_mv"].get(di, 0.0)
                open_times = s["lim_open"].get(di, 0.0)
                limit_times = s["lim_limit_times"].get(di, 0.0)
                blast = s["lim_blast"].get(di, 0.0)
                fd_float = fd / (float_mv + 1e-6) if float_mv > 0.0 else 0.0
                fd_limit = fd / (limit_amt + 1e-6) if limit_amt > 0.0 else 0.0
                open_weak = math.log1p(open_times)
                features += [
                    math.log1p(max(fd_float, 0.0)),
                    math.log1p(max(fd_limit, 0.0)),
                    open_weak,
                    math.log1p(max(limit_times, 0.0)),
                    blast * (1.0 + open_weak),
                ]
            if use_external_doc:
                beta20 = market_beta(s["ret"], mkt_day_ret, di, long_w)
                mkt_mom_s = win_sum(mkt_day_ret, di, short_w)
                mkt_mom_l = win_sum(mkt_day_ret, di, long_w)
                tnr_prev = s["tnr"].get(di - 1)
                tnr_prev_pct = win_pct_rank(s["tnr"], di - 1, long_w, tnr_prev) if tnr_prev is not None else None
                if None in (beta20, mkt_mom_s, mkt_mom_l, tnr_prev_pct):
                    continue
                residual_mom_s = mom_s - beta20 * mkt_mom_s
                residual_mom_l = mom_l - beta20 * mkt_mom_l
                crowding_fade = tnr_prev_pct * math.log((tnr_avg_s + 1e-6) / (tnr_today + 1e-6))
                crowding_accel = math.log((tnr_today + 1e-6) / (tnr_avg_s + 1e-6)) - math.log((tnr_avg_s + 1e-6) / (tnr_avg_l + 1e-6))
                features += [residual_mom_s, residual_mom_l, beta20, crowding_fade, crowding_accel]
            if use_behavioral:
                close_today = s["close"].get(di)
                hi_252 = win_max_available(s["close"], di, 252, min_count=20)
                hi_60 = win_max_available(s["close"], di, 60, min_count=20)
                if close_today is None or hi_252 is None or hi_60 is None or close_today <= 0.0:
                    continue
                last_up = traj_win + 1
                for w in range(0, traj_win + 1):
                    if s["lim_up"].get(di - w, 0.0) > 0.5:
                        last_up = w
                        break
                features += [
                    (hi_252 - close_today) / close_today,
                    (hi_60 - close_today) / close_today,
                    tnr_pct * (vol_s / (vol_l + 1e-6)),
                    math.log1p(last_up),
                ]
            if use_intraday15:
                features += [s.get(f"i15_{idx}", {}).get(di, 0.0) for idx in range(len(INTRADAY15_FEATURE_NAMES))]
            # 复合标签需要：未来 fwd 日内是否跌停命中（实盘=卖不掉的灾难）
            future_limit_down = 0.0
            if composite_label:
                for k in range(1, fwd + 1):
                    if s["lim_down"].get(di + k, 0.0) > 0.5:
                        future_limit_down = 1.0
                        break
            # 延后定标签：先收集 (ts, features, drop, excess, future_limit_down)
            raw_rows.append((ts, features, drop, excess, future_limit_down))
        if len(raw_rows) < 100:
            continue
        # ── 动态大跌阈值（该截面统一）：σ_blend = 0.5·横截面分化 + 0.5·历史滚动波动 ──
        excesses = np.array([r[3] for r in raw_rows], dtype=np.float64)
        sigma_cs = float(np.std(excesses, ddof=1)) if len(excesses) > 1 else 0.0
        sigma_r = sigma_roll.get(di, sigma_cs)
        sigma_blend = 0.5 * sigma_cs + 0.5 * sigma_r
        thr = -dyn_k * sigma_blend                # μ_drop≈0（剥β后 excess 中枢≈0）
        labeled_rows: list[tuple[str, list[float], float, float]] = []
        # severity = 标准化急杀强度（连续）：severity = (thr − excess) / sigma_blend。
        # severity > 0 ⟺ excess < thr ⟺ 动态正例；数值上编码「超过急杀阈值多少个 σ」。
        # 分位数回归直接拟合 severity 左尾，二值 label = 1[severity > 0] 与之同口径——
        # severity 是二值 label 的连续充分统计量（保留"跌多深"信息，符号函数即原标签）。
        sev_scale = sigma_blend if sigma_blend > 1e-9 else 1.0
        for ts, features, drop, excess, future_limit_down in raw_rows:
            if label_kind == "dynamic":
                label = 1.0 if (excess <= thr and drop < 0.0) else 0.0
                severity = (thr - excess) / sev_scale
            elif label_kind == "abs":
                label = 1.0 if drop <= -0.10 else 0.0
                severity = (-0.10 - drop) / sev_scale
            elif label_kind == "rel":
                label = 1.0 if excess <= -0.05 else 0.0
                severity = (-0.05 - excess) / sev_scale
            else:  # resid
                label = 1.0 if (excess <= -0.05 and drop < 0.0) else 0.0
                severity = (-0.05 - excess) / sev_scale
            # 复合标签：回撤 OR 未来跌停命中（实盘损失=跌幅+卖不掉，跌停即使幅度小也是灾难）
            if composite_label and future_limit_down > 0.5:
                label = 1.0
                severity = max(severity, 0.0)
            labeled_rows.append((ts, features, label, severity))
        z_cols = [zscore([r[1][c] for r in labeled_rows]) for c in range(n_feat)]
        date = date_of_di[di]
        for i, (ts, _, label, severity) in enumerate(labeled_rows):
            samples.append((ts, date, [z_cols[c][i] for c in range(n_feat)], label, severity))
    samples.sort(key=lambda r: (r[1], r[0]))
    return samples


def market_day_returns(series_by_ts: dict[str, dict[str, dict[int, float]]]) -> dict[int, float]:
    sums: dict[int, float] = {}
    counts: dict[int, int] = {}
    for s in series_by_ts.values():
        for di, value in s["ret"].items():
            sums[di] = sums.get(di, 0.0) + value
            counts[di] = counts.get(di, 0) + 1
    return {di: sums[di] / counts[di] for di in counts if counts[di] >= 20}


def normalize_market(
    rows: list[dict[str, Any]], names: list[str], split_date: str,
) -> tuple[dict[str, list[float]], list[float], list[float]]:
    """市场情绪因子 zscore：均值方差**只用训练段（tradeDate < split_date）**，杜绝未来泄漏。"""
    if not names:
        return {}, [], []
    raw = market_raw_values(rows, names)
    arr = np.array([r[1] for r in raw], dtype=np.float64)
    train_idx = np.array([i for i, r in enumerate(raw) if r[0] < split_date], dtype=np.int64)
    fit = arr[train_idx] if len(train_idx) > 1 else arr
    mean = fit.mean(axis=0)
    sd = fit.std(axis=0, ddof=1)
    sd[sd == 0.0] = 1.0
    norm = np.clip((arr - mean) / sd, -5.0, 5.0)
    return (
        {raw[i][0]: norm[i].astype(np.float32).tolist() for i in range(len(raw))},
        mean.tolist(),
        sd.tolist(),
    )


def market_raw_values(rows: list[dict[str, Any]], names: list[str]) -> list[tuple[str, list[float]]]:
    rows_sorted = sorted(rows, key=lambda r: r["tradeDate"])
    base_by_date: dict[str, dict[str, float]] = {}
    d4_hist: list[float] = []
    vpm_ret_hist: list[float] = []
    e1_hist: list[float] = []
    meta_by_date: dict[str, dict[str, float]] = {}
    for row in rows_sorted:
        factors = row.get("factors") or {}
        date = row["tradeDate"]
        base = {name: float(factors.get(name) if factors.get(name) is not None else 0.0) for name in MARKET_FEATURE_NAMES}
        base["VPM_ret"] = float(row.get("vpmRet") if row.get("vpmRet") is not None else 0.0)
        base["VPM_turn"] = float(row.get("vpmTurn") if row.get("vpmTurn") is not None else 0.0)
        base_by_date[date] = base

        d4_hist.append(base["D4"])
        vpm_ret_hist.append(base["VPM_ret"])
        e1_hist.append(base["E1"])
        d4_tail = d4_hist[-5:]
        sign_mean = sum(1.0 if v > 0 else -1.0 if v < 0 else 0.0 for v in d4_tail) / max(len(d4_tail), 1)
        v5 = float(np.std(vpm_ret_hist[-5:], ddof=0)) if len(vpm_ret_hist) >= 2 else 0.0
        v20 = float(np.std(vpm_ret_hist[-20:], ddof=0)) if len(vpm_ret_hist) >= 2 else 0.0
        vol_stability = -(v5 / (v20 + 1e-6)) if v20 > 0.0 else 0.0
        meta_by_date[date] = {
            "元择时·趋势持续度": abs(sign_mean),
            "元择时·量价一致性": base["D4"] * base["A3"],
            "元择时·波动稳定度": vol_stability,
            "元择时·情绪修复": base["E1"] - min(e1_hist[-5:]),
        }

    raw = []
    for row in rows:
        date = row["tradeDate"]
        base = base_by_date.get(date, {})
        meta = meta_by_date.get(date, {})
        values = []
        for name in names:
            if name in meta:
                value = meta[name]
            else:
                value = base.get(name, 0.0)
            values.append(float(value if value is not None else 0.0))
        raw.append((date, values))
    return raw


def state_quantile_edges(rows: list[dict[str, Any]], axes: list[str], split_date: str) -> list[tuple[float, float]]:
    """每个 regime 轴的 33%/67% 分位边界，**只用训练段**计算（防泄漏），三等分成 3 档。"""
    edges: list[tuple[float, float]] = []
    for axis in axes:
        vals = [
            float((row.get("factors") or {}).get(axis))
            for row in rows
            if row["tradeDate"] < split_date and (row.get("factors") or {}).get(axis) is not None
        ]
        if len(vals) < 3:
            edges.append((0.0, 0.0))
            continue
        arr = np.array(vals, dtype=np.float64)
        edges.append((float(np.quantile(arr, 1 / 3)), float(np.quantile(arr, 2 / 3))))
    return edges


def compute_state_ids(rows: list[dict[str, Any]], axes: list[str], edges: list[tuple[float, float]]) -> dict[str, int]:
    """按 27 状态格离散化：每轴 3 档（低/中/高），id = Σ bucket_k · 3^k ∈ [0, 27)。"""
    out: dict[str, int] = {}
    for row in rows:
        factors = row.get("factors") or {}
        state = 0
        for k, axis in enumerate(axes):
            value = factors.get(axis)
            lo, hi = edges[k]
            if value is None:
                bucket = 1
            elif value <= lo:
                bucket = 0
            elif value <= hi:
                bucket = 1
            else:
                bucket = 2
            state += bucket * (3 ** k)
        out[row["tradeDate"]] = state
    return out


def build_sequences(
    samples: list[tuple[str, str, list[float], float, float]],
    market_by_date: dict[str, list[float]],
    state_by_date: dict[str, int],
    di_of_date: dict[str, int],
    seq_len: int,
    max_samples: int,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """组装时序样本。序列 x 只含**个股因子**；市场情绪因子单独成 market_x（取被预测日 t 的值，
    作为 FiLM regime 门控输入，不混入时序——因为全市场共享同一值，混入时序零截面区分度。
    额外输出 severity[n]：被预测日的连续急杀强度（分位数回归目标），与二值 y 同口径。"""
    market_dim = len(next(iter(market_by_date.values()))) if market_by_date else 0
    feature_dim = len(samples[0][2]) if samples else len(STOCK_FEATURE_NAMES)
    total = count_sequences(samples, market_by_date, seq_len)
    n = min(max_samples, total)
    if n <= 0:
        raise ValueError("No sequence samples built from API source")
    selected = set(even_positions(total, n))
    x = np.zeros((n, seq_len, feature_dim), dtype=np.float32)
    market_x = np.zeros((n, market_dim), dtype=np.float32)
    y = np.zeros((n,), dtype=np.float32)
    severity = np.zeros((n,), dtype=np.float32)
    state_ids = np.zeros((n,), dtype=np.int64)
    end_di = np.zeros((n,), dtype=np.int64)
    # 可视化定位：每个样本被预测日的股票代码 + 日期，供 K 线回看预测对错（不参与训练）。
    ts_codes = np.empty((n,), dtype=object)
    pred_dates = np.empty((n,), dtype=object)
    buffers: dict[str, list[tuple[list[float], list[float], float, int, int, float, str]]] = {}
    seq_index = 0
    out_index = 0
    for ts, date, stock_features, label, sev in samples:
        market = market_by_date.get(date)
        if market_dim and market is None:
            buffers.pop(ts, None)
            continue
        buf = buffers.setdefault(ts, [])
        buf.append((stock_features, market or [], label, state_by_date.get(date, NUM_STATES // 2), di_of_date[date], sev, date))
        if len(buf) > seq_len:
            del buf[0]
        if len(buf) == seq_len:
            if seq_index in selected:
                x[out_index] = np.array([r[0] for r in buf], dtype=np.float32)
                market_x[out_index] = np.array(buf[-1][1], dtype=np.float32) if market_dim else market_x[out_index]
                y[out_index] = buf[-1][2]
                state_ids[out_index] = buf[-1][3]   # 被预测日 t 的市场状态格
                end_di[out_index] = buf[-1][4]       # 被预测日 di（时间切分用）
                severity[out_index] = buf[-1][5]     # 被预测日连续急杀强度
                ts_codes[out_index] = ts             # 股票代码（可视化定位）
                pred_dates[out_index] = buf[-1][6]   # 被预测日日期（可视化定位）
                out_index += 1
                if out_index >= n:
                    break
            seq_index += 1
    return x, market_x, y, state_ids, end_di, severity, ts_codes, pred_dates


def count_sequences(samples: list[tuple[str, str, list[float], float, float]], market_by_date: dict[str, list[float]], seq_len: int) -> int:
    counts: dict[str, int] = {}
    total = 0
    use_market = bool(market_by_date)
    for ts, date, *_ in samples:
        if use_market and date not in market_by_date:
            counts.pop(ts, None)
            continue
        count = counts.get(ts, 0) + 1
        counts[ts] = count
        if count >= seq_len:
            total += 1
    return total


def even_positions(total: int, n: int) -> list[int]:
    if n >= total:
        return list(range(total))
    if n <= 1:
        return [0]
    step = (total - 1) / (n - 1)
    return [int(i * step) for i in range(n)]


def win_avg(values: dict[int, float], di: int, w: int) -> float | None:
    total = 0.0
    for k in range(di - w, di):
        value = values.get(k)
        if value is None:
            return None
        total += value
    return total / w


def win_sum(values: dict[int, float], di: int, w: int) -> float | None:
    total = 0.0
    for k in range(di - w, di):
        value = values.get(k)
        if value is None:
            return None
        total += value
    return total


def win_max(values: dict[int, float], di: int, w: int) -> float | None:
    xs = []
    for k in range(di - w, di):
        value = values.get(k)
        if value is None:
            return None
        xs.append(value)
    return float(max(xs))


def win_max_available(values: dict[int, float], di: int, w: int, min_count: int) -> float | None:
    xs = []
    for k in range(max(0, di - w), di):
        value = values.get(k)
        if value is not None:
            xs.append(value)
    if len(xs) < min_count:
        return None
    return float(max(xs))


def win_vol(values: dict[int, float], di: int, w: int) -> float | None:
    xs = []
    for k in range(di - w, di):
        value = values.get(k)
        if value is None:
            return None
        xs.append(value)
    if len(xs) < w:
        return None
    return float(np.std(np.array(xs, dtype=np.float64), ddof=1))


def market_beta(ret: dict[int, float], market_ret: dict[int, float], di: int, w: int) -> float | None:
    """个股对市场等权收益的 20 日 beta，窗口 [di-w, di)。只用历史，不含未来。"""
    xs = []
    ys = []
    for k in range(di - w, di):
        x = market_ret.get(k)
        y = ret.get(k)
        if x is None or y is None:
            return None
        xs.append(x)
        ys.append(y)
    if len(xs) < w:
        return None
    mx = sum(xs) / len(xs)
    my = sum(ys) / len(ys)
    den = sum((x - mx) ** 2 for x in xs)
    if den <= 0.0:
        return None
    return sum((xs[i] - mx) * (ys[i] - my) for i in range(len(xs))) / den


def win_pct_rank(values: dict[int, float], di: int, w: int, current: float) -> float | None:
    """current 在过去 w 窗内的分位（0~1）。热度的连续刻画：高分位=近期罕见高换手。"""
    xs = []
    for k in range(di - w, di):
        value = values.get(k)
        if value is None:
            return None
        xs.append(value)
    if len(xs) < w:
        return None
    below = sum(1 for v in xs if v <= current)
    return below / len(xs)


def zscore(xs: list[float]) -> list[float]:
    arr = np.array(xs, dtype=np.float64)
    sd = float(arr.std(ddof=1))
    if sd == 0.0:
        return [0.0] * len(xs)
    return np.clip((arr - float(arr.mean())) / sd, -5.0, 5.0).tolist()


# ──────────────────────────────────────────────────────────────────────────────
# 个股级 VV/VP 量价算子（factor topic 成果的算子复用）
#
# factor topic 的 VolumePriceFactors 把这些算子用在【市场等权聚合序列】上（市场级，截面零区分度）。
# 这里把同一套经过验证的【时序算子】作用到【单只个股自己的 ret/turn 序列】，得到有截面区分度的
# 个股量价因子。严格复刻 Kotlin VolumePriceFactors 的窗口语义与因果性（第 t 个值只用 ≤t 数据），
# 保数值一致。缺口（窗口内有 None）一律返回 None → 该样本被跳过，保证列对齐。
# ──────────────────────────────────────────────────────────────────────────────

def _slice(values: dict[int, float], lo: int, hi: int) -> list[float] | None:
    """连续切片 [lo, hi)，任一 di 缺失返回 None（个股因果窗口需连续）。"""
    out = []
    for k in range(lo, hi):
        v = values.get(k)
        if v is None:
            return None
        out.append(v)
    return out


def _vol_anomaly(tnr: dict[int, float], di: int, base_w: int = 20) -> float | None:
    """量异动 v_di = ln(τ_di / mean(τ, [di-base_w, di-1]))。复刻 volumeAnomaly。"""
    cur = tnr.get(di)
    if cur is None or cur <= 0.0:
        return None
    base = _slice(tnr, di - base_w, di)
    if base is None:
        return None
    m = sum(base) / len(base)
    return math.log(cur / m) if m > 0.0 else None


def vv5_pulse_z(tnr: dict[int, float], di: int, w: int = 20) -> float | None:
    """VV5 量能脉冲 z 分：(τ_di − mean(base)) / std(base)，base=[di-w, di-1]（不含当日）。
    高位连续=派发见顶信号（直击 case② 主力出货）。复刻 pulseZ。"""
    cur = tnr.get(di)
    if cur is None:
        return None
    base = _slice(tnr, di - w, di)
    if base is None:
        return None
    m = sum(base) / len(base)
    sd = math.sqrt(sum((x - m) ** 2 for x in base) / len(base))
    return (cur - m) / sd if sd > 0.0 else None


def vv3_run_length(tnr: dict[int, float], di: int, base_w: int = 20) -> float | None:
    """VV3 放量/缩量游程：sign(v_di)·连续同号天数。复刻 runLength（在量异动序列上）。"""
    cur = _vol_anomaly(tnr, di, base_w)
    if cur is None:
        return None
    s = 1.0 if cur > 0 else (-1.0 if cur < 0 else 0.0)
    if s == 0.0:
        return 0.0
    n = 1
    i = di - 1
    while True:
        vi = _vol_anomaly(tnr, i, base_w)
        if vi is None:
            break
        si = 1.0 if vi > 0 else (-1.0 if vi < 0 else 0.0)
        if si != s:
            break
        n += 1
        i -= 1
    return s * n


def vp2v_accel(tnr: dict[int, float], di: int, base_w: int = 20) -> float | None:
    """VP2v 量加速度：量异动的二阶差分 v_di − 2·v_{di-1} + v_{di-2}。复刻 secondDiff。"""
    v2 = _vol_anomaly(tnr, di, base_w)
    v1 = _vol_anomaly(tnr, di - 1, base_w)
    v0 = _vol_anomaly(tnr, di - 2, base_w)
    if v2 is None or v1 is None or v0 is None:
        return None
    return (v2 - v1) - (v1 - v0)


def vp0_coordination(ret: dict[int, float], tnr: dict[int, float], di: int, base_w: int = 20) -> float | None:
    """VP0 量价配合度：sign(ret_di)·v_di（放量上涨为正/放量下跌为负）。复刻 pairwise(sign(r)*v)。"""
    r = ret.get(di)
    v = _vol_anomaly(tnr, di, base_w)
    if r is None or v is None:
        return None
    s = 1.0 if r > 0 else (-1.0 if r < 0 else 0.0)
    return s * v


def vpbeta_elasticity(ret: dict[int, float], tnr: dict[int, float], di: int, w: int = 20, base_w: int = 20) -> float | None:
    """VPbeta 量价弹性 β：ret 对量异动 v 回归斜率 cov(v,r)/var(v)，窗口 [di-w+1, di]。
    放量推不动价(β小)=出货嫌疑。复刻 rollingBeta。"""
    rs, vs = [], []
    for k in range(di - w + 1, di + 1):
        r = ret.get(k)
        v = _vol_anomaly(tnr, k, base_w)
        if r is None or v is None:
            return None
        rs.append(r); vs.append(v)
    if len(vs) < w:
        return None
    mv = sum(vs) / len(vs); mr = sum(rs) / len(rs)
    num = sum((vs[i] - mv) * (rs[i] - mr) for i in range(len(vs)))
    den = sum((vs[i] - mv) ** 2 for i in range(len(vs)))
    return num / den if den != 0.0 else None


def rsi(ret: dict[int, float], di: int, w: int = 14) -> float | None:
    """
    RSI（相对强弱指数，SMA 版，0~100）：上涨力量占比。
      RSI = 100 · avg_gain / (avg_gain + avg_loss)，窗口 [di-w+1, di]（含当日，因果）。
    业务：RSI 过高=超买亢奋，容易回落（正向预警指标）。
    数学独特性：有界非线性饱和——区别于无界线性的动量因子，可能提供正交信息。
    """
    rs = _slice(ret, di - w + 1, di + 1)
    if rs is None:
        return None
    gain = sum(x for x in rs if x > 0.0)
    loss = -sum(x for x in rs if x < 0.0)
    denom = gain + loss
    return 100.0 * gain / denom if denom > 0.0 else 50.0


def rsi_delta(ret: dict[int, float], di: int, w: int = 14, lag: int = 3) -> float | None:
    """ΔRSI：RSI 的 lag 日变化率（「RSI 快速放大」——亢奋的突变，比静态超买更预示回落）。"""
    cur = rsi(ret, di, w)
    prev = rsi(ret, di - lag, w)
    if cur is None or prev is None:
        return None
    return cur - prev


def rsi_accel(ret: dict[int, float], di: int, w: int = 14, lag: int = 3) -> float | None:
    """RSI 二阶（加速度）：ΔRSI 的变化——放大在加速还是减速。"""
    d2 = rsi_delta(ret, di, w, lag)
    d1 = rsi_delta(ret, di - lag, w, lag)
    if d2 is None or d1 is None:
        return None
    return d2 - d1


def vv8_lead_share(ret: dict[int, float], tnr: dict[int, float], di: int, w: int = 40, max_lead: int = 3) -> float | None:
    """VV8 量能领先占比：窗内量拐点领先价拐点 1~max_lead 日的占比。量先于价转折=超前性。复刻 leadShareV。"""
    tseg = _slice(tnr, di - w + 1, di + 1)
    rseg = _slice(ret, di - w + 1, di + 1)
    if tseg is None or rseg is None:
        return None
    def turns(seg: list[float]) -> list[int]:
        out = []
        for i in range(1, len(seg) - 1):
            if (seg[i] > seg[i - 1] and seg[i] > seg[i + 1]) or (seg[i] < seg[i - 1] and seg[i] < seg[i + 1]):
                out.append(i)
        return out
    vt = turns(tseg); pt = turns(rseg)
    if not pt:
        return None
    led = sum(1 for p in pt if any(v < p and p - v <= max_lead for v in vt))
    return led / len(pt)


def select_device(requested: str) -> torch.device:
    if requested == "cpu":
        return torch.device("cpu")
    if requested == "mps":
        if not torch.backends.mps.is_available():
            raise RuntimeError("MPS requested but torch.backends.mps is not available")
        return torch.device("mps")
    if torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


def evaluate_model(
    model: nn.Module,
    x: torch.Tensor,
    market_x: torch.Tensor,
    y: torch.Tensor,
    state_ids: torch.Tensor,
    device: torch.device,
    batch_size: int,
) -> dict[str, Any]:
    model.eval()
    is_quantile = getattr(model, "num_quantiles", 1) > 1
    scores: list[np.ndarray] = []
    with torch.no_grad():
        for start in range(0, len(y), batch_size):
            xb = x[start : start + batch_size].to(device)
            mb = market_x[start : start + batch_size].to(device)
            sb = state_ids[start : start + batch_size].to(device)
            out = model(xb, mb, sb)
            # 分位回归：取最高分位列作排序打分（severity 连续，单调变换不影响 AUC/PR）。
            # 分类：sigmoid(logit)。两者都仅用于排序与工作点 precision。
            sc = out[:, -1] if is_quantile else torch.sigmoid(out)
            scores.append(sc.detach().cpu().numpy())
    score_arr = np.concatenate(scores).astype(np.float64) if scores else np.zeros(0)
    label_arr = y.numpy().astype(np.int32)
    auc = roc_auc(score_arr, label_arr)
    pr = precision_at_recall(score_arr, label_arr, 0.60)
    return {
        "samples": int(len(label_arr)),
        "positives": int(label_arr.sum()),
        "positive_rate": float(label_arr.mean()) if len(label_arr) else 0.0,
        "auc": auc,
        "precision_at_recall60": pr["precision"],
        "recall_at_recall60": pr["recall"],
        "threshold_at_recall60": pr["threshold"],
    }


def evaluate_by_regime(
    model: nn.Module,
    x: torch.Tensor,
    market_x: torch.Tensor,
    y: torch.Tensor,
    state_ids: torch.Tensor,
    device: torch.device,
    batch_size: int,
) -> dict[str, Any]:
    """
    按市场 regime 分桶评估 + 校准度——专门暴露 AUC（纯排序）看不见的市场因子价值。

    通用大跌预警是 (股×时) 二分类，市场状态决定「整体多危险」。AUC 对整体概率偏移不敏感，
    会抹平这一信息。这里：
      ① 按 D4 趋势档（state_id % 3：0=下跌趋势=高危，1=中性，2=上涨趋势=低危）分桶，
         看每桶的实际正例率 vs 模型平均预测概率——市场因子有用则「高危桶预测概率↑且实际正例率↑」。
      ② 校准曲线：预测概率分 5 档，看每档实际下跌频率是否对齐——大跌预警要的是概率校准，非排序。
    """
    model.eval()
    is_quantile = getattr(model, "num_quantiles", 1) > 1
    scores: list[np.ndarray] = []
    with torch.no_grad():
        for start in range(0, len(y), batch_size):
            xb = x[start : start + batch_size].to(device)
            mb = market_x[start : start + batch_size].to(device)
            sb = state_ids[start : start + batch_size].to(device)
            out = model(xb, mb, sb)
            sc = out[:, -1] if is_quantile else torch.sigmoid(out)
            scores.append(sc.detach().cpu().numpy())
    s = np.concatenate(scores).astype(np.float64) if scores else np.zeros(0)
    lab = y.numpy().astype(np.int32)
    sid = state_ids.numpy().astype(np.int64)
    d4 = sid % 3  # D4 趋势档（编码最低位）：0=高危，1=中性，2=低危

    regimes = {}
    for bucket, name in [(0, "高危(下跌趋势)"), (1, "中性"), (2, "低危(上涨趋势)")]:
        m = d4 == bucket
        n = int(m.sum())
        if n == 0:
            continue
        pr = precision_at_recall(s[m], lab[m], 0.60)
        regimes[name] = {
            "samples": n,
            "actual_positive_rate": float(lab[m].mean()),    # 该 regime 实际大跌频率
            "mean_predicted_prob": float(s[m].mean()),        # 模型平均预测概率（应随 regime 危险度变化）
            "auc": roc_auc(s[m], lab[m]),
            "precision_at_recall60": pr["precision"],
        }

    # 校准曲线：预测概率 5 档，每档实际正例率（理想=对角线）
    calibration = []
    for lo in (0.0, 0.2, 0.4, 0.6, 0.8):
        hi = lo + 0.2
        m = (s >= lo) & (s < hi if hi < 1.0 else s <= hi)
        n = int(m.sum())
        calibration.append({
            "bin": f"[{lo:.1f},{hi:.1f})",
            "samples": n,
            "mean_predicted": float(s[m].mean()) if n else None,
            "actual_frequency": float(lab[m].mean()) if n else None,
        })
    return {"by_regime": regimes, "calibration": calibration}


def roc_auc(scores: np.ndarray, labels: np.ndarray) -> float:
    pos = int(labels.sum())
    neg = int(len(labels) - pos)
    if pos == 0 or neg == 0:
        return float("nan")
    order = np.argsort(scores)
    ranks = np.empty_like(order, dtype=np.float64)
    i = 0
    rank = 1.0
    while i < len(order):
        j = i + 1
        while j < len(order) and scores[order[j]] == scores[order[i]]:
            j += 1
        avg_rank = (rank + rank + (j - i) - 1.0) / 2.0
        ranks[order[i:j]] = avg_rank
        rank += j - i
        i = j
    pos_rank_sum = float(ranks[labels == 1].sum())
    return (pos_rank_sum - pos * (pos + 1) / 2.0) / (pos * neg)


def precision_at_recall(scores: np.ndarray, labels: np.ndarray, target_recall: float) -> dict[str, float]:
    pos = int(labels.sum())
    if pos == 0:
        return {"precision": float("nan"), "recall": float("nan"), "threshold": float("nan")}
    order = np.argsort(-scores)
    tp = 0
    fp = 0
    i = 0
    while i < len(order):
        threshold = float(scores[order[i]])
        while i < len(order) and scores[order[i]] == threshold:
            if labels[order[i]] == 1:
                tp += 1
            else:
                fp += 1
            i += 1
        recall = tp / pos
        if recall >= target_recall:
            return {
                "precision": tp / max(1, tp + fp),
                "recall": recall,
                "threshold": threshold,
            }
    return {"precision": tp / max(1, tp + fp), "recall": tp / pos, "threshold": float(scores[order[-1]])}


if __name__ == "__main__":
    main()
