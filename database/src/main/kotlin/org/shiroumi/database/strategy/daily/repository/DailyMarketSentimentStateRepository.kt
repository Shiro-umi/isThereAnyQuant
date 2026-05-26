package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stockDb
import org.shiroumi.strategy.core.daily.MarketSentimentRollingState
import org.shiroumi.database.strategy.daily.table.DailyMarketSentimentStateTable
import org.shiroumi.database.transaction

object DailyMarketSentimentStateRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun countByDate(tradeDate: LocalDate): Long {
        return stockDb.transaction(DailyMarketSentimentStateTable, log = false) {
            DailyMarketSentimentStateTable.selectAll()
                .where { DailyMarketSentimentStateTable.tradeDate eq tradeDate }
                .count()
        }
    }

    fun deleteByDate(tradeDate: LocalDate): Int {
        return stockDb.transaction(DailyMarketSentimentStateTable, log = false) {
            DailyMarketSentimentStateTable.deleteWhere { DailyMarketSentimentStateTable.tradeDate eq tradeDate }
        }
    }

    fun replace(state: MarketSentimentRollingState) {
        stockDb.transaction(DailyMarketSentimentStateTable, log = false) {
            DailyMarketSentimentStateTable.batchUpsert(listOf(state)) { s ->
                this[DailyMarketSentimentStateTable.tradeDate] = s.tradeDate
                this[DailyMarketSentimentStateTable.signalBasis] = s.signalBasis
                this[DailyMarketSentimentStateTable.sampleCodesJson] = json.encodeToString(s.sampleCodes)
                this[DailyMarketSentimentStateTable.symbolStatesJson] = json.encodeToString(s.symbolStates)
                this[DailyMarketSentimentStateTable.bullRatioHistoryJson] = json.encodeToString(s.bullRatioHistory)
                this[DailyMarketSentimentStateTable.marketVolHistoryJson] = json.encodeToString(s.marketVolHistory)
                this[DailyMarketSentimentStateTable.accelHistoryJson] = json.encodeToString(s.accelHistory)
                this[DailyMarketSentimentStateTable.combinedHistoryJson] = json.encodeToString(s.combinedHistory)
                this[DailyMarketSentimentStateTable.totalDays] = s.totalDays
            }
        }
    }

    fun findByDate(tradeDate: LocalDate): MarketSentimentRollingState? {
        return stockDb.transaction(DailyMarketSentimentStateTable, log = false) {
            DailyMarketSentimentStateTable
                .selectAll()
                .where { DailyMarketSentimentStateTable.tradeDate eq tradeDate }
                .map(::toState)
                .firstOrNull()
        }
    }

    private fun toState(row: org.jetbrains.exposed.v1.core.ResultRow): MarketSentimentRollingState {
        val context = "tradeDate=${row[DailyMarketSentimentStateTable.tradeDate]}"
        return MarketSentimentRollingState(
            tradeDate = row[DailyMarketSentimentStateTable.tradeDate],
            signalBasis = row[DailyMarketSentimentStateTable.signalBasis],
            sampleCodes = json.parseChecked("sample_codes_json", context, row[DailyMarketSentimentStateTable.sampleCodesJson]),
            symbolStates = json.parseChecked("symbol_states_json", context, row[DailyMarketSentimentStateTable.symbolStatesJson]),
            bullRatioHistory = json.parseChecked("bull_ratio_history_json", context, row[DailyMarketSentimentStateTable.bullRatioHistoryJson]),
            marketVolHistory = json.parseChecked("market_vol_history_json", context, row[DailyMarketSentimentStateTable.marketVolHistoryJson]),
            accelHistory = json.parseChecked("accel_history_json", context, row[DailyMarketSentimentStateTable.accelHistoryJson]),
            combinedHistory = json.parseChecked("combined_history_json", context, row[DailyMarketSentimentStateTable.combinedHistoryJson]),
            totalDays = row[DailyMarketSentimentStateTable.totalDays],
        )
    }

    private inline fun <reified T> Json.parseChecked(columnName: String, context: String, raw: String): T {
        check(raw.isNotBlank()) {
            "MarketSentimentState JSON column '$columnName' is blank or empty ($context). " +
                "This indicates corrupted or incomplete state data in the database."
        }
        return decodeFromString(raw)
    }
}
