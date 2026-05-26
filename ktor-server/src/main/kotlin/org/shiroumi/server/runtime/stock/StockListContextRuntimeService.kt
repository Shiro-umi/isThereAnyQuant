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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val logger by logger("StockListContextRuntimeService")
    private val started = AtomicBoolean(false)
    private val json = Json { encodeDefaults = true }

    /**
     * 当前所有 session 的股票列表页面上下文。
     */
    private val sessionVisibleStocks =
        ConcurrentHashMap<DefaultWebSocketServerSession, List<String>>()
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
            runCatching {
                pushChangedQuotes(changedKeys)
            }.onFailure { error ->
                logger.error("StockListContextRuntimeService: 股票列表快照推送异常: ${error.message}")
            }
        }
        logger.info("StockListContextRuntimeService: 已初始化，等待 DataLayer 快照变更")
    }

    /**
     * 更新某个 session 的可见股票上下文。
     */
    fun updateVisibleStocks(session: DefaultWebSocketServerSession, tsCodes: List<String>) {
        val normalizedCodes = tsCodes.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (normalizedCodes.isEmpty()) {
            sessionVisibleStocks.remove(session)
        } else {
            sessionVisibleStocks[session] = normalizedCodes
        }
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
     */
    fun cleanupSession(session: DefaultWebSocketServerSession) {
        sessionVisibleStocks.remove(session)
        currentPushJobs.remove(session)?.cancel()
    }

    private suspend fun pushChangedQuotes(changedKeys: Collection<CandleKey>) {
        val unionCodes = sessionVisibleStocks.values
            .asSequence()
            .flatten()
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (unionCodes.isEmpty()) return
        val unionCodeSet = unionCodes.toSet()

        val changedDayCodes = changedKeys
            .asSequence()
            .filter { it.period == CandlePeriod.DAY }
            .map { it.tsCode }
            .filter { it in unionCodeSet }
            .toSet()
        if (changedDayCodes.isEmpty()) return

        changedDayCodes.forEach { tsCode ->
            readAndCacheQuote(tsCode)
        }

        sessionVisibleStocks.forEach { (session, visibleCodes) ->
            val changedVisibleCodes = visibleCodes.filter { it in changedDayCodes }
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
        DataLayerBootstrap.candleFacade.readLatestQuote(CandleKey(tsCode, CandlePeriod.DAY))
            ?.let { quoteCache[tsCode] = it }
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
        DataLayerBootstrap.candleFacade.refreshLatestQuotes(refreshCodes)
    }

    private suspend fun pushCachedQuotes(session: DefaultWebSocketServerSession, tsCodes: List<String>) {
        if (tsCodes.isEmpty()) return
        val updates = tsCodes.mapNotNull { code ->
            quoteCache[code]?.toWsUpdate()
        }
        if (updates.isEmpty()) return
        AppWebSocketConnectionManager.sendToSession(
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
