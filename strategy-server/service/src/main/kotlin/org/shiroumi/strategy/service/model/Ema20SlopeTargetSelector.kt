package org.shiroumi.strategy.service.model

import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.ProductionOhlcvWindowRow
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.TargetPosition
import utils.logger

/**
 * EMA20 斜率选股器（三件套口径）：替代 [ProfitPredictionModelSelector] 的生产选股口径。
 *
 * 业务背景：实盘交易路径把盘后选股从 v5 盈利预测模型 Top-N 换成「EMA20 斜率最大的 Top-N」趋势池。
 * 口径演进：
 *  - 2026-06-29 用户拍板首版：末两点斜率 (ema[T]-ema[T-1])/ema[T-1] Top5 + 信号日涨停剔除。
 *  - 2026-07-02 用户拍板三件套（全期 2020-06..2026-05 四臂回测 + 对抗审计裁决，
 *    见 temp/ema20_slope_ab_result.json / temp/ema20_gates_combo_result.json）：
 *    ① 斜率窗口末两点 → 10 日：slope = (ema[T] - ema[T-slopeWindow]) / ema[T-slopeWindow]。
 *       末两点斜率数学恒等于单日乖离率 α·(c[T]/ema[T-1]-1)，排序被单日行为主导；
 *       slope10 全期 EV +0.659%→+0.865%、巨亏(≤-20%)175→113、7 年全正（末两点 2024 年为负）。
 *    ② G1∪G2 尾控否决门（gate-revenue-accounting @30%）：
 *       B1 = close/max(近60根close)（贴顶=危险）、D1 = 近5根 MACD 柱 2*(DIF-DEA) 一阶差分均值
 *       （高=推力仍冲=危险）。b1 > [gateB1Threshold] 或 d1 > [gateD1Threshold] → 否决该票。
 *       阈值为全期选票分布 70 分位固定绝对值（因果阈值复核：expanding/frozen 与全期分位
 *       结论同向，见 temp/ema20_gate_causal_threshold_result.json）。叠门后全期
 *       EV +0.865%→+1.066%、巨亏(≤-20%)113→27、maxloss -31%→-27.7%、巨亏占比过随机否决
 *       占比铁证；门在末两点乖离率池上失效反噬（信号无梯度），故门必须与 slope10 配套启用。
 *    ③ 信号日收盘涨停剔除保留（对照 crashdip 基线为强正贡献过滤）。
 *  - 2026-07-02 证伪重审后用户拍板补位语义 B：门否决位由排序后继非否决票顺位补足
 *    （[gateBackfill]，补位票同样过门，扫描上限 [BACKFILL_SCAN_LIMIT] 位）。原"补位证伪"
 *    是 crashdip 负 EV 池结论的跨池外推；slope10 正 EV 池直接重验翻案
 *    （temp/ema20_falsify_recheck.py，对抗审计 PASS）：组合口径（每天 topN 槽、空位记 0）
 *    门+补位 +0.736% vs 门+空仓 +0.595%（+0.141pp/仓位），补位笔自身 EV +0.333% 超随机、
 *    尾率不恶化（maxloss 同 -27.7%）；第 6-10 名 EV +0.430% 仍带 2 倍随机 alpha。
 *    全期 1453 信号日仅 2 天扫描后仍补不满（极端全池贴顶日），该情形留现金空位。
 *    up_days 连涨兑现第三门三关核账不过（EV 落随机 63.8 分位/分年 2/7），不叠加。
 *    gateBackfill=false 回退语义 A（否决位=现金空仓，2026-07-02 前口径）。
 *  - 选股因子用前复权 close_qfq，比值口径对复权因子平移免疫。
 *  - selectionScore = EMA20 斜率：下游 advanceHoldings 按 entryPriority 降序建仓，
 *    填斜率即「斜率最高的若干只优先入场」，与生产 entry-cap 口径一致，下游零改动。
 *
 * 边界纪律：
 *  - 实现 [ProfitPredictionTargetSelector] 接口，与 [ProfitPredictionModelSelector] 同契约，
 *    可在 [PostMarketPreparationJob] 装配点一键替换 / 回滚；三件套内部各组件可经
 *    system property 单独回退（slopeWindow=1 回末两点、gateEnabled=false 关门）。
 *  - 零 DB 直连：取数走已有 [StockDailyCandleRepository]，涨停前收走 [TradingCalendarRepository]
 *    + 同 Repository。判定逻辑全部可注入桩，可单测。
 *  - 返回 [TargetPosition] 字段口径与 [ProfitPredictionModelSelector] 完全一致（selected 票
 *    targetWeight=1/topN、未选票 weight=0、sentimentExposure 透传），使 SelectionDriftGuard /
 *    写库 / 审计链路无差别消费。门控否决时 selected 数可少于 topN，等权分母仍为 topN。
 *
 * 防未来函数：
 *  - tradeDate 是信号日 T（盘后选股发生日）。EMA20/B1/D1 全部只取 endDateInclusive=tradeDate
 *    的窗口，不触碰 T+1 及之后任何信息。
 *  - 涨停判定用 T 日收盘 vs T 的前一交易日收盘，同样不越过 T。
 *
 * 与回测口径的已知残差（声明，均经数据量化）：
 *  - EMA 预热：回测 slope10 用全历史递推，生产用末 [windowSize]=70 条记录窗递推。数值残差
 *    上界 ~0.004（窗口初值权重 (20/21)^59-(20/21)^69 ≈ 2% 的乖离传导），但选票层面抽样
 *    60 信号日 Top5 重合 300/300 = 100%（极值票斜率间距远大于扰动），排序不受影响。
 *  - B1/D1 窗基准：回测用 60/61 个日历交易日窗（遇缺口重启），生产是「最近 60/61 条交易
 *    记录」跨停牌拼接；仅近期停牌过的票数值微差，门为阈值比较非排序，影响有限。
 */
