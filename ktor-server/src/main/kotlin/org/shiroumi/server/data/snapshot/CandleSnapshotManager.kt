package org.shiroumi.server.data.snapshot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate as KxLocalDate
import model.Candle
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import org.shiroumi.database.common.repository.TradingCalendarRepository
import org.shiroumi.database.stock.StockReader
import org.shiroumi.server.data.api.ApiTask
import org.shiroumi.server.data.api.CandleApiLayer
import org.shiroumi.server.data.api.InterfaceId
import org.shiroumi.server.data.api.TaskParams
import utils.logger
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * `CandleSnapshotManager` 是新数据层里的 K 线快照中心。
 *
 * 设计边界：
 * - 负责缓存、缺口回填、写回、版本推进
 * - 不负责订阅者管理
 * - 不负责 payload 组装
 *
 * 为什么必须独立存在：
 * - 旧 `CandleProvider` 把 H/R/merged 都包进 provider，自然会把"数据真相"和"订阅映射"重新绑死
 * - 新架构需要先有一个独立数据中心，再由 provider 从这里读取并做 topic 级映射
 */
class CandleSnapshotManager(
    private val apiLayer: CandleApiLayer,
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
    private val dayHistoryLimit: Int = 500,
    private val lruCapacity: Int = 1000
) {
    private val logger by logger("CandleSnapshotManager")
    private val initialized = AtomicBoolean(false)
    private val versionCounter = AtomicLong(0L)
    // internal 而非 private：同模块单测据此预置 minute 缓存，模拟异步回填完成后的缓存状态，
    // 从而在不启动后台 worker 的前提下覆盖 readMinuteSnapshot 的命中 / 未命中 / 复用语义。
    internal val caches = PeriodCache(lruCapacity)
    private val dayManager = DaySnapshotManager(dayHistoryLimit)
    private val minuteHistoryLoads = ConcurrentHashMap<String, AtomicBoolean>()
    private val minuteRealtimeLoads = ConcurrentHashMap<String, AtomicBoolean>()
    private val periodicLoads = ConcurrentHashMap<String, AtomicBoolean>()
    private val changedKeys = ConcurrentLinkedQueue<CandleKey>()

    suspend fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        dayManager.initialize()
    }

    fun activeSymbols(): List<String> = dayManager.activeSymbols()

    fun refreshDayUniverse() {
        dayManager.refreshUniverse()
    }

    /**
     * 读取某个 key 的快照视图。
     *
     * 行为约束：
     * - 若缓存已命中，返回当前视图
     * - 若缓存缺口存在，会异步提交回填任务，但本次读取仍可能返回 null
     * - provider / CLI 必须容忍"本次暂无数据，下次就有"的 miss 语义
     */
    fun readSnapshot(key: CandleKey): CandleSnapshotState? = when (key.period) {
        CandlePeriod.DAY -> dayManager.snapshotOf(key.tsCode)
        CandlePeriod.MIN_5,
        CandlePeriod.MIN_15,
        CandlePeriod.MIN_30,
        CandlePeriod.MIN_60 -> readMinuteSnapshot(key)
        CandlePeriod.WEEK,
        CandlePeriod.MONTH -> readPeriodicSnapshot(key)
    }

    fun readMergedCandles(key: CandleKey): List<Candle> = readSnapshot(key)?.candles.orEmpty()

    fun scheduleDayRealtimeUpdate() {
        when (val phase = resolveDayRealtimePhase()) {
            DayRealtimePhase.PURE_HISTORY -> return
            DayRealtimePhase.CONTINUOUS_REALTIME -> dayManager.scheduleContinuousRealtimeUpdate()
            is DayRealtimePhase.SINGLE_REALTIME_PASS -> dayManager.scheduleSingleRealtimePass(phase.passKey)
        }
    }

    /**
     * 对前端当前关注的股票做一次优先 DAY 实时刷新。
     *
     * 全市场轮询仍然负责兜底推进；这个入口只服务页面上下文中的小批量股票，
     * 用于补偿全市场通配符刷新尚未返回或单股短时缺失的窗口。
     */
    fun scheduleDayRealtimeUpdate(tsCodes: List<String>) {
        if (resolveDayRealtimePhase() == DayRealtimePhase.PURE_HISTORY) return
        val normalizedCodes = tsCodes
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedCodes.isEmpty()) return
        normalizedCodes.chunked(dayManager.batchSize).forEach { batch ->
            dayManager.submitPriorityRealtimeBatch(batch)
        }
    }

    fun drainChangedKeys(): Set<CandleKey> {
        val drained = LinkedHashSet<CandleKey>()
        while (true) {
            val key = changedKeys.poll() ?: break
            drained.add(key)
        }
        return drained
    }

    private fun readMinuteSnapshot(key: CandleKey): CandleSnapshotState? {
        // 历史窗口 H 与实时窗口 R 各查一次缓存即可：本函数同步执行，回填走后台 worker，
        // 回填结果只改缓存内部状态，对当前栈帧里的 history / realtime 引用不可见，
        // 因此第二次重查必然得到与首次完全相同的对象引用，纯属冗余的 LRU get（含 synchronized
        // 锁与 accessOrder 链表重排）。这里直接复用首次查询结果，把每次读取的 LRU get 从 4 次降到 2 次。
        val history = caches.minuteHistory(key.period)[key.tsCode]
        if (history == null) {
            submitMinuteHistoryLoad(key)
        }
        val realtime = caches.minuteRealtime(key.period).get(key.tsCode)
        if (realtime == null) {
            submitMinuteRealtimeLoad(key)
        }
        val historyWindow = history.orEmpty()
        val realtimeWindow = realtime.orEmpty()
        if (historyWindow.isEmpty() && realtimeWindow.isEmpty()) return null
        val merged = historyWindow + realtimeWindow
        return publishIfChanged(
            key = key,
            merged = merged,
            sourceTag = "minute:${key.period.name}"
        )
    }

    private fun readPeriodicSnapshot(key: CandleKey): CandleSnapshotState? {
        val cache = when (key.period) {
            CandlePeriod.WEEK -> caches.weekData
            CandlePeriod.MONTH -> caches.monthData
            else -> error("不支持的周期: ${key.period}")
        }
        val cached = cache.get(key.tsCode)
        if (cached == null) {
            submitPeriodicLoad(key)
            return caches.currentSnapshot(key.id)
        }
        return publishIfChanged(
            key = key,
            merged = cached,
            sourceTag = "periodic:${key.period.name}"
        )
    }

    private fun submitMinuteHistoryLoad(key: CandleKey) {
        val loadingFlag = minuteHistoryLoads.computeIfAbsent(key.id) { AtomicBoolean(false) }
        if (!loadingFlag.compareAndSet(false, true)) return
        val submitted = apiLayer.submit(
            ApiTask(
                interfaceId = InterfaceId.STK_MINS,
                params = TaskParams.StkMinsParams(
                    tsCode = key.tsCode,
                    period = key.period
                ),
                callback = { result ->
                loadingFlag.set(false)
                minuteHistoryLoads.remove(key.id)
                result.onSuccess { candles ->
                    caches.minuteHistory(key.period)[key.tsCode] = candles
                    publishIfChanged(key, candles + caches.minuteRealtime(key.period).get(key.tsCode).orEmpty(), "minute-history")
                }.onFailure { error ->
                    logger.warning("分钟历史快照回填失败: key=${key.id}, error=${error.message}")
                }
                }
            )
        )
        if (!submitted) releaseLoadingFlag(minuteHistoryLoads, key, "minute-history")
    }

    private fun submitMinuteRealtimeLoad(key: CandleKey) {
        val loadingFlag = minuteRealtimeLoads.computeIfAbsent(key.id) { AtomicBoolean(false) }
        if (!loadingFlag.compareAndSet(false, true)) return
        val submitted = apiLayer.submit(
            ApiTask(
                interfaceId = InterfaceId.RT_MIN_DAILY,
                params = TaskParams.RtMinDailyParams(
                    tsCode = key.tsCode,
                    period = key.period
                ),
                callback = { result ->
                loadingFlag.set(false)
                minuteRealtimeLoads.remove(key.id)
                result.onSuccess { candles ->
                    caches.minuteRealtime(key.period).put(key.tsCode, candles)
                    publishIfChanged(key, caches.minuteHistory(key.period)[key.tsCode].orEmpty() + candles, "minute-realtime")
                }.onFailure { error ->
                    logger.warning("分钟实时快照回填失败: key=${key.id}, error=${error.message}")
                }
                }
            )
        )
        if (!submitted) releaseLoadingFlag(minuteRealtimeLoads, key, "minute-realtime")
    }

    private fun submitPeriodicLoad(key: CandleKey) {
        val loadingFlag = periodicLoads.computeIfAbsent(key.id) { AtomicBoolean(false) }
        if (!loadingFlag.compareAndSet(false, true)) return
        val interfaceId = if (key.period == CandlePeriod.WEEK) InterfaceId.WEEKLY else InterfaceId.MONTHLY
        val submitted = apiLayer.submit(
            ApiTask(
                interfaceId = interfaceId,
                params = TaskParams.WeeklyMonthlyParams(tsCode = key.tsCode),
                callback = { result ->
                loadingFlag.set(false)
                periodicLoads.remove(key.id)
                result.onSuccess { candles ->
                    when (key.period) {
                        CandlePeriod.WEEK -> caches.weekData.put(key.tsCode, candles)
                        CandlePeriod.MONTH -> caches.monthData.put(key.tsCode, candles)
                        else -> error("不支持的周期: ${key.period}")
                    }
                    publishIfChanged(key, candles, "periodic-load")
                }.onFailure { error ->
                    logger.warning("周/月快照回填失败: key=${key.id}, error=${error.message}")
                }
                }
            )
        )
        if (!submitted) releaseLoadingFlag(periodicLoads, key, "periodic")
    }

    // apiLayer.submit 返回 false 表示任务未入队（channel 满或已关闭），此时 callback 永远不会触发。
    // 必须主动释放 loading flag，否则该 key 的后续快照读取会永远跳过回填。
    private fun releaseLoadingFlag(
        registry: ConcurrentHashMap<String, AtomicBoolean>,
        key: CandleKey,
        sourceTag: String
    ) {
        registry[key.id]?.set(false)
        registry.remove(key.id)
        logger.warning("快照任务入队失败，已重置 loading flag: source=$sourceTag, key=${key.id}")
    }

    private fun publishIfChanged(
        key: CandleKey,
        merged: List<Candle>,
        sourceTag: String
    ): CandleSnapshotState {
        val current = caches.currentSnapshot(key.id)
        if (current != null && sameWindow(current.candles, merged)) {
            return current
        }
        val next = CandleSnapshotState(
            key = key,
            candles = merged,
            version = versionCounter.incrementAndGet(),
            updatedAt = System.currentTimeMillis(),
            sourceTag = sourceTag,
            hasRealtimeWindow = key.period == CandlePeriod.DAY ||
                key.period in CandleApiLayer.SUPPORTED_MINUTE_PERIODS
        )
        caches.putSnapshot(key.id, next)
        markChanged(key)
        return next
    }

    private fun markChanged(key: CandleKey) {
        changedKeys.offer(key)
    }

    private fun sameWindow(current: List<Candle>, next: List<Candle>): Boolean {
        if (current.size != next.size) return false
        val currentLast = current.lastOrNull() ?: return next.isEmpty()
        val nextLast = next.lastOrNull() ?: return false
        return currentLast.date == nextLast.date &&
            currentLast.tradeTime == nextLast.tradeTime &&
            currentLast.open == nextLast.open &&
            currentLast.high == nextLast.high &&
            currentLast.low == nextLast.low &&
            currentLast.close == nextLast.close &&
            currentLast.volume == nextLast.volume &&
            currentLast.turnoverReal == nextLast.turnoverReal
    }

    private fun resolveDayRealtimePhase(now: ZonedDateTime = ZonedDateTime.now(zoneId)): DayRealtimePhase {
        val dayOfWeek = now.dayOfWeek
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return DayRealtimePhase.PURE_HISTORY
        }

        val today = now.toLocalDate()
        val effectiveTradeDate = runCatching {
            TradingCalendarRepository.findLatestTradingDateOnOrBefore(
                KxLocalDate(today.year, today.monthValue, today.dayOfMonth)
            )
        }.getOrNull() ?: return DayRealtimePhase.PURE_HISTORY
        if (effectiveTradeDate != KxLocalDate(today.year, today.monthValue, today.dayOfMonth)) {
            return DayRealtimePhase.PURE_HISTORY
        }

        val time = now.toLocalTime()
        return when {
            time.isBefore(LocalTime.of(9, 15)) -> DayRealtimePhase.PURE_HISTORY
            time.isBefore(LocalTime.of(11, 30)) -> DayRealtimePhase.CONTINUOUS_REALTIME
            time.isBefore(LocalTime.of(13, 0)) -> DayRealtimePhase.SINGLE_REALTIME_PASS("${effectiveTradeDate}:lunch")
            time.isBefore(LocalTime.of(15, 0)) -> DayRealtimePhase.CONTINUOUS_REALTIME
            isStockDailyUpdated(effectiveTradeDate) -> DayRealtimePhase.PURE_HISTORY
            else -> DayRealtimePhase.SINGLE_REALTIME_PASS("${effectiveTradeDate}:post-market")
        }
    }

    private fun isStockDailyUpdated(tradeDate: KxLocalDate): Boolean =
        runCatching { TradingCalendarRepository.isStockDailyUpdated(tradeDate) }.getOrDefault(false)

    private sealed interface DayRealtimePhase {
        data object PURE_HISTORY : DayRealtimePhase
        data object CONTINUOUS_REALTIME : DayRealtimePhase
        data class SINGLE_REALTIME_PASS(val passKey: String) : DayRealtimePhase
    }

    private inner class DaySnapshotManager(
        private val historyLimit: Int
    ) {
        private val symbolsFlow = MutableStateFlow<List<String>>(emptyList())
        private val snapshots = ConcurrentHashMap<String, CandleSnapshotState?>()

        /**
         * 全市场实时日 K 单批 priority 调用的最大 ts_code 数量。
         * 通配符全市场调用本身一次接口就能覆盖整个市场；priority 通道服务前端页面可见股票，
         * 列表通常不超过几十只，这里只是个兜底上限。
         */
        val batchSize = 200

        /**
         * 全市场实时日 K 调用使用的通配符集合，对应 A 股所有交易板块：
         * - 6*.SH 沪市主板
         * - 3*.SZ 创业板
         * - 688*.SH 科创板
         * - 0*.SZ 深市主板/中小板
         * - 8*.BJ 北交所
         */
        private val wholeMarketWildcards: List<String> =
            listOf("6*.SH", "3*.SZ", "688*.SH", "0*.SZ", "8*.BJ")

        private val continuousInFlight = AtomicBoolean(false)
        private val singlePassInFlight = AtomicBoolean(false)
        private var singlePassKey: String? = null
        private var singlePassCompleted = false

        suspend fun initialize() {
            refreshUniverse()
        }

        fun refreshUniverse() {
            val activeSymbols = StockReader.getAllSymbols()
            val windows = StockReader.getAllStockDailyWindows(limit = historyLimit)
            val now = System.currentTimeMillis()
            activeSymbols.forEach { tsCode ->
                val state = CandleSnapshotState(
                    key = CandleKey(tsCode, CandlePeriod.DAY),
                    candles = windows[tsCode].orEmpty(),
                    version = versionCounter.incrementAndGet(),
                    updatedAt = now,
                    sourceTag = "day-db",
                    hasRealtimeWindow = true
                )
                snapshots[tsCode] = state
                val key = CandleKey(tsCode, CandlePeriod.DAY)
                caches.putSnapshot(key.id, state)
                markChanged(key)
            }
            symbolsFlow.value = activeSymbols
        }

        fun activeSymbols(): List<String> = symbolsFlow.value

        fun snapshotOf(tsCode: String): CandleSnapshotState? = snapshots[tsCode]

        /**
         * 连续交易时段的全市场实时日 K 调度。
         *
         * 单次通配符调用即可覆盖整个市场；这里用 [continuousInFlight] 防止上一次调用尚未返回时
         * 又被 SyncLooper 触发，避免同一接口配额被同一批数据并发占用。
         */
        fun scheduleContinuousRealtimeUpdate() {
            if (symbolsFlow.value.isEmpty()) return
            if (!continuousInFlight.compareAndSet(false, true)) return
            val submitted = submitWholeMarketRealtime { _ ->
                continuousInFlight.set(false)
            }
            if (!submitted) {
                continuousInFlight.set(false)
            }
        }

        /**
         * 单次实时 pass（午休 / 盘后）。
         *
         * 与 [scheduleContinuousRealtimeUpdate] 的区别只在于：成功执行一次后用 [singlePassKey]
         * 锁定阶段标识，相同标识不再重复触发；阶段切换（如午休→下午开盘）通过传入新的 [passKey] 自动重置。
         */
        fun scheduleSingleRealtimePass(passKey: String) {
            if (symbolsFlow.value.isEmpty()) return
            if (singlePassKey != passKey) {
                singlePassKey = passKey
                singlePassCompleted = false
                singlePassInFlight.set(false)
            }
            if (singlePassCompleted || singlePassInFlight.get()) return
            if (!singlePassInFlight.compareAndSet(false, true)) return

            val submitted = submitWholeMarketRealtime { success ->
                singlePassInFlight.set(false)
                if (success) {
                    singlePassCompleted = true
                }
            }
            if (!submitted) {
                singlePassInFlight.set(false)
            }
        }

        /**
         * 提交一次全市场通配符实时日 K 拉取。
         */
        private fun submitWholeMarketRealtime(
            onComplete: ((success: Boolean) -> Unit)? = null
        ): Boolean {
            return apiLayer.submit(
                ApiTask(
                    interfaceId = InterfaceId.RT_K,
                    params = TaskParams.RtKParams.ByWildcards(wholeMarketWildcards),
                    callback = { result ->
                        result.onSuccess { candles ->
                            val now = System.currentTimeMillis()
                            val activeSymbols = symbolsFlow.value.toHashSet()
                            candles.forEach { realtime ->
                                if (realtime.tsCode in activeSymbols) {
                                    mergeRealtime(realtime, now)
                                }
                            }
                            onComplete?.invoke(true)
                        }.onFailure { error ->
                            logger.info("DAY 实时全市场刷新未命中或失败: error=${error.message}")
                            onComplete?.invoke(false)
                        }
                    }
                )
            )
        }

        /**
         * 针对前端页面可见股票的优先 ts_code 列表刷新。
         *
         * 全市场通配符调度已经在后台稳定推进；这里只在用户操作后立刻补一次目标股票，
         * 用于补偿全市场通配符刷新尚未返回或单股短时缺失的窗口。
         */
        fun submitPriorityRealtimeBatch(batch: List<String>): Boolean {
            if (batch.isEmpty()) return false
            return apiLayer.submit(
                ApiTask(
                    interfaceId = InterfaceId.RT_K,
                    params = TaskParams.RtKParams.ByCodes(batch),
                    callback = { result ->
                        result.onSuccess { candles ->
                            val now = System.currentTimeMillis()
                            candles.forEach { realtime ->
                                mergeRealtime(realtime, now)
                            }
                        }.onFailure { error ->
                            logger.info("DAY 优先实时批次刷新未完成: batch=${batch.size}, error=${error.message}")
                        }
                    }
                )
            )
        }

        private fun mergeRealtime(realtime: Candle, now: Long) {
            val key = CandleKey(realtime.tsCode, CandlePeriod.DAY)
            val current = snapshots[realtime.tsCode]
            val merged = mergeRealtimeIntoWindow(
                current = current?.candles.orEmpty(),
                realtime = realtime,
                historyLimit = historyLimit
            ) ?: return
            if (current != null && sameWindow(current.candles, merged)) return
            val next = CandleSnapshotState(
                key = key,
                candles = merged,
                version = versionCounter.incrementAndGet(),
                updatedAt = now,
                sourceTag = "day-rt",
                hasRealtimeWindow = true
            )
            snapshots[realtime.tsCode] = next
            caches.putSnapshot(key.id, next)
            markChanged(key)
        }
    }
}

