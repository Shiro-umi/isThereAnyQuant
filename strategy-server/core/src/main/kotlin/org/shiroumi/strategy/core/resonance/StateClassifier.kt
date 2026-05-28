package org.shiroumi.strategy.core.resonance

import kotlinx.datetime.LocalDate
import org.shiroumi.quant_kmp.strategy.daily.model.SentimentFactorSnapshot
import org.shiroumi.quant_kmp.strategy.daily.model.MarketRegime
import org.shiroumi.quant_kmp.strategy.daily.model.RegimeCategory
import org.shiroumi.quant_kmp.strategy.daily.model.TrendLevel

/**
 * 市场状态分类器 —— 从 Study 的 stateAt / buildStateSlices 中提取的独立模块。
 *
 * 输入：当日因子快照 + 历史窗口
 * 输出：[MarketRegime]（三轴分档 + 业务语义分类）
 *
 * 此逻辑与 [SentimentResonanceStudy.stateAt] 完全一致，
 * 输入类型从 [SentimentFactorDailyRecord] 改为 [SentimentFactorSnapshot]（共享契约），
 * 输出增加了业务语义分类 [RegimeCategory] 方便 SelectionRuleEngine 按六大状态查询。
 *
 * 生产环境：盘中/盘后均可调用，纯计算、无 IO。
 */
object StateClassifier {

    private const val DEFAULT_WINDOW = 100

    /**
     * 给定当日快照和足够长的历史窗口，返回当前市场状态。
     *
     * @param today        当日因子快照
     * @param historyWindow 历史因子快照（按 tradeDate 升序），至少 100 天
     * @param windowSize    状态回溯窗口，默认 100（与 Study state-window 对齐）
     * @return MarketRegime，或 null（数据不足）
     */
    fun classify(
        today: SentimentFactorSnapshot,
        historyWindow: List<SentimentFactorSnapshot>,
        windowSize: Int = DEFAULT_WINDOW,
    ): MarketRegime? {
        // 取最近 windowSize 个历史快照（含 today）
        val recent = historyWindow.filter { it.tradeDate <= today.tradeDate }
            .takeLast(windowSize)
        if (recent.size < 5) return null

        // 趋势方向：D4（A1 的 20 日分位数），fallback 到 A1
        val trendValues = recent.map { it.factors["D4"] ?: it.factors["A1"] }
        val trendValue = trendValues.lastOrNull() ?: return null
        val trendRank = percentileRank(trendValue, trendValues.filterNotNull())
        val trendLevel = when {
            trendRank < 1.0 / 3.0 -> TrendLevel.LOW
            trendRank < 2.0 / 3.0 -> TrendLevel.MID
            else -> TrendLevel.HIGH
        }

        // 分化度：B3'（分化度变化），fallback 到 B3
        val dispValues = recent.map { it.factors["B3p"] ?: it.factors["B3"] }
        val dispValue = dispValues.lastOrNull() ?: return null
        val dispRank = percentileRank(dispValue, dispValues.filterNotNull())
        val dispLevel = when {
            dispRank < 1.0 / 3.0 -> TrendLevel.LOW
            dispRank < 2.0 / 3.0 -> TrendLevel.MID
            else -> TrendLevel.HIGH
        }

        // 量能水平：EMA(A3, 5) 的当前值
        val a3Values = recent.map { it.factors["A3"] ?: 0.0 }
        val volEma = ema(a3Values, span = 5)
        val volValue = volEma.lastOrNull() ?: return null
        // volValue 的分位数基于近期 A3 EMA 分布
        val volRank = percentileRank(volValue, volEma.toList())
        val volLevel = when {
            volRank < 1.0 / 3.0 -> TrendLevel.LOW
            volRank < 2.0 / 3.0 -> TrendLevel.MID
            else -> TrendLevel.HIGH
        }

        // raw stateId 与 Study 的 stateId 格式一致
        val stateId = buildStateId(trendLevel, dispLevel, volLevel)

        // 业务语义分类
        val category = categorize(trendLevel, dispLevel, volLevel)

        return MarketRegime(
            tradeDate = today.tradeDate,
            trendLevel = trendLevel,
            dispersionLevel = dispLevel,
            volumeLevel = volLevel,
            regimeCategory = category,
            stateId = stateId,
        )
    }

    /**
     * 构建与 ResonanceCard.state_id 格式对齐的状态 id。
     *
     * 注意：Study 会通过 mergeState 生成合并状态（如 "low+mid"），
     * 这里只产生精确状态的 stateId。SelectionRuleEngine 在匹配时做模糊匹配。
     */
    private fun buildStateId(trend: TrendLevel, disp: TrendLevel, vol: TrendLevel): String {
        val tl = trend.name.lowercase()
        val dl = disp.name.lowercase()
        val vl = vol.name.lowercase()
        return "trend=$tl,disp=$dl,vol=$vl"
    }

    /**
     * 三轴分档 → 六大业务状态映射。
     *
     * 规则遵循 §10.4 的六状态覆盖逻辑：
     * - 下跌(trend=LOW) + 高分化 + 放量 → PANIC_RELEASE
     * - 上涨(trend=HIGH) + 高分化 + 放量 → STRONG_OSCILLATION
     * - 上涨(trend=HIGH) + 低分化 + 缩量 → TREND_CONTINUATION
     * - 下跌(trend=LOW) + 低分化 + 缩量 → BOTTOM_WATCH
     * - 震荡(其他)  + 高分化 + 放量 → EXTREME_GAME
     * - 其余 → CROSS_REGIME（跨状态基线规则）
     */
    private fun categorize(trend: TrendLevel, disp: TrendLevel, vol: TrendLevel): RegimeCategory {
        return when {
            trend == TrendLevel.LOW && disp == TrendLevel.HIGH && vol == TrendLevel.HIGH ->
                RegimeCategory.PANIC_RELEASE
            trend == TrendLevel.HIGH && disp == TrendLevel.HIGH && vol == TrendLevel.HIGH ->
                RegimeCategory.STRONG_OSCILLATION
            trend == TrendLevel.HIGH && disp == TrendLevel.LOW && vol == TrendLevel.LOW ->
                RegimeCategory.TREND_CONTINUATION
            trend == TrendLevel.LOW && disp == TrendLevel.LOW && vol == TrendLevel.LOW ->
                RegimeCategory.BOTTOM_WATCH
            disp == TrendLevel.HIGH && vol == TrendLevel.HIGH ->
                RegimeCategory.EXTREME_GAME
            else -> RegimeCategory.CROSS_REGIME
        }
    }
}

/**
 * 给定值和值列表，返回值的分位数 (0-1)。
 * 与 Study.stateAt 的 rank 计算逻辑一致。
 */
internal fun percentileRank(value: Double, values: List<Double>): Double {
    if (values.isEmpty()) return 0.5
    val less = values.count { it < value }
    return less.toDouble() / values.size
}

/**
 * 指数移动平均。
 * 与 Study.ema 实现一致。
 */
internal fun ema(values: List<Double>, span: Int): DoubleArray {
    if (values.isEmpty()) return DoubleArray(0)
    val alpha = 2.0 / (span + 1.0)
    val out = DoubleArray(values.size)
    out[0] = values[0]
    for (i in 1 until values.size) {
        out[i] = alpha * values[i] + (1.0 - alpha) * out[i - 1]
    }
    return out
}
