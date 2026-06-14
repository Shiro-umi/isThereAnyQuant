package org.shiroumi.server.runtime.stock

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import model.ws.StockInfoUpdate
import model.ws.StockListUpdatePayload
import model.ws.WsAction
import model.ws.WsEvent
import model.ws.WsTopic
import org.shiroumi.server.data.bootstrap.DataLayerBootstrap
import org.shiroumi.server.data.facade.LatestCandleQuote
import org.shiroumi.server.websocket.AppWebSocketConnectionManager
import utils.logger
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * 股票列表页面上下文运行时服务。
 *
 * 它直接整合现有的 `SET_STOCK_LIST_CONTEXT` 机制：
 * - 前端只按 session 上报当前页面可见股票列表
 * - 服务端监听 Data Layer DAY 快照变更（已含 rt_k 实时覆盖）
 * - 当相关股票的数据准备好或发生变化时，投影回各自 session 的 `STOCK_LIST_UPDATE`
 *
 * 设计约束：
 * 1. 不承担 K 线快照职责
 * 2. 不新增新的页面上下文协议
 * 3. 不自建报价轮询；推送节奏由 Data Layer 的数据同步事件驱动
 */
class StockListContextRuntimeService(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * 读取某只股票最新 DAY 报价的副作用入口。
     * 生产默认走 DataLayer Facade；测试可注入纯内存桩，无需真实 DB / WS。
     */
    private val quoteReader: (String) -> LatestCandleQuote? = { tsCode ->
        DataLayerBootstrap.candleFacade.readLatestQuote(CandleKey(tsCode, CandlePeriod.DAY))
    },
    /**
     * 优先刷新一批股票报价的副作用入口（页面刚建立时主动拉新）。
     */
    private val quoteRefresher: (List<String>) -> Unit = { codes ->
        DataLayerBootstrap.candleFacade.refreshLatestQuotes(codes)
    },
    /**
     * 向指定 session 推送 WS 事件的副作用入口。
     */
    private val eventSender: suspend (DefaultWebSocketServerSession, WsEvent) -> Unit = { session, event ->
        AppWebSocketConnectionManager.sendToSession(session, event)
    }
) {
    private val logger by logger("StockListContextRuntimeService")
    private val started = AtomicBoolean(false)
    private val json = Json { encodeDefaults = true }

    /**
     * 当前所有 session 的股票列表页面上下文（前向索引：session → 可见 code 列表）。
     */
    private val sessionVisibleStocks =
        ConcurrentHashMap<DefaultWebSocketServerSession, List<String>>()

    /**
     * 反向索引：code → 关注该 code 的 session 集合。
     *
     * 由 [updateVisibleStocks] / [cleanupSession] 与前向索引同步维护，
     * 让 [pushChangedQuotes] 能按变更 code 直接反查受影响 session，
     * 把热路径从 O(sessions × visibleCodesPerSession) 降到 O(changedDayCodes × sessionsPerCode)。
     *
     * value 为线程安全 Set（ConcurrentHashMap.newKeySet），
     * 因此对集合本身的增删无需额外加锁，配合外层 ConcurrentHashMap 的原子
     * compute/computeIfPresent 即可消除空集合泄漏与并发竞态。
     */
    private val codeToSessions =
        ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    private val currentPushJobs =
        ConcurrentHashMap<DefaultWebSocketServerSession, kotlinx.coroutines.Job>()

    /**
     * 最近一次 DataLayer 变更事件得到的轻量报价快照，带 LRU 上限。
     *
     * 使用 access-order LinkedHashMap 驱逐最久未访问的条目，
     * 防止冷门股报价占用内存无限增长。
     */
    private val quoteCache: MutableMap<String, LatestCandleQuote> = Collections.synchronizedMap(
        object : LinkedHashMap<String, LatestCandleQuote>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LatestCandleQuote>?) =
                size > 1024
        }
    )
    private val priorityRefreshTimestamps = ConcurrentHashMap<String, Long>()
    private val priorityRefreshCooldownMs = 5.seconds.inWholeMilliseconds

    /**
     * 幂等初始化入口。
     */
    fun initialize() {
        if (!started.compareAndSet(false, true)) return
        DataLayerBootstrap.registerDataSyncListener { changedKeys ->
            onDataSync(changedKeys)
        }
        logger.info("StockListContextRuntimeService: 已初始化，等待 DataLayer 快照变更")
    }

    /**
     * 更新某个 session 的可见股票上下文。
     *
     * 反向索引与前向索引同步维护，刻意「先增后删」：
     * 先为新增 code 建立反向边，再移除旧 code 的反向边，
     * 保证任意中间时刻 [pushChangedQuotes] 反查到的 session 集合不会漏掉真正在看的 code。
     */
    fun updateVisibleStocks(session: DefaultWebSocketServerSession, tsCodes: List<String>) {
        val normalizedCodes = tsCodes.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val previousCodes = if (normalizedCodes.isEmpty()) {
            sessionVisibleStocks.remove(session)
        } else {
            sessionVisibleStocks.put(session, normalizedCodes)
        } ?: emptyList()
        syncReverseIndex(session, previousCodes, normalizedCodes)
        logger.info(
            "StockListContextRuntimeService: Session ${session.hashCode()} 更新可见列表, count=${tsCodes.size}"
        )
        currentPushJobs.remove(session)?.cancel()
        if (normalizedCodes.isNotEmpty()) {
            val job = scope.launch {
                runCatching {
                    pushCurrentQuotes(session, normalizedCodes)
                    refreshContextQuotes(normalizedCodes)
                }.onFailure { error ->
                    logger.error("StockListContextRuntimeService: 股票列表当前快照推送异常: ${error.message}")
                }
            }
            currentPushJobs[session] = job
            job.invokeOnCompletion {
                currentPushJobs.remove(session, job)
            }
        }
    }

    /**
     * 连接断开时移除 session 上下文。
     *
     * 遍历该 session 旧的可见列表，把每条反向边从 [codeToSessions] 移除，
     * 集合空了顺手删 key，保证反向索引不残留孤立条目。
     */
    fun cleanupSession(session: DefaultWebSocketServerSession) {
        val previousCodes = sessionVisibleStocks.remove(session) ?: emptyList()
        previousCodes.forEach { code -> removeReverseEdge(code, session) }
        currentPushJobs.remove(session)?.cancel()
    }

    /**
     * 把 session 的前向变更（旧列表 → 新列表）同步到反向索引。
     * 先为新增 code 建边，再删旧 code 的边，避免出现「两边都查不到」的窗口。
     */
    private fun syncReverseIndex(
        session: DefaultWebSocketServerSession,
        previousCodes: List<String>,
        currentCodes: List<String>
    ) {
        val previousSet = previousCodes.toHashSet()
        val currentSet = currentCodes.toHashSet()
        currentCodes.forEach { code ->
            if (code !in previousSet) addReverseEdge(code, session)
        }
        previousCodes.forEach { code ->
            if (code !in currentSet) removeReverseEdge(code, session)
        }
    }

    /**
     * 原子地为 code 建立一条指向 session 的反向边。
     * compute 保证「取/建集合 + 插入」在外层 map 上原子完成，无 TOCTOU。
     */
    private fun addReverseEdge(code: String, session: DefaultWebSocketServerSession) {
        codeToSessions.compute(code) { _, existing ->
            (existing ?: ConcurrentHashMap.newKeySet()).apply { add(session) }
        }
    }

    /**
     * 原子地移除 code → session 的反向边，集合空了删 key（杜绝空集合泄漏）。
     */
    private fun removeReverseEdge(code: String, session: DefaultWebSocketServerSession) {
        codeToSessions.computeIfPresent(code) { _, sessions ->
            sessions.remove(session)
            if (sessions.isEmpty()) null else sessions
        }
    }

    /**
     * 反向索引只读快照，仅供同模块测试校验前向/反向索引一致性。
     * 返回深拷贝，外部无法借此破坏内部状态。
     */
    internal fun reverseIndexSnapshot(): Map<String, Set<DefaultWebSocketServerSession>> =
        codeToSessions.mapValues { (_, sessions) -> sessions.toSet() }

    /**
     * DataLayer 变更回调统一入口：与 [initialize] 注册的监听体一致，
     * 抽出来既保证生产路径单处实现，又给同模块测试一个可驱动的真实入口。
     */
    internal suspend fun onDataSync(changedKeys: Collection<CandleKey>) {
        runCatching {
            pushChangedQuotes(changedKeys)
        }.onFailure { error ->
            logger.error("StockListContextRuntimeService: 股票列表快照推送异常: ${error.message}")
        }
    }

    private suspend fun pushChangedQuotes(changedKeys: Collection<CandleKey>) {
        if (sessionVisibleStocks.isEmpty()) return

        // 只保留「确实有 session 在看」的变更 DAY code：直接以反向索引为白名单，
        // 不再展开全部 session 求并集，避免 O(sessions × visibleCodesPerSession) 的扫描。
        val changedDayCodes = changedKeys
            .asSequence()
            .filter { it.period == CandlePeriod.DAY }
            .map { it.tsCode }
            .filter { codeToSessions.containsKey(it) }
            .toSet()
        if (changedDayCodes.isEmpty()) return

        changedDayCodes.forEach { tsCode ->
            readAndCacheQuote(tsCode)
        }

        // 反查每个变更 code 牵动的 session，按 session 聚合其命中的 code（自动去重）。
        // 复杂度 O(changedDayCodes × sessionsPerCode)，与总 session 数和单 session 列表长度解耦。
        val affected = LinkedHashMap<DefaultWebSocketServerSession, MutableList<String>>()
        changedDayCodes.forEach { code ->
            codeToSessions[code]?.forEach { session ->
                affected.getOrPut(session) { mutableListOf() }.add(code)
            }
        }

        affected.forEach { (session, changedVisibleCodes) ->
            pushCachedQuotes(session, changedVisibleCodes)
        }
    }

    /**
     * 页面上下文刚建立时，服务端主动下发当前已准备好的报价。
     * 如果 DAY 快照还没准备好，后续 DataLayer 变更事件会再推送。
     */
    private suspend fun pushCurrentQuotes(session: DefaultWebSocketServerSession, tsCodes: List<String>) {
        tsCodes.forEach { tsCode ->
            readAndCacheQuote(tsCode)
        }
        if (sessionVisibleStocks[session] != tsCodes) return
        pushCachedQuotes(session, tsCodes)
    }

    private fun readAndCacheQuote(tsCode: String) {
        quoteReader(tsCode)?.let { quoteCache[tsCode] = it }
    }

    private fun refreshContextQuotes(tsCodes: List<String>) {
        val now = System.currentTimeMillis()
        val refreshCodes = tsCodes.filter { code ->
            val previous = priorityRefreshTimestamps[code] ?: Long.MIN_VALUE
            if (now - previous < priorityRefreshCooldownMs) {
                false
            } else {
                priorityRefreshTimestamps[code] = now
                true
            }
        }
        if (refreshCodes.isEmpty()) return
        quoteRefresher(refreshCodes)
    }

    private suspend fun pushCachedQuotes(session: DefaultWebSocketServerSession, tsCodes: List<String>) {
        if (tsCodes.isEmpty()) return
        val updates = tsCodes.mapNotNull { code ->
            quoteCache[code]?.toWsUpdate()
        }
        if (updates.isEmpty()) return
        eventSender(
            session,
            WsEvent(
                topic = WsTopic.STOCK_LIST_UPDATE,
                action = WsAction.UPDATE,
                payload = json.encodeToString(StockListUpdatePayload(stocks = updates))
            )
        )
    }

}

/**
 * 将 Facade 层最新报价转为 WS 推送协议。
 */
private fun LatestCandleQuote.toWsUpdate() = StockInfoUpdate(
    code = tsCode,
    latestPrice = latestPrice,
    changeAmount = changeAmount,
    changePercent = changePercent,
    volume = volume,
    turnover = turnover
)
