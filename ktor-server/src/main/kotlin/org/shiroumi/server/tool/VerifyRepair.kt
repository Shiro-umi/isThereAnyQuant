package org.shiroumi.server.tool

import kotlinx.coroutines.runBlocking
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.stock.StockReader
import org.shiroumi.server.registerJdbcDrivers
import kotlinx.datetime.LocalDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import utils.logger

private val logger by logger("VerifyRepair")

fun main() {
    registerJdbcDrivers()
    ConfigManager.load()

    val tsCode = "000001.SZ"
    val now = LocalDate(2026, 4, 21) // consistent with repair date
    val startDate = now.minus(14, DateTimeUnit.DAY)
    
    println("\n🔍 正在验证 $tsCode 的修复结果...")
    
    runBlocking {
        try {
            val candles = StockReader.getStockHistory(tsCode, startDate, now)
            if (candles.isEmpty()) {
                println("❌ 未在数据库中找到 $tsCode 的历史记录。")
                return@runBlocking
            }
            
            println("📊 找到 ${candles.size} 条记录：")
            println("%-12s | %-8s | %-8s | %-8s | %-8s".format("日期", "收盘(RAW)", "收盘(QFQ)", "复权因子", "成交量"))
            println("-".repeat(60))
            
            candles.forEach { candle ->
                println("%-12s | %-8.2f | %-8.2f | %-8.4f | %-8.2f".format(
                    candle.date, 
                    candle.close, 
                    candle.closeQfq, 
                    candle.adj,
                    candle.volume
                ))
            }
            
            // 特别检查 2026-04-07
            val corruptedDate = LocalDate(2026, 4, 7)
            val corruptedCandle = candles.find { it.date == corruptedDate }
            if (corruptedCandle != null) {
                if (corruptedCandle.closeQfq > 0 && corruptedCandle.adj != 1.0f) {
                    println("\n✅ 2026-04-07 数据校验通过：QFQ=${corruptedCandle.closeQfq}, Adj=${corruptedCandle.adj}")
                } else if (corruptedCandle.adj == 1.0f) {
                     // In case no split happened, adj could be 1.0f, but the problem was it was 1.0f with QFQ = 0 earlier.
                     println("\nℹ️ 2026-04-07 复权因子为 1.0，请人工确认是否正确。QFQ=${corruptedCandle.closeQfq}")
                }
            } else {
                println("\n⚠️ 未能找到 2026-04-07 的数据点。")
            }
            
        } catch (e: Exception) {
            println("❌ 验证出错: ${e.message}")
            e.printStackTrace()
        }
    }
}
