package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stockDb
import org.shiroumi.database.strategy.daily.table.DailyMarketSentimentTable
import org.shiroumi.database.transaction
import org.shiroumi.strategy.core.daily.MarketSentimentSnapshot
import org.shiroumi.strategy.core.sentiment.SentimentDerivedFields
import org.shiroumi.strategy.core.sentiment.restoreSentimentDerivedFields

object DailyMarketSentimentRepository {
    fun countByDate(tradeDate: LocalDate): Long {
        return stockDb.transaction(DailyMarketSentimentTable, log = false) {
            DailyMarketSentimentTable.selectAll()
                .where { DailyMarketSentimentTable.tradeDate eq tradeDate }
                .count()
        }
    }

    fun deleteByDate(tradeDate: LocalDate): Int {
        return stockDb.transaction(DailyMarketSentimentTable, log = false) {
            DailyMarketSentimentTable.deleteWhere { DailyMarketSentimentTable.tradeDate eq tradeDate }
        }
    }

    fun replaceForDate(snapshot: MarketSentimentSnapshot) {
        stockDb.transaction(DailyMarketSentimentTable) {
            DailyMarketSentimentTable.batchUpsert(listOf(Unit)) {
                this[DailyMarketSentimentTable.tradeDate] = snapshot.tradeDate
                this[DailyMarketSentimentTable.signalBasis] = snapshot.signalBasis
                this[DailyMarketSentimentTable.sampleSize] = snapshot.sampleSize
                this[DailyMarketSentimentTable.bullRatio] = snapshot.bullRatio
                this[DailyMarketSentimentTable.fftScore] = snapshot.fftScore
                this[DailyMarketSentimentTable.residualScore] = snapshot.residualScore
                this[DailyMarketSentimentTable.marketVol] = snapshot.marketVol
                this[DailyMarketSentimentTable.volZ] = snapshot.volZ
                this[DailyMarketSentimentTable.accelZ] = snapshot.accelZ
                this[DailyMarketSentimentTable.sentimentExposure] = snapshot.sentimentExposure
                this[DailyMarketSentimentTable.ratioNorm] = snapshot.ratioNorm
                this[DailyMarketSentimentTable.volScore] = snapshot.volScore
                this[DailyMarketSentimentTable.accelScore] = snapshot.accelScore
                this[DailyMarketSentimentTable.absoluteFloor] = snapshot.absoluteFloor
                this[DailyMarketSentimentTable.volCap] = snapshot.volCap
                this[DailyMarketSentimentTable.sufficientHistory] = snapshot.sufficientHistory
                this[DailyMarketSentimentTable.requiredHistory] = snapshot.requiredHistory
                this[DailyMarketSentimentTable.reason] = snapshot.reason
            }
        }
    }

    /**
     * 查询指定日期的市场情绪
     * 用于盘中计算初始化时加载 T-1 日盘后数据
     */
    fun findByDate(tradeDate: LocalDate): MarketSentimentSnapshot? {
        return stockDb.transaction(DailyMarketSentimentTable) {
            DailyMarketSentimentTable
                .selectAll()
                .where { DailyMarketSentimentTable.tradeDate eq tradeDate }
                .map(::toSnapshot)
                .firstOrNull()
        }
    }

    fun findRecentUpToDate(endDateInclusive: LocalDate, limit: Int): List<MarketSentimentSnapshot> {
        return stockDb.transaction(DailyMarketSentimentTable) {
            DailyMarketSentimentTable
                .selectAll()
                .where { DailyMarketSentimentTable.tradeDate lessEq endDateInclusive }
                .orderBy(DailyMarketSentimentTable.tradeDate, SortOrder.DESC)
                .limit(limit)
                .map(::toSnapshot)
                .asReversed()
        }
    }

    /**
     * 读取截止指定日期的全部历史情绪。
     *
     * 这条接口只用于需要“从完整连续历史精确恢复滚动状态”的场景，
     * 例如首次生成 `SentimentRuntimeSeed.accelWindow`。
     */
    fun findAllUpToDate(endDateInclusive: LocalDate): List<MarketSentimentSnapshot> {
        return stockDb.transaction(DailyMarketSentimentTable) {
            DailyMarketSentimentTable
                .selectAll()
                .where { DailyMarketSentimentTable.tradeDate lessEq endDateInclusive }
                .orderBy(DailyMarketSentimentTable.tradeDate, SortOrder.ASC)
                .map(::toSnapshot)
        }
    }

    fun findLatestBefore(tradeDateExclusive: LocalDate): MarketSentimentSnapshot? {
        return stockDb.transaction(DailyMarketSentimentTable) {
            DailyMarketSentimentTable
                .selectAll()
                .where { DailyMarketSentimentTable.tradeDate lessEq tradeDateExclusive }
                .orderBy(DailyMarketSentimentTable.tradeDate, SortOrder.DESC)
                .limit(1)
                .map(::toSnapshot)
                .firstOrNull()
        }
    }

    private fun toSnapshot(row: org.jetbrains.exposed.v1.core.ResultRow): MarketSentimentSnapshot {
        val bullRatio = row[DailyMarketSentimentTable.bullRatio]
        val volZ = row[DailyMarketSentimentTable.volZ]
        val accelZ = row[DailyMarketSentimentTable.accelZ]
        val sufficientHistory = row[DailyMarketSentimentTable.sufficientHistory]
        val reason = row[DailyMarketSentimentTable.reason]
        val storedDerivedFields = SentimentDerivedFields(
            ratioNorm = row[DailyMarketSentimentTable.ratioNorm],
            volScore = row[DailyMarketSentimentTable.volScore],
            accelScore = row[DailyMarketSentimentTable.accelScore],
            absoluteFloor = row[DailyMarketSentimentTable.absoluteFloor],
            volCap = row[DailyMarketSentimentTable.volCap],
        )
        val derivedFields = if (sufficientHistory && reason == null && storedDerivedFields.isAllZero()) {
            restoreSentimentDerivedFields(
                bullRatio = bullRatio,
                volZ = volZ,
                accelZ = accelZ,
                sufficientHistory = sufficientHistory,
                reason = reason
            )
        } else {
            storedDerivedFields
        }
        return MarketSentimentSnapshot(
            tradeDate = row[DailyMarketSentimentTable.tradeDate],
            signalBasis = row[DailyMarketSentimentTable.signalBasis],
            sampleSize = row[DailyMarketSentimentTable.sampleSize],
            bullRatio = bullRatio,
            fftScore = row[DailyMarketSentimentTable.fftScore],
            residualScore = row[DailyMarketSentimentTable.residualScore],
            marketVol = row[DailyMarketSentimentTable.marketVol],
            volZ = volZ,
            accelZ = accelZ,
            sentimentExposure = row[DailyMarketSentimentTable.sentimentExposure],
            ratioNorm = derivedFields.ratioNorm,
            volScore = derivedFields.volScore,
            accelScore = derivedFields.accelScore,
            absoluteFloor = derivedFields.absoluteFloor,
            volCap = derivedFields.volCap,
            sufficientHistory = sufficientHistory,
            requiredHistory = row[DailyMarketSentimentTable.requiredHistory],
            reason = reason
        )
    }
}