/**
 * 把一根实时日 K 增量合并进既有升序窗口。
 *
 * 既有窗口由 SQL `ORDER BY trade_date ASC`（[StockReader.getAllStockDailyWindows]）
 * 与历史回填共同维持升序，实时日 K 只可能落在窗口尾部（当日盘中刷新或新交易日），
 * 据此把原先每股 `filterNot + sortedBy + takeLast`（O(n log n)）替换为
 * 末根二路决策（O(1)~O(n)）：
 *
 * - 末根同日：盘中刷新，原地替换末根（[List.dropLast] + 追加），长度不变
 * - 实时日更晚：新交易日，直接追加；停牌空洞导致的大跨度跳变同样保持升序
 * - 实时日更早：网络/队列乱序或历史重传，返回 `null` 表示忽略，避免破坏升序
 * - 窗口为空：尚未回填，直接以实时 K 作为窗口起点
 *
 * 末根替换长度不变、追加后 +1，统一由 [List.takeLast] 收口在 [historyLimit] 内；
 * 仅在确实超限时才分配新列表，未超限直接复用合并结果，避免多余拷贝。
 * 返回 `null` 专门表示乱序忽略，调用方据此跳过版本推进与发布，与旧实现
 * "乱序 K 被 filterNot 留下又被 sortedBy 排到中间、再被 takeLast 裁掉" 的净效果一致。
 */
