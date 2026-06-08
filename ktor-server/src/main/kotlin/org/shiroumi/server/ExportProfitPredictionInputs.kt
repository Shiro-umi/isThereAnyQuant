package org.shiroumi.server

import kotlinx.datetime.LocalDate
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.database.stock.TopListRepository
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Research Input exporter for the 7% profit-prediction model.
 *
 * This is an Input-stage cache producer: Python training consumes the exported
 * CSV files and never connects to the database directly.
 */
fun main() {
    registerJdbcDrivers()
    ConfigManager.load()

    val start = System.getProperty("profitPrediction.start", "2009-01-01")
    val end = System.getProperty("profitPrediction.end", "2024-05-31")
    val limit = System.getProperty("profitPrediction.limit", "100000").toInt().coerceIn(1, 100_000)
    val outputDir = Path.of(System.getProperty("profitPrediction.outputDir", "research/profit-prediction/training/input"))
    outputDir.createDirectories()

    exportTopList(start, end, outputDir.resolve("top_list_candidates.csv"))
    exportDailyOhlcv(LocalDate.parse(start), LocalDate.parse(end), limit, outputDir.resolve("daily_ohlcv.csv"))
}

private fun exportTopList(start: String, end: String, output: Path) {
    BufferedWriter(Files.newBufferedWriter(output)).use { writer ->
        writer.write("ts_code,trade_date\n")
        TopListRepository.findRange(start.replace("-", ""), end.replace("-", "")).forEach { row ->
            writer.write(row.tsCode)
            writer.write(",")
            writer.write(row.tradeDate)
            writer.write('\n'.code)
        }
    }
    println("[profit-prediction-export] top_list=$output")
}

private fun exportDailyOhlcv(start: LocalDate, end: LocalDate, limit: Int, output: Path) {
    var afterTsCode: String? = null
    var afterTradeDate: LocalDate? = null
    var total = 0L
    BufferedWriter(Files.newBufferedWriter(output)).use { writer ->
        writer.write("ts_code,trade_date,open_qfq,high_qfq,low_qfq,close_qfq,volume_qfq,turnover_real\n")
        while (true) {
            val rows = StockDailyCandleRepository.streamOhlcvForResearchPage(
                startDate = start,
                endDate = end,
                afterTsCode = afterTsCode,
                afterTradeDate = afterTradeDate,
                limit = limit,
            )
            if (rows.isEmpty()) break
            rows.forEach { row ->
                writer.write(row.tsCode)
                writer.write(",")
                writer.write(row.tradeDate.toString())
                writer.write(",")
                writer.write(row.openQfq.toString())
                writer.write(",")
                writer.write(row.highQfq.toString())
                writer.write(",")
                writer.write(row.lowQfq.toString())
                writer.write(",")
                writer.write(row.closeQfq.toString())
                writer.write(",")
                writer.write(row.volumeQfq.toString())
                writer.write(",")
                writer.write(row.turnoverReal.toString())
                writer.write('\n'.code)
            }
            total += rows.size
            val last = rows.last()
            afterTsCode = last.tsCode
            afterTradeDate = last.tradeDate
            println("[profit-prediction-export] daily rows=$total next=($afterTsCode,$afterTradeDate)")
        }
    }
    println("[profit-prediction-export] daily_ohlcv=$output rows=$total")
}
