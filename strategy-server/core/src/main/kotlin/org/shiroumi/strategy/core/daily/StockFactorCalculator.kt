package org.shiroumi.strategy.core.daily

import kotlinx.datetime.LocalDate
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedBar
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedStockWindow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

private const val EMA_SHORT = 10
private const val EMA_LONG = 30
private const val ATR_WINDOW = 14
private const val MOMENTUM_WINDOW = 20
private const val VOL_SHORT = 5
private const val VOL_LONG = 20
private const val ATR_MULT = 0.6
private const val STOP_FLOOR = 0.02
private const val STOP_CEILING = 0.06

private const val W_TREND = 0.25
private const val W_MOM = 0.25
private const val W_VOL = 0.20
private const val W_AMOM = 0.30

data class StockFactorSnapshot(
    val tradeDate: kotlinx.datetime.LocalDate,
    val tsCode: String,
    val signalBasis: String,
    val executionBasis: String,
    val sufficientHistory: Boolean,
    val requiredHistory: Int,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val executionOpen: Double,
    val executionClose: Double,
    val hfqFactor: Double,
    val ema10: Double,
    val ema30: Double,
    val emaBull: Boolean,
    val atr14: Double,
    val signal: Boolean,
    val momentum20: Double,
    val volRatio520: Double,
    val amomCombined: Double,
    val rankScore: Double,
)

object StockFactorCalculator {
    fun calculate(window: PreparedStockWindow): StockFactorSnapshot? {
        return process(window)?.second
    }

    /**
     * 全量路径：遍历整个窗口，同时产出最终 state 与 snapshot。
     */
    fun process(window: PreparedStockWindow): Pair<FactorRollingState, StockFactorSnapshot>? {
        if (!window.sufficientHistory || window.bars.isEmpty()) return null
        val bars = window.bars
        if (bars.size < EMA_LONG + ATR_WINDOW + 5) return null

        val firstBar = bars.first()
        var state = initialState(
            tradeDate = firstBar.date,
            tsCode = firstBar.tsCode,
            signalBasis = firstBar.signalBasis.name,
            executionBasis = firstBar.executionBasis.name,
            requiredHistory = window.requiredHistory,
            firstBar = firstBar,
        )

        var snapshot: StockFactorSnapshot? = null
        for (bar in bars.drop(1)) {
            val (nextState, snap) = step(state, bar)
            state = nextState
            snapshot = snap
        }
        // 如果窗口只有一根 bar（理论上不会发生，因为前面有 size 检查），直接用 state 构建 snapshot
        val finalSnapshot = snapshot ?: buildSnapshot(state, firstBar)
        return state to finalSnapshot
    }

    /**
     * 增量路径：基于前一天的 state 和今天的单根 bar，O(1) 递推。
     */
    fun calculate(
        state: FactorRollingState,
        bar: PreparedBar,
    ): Pair<FactorRollingState, StockFactorSnapshot>? {
        return step(state, bar)
    }

    private fun initialState(
        tradeDate: LocalDate,
        tsCode: String,
        signalBasis: String,
        executionBasis: String,
        requiredHistory: Int,
        firstBar: PreparedBar,
    ): FactorRollingState {
        val close = firstBar.close
        return FactorRollingState(
            tradeDate = tradeDate,
            tsCode = tsCode,
            signalBasis = signalBasis,
            executionBasis = executionBasis,
            requiredHistory = requiredHistory,
            barsCount = 1,
            emaShort = close,
            emaLong = close,
            atr = firstBar.high - firstBar.low,
            holding = false,
            stopPrice = 0.0,
            holdingDays = 0,
            shortVolumeSum = firstBar.volume,
            longVolumeSum = firstBar.volume,
            prevClose = close,
            recentReturns = List(20) { 0.0 },
            recentCloses = MutableList(20) { if (it == 0) close else 0.0 },
            recentVolumes = MutableList(20) { if (it == 0) firstBar.volume else 0.0 },
            momentumBaseClose = 0.0,
        )
    }

