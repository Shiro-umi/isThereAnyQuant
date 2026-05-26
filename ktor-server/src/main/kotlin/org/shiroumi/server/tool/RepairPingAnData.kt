package org.shiroumi.server.tool

import kotlinx.coroutines.runBlocking
import model.Candle
import model.dataprovider.HistoricalDailyCandleRequest
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.server.dataprovider.adapter.TushareHistoricalDailyCandleFetcher
import org.shiroumi.server.registerJdbcDrivers
import utils.logger
import kotlinx.datetime.LocalDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus

private val logger by logger("RepairPingAnData")

fun main() {
    registerJdbcDrivers()
    ConfigManager.load()

    val tsCode = "000001.SZ"
    
    // 手动指定日期，避免 Clock.System 的编译问题
    val now = LocalDate(2026, 4, 21) 
    val startDate = now.minus(14, DateTimeUnit.DAY)
    
    println("\n🚀 开始修复 $tsCode 的历史日线数据...")
    println("📅 时间范围: $startDate 至 $now (最近2周)")

    val fetcher = TushareHistoricalDailyCandleFetcher()
    
    runBlocking {
        try {
            println("🌐 正在从 Tushare 获取 authoritative 数据...")
            val candles = fetcher.fetch(
                HistoricalDailyCandleRequest(
                    tsCode = tsCode,
                    startDate = startDate,
                    endDate = now,
                    limit = 30
                )
            )
            
            if (candles.isNotEmpty()) {
                println("📦 成功获取 ${candles.size} 条日线数据。")
                println("💾 正在执行 batchUpsert 强制覆盖数据库记录...")
                StockDailyCandleRepository.replaceCandles(candles)
                println("✅ 修复完成！平安银行的数据已根据 Tushare 权威源重刷。")
            } else {
                println("⚠️ 未能获取到指定日期范围内的日线数据。")
            }
        } catch (e: Exception) {
            println("❌ 修复过程中发生错误: ${e.message}")
            e.printStackTrace()
        }
    }
}
