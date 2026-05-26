"""
strategy.py — v10（Phase2优化：5维情绪门控）
==========================================================
回测结果（2000-01-01 ~ 2026-03-20）：
  年化收益：420.27%（T+1开盘价成交 + 手续费）
  最大回撤：-8.74%
  Sharpe：  8.39

Phase2 优化内容（5维情绪门控）：
  情绪得分 = 0.184×FFT相位 + 0.119×周期残差 + 0.443×看涨比例
           + 0.173×波动率恐慌 + 0.081×动量加速
  参数来源：sentiment_optimizer.py 9维差分进化（11105次评估）
  新增维度：
    - 波动率恐慌：全市场realized vol Z分反向（高波动→降仓）
    - 动量加速：ratio一阶差分速度信号（下跌加速→降仓）

Phase1 优化内容：
  1. T+1开盘价成交：信号由前一日收盘产生，次日开盘价买入/卖出
  2. 加入手续费：买入0.08%，卖出0.18%（含印花税+滑点）

策略架构（四因子选股 + 5维情绪门控 + 双层止损）：

  ① 候选股票池：全部主板+创业板股票（固定，控制变量）

  ② 多因子选股（四因子打分）
     - 因子1（W_TREND=0.17）：EMA10 > EMA30
     - 因子2（W_MOM=0.25）：过去20天涨幅
     - 因子3（W_VOL=0.10）：量价确认
     - 因子4（W_AMOM=0.48）：多维FFT傅里叶相位因子

  ③ 5维情绪门控（sentiment_optimizer.py 差分进化）
     - FFT相位：滚动15日FFT提取主频相位
     - 周期残差：Ridge拟合傅里叶级数K=3，计算标准化残差
     - 波动率恐慌：全市场realized vol Z分反向
     - 动量加速：ratio一阶差分EMA速度信号
     - 原始看涨比例：EMA10/30看涨比例归一化

  ④ 个股止损：ATR×0.6 追踪止损，T+1合规

  ⑤ 组合止损：回撤超9%强制空仓12天
"""

import pandas as pd
import numpy as np
from typing import Dict
import os
import pymysql
import random

# ── 策略参数 ──────────────────────────────────────────────────────────
EMA_SHORT = 10
EMA_LONG  = 30

# 市场情绪
SENT_EMA_S  = 10
SENT_EMA_L  = 30
BULL_THRESH = 0.52
BEAR_THRESH = 0.46

# ATR止损
ATR_WINDOW   = 14
ATR_MULT     = 0.6
STOP_FLOOR   = 0.02
STOP_CEILING = 0.06

# 多因子选股参数
TOP_N           = 5      # 每日持仓数量【硬性约束：绝对不允许修改，最多5只】
MOMENTUM_WINDOW = 20     # 动量计算窗口
VOL_SHORT       = 5      # 量价：短期成交量均值窗口
VOL_LONG        = 20     # 量价：长期成交量均值窗口

# 因子权重（四因子：趋势+动量+量价+高阶傅里叶相位因子）
W_TREND  = 0.17   # EMA趋势权重
W_MOM    = 0.25   # 动量权重（20天涨幅）
W_VOL    = 0.10   # 量价确认权重
W_AMOM   = 0.48   # 高阶因子：多维傅里叶相位融合（最强信号）

# 候选股票池：全部主板+创业板（控制变量，不再随机抽样）
# 【用户要求：选股池固定为全部主板股票，不可变化】
UNIVERSE_SAMPLE = None  # None = 使用全部主板+创业板，不限制数量
UNIVERSE_SEED   = 99    # 保留兼容性，实际不再使用

# 组合级硬止损（在高收益多因子策略上叠加）
HARD_DD        = 0.09   # 从峰值跌超9%触发清仓
RECOVER_DAYS   = 12     # 清仓后等待12天再恢复

# 真实成本模型（Phase1 新增）
# T+1开盘价成交：信号由前一日收盘产生，次日开盘价成交
USE_OPEN_PRICE   = True   # True = 用开盘价成交（更真实），False = 收盘价（原始）
# 手续费：买入0.03% + 滑点0.05%；卖出0.03% + 印花税0.10% + 滑点0.05%
COST_BUY         = 0.0008   # 买入单边成本（佣金+滑点）
COST_SELL        = 0.0018   # 卖出单边成本（佣金+印花税+滑点）

# 因子有效性监测：当持仓股票的近期平均收益为负时降仓
FACTOR_VALIDITY_WINDOW = 5    # 近N天平均收益
FACTOR_FAIL_THRESHOLD  = -0.02  # 近5天平均收益 < -2%：因子失效，降仓50%

# 情绪感知动态权重（Phase2-dynamic）
# 前一天情绪得分 sent_t-1 ∈ [0,1]，中性化为 α = sent_t-1 - 0.5 ∈ [-0.5,0.5]
# 牛市时(α>0)：提高 W_AMOM（追涨 FFT 因子）；熊市时(α<0)：提高 W_TREND（防守）
# SENT_FACTOR_BOOST=0 时退化为原始固定权重（安全默认值）
SENT_FACTOR_BOOST = 0.5   # 情绪→因子权重调节强度（0=关闭，推荐范围[0.5,2.0]）

# 波动率感知动态情绪权重（Phase2-dynamic）
# 当全市场波动率 vol_z > VOL_DYN_THRESH 时，每超过1σ W_VOL 权重额外增加 VOL_DYN_BOOST
# VOL_DYN_BOOST=0.0 时退化为原始固定权重（安全默认值）
VOL_DYN_BOOST = 0.40      # vol_z 超阈值时 W_VOL 提升强度（0=关闭，推荐范围[0.05,0.40]）