    private fun step(
        state: FactorRollingState,
        bar: PreparedBar,
    ): Pair<FactorRollingState, StockFactorSnapshot> {
        val close = bar.close
        val prevClose = state.prevClose
        val newBarsCount = state.barsCount + 1

        // EMA
        val emaShortMultiplier = 2.0 / (EMA_SHORT + 1)
        val emaLongMultiplier = 2.0 / (EMA_LONG + 1)
        val emaShort = (close - state.emaShort) * emaShortMultiplier + state.emaShort
        val emaLong = (close - state.emaLong) * emaLongMultiplier + state.emaLong

        // ATR
        val trueRange = maxOf(bar.high - bar.low, abs(bar.high - prevClose), abs(bar.low - prevClose))
        val atrMultiplier = 2.0 / (ATR_WINDOW + 1)
        val atr = (trueRange - state.atr) * atrMultiplier + state.atr

        // Signal state machine
        val emaBullPrev = state.emaShort > state.emaLong
        val atrPrev = state.atr
        val (nextHolding, nextStopPrice, nextHoldingDays) = if (!state.holding) {
            if (emaBullPrev && atrPrev > 0.0) {
                val atrStopPct = ((atrPrev * ATR_MULT) / close).coerceIn(STOP_FLOOR, STOP_CEILING)
                Triple(true, close * (1 - atrStopPct), 0)
            } else {
                Triple(false, state.stopPrice, state.holdingDays)
            }
        } else {
            val days = state.holdingDays + 1
            if (days >= 1 && (close <= state.stopPrice || !emaBullPrev)) {
                Triple(false, state.stopPrice, days)
            } else {
                val atrStopPct = ((atr * ATR_MULT) / close).coerceIn(STOP_FLOOR, STOP_CEILING)
                Triple(true, max(state.stopPrice, close * (1 - atrStopPct)), days)
            }
        }

        // Volume sums (rolling 5/20)
        var shortVolumeSum = state.shortVolumeSum + bar.volume
        var longVolumeSum = state.longVolumeSum + bar.volume
        if (newBarsCount > VOL_SHORT) {
            shortVolumeSum -= state.recentVolumes[(newBarsCount - 1 - VOL_SHORT) % 20]
        }
        if (newBarsCount > VOL_LONG) {
            longVolumeSum -= state.recentVolumes[(newBarsCount - 1 - VOL_LONG) % 20]
        }

        // 在覆盖循环数组前先缓存 20 天前的 close，随后用更新后的缓冲区生成当日 snapshot。
        val nextMomentumBaseClose = if (newBarsCount > MOMENTUM_WINDOW) {
            state.recentCloses[state.barsCount % 20]
        } else 0.0

        // 更新循环数组
        val returnValue = if (prevClose == 0.0) 0.0 else (close - prevClose) / prevClose
        val recentReturns = state.recentReturns.toMutableList().apply {
            this[(state.barsCount - 1) % 20] = returnValue
        }
        val recentCloses = state.recentCloses.toMutableList().apply {
            this[state.barsCount % 20] = close
        }
        val recentVolumes = state.recentVolumes.toMutableList().apply {
            this[state.barsCount % 20] = bar.volume
        }

        val nextState = FactorRollingState(
            tradeDate = bar.date,
            tsCode = state.tsCode,
            signalBasis = state.signalBasis,
            executionBasis = state.executionBasis,
            requiredHistory = state.requiredHistory,
            barsCount = newBarsCount,
            emaShort = emaShort,
            emaLong = emaLong,
            atr = atr,
            holding = nextHolding,
            stopPrice = nextStopPrice,
            holdingDays = nextHoldingDays,
            shortVolumeSum = shortVolumeSum,
            longVolumeSum = longVolumeSum,
            prevClose = close,
            recentReturns = recentReturns,
            recentCloses = recentCloses,
            recentVolumes = recentVolumes,
            momentumBaseClose = nextMomentumBaseClose,
        )
        val snapshot = buildSnapshot(
            bar = bar,
            state = nextState,
            emaShort = emaShort,
            emaLong = emaLong,
            atr = atr,
            holding = nextHolding,
            shortVolumeSum = shortVolumeSum,
            longVolumeSum = longVolumeSum,
            barsCount = newBarsCount,
            momentumBaseClose = nextMomentumBaseClose,
        )

        return nextState to snapshot
    }

