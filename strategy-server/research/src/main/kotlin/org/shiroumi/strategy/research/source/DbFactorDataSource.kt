package org.shiroumi.strategy.research.source

import kotlinx.datetime.LocalDate
import org.shiroumi.database.sentiment.SentimentFactorDailyRepository
import org.shiroumi.quant_kmp.strategy.daily.FactorDataSource
import org.shiroumi.quant_kmp.strategy.daily.model.FactorSnapshot

/**
 * 研究环境因子数据源 —— 从 [SentimentFactorDailyRepository] 读 DB。
 *
 * 生产环境使用 [SnapshotFactorDataSource]（内存缓存），两个实现对外暴露完全一致的接口。
 */
class DbFactorDataSource : FactorDataSource {

    override suspend fun snapshot(tradeDate: LocalDate): FactorSnapshot? {
        val records = SentimentFactorDailyRepository.findBetween(tradeDate, tradeDate)
        return records.firstOrNull()?.toSnapshot()
    }

    override suspend fun history(startDate: LocalDate, endDate: LocalDate): List<FactorSnapshot> {
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
        // 从今天往前找最近的有数据的交易日
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
 * DB Record → FactorSnapshot 映射。
 * 两个结构字段一致，这里做纯粹的类型转换，无任何数据语义变化。
 */
internal fun org.shiroumi.database.sentiment.SentimentFactorDailyRecord.toSnapshot(): FactorSnapshot =
    FactorSnapshot(
        tradeDate = tradeDate,
        factors = factors,
        y1Raw = y1Raw,
        y2Raw = y2Raw,
        y3Raw = y3Raw,
        yComposite = yComposite,
        notes = notes,
    )

/**
 * FactorSnapshot → DB Record 逆映射（Study 内部需要 SentimentFactorDailyRecord 格式）。
 */
internal fun FactorSnapshot.toRecord(): org.shiroumi.database.sentiment.SentimentFactorDailyRecord =
    org.shiroumi.database.sentiment.SentimentFactorDailyRecord(
        tradeDate = tradeDate,
        factors = factors,
        y1Raw = y1Raw,
        y2Raw = y2Raw,
        y3Raw = y3Raw,
        yComposite = yComposite,
        notes = notes,
    )
