package org.shiroumi.server.tools

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format
import model.Candle
import org.shiroumi.database.common.CalendarReader
import org.shiroumi.database.stock.StockReader
import org.shiroumi.network.apis.getKplConcept
import org.shiroumi.network.apis.getKplConceptCons
import org.shiroumi.network.apis.tushare
import utils.dateFormatter

/**
 * 羊群效应选股工具
 * 从 ktor.module.llm.tools 迁移而来，移除 koog 依赖
 */
object BandwagonEffectPicker {

    /**
     * 执行羊群效应选股
     * @param targetDateStr 交易日期，格式：yyyy-MM-dd，null表示今天
     * @return 选股结果文本
     */
    suspend fun filterBandwagonEffects(targetDateStr: String? = null): String {
        val targetDate = if (targetDateStr != null) {
            LocalDate.parse(targetDateStr)
        } else {
            java.time.LocalDate.now().let { LocalDate(it.year, it.monthValue, it.dayOfMonth) }
        }

        println("开始人气主升浪选股 (基于 KPL 题材数据)... 目标日期: ${targetDate.format(dateFormatter)}")

        // 1. Get Trading Days (Last 10)
        val tradingDays = CalendarReader.getRecentTradingDays(targetDate, 15) // Get 15 to be safe
            .take(10)

        if (tradingDays.isEmpty()) {
            println("未找到交易日数据，请检查 CalendarTable")
            return "未找到交易日数据，请检查 CalendarTable"
        }

        println("分析交易日范围: ${tradingDays.last()} ~ ${tradingDays.first()}")

        // 2. Fetch KPL Theme Data
        println("\nStep 1: 获取开盘啦题材热度...")
        val themeHeat = mutableMapOf<String, ThemeStat>()

        for (date in tradingDays) {
            val dateStr = date.format(dateFormatter)
            print("Fetching KPL Concept for $dateStr...\r")
            try {
                val response = tushare.getKplConcept(tradeDate = dateStr).check()
                response?.items?.forEach { item ->
                    // Columns: ts_code, name, z_t_num
                    // We assume columns are roughly in this order or we check fields
                    val columns = response.fields
                    val codeIdx = columns.indexOf("ts_code")
                    val nameIdx = columns.indexOf("name")
                    val ztIdx = columns.indexOf("z_t_num")

                    if (codeIdx != -1 && nameIdx != -1 && ztIdx != -1) {
                        val code = item[codeIdx] ?: return@forEach
                        val name = item[nameIdx] ?: ""
                        val ztNum = item[ztIdx]?.toIntOrNull() ?: 0

                        val stat = themeHeat.getOrPut(code) { ThemeStat(code, name) }
                        stat.totalZt += ztNum
                    }
                }
                delay(100) // Rate limit
            } catch (e: Exception) {
                println("\nFailed to fetch KPL data for $dateStr: ${e.message}")
            }
        }
        println("\n题材数据获取完成。")

        // Rank Themes
        val top10Themes = themeHeat.values.sortedByDescending { it.totalZt }.take(10)

        println("\n=== 羊群效应最强题材 (Top 10) ===")
        println(String.format("%-5s | %-15s | %-16s", "Rank", "Theme", "10d Total ZT Num"))
        top10Themes.forEachIndexed { index, theme ->
            println(String.format("%-5d | %-15s | %-16d", index + 1, theme.name, theme.totalZt))
        }

        // 3. Fetch Members & Filter
        println("\nStep 2: 分析成分股，筛选非涨停领头羊...")

        val stockInfoMap = StockReader.getStockInfoMap()
        val allCandidates = mutableSetOf<String>()
        val themeMembersMap = mutableMapOf<String, List<String>>()

        for (theme in top10Themes) {
            try {
                val response = tushare.getKplConceptCons(tsCode = theme.code).check()
                val members = response?.items?.mapNotNull {
                    // Columns: ts_code, con_code, name
                    val conCodeIdx = response.fields.indexOf("con_code")
                    if (conCodeIdx != -1) it[conCodeIdx] as? String else null
                }?.distinct() ?: emptyList()

                themeMembersMap[theme.code] = members
                allCandidates.addAll(members)
                delay(100)
            } catch (e: Exception) {
                println("Failed members for ${theme.name}: ${e.message}")
            }
        }

        println("Total Unique Candidates to Check: ${allCandidates.size}")

        // 4. Load Data & Calculate Stats
        val stockCache = mutableMapOf<String, StockStats>()
        var loadedCount = 0

        // We need data up to targetDate.
        // History query gets recent data.

        allCandidates.filter { !isExcludedBoard(it) }.forEach { code ->
            loadedCount++
            if (loadedCount % 100 == 0) print("Loading $loadedCount/${allCandidates.size}...\r")

            val candles = StockReader.getStockHistory(code, limit = 30) // Get more to be safe
            if (candles.isNotEmpty()) {
                // Find index of targetDate
                val targetDateIdx = candles.indexOfFirst { it.date == targetDate }
                // If targetDate not found, maybe it's a non-trading day for this stock or suspended.
                // Try to find nearest date <= targetDate
                val validIdx = if (targetDateIdx != -1) targetDateIdx else {
                    candles.indexOfLast { it.date <= targetDate }
                }

                if (validIdx != -1) {
                    // We need history from validIdx backwards
                    // candles are sorted oldest to newest?
                    // StockReader.getStockHistory returns: .reversed() -> Oldest to Newest.
                    // Let's re-verify StockReader logic.
                    // StockReader: .orderBy(table.date, DESC).limit().map.reversed()
                    // So index 0 is Oldest, index Size-1 is Newest.

                    // But wait, if we found validIdx, that's the index in the list.
                    // We need to calculate stats ending at validIdx.

                    val stats = calculateStockStats(code, stockInfoMap[code] ?: "Unknown", candles, validIdx)
                    if (stats != null) {
                        stockCache[code] = stats
                    }
                }
            }
        }
        println("\nData loading complete.")

        val finalList = mutableListOf<StockDisplay>()

        for (theme in top10Themes) {
            val members = themeMembersMap[theme.code] ?: emptyList()
            val activeMembers = members.mapNotNull { stockCache[it] }
                .filter { !it.isLimitUpToday && !isExcludedBoard(it.code) } // Exclude Limit Up Today and filtered boards
                .sortedByDescending { it.pctChg10d } // Sort by 10d Gain
                .take(5) // Top 5

            activeMembers.forEach { stats ->
                finalList.add(
                    StockDisplay(
                        themeName = theme.name,
                        stats = stats
                    )
                )
            }
        }

        val output = """
最终入选: ${finalList.size} 只"
${
String.format(
    "%-12s | %-10s | %-8s | %-8s | %-8s | %-8s | %-8s",
    "题材", "代码", "名称", "10d%", "10d板数", "Today%", "Close"
)
}
${"- ".repeat(100)}
${
finalList.joinToString("\n") { item ->
    val s = item.stats
    String.format(
        "%-12s | %-10s | %-8s | %-8.2f | %-8d | %-8.2f | %-8.2f",
        item.themeName, s.code, s.name, s.pctChg10d * 100, s.limitUpCount10d, s.pctChgToday * 100, s.close
    )
}
}
    """.trimIndent()
        println(output)
        return output
    }