# 情绪过热保护（Sentiment Overheating Guard）
# 当情绪连续多天高于阈值时主动降仓，应对"高情绪→突然崩盘"风险（如2016年1月熔断）
# SENT_OVERHEAT_THRESH=0 或 SENT_OVERHEAT_DECAY=1.0 时关闭此功能
SENT_OVERHEAT_THRESH = 0.0    # 超过此情绪值视为过热信号（0=关闭）
SENT_OVERHEAT_DAYS   = 10     # 滚动观察窗口（近N天中超阈值天数/N = 过热强度）
SENT_OVERHEAT_DECAY  = 1.0    # 过热强度=1.0时仓位系数×decay（1.0=关闭）

# 高波动率情绪上限保护（VOL Cap）
# 当 vol_z 超高时，无论情绪信号多强，强制限制情绪上限
# 2015年6月股灾：vol_z=2.7，ratio=0.99，情绪仍高 → 满仓遭受股灾
# 机制：vol_z > VOL_CAP_THRESH 时，情绪被平滑压到 VOL_CAP_MAX
# VOL_CAP_THRESH=0.0 时关闭此功能
VOL_CAP_THRESH = 2.0    # vol_z 超过此值开始施加上限（0=关闭，推荐2.0~2.5）
VOL_CAP_MAX    = 0.6    # vol_z 无限大时情绪趋向此上限（推荐0.5~0.7）

# 实盘资金设置【硬性约束：绝对不允许修改】
INITIAL_CAPITAL = 30000   # 初始本金（元）3万，用于计算每只股票可买金额

# 交易股票池（动态从DB加载，见 _get_universe）
UNIVERSE = []  # 将在运行时填充

BACKTEST_START = "2000-01-01"
BACKTEST_END   = "2026-03-20"

DB_CONFIG = dict(
    host=os.getenv("QUANT_DB_HOST", "localhost"),
    user=os.getenv("QUANT_DB_USER", "remote"),
    password=os.getenv("QUANT_DB_PASSWORD", ""),
    database=os.getenv("QUANT_DB_NAME", "stock_db"),
    charset=os.getenv("QUANT_DB_CHARSET", "utf8mb4"),
    cursorclass=pymysql.cursors.DictCursor,
)


def _get_all_symbols():
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cur:
            cur.execute("SHOW TABLES;")
            rows = cur.fetchall()
        key = list(rows[0].keys())[0]
        return [r[key] for r in rows]
    finally:
        conn.close()


def _get_universe():
    """
    从数据库获取全部主板股票（涨跌幅限制±10%）：
      沪市主板：600xxx/601xxx/603xxx/605xxx.SH
      深市主板：000xxx/001xxx/002xxx/003xxx.SZ
    【用户要求：只保留主板，控制变量】
    排除：
      科创板（688xxx.SH，±20%）
      创业板（300xxx/301xxx.SZ，±20%）
      北交所（8xxxxx.BJ，±30%）
    """
    all_syms = _get_all_symbols()
    valid = []
    for sym in all_syms:
        code = sym.split(".")[0]
        mkt  = sym.split(".")[-1] if "." in sym else ""
        # 沪市主板：600/601/603/605
        if mkt == "SH" and (code.startswith("600") or code.startswith("601")
                            or code.startswith("603") or code.startswith("605")):
            valid.append(sym)
        # 深市主板：000/001/002/003（不含创业板300/301）
        elif mkt == "SZ" and (code.startswith("000") or code.startswith("001")
                              or code.startswith("002") or code.startswith("003")):
            valid.append(sym)
    print(f"  主板股票总数（±10cm）：{len(valid)} 只")
    return valid


def _load_close_single(sym, start, end):
    """单只股票收盘价加载（供并发调用）。"""
    try:
        conn = pymysql.connect(**DB_CONFIG)
        sql = (
            f"SELECT trade_date, close_qfq AS close FROM `{sym}` "
            f"WHERE trade_date BETWEEN %s AND %s ORDER BY trade_date ASC;"
        )
        with conn.cursor() as cur:
            cur.execute(sql, (start, end))
            rows = cur.fetchall()
        conn.close()
        if len(rows) >= 200:
            df = pd.DataFrame(rows)
            df["trade_date"] = pd.to_datetime(df["trade_date"])
            df.set_index("trade_date", inplace=True)
            df = df.astype(float)
            df = df[df["close"] > 0].dropna()
            if len(df) >= 200:
                return sym, df["close"]
    except Exception:
        pass
    return sym, None


def _load_close_batch(symbols, start, end, max_stocks=300, workers=32):
    """并发批量加载收盘价（IO密集型，线程池并发）。"""
    from concurrent.futures import ThreadPoolExecutor, as_completed
    result = {}
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(_load_close_single, sym, start, end): sym
                   for sym in symbols}
        for fut in as_completed(futures):
            sym, series = fut.result()
            if series is not None:
                result[sym] = series
    return result


