package org.shiroumi.server.data.facade


import model.Candle
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import org.shiroumi.server.data.trace.CandleTrace
import org.shiroumi.server.data.snapshot.CandleSnapshotManager
import org.shiroumi.server.data.snapshot.CandleSnapshotState
import utils.logger

/**
 * `CandleDataFacade` 是新数据层暴露给上游调用方的唯一 Candle 读口。
 *
 * 读口统一的意义在于：
 * - WebSocket、CLI、策略跟踪都不再分别知道 DAY 走哪里、分钟线走哪里
 * - 后续如果 snapshot 内部实现变化，只需要收敛在这个 facade 里
 *
 * 正确性要求：
 * - 任何 Candle 消费方都不应该绕过它直接摸 snapshot manager 的内部缓存结构
 * - 它负责把"当前是否 ready"与"如何等待 ready"变成稳定语义
 */
// `open` 仅为给下游消费方（如 CandleDataProvider）提供一个干净的读口测试替身：
// provider 被定位为纯 topic mapper，单测它的订阅/推送逻辑时无需拉起整条数据库链路，
// 只要替换 readSnapshot 的返回即可。生产实现保持唯一，open 不改变运行期行为。
open class CandleDataFacade(
    private val snapshotManager: CandleSnapshotManager
) {
    private val logger by logger("CandleDataFacade")

    /**
     * 读取当前快照。
     *
     * `requestSeq` 只用于链路 trace，不参与任何业务判断。
     * 这样可以在不污染读模型的前提下，把"provider 读没读到 snapshot、耗时多少"
     * 这类排障信息稳定打印出来。
     */
    open fun readSnapshot(key: CandleKey, requestSeq: Long? = null): CandleSnapshotState? {
        val startNanos = System.nanoTime()
        val snapshot = snapshotManager.readSnapshot(key)
        CandleTrace.log(
            stage = "FACADE_READ",
            tsCode = key.tsCode,
            period = key.period,
            requestSeq = requestSeq,
            detail = "hit=${snapshot != null}, version=${snapshot?.version}, source=${snapshot?.sourceTag}, elapsedMs=${(System.nanoTime() - startNanos) / 1_000_000.0}"
        )
        return snapshot
    }

    fun readMergedCandles(key: CandleKey): List<Candle> = snapshotManager.readMergedCandles(key)

    suspend fun awaitSnapshot(key: CandleKey, timeoutMs: Long = 15_000L): CandleSnapshotState? {
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            readSnapshot(key)?.let { snapshot ->
                if (snapshot.candles.isNotEmpty()) {
                    return snapshot
                }
            }
            kotlinx.coroutines.delay(250L)
        }
        logger.info("等待 Candle 快照超时: key=${key.id}, timeoutMs=$timeoutMs")
        return readSnapshot(key)
    }

    fun refreshDailyUniverse() {
        snapshotManager.refreshDayUniverse()
    }

    fun refreshLatestQuotes(tsCodes: List<String>) {
        snapshotManager.scheduleDayRealtimeUpdate(tsCodes)
    }

    /**
     * 读取最新报价摘要，用于股票列表行级价格展示。
     *
     * 只从 DAY 快照取最新一根 K 线的 close/vol/turnover 和前收盘价，
     * 避免 `readSnapshot()` 返回完整 K 线列表的序列化开销。
     */
    fun readLatestQuote(key: CandleKey): LatestCandleQuote? {
        require(key.period == CandlePeriod.DAY) { "readLatestQuote only supports DAY period" }
        val snapshot = readSnapshot(key) ?: return null
        val candles = snapshot.candles
        if (candles.isEmpty()) return null
        val latest = candles.last()
        val prev = if (candles.size >= 2) candles[candles.size - 2] else null
        val preClose = prev?.close ?: latest.open
        val changeAmount = if (preClose > 0f) latest.close - preClose else 0f
        val changePercent = if (preClose > 0f) (changeAmount / preClose) * 100f else 0f
        return LatestCandleQuote(
            tsCode = key.tsCode,
            latestPrice = latest.close,
            preClose = preClose,
            changePercent = changePercent,
            changeAmount = changeAmount,
            volume = latest.volume,
            turnover = latest.turnoverReal
        )
    }
}

data class LatestCandleQuote(
    val tsCode: String,
    val latestPrice: Float,
    val preClose: Float,
    val changePercent: Float,
    val changeAmount: Float,
    val volume: Float,
    val turnover: Float
)
