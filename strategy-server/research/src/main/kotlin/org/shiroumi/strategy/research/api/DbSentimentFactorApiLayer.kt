package org.shiroumi.strategy.research.api

import kotlinx.datetime.LocalDate
import org.shiroumi.database.sentiment.SentimentFactorDailyRecord
import org.shiroumi.database.sentiment.SentimentFactorDailyRepository
import org.shiroumi.quant_kmp.strategy.daily.SentimentFactorApiLayer
import org.shiroumi.quant_kmp.strategy.daily.model.SentimentFactorSnapshot

/**
 * 研究环境因子数据访问 —— 遵循 Provider ← Snapshot → ApiLayer 三层架构中的 ApiLayer。
 *
 * 从 [SentimentFactorDailyRepository] 读 DB。
 * 生产环境使用 [SentimentFactorSnapshotStore]（内存缓存），
 * 两个实现对外暴露完全一致的 [SentimentFactorApiLayer] 接口。
 */
class DbSentimentFactorApiLayer : SentimentFactorApiLayer {

    override suspend fun snapshot(tradeDate: LocalDate): SentimentFactorSnapshot? {
        val records = SentimentFactorDailyRepository.findBetween(tradeDate, tradeDate)
        return records.firstOrNull()?.toSnapshot()
    }

    override suspend fun history(startDate: LocalDate, endDate: LocalDate): List<SentimentFactorSnapshot> {
        val records = SentimentFactorDailyRepository.findBetween(startDate, endDate)
            .sortedBy { it.tradeDate }
        return records.map { it.toSnapshot() }
    }

    override suspend fun latestTradeDate(): LocalDate? {
        val today = LocalDate(
            java.time.LocalDate.now().year,
            java.time.LocalDate.now().monthValue,
            java.time.LocalDate.now().dayOfMonth,
        )
        var cursor = today
        for (attempt in 0..10) {
            if (snapshot(cursor) != null) return cursor
            cursor = cursor.previousDay()
        }
        return null
    }

    private fun LocalDate.previousDay(): LocalDate {
        val prev = java.time.LocalDate.of(year, monthNumber, dayOfMonth).minusDays(1)
        return LocalDate(prev.year, prev.monthValue, prev.dayOfMonth)
    }
}

/**
 * DB Record → Snapshot 映射。
 * 两个结构字段一致，纯粹类型转换，无任何数据语义变化。
 */
internal fun SentimentFactorDailyRecord.toSnapshot(): SentimentFactorSnapshot =
    SentimentFactorSnapshot(
        tradeDate = tradeDate,
        factors = factors,
        y1Raw = y1Raw,
        y2Raw = y2Raw,
        y3Raw = y3Raw,
        yComposite = yComposite,
        notes = notes,
    )

/**
 * Snapshot → DB Record 逆映射（Study 内部需要 SentimentFactorDailyRecord 格式）。
 */
internal fun SentimentFactorSnapshot.toRecord(): SentimentFactorDailyRecord =
    SentimentFactorDailyRecord(
        tradeDate = tradeDate,
        factors = factors,
        y1Raw = y1Raw,
        y2Raw = y2Raw,
        y3Raw = y3Raw,
        yComposite = yComposite,
        notes = notes,
    )
