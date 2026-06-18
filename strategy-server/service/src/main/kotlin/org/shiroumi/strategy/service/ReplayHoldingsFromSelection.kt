package org.shiroumi.strategy.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import model.Candle
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.strategy.daily.StrategyStateSchemaBootstrap
import org.shiroumi.database.strategy.daily.repository.DailyHoldingState
import org.shiroumi.database.strategy.daily.repository.DailyProfitPredictionSelectionRepository
import org.shiroumi.database.strategy.daily.repository.DailyStrategyHoldingRepository
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.strategy.service.postmarket.HoldingStateMachine
import utils.logger

/**
 * 只重放持仓状态机的区间刷库工具——锁定选股历史、不重新选股，只改入场判定。
 *
 * 与 [RebuildStrategyRange] 的根本区别：本工具 **不跑模型选股、不写 selection 表**，直接读已落库的
 * daily_profit_prediction_selection（含 agent 买点 limit_price），逐日推进 [HoldingStateMachine] 并
 * 写 daily_strategy_holding。对应「保持选股与离场逻辑不变，只把入场点从开盘价无条件建仓换成 agent 买点
 * LIMIT 触达建仓」这一对照口径——selection.limitPrice 经 [HoldingStateMachine.EntryCandidate.entryLimitPrice]
 * 注入，有买点走 LIMIT 触达、缺买点回退开盘价。
 *
 * 推进语义与生产盘后 PostMarketPreparationJob.advanceHoldings 严格一致：
 * - 行情 QFQ 口径（findByTradeDate 原样 Candle，getOpen/getLow 取 *_qfq 列），与买点 limitPrice 同标系。
 * - 入场候选 = 当日 target_date 的 selected 票，entryPriority = model_score（模型分降序取前 maxDailyEntries 只）。
 * - previousHoldings 链：start 前一交易日的库内持仓作链初值；区间内逐日严格串行，先清区间残留旧链。
 * - 持仓规则走 [HoldingStateMachine.ExitRules.fromSystemProperties]，与生产/跟踪展示同一装配入口。
 *
 * 破位加分（生产默认关闭）在本工具不注入，与生产默认口径一致。
 *
 * 评估模式（-Dquant.replay.eval=true）：不写库，逐笔统计交易（建仓→离场，含离场原因），输出交易数/胜率/
 * 平均收益/总复利。配合 -Dquant.replay.ignoreLimit=true 强制忽略 limit_price 走开盘价无条件建仓，作为
 * 「只改入场点」对照组——同选股、同离场规则，唯一变量是入场点（开盘价 vs agent 买点 LIMIT）。
 *
 * 运行（写库刷持仓）: ./gradlew :strategy-server:service:replayHoldingsFromSelection \
 *   -Dquant.replay.start=2026-03-23 -Dquant.replay.end=2026-06-18
 * 运行（评估对照，不写库）: 同上追加 -Dquant.replay.eval=true [-Dquant.replay.ignoreLimit=true]
 */
private val logger by logger("ReplayHoldingsFromSelection")

private data class ClosedTrade(
    val entryDate: LocalDate,
    val exitDate: LocalDate,
    val tsCode: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val reason: String,
    val usedLimit: Boolean,
) {
    val returnPct: Double get() = (exitPrice - entryPrice) / entryPrice * 100.0
}