internal fun mergeRealtimeIntoWindow(
    current: List<Candle>,
    realtime: Candle,
    historyLimit: Int
): List<Candle>? {
    val last = current.lastOrNull()
    val merged = when {
        last == null -> listOf(realtime)
        realtime.date == last.date -> current.dropLast(1) + realtime
        realtime.date > last.date -> current + realtime
        else -> return null
    }
    return if (merged.size > historyLimit) merged.takeLast(historyLimit) else merged
}

data class CandleSnapshotState(
    val key: CandleKey,
    val candles: List<Candle>,
    val version: Long,
    val updatedAt: Long,
    val sourceTag: String,
    val hasRealtimeWindow: Boolean
)

/**
 * 分区缓存与快照缓存的统一管理层。
 *
 * 所有缓存均使用 LRU 淘汰，防止无人关注的代码长期占据内存。
 * minute realtime 和 week/month 容量上限为 [lruCapacity]（默认 1000），
 * minute history 和 snapshot 同理。
 */
class PeriodCache(lruCapacity: Int) {
    private val snapshotCache = SynchronizedLruCache<String, CandleSnapshotState>(lruCapacity)

    private val min5History = SynchronizedLruCache<String, List<Candle>>(lruCapacity)
    private val min15History = SynchronizedLruCache<String, List<Candle>>(lruCapacity)
    private val min30History = SynchronizedLruCache<String, List<Candle>>(lruCapacity)
    private val min60History = SynchronizedLruCache<String, List<Candle>>(lruCapacity)