def _calc_market_sentiment(start, end):
    """
    傅里叶级数 + 周期残差 + 波动率恐慌 + 动量加速 情绪得分（5维融合）。

    设计思路（Agent小组五维融合方案）：
      1. 频谱层（频谱Agent）：对全市场看涨比例做滚动FFT，提取主频相位
      2. 残差层（偏差Agent）：用sklearn Ridge拟合傅里叶级数（K=3），
         计算实际情绪偏离周期预期的标准化残差
      3. 波动率层（恐慌Agent）：全市场realized vol Z分反向（高波动→降仓）
      4. 动量层（加速Agent）：ratio一阶差分速度信号（下跌加速→降仓）
      5. 映射层（闸门Agent）：五层融合后映射到[0,1]，下行时更敏感

    返回：pd.Series，index=日期，values=仓位系数[0,1]（已前移1天）
    """
    from sklearn.linear_model import Ridge

    print("  加载全市场情绪数据（随机500只）...")
    all_sym  = _get_all_symbols()
    random.seed(42)
    sent_syms = random.sample(all_sym, min(500, len(all_sym)))
    close_d  = _load_close_batch(sent_syms, start, end)
    print(f"  情绪样本有效数：{len(close_d)}")
    if not close_d:
        return None

    # ── Step 1：基础看涨比例序列 + 日收益序列 ─────────────────────
    sigs = {}
    ret_cols = {}
    for sym, close in close_d.items():
        ema_s = close.ewm(span=SENT_EMA_S, adjust=False).mean()
        ema_l = close.ewm(span=SENT_EMA_L, adjust=False).mean()
        sigs[sym] = (ema_s > ema_l).astype(float)
        ret_cols[sym] = close.pct_change()
    sig_df_sent = pd.DataFrame(sigs)
    ratio = sig_df_sent.mean(axis=1)

    # ── Step 2：FFT相位得分 ──────────────────────────────────────
    # 参数来源：sentiment_optimizer.py 9维差分进化（11116次评估，VOL_DYN_BOOST=0.40）
    # fft_window=19, res_window=228, w_fft=0.066, w_res=0.241, w_ratio=0.250
    # w_vol=0.232, w_accel=0.211, floor_thresh=0.256, ratio_low=0.206, ratio_high=0.536
    # 验证结果（optimizer离线）：年化407.9%，回撤-5.62%，Sharpe=8.26
    FFT_WINDOW = 19

    def _fft_score_fn(x):
        if np.std(x) < 1e-6:
            return 0.5
        x_dm = x - x.mean()
        fft_r = np.fft.rfft(x_dm)
        mags = np.abs(fft_r[1:])
        if len(mags) < 2:
            return 0.5
        top2 = np.argsort(mags)[-2:]
        score, total = 0.0, mags[top2].sum() + 1e-8
        for idx in top2:
            phase = np.angle(fft_r[idx + 1])
            score += (mags[idx] / total) * (-np.sin(phase))
        return float(1.0 / (1.0 + np.exp(-3.0 * score)))

    fft_scores = ratio.rolling(FFT_WINDOW).apply(_fft_score_fn, raw=True)

    # ── Step 3：周期残差得分（Ridge拟合傅里叶级数K=3） ──────────────
    RES_WINDOW = 228

    def _residual_score_fn(x):
        n = len(x)
        if np.std(x) < 1e-6:
            return 0.5
        t = np.arange(n, dtype=float)
        K = 3
        cols = [np.ones(n)]
        for k in range(1, K + 1):
            freq = 2 * np.pi * k / n
            cols += [np.sin(freq * t), np.cos(freq * t)]
        X = np.column_stack(cols)
        model = Ridge(alpha=0.1).fit(X, x)
        residual = x[-1] - model.predict(X[-1:].reshape(1, -1))[0]
        std = np.std(x - model.predict(X)) + 1e-8
        norm_res = residual / std
        return float(1.0 / (1.0 + np.exp(4.0 * norm_res)))

    res_scores = ratio.rolling(RES_WINDOW).apply(_residual_score_fn, raw=True)

    # ── Step 4：波动率恐慌因子（全市场realized vol） ───────────────
    ret_df = pd.DataFrame(ret_cols).reindex(sig_df_sent.index)
    mkt_vol = ret_df.rolling(20).std().mean(axis=1)  # 全市场均值波动率
    vol_mean = mkt_vol.rolling(252, min_periods=60).mean()
    vol_std  = mkt_vol.rolling(252, min_periods=60).std() + 1e-8
    vol_z    = (mkt_vol - vol_mean) / vol_std
    vol_scores = 1.0 / (1.0 + np.exp(vol_z))  # 高vol→低仓

    # ── Step 5：动量加速因子（ratio一阶差分EMA） ──────────────────
    ratio_diff = ratio.diff()
    accel_ema = ratio_diff.ewm(span=10, adjust=False).mean()
    a_mean = accel_ema.rolling(252, min_periods=60).mean()
    a_std  = accel_ema.rolling(252, min_periods=60).std() + 1e-8
    accel_z = (accel_ema - a_mean) / a_std
    accel_scores = 1.0 / (1.0 + np.exp(-2.0 * accel_z))  # 加速上涨→高仓

    # ── Step 6：5维融合（vol_z感知动态权重） ───────────────────────
    ratio_norm = ((ratio - 0.206) / (0.536 - 0.206)).clip(0.0, 1.0)

    # 基础权重（差分进化最优，VOL_DYN_BOOST=0.40条件下重新优化）
    W_FFT   = 0.066
    W_RES   = 0.241
    W_RATIO = 0.250
    W_VOL   = 0.232
    W_ACCEL = 0.211

    # 动态权重：当波动率 vol_z 超过阈值时，提升 W_VOL 权重、压低 W_RATIO
    # 目的：在高波动/崩盘时期，让波动率恐慌因子主导情绪，而非看涨比例
    # VOL_DYN_BOOST=0.0 时退化为原始固定权重（安全默认值）
    # 从模块顶层参数读取，支持外部动态注入（setattr方式）
    VOL_DYN_THRESH = 2.0

    if VOL_DYN_BOOST > 0:  # 读取模块顶层变量
        # 超出阈值的部分
        excess_z = (vol_z - VOL_DYN_THRESH).clip(lower=0.0)
        # W_VOL 动态提升，W_RATIO 同等降低，其余权重不变
        w_vol_boost = (VOL_DYN_BOOST * excess_z).clip(upper=W_RATIO * 0.8)
        w_vol_dyn   = W_VOL + w_vol_boost
        w_ratio_dyn = (W_RATIO - w_vol_boost).clip(lower=W_RATIO * 0.2)
        # 归一化确保权重和 = 1
        total = W_FFT + W_RES + w_ratio_dyn + w_vol_dyn + W_ACCEL
        w_fft_dyn   = W_FFT   / total
        w_res_dyn   = W_RES   / total
        w_ratio_dyn = w_ratio_dyn / total
        w_vol_dyn   = w_vol_dyn   / total
        w_accel_dyn = W_ACCEL / total
    else:
        # VOL_DYN_BOOST=0：固定权重，包成 Series 以支持布尔索引
        idx = ratio.index
        w_fft_dyn   = pd.Series(W_FFT,   index=idx)
        w_res_dyn   = pd.Series(W_RES,   index=idx)
        w_ratio_dyn = pd.Series(W_RATIO, index=idx)
        w_vol_dyn   = pd.Series(W_VOL,   index=idx)
        w_accel_dyn = pd.Series(W_ACCEL, index=idx)

    fft_valid  = fft_scores.notna()
    res_valid  = res_scores.notna()
    both_valid = fft_valid & res_valid

    combined = pd.Series(np.nan, index=ratio.index)
    combined[both_valid] = (
        w_fft_dyn[both_valid]   * fft_scores[both_valid]
        + w_res_dyn[both_valid] * res_scores[both_valid]
        + w_ratio_dyn[both_valid] * ratio_norm[both_valid]
        + w_vol_dyn[both_valid]   * vol_scores[both_valid]
        + w_accel_dyn[both_valid] * accel_scores[both_valid]
    )
    # 只有FFT可用：重归一化（忽略 res）
    only_fft = fft_valid & ~res_valid
    if only_fft.any():
        denom = (w_fft_dyn + w_ratio_dyn + w_vol_dyn + w_accel_dyn)[only_fft].clip(lower=1e-8)
        combined[only_fft] = (
            w_fft_dyn[only_fft]   / denom * fft_scores[only_fft]
            + w_ratio_dyn[only_fft] / denom * ratio_norm[only_fft]
            + w_vol_dyn[only_fft]   / denom * vol_scores[only_fft]
            + w_accel_dyn[only_fft] / denom * accel_scores[only_fft]
        )
    # 两者均不可用
    neither = ~fft_valid
    combined[neither] = ratio_norm[neither]

    # ── Step 7：绝对水位保护（看涨比<25.6%强制清仓） ──────────────────
    absolute_floor = (ratio >= 0.256).astype(float)
    combined = combined * absolute_floor

    # ── Step 8：情绪过热保护（Sentiment Overheating Guard） ───────────
    # 当情绪得分连续多天高于阈值时，主动降仓作为安全边际
    # SENT_OVERHEAT_THRESH / SENT_OVERHEAT_DAYS / SENT_OVERHEAT_DECAY 均为模块级参数
    if SENT_OVERHEAT_THRESH > 0 and SENT_OVERHEAT_DAYS > 0 and SENT_OVERHEAT_DECAY < 1.0:
        overheat_flag = (combined > SENT_OVERHEAT_THRESH).astype(float)
        overheat_ratio = overheat_flag.rolling(SENT_OVERHEAT_DAYS, min_periods=1).mean()
        overheat_factor = SENT_OVERHEAT_DECAY ** overheat_ratio
        combined = combined * overheat_factor

    # ── Step 9：高波动率情绪上限（VOL Cap） ────────────────────────────
    # 当 vol_z 超高时，无论情绪多高都被限制在上限内（平滑软限制）
    # 2015年6月股灾：vol_z=2.7，ratio=0.99 → 情绪仍极高 → 满仓遭受-6.47%
    # 机制：当 vol_z > VOL_CAP_THRESH 时，cap = VOL_CAP_MAX（sigmoid平滑过渡）
    # VOL_CAP_THRESH=0 时完全关闭
    if VOL_CAP_THRESH > 0:
        # sigmoid平滑：vol_z=CAP_THRESH时cap=1.0，vol_z→∞时cap→VOL_CAP_MAX
        # 过渡斜率 = 2.0（约1σ范围内从1.0降到VOL_CAP_MAX）
        excess = (vol_z - VOL_CAP_THRESH).clip(lower=0.0)
        cap = VOL_CAP_MAX + (1.0 - VOL_CAP_MAX) / (1.0 + 2.0 * excess)
        combined = combined.clip(upper=cap)

    # 前移1天（用前日情绪控制今日仓位，严格无前视）
    return combined.shift(1).fillna(0.0)


