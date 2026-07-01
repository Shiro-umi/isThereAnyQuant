package org.shiroumi.strategy.service.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import model.Candle
import model.candle.StrategyPositionTrackingResponse
import model.candle.StrategyTrackingEdgeKind
import model.candle.StrategyTrackingExitReason
import model.candle.StrategyTrackingSection
import model.ws.PositionSource
import model.ws.StrategySelectionSnapshot
import model.ws.StrategyPositionSnapshot
import org.shiroumi.database.strategy.daily.repository.DailyHoldingState
import org.shiroumi.database.strategy.daily.repository.ProfitPredictionSelection
import org.shiroumi.strategy.client.LocalStrategySnapshotHub
import org.shiroumi.strategy.contract.StrategyTopic
import org.shiroumi.strategy.core.audit.StrategyAuditSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class StrategyPositionTrackingRuntimeTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val day1 = LocalDate(2026, 4, 29)
    private val day2 = LocalDate(2026, 4, 30)

    @Test
    fun `publishes service position tracking snapshot from positions update`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val runtime = StrategyPositionTrackingRuntime(
            snapshotHub = hub,
            json = json,
            // 钉住 v5 快线旧运营点预设：本测试验证“服务端按规则重建判决”的机制，断言围绕 TP7%/H5 语义编写
            exitRules = org.shiroumi.strategy.service.postmarket.HoldingStateMachine.ExitRules.V5_FAST_TP7_H5,
            dataSource = FakeTrackingDataSource(
                audits = listOf(
                    audit(day2, currentPositions = listOf("000002.SZ")),
                    audit(day1, currentPositions = listOf("000001.SZ"))
                ),
                selectionsByTradeDate = mapOf(
                    day1 to listOf(selection(day1, "000002.SZ", 0.7)),
                    day2 to listOf(selection(day2, "000003.SZ", 0.93))
                ),
                holdingsByTradeDate = mapOf(
                    day1 to listOf(
                        holding(day1, "000001.SZ", entryDate = day1),
                        holding(day1, "000005.SZ", entryDate = day1),
                    ),
                    day2 to listOf(
                        holding(day2, "000002.SZ", entryDate = day2),
                        holding(day2, "000005.SZ", entryDate = day1),
                    )
                ),
                names = mapOf(
                    "000001.SZ" to "A",
                    "000002.SZ" to "B",
                    "000003.SZ" to "C",
                    "000004.SZ" to "D",
                    "000005.SZ" to "E"
                ),
                candles = mapOf(
                    "000001.SZ" to mapOf(
                        day1 to candle("000001.SZ", day1, open = 10f, close = 11f),
                        // 高点 10.8 触及止盈价 10×1.07=10.7，收盘回落 10.2：
                        // 规则口径已实现收益应为 +7%（触价），而非收盘口径的 +2%
                        day2 to candle("000001.SZ", day2, open = 10.1f, close = 10.2f, high = 10.8f)
                    ),
                    "000002.SZ" to mapOf(day2 to candle("000002.SZ", day2, open = 20f, close = 22f)),
                    // 跨两日持有：HOLD_CONTINUE 边盈亏 = 目标日当日涨跌 (51-50)/50 = +2%
                    "000005.SZ" to mapOf(
                        day1 to candle("000005.SZ", day1, open = 49f, close = 50f),
                        day2 to candle("000005.SZ", day2, open = 50f, close = 51f)
                    )
                ),
                tradeDates = listOf(day1, day2)
            )
        )

        val envelope = runtime.publishFromPositions(
            StrategyPositionSnapshot(
                tradeDate = day2.toString(),
                currentPositions = listOf("000002.SZ"),
                source = PositionSource.DAILY_AUDIT_COMPLETE,
                nextSessionSelections = listOf("000003.SZ"),
                nextSessionSelectionDetails = listOf(StrategySelectionSnapshot("000003.SZ", 0.93)),
                newlySelected = listOf("000003.SZ")
            )
        )

        assertNotNull(envelope)
        val stored = assertNotNull(hub.current(StrategyTopic.POSITION_TRACKING))
        val payload = json.decodeFromJsonElement(
            StrategyPositionTrackingResponse.serializer(),
            stored.payload
        )
        assertEquals(listOf("2026-04-29", "2026-04-30"), payload.days.map { it.tradeDate })
        assertEquals(listOf("000003.SZ"), payload.days.last().selection.map { it.stockCode })
        assertEquals(listOf(0.93), payload.days.last().selection.map { it.modelScore })
        assertEquals(setOf("000002.SZ", "000005.SZ"), payload.days.last().holdings.map { it.stockCode }.toSet())
        assertEquals(StrategyTopic.POSITION_TRACKING, envelope.topic)

        // 持仓浮盈基准 = 真实成交价 entry_price，非买入日开盘价（回归防护）。
        // 000005.SZ：entry_price=10（fixture 刻意 ≠ 买入日 day1 开盘 49），day2 收盘 51
        //   正确 actualPnl = (51-10)/10 = +410%；若退回 getOpen(49) 基准会算成 (51-49)/49≈+4.08%。
        //   两值天差地别，锁死"盈亏用开盘价当成本"的 bug 不再回归。
        val holdingNode5 = assertNotNull(
            payload.days.last().holdings.singleOrNull { it.stockCode == "000005.SZ" }
        )
        assertEquals(10.0f, assertNotNull(holdingNode5.entryPrice), absoluteTolerance = 0.001f)
        assertEquals(410.0f, assertNotNull(holdingNode5.actualPnl), absoluteTolerance = 0.01f)

        // 清仓节点：服务端按生产规则重建离场判决——止盈触价 +7%，而非收盘口径 +2%
        val clearedNode = assertNotNull(payload.days.last().cleared.singleOrNull())
        assertEquals("000001.SZ", clearedNode.stockCode)
        assertEquals(StrategyTrackingExitReason.TAKE_PROFIT, clearedNode.exitReason)
        assertEquals(7.0f, assertNotNull(clearedNode.exitPnl), absoluteTolerance = 0.01f)
        assertEquals(2.0f, assertNotNull(clearedNode.actualPnl), absoluteTolerance = 0.01f)

        // 流转边由服务端计算：清仓支路带规则口径收益与原因，入场支路带入场日开→收涨幅
        val exitEdge = assertNotNull(
            payload.edges.singleOrNull { it.kind == StrategyTrackingEdgeKind.EXIT_CLEAR }
        )
        assertEquals("000001.SZ", exitEdge.toStockCode)
        assertEquals(StrategyTrackingExitReason.TAKE_PROFIT, exitEdge.exitReason)
        assertEquals(7.0f, assertNotNull(exitEdge.pnlPct), absoluteTolerance = 0.01f)
        val enterEdge = assertNotNull(
            payload.edges.singleOrNull { it.kind == StrategyTrackingEdgeKind.ENTER_HOLDING }
        )
        assertEquals("000002.SZ", enterEdge.toStockCode)
        assertEquals(StrategyTrackingSection.SELECTION, enterEdge.fromSection)
        assertEquals(10.0f, assertNotNull(enterEdge.pnlPct), absoluteTolerance = 0.01f)
        // 持有主干边：跨两日持有的票，盈亏 = 目标日当日涨跌 (51-50)/50 = +2%
        val holdEdge = assertNotNull(
            payload.edges.singleOrNull { it.kind == StrategyTrackingEdgeKind.HOLD_CONTINUE }
        )
        assertEquals("000005.SZ", holdEdge.toStockCode)
        assertEquals(StrategyTrackingSection.HOLDINGS, holdEdge.fromSection)
        assertEquals(2.0f, assertNotNull(holdEdge.pnlPct), absoluteTolerance = 0.01f)

        // 下一卖点（最新观察日持仓）：000005.SZ 成本 = 真实成交价 entry_price=10（非买入日开盘 49），
        // TP7% → 止盈价 10×1.07=10.7；V5 预设含 1~4 档 2.5% 阶梯，daysSinceEntry=1 命中保盈价 10×1.025=10.25；
        // 时间止损剩余 = timeStopDays(5) - 1 - daysSinceEntry(1) = 3 个交易日。
        // 注：本 fixture 刻意让 entry_price(10) ≠ 买入日开盘(49)，验证盈亏/卖点基准用真实成交价而非开盘价。
        val holdingNext = assertNotNull(
            payload.days.last().holdings.singleOrNull { it.stockCode == "000005.SZ" }?.nextExit
        )
        assertEquals(10.7f, holdingNext.takeProfitPrice, absoluteTolerance = 0.01f)
        assertEquals(10.25f, assertNotNull(holdingNext.profitProtectPrice), absoluteTolerance = 0.01f)
        assertEquals(3, holdingNext.timeStopInTradingDays)

        // 盘中实时快照不再覆盖跟踪页：INTRADAY_REALTIME 来源返回 null，跟踪快照保持盘后确认结果不变。
        val intradayEnvelope = runtime.publishFromPositions(
            StrategyPositionSnapshot(
                tradeDate = day2.toString(),
                currentPositions = listOf("000002.SZ"),
                source = PositionSource.INTRADAY_REALTIME,
                nextSessionSelections = listOf("000004.SZ"),
                nextSessionSelectionDetails = listOf(StrategySelectionSnapshot("000004.SZ", 0.97)),
                newlySelected = listOf("000004.SZ")
            )
        )
        assertEquals(null, intradayEnvelope)

        val afterIntraday = json.decodeFromJsonElement(
            StrategyPositionTrackingResponse.serializer(),
            assertNotNull(hub.current(StrategyTopic.POSITION_TRACKING)).payload
        )
        // 选股列仍为盘后确认的 000003.SZ，未被盘中投影的 000004.SZ 覆盖
        assertEquals(listOf("000003.SZ"), afterIntraday.days.last().selection.map { it.stockCode })
        assertEquals(listOf("2026-04-29", "2026-04-30"), afterIntraday.days.map { it.tradeDate })
    }

    private fun audit(
        tradeDate: LocalDate,
        currentPositions: List<String>
    ) = StrategyAuditSummary(
        tradeDate = tradeDate,
        universeSize = 10,
        signalPositiveCount = 3,
        selectedCount = currentPositions.size,
        emptyReason = null,
        newlySelected = emptyList(),
        dropped = emptyList(),
        currentPositions = currentPositions,
        sentimentExposure = 1.0,
        bullRatio = 0.5,
        marketVol = 0.01,
        fftScore = 0.5,
        residualScore = 0.5,
        accelZ = 0.0,
        volZ = 0.0,
        ratioNorm = 0.5,
        volScore = 0.5,
        accelScore = 0.5,
        absoluteFloor = 0.256,
        volCap = 1.0
    )

    @Test
    fun `最早跟随日校准_空仓起步重放生产入场规则_模型已有持仓不影响跟随者买入`() = runTest {
        val hub = LocalStrategySnapshotHub<kotlinx.serialization.json.JsonElement>("test-service")
        val runtime = StrategyPositionTrackingRuntime(
            snapshotHub = hub,
            json = json,
            exitRules = org.shiroumi.strategy.service.postmarket.HoldingStateMachine.ExitRules.V5_FAST_TP7_H5,
            dataSource = FakeTrackingDataSource(
                audits = listOf(
                    audit(day2, currentPositions = listOf("000010.SZ")),
                    audit(day1, currentPositions = listOf("000010.SZ"))
                ),
                // 信号日 day1 选出三只、target_date=day2，入场优先级 = 模型分降序（0.9 > 0.8 > 0.7）：
                // 000011 模型分最高且不跳空 → 入场；000012 跳空 5% 超限（不占名额）；
                // 000013 模型分最低被每日入场上限 1（V5_FAST_TP7_H5）挡住
                selectionsByTradeDate = mapOf(
                    day1 to listOf(
                        selection(day1, "000011.SZ", 0.9, targetDate = day2),
                        selection(day1, "000012.SZ", 0.8, targetDate = day2),
                        selection(day1, "000013.SZ", 0.7, targetDate = day2),
                    )
                ),
                // 模型自身书本：000010 持仓贯穿两日（跟随者书本为空，不应出现在校准流中）
                holdingsByTradeDate = mapOf(
                    day1 to listOf(holding(day1, "000010.SZ", entryDate = day1)),
                    day2 to listOf(holding(day2, "000010.SZ", entryDate = day1)),
                ),
                names = mapOf(
                    "000010.SZ" to "模",
                    "000011.SZ" to "甲",
                    "000012.SZ" to "乙",
                    "000013.SZ" to "丙"
                ),
                candles = mapOf(
                    "000011.SZ" to mapOf(
                        day1 to candle("000011.SZ", day1, open = 9.8f, close = 10f),
                        day2 to candle("000011.SZ", day2, open = 10.2f, close = 10.4f)
                    ),
                    "000012.SZ" to mapOf(
                        day1 to candle("000012.SZ", day1, open = 19.5f, close = 20f),
                        day2 to candle("000012.SZ", day2, open = 21f, close = 21.5f)
                    ),
                    "000013.SZ" to mapOf(
                        day1 to candle("000013.SZ", day1, open = 29.5f, close = 30f),
                        day2 to candle("000013.SZ", day2, open = 30.3f, close = 30.9f)
                    ),
                ),
                tradeDates = listOf(day1, day2),
            )
        )

        val calibrated = assertNotNull(runtime.buildCalibratedSnapshot(day2))
        assertEquals("2026-04-30", calibrated.followStartDate)
        assertEquals(listOf("2026-04-29", "2026-04-30"), calibrated.days.map { it.tradeDate })

        // 跟随起点前书本为空；选股列仍展示模型选股（公共信息）
        assertEquals(emptyList(), calibrated.days.first().holdings)
        assertEquals(
            setOf("000011.SZ", "000012.SZ", "000013.SZ"),
            calibrated.days.first().selection.map { it.stockCode }.toSet()
        )

        // 跟随日入场：跳空超限候选不占名额，按模型分降序入场 000011，每日上限 1 挡住 000013；
        // 模型自身持仓 000010 不出现在跟随者书本
        val followDayHoldings = calibrated.days.last().holdings
        assertEquals(listOf("000011.SZ"), followDayHoldings.map { it.stockCode })
        val entered = followDayHoldings.single()
        assertEquals("2026-04-30", entered.buyDate)
        assertEquals(10.2f, assertNotNull(entered.buyPrice), absoluteTolerance = 0.001f)
        // 入场日盈亏 = 开盘 10.2 → 收盘 10.4
        assertEquals(1.96f, assertNotNull(entered.actualPnl), absoluteTolerance = 0.01f)
        assertEquals(emptyList(), calibrated.days.last().cleared)

        // 入场支路：前一交易日选股节点 → 跟随日新进持仓
        val enterEdge = assertNotNull(
            calibrated.edges.singleOrNull { it.kind == StrategyTrackingEdgeKind.ENTER_HOLDING }
        )
        assertEquals("000011.SZ", enterEdge.toStockCode)
        assertEquals(StrategyTrackingSection.SELECTION, enterEdge.fromSection)
        assertEquals(1.96f, assertNotNull(enterEdge.pnlPct), absoluteTolerance = 0.01f)

        // 非窗口内交易日 → 拒绝校准
        assertEquals(null, runtime.buildCalibratedSnapshot(LocalDate(2026, 5, 6)))
    }

    private fun holding(tradeDate: LocalDate, tsCode: String, entryDate: LocalDate) =
        DailyHoldingState(
            tradeDate = tradeDate,
            tsCode = tsCode,
            entryDate = entryDate,
            entryPrice = 10.0,
            signalDateLow = 9.0,
        )

    private fun selection(
        tradeDate: LocalDate,
        tsCode: String,
        modelScore: Double,
        targetDate: LocalDate = tradeDate,
    ) =
        ProfitPredictionSelection(
            tradeDate = tradeDate,
            targetDate = targetDate,
            tsCode = tsCode,
            modelScore = modelScore,
            selected = true,
            targetWeight = 0.2,
            sentimentExposure = 1.0,
            selectionReason = null,
            modelId = "test-model",
            candidateMode = "all-universe"
        )

    private fun candle(tsCode: String, date: LocalDate, open: Float, close: Float, high: Float? = null) =
        Candle(
            tsCode = tsCode,
            date = date,
            open = open,
            high = high ?: maxOf(open, close),
            low = minOf(open, close),
            close = close,
            adj = 1f,
            volume = 100f,
            turnoverReal = 0f,
            pe = 0f,
            peTtm = 0f,
            pb = 0f,
            ps = 0f,
            psTtm = 0f,
            mvTotal = 0f,
            mvCirc = 0f
        )
}