fun main() = runBlocking {
    Class.forName("com.mysql.cj.jdbc.Driver")
    ConfigManager.load()
    StrategyStateSchemaBootstrap.ensureSchema()

    val start = LocalDate.parse(System.getProperty("quant.replay.start") ?: error("缺少 quant.replay.start"))
    val end = LocalDate.parse(System.getProperty("quant.replay.end") ?: error("缺少 quant.replay.end"))
    require(end >= start) { "无效区间: start=$start end=$end" }
    val evalMode = System.getProperty("quant.replay.eval")?.toBooleanStrictOrNull() ?: false
    val ignoreLimit = System.getProperty("quant.replay.ignoreLimit")?.toBooleanStrictOrNull() ?: false

    val machine = HoldingStateMachine(HoldingStateMachine.ExitRules.fromSystemProperties())
    val tradeDates = TradingCalendarRepository.findOpenDates(start, end)
    require(tradeDates.isNotEmpty()) { "区间内无交易日: $start..$end" }
    logger.info("[replay] start=$start end=$end 交易日=${tradeDates.size} 规则=${machine.rules}")

    // 区间内有序交易日索引：tradingDaysSince 二分定位（含初值持仓回退查库），与 advanceHoldings 一致。
    val indexInRange = tradeDates.withIndex().associate { it.value to it.index }
    val tradingDaysSince: (LocalDate, LocalDate) -> Int = { entryDate, date ->
        if (date <= entryDate) {
            0
        } else {
            val e = indexInRange[entryDate]
            val d = indexInRange[date]
            if (e != null && d != null) (d - e).coerceAtLeast(0)
            else (TradingCalendarRepository.findOpenDates(entryDate, date).size - 1).coerceAtLeast(0)
        }
    }

    // 链初值：start 前一交易日的库内持仓；先读初值再清区间，避免旧 entry 污染链式推进。
    // 边界：start 为交易日历最早日时无前一交易日，链初值为空是正确的（全新推进）；
    // 若 start 非首日却读到空初值，说明前一日数据缺失或区间不连贯，告警便于排查。
    val previousTradeDateOfStart = TradingCalendarRepository.findPreviousTradingDate(start)
    var previousHoldings = previousTradeDateOfStart
        ?.let { DailyStrategyHoldingRepository.findByTradeDate(it) }
        .orEmpty()
    if (previousTradeDateOfStart != null && previousHoldings.isEmpty()) {
        logger.info("[replay] 警告：前一交易日 $previousTradeDateOfStart 持仓为空，链初值为零；若该日本应有持仓，请检查数据完整性")
    }
    if (!evalMode) {
        DailyStrategyHoldingRepository.deleteByDateRange(start, end)
    }
    logger.info(
        "[replay] 链初值持仓=${previousHoldings.size}（来自 $previousTradeDateOfStart）" +
            (if (evalMode) "，评估模式不写库 ignoreLimit=$ignoreLimit" else "，已清区间残留")
    )

    var totalEntered = 0
    var totalExited = 0
    // 评估模式：跟踪在持持仓与建仓是否走 LIMIT，离场时配对成已平仓交易。
    val openTrades = mutableMapOf<String, Pair<DailyHoldingState, Boolean>>()
    previousHoldings.forEach { openTrades[it.tsCode] = it to false }
    val closedTrades = mutableListOf<ClosedTrade>()

    for (tradeDate in tradeDates) {
        val previousTradeDate = TradingCalendarRepository.findPreviousTradingDate(tradeDate)
        val todayCandles: Map<String, Candle> =
            StockDailyCandleRepository.findByTradeDate(tradeDate).associateBy { it.tsCode }
        val signalDayCandleByCode: Map<String, Candle> = previousTradeDate
            ?.let { StockDailyCandleRepository.findByTradeDate(it).associateBy { c -> c.tsCode } }
            .orEmpty()

        val selections = DailyProfitPredictionSelectionRepository.findSelectionsByTargetDate(tradeDate)
        val newEntries = selections.map { selection ->
            val signalBar = signalDayCandleByCode[selection.tsCode]
            HoldingStateMachine.EntryCandidate(
                tsCode = selection.tsCode,
                signalDateLow = signalBar?.getLow()?.toDouble() ?: 0.0,
                signalDateClose = signalBar?.getPrice()?.toDouble() ?: 0.0,
                entryPriority = if (machine.entryCapEnabled) selection.modelScore else 0.0,
                // 对照组（ignoreLimit）强制忽略买点 → 开盘价无条件建仓；实验组注入 agent 买点 LIMIT。
                entryLimitPrice = if (ignoreLimit) null else selection.limitPrice,
            )
        }

        val candleFor: (String, LocalDate) -> Candle? = { tsCode, date ->
            if (date == tradeDate) todayCandles[tsCode] else null
        }

        // 评估模式：advance 前先用 evaluateExit 捕获今日离场票的离场价，配对成已平仓交易。
        if (evalMode) {
            for (holding in previousHoldings) {
                val bar = todayCandles[holding.tsCode] ?: continue
                val verdict = machine.evaluateExit(holding, tradeDate, bar, tradingDaysSince) ?: continue
                val open = openTrades.remove(holding.tsCode) ?: (holding to false)
                closedTrades += ClosedTrade(
                    entryDate = holding.entryDate,
                    exitDate = tradeDate,
                    tsCode = holding.tsCode,
                    entryPrice = holding.entryPrice,
                    exitPrice = verdict.exitPrice,
                    reason = verdict.reason.name,
                    usedLimit = open.second,
                )
            }
        }

        val result = machine.advance(
            tradeDate = tradeDate,
            previousHoldings = previousHoldings,
            newEntries = newEntries,
            tradingDaysSince = tradingDaysSince,
            candleFor = candleFor,
        )

        if (evalMode) {
            // 记录新建仓是否走了 LIMIT（用于交易明细标注）。
            val limitByCode = selections.associate { it.tsCode to it.limitPrice }
            result.holdings.filter { it.entryDate == tradeDate }.forEach { h ->
                val usedLimit = !ignoreLimit && limitByCode[h.tsCode] != null
                openTrades[h.tsCode] = h to usedLimit
            }
        } else {
            DailyStrategyHoldingRepository.replaceForDate(tradeDate, result.holdings)
        }
        previousHoldings = result.holdings
        totalEntered += result.entered.size
        totalExited += result.exited.size
    }

    logger.info("[replay] finished 建仓=$totalEntered 清仓=$totalExited")
    if (evalMode) {
        reportEval(closedTrades, start, end, ignoreLimit)
    } else {
        println("[replay] start=$start end=$end days=${tradeDates.size} entered=$totalEntered exited=$totalExited")
    }
}

