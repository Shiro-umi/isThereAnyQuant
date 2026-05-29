package org.shiroumi.strategy.research.topic.factor.output

import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.topic.factor.signal.BandpassFilter
import org.shiroumi.strategy.research.topic.factor.signal.BlockPermutation
import org.shiroumi.strategy.research.topic.factor.signal.Coherence
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * factor 层的**频域共振数值核心**——因子有效性度量的通用计算引擎。
 *
 * 这里只有「给定一对序列 (因子, 目标)，算出未裁判的 [ResonanceMetric]」所需的纯数学：
 * 预处理（winsorize→Z→去 EMA 趋势）、带通滤波分频、滚动相关、相干性与相位超前、
 * 滞后相关定超前、样本外 IC/命中率/分层价差、块置换显著性。**零业务耦合**（不认识情绪/量价）。
 *
 * 由各 topic 的 Study 调用：sentiment 在 38 情绪因子上调它，volume-price 在 VV/VP 序列上调它，
 * 共享同一套口径与数值实现，保证不同研究方向的 [ResonanceMetric] 严格可比、不重复造轮子。
 *
 * 设计依据：研究执行手册 §6/§12，以及 `private/research-docs/volume-price-factor-formula.html` §5.2。
 */
object ResonanceCore {

    const val MIN_SERIES_SIZE = 60

    data class BandSpec(val low: Double, val high: Double)

    /** 频带定义（cycle/day）。各 topic 共享同一频带口径。 */
    val BANDS = linkedMapOf(
        "F1b" to BandSpec(0.200, 0.333),
        "F2a" to BandSpec(0.125, 0.200),
        "F1a" to BandSpec(0.333, 0.499),
        "F2b" to BandSpec(0.100, 0.125),
    )

    /** buildMetric 的产物：未裁判度量 + discovery funnel 所需的 recent 滚动相关值。 */
    data class MetricResult(val metric: ResonanceMetric, val recentRollingCorr: Double?)

    /**
     * 给定一对原始序列，算出一条未裁判的 [ResonanceMetric]。
     *
     * 与上游解耦：调用方负责把自己的因子/目标抽成 `List<Double?>` 三元组（因子序列、当期目标、未来目标），
     * 并提供五维身份所需的命名信息。核心只做数值，不认识因子语义。
     *
     * @return null 当有效样本不足；否则返回度量 + recent（供 discovery funnel 用，调用方自行存储）。
     */
    fun buildMetric(
        ctx: ResearchContext,
        factor: String,
        target: String,
        horizon: Int,
        band: String,
        rawFactor: List<Double?>,
        rawTarget: List<Double?>,
        futureTarget: List<Double?>,
        stateId: String,
        stateIndexes: Set<Int>?,
        factorName: String = factor,
        factorType: String = "single",
        factorJ: String? = null,
    ): MetricResult? {
        val aligned = rawFactor.indices.mapNotNull { index ->
            if (stateIndexes != null && index !in stateIndexes) return@mapNotNull null
            val x = rawFactor[index]
            val y = rawTarget[index]
            val fy = futureTarget[index]
            if (x == null || y == null || fy == null) null else Triple(index, x, y)
        }
        if (aligned.size < MIN_SERIES_SIZE) return null

        val validIndexes = aligned.map { it.first }
        val xRaw = aligned.map { it.second }.toDoubleArray()
        val yRaw = aligned.map { it.third }.toDoubleArray()
        validIndexes.forEach { futureTarget[it] ?: return null }

        val xNorm = preprocess(xRaw)
        val yNorm = preprocess(yRaw)
        val spec = BANDS.getValue(band)
        val coeff = BandpassFilter.design(order = 4, lowWn = spec.low / 0.5, highWn = spec.high / 0.5)
        val xFf = BandpassFilter.filtfilt(coeff, xNorm)
        val yFf = BandpassFilter.filtfilt(coeff, yNorm)
        val xLf = BandpassFilter.lfilter(coeff, xNorm)
        val yLf = BandpassFilter.lfilter(coeff, yNorm)

        val rolling = rollingCorrelationStats(xFf, yFf, horizon, window = 40)
        val stftWindowForBand = if (band == "F2a") 48 else 40
        val coherence = coherenceStats(xFf, yFf, band, stftWindow = stftWindowForBand)
        val leadLag = leadByLagCorrelation(xFf, yFf, horizon = horizon, maxLag = 5)
        val leadPhase = alignPhaseLeadToLag(coherence.leadDaysPhase, leadLag, band)
        val leadStable = leadPhase == null || abs(leadLag.leadDays - leadPhase) <= 1.0
        val xOos = xLf.copyOfRange(0, xLf.size - horizon)
        val yOos = yLf.copyOfRange(horizon, yLf.size)
        val oos = oosStats(xOos, yOos)
        val p = permutationPValue(xOos, yOos, band, ctx.randomSeed + factorName.hashCode() + target.hashCode() + horizon)
        val ffCorr = shiftedCorrelation(xFf, yFf, horizon)
        val lfCorr = shiftedCorrelation(xLf, yLf, horizon)
        val causalConsistent = ffCorr == null || lfCorr == null ||
            (ffCorr == 0.0 && lfCorr == 0.0) ||
            (sign(ffCorr) == sign(lfCorr) && abs(lfCorr) >= abs(ffCorr) * 0.35)

        val identity = ResonanceIdentity(
            factor_name = factorName,
            factor_type = factorType,
            factor_i = factor,
            factor_j = factorJ,
            target_y = target,
            horizon = horizon,
            band = band,
            state_id = stateId,
        )
        val metric = ResonanceMetric(
            identity = identity,
            stft_window = stftWindowForBand,
            norm_version = "Z_60_detrend",
            state_window = 60,
            rolling_corr_mean = rolling.mean,
            rolling_corr_stability = rolling.stability,
            mean_coherence = coherence.mean,
            max_coherence = coherence.max,
            coherence_coverage = coherence.coverage,
            phase_std = coherence.phaseStd,
            lead_days_lag = leadLag.leadDays,
            lead_days_phase = leadPhase,
            lead_relation_stable = leadStable,
            beta = oos.beta,
            oos_ic = oos.ic,
            rank_ic_oos = oos.rankIc,
            hit_rate = oos.hitRate,
            baseline = oos.baseline,
            top_bottom_spread = oos.topBottomSpread,
            top_bottom_spread_consistency = oos.topBottomSpreadConsistency,
            p_value = p,
            q_value = p,
            sample_count = oos.sampleCount,
            filtfilt_lfilter_consistent = causalConsistent,
            regime = "all",
        )
        return MetricResult(metric, rolling.recent)
    }