internal class Ema20SlopeTargetSelector(
    private val topN: Int = System.getProperty("quant.ema20Selection.topN", "5").toInt(),
    private val emaSpan: Int = System.getProperty("quant.ema20Selection.emaSpan", "20").toInt(),
    // 斜率窗口：slope = (ema[T] - ema[T-slopeWindow]) / ema[T-slopeWindow]。
    // 默认 10（全期回测裁决口径）；slopeWindow=1 回退 2026-06-29 末两点旧口径。
    private val slopeWindow: Int = System.getProperty("quant.ema20Selection.slopeWindow", "10").toInt(),
    // EMA20 收敛需要足够预热；slope 需 emaSpan+slopeWindow+1 行起步，D1 需 61 行窗内 >=35 行。
    private val windowSize: Int = System.getProperty("quant.ema20Selection.windowSize", "70").toInt(),
    // G1∪G2 尾控否决门。阈值 = 全期（2020-06..2026-05）slope10 Top5 选票 b1/d1 分布 70 分位固定值，
    // 产自 temp/ema20_gate_causal_threshold_result.json；重定标时更新 property 即可。
    private val gateEnabled: Boolean = System.getProperty("quant.ema20Selection.gateEnabled", "true").toBoolean(),
    private val gateB1Threshold: Double =
        System.getProperty("quant.ema20Selection.gateB1Threshold", "0.965903").toDouble(),
    private val gateD1Threshold: Double =
        System.getProperty("quant.ema20Selection.gateD1Threshold", "0.004666").toDouble(),
    // 语义 B 顺位补位（2026-07-02 翻案接入）；false 回退语义 A（否决位=现金空仓）。
    private val gateBackfill: Boolean = System.getProperty("quant.ema20Selection.gateBackfill", "true").toBoolean(),
    private val dataSource: Ema20SelectionDataSource = DatabaseEma20SelectionDataSource(),
) : ProfitPredictionTargetSelector {
    private val logger by logger("Ema20SlopeTargetSelector")

    override suspend fun generateTargets(
        tradeDate: LocalDate,
        targetDate: LocalDate,
        universeSymbols: List<String>,
        sentiment: MarketSentimentSnapshot,
    ): List<TargetPosition> {
        require(topN > 0) { "ema20 selection topN must be positive, got $topN" }
        require(emaSpan >= 2) { "ema20 selection emaSpan must be >= 2, got $emaSpan" }
        require(slopeWindow >= 1) { "ema20 selection slopeWindow must be >= 1, got $slopeWindow" }

        // 1) 信号日 T 收盘涨停集合（剔除用）。前收来自 T 的前一交易日。
        val limitUpSymbols = dataSource.loadLimitUpSymbols(tradeDate)

        // 2) 候选池窗口前复权 close_qfq（末 windowSize 行，endDateInclusive=tradeDate，防未来函数）。
        val windows = dataSource.loadCloseQfqWindows(
            tsCodes = universeSymbols,
            endDateInclusive = tradeDate,
            limitPerStock = windowSize,
        )

        // 3) 逐票算 EMA20 斜率。剔除：窗口末行不是 T（停牌/数据缺）、预热不足、信号日涨停。
        class Scored(val tsCode: String, val slope: Double, val closes: DoubleArray)
        val scored = ArrayList<Scored>(universeSymbols.size)
        var droppedLimitUp = 0
        var droppedInsufficient = 0
        for (tsCode in universeSymbols) {
            if (tsCode in limitUpSymbols) {
                droppedLimitUp++
                continue
            }
            val rows = windows[tsCode].orEmpty()
            // 末行必须正好是信号日 T：否则该票当日停牌或数据未到，不可选。
            if (rows.isEmpty() || rows.last().tradeDate != tradeDate) {
                droppedInsufficient++
                continue
            }
            // 仅裁掉「最后一个未复权行」之前的上下文，与生产取数降级口径一致。
            val lastIncomplete = rows.indexOfLast { !it.adjustedComplete }
            val usable = if (lastIncomplete >= 0) rows.drop(lastIncomplete + 1) else rows
            // 预热门槛 emaSpan+slopeWindow+1：slope10 口径 =31，与全期回测 B 臂同池门槛严格一致。
            // 注意 slopeWindow=1 回退口径下 =22，比 2026-06-29 旧版（emaSpan+1=21）严一根——
            // 仅影响「恰好 21 条记录」的次新股边界票，回退语义可接受。
            if (usable.size < emaSpan + slopeWindow + 1 || usable.last().tradeDate != tradeDate) {
                droppedInsufficient++
                continue
            }
            val closes = DoubleArray(usable.size) { usable[it].closeQfq }
            if (closes.any { it <= 0.0 }) {
                droppedInsufficient++
                continue
            }
            val ema = ema(closes, emaSpan)
            val base = ema[ema.size - 1 - slopeWindow]
            if (base <= 0.0) {
                droppedInsufficient++
                continue
            }
            val slope = (ema[ema.size - 1] - base) / base
            scored += Scored(tsCode, slope, closes)
        }

        // 4) 斜率降序取 Top N 候选。同分按 tsCode 升序保证确定性。
        val ranked = scored.sortedWith(
            compareByDescending<Scored> { it.slope }.thenBy { it.tsCode }
        )
        val topCandidates = ranked.take(topN)

        // 5) G1∪G2 尾控否决门：Top-N 内逐票判定；否决位默认由排序后继非否决票顺位补足
        //    （语义 B，补位票同样过门，扫描上限 BACKFILL_SCAN_LIMIT；gateBackfill=false 回退
        //    语义 A=否决位现金空仓）。信号不可算（NaN）= 不可判，保留。
        //    防呆耦合：门阈值在 slope10 选票分布上定标，且组合核账证明门在末两点乖离率池上
        //    失效反噬（EV 落随机 10.8 分位）——slopeWindow=1 回退时强制关门，防止应急回退
        //    斜率口径却忘关门、把被证伪的组合推上实盘。
        val gateEffective = gateEnabled && slopeWindow != 1
        if (gateEnabled && !gateEffective) {
            logger.warning("[EMA20选股] slopeWindow=1 回退口径下 G1∪G2 门强制关闭（门在乖离率池上失效反噬，见组合核账）")
        }
        val vetoReasons = LinkedHashMap<String, String>()
        val selectedCodes = LinkedHashSet<String>()
        var backfilled = 0
        if (gateEffective) {
            fun vetoReasonOf(candidate: Scored): String? {
                val b1 = b1TopProximity(candidate.closes)
                val d1 = d1MacdImpulse(candidate.closes)
                val vetoB1 = !b1.isNaN() && b1 > gateB1Threshold
                val vetoD1 = !d1.isNaN() && d1 > gateD1Threshold
                if (!vetoB1 && !vetoD1) return null
                return buildString {
                    append("b1=").append(formatSlope(b1))
                    if (vetoB1) append("!")
                    append(" d1=").append(formatSlope(d1))
                    if (vetoD1) append("!")
                }
            }
            for (candidate in topCandidates) {
                val veto = vetoReasonOf(candidate)
                if (veto != null) vetoReasons[candidate.tsCode] = veto else selectedCodes += candidate.tsCode
            }
            if (gateBackfill) {
                var scan = topN
                while (selectedCodes.size < topN && scan < minOf(ranked.size, BACKFILL_SCAN_LIMIT)) {
                    val candidate = ranked[scan]
                    scan++
                    val veto = vetoReasonOf(candidate)
                    if (veto != null) {
                        vetoReasons[candidate.tsCode] = veto
                    } else {
                        selectedCodes += candidate.tsCode
                        backfilled++
                    }
                }
            }
        } else {
            topCandidates.forEach { selectedCodes += it.tsCode }
        }
        // 等权分母恒为 topN：否决位 = 现金空仓（回测语义 A 口径），不得摊给留存票。
        val weightPerStock = if (selectedCodes.isEmpty()) 0.0 else 1.0 / topN
        val exposure = sentiment.sentimentExposure

        logger.info(
            "[EMA20选股] tradeDate=$tradeDate universe=${universeSymbols.size} " +
                "limitUpDropped=$droppedLimitUp insufficientDropped=$droppedInsufficient " +
                "scored=${ranked.size} topN=$topN slopeWindow=$slopeWindow " +
                "gateVetoed=${vetoReasons.size}" +
                (if (vetoReasons.isEmpty()) "" else " vetoedCodes=${vetoReasons.keys}") +
                " backfilled=$backfilled selected=${selectedCodes.size} exposure=$exposure"
        )

        // 6) 产出与 ProfitPredictionModelSelector 同构的 TargetPosition 列表。
        return ranked.mapIndexed { rank, s ->
            val selected = s.tsCode in selectedCodes
            val veto = vetoReasons[s.tsCode]
            TargetPosition(
                tradeDate = tradeDate,
                targetDate = targetDate,
                tsCode = s.tsCode,
                selectionScore = s.slope,
                selected = selected,
                targetWeight = if (selected) weightPerStock else 0.0,
                sentimentExposure = exposure,
                selectionReason = when {
                    selected && rank < topN -> "ema20-slope:Top$topN slope=${formatSlope(s.slope)}"
                    selected -> "ema20-slope:backfill@${rank + 1} slope=${formatSlope(s.slope)}"
                    veto != null -> "ema20-slope:gate-veto[$veto] slope=${formatSlope(s.slope)}"
                    else -> "ema20-slope:candidate slope=${formatSlope(s.slope)}"
                },
            )
        }
    }

    internal companion object {
        /**
         * 语义 B 补位的排序位扫描上限。与全期回测 temp/ema20_falsify_recheck.py
         * BACKFILL_SCAN=60 一致；全期 1453 信号日仅 2 天在此上限内仍补不满。
         */
        const val BACKFILL_SCAN_LIMIT = 60

        /**
         * 标准 EMA，alpha=2/(span+1)，首值初始化。与回测脚本 ema() 同口径。
         */
        fun ema(arr: DoubleArray, span: Int): DoubleArray {
            val alpha = 2.0 / (span + 1.0)
            val out = DoubleArray(arr.size)
            out[0] = arr[0]
            for (i in 1 until arr.size) {
                out[i] = alpha * arr[i] + (1 - alpha) * out[i - 1]
            }
            return out
        }

        /**
         * G1 信号：B1 贴顶度 = close[T] / max(近 [win] 根 close)。1.0 = 贴 60 日新高（危险端）。
         * 与研究 gate_rev_base.b1_at 同源：窗口有效行 < 5 不可判返回 NaN。
         * [closes] 为该票最近交易记录的 close_qfq 序列（尾部 = 信号日 T，调用方保证全正）。
         */
        fun b1TopProximity(closes: DoubleArray, win: Int = 60): Double {
            val n = closes.size
            if (n < 5) return Double.NaN
            var mx = 0.0
            for (i in maxOf(0, n - win) until n) {
                if (closes[i] > mx) mx = closes[i]
            }
            return if (mx > 0.0) closes[n - 1] / mx else Double.NaN
        }

        /**
         * G2 信号：D1 MACD 柱推力 = 近 [diffBars] 根 MACD 柱（2*(DIF-DEA)，EMA12/26/9）
         * 一阶差分均值。高 = 柱仍在放大 = 推力冲刺（危险端）；低/负 = 推力钝化。
         * 与研究 gate_rev_base.d1_at 同源：取末 [win]=61 根窗，有效行 < 35 不可判返回 NaN。
         */
        fun d1MacdImpulse(closes: DoubleArray, win: Int = 61, diffBars: Int = 5): Double {
            val seg = if (closes.size > win) closes.copyOfRange(closes.size - win, closes.size) else closes
            if (seg.size < 35) return Double.NaN
            val ema12 = ema(seg, 12)
            val ema26 = ema(seg, 26)
            val dif = DoubleArray(seg.size) { ema12[it] - ema26[it] }
            val dea = ema(dif, 9)
            val last = seg.size - 1
            var acc = 0.0
            for (k in 0 until diffBars - 1) {
                val newer = 2.0 * (dif[last - k] - dea[last - k])
                val older = 2.0 * (dif[last - k - 1] - dea[last - k - 1])
                acc += newer - older
            }
            return acc / (diffBars - 1)
        }

        /** JS 平台不兼容 String.format，手动保留 6 位小数（与项目规范一致）。 */
        fun formatSlope(v: Double): String {
            if (v.isNaN()) return "NaN"
            val scaled = kotlin.math.round(v * 1_000_000.0) / 1_000_000.0
            return scaled.toString()
        }
    }
}

