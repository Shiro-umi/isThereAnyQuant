package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import model.Candle
import org.junit.jupiter.api.Test
import org.shiroumi.backtest.testing.candle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PositionExitManager 单元测试，覆盖四种核心退出条件：
 *  1. 止盈：HIGH 触及入场价 +7%
 *  2. 时间止损：T+3 收盘强制清仓
 *  3. 价格止损：T+2/T+3 收盘 < T 日最低点
 *  4. T+1 禁售：入场日不可卖出
 */
class PositionExitManagerTest {

    // 连续 10 个交易日
    private val tradingDays = (0..9).map { d ->
        LocalDate(2024, 1, d + 2) // 2024-01-02 ~ 2024-01-11
    }
    private val calendar = InMemoryTradingCalendar(tradingDays)

    private val tsCode = "000001.SZ"
    private val defaultConfig = ExitRulesConfig()

    @Test
    fun `止盈 - HIGH 触及入场价 +7% - 生成 LIMIT 退出单`() {
        val entryDate = tradingDays[0]  // T+1
        val t2 = tradingDays[1]         // T+2

        val feed = mapFeed(
            // 入场日（T+1，信号日=T）的行情 → 用于获取信号日最低价
            tradingDays[0] to bar(high = 10.30, low = 9.5, close = 10.20),
            // T+2 触发止盈：HIGH=10.80（触及 10*1.07=10.70）
            t2 to bar(high = 10.80, low = 10.0, close = 10.30),
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)

        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)
        assertEquals(1, exitMgr.trackedCount())

        val exits = exitMgr.checkExits(
            date = t2,
            positions = mapOf(tsCode to position(100)),
        )

