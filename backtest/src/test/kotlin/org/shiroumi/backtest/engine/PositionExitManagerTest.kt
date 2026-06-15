package org.shiroumi.backtest.engine

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import org.shiroumi.backtest.testing.candle
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PositionExitManager 单元测试，覆盖 tp8/u25/H3 生产运营点五级退出优先级：
 *  1. 止盈：HIGH 触及入场价 ×1.08 → max(开盘, 触发价)
 *  2. 保盈阶梯：1~2 日 HIGH 触及入场价 ×1.025 → max(开盘, 触发价)
 *  3. 浅浮亏止损：到期前收盘跌破入场价 ×0.97 → 收盘离场
 *  4. 时间止损：H3 第 3 个交易日收盘强平
 *  5. 价格止损：默认关闭（仅 priceStopEnabled=true 时生效）
 * 另含高开穿越价口径 + T+1 禁售。
 */
class PositionExitManagerTest {

    // 连续 10 个交易日
    private val tradingDays = (0..9).map { d ->
        LocalDate(2024, 1, d + 2) // 2024-01-02 ~ 2024-01-11
    }
    private val calendar = InMemoryTradingCalendar(tradingDays)

    private val tsCode = "000001.SZ"
    private val defaultConfig = ExitRulesConfig() // tp8/u25/H3 默认

    // ---- 止盈 ----

    @Test
    fun `止盈 - HIGH 触及入场价 +8% - 生成 LIMIT 退出单且离场价取触发价`() {
        val entryDate = tradingDays[0]  // 入场日
        val t2 = tradingDays[1]         // 第 1 个可卖日

        val feed = mapFeed(
            tradingDays[0] to bar(high = 10.30, low = 9.5, close = 10.20),
            // 第 1 可卖日触发止盈：HIGH=10.90 >= 10*1.08=10.80，开盘 10.20 < 触发价 → 离场价 = 10.80
            t2 to bar(open = 10.20, high = 10.90, low = 10.0, close = 10.30),
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)
        assertEquals(1, exitMgr.trackedCount())

        val exits = exitMgr.checkExits(date = t2, positions = mapOf(tsCode to position(100)))

        assertEquals(1, exits.size, "应生成一笔退出单")
        val exit = exits[0]
        assertEquals(tsCode, exit.tsCode)
        assertEquals(org.shiroumi.backtest.domain.Side.SELL, exit.side)
        assertEquals(org.shiroumi.backtest.domain.ExecutionHint.LIMIT, exit.hint)
        assertTrue(exit.reason.contains("止盈"), "原因应包含'止盈': ${exit.reason}")
        assertEquals(10.80, exit.limitPrice!!, 1e-9, "未高开穿越，离场价 = 止盈触发价 10.80")
    }

    @Test
    fun `止盈 - 高开穿越止盈价 - 离场价取开盘价`() {
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        val feed = mapFeed(
            tradingDays[0] to bar(high = 10.30, low = 9.5, close = 10.20),
            // 开盘 11.00 已高于止盈价 10.80 → 离场价 = max(开盘 11.00, 触发价 10.80) = 11.00
            t2 to bar(open = 11.00, high = 11.50, low = 10.90, close = 11.20),
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = t2, positions = mapOf(tsCode to position(100)))
        assertEquals(1, exits.size)
        val exit = exits[0]
        assertTrue(exit.reason.contains("止盈"))
        assertEquals(11.00, exit.limitPrice!!, 1e-9, "高开穿越，离场价 = 开盘价 11.00")
    }