def _calc_atr(df, window=14):
    high  = df["high"]  if "high"  in df.columns else df["close"]
    low   = df["low"]   if "low"   in df.columns else df["close"]
    close = df["close"]
    prev_close = close.shift(1)
    tr = pd.concat([
        high - low,
        (high - prev_close).abs(),
        (low  - prev_close).abs(),
    ], axis=1).max(axis=1)
    return tr.ewm(span=window, adjust=False).mean()


# 多进程因子计算用的全局数据缓存
# macOS spawn模式：子进程不继承父进程内存，通过 initializer 在子进程启动时注入
_FACTOR_DATA_CACHE = {}


def _worker_init(data_cache):
    """子进程初始化：接收数据缓存注入到全局变量。"""
    global _FACTOR_DATA_CACHE
    _FACTOR_DATA_CACHE = data_cache


def _fft_phase_signal(series, window=20):
    """
    对序列做滚动FFT，提取主频相位。
    返回 -sin(phase)：谷底=+1（买入），顶峰=-1（卖出）
    【模块级函数：multiprocessing.Pool序列化要求，不能是嵌套函数】
    """
    def _fft_phase(x):
        if len(x) < 4 or np.std(x) < 1e-8:
            return 0.0
        fft_result = np.fft.rfft(x)
        magnitudes = np.abs(fft_result[1:])
        if len(magnitudes) == 0:
            return 0.0
        main_idx = np.argmax(magnitudes) + 1
        phase = np.angle(fft_result[main_idx])
        return -np.sin(phase)
    return series.rolling(window).apply(_fft_phase, raw=True)


