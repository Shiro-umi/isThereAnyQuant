package org.shiroumi.strategy.service.universe

import org.shiroumi.database.common.repository.StockBasicRepository

object MainBoardUniverseProvider {
    const val UNIVERSE_TYPE = "main_board"

    fun getActiveSymbols(): List<String> = StockBasicRepository.findActiveProfiles()
        .asSequence()
        .filter { isTenCentimeterMainBoard(it.tsCode, it.name) }
        .map { it.tsCode }
        .sorted()
        .toList()

    internal fun isTenCentimeterMainBoard(tsCode: String, name: String): Boolean {
        if (name.contains("ST", ignoreCase = true)) return false
        val code = tsCode.substringBefore('.')
        val market = tsCode.substringAfter('.', "")
        return when (market) {
            "SH" -> code.startsWith("600") || code.startsWith("601") || code.startsWith("603") || code.startsWith("605")
            "SZ" -> code.startsWith("000") || code.startsWith("001") || code.startsWith("002") || code.startsWith("003")
            else -> false
        }
    }
}
