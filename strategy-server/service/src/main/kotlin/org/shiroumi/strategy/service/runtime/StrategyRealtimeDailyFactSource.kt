package org.shiroumi.strategy.service.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import model.Candle
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

interface StrategyRealtimeDailyFactSource {
    suspend fun prefetch(tsCodes: List<String>, tradeDate: LocalDate): List<Candle>

    suspend fun load(tsCodes: List<String>, tradeDate: LocalDate): List<Candle>
}

interface KtorRealtimeDailyCandleClient {
    suspend fun loadRealtimeDailyCandles(tsCodes: List<String>, tradeDate: LocalDate): List<Candle>
}

/**
 * strategy-service 侧的 DAY realtime 事实源。
 *
 * 事实来源只允许是 Ktor `CandleSnapshotManager` 已经维护好的 DAY snapshot；
 * service 不直接访问 Tushare，不自建 rt_k 轮询，也不重新补 adj_factor。
 */
class KtorSnapshotStrategyRealtimeDailyFactSource(
    private val ktorClient: KtorRealtimeDailyCandleClient,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Shanghai")),
    private val realtimeFactTtlMs: Long = 10_000L
) : StrategyRealtimeDailyFactSource {
    private val realtimeFactCache = ConcurrentHashMap<String, CachedStrategyRealtimeDailyFact>()
    private val realtimeFactMutex = Mutex()

    override suspend fun prefetch(tsCodes: List<String>, tradeDate: LocalDate): List<Candle> {
        val normalizedCodes = tsCodes.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        if (normalizedCodes.isEmpty()) return emptyList()

        val now = clock.millis()
        val fetched = ktorClient.loadRealtimeDailyCandles(normalizedCodes, tradeDate)
            .filter { it.date == tradeDate }
        realtimeFactMutex.withLock {
            pruneExpiredRealtimeFacts(tradeDate = tradeDate, nowMs = now)
            fetched.forEach { candle ->
                realtimeFactCache[candle.tsCode] = CachedStrategyRealtimeDailyFact(
                    tradeDate = tradeDate,
                    candle = candle,
                    cachedAtMs = now
                )
            }
        }
        return fetched
    }

    override suspend fun load(tsCodes: List<String>, tradeDate: LocalDate): List<Candle> {
        val normalizedCodes = tsCodes.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        if (normalizedCodes.isEmpty()) return emptyList()

        val now = clock.millis()
        realtimeFactMutex.withLock {
            pruneExpiredRealtimeFacts(tradeDate = tradeDate, nowMs = now)
        }

        return normalizedCodes.mapNotNull { tsCode ->
            realtimeFactCache[tsCode]
                ?.takeIf { cached ->
                    cached.tradeDate == tradeDate &&
                        now - cached.cachedAtMs < realtimeFactTtlMs
                }
                ?.candle
        }
    }

    private fun pruneExpiredRealtimeFacts(
        tradeDate: LocalDate,
        nowMs: Long
    ) {
        realtimeFactCache.entries.removeIf { (_, cached) ->
            cached.tradeDate != tradeDate || nowMs - cached.cachedAtMs >= realtimeFactTtlMs
        }
    }
}

private data class CachedStrategyRealtimeDailyFact(
    val tradeDate: LocalDate,
    val candle: Candle,
    val cachedAtMs: Long
)