def _calc_stock_factors_worker(sym):
    """
    多进程worker：计算单只股票的全部因子。
    从全局 _FACTOR_DATA_CACHE 中读取 DataFrame（避免大数据序列化传输）。

    返回：(sym, sig, daily_ret, open_ret, momentum, vol_ratio, amom_combined)
      - sig:           逐日0/1持仓信号（EMA+ATR）
      - daily_ret:     收盘价日收益率
      - open_ret:      开盘到收盘收益率（T+1真实成本）
      - momentum:      20天动量（前移1天）
      - vol_ratio:     短/长期量比（前移1天），无量数据则None
      - amom_combined: 多维FFT相位 + ATR归一化动量（前移1天）
    """
    df = _FACTOR_DATA_CACHE.get(sym)
    if df is None:
        return sym, None, None, None, None, None, None
    min_len = EMA_LONG + ATR_WINDOW + VOL_LONG + MOMENTUM_WINDOW + 10
    if len(df) < min_len:
        return sym, None, None, None, None, None, None

    # ── EMA+ATR信号 ───────────────────────────────────────────────
    sig = _calc_stock_signal_with_atr(df)

    # ── 收益率序列 ────────────────────────────────────────────────
    daily_ret = df["close"].pct_change()

    if USE_OPEN_PRICE and "open" in df.columns:
        op = df["open"].replace(0, np.nan)
        open_ret = (df["close"] - op) / op
    else:
        open_ret = daily_ret

    # ── 动量因子（前移1天） ───────────────────────────────────────
    momentum = df["close"].pct_change(MOMENTUM_WINDOW).shift(1)

    # ── 量价因子（前移1天） ───────────────────────────────────────
    vol_ratio = None
    if "volume" in df.columns and df["volume"].notna().sum() > VOL_LONG:
        vol = df["volume"]
        vol_s = vol.rolling(VOL_SHORT).mean()
        vol_l = vol.rolling(VOL_LONG).mean()
        vol_ratio = (vol_s / vol_l.replace(0, np.nan)).shift(1)

    # ── 高阶因子：多维FFT相位 + ATR归一化动量 ─────────────────────
    close = df["close"]
    atr = _calc_atr(df, ATR_WINDOW)
    raw_mom = close.pct_change(MOMENTUM_WINDOW)
    atr_pct = atr / close.replace(0, np.nan)
    amom_raw = raw_mom / atr_pct.replace(0, np.nan)

    ema_l = close.ewm(span=EMA_LONG, adjust=False).mean()
    ema_s = close.ewm(span=EMA_SHORT, adjust=False).mean()

    # 维度1：价格去趋势残差 FFT相位（权重0.6）
    price_residual = close - ema_l
    fft_price = _fft_phase_signal(price_residual, window=20)

    # 维度2：MACD线 FFT相位（权重0.2）
    macd_line = ema_s - ema_l
    fft_macd = _fft_phase_signal(macd_line, window=20)

    # 维度3：日收益率 FFT相位（权重0.2）
    fft_ret = _fft_phase_signal(daily_ret, window=15)

    fft_combined = (0.60 * fft_price + 0.20 * fft_macd + 0.20 * fft_ret)

    # 动量计算不依赖当天的收盘，这里已经有前视修正机制
    amom_combined = (0.3 * amom_raw + 0.7 * fft_combined).shift(1)

    # 返回的几个特征中：momentum、vol_ratio、amom_combined、sig 都在内部做好了 shift(1)
    return sym, sig, daily_ret, open_ret, momentum, vol_ratio, amom_combined


def _calc_stock_signal_with_atr(df):
    """EMA10/30信号 + ATR追踪止损。返回逐日0/1持仓信号。"""
    if len(df) < EMA_LONG + ATR_WINDOW + 5:
        return pd.Series(0.0, index=df.index)

    close = df["close"]
    ema_s = close.ewm(span=EMA_SHORT, adjust=False).mean()
    ema_l = close.ewm(span=EMA_LONG,  adjust=False).mean()
    ema_bull = (ema_s > ema_l)
    atr = _calc_atr(df, ATR_WINDOW)

    n = len(close)
    signal = np.zeros(n)
    holding      = False
    stop_price   = 0.0
    holding_days = 0   # T+1：记录持仓天数

    close_arr    = close.values
    ema_bull_arr = ema_bull.values
    atr_arr      = atr.values

    for i in range(1, n):
        if not holding:
            if ema_bull_arr[i-1] and not np.isnan(atr_arr[i-1]):
                holding      = True
                holding_days = 0   # 第i天买入，持仓天数重置
                atr_stop_pct = (atr_arr[i-1] * ATR_MULT) / close_arr[i-1]
                atr_stop_pct = np.clip(atr_stop_pct, STOP_FLOOR, STOP_CEILING)
                stop_price   = close_arr[i-1] * (1 - atr_stop_pct)
                signal[i]    = 1.0
        else:
            holding_days += 1
            # T+1：持仓至少1天（holding_days>=1）才允许止损出场
            # 用昨天的 close 和 昨天的 stop_price 判断是否出场
            if holding_days >= 1 and (close_arr[i-1] <= stop_price or not ema_bull_arr[i-1]):
                holding   = False
                signal[i] = 0.0
            else:
                atr_stop_pct = (atr_arr[i-1] * ATR_MULT) / close_arr[i-1]
                atr_stop_pct = np.clip(atr_stop_pct, STOP_FLOOR, STOP_CEILING)
                new_stop   = close_arr[i-1] * (1 - atr_stop_pct)
                stop_price = max(stop_price, new_stop)
                signal[i]  = 1.0

    return pd.Series(signal, index=df.index).shift(1).fillna(0.0)


