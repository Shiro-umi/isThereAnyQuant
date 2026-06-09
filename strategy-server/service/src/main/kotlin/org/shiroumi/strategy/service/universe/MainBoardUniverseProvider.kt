package org.shiroumi.strategy.service.universe

import org.shiroumi.database.common.repository.StockBasicRepository

/**
 * 选股候选池：主板（10cm）+ 创业板（20cm），排除 ST/PT/退市/N新股。
 * 不包含科创板（688），其交易规则和投资者结构与主板差异较大。
 */
object MainBoardUniverseProvider {
    const val UNIVERSE_TYPE = "main_and_chiext"

    fun getActiveSymbols(): List<String> = StockBasicRepository.findActiveProfiles()
        .asSequence()
        .filter { isEligible(it.tsCode, it.name, it.listStatus, it.delistDate) }
        .map { it.tsCode }
        .sorted()
        .toList()

    /**
     * 名称 + 退市状态双判断：
     * - 名称命中 ST/PT/退/N新股 → 排除（数据源若已改名，名称兜底）
     * - listStatus 为 D（退市）/ P（暂停上市）或 delistDate 已落值 → 排除（数据源若状态已更新而名称未改，状态兜底）
     * 两者互补，覆盖「改名滞后」与「状态滞后」两种数据源不同步情形。
     */
    internal fun isEligible(
        tsCode: String,
        name: String,
        listStatus: String,
        delistDate: kotlinx.datetime.LocalDate?,
    ): Boolean {
        // 退市状态兜底
        when (listStatus.uppercase()) {
            "D", "P" -> return false
        }
        if (delistDate != null) return false
        // 过滤风险警示股和退市整理股（名称兜底）
        if (name.contains("ST", ignoreCase = true)) return false
        if (name.contains("PT", ignoreCase = true)) return false
        if (name.contains("退", ignoreCase = true)) return false
        if (name.contains("N", ignoreCase = false) && name.length <= 4) return false
        val code = tsCode.substringBefore('.')
        val market = tsCode.substringAfter('.', "")
        return when (market) {
            "SH" -> code.startsWith("600") || code.startsWith("601") || code.startsWith("603") || code.startsWith("605")
            "SZ" -> code.startsWith("000") || code.startsWith("001") || code.startsWith("002") || code.startsWith("003")
                 || code.startsWith("300") || code.startsWith("301")
            else -> false
        }
    }
}
