package org.shiroumi.backtest.output

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.pow
import kotlin.math.round

/**
 * equity 曲线 CSV 导出器。
 */
object EquityCurveCsvExporter {
    fun export(points: List<EquityPoint>, path: Path) {
        path.parent?.let { Files.createDirectories(it) }
        val lines = buildList {
            add("trade_date,cash,position_value,equity")
            points.forEach { point ->
                add(
                    listOf(
                        point.tradeDate.toString(),
                        rounded(point.cash.toDouble()),
                        rounded(point.positionValue.toDouble()),
                        rounded(point.equity.toDouble()),
                    ).joinToString(",")
                )
            }
        }
        Files.writeString(path, lines.joinToString("\n"))
    }

    private fun rounded(value: Double, digits: Int = 4): String {
        val scale = 10.0.pow(digits)
        return (round(value * scale) / scale).toString()
    }
}