/** 评估输出：交易数、胜率、平均单笔收益、总复利、离场原因分布。 */
private fun reportEval(trades: List<ClosedTrade>, start: LocalDate, end: LocalDate, ignoreLimit: Boolean) {
    val n = trades.size
    val label = if (ignoreLimit) "对照组(开盘价无条件建仓)" else "实验组(agent买点LIMIT)"
    if (n == 0) {
        println("[eval] $label start=$start end=$end 无已平仓交易")
        return
    }
    val wins = trades.count { it.returnPct > 0.0 }
    val avg = trades.sumOf { it.returnPct } / n
    var equity = 1.0
    trades.sortedBy { it.exitDate }.forEach { equity *= (1.0 + it.returnPct / 100.0) }
    val totalReturn = (equity - 1.0) * 100.0
    val byReason = trades.groupingBy { it.reason }.eachCount()
    println("[eval] ===== $label =====")
    println("[eval] 区间 $start ~ $end")
    println("[eval] 交易数(已平仓) = $n")
    println("[eval] 胜率 = ${"%.2f".format(wins * 100.0 / n)}%  ($wins/$n)")
    println("[eval] 平均单笔收益 = ${"%.4f".format(avg)}%")
    println("[eval] 单笔串行全仓复利(失真上界,仅供参考) = ${"%.2f".format(totalReturn)}%")
    println("[eval] 离场原因分布 = $byReason")
    // 导出每笔交易供组合口径(每日等权 Top3、空仓持现)复利核算，避免串行全仓复利失真。
    val csv = System.getProperty("quant.replay.csv")
    if (csv != null) {
        java.io.File(csv).bufferedWriter().use { w ->
            w.write("entry_date,exit_date,ts_code,entry_price,exit_price,return_pct,reason,used_limit\n")
            trades.sortedWith(compareBy({ it.entryDate }, { it.tsCode })).forEach { t ->
                w.write(
                    "${t.entryDate},${t.exitDate},${t.tsCode},${t.entryPrice},${t.exitPrice}," +
                        "${"%.6f".format(t.returnPct)},${t.reason},${t.usedLimit}\n"
                )
            }
        }
        println("[eval] 交易明细已写 $csv")
    }
}