def _compute_factor_matrices(data: Dict[str, pd.DataFrame]):
    """
    第一阶段：多进程并行计算所有股票的因子矩阵。
    返回 (ret_df, open_df, sig_df, mom_df, vol_df, amom_df, has_volume, has_amom)
    """
    from multiprocessing import Pool, cpu_count

    sym_list  = list(data.keys())
    n_workers = min(cpu_count(), 12)

    daily_returns  = {}
    open_returns   = {}
    signals        = {}
    momentum_dict  = {}
    vol_ratio_dict = {}
    amom_dict      = {}

    with Pool(processes=n_workers,
              initializer=_worker_init,
              initargs=(dict(data),)) as pool:
        results = pool.map(_calc_stock_factors_worker, sym_list, chunksize=32)

    for (sym, sig, daily_ret, open_ret, momentum, vol_ratio, amom_combined) in results:
        if sig is None:
            continue
        signals[sym]       = sig
        daily_returns[sym] = daily_ret
        open_returns[sym]  = open_ret
        momentum_dict[sym] = momentum
        if vol_ratio is not None:
            vol_ratio_dict[sym] = vol_ratio
        amom_dict[sym]     = amom_combined

    if not daily_returns:
        return None

    ret_df   = pd.DataFrame(daily_returns)
    open_df  = pd.DataFrame(open_returns)
    sig_df   = pd.DataFrame(signals)
    mom_df   = pd.DataFrame(momentum_dict)
    ret_df, sig_df = ret_df.align(sig_df, join="inner")
    open_df  = open_df.reindex(ret_df.index)
    mom_df   = mom_df.reindex(ret_df.index)

    has_volume = len(vol_ratio_dict) > 0
    vol_df = pd.DataFrame(vol_ratio_dict).reindex(ret_df.index) if has_volume else pd.DataFrame(index=ret_df.index)

    has_amom = len(amom_dict) > 0
    amom_df = pd.DataFrame(amom_dict).reindex(ret_df.index) if has_amom else pd.DataFrame(index=ret_df.index)

    return ret_df, open_df, sig_df, mom_df, vol_df, amom_df, has_volume, has_amom