    // ════════════════════════════════════════════════════════════════════
    //  以下为纯数值方法，原样自 SentimentResonanceStudy 迁移，逐位等价
    // ════════════════════════════════════════════════════════════════════

    fun preprocess(values: DoubleArray): DoubleArray {
        val clipped = rollingWinsorize(values, window = 252)
        val z = rollingZ(clipped, window = 60)
        val ema = ema(z, span = 20)
        return DoubleArray(z.size) { z[it] - ema[it] }
    }

    private fun rollingWinsorize(values: DoubleArray, window: Int): DoubleArray =
        DoubleArray(values.size) { index ->
            val start = max(0, index - window + 1)
            val sample = values.copyOfRange(start, index + 1).sortedArray()
            val lo = sample[(sample.lastIndex * 0.01).toInt()]
            val hi = sample[(sample.lastIndex * 0.99).toInt()]
            values[index].coerceIn(lo, hi)
        }

    private fun rollingZ(values: DoubleArray, window: Int): DoubleArray =
        DoubleArray(values.size) { index ->
            val start = max(0, index - window + 1)
            val sample = values.copyOfRange(start, index + 1)
            val mean = sample.average()
            val std = sqrt(sample.sumOf { (it - mean) * (it - mean) } / sample.size)
            if (std == 0.0) 0.0 else (values[index] - mean) / std
        }

    fun ema(values: DoubleArray, span: Int): DoubleArray {
        if (values.isEmpty()) return values
        val alpha = 2.0 / (span + 1.0)
        val out = DoubleArray(values.size)
        out[0] = values[0]
        for (i in 1 until values.size) out[i] = alpha * values[i] + (1.0 - alpha) * out[i - 1]
        return out
    }

    private fun rollingCorrelationStats(x: DoubleArray, y: DoubleArray, lag: Int, window: Int): RollingStats {
        val values = ArrayList<Double>()
        var start = 0
        while (start + window + lag <= x.size) {
            val xs = x.copyOfRange(start, start + window)
            val ys = y.copyOfRange(start + lag, start + lag + window)
            pearson(xs, ys)?.let(values::add)
            start++
        }
        if (values.isEmpty()) return RollingStats(null, null, null)
        val mean = values.average()
        val dominant = if (mean >= 0.0) 1.0 else -1.0
        return RollingStats(
            mean = mean,
            stability = values.count { it == 0.0 || sign(it) == dominant }.toDouble() / values.size,
            recent = values.lastOrNull(),
        )
    }

