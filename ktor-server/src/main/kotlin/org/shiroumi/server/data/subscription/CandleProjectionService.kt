package org.shiroumi.server.data.subscription

import model.Candle
import model.CandleData
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import model.ws.CandleDataPayload
import model.ws.CandleSubscribeRequest
import org.shiroumi.server.data.snapshot.CandleSnapshotState

/**
 * 新数据层下的 Candle 投影服务。
 *
 * 这里仍然只做两件事：
 * 1. 从 snapshot 的 merged 视图裁剪请求窗口
 * 2. 映射为对外稳定的 `CandleDataPayload`
 *
 * 它不应该承担：
 * - 判断该读哪个数据源
 * - 自己拼接历史与实时
 * - 等待 snapshot ready
 */
class CandleProjectionService {
    fun project(
        key: CandleKey,
        request: CandleSubscribeRequest,
        snapshot: CandleSnapshotState
    ): CandleDataPayload = project(
        tsCode = key.tsCode,
        request = request,
        candles = snapshot.candles
    )

    fun project(
        tsCode: String,
        request: CandleSubscribeRequest,
        candles: List<Candle>
    ): CandleDataPayload {
        val filteredCandles = candles
            .filter { candle -> request.matches(candle) }
            .let { candidates ->
                request.limit?.let { limit -> candidates.takeLast(limit.coerceAtLeast(1)) } ?: candidates
            }

        val candleData = filteredCandles.mapIndexed { index, candle ->
            candle.toSocketCandleData(
                period = request.period,
                useAdjusted = request.useAdjusted,
                previous = filteredCandles.getOrNull(index - 1)
            )
        }

        return CandleDataPayload(
            tsCode = tsCode,
            candles = candleData,
            totalCount = candleData.size,
            requestParams = request
        )
    }

    private fun CandleSubscribeRequest.matches(candle: Candle): Boolean {
        val candleDate = candle.tradeTime?.substringBefore(" ") ?: candle.date.toString()
        val start = startDate
        val end = endDate
        if (start != null && candleDate < start) return false
        if (end != null && candleDate > end) return false
        return true
    }

    private fun Candle.toSocketCandleData(
        period: CandlePeriod,
        useAdjusted: Boolean,
        previous: Candle?
    ): CandleData {
        val useQfq = useAdjusted && closeQfq > 0f
        val displayOpen = if (useQfq) openQfq else open
        val displayHigh = if (useQfq) highQfq else high
        val displayLow = if (useQfq) lowQfq else low
        val displayClose = if (useQfq) closeQfq else close
        val previousClose = previous?.let { prev ->
            if (useQfq && prev.closeQfq > 0f) prev.closeQfq else prev.close
        }
        val changePct = previousClose?.takeIf { it != 0f }
            ?.let { ((displayClose - it) / it) * 100f }
        val amplitude = if (displayOpen != 0f) {
            ((displayHigh - displayLow) / displayOpen) * 100f
        } else {
            null
        }

        val displayDate = when (period) {
            CandlePeriod.MIN_5,
            CandlePeriod.MIN_15,
            CandlePeriod.MIN_30,
            CandlePeriod.MIN_60 -> tradeTime ?: date.toString()
            CandlePeriod.DAY,
            CandlePeriod.WEEK,
            CandlePeriod.MONTH -> (tradeTime?.substringBefore(" ") ?: date.toString()).take(10)
        }

        return CandleData(
            date = displayDate,
            open = displayOpen,
            high = displayHigh,
            low = displayLow,
            close = displayClose,
            volume = if (useQfq && volumeQfq > 0f) volumeQfq else volume,
            turnover = turnoverReal,
            changePercent = changePct,
            amplitude = amplitude,
            adjOpen = if (openQfq > 0f) open else null,
            adjHigh = if (highQfq > 0f) high else null,
            adjLow = if (lowQfq > 0f) low else null,
            adjClose = if (closeQfq > 0f) close else null
        )
    }
}