        assertEquals(1, exits.size, "应生成一笔退出单")
        val exit = exits[0] as org.shiroumi.backtest.domain.StrategyDecision.TradeIntentDecision
        assertEquals(tsCode, exit.tsCode)
        assertEquals(org.shiroumi.backtest.domain.Side.SELL, exit.side)
        assertEquals(org.shiroumi.backtest.domain.ExecutionHint.LIMIT, exit.hint)
        assertTrue(exit.reason.contains("止盈"), "原因应包含'止盈': ${exit.reason}")
    }

    @Test
    fun `止盈 - HIGH 未触及 +7% - 不生成退出单`() {
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            t2 to bar(high = 10.60, low = 10.0, close = 10.20),  // 仅 +6%
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(
            date = t2,
            positions = mapOf(tsCode to position(100)),
        )
        assertEquals(0, exits.size, "HIGH 未触及止盈价，不应生成退出单")
    }

    @Test
    fun `时间止损 - T+3 收盘未止盈 - 生成 CLOSE 退出单`() {
        val entryDate = tradingDays[0]  // T+1
        val t3 = tradingDays[2]         // T+3

        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            t3 to bar(high = 10.50, low = 10.0, close = 10.20),  // 未触及 +7%
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(
            date = t3,
            positions = mapOf(tsCode to position(100)),
        )

        assertEquals(1, exits.size, "T+3 应生成时间止损单")
        val exit = exits[0] as org.shiroumi.backtest.domain.StrategyDecision.TradeIntentDecision
        assertEquals(org.shiroumi.backtest.domain.ExecutionHint.CLOSE, exit.hint)
        assertTrue(exit.reason.contains("时间止损"), "原因应包含'时间止损': ${exit.reason}")
    }

    @Test
    fun `时间止损 - 止盈优先级高于时间止损`() {
        val entryDate = tradingDays[0]
        val t3 = tradingDays[2]

        // T+3 且 HIGH 同时触及止盈价：止盈优先
        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            t3 to bar(high = 10.80, low = 10.0, close = 10.30),  // HIGH 触及 +8%
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(
            date = t3,
            positions = mapOf(tsCode to position(100)),
        )

        assertEquals(1, exits.size)
        val exit = exits[0] as org.shiroumi.backtest.domain.StrategyDecision.TradeIntentDecision
        assertEquals(org.shiroumi.backtest.domain.ExecutionHint.LIMIT, exit.hint)
        assertTrue(exit.reason.contains("止盈"), "止盈优先级更高: ${exit.reason}")
    }

    @Test
    fun `价格止损 - T+2 收盘低于 T 日最低点 - 生成 CLOSE 退出单`() {
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        val feed = mapFeed(
            // 信号日（T）行情：low = 9.5
            tradingDays[0] to bar(high = 10.30, low = 9.5, close = 10.20),
            // T+2：收盘 9.3 < T 日最低 9.5 → 价格止损
            t2 to bar(high = 10.40, low = 9.2, close = 9.30),
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(
            date = t2,
            positions = mapOf(tsCode to position(100)),
        )

        assertEquals(1, exits.size, "收盘低于 T 日最低应生成价格止损单")
        val exit = exits[0] as org.shiroumi.backtest.domain.StrategyDecision.TradeIntentDecision
        assertEquals(org.shiroumi.backtest.domain.ExecutionHint.CLOSE, exit.hint)
        assertTrue(exit.reason.contains("价格止损"), "原因应包含'价格止损': ${exit.reason}")
    }

    @Test
    fun `价格止损 - T+2 收盘高于 T 日最低 - 不触发`() {
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        val feed = mapFeed(
            tradingDays[0] to bar(high = 10.30, low = 9.5, close = 10.20),
            t2 to bar(high = 10.40, low = 10.0, close = 9.80),  // 9.8 > 9.5
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(
            date = t2,
            positions = mapOf(tsCode to position(100)),
        )
        assertEquals(0, exits.size, "收盘高于 T 日最低，不触发价格止损")
    }

    @Test
    fun `T+1 禁售 - 入场日不生成退出单`() {
        val entryDate = tradingDays[0]

        val feed = mapFeed(
            // 入场日（也是信号日查询用）：HIGH 触及止盈 + 收盘低于最低
            tradingDays[0] to bar(high = 10.80, low = 9.5, close = 9.30),
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(
            date = entryDate,
            positions = mapOf(tsCode to position(100)),
        )
        assertEquals(0, exits.size, "T+1 禁售日不生成任何退出单")
    }

    @Test
    fun `无元数据的持仓不触发退出`() {
        val feed = mapFeed(
            tradingDays[0] to bar(high = 10.80, low = 9.0, close = 9.30),
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        // 不调用 onEntry

        val exits = exitMgr.checkExits(
            date = tradingDays[0],
            positions = mapOf(tsCode to position(100)),
        )
        assertEquals(0, exits.size, "无入场元数据则不生成退出单")
    }

    @Test
    fun `多只股票各自独立判定`() {
        val codeA = "000001.SZ"
        val codeB = "600519.SH"
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        // 用多股票行情
        val feed: BacktestMarketDataFeed = object : BacktestMarketDataFeed {
            override fun marketDataFor(date: LocalDate): DailyMarketData {
                return when (date) {
                    tradingDays[0] -> DailyMarketData(
                        quotes = mapOf(tsCode to candle(tsCode = tsCode, date = date, high = 10.30, low = 9.5)),
                        preClose = emptyMap(),
                    )
                    t2 -> DailyMarketData(
                        quotes = mapOf(
                            codeA to candle(tsCode = codeA, date = date, high = 10.80, low = 10.0, close = 10.30),
                            codeB to candle(tsCode = codeB, date = date, high = 104.0, low = 100.0, close = 103.0),
                        ),
                        preClose = emptyMap(),
                    )
                    else -> DailyMarketData(emptyMap())
                }
            }
        }
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(codeA, entryDate, entryPrice = 10.0)
        exitMgr.onEntry(codeB, entryDate, entryPrice = 100.0)

        val exits = exitMgr.checkExits(
            date = t2,
            positions = mapOf(codeA to position(100, codeA), codeB to position(50, codeB)),
        )

        assertEquals(1, exits.size, "只有 A 触发退出")
        assertEquals(codeA, (exits[0] as org.shiroumi.backtest.domain.StrategyDecision.TradeIntentDecision).tsCode)
    }

    @Test
    fun `onExit 清除元数据后不再跟踪`() {
        val entryDate = tradingDays[0]

        val feed = mapFeed(
            tradingDays[0] to bar(high = 10.30, low = 9.5, close = 10.20),
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)
        assertEquals(1, exitMgr.trackedCount())

        exitMgr.onExit(tsCode)
        assertEquals(0, exitMgr.trackedCount())
    }

    @Test
    fun `时间止损天数可配置 - timeStopDays=5`() {
        val config = ExitRulesConfig(timeStopDays = 5)
        val entryDate = tradingDays[0]
        val t4 = tradingDays[3]  // 第 4 个交易日

        val feed = mapFeed(
            tradingDays[0] to bar(high = 10.30, low = 9.5, close = 10.20),
            t4 to bar(high = 10.50, low = 10.0, close = 10.20),
            tradingDays[4] to bar(high = 10.50, low = 10.0, close = 10.20),
        )

        val exitMgr = PositionExitManager(config, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        // timeStopDays=5, daysSinceEntry=3 → 不触发
        val exitsEarly = exitMgr.checkExits(
            date = t4,
            positions = mapOf(tsCode to position(100)),
        )
        assertEquals(0, exitsEarly.size, "timeStopDays=5 时第 4 天不触发时间止损")

        // daysSinceEntry=4 → 触发
        val exits = exitMgr.checkExits(
            date = tradingDays[4],
            positions = mapOf(tsCode to position(100)),
        )
        assertEquals(1, exits.size, "timeStopDays=5 时第 5 天应触发")
    }

    @Test
    fun `止盈价计算精确到分`() {
        val config = ExitRulesConfig(takeProfitPct = 0.07)
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        // 入场价 10.0, 止盈价 = round2(10.0 * 1.07) = 10.70
        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            t2 to bar(high = 10.70, low = 10.0, close = 10.50),  // 恰好触及 10.70
        )
        val exitMgr = PositionExitManager(config, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(
            date = t2,
            positions = mapOf(tsCode to position(100)),
        )
        assertEquals(1, exits.size, "HIGH 恰好等于止盈价应触发")
    }

    @Test
    fun `禁用价格止损不触发`() {
        val config = ExitRulesConfig(priceStopEnabled = false)
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        val feed = mapFeed(
            tradingDays[0] to bar(high = 10.30, low = 9.5, close = 10.20),
            t2 to bar(high = 10.40, low = 9.2, close = 9.30),  // close < signal low
        )
        val exitMgr = PositionExitManager(config, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(
            date = t2,
            positions = mapOf(tsCode to position(100)),
        )
        assertEquals(0, exits.size, "priceStopEnabled=false 不触发价格止损")
    }

    @Test
    fun `禁用 T+1 禁售 - 入场日即可卖出`() {
        val config = ExitRulesConfig(t1NoSell = false)
        val entryDate = tradingDays[0]

        val feed = mapFeed(
            tradingDays[0] to bar(high = 10.80, low = 9.5, close = 10.30),
        )
        val exitMgr = PositionExitManager(config, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(
            date = entryDate,
            positions = mapOf(tsCode to position(100)),
        )
        assertEquals(1, exits.size, "t1NoSell=false 时入场日可以止盈卖出")
    }

    // ---- 测试辅助 ----

    private fun mapFeed(vararg entries: Pair<LocalDate, DailyMarketData>): BacktestMarketDataFeed {
        val map = entries.toMap()
        return object : BacktestMarketDataFeed {
            override fun marketDataFor(date: LocalDate): DailyMarketData =
                map[date] ?: DailyMarketData(emptyMap())
        }
    }

    /** 单股票单日行情快捷构造 */
    private fun bar(
        tsCode: String = this.tsCode,
        date: LocalDate = LocalDate(2024, 1, 1),
        open: Double = 10.0,
        high: Double = 10.5,
        low: Double = 9.8,
        close: Double = 10.2,
    ): DailyMarketData = DailyMarketData(
        quotes = mapOf(tsCode to candle(
            tsCode = tsCode, date = date, open = open, high = high, low = low, close = close
        )),
        preClose = mapOf(tsCode to 10.0),
    )

    private fun position(qty: Long, code: String = tsCode) =
        org.shiroumi.backtest.domain.StockPosition(
            tsCode = code,
            lot = if (qty > 0) org.shiroumi.backtest.domain.Lot(
                buyDate = tradingDays[0],
                quantity = qty,
                cost = 10.0,
                settled = true,
            ) else null,
        )
}
