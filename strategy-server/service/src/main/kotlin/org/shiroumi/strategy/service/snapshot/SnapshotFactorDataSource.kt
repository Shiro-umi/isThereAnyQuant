package org.shiroumi.strategy.service.snapshot

import kotlinx.datetime.LocalDate
import org.shiroumi.database.sentiment.SentimentFactorDailyRepository
import org.shiroumi.quant_kmp.strategy.daily.FactorDataSource
import org.shiroumi.quant_kmp.strategy.daily.model.FactorSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import utils.logger

private val logger by logger("SnapshotFactorDataSource")

/**
 * 生产环境因子数据源 —— 从内存缓存读取，盘后从 DB 刷新。
 *
 * 内存数据结构：
 * - [cache]: ConcurrentHashMap<LocalDate, FactorSnapshot>  O(1) 单日查找
 * - [sortedDates]: CopyOnWriteArrayList 保持 tradeDate 升序，支持区间查询
 *
 * 并发安全：读写均线程安全。写操作（refresh/append）频率极低（盘后 1 次/天），
 * 读操作（盘中）高频但无锁竞争（ConcurrentHashMap 读不需要锁）。
 *
 * 与研究环境 [DbFactorDataSource] 对外暴露完全一致的 [FactorDataSource] 接口，
 * 确保 Study / StateClassifier / SelectionRuleEngine 在两个环境下调用完全相同的代码路径。
 */
class SnapshotFactorDataSource : FactorDataSource {

    private val cache = ConcurrentHashMap<LocalDate, FactorSnapshot>()
    private val sortedDates = CopyOnWriteArrayList<LocalDate>()

    /** 缓存中的交易日数量 */
    val size: Int get() = cache.size

    /** 缓存中最早的交易日 */
    val earliestDate: LocalDate? get() = sortedDates.firstOrNull()

    /** 缓存中最新的交易日 */
    val latestDate: LocalDate? get() = sortedDates.lastOrNull()

    // ── FactorDataSource 实现 ──

    /**
     * 单日快照查询 —— 纯内存读取，O(1)，无锁。
     * 盘中任意频率调用，性能影响可忽略 (< 1μs)。
     */
    override suspend fun snapshot(tradeDate: LocalDate): FactorSnapshot? =
        cache[tradeDate]

    /**
     * 区间历史查询 —— 按 tradeDate 升序返回。
     * 时间复杂度 O(log N + K)，K = 区间内的交易日数。
     * 盘后低频调用（每天一次）。
     */
    override suspend fun history(startDate: LocalDate, endDate: LocalDate): List<FactorSnapshot> {
        // 二分查找 startDate 位置
        val startIdx = sortedDates.binarySearch(startDate).let { if (it < 0) -it - 1 else it }
        val endIdx = sortedDates.binarySearch(endDate).let { if (it < 0) -it - 2 else it }
        if (startIdx > endIdx || startIdx >= sortedDates.size) return emptyList()

        val result = mutableListOf<FactorSnapshot>()
        for (i in startIdx..endIdx.coerceAtMost(sortedDates.lastIndex)) {
            cache[sortedDates[i]]?.let { result.add(it) }
        }
        return result
    }

    override suspend fun latestTradeDate(): LocalDate? = sortedDates.lastOrNull()

    // ── 缓存管理 ──

    /**
     * 从 DB 全量刷新缓存。
     * 盘后调用一次，通常在 PostMarketPreparationJob 完成后。
     *
     * @param startDate 历史窗口起始日期（研究需要 ≥500 天）
     * @param endDate   截止日期（通常为 today）
     */
    fun refresh(startDate: LocalDate, endDate: LocalDate) {
        logger.info("[SnapshotFactorDataSource] 开始全量刷新 $startDate ~ $endDate ...")
        val startMs = System.currentTimeMillis()

        val records = SentimentFactorDailyRepository.findBetween(startDate, endDate)
            .sortedBy { it.tradeDate }

        cache.clear()
        sortedDates.clear()
        for (record in records) {
            val snapshot = record.toSnapshot()
            cache[snapshot.tradeDate] = snapshot
            sortedDates.add(snapshot.tradeDate)
        }

        val elapsed = System.currentTimeMillis() - startMs
        logger.info("[SnapshotFactorDataSource] 刷新完成: ${records.size} 条记录, ${elapsed}ms, " +
            "区间 ${sortedDates.firstOrNull()} ~ ${sortedDates.lastOrNull()}")
    }

    /**
     * 追加单日快照到缓存（增量更新）。
     * 盘后当日因子落库后调用，比 refresh 更轻量。
     */
    fun append(snapshot: FactorSnapshot) {
        cache[snapshot.tradeDate] = snapshot
        if (!sortedDates.contains(snapshot.tradeDate)) {
            sortedDates.add(snapshot.tradeDate)
            sortedDates.sort()
        }
    }
}

/**
 * DB Record → FactorSnapshot 映射。
 * 与 [DbFactorDataSource.toSnapshot] 完全一致的逻辑。
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