    private fun coherenceStats(x: DoubleArray, y: DoubleArray, band: String, stftWindow: Int): CoherenceStats {
        val nperseg = min(stftWindow, x.size)
        val result = Coherence.compute(x, y, nperseg = nperseg, noverlap = (nperseg * 7) / 8)
        val spec = BANDS.getValue(band)
        val indexes = result.frequencies.indices.filter { result.frequencies[it] in spec.low..spec.high }
        if (indexes.isEmpty()) return CoherenceStats(null, null, null, null, null)
        val cohs = indexes.map { result.coherence[it].coerceIn(0.0, 1.0) }
        val phases = indexes.map { result.phase[it] }
        val weights = cohs.map { max(it, 1e-9) }
        val mean = cohs.average()
        val lead = indexes.indices.sumOf { i ->
            val freq = result.frequencies[indexes[i]]
            val days = if (freq > 0.0) phases[i] / (2.0 * PI * freq) else 0.0
            days * weights[i]
        } / weights.sum()
        return CoherenceStats(
            mean = mean,
            max = cohs.maxOrNull(),
            coverage = cohs.count { it > 0.5 }.toDouble() / cohs.size,
            phaseStd = circularStd(phases),
            leadDaysPhase = lead,
        )
    }

    private fun leadByLagCorrelation(x: DoubleArray, y: DoubleArray, horizon: Int, maxLag: Int): LeadLag {
        val candidates = (-maxLag..maxLag).mapNotNull { lag ->
            shiftedCorrelation(x, y, lag)?.let { corr -> LagCandidate(lag = lag, corr = corr) }
        }
        if (candidates.isEmpty()) return LeadLag(0.0, null)
        val absolutePeak = candidates.maxBy { abs(it.corr) }
        val validPeak = candidates
            .filter { it.lag.toDouble() in leadRange(horizon) }
            .maxByOrNull { abs(it.corr) }
        val selected = if (validPeak != null && abs(validPeak.corr) >= abs(absolutePeak.corr) * 0.65) {
            validPeak
        } else {
            absolutePeak
        }
        return LeadLag(selected.lag.toDouble(), selected.corr)
    }

    private fun leadRange(horizon: Int): ClosedFloatingPointRange<Double> =
        when (horizon) {
            1 -> 0.5..1.5
            3 -> 1.0..3.0
            5 -> 1.0..5.0
            else -> 1.0..horizon.toDouble()
        }

    private fun alignPhaseLeadToLag(phaseLead: Double?, leadLag: LeadLag, band: String): Double? {
        if (phaseLead == null) return null
        val spec = BANDS.getValue(band)
        val centerFrequency = (spec.low + spec.high) / 2.0
        if (centerFrequency <= 0.0) return phaseLead
        val periodDays = 1.0 / centerFrequency
        val offsets = (-2..2).map { branch -> branch * periodDays }.toMutableList()
        if ((leadLag.corr ?: 0.0) < 0.0) {
            offsets += (-2..2).map { branch -> branch * periodDays + periodDays / 2.0 }
        }
        return offsets
            .map { offset -> phaseLead + offset }
            .minBy { abs(it - leadLag.leadDays) }
    }

    fun shiftedCorrelation(x: DoubleArray, y: DoubleArray, lag: Int): Double? {
        val startX = if (lag >= 0) 0 else -lag
        val startY = if (lag >= 0) lag else 0
        val len = min(x.size - startX, y.size - startY)
        if (len < 3) return null
        return pearson(x.copyOfRange(startX, startX + len), y.copyOfRange(startY, startY + len))
    }

