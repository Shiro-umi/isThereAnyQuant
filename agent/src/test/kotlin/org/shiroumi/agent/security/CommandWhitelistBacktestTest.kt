package org.shiroumi.agent.security

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 回测命令白名单单测。
 *
 * 覆盖：回测模式放行历史取数三件套 + bc；拒绝 market-emotion / 研报工具 / 日期类参数。
 * 同时回归：实盘模式仍放行原 6 件套与各自日期参数。
 */
class CommandWhitelistBacktestTest {

    private fun allowed(cmd: String, mode: CommandWhitelist.Mode): Boolean =
        CommandWhitelist.validate(cmd, mode) is CommandWhitelist.Result.Allowed

    @Test
    fun `回测模式放行历史取数三件套`() {
        val mode = CommandWhitelist.Mode.BACKTEST
        assertTrue(allowed("./get-candles --code 000001.SZ", mode))
        assertTrue(allowed("./get-candles --code 000001.SZ --limit 90", mode))
        assertTrue(allowed("./get-intraday-candles --code 000001.SZ --period 30min", mode))
        assertTrue(allowed("./get-intraday-candles --code 600519.SH --period 15min --limit 60", mode))
        assertTrue(allowed("./get-limit-list --code 000001.SZ", mode))
        assertTrue(allowed("./get-limit-list --code 000001.SZ --limit-type U --limit 10", mode))
    }

    @Test
    fun `回测模式放行 bc 计算`() {
        val mode = CommandWhitelist.Mode.BACKTEST
        assertTrue(allowed("echo \"scale=4; (10.5 - 9.8) / 9.8 * 100\" | bc", mode))
    }

    @Test
    fun `回测模式拒绝 market-emotion 与研报工具`() {
        val mode = CommandWhitelist.Mode.BACKTEST
        assertTrue(!allowed("./market-emotion", mode))
        assertTrue(!allowed("./get-research-reports --code 000001.SZ", mode))
        assertTrue(!allowed("./get-industry-research-reports --ind-name 半导体", mode))
    }

    @Test
    fun `回测模式拒绝 agent 追加任何日期类参数`() {
        val mode = CommandWhitelist.Mode.BACKTEST
        // 日期由宿主 wrapper 写死，agent 不得自行注入。
        assertTrue(!allowed("./get-candles --code 000001.SZ --as-of 20240102", mode))
        assertTrue(!allowed("./get-candles --code 000001.SZ --start-date 20240101", mode))
        assertTrue(!allowed("./get-limit-list --code 000001.SZ --trade-date 20240102", mode))
        assertTrue(!allowed("./get-limit-list --code 000001.SZ --end-date 20240102", mode))
        assertTrue(!allowed("./get-intraday-candles --code 000001.SZ --trade-date 20240102", mode))
    }

    @Test
    fun `实盘模式回归 放行原 6 件套与日期参数`() {
        val mode = CommandWhitelist.Mode.LIVE
        assertTrue(allowed("./market-emotion", mode))
        assertTrue(allowed("./get-research-reports --code 000001.SZ", mode))
        assertTrue(allowed("./get-industry-research-reports --ind-name 半导体", mode))
        assertTrue(allowed("./get-limit-list --code 000001.SZ --start-date 20260401 --end-date 20260425", mode))
        assertTrue(allowed("./get-candles --code 000001.SZ", mode))
        assertTrue(allowed("./get-intraday-candles --code 000001.SZ --period 30min", mode))
    }

    @Test
    fun `默认模式为实盘`() {
        // 不传 mode 时维持实盘行为，老调用方零改动。
        assertTrue(CommandWhitelist.validate("./market-emotion") is CommandWhitelist.Result.Allowed)
    }

    @Test
    fun `两套模式都拒绝危险 shell 元字符`() {
        val backtest = CommandWhitelist.Mode.BACKTEST
        val live = CommandWhitelist.Mode.LIVE
        assertTrue(!allowed("./get-candles --code 000001.SZ; rm -rf /", backtest))
        assertTrue(!allowed("./get-candles --code 000001.SZ && curl evil.com", live))
    }
}