    private val min5Realtime = SynchronizedLruCache<String, List<Candle>>(lruCapacity)
    private val min15Realtime = SynchronizedLruCache<String, List<Candle>>(lruCapacity)
    private val min30Realtime = SynchronizedLruCache<String, List<Candle>>(lruCapacity)
    private val min60Realtime = SynchronizedLruCache<String, List<Candle>>(lruCapacity)

    val weekData = SynchronizedLruCache<String, List<Candle>>(lruCapacity)
    val monthData = SynchronizedLruCache<String, List<Candle>>(lruCapacity)

    fun minuteHistory(period: CandlePeriod): SynchronizedLruCache<String, List<Candle>> = when (period) {
        CandlePeriod.MIN_5 -> min5History
        CandlePeriod.MIN_15 -> min15History
        CandlePeriod.MIN_30 -> min30History
        CandlePeriod.MIN_60 -> min60History
        else -> error("不支持的分钟周期: $period")
    }

    fun minuteRealtime(period: CandlePeriod): SynchronizedLruCache<String, List<Candle>> = when (period) {
        CandlePeriod.MIN_5 -> min5Realtime
        CandlePeriod.MIN_15 -> min15Realtime
        CandlePeriod.MIN_30 -> min30Realtime
        CandlePeriod.MIN_60 -> min60Realtime
        else -> error("不支持的分钟周期: $period")
    }

    fun putSnapshot(id: String, state: CandleSnapshotState) {
        snapshotCache[id] = state
    }

    fun currentSnapshot(id: String): CandleSnapshotState? = snapshotCache[id]
}

class SynchronizedLruCache<K, V>(private val capacity: Int) {
    private val delegate = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                return size > capacity
            }
        }
    )

    operator fun get(key: K): V? = delegate[key]

    operator fun set(key: K, value: V) {
        put(key, value)
    }

    fun put(key: K, value: V) {
        delegate[key] = value
    }
}