    private fun calculateStockStats(
        code: String,
        name: String,
        candles: List<Candle>,
        targetIdx: Int
    ): StockStats? {
        // candles is Oldest -> Newest
        // targetIdx is the index of targetDate (or nearest before)

        if (targetIdx < 0) return null

        val currRow = candles[targetIdx]
        val closeCol = currRow.close // or closeQfq if we want adjusted
        if (closeCol <= 0) return null

        val limitThreshold = getLimitThreshold(code)

        // 1. Is Limit Up Today?
        var pctChg = 0f
        if (targetIdx > 0) {
            val prev = candles[targetIdx - 1].close
            pctChg = (currRow.close - prev) / prev
        }
        val isLimitUpToday = pctChg > limitThreshold

        // 2. Limit Up Count in Last 10 Days
        // Window: [targetIdx - 9, targetIdx]
        val startWin = (targetIdx - 9).coerceAtLeast(0)
        var luCount = 0
        for (i in startWin..targetIdx) {
            if (i == 0) continue // Can't calc pctChg for first record in list if list starts there
            val prev = candles[i - 1].close
            val curr = candles[i].close
            val chg = (curr - prev) / prev
            if (chg > limitThreshold) {
                luCount++
            }
        }

        // 3. 10-Day Gain
        val prev10Idx = targetIdx - 10
        var gain10d = 0f
        if (prev10Idx >= 0) {
            val closeOld = candles[prev10Idx].close
            gain10d = (currRow.close - closeOld) / closeOld
        }

        return StockStats(
            code = code,
            name = name,
            pctChg10d = gain10d,
            limitUpCount10d = luCount,
            isLimitUpToday = isLimitUpToday,
            close = currRow.close,
            pctChgToday = pctChg,
            tradeDate = currRow.date
        )
    }

    private fun isExcludedBoard(tsCode: String): Boolean {
        val code = tsCode.split(".")[0]
        // 北交所
        if (tsCode.endsWith(".BJ", ignoreCase = true) || code.startsWith("8") || code.startsWith("4") || code.startsWith("92")) {
            return true
        }
        // 科创板
        if (code.startsWith("688")) {
            return true
        }
        return false
    }

    private fun getLimitThreshold(stockCode: String): Float {
        val code = stockCode.split(".")[0]
        if (stockCode.endsWith(".BJ") || code.startsWith("8") || code.startsWith("4") || code.startsWith("92")) {
            return 0.295f
        }
        if (code.startsWith("688") || code.startsWith("300") || code.startsWith("301")) {
            return 0.195f
        }
        return 0.095f
    }

    private data class ThemeStat(val code: String, val name: String, var totalZt: Int = 0)

    private data class StockStats(
        val code: String,
        val name: String,
        val pctChg10d: Float,
        val limitUpCount10d: Int,
        val isLimitUpToday: Boolean,
        val close: Float,
        val pctChgToday: Float,
        val tradeDate: LocalDate
    )

    private data class StockDisplay(
        val themeName: String,
        val stats: StockStats
    )
}