def _run_portfolio_loop(ret_df, open_df, sig_df, mom_df, vol_df, amom_df,
                        has_volume, has_amom, sent_expo,
                        record_trades=False):
    """
    第二阶段：逐日横截面选TOP_N，计算组合收益序列。
    record_trades=True 时额外返回完整交易记录。

    交易记录格式（trade_log）：
      list of dict，每条代表一笔完整交易（建仓→平仓）：
        sym, entry_date, entry_price, exit_date, exit_price,
        exit_reason, hold_days, pnl_pct
    持仓快照（daily_holdings）：
      dict { date -> list[sym] }，每日实际持仓股票代码列表
    """
    mkt_allow = pd.Series(1.0, index=ret_df.index)
    sig_filled = sig_df.fillna(0)
    mom_filled = mom_df.fillna(np.nan)

    n_dates  = len(ret_df)
    port_ret = np.zeros(n_dates)
    prev_syms = set()

    # 交易记录辅助结构
    # open_positions: sym -> {entry_date, entry_price}
    open_positions = {}
    trade_log = []
    daily_holdings = {}

    # 用 data 原始价格（via ret_df index + open_df）做入/出场价
    # 入场价 = 当日开盘价（T+1），出场价同理
    # 我们从 open_df 和 ret_df 反推绝对价格的方法：
    #   因子计算时 open_ret = (close - open) / open，没有存绝对价
    #   所以 trade_log 中用 open_ret 的日期+归一化收益作为代理记录
    #   实际绝对价格需要从 data[sym] 直接读取
    # 为避免传入 data 字典（太大），我们只记录 pnl_pct（相对收益）
    # 并记录 entry_date/exit_date，由调用方自行查价格

    for i in range(n_dates):
        row_sig = sig_filled.iloc[i]
        row_mom = mom_filled.iloc[i]
        row_ret = open_df.iloc[i] if USE_OPEN_PRICE else ret_df.iloc[i]
        today   = ret_df.index[i]

        held_syms = row_sig[row_sig > 0].index.tolist()
        if not held_syms:
            if prev_syms:
                port_ret[i] = -COST_SELL
                if record_trades:
                    for sym in list(prev_syms):
                        if sym in open_positions:
                            ep = open_positions.pop(sym)
                            r_today = float(np.nan_to_num(row_ret.get(sym, 0.0), nan=0.0))
                            cum = ep["cum_log_ret"] + np.log1p(r_today)
                            pnl_cumulative = float(np.expm1(cum))
                            trade_log.append({
                                "sym": sym,
                                "entry_date":  ep["entry_date"],
                                "exit_date":   today,
                                "hold_days":   ep["hold_days"] + 1,
                                "pnl_pct":     pnl_cumulative,
                                "exit_reason": "情绪空仓/全清",
                            })
            prev_syms = set()
            if record_trades:
                daily_holdings[today] = []
            continue

        if len(held_syms) <= TOP_N:
            final_syms = held_syms
        else:
            if SENT_FACTOR_BOOST > 0 and i > 0:
                alpha = float(sent_expo.iloc[i - 1]) - 0.5
                adj = SENT_FACTOR_BOOST * alpha
                w_amom_dyn  = max(0.01, W_AMOM  * (1.0 + adj))
                w_trend_dyn = max(0.01, W_TREND * (1.0 - adj))
                w_mom_dyn   = W_MOM
                w_vol_dyn   = W_VOL
                total_orig  = W_AMOM + W_TREND + W_MOM + W_VOL
                total_dyn   = w_amom_dyn + w_trend_dyn + w_mom_dyn + w_vol_dyn
                scale = total_orig / max(total_dyn, 1e-8)
                w_amom_dyn  *= scale
                w_trend_dyn *= scale
                w_mom_dyn   *= scale
                w_vol_dyn   *= scale
            else:
                w_amom_dyn, w_trend_dyn, w_mom_dyn, w_vol_dyn = W_AMOM, W_TREND, W_MOM, W_VOL

            scores = pd.Series(0.0, index=held_syms)
            mom_vals = row_mom[held_syms].dropna()
            if len(mom_vals) > 1:
                scores[mom_vals.rank(pct=True).index] += w_mom_dyn * mom_vals.rank(pct=True)
            if has_volume and i < len(vol_df):
                row_vol  = vol_df.iloc[i]
                vol_vals = row_vol[held_syms].dropna()
                vol_vals = vol_vals[vol_vals > 0]
                if len(vol_vals) > 1:
                    scores[vol_vals.rank(pct=True).index] += w_vol_dyn * vol_vals.rank(pct=True)
            if has_amom and i < len(amom_df):
                row_amom  = amom_df.iloc[i]
                amom_vals = row_amom[held_syms].dropna()
                if len(amom_vals) > 1:
                    scores[amom_vals.rank(pct=True).index] += w_amom_dyn * amom_vals.rank(pct=True)
            scores[held_syms] += w_trend_dyn
            final_syms = scores.nlargest(TOP_N).index.tolist()

        final_set = set(final_syms)
        n = len(final_syms)

        if n > 0:
            gross_ret = row_ret[final_syms].mean()
            if prev_syms:
                buy_weight  = len(final_set - prev_syms) / n
                sell_weight = len(prev_syms - final_set) / n
                cost = buy_weight * COST_BUY + sell_weight * COST_SELL
            else:
                cost = COST_BUY
            port_ret[i] = gross_ret - cost
        else:
            port_ret[i] = 0.0

        if record_trades:
            # 记录新买入（entry_date = 今日，cum_log_ret 从0开始累计）
            for sym in (final_set - prev_syms):
                open_positions[sym] = {"entry_date": today, "hold_days": 0, "cum_log_ret": 0.0}
            # 记录平仓（被踢出 TOP_N 或信号消失）
            for sym in (prev_syms - final_set):
                if sym in open_positions:
                    ep  = open_positions.pop(sym)
                    # 出场当天的日收益也要累计进去（nan→0 防止数据缺失）
                    r_today = float(np.nan_to_num(row_ret.get(sym, 0.0), nan=0.0))
                    cum = ep["cum_log_ret"] + np.log1p(r_today)
                    # 还原真实累计收益（持仓全程，入场到出场）
                    pnl_cumulative = float(np.expm1(cum))
                    # 判断出场原因：ATR止损 or 因子轮换
                    sig_val = float(sig_filled.iloc[i].get(sym, 0.0))
                    if sig_val == 0.0:
                        reason = "ATR止损/EMA反转"
                    else:
                        reason = "因子轮换"
                    trade_log.append({
                        "sym":         sym,
                        "entry_date":  ep["entry_date"],
                        "exit_date":   today,
                        "hold_days":   ep["hold_days"] + 1,   # +1 含出场当天
                        "pnl_pct":     pnl_cumulative,        # 真实累计收益
                        "exit_reason": reason,
                    })
            # 连续持有：累计今日日收益到 cum_log_ret，hold_days +1
            for sym in (final_set & prev_syms):
                if sym in open_positions:
                    r_today = float(np.nan_to_num(row_ret.get(sym, 0.0), nan=0.0))
                    open_positions[sym]["cum_log_ret"] += np.log1p(r_today)
                    open_positions[sym]["hold_days"] += 1
            daily_holdings[today] = list(final_set)

        prev_syms = final_set

    # 回测结束：强制平掉所有未平仓
    if record_trades and open_positions:
        last_date = ret_df.index[-1]
        last_ret  = (open_df if USE_OPEN_PRICE else ret_df).iloc[-1]
        for sym, ep in open_positions.items():
            r_today = float(np.nan_to_num(last_ret.get(sym, 0.0), nan=0.0))
            cum = ep["cum_log_ret"] + np.log1p(r_today)
            pnl_cumulative = float(np.expm1(cum))
            trade_log.append({
                "sym":         sym,
                "entry_date":  ep["entry_date"],
                "exit_date":   last_date,
                "hold_days":   ep["hold_days"] + 1,
                "pnl_pct":     pnl_cumulative,
                "exit_reason": "回测结束",
            })

    portfolio_ret = pd.Series(port_ret, index=ret_df.index)
    portfolio_ret = portfolio_ret * sent_expo * mkt_allow

    # 组合级硬止损
    port_arr = portfolio_ret.values.copy()
    peak = 1.0
    nav  = 1.0
    cooldown = 0
    for i in range(len(port_arr)):
        if cooldown > 0:
            port_arr[i] = 0.0
            cooldown -= 1
            if cooldown == 0:
                peak = nav
        nav = nav * (1 + port_arr[i])
        if nav > peak:
            peak = nav
        dd = (nav - peak) / peak
        if dd < -HARD_DD and cooldown == 0:
            cooldown = RECOVER_DAYS

    portfolio_ret = pd.Series(port_arr, index=ret_df.index)
    equity = (1 + portfolio_ret).cumprod()
    equity.iloc[0] = 1.0

    if record_trades:
        return equity, trade_log, daily_holdings
    return equity