private class FakeTrackingDataSource(
    private val audits: List<StrategyAuditSummary>,
    private val selectionsByTradeDate: Map<LocalDate, List<ProfitPredictionSelection>>,
    private val holdingsByTradeDate: Map<LocalDate, List<DailyHoldingState>>,
    private val names: Map<String, String>,
    private val candles: Map<String, Map<LocalDate, Candle>>,
    private val tradeDates: List<LocalDate> = emptyList(),
) : StrategyPositionTrackingDataSource {
    override fun loadAuditSummaries(limit: Int): List<StrategyAuditSummary> = audits.take(limit)

    override fun loadSelectionsByTradeDate(tradeDates: List<LocalDate>): Map<LocalDate, List<ProfitPredictionSelection>> =
        selectionsByTradeDate.filterKeys { it in tradeDates }

    override fun loadHoldingsByTradeDate(tradeDates: List<LocalDate>): Map<LocalDate, List<DailyHoldingState>> =
        holdingsByTradeDate.filterKeys { it in tradeDates }

    override fun loadStockNames(tsCodes: Collection<String>): Map<String, String> =
        names.filterKeys { it in tsCodes }

    override fun loadCandles(
        tsCodes: List<String>,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<String, Map<LocalDate, Candle>> =
        candles.filterKeys { it in tsCodes }

    override fun tradingDaysSince(entryDate: LocalDate, date: LocalDate): Int =
        tradeDates.count { it > entryDate && it <= date }

    override fun tradingDayAfter(date: LocalDate, tradingDays: Int): LocalDate? {
        if (tradingDays <= 0) return date
        val ordered = tradeDates.filter { it >= date }.sorted()
        return ordered.getOrNull(tradingDays)
    }

    override fun loadSelectionsByTargetDate(targetDate: LocalDate): List<ProfitPredictionSelection> =
        selectionsByTradeDate.values.flatten().filter { it.targetDate == targetDate }
}