    @Test
    fun `止盈 - HIGH 未触及 +8% 但触及保盈阶梯 - 走保盈阶梯`() {
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            t2 to bar(open = 10.0, high = 10.60, low = 10.0, close = 10.20),  // 仅 +6%，止盈未触发
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = t2, positions = mapOf(tsCode to position(100)))
        // HIGH 10.60 >= 10*1.025=10.25 → 保盈阶梯触发
        assertEquals(1, exits.size, "HIGH 触及保盈阶梯应生成退出单")
        val exit = exits[0]
        assertTrue(exit.reason.contains("保盈阶梯"), "原因应为保盈阶梯: ${exit.reason}")
    }

    // ---- 保盈阶梯 ----

    @Test
    fun `保盈阶梯 - 第1可卖日 HIGH 触及入场价 +2点5% - LIMIT 退出且离场价取触发价`() {
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]   // daysSinceEntry = 1

        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            // 开盘 10.10 < 触发价 10.25，HIGH=10.40 >= 10.25 → 离场价 = 10.25
            t2 to bar(open = 10.10, high = 10.40, low = 10.0, close = 10.30),
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = t2, positions = mapOf(tsCode to position(100)))
        assertEquals(1, exits.size)
        val exit = exits[0]
        assertEquals(org.shiroumi.backtest.domain.ExecutionHint.LIMIT, exit.hint)
        assertTrue(exit.reason.contains("保盈阶梯"))
        assertEquals(10.25, exit.limitPrice!!, 1e-9, "离场价 = 保盈触发价 10.25")
    }

    @Test
    fun `保盈阶梯 - 非阶梯日不触发`() {
        // H3 周期内阶梯仅覆盖 1~2 日；这里把 timeStopDays 调大避开时间止损干扰，验证第 3 日无阶梯
        val config = ExitRulesConfig(timeStopDays = 6) // 阶梯仍只有 1,2
        val entryDate = tradingDays[0]
        val t4 = tradingDays[3]   // daysSinceEntry = 3，不在阶梯表

        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            t4 to bar(open = 10.0, high = 10.40, low = 9.9, close = 10.10), // HIGH 触及 2.5% 但非阶梯日
        )
        val exitMgr = PositionExitManager(config, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = t4, positions = mapOf(tsCode to position(100)))
        assertEquals(0, exits.size, "daysSinceEntry=3 不在阶梯表，不应触发保盈阶梯")
    }

    // ---- 浅浮亏止损 ----

    @Test
    fun `浅浮亏止损 - 到期前收盘跌破入场价 -3% - CLOSE 退出`() {
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]   // daysSinceEntry=1 < timeStopDays-1=2 → 到期前

        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            // 收盘 9.60 < 入场价 ×0.97 = 9.70 → 浅止损；HIGH 10.10 未触及止盈/阶梯
            t2 to bar(open = 9.90, high = 10.10, low = 9.50, close = 9.60),
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = t2, positions = mapOf(tsCode to position(100)))
        assertEquals(1, exits.size)
        val exit = exits[0]
        assertEquals(org.shiroumi.backtest.domain.ExecutionHint.CLOSE, exit.hint)
        assertTrue(exit.reason.contains("浅浮亏止损"), "原因应为浅浮亏止损: ${exit.reason}")
    }

    @Test
    fun `浅浮亏止损 - 收盘未跌破 -3% 不触发`() {
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            t2 to bar(open = 9.90, high = 10.10, low = 9.60, close = 9.80), // 9.80 > 9.70
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = t2, positions = mapOf(tsCode to position(100)))
        assertEquals(0, exits.size, "收盘高于浅止损线，不触发")
    }

    @Test
    fun `浅浮亏止损 - 关闭时不触发`() {
        val config = ExitRulesConfig(shallowStopLossPct = 0.0)
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            t2 to bar(open = 9.90, high = 10.10, low = 9.40, close = 9.50), // 远低于 9.70
        )
        val exitMgr = PositionExitManager(config, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = t2, positions = mapOf(tsCode to position(100)))
        assertEquals(0, exits.size, "shallowStopLossPct=0 关闭浅止损")
    }

    // ---- 时间止损 ----

    @Test
    fun `时间止损 - H3 第3个交易日收盘未止盈 - 生成 CLOSE 退出单`() {
        val entryDate = tradingDays[0]
        val t3 = tradingDays[2]   // daysSinceEntry = 2 = timeStopDays-1

        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            t3 to bar(open = 10.0, high = 10.20, low = 9.9, close = 10.10),  // 未触及 +8% / 阶梯
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = t3, positions = mapOf(tsCode to position(100)))
        assertEquals(1, exits.size, "H3 第 3 日应生成时间止损单")
        val exit = exits[0]
        assertEquals(org.shiroumi.backtest.domain.ExecutionHint.CLOSE, exit.hint)
        assertTrue(exit.reason.contains("时间止损"), "原因应包含'时间止损': ${exit.reason}")
    }

    @Test
    fun `优先级 - 止盈高于时间止损`() {
        val entryDate = tradingDays[0]
        val t3 = tradingDays[2]

        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            t3 to bar(open = 10.0, high = 10.90, low = 10.0, close = 10.30),  // HIGH 触及 +8%
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = t3, positions = mapOf(tsCode to position(100)))
        assertEquals(1, exits.size)
        val exit = exits[0]
        assertEquals(org.shiroumi.backtest.domain.ExecutionHint.LIMIT, exit.hint)
        assertTrue(exit.reason.contains("止盈"), "止盈优先级更高: ${exit.reason}")
    }

    @Test
    fun `时间止损天数可配置 - timeStopDays=5`() {
        val config = ExitRulesConfig(timeStopDays = 5)
        val entryDate = tradingDays[0]
        val t4 = tradingDays[3]  // daysSinceEntry=3

        val feed = mapFeed(
            tradingDays[0] to bar(low = 9.5),
            t4 to bar(open = 10.0, high = 10.20, low = 9.9, close = 10.10),
            tradingDays[4] to bar(open = 10.0, high = 10.20, low = 9.9, close = 10.10),
        )
        val exitMgr = PositionExitManager(config, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exitsEarly = exitMgr.checkExits(date = t4, positions = mapOf(tsCode to position(100)))
        assertEquals(0, exitsEarly.size, "timeStopDays=5 时第 4 天不触发时间止损")

        val exits = exitMgr.checkExits(date = tradingDays[4], positions = mapOf(tsCode to position(100)))
        assertEquals(1, exits.size, "timeStopDays=5 时第 5 天应触发")
    }

    // ---- 价格止损（默认关闭）----

    @Test
    fun `价格止损 - 默认关闭不触发`() {
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        val feed = mapFeed(
            tradingDays[0] to bar(high = 10.30, low = 9.5, close = 10.20),
            // 收盘 9.40 < 信号日最低 9.5，但浅止损也会触发；用更高收盘隔离价格止损：
            t2 to bar(open = 9.90, high = 10.10, low = 9.40, close = 9.45),
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = t2, positions = mapOf(tsCode to position(100)))
        // 默认价格止损关闭；但收盘 9.45 < 9.70 浅止损线 → 浅止损会触发。验证它不是价格止损：
        assertEquals(1, exits.size)
        val exit = exits[0]
        assertTrue(exit.reason.contains("浅浮亏止损"), "默认应走浅止损而非价格止损: ${exit.reason}")
    }

    @Test
    fun `价格止损 - 显式开启且关闭浅止损 - 收盘低于信号日最低触发`() {
        val config = ExitRulesConfig(priceStopEnabled = true, shallowStopLossPct = 0.0)
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        val feed = mapFeed(
            tradingDays[0] to bar(high = 10.30, low = 9.5, close = 10.20),
            t2 to bar(open = 9.80, high = 10.10, low = 9.20, close = 9.30), // 收盘 9.30 < 9.5
        )
        val exitMgr = PositionExitManager(config, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = t2, positions = mapOf(tsCode to position(100)))
        assertEquals(1, exits.size)
        val exit = exits[0]
        assertEquals(org.shiroumi.backtest.domain.ExecutionHint.CLOSE, exit.hint)
        assertTrue(exit.reason.contains("价格止损"), "原因应包含'价格止损': ${exit.reason}")
    }

    // ---- T+1 禁售 & 跟踪生命周期 ----

    @Test
    fun `T+1 禁售 - 入场日不生成退出单`() {
        val entryDate = tradingDays[0]

        val feed = mapFeed(
            // 入场日：HIGH 触及止盈 + 收盘低于浅止损线，但 daysSinceEntry=0 一律不离场
            tradingDays[0] to bar(open = 10.0, high = 10.90, low = 9.0, close = 9.30),
        )
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = entryDate, positions = mapOf(tsCode to position(100)))
        assertEquals(0, exits.size, "T+1 禁售日不生成任何退出单")
    }

    @Test
    fun `无元数据的持仓不触发退出`() {
        val feed = mapFeed(tradingDays[0] to bar(high = 10.90, low = 9.0, close = 9.30))
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        // 不调用 onEntry
        val exits = exitMgr.checkExits(date = tradingDays[0], positions = mapOf(tsCode to position(100)))
        assertEquals(0, exits.size, "无入场元数据则不生成退出单")
    }

    @Test
    fun `多只股票各自独立判定`() {
        val codeA = "000001.SZ"
        val codeB = "600519.SH"
        val entryDate = tradingDays[0]
        val t2 = tradingDays[1]

        val feed: BacktestMarketDataFeed = object : BacktestMarketDataFeed {
            override fun marketDataFor(date: LocalDate): DailyMarketData = when (date) {
                tradingDays[0] -> DailyMarketData(
                    quotes = mapOf(
                        codeA to candle(tsCode = codeA, date = date, high = 10.30, low = 9.5, close = 10.2),
                        codeB to candle(tsCode = codeB, date = date, high = 103.0, low = 99.0, close = 102.0),
                    ),
                    preClose = emptyMap(),
                )
                t2 -> DailyMarketData(
                    quotes = mapOf(
                        codeA to candle(tsCode = codeA, date = date, open = 10.0, high = 10.90, low = 10.0, close = 10.30),
                        codeB to candle(tsCode = codeB, date = date, open = 100.0, high = 101.0, low = 99.0, close = 100.0),
                    ),
                    preClose = emptyMap(),
                )
                else -> DailyMarketData(emptyMap())
            }
        }
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(codeA, entryDate, entryPrice = 10.0)
        exitMgr.onEntry(codeB, entryDate, entryPrice = 100.0)

        val exits = exitMgr.checkExits(
            date = t2,
            positions = mapOf(codeA to position(100, codeA), codeB to position(50, codeB)),
        )
        assertEquals(1, exits.size, "只有 A 触发止盈")
        assertEquals(codeA, exits[0].tsCode)
    }

    @Test
    fun `onExit 清除元数据后不再跟踪`() {
        val entryDate = tradingDays[0]
        val feed = mapFeed(tradingDays[0] to bar(high = 10.30, low = 9.5, close = 10.20))
        val exitMgr = PositionExitManager(defaultConfig, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)
        assertEquals(1, exitMgr.trackedCount())
        exitMgr.onExit(tsCode)
        assertEquals(0, exitMgr.trackedCount())
    }

    @Test
    fun `禁用 T+1 禁售 - 入场日即可止盈卖出`() {
        val config = ExitRulesConfig(t1NoSell = false)
        val entryDate = tradingDays[0]

        val feed = mapFeed(tradingDays[0] to bar(open = 10.0, high = 10.90, low = 9.5, close = 10.30))
        val exitMgr = PositionExitManager(config, calendar, feed)
        exitMgr.onEntry(tsCode, entryDate, entryPrice = 10.0)

        val exits = exitMgr.checkExits(date = entryDate, positions = mapOf(tsCode to position(100)))
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