    private fun oosStats(x: DoubleArray, y: DoubleArray): OosStats {
        val pairs = x.indices.map { x[it] to y[it] }
        if (pairs.size < 30) return OosStats(sampleCount = pairs.size)
        val foldsWithTrainEnd = ArrayList<Pair<Int, List<Pair<Double, Double>>>>()
        if (pairs.size >= 620) {
            var trainStart = 0
            while (trainStart + 500 + 120 <= pairs.size) {
                val trainEnd = trainStart + 500
                foldsWithTrainEnd.add(trainEnd to pairs.subList(trainEnd, trainEnd + 120))
                trainStart += 20
            }
        } else {
            val split = max(1, (pairs.size * 0.7).toInt())
            if (pairs.size - split >= 10) foldsWithTrainEnd.add(split to pairs.subList(split, pairs.size))
        }
        if (foldsWithTrainEnd.isEmpty()) return OosStats(sampleCount = pairs.size)

        val orientedFolds = foldsWithTrainEnd.map { (trainEnd, fold) ->
            val trainSlice = pairs.subList(0, max(1, trainEnd))
            val ori = sign(pearson(trainSlice.map { it.first }.toDoubleArray(), trainSlice.map { it.second }.toDoubleArray()) ?: 0.0)
                .let { if (it == 0.0) 1.0 else it }
            fold.map { (px, py) -> px * ori to py }
        }
        val oriented = orientedFolds.flatten()
        val pred = oriented.map { it.first }.toDoubleArray()
        val actual = oriented.map { it.second }.toDoubleArray()
        val ic = pearson(pred, actual)
        val rankIc = pearson(ranks(pred), ranks(actual))
        val hitRate = oriented.count { sign(it.first) == sign(it.second) && sign(it.second) != 0.0 }
            .toDouble() / oriented.count { sign(it.second) != 0.0 }.coerceAtLeast(1)
        val positive = oriented.count { it.second > 0.0 }.toDouble() / oriented.size
        val negative = oriented.count { it.second < 0.0 }.toDouble() / oriented.size
        val spreads = orientedFolds.mapNotNull { topBottomSpread(it) }
        return OosStats(
            ic = ic,
            rankIc = rankIc,
            hitRate = hitRate,
            baseline = max(positive, negative),
            topBottomSpread = spreads.takeIf { it.isNotEmpty() }?.average(),
            topBottomSpreadConsistency = spreads.takeIf { it.isNotEmpty() }
                ?.let { values -> values.count { it > 0.0 }.toDouble() / values.size },
            beta = beta(pred, actual),
            sampleCount = pairs.size,
        )
    }

    private fun topBottomSpread(pairs: List<Pair<Double, Double>>): Double? {
        if (pairs.size < 10) return null
        val sorted = pairs.sortedBy { it.first }
        val n = max(1, sorted.size / 5)
        val bottom = sorted.take(n).map { it.second }.average()
        val top = sorted.takeLast(n).map { it.second }.average()
        return top - bottom
    }

    private fun permutationPValue(x: DoubleArray, y: DoubleArray, band: String, seed: Long): Double? {
        if (x.size < 30) return null
        val block = min(BlockPermutation.blockSizeByBand.getValue(band), y.size)
        return BlockPermutation.test(
            series = y,
            blockSize = block,
            iterations = 2000,
            seed = seed,
            statistic = { permutedY -> abs(pearson(x, permutedY) ?: 0.0) },
        ).pValue
    }

    fun pearson(x: DoubleArray, y: DoubleArray): Double? {
        if (x.size != y.size || x.size < 3) return null
        val mx = x.average()
        val my = y.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in x.indices) {
            val vx = x[i] - mx
            val vy = y[i] - my
            num += vx * vy
            dx += vx * vx
            dy += vy * vy
        }
        val den = sqrt(dx * dy)
        return if (den == 0.0) null else num / den
    }

    private fun ranks(values: DoubleArray): DoubleArray {
        val sorted = values.withIndex().sortedBy { it.value }
        val out = DoubleArray(values.size)
        var i = 0
        while (i < sorted.size) {
            var j = i + 1
            while (j < sorted.size && sorted[j].value == sorted[i].value) j++
            val rank = (i + j + 1).toDouble() / 2.0
            for (k in i until j) out[sorted[k].index] = rank
            i = j
        }
        return out
    }

    private fun beta(x: DoubleArray, y: DoubleArray): Double? {
        if (x.size != y.size || x.size < 3) return null
        val mx = x.average()
        val my = y.average()
        val variance = x.sumOf { (it - mx) * (it - mx) }
        if (variance == 0.0) return null
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / variance
    }

    private fun circularStd(phases: List<Double>): Double? {
        if (phases.isEmpty()) return null
        val sinMean = phases.sumOf { kotlin.math.sin(it) } / phases.size
        val cosMean = phases.sumOf { kotlin.math.cos(it) } / phases.size
        val r = sqrt(sinMean * sinMean + cosMean * cosMean).coerceIn(1e-12, 1.0)
        return sqrt(-2.0 * kotlin.math.ln(r))
    }

    data class RollingStats(val mean: Double?, val stability: Double?, val recent: Double?)
    data class CoherenceStats(
        val mean: Double?,
        val max: Double?,
        val coverage: Double?,
        val phaseStd: Double?,
        val leadDaysPhase: Double?,
    )
    data class LeadLag(val leadDays: Double, val corr: Double?)
    private data class LagCandidate(val lag: Int, val corr: Double)
    data class OosStats(
        val ic: Double? = null,
        val rankIc: Double? = null,
        val hitRate: Double? = null,
        val baseline: Double? = null,
        val topBottomSpread: Double? = null,
        val topBottomSpreadConsistency: Double? = null,
        val beta: Double? = null,
        val sampleCount: Int? = null,
    )
}