/**
 * EMA20 选股器数据源边界。生产实现走库表；单测注入桩。
 */
internal interface Ema20SelectionDataSource {
    /** 信号日 [tradeDate] 收盘已涨停的票集合（前收取 [tradeDate] 的前一交易日收盘）。 */
    fun loadLimitUpSymbols(tradeDate: LocalDate): Set<String>

    /** 候选票末 [limitPerStock] 行前复权窗口（endDateInclusive=[endDateInclusive]）。 */
    fun loadCloseQfqWindows(
        tsCodes: List<String>,
        endDateInclusive: LocalDate,
        limitPerStock: Int,
    ): Map<String, List<ProductionOhlcvWindowRow>>
}

internal class DatabaseEma20SelectionDataSource : Ema20SelectionDataSource {

    override fun loadLimitUpSymbols(tradeDate: LocalDate): Set<String> {
        val previousDate = TradingCalendarRepository.findPreviousTradingDate(tradeDate) ?: return emptySet()
        val preClose = StockDailyCandleRepository.findByTradeDate(previousDate)
            .associate { it.tsCode to it.close.toDouble() }
        if (preClose.isEmpty()) return emptySet()
        return StockDailyCandleRepository.findByTradeDate(tradeDate)
            .asSequence()
            .filter { candle ->
                val previousClose = preClose[candle.tsCode] ?: return@filter false
                val upper = round2(previousClose * (1.0 + limitFor(candle.tsCode)))
                candle.close.toDouble() + LIMIT_UP_EPS >= upper
            }
            .mapTo(linkedSetOf()) { it.tsCode }
    }

    override fun loadCloseQfqWindows(
        tsCodes: List<String>,
        endDateInclusive: LocalDate,
        limitPerStock: Int,
    ): Map<String, List<ProductionOhlcvWindowRow>> =
        StockDailyCandleRepository.findRecentOhlcvWindowsForProduction(tsCodes, endDateInclusive, limitPerStock)

    private companion object {
        /** 浮点容差：避免「贴近涨停」被错误放行。与回测 LimitUpChecker 同口径（1e-6）。 */
        const val LIMIT_UP_EPS = 1e-6

        /** 涨跌幅限制按板块：科创/创业/北交 20%，主板 10%。与回测 LimitUpChecker.limitFor 同源。 */
        fun limitFor(tsCode: String): Double {
            val code = tsCode.substringBefore(".")
            val market = tsCode.substringAfter(".", missingDelimiterValue = "")
            return when {
                market.equals("BJ", ignoreCase = true) -> 0.20
                code.startsWith("688") -> 0.20
                code.startsWith("300") -> 0.20
                code.startsWith("301") -> 0.20
                else -> 0.10
            }
        }

        fun round2(v: Double): Double =
            java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).toDouble()
    }
}