    private fun buildSnapshot(
        bar: PreparedBar,
        state: FactorRollingState,
        emaShort: Double,
        emaLong: Double,
        atr: Double,
        holding: Boolean,
        shortVolumeSum: Double,
        longVolumeSum: Double,
        barsCount: Int,
        momentumBaseClose: Double = state.momentumBaseClose,
    ): StockFactorSnapshot {
        val ema10 = emaShort
        val ema30 = emaLong
        val emaBull = ema10 > ema30

        val momentum20 = if (barsCount <= MOMENTUM_WINDOW) {
            0.0
        } else {
            if (momentumBaseClose == 0.0) 0.0 else (bar.close - momentumBaseClose) / momentumBaseClose
        }

        val volRatio = if (barsCount < VOL_LONG || longVolumeSum == 0.0) {
            0.0
        } else {
            (shortVolumeSum / VOL_SHORT) / (longVolumeSum / VOL_LONG)
        }

        val atrPct = if (bar.close == 0.0) 0.0 else atr / bar.close
        val normalizedMomentum = if (atrPct == 0.0) 0.0 else momentum20 / atrPct
        val returnCount = minOf(20, state.barsCount - 1)
        val returnsBuffer = DoubleArray(returnCount) { i ->
            state.recentReturns[(state.barsCount - returnCount + i - 1) % 20]
        }
        val fftLike = fftLikePhase(returnsBuffer, returnCount)
        val amomCombined = 0.3 * normalizedMomentum + 0.7 * fftLike
        val rankScore = W_TREND +
                W_MOM * normalizeSigned(momentum20) +
                W_VOL * normalizePositive(volRatio) +
                W_AMOM * normalizeSigned(amomCombined)

        return StockFactorSnapshot(
            tradeDate = bar.date,
            tsCode = bar.tsCode,
            signalBasis = bar.signalBasis.name,
            executionBasis = bar.executionBasis.name,
            sufficientHistory = true,
            requiredHistory = state.requiredHistory,
            open = bar.open,
            high = bar.high,
            low = bar.low,
            close = bar.close,
            volume = bar.volume,
            executionOpen = bar.executionOpen,
            executionClose = bar.executionClose,
            hfqFactor = bar.hfqFactor,
            ema10 = ema10,
            ema30 = ema30,
            emaBull = emaBull,
            atr14 = atr,
            signal = holding,
            momentum20 = momentum20,
            volRatio520 = volRatio,
            amomCombined = amomCombined,
            rankScore = rankScore,
        )
    }

    /**
     * 基于最终 state 构建 snapshot（用于 process() 中仅剩单根 bar 的退化场景）。
     * 正常滑动窗口下应优先使用 step 返回的 snapshot。
     */
    private fun buildSnapshot(state: FactorRollingState, bar: PreparedBar): StockFactorSnapshot {
        return buildSnapshot(
            bar = bar,
            state = state,
            emaShort = state.emaShort,
            emaLong = state.emaLong,
            atr = state.atr,
            holding = state.holding,
            shortVolumeSum = state.shortVolumeSum,
            longVolumeSum = state.longVolumeSum,
            barsCount = state.barsCount,
        )
    }

    private fun fftLikePhase(buffer: DoubleArray, size: Int): Double {
        if (size < 4) return 0.0
        var sum = 0.0
        val start = (buffer.size - size).coerceAtLeast(0)
        for (index in 0 until size) {
            val value = buffer[(start + index) % buffer.size]
            sum += value * sin(2.0 * PI * index / size)
        }
        return (sum / size).coerceIn(-1.0, 1.0)
    }

    private fun normalizeSigned(value: Double): Double = ((value + 1.0) / 2.0).coerceIn(0.0, 1.0)
    private fun normalizePositive(value: Double): Double = value.coerceIn(0.0, 2.0) / 2.0
}
