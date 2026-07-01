package org.shiroumi.strategy.service.model

import kotlinx.datetime.LocalDate
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.ProductionOhlcvWindowRow
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.daily.TargetPosition
import utils.logger

/**
 * 纯 EMA20 斜率选股器：替代 [ProfitPredictionModelSelector] 的生产选股口径。
 *
 * 业务背景：实盘交易路径把盘后选股从 v5 盈利预测模型 Top-N 换成「EMA20 斜率最大的 Top-N」趋势池。
 * 口径用户拍板（2026-06-29）：
 *  - 候选池：主板 + 创业板活跃股（由 [PostMarketPreparationJob] 传入的 universeSymbols，即
 *    [org.shiroumi.strategy.service.universe.MainBoardUniverseProvider] 产出，已排除 ST/退市/科创/北交）。
 *  - 选股因子：EMA20(span=20) 斜率 = (ema[T] - ema[T-1]) / ema[T-1]，前复权 close_qfq。
 *    比值口径对复权因子平移免疫，且严格只用信号日 T 及之前的序列，杜绝未来函数。
 *  - 涨停过滤：信号日 T 收盘已涨停的票直接剔除（涨停板次日开盘买不进 / 要追高）。
 *    判定口径 = 收盘 >= round2(前收 × (1+涨跌幅限制))，与回测 LimitUpChecker 同源。
 *  - 选 Top5 等权：斜率降序取前 [topN]（默认 5），各 1/N 仓。
 *  - selectionScore = EMA20 斜率：下游 advanceHoldings 按 entryPriority 降序建仓，
 *    填斜率即「斜率最高的若干只优先入场」，与生产 entry-cap 口径一致，下游零改动。
 *
 * 边界纪律：
 *  - 实现 [ProfitPredictionTargetSelector] 接口，与 [ProfitPredictionModelSelector] 同契约，
 *    可在 [PostMarketPreparationJob] 装配点一键替换 / 回滚。
 *  - 零 DB 直连：取数走已有 [StockDailyCandleRepository]，涨停前收走 [TradingCalendarRepository]
 *    + 同 Repository。判定逻辑全部可注入桩，可单测。
 *  - 返回 [TargetPosition] 字段口径与 [ProfitPredictionModelSelector] 完全一致（selected 票
 *    targetWeight=1/N、未选票 weight=0、sentimentExposure 透传），使 SelectionDriftGuard / 写库 /
 *    审计链路无差别消费。
 *
 * 防未来函数（与 [Ema20PoolDecisionFeed] / 回测脚本严格一致）：
 *  - tradeDate 是信号日 T（盘后选股发生日）。EMA20 序列只取 endDateInclusive=tradeDate 的窗口，
 *    斜率用窗口末两点（T 与 T-1），不触碰 T+1 及之后任何信息。
 *  - 涨停判定用 T 日收盘 vs T 的前一交易日收盘，同样不越过 T。
 */
internal class Ema20SlopeTargetSelector(
    private val topN: Int = System.getProperty("quant.ema20Selection.topN", "5").toInt(),
    private val emaSpan: Int = System.getProperty("quant.ema20Selection.emaSpan", "20").toInt(),
    // EMA20 收敛需要足够预热；窗口末两点算斜率需 emaSpan+1 行起步，再留余量。
    private val windowSize: Int = System.getProperty("quant.ema20Selection.windowSize", "60").toInt(),
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

        // 1) 信号日 T 收盘涨停集合（剔除用）。前收来自 T 的前一交易日。
        val limitUpSymbols = dataSource.loadLimitUpSymbols(tradeDate)

        // 2) 候选池窗口前复权 close_qfq（末 windowSize 行，endDateInclusive=tradeDate，防未来函数）。
        val windows = dataSource.loadCloseQfqWindows(
            tsCodes = universeSymbols,
            endDateInclusive = tradeDate,
            limitPerStock = windowSize,
        )

        // 3) 逐票算 EMA20 斜率。剔除：窗口末行不是 T（停牌/数据缺）、预热不足、信号日涨停。
        data class Scored(val tsCode: String, val slope: Double)
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
            if (usable.size < emaSpan + 1 || usable.last().tradeDate != tradeDate) {
                droppedInsufficient++
                continue
            }
            val closes = DoubleArray(usable.size) { usable[it].closeQfq }
            if (closes.any { it <= 0.0 }) {
                droppedInsufficient++
                continue
            }
            val ema = ema(closes, emaSpan)
            val prev = ema[ema.size - 2]
            if (prev <= 0.0) {
                droppedInsufficient++
                continue
            }
            val slope = (ema[ema.size - 1] - prev) / prev
            scored += Scored(tsCode, slope)
        }

        // 4) 斜率降序取 Top N，等权 1/N。同分按 tsCode 升序保证确定性。
        val ranked = scored.sortedWith(
            compareByDescending<Scored> { it.slope }.thenBy { it.tsCode }
        )
        val selectedCodes = ranked.asSequence().take(topN).map { it.tsCode }.toSet()
        val weightPerStock = if (selectedCodes.isEmpty()) 0.0 else 1.0 / selectedCodes.size
        val exposure = sentiment.sentimentExposure

        logger.info(
            "[EMA20选股] tradeDate=$tradeDate universe=${universeSymbols.size} " +
                "limitUpDropped=$droppedLimitUp insufficientDropped=$droppedInsufficient " +
                "scored=${ranked.size} selected=${selectedCodes.size} topN=$topN exposure=$exposure"
        )

        // 5) 产出与 ProfitPredictionModelSelector 同构的 TargetPosition 列表。
        return ranked.map { s ->
            val selected = s.tsCode in selectedCodes
            TargetPosition(
                tradeDate = tradeDate,
                targetDate = targetDate,
                tsCode = s.tsCode,
                selectionScore = s.slope,
                selected = selected,
                targetWeight = if (selected) weightPerStock else 0.0,
                sentimentExposure = exposure,
                selectionReason = "ema20-slope:${if (selected) "Top$topN" else "candidate"} " +
                    "slope=${formatSlope(s.slope)}",
            )
        }
    }

    private companion object {
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

        /** JS 平台不兼容 String.format，手动保留 6 位小数（与项目规范一致）。 */
        fun formatSlope(v: Double): String {
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