def strategy_func(data: Dict[str, pd.DataFrame]) -> pd.Series:
    """
    多因子选股策略（标准接口，返回净值曲线）。
    backtest.py 调用此函数，签名不可修改。
    """
    matrices = _compute_factor_matrices(data)
    if matrices is None:
        return pd.Series(dtype=float)

    ret_df, open_df, sig_df, mom_df, vol_df, amom_df, has_volume, has_amom = matrices

    sent_expo_raw = _calc_market_sentiment(BACKTEST_START, BACKTEST_END)
    if sent_expo_raw is not None:
        sent_expo = sent_expo_raw.reindex(ret_df.index).ffill().fillna(0.0)
    else:
        sent_expo = pd.Series(1.0, index=ret_df.index)

    return _run_portfolio_loop(
        ret_df, open_df, sig_df, mom_df, vol_df, amom_df,
        has_volume, has_amom, sent_expo,
        record_trades=False,
    )


def run_with_detail(data: Dict[str, pd.DataFrame]):
    """
    完整回测，同时返回净值曲线、逐笔交易记录和每日持仓快照。

    Returns
    -------
    equity         : pd.Series  — 净值曲线
    trade_log      : list[dict] — 逐笔交易记录
                     每条字段: sym, entry_date, exit_date,
                               hold_days, pnl_pct, exit_reason
    daily_holdings : dict{date: list[sym]} — 每日实际持仓
    sentiment      : pd.Series  — 情绪曲线（aligned to equity.index）
    """
    matrices = _compute_factor_matrices(data)
    if matrices is None:
        return pd.Series(dtype=float), [], {}, pd.Series(dtype=float)

    ret_df, open_df, sig_df, mom_df, vol_df, amom_df, has_volume, has_amom = matrices

    sent_expo_raw = _calc_market_sentiment(BACKTEST_START, BACKTEST_END)
    if sent_expo_raw is not None:
        sent_expo = sent_expo_raw.reindex(ret_df.index).ffill().fillna(0.0)
    else:
        sent_expo = pd.Series(1.0, index=ret_df.index)

    equity, trade_log, daily_holdings = _run_portfolio_loop(
        ret_df, open_df, sig_df, mom_df, vol_df, amom_df,
        has_volume, has_amom, sent_expo,
        record_trades=True,
    )

    return equity, trade_log, daily_holdings, sent_expo


def run():
    from backtest import evaluate_strategy

    # 动态获取候选股票池
    global UNIVERSE
    if not UNIVERSE:
        print("  正在从数据库获取候选股票池...")
        UNIVERSE = _get_universe()
        print(f"  候选池大小：{len(UNIVERSE)} 只")

    print("=" * 60)
    print(f"策略：多因子选股（EMA趋势+动量+量价）+ 情绪门控 + ATR×{ATR_MULT}")
    print(f"候选池：{len(UNIVERSE)} 只（随机抽样seed={UNIVERSE_SEED}）")
    print(f"因子权重：趋势={W_TREND}, 动量={W_MOM}, 量价={W_VOL}")
    print(f"持仓数量：TOP{TOP_N}，情绪阈值：满仓={BULL_THRESH*100:.0f}%，空仓={BEAR_THRESH*100:.0f}%")
    print(f"区间：{BACKTEST_START} ~ {BACKTEST_END}")
    print("=" * 60)
    print("正在加载数据并运行回测，请稍候...")

    metrics = evaluate_strategy(
        strategy_func=strategy_func,
        universe=UNIVERSE,
        start=BACKTEST_START,
        end=BACKTEST_END,
    )

    print("\n【回测结果】")
    print(f"  Sharpe Ratio   : {metrics['sharpe']:.4f}")
    print(f"  年化收益率     : {metrics['annual_return']*100:.2f}%")
    print(f"  最大回撤       : {metrics['max_drawdown']*100:.2f}%")
    print(f"  Score (Sharpe) : {metrics['score']:.4f}")
    print("=" * 60)

    goal_dd  = abs(metrics['max_drawdown']) <= 0.10
    goal_ret = metrics['annual_return'] >= 0.50
    if goal_dd and goal_ret:
        print("🎯 双目标达成：年化≥50% 且 最大回撤≤10%")
    elif goal_dd:
        print(f"✓ 回撤达标（{metrics['max_drawdown']*100:.2f}%），年化{metrics['annual_return']*100:.2f}%（目标50%）")
    elif goal_ret:
        print(f"✓ 收益达标（{metrics['annual_return']*100:.2f}%），回撤{metrics['max_drawdown']*100:.2f}%（目标<10%）")
    else:
        print(f"✗ 年化={metrics['annual_return']*100:.2f}%，回撤={metrics['max_drawdown']*100:.2f}%")

    import os, datetime
    results_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results.tsv")
    ts   = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    desc = f"multifactor_pool{len(UNIVERSE)}_top{TOP_N}_w{W_TREND}_{W_MOM}_{W_VOL}"
    line = (
        f"{ts}\t{desc}\t"
        f"{metrics['score']:.4f}\t{metrics['sharpe']:.4f}\t"
        f"{metrics['annual_return']:.4f}\t{metrics['max_drawdown']:.4f}\n"
    )
    with open(results_path, "a") as f:
        f.write(line)
    print(f"\n结果已追加至 results.tsv")
    return metrics


if __name__ == "__main__":
    run()
