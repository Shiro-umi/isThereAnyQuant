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
        .filter { isEligible(it.tsCode, it.name) }
        .map { it.tsCode }
        .sorted()
        .toList()

    internal fun isEligible(tsCode: String, name: String): Boolean {
        // 过滤风险警示股和退市整理股
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
