package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.dataprovider.SentimentRuntimeSeed
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stockDb
import org.shiroumi.database.strategy.daily.table.SentimentRuntimeSeedTable
import org.shiroumi.database.transaction

/**
 * 情绪运行时种子仓储。
 *
 * 这层的职责只有两个：
 * 1. 盘后把“下一交易日可直接复用的 seed”落库
 * 2. 盘中按 `scope + forTradeDate` 精确读回 seed
 *
 * 它不负责情绪算法，也不负责 Provider 内存状态。
 */
object SentimentRuntimeSeedRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun countBySourceTradeDate(sourceTradeDate: LocalDate): Long {
        return stockDb.transaction(SentimentRuntimeSeedTable, log = false) {
            SentimentRuntimeSeedTable.selectAll()
                .where { SentimentRuntimeSeedTable.sourceTradeDate eq sourceTradeDate }
                .count()
        }
    }

    fun deleteBySourceTradeDate(sourceTradeDate: LocalDate): Int {
        return stockDb.transaction(SentimentRuntimeSeedTable, log = false) {
            SentimentRuntimeSeedTable.deleteWhere { SentimentRuntimeSeedTable.sourceTradeDate eq sourceTradeDate }
        }
    }

    /**
     * 使用唯一键 `(scope, forTradeDate)` 做幂等覆盖写入。
     *
     * 语义上同一交易日同一作用域只能存在一份权威 seed，
     * 因此这里不采用追加式写入。
     *
     * 当前表有 `(scope, for_trade_date)` 唯一索引，
     * `batchUpsert` 不传 keys 时生成 `ON DUPLICATE KEY UPDATE`，
     * MySQL 自动基于该索引检测冲突，实现同键覆盖。
     */
    fun replace(seed: SentimentRuntimeSeed) {
        stockDb.transaction(SentimentRuntimeSeedTable, log = false) {
            SentimentRuntimeSeedTable.batchUpsert(listOf(seed)) { current ->
                this[SentimentRuntimeSeedTable.scope] = current.scope
                this[SentimentRuntimeSeedTable.forTradeDate] = current.forTradeDate
                this[SentimentRuntimeSeedTable.sourceTradeDate] = current.sourceTradeDate
                this[SentimentRuntimeSeedTable.signalBasis] = current.signalBasis
                this[SentimentRuntimeSeedTable.requiredHistory] = current.requiredHistory
                this[SentimentRuntimeSeedTable.sampleSize] = current.sampleSize
                this[SentimentRuntimeSeedTable.sampleCodesJson] = json.encodeToString(current.sampleCodes)
                this[SentimentRuntimeSeedTable.symbolStatesJson] = json.encodeToString(current.symbolStates)
                this[SentimentRuntimeSeedTable.bullRatioWindowJson] = json.encodeToString(current.bullRatioHistory)
                this[SentimentRuntimeSeedTable.marketVolWindowJson] = json.encodeToString(current.marketVolHistory)
                this[SentimentRuntimeSeedTable.accelWindowJson] = json.encodeToString(current.accelHistory)
                this[SentimentRuntimeSeedTable.combinedHistoryJson] = json.encodeToString(current.combinedHistory)
                this[SentimentRuntimeSeedTable.totalDays] = current.totalDays
                this[SentimentRuntimeSeedTable.createdAt] = current.createdAt
            }
        }
    }

    /**
     * 精确读取指定交易日的 seed。
     */
    fun find(scope: String, forTradeDate: LocalDate): SentimentRuntimeSeed? {
        return stockDb.transaction(SentimentRuntimeSeedTable, log = false) {
            SentimentRuntimeSeedTable
                .selectAll()
                .where {
                    (SentimentRuntimeSeedTable.scope eq scope) and
                        (SentimentRuntimeSeedTable.forTradeDate eq forTradeDate)
                }
                .map(::toSeed)
                .firstOrNull()
        }
    }

    private fun toSeed(row: org.jetbrains.exposed.v1.core.ResultRow): SentimentRuntimeSeed {
        val context = "scope=${row[SentimentRuntimeSeedTable.scope]}, forTradeDate=${row[SentimentRuntimeSeedTable.forTradeDate]}"
        return SentimentRuntimeSeed(
            scope = row[SentimentRuntimeSeedTable.scope],
            forTradeDate = row[SentimentRuntimeSeedTable.forTradeDate],
            sourceTradeDate = row[SentimentRuntimeSeedTable.sourceTradeDate],
            signalBasis = row[SentimentRuntimeSeedTable.signalBasis],
            requiredHistory = row[SentimentRuntimeSeedTable.requiredHistory],
            sampleSize = row[SentimentRuntimeSeedTable.sampleSize],
            sampleCodes = json.parseChecked("sample_codes_json", context, row[SentimentRuntimeSeedTable.sampleCodesJson]),
            symbolStates = json.parseChecked("symbol_states_json", context, row[SentimentRuntimeSeedTable.symbolStatesJson]),
            bullRatioHistory = json.parseChecked("bull_ratio_window_json", context, row[SentimentRuntimeSeedTable.bullRatioWindowJson]),
            marketVolHistory = json.parseChecked("market_vol_window_json", context, row[SentimentRuntimeSeedTable.marketVolWindowJson]),
            accelHistory = json.parseChecked("accel_window_json", context, row[SentimentRuntimeSeedTable.accelWindowJson]),
            combinedHistory = json.parseChecked("combined_history_json", context, row[SentimentRuntimeSeedTable.combinedHistoryJson]),
            totalDays = row[SentimentRuntimeSeedTable.totalDays],
            createdAt = row[SentimentRuntimeSeedTable.createdAt]
        )
    }

    private inline fun <reified T> Json.parseChecked(columnName: String, context: String, raw: String): T {
        check(raw.isNotBlank()) {
            "SentimentRuntimeSeed JSON column '$columnName' is blank or empty ($context). " +
                "This indicates corrupted or incomplete seed data in the database."
        }
        return decodeFromString(raw)
    }
}
