package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import org.shiroumi.config.ConfigManager
import org.shiroumi.network.apis.getStkMins
import org.shiroumi.network.apis.tushare

/**
 * Step 0 一次性探针：确定 Tushare `stk_mins` 5min 对某股票的真实可回溯起点。
 *
 * stk_mins 硬约束（用户校正）：必须同时给 start_date / end_date，且区间内数据 ≤ 8000 条。
 * 5min 每股每日约 48 根，半年窗（约 120 交易日）≈ 5760 根 < 8000，故按半年窗逐年试探。
 *
 * 运行：./gradlew :ktor-server:probeStkMins -Dquant.probe.code=000001.SZ
 * 输出每个探测窗的行数与最早 trade_time，定位最早有数据的年份。
 */
fun main() = runBlocking {
    ConfigManager.load()
    val code = System.getProperty("quant.probe.code")?.trim()?.takeIf { it.isNotEmpty() } ?: "000001.SZ"
    val probeYears = listOf(2000, 2005, 2008, 2010, 2013, 2016, 2019, 2022, 2024)
    println("# probe stk_mins 5min for $code（半年窗 H1: 01-01~06-30）")
    for (y in probeYears) {
        val start = "$y-01-01 09:00:00"
        val end = "$y-06-30 16:00:00"
        try {
            val form = tushare.getStkMins(code, "5min", start, end).check()
            val rows = form?.items ?: emptyList()
            val ttIdx = form?.fields?.indexOf("trade_time") ?: -1
            val earliest = if (rows.isNotEmpty() && ttIdx >= 0)
                rows.mapNotNull { it.getOrNull(ttIdx) }.minOrNull() else null
            println("- $y H1: rows=${rows.size} earliest=${earliest ?: "（无数据）"}")
        } catch (e: Exception) {
            println("- $y H1: error=${e.message}")
        }
    }
}
