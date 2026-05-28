package org.shiroumi.strategy.service.snapshot

import kotlinx.datetime.LocalDate
import org.shiroumi.database.sentiment.SentimentFactorDailyRepository
import org.shiroumi.quant_kmp.strategy.daily.SentimentFactorApiLayer
import org.shiroumi.quant_kmp.strategy.daily.model.SentimentFactorSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import utils.logger

private val logger by logger("SentimentFactorSnapshotStore")

/**
 * 市场情绪因子内存快照 —— 遵循 Provider ← Snapshot → ApiLayer 三层架构中的 Snapshot 层。
 *
 * 对应 Candle Data Chain 中的 [CandleSnapshotManager] 角色：
 * 将底层存储的因子数据缓存在内存中，提供 O(1) 无锁读取，支撑盘中高频访问。
 *
 * 内存数据结构：
 * - [cache]: ConcurrentHashMap<LocalDate, SentimentFactorSnapshot>  O(1) 单日查找
 * - [sortedDates]: CopyOnWriteArrayList 保持 tradeDate 升序，支持区间查询
 *
 * 并发安全：读写均线程安全。写操作（refresh/append）频率极低（盘后 1 次/天），
 * 读操作（盘中）高频但无锁竞争（ConcurrentHashMap 读不需要锁）。
 *
 * 与研究环境 [DbSentimentFactorApiLayer] 对外暴露完全一致的 [SentimentFactorApiLayer] 接口，
 * 确保 Study / StateClassifier / SelectionRuleEngine 在两个环境下调用完全相同的代码路径。
 */
class SentimentFactorSnapshotStore : SentimentFactorApiLayer {

    private val cache = ConcurrentHashMap<LocalDate, SentimentFactorSnapshot>()
    private val sortedDates = CopyOnWriteArrayList<LocalDate>()

    val size: Int get() = cache.size
    val earliestDate: LocalDate? get() = sortedDates.firstOrNull()
    val latestDate: LocalDate? get() = sortedDates.lastOrNull()

    // ── SentimentFactorApiLayer 实现 ──

    /** 单日快照查询 —— 纯内存读取，O(1)，无锁。盘中任意频率调用，< 1μs。 */
    override suspend fun snapshot(tradeDate: LocalDate): SentimentFactorSnapshot? =
        cache[tradeDate]

    /** 区间历史查询 —— O(log N + K)。盘后低频调用（每天一次）。 */
    override suspend fun history(startDate: LocalDate, endDate: LocalDate): List<SentimentFactorSnapshot> {
        val startIdx = sortedDates.binarySearch(startDate).let { if (it < 0) -it - 1 else it }
        val endIdx = sortedDates.binarySearch(endDate).let { if (it < 0) -it - 2 else it }
        if (startIdx > endIdx || startIdx >= sortedDates.size) return emptyList()

        val result = mutableListOf<SentimentFactorSnapshot>()
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
     */
    fun refresh(startDate: LocalDate, endDate: LocalDate) {
        logger.info("[SentimentFactorSnapshotStore] 开始全量刷新 $startDate ~ $endDate ...")
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
        logger.info("[SentimentFactorSnapshotStore] 刷新完成: ${records.size} 条记录, ${elapsed}ms, " +
            "区间 ${sortedDates.firstOrNull()} ~ ${sortedDates.lastOrNull()}")
    }

    /**
     * 追加单日快照到缓存（增量更新）。
     * 盘后当日因子落库后调用，比 [refresh] 更轻量。
     */
    fun append(snapshot: SentimentFactorSnapshot) {
        cache[snapshot.tradeDate] = snapshot
        if (!sortedDates.contains(snapshot.tradeDate)) {
            sortedDates.add(snapshot.tradeDate)
            sortedDates.sort()
        }
    }
}

/**
 * DB Record → Snapshot 映射。
 * 与 [DbSentimentFactorApiLayer] 中同名的扩展函数逻辑完全一致。
 */
internal fun org.shiroumi.database.sentiment.SentimentFactorDailyRecord.toSnapshot(): SentimentFactorSnapshot =
    SentimentFactorSnapshot(
        tradeDate = tradeDate,
        factors = factors,
        y1Raw = y1Raw,
        y2Raw = y2Raw,
        y3Raw = y3Raw,
        yComposite = yComposite,
        notes = notes,
    )
