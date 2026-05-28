package org.shiroumi.strategy.research.study.sentiment

import org.shiroumi.database.sentiment.SentimentFactorDailyRecord
import org.shiroumi.database.sentiment.SentimentFactorDailyRepository
import org.shiroumi.database.sentiment.SentimentTargetLabelCalculator
import org.shiroumi.strategy.research.output.ResonanceIdentity
import org.shiroumi.strategy.research.output.ResonanceMetric
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.pipeline.ResearchStudy
import org.shiroumi.strategy.research.signal.BandpassFilter
import org.shiroumi.strategy.research.signal.BlockPermutation
import org.shiroumi.strategy.research.signal.Coherence
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

class SentimentResonanceStudy : ResearchStudy<Unit, List<ResonanceMetric>> {
    override val name: String = "study:sentiment-resonance"
    private val metricDiscoveryRecent = mutableMapOf<ResonanceIdentity, Double?>()

    override fun run(ctx: ResearchContext, input: Unit): List<ResonanceMetric> {
        val records = SentimentFactorDailyRepository.findBetween(ctx.startDate, ctx.endDate)
            .sortedBy { it.tradeDate }
        return runRecords(ctx, records)
    }

    internal fun runRecords(ctx: ResearchContext, records: List<SentimentFactorDailyRecord>): List<ResonanceMetric> {
        metricDiscoveryRecent.clear()
        if (records.isEmpty()) return emptyList()

        val labels = SentimentTargetLabelCalculator.build(records)
        val factorNames = ctx.param("factors", FACTOR_NAMES.joinToString(","))
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in FACTOR_NAMES }
        val targets = ctx.param("targets", "Y1,Y2,Y3")
            .split(',')
            .map { it.trim() }
            .filter { it in TARGETS }
        val horizons = ctx.param("horizons", "1,3,5")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in setOf(1, 3, 5) }
        val bands = ctx.param("bands", "F1b,F2a")
            .split(',')
            .map { it.trim() }
            .filter { it in BANDS }
        val stateMode = ctx.param("state-mode", "all,conditional")
            .split(',')
            .map { it.trim() }
            .toSet()
        val stateSlices = if ("conditional" in stateMode) buildStateSlices(records, ctx.param("state-window", "60").toInt()) else emptyList()
        val maxStateCandidates = ctx.param("max-state-candidates", "80").toInt()
        val discoveryFilter = ctx.param("discovery-filter", "true").toBoolean()
        val stftFilter = ctx.param("stft-filter", "true").toBoolean()
        val stftCoherenceFloor = ctx.param("stft-coherence-floor", "0.40").toDouble()
        val stftCoverageFloor = ctx.param("stft-coverage-floor", "0.15").toDouble()
        val fdrFamily = ctx.param("fdr-family", "target-horizon-band")
        val pairMode = ctx.param("pair-mode", "true").toBoolean()
        val pairTransforms = ctx.param("pair-transforms", "diff,product,ratio")
            .split(',')
            .map { it.trim() }
            .filter { it in PAIR_TRANSFORMS }
        val maxPairsPerFamily = ctx.param("max-pairs-per-family", "12").toInt()

        val globalMetrics = ArrayList<ResonanceMetric>()
        for (factor in factorNames) {
            val rawFactor = records.map { it.factors[factor] }
            for (target in targets) {
                val rawTarget = records.map { targetRaw(it, target) }
                for (horizon in horizons) {
                    val futureTarget = labels.map { targetNext(it, target, horizon) }
                    for (band in bands) {
                        buildMetric(
                            ctx = ctx,
                            factor = factor,
                            target = target,
                            horizon = horizon,
                            band = band,
                            rawFactor = rawFactor,
                            rawTarget = rawTarget,
                            futureTarget = futureTarget,
                            stateId = "trend=all,disp=all,vol=all",
                            stateIndexes = null,
                        )?.let(globalMetrics::add)
                    }
                }
            }
        }
        val globalCandidates = globalMetrics.filter {
            (!discoveryFilter || passesDiscoveryFunnel(it)) &&
                (!stftFilter || passesStftConfirmation(it, stftCoherenceFloor, stftCoverageFloor))
        }
        val metrics = ArrayList<ResonanceMetric>()
        if ("all" in stateMode) metrics.addAll(globalCandidates)
        if (pairMode && "all" in stateMode) {
            metrics.addAll(
                buildPairMetrics(
                    ctx = ctx,
                    records = records,
                    labels = labels,
                    singleCandidates = globalCandidates,
                    pairTransforms = pairTransforms,
                    maxPairsPerFamily = maxPairsPerFamily,
                    discoveryFilter = discoveryFilter,
                    stftFilter = stftFilter,
                    stftCoherenceFloor = stftCoherenceFloor,
                    stftCoverageFloor = stftCoverageFloor,
                ),
            )
        }
        val stateCandidates = globalCandidates
            .filter { isStateCandidate(it) }
            .sortedByDescending { candidateScore(it) }
            .take(maxStateCandidates)
        for (candidate in stateCandidates) {
            val id = candidate.identity
            val rawFactor = records.map { it.factors[id.factor_i] }
            val rawTarget = records.map { targetRaw(it, id.target_y) }
            val futureTarget = labels.map { targetNext(it, id.target_y, id.horizon) }
            for (slice in stateSlices) {
                buildMetric(
                    ctx = ctx,
                    factor = id.factor_i,
                    target = id.target_y,
                    horizon = id.horizon,
                    band = id.band,
                    rawFactor = rawFactor,
                    rawTarget = rawTarget,
                    futureTarget = futureTarget,
                    stateId = slice.id,
                    stateIndexes = slice.indexes,
                )?.takeIf {
                    (!discoveryFilter || passesDiscoveryFunnel(it)) &&
                        (!stftFilter || passesStftConfirmation(it, stftCoherenceFloor, stftCoverageFloor))
                }
                    ?.let(metrics::add)
            }
        }
        return withBenjaminiHochbergQ(metrics, fdrFamily)
    }

    private fun buildMetric(
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
    ): ResonanceMetric? {
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

        val rolling = rollingCorrelationStats(xFf, yFf, horizon, window = 30)
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
        metricDiscoveryRecent[identity] = rolling.recent
        return ResonanceMetric(
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
    }

    private fun buildPairMetrics(
        ctx: ResearchContext,
        records: List<SentimentFactorDailyRecord>,
        labels: List<org.shiroumi.database.sentiment.SentimentTargetLabels>,
        singleCandidates: List<ResonanceMetric>,
        pairTransforms: List<String>,
        maxPairsPerFamily: Int,
        discoveryFilter: Boolean,
        stftFilter: Boolean,
        stftCoherenceFloor: Double,
        stftCoverageFloor: Double,
    ): List<ResonanceMetric> {
        if (pairTransforms.isEmpty() || maxPairsPerFamily <= 0) return emptyList()
        val metrics = ArrayList<ResonanceMetric>()
        val candidatesByFamily = singleCandidates.groupBy {
            FamilyKey(it.identity.target_y, it.identity.horizon, it.identity.band)
        }
        for ((family, candidates) in candidatesByFamily) {
            val factorScores = candidates
                .groupBy { it.identity.factor_i }
                .mapValues { (_, values) -> values.maxOf(::candidateScore) }
                .entries
                .sortedByDescending { it.value }
            val pairs = factorScores.indices.flatMap { leftIndex ->
                ((leftIndex + 1)..factorScores.lastIndex).map { rightIndex ->
                    PairCandidate(
                        factorI = factorScores[leftIndex].key,
                        factorJ = factorScores[rightIndex].key,
                        score = factorScores[leftIndex].value + factorScores[rightIndex].value,
                    )
                }
            }.sortedByDescending { it.score }
                .take(maxPairsPerFamily)
            if (pairs.isEmpty()) continue

            val rawTarget = records.map { targetRaw(it, family.target) }
            val futureTarget = labels.map { targetNext(it, family.target, family.horizon) }
            for (pair in pairs) {
                val rawI = records.map { it.factors[pair.factorI] }
                val rawJ = records.map { it.factors[pair.factorJ] }
                val baseI = candidates.firstOrNull { it.identity.factor_i == pair.factorI }
                val baseJ = candidates.firstOrNull { it.identity.factor_i == pair.factorJ }
                val baseIc = max(baseI?.oos_ic ?: Double.NEGATIVE_INFINITY, baseJ?.oos_ic ?: Double.NEGATIVE_INFINITY)
                    .takeIf { it.isFinite() } ?: 0.0
                val baseScore = max(baseI?.let(::candidateScore) ?: 0.0, baseJ?.let(::candidateScore) ?: 0.0)
                val beta3 = beta3Stability(rawI, rawJ, futureTarget)
                for (transform in pairTransforms) {
                    val pairRaw = pairRaw(rawI, rawJ, transform)
                    val factorName = "${pair.factorI}_${transform}_${pair.factorJ}"
                    val metric = buildMetric(
                        ctx = ctx,
                        factor = pair.factorI,
                        target = family.target,
                        horizon = family.horizon,
                        band = family.band,
                        rawFactor = pairRaw,
                        rawTarget = rawTarget,
                        futureTarget = futureTarget,
                        stateId = "trend=all,disp=all,vol=all",
                        stateIndexes = null,
                        factorName = factorName,
                        factorType = "pair_$transform",
                        factorJ = pair.factorJ,
                    ) ?: continue
                    if ((!discoveryFilter || passesDiscoveryFunnel(metric)) &&
                        (!stftFilter || passesStftConfirmation(metric, stftCoherenceFloor, stftCoverageFloor))
                    ) {
                        metrics += metric.copy(
                            delta_score_vs_base = candidateScore(metric) - baseScore,
                            delta_ic_vs_base = (metric.oos_ic ?: 0.0) - baseIc,
                            beta3_stability = beta3,
                        )
                    }
                }
            }
        }
        return metrics
    }

    private fun buildStateSlices(records: List<SentimentFactorDailyRecord>, stateWindow: Int): List<StateSlice> {
        val exactStates = records.indices.mapNotNull { index ->
            val state = stateAt(records, index, stateWindow) ?: return@mapNotNull null
            index to state
        }
        val indexesByExact = exactStates.groupBy({ it.second }, { it.first })
        val slices = linkedMapOf<String, MutableSet<Int>>()
        for ((state, indexes) in indexesByExact) {
            val merged = if (indexes.size >= MIN_STATE_SAMPLE) state.toMergedState() else mergeState(state, indexesByExact)
            val mergedIndexes = indexesByExact
                .filterKeys { candidate -> merged.includes(candidate) }
                .values
                .flatten()
            if (mergedIndexes.size >= MIN_OBSERVATION_SAMPLE) {
                slices.getOrPut(merged.id) { linkedSetOf() }.addAll(mergedIndexes)
            }
        }
        return slices.entries
            .map { (id, indexes) -> StateSlice(id = id, indexes = indexes) }
            .sortedBy { it.id }
    }

    private fun stateAt(records: List<SentimentFactorDailyRecord>, index: Int, stateWindow: Int): MarketState? {
        val trendValue = records[index].factors["D4"] ?: records[index].factors["A1"] ?: return null
        val dispersionValue = records[index].factors["B3p"] ?: records[index].factors["B3"] ?: return null
        val volumeEma = ema(records.map { it.factors["A3"] ?: 0.0 }.toDoubleArray(), span = 5)
        val volumeValue = volumeEma[index]
        fun bucket(name: String, value: Double, values: List<Double>): StateBucket? {
            if (values.size < 5) return null
            val rank = values.count { it < value }.toDouble() / values.size
            return when {
                rank < 0.33 -> StateBucket(0, name.split('/')[0])
                rank < 0.67 -> StateBucket(1, name.split('/')[1])
                else -> StateBucket(2, name.split('/')[2])
            }
        }
        val start = max(0, index - stateWindow + 1)
        return MarketState(
            trend = bucket("low/mid/high", trendValue, records.subList(start, index + 1).mapNotNull { it.factors["D4"] ?: it.factors["A1"] })
                ?: return null,
            dispersion = bucket("low/mid/high", dispersionValue, records.subList(start, index + 1).mapNotNull { it.factors["B3p"] ?: it.factors["B3"] })
                ?: return null,
            volume = bucket("low/mid/high", volumeValue, volumeEma.copyOfRange(start, index + 1).toList())
                ?: return null,
        )
    }

    private fun mergeState(state: MarketState, indexesByExact: Map<MarketState, List<Int>>): MergedState {
        val candidates = listOf(
            MergedState(setOf(state.trend.level), adjacentLevels(state.dispersion.level), setOf(state.volume.level)),
            MergedState(setOf(state.trend.level), setOf(state.dispersion.level), adjacentLevels(state.volume.level)),
            MergedState(adjacentLevels(state.trend.level), setOf(state.dispersion.level), setOf(state.volume.level)),
            MergedState(setOf(0, 1, 2), setOf(state.dispersion.level), setOf(state.volume.level)),
            MergedState(setOf(state.trend.level), setOf(0, 1, 2), setOf(state.volume.level)),
            MergedState(setOf(state.trend.level), setOf(state.dispersion.level), setOf(0, 1, 2)),
            MergedState(setOf(0, 1, 2), setOf(0, 1, 2), setOf(0, 1, 2)),
        )
        return candidates.firstOrNull { merged ->
            indexesByExact.filterKeys { merged.includes(it) }.values.sumOf { it.size } >= MIN_STATE_SAMPLE
        } ?: candidates.last()
    }

    private fun MarketState.toMergedState(): MergedState =
        MergedState(setOf(trend.level), setOf(dispersion.level), setOf(volume.level))

    private fun adjacentLevels(level: Int): Set<Int> =
        when (level) {
            0 -> setOf(0, 1)
            1 -> setOf(0, 1, 2)
            else -> setOf(1, 2)
        }

    private fun isStateCandidate(metric: ResonanceMetric): Boolean =
        (metric.mean_coherence ?: 0.0) >= 0.35 ||
            abs(metric.rolling_corr_mean ?: 0.0) >= 0.10 ||
            (metric.oos_ic ?: 0.0) > 0.0

    private fun passesDiscoveryFunnel(metric: ResonanceMetric): Boolean {
        val mean = metric.rolling_corr_mean ?: return false
        val stability = metric.rolling_corr_stability ?: return false
        val recent = metricDiscoveryRecent[metric.identity] ?: return false
        return abs(mean) > 0.10 && stability > 0.55 && (recent == 0.0 || sign(recent) == sign(mean))
    }

    private fun passesStftConfirmation(metric: ResonanceMetric, coherenceFloor: Double, coverageFloor: Double): Boolean {
        val meanCoherence = metric.mean_coherence ?: return false
        val coverage = metric.coherence_coverage ?: return false
        return meanCoherence >= coherenceFloor && coverage >= coverageFloor
    }

    private fun candidateScore(metric: ResonanceMetric): Double =
        (metric.mean_coherence ?: 0.0) +
            abs(metric.rolling_corr_mean ?: 0.0) +
            max(0.0, metric.oos_ic ?: 0.0)

    private fun preprocess(values: DoubleArray): DoubleArray {
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

    private fun ema(values: DoubleArray, span: Int): DoubleArray {
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
        val result = Coherence.compute(x, y, nperseg = nperseg, noverlap = (nperseg * 3) / 4)
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

    private fun shiftedCorrelation(x: DoubleArray, y: DoubleArray, lag: Int): Double? {
        val startX = if (lag >= 0) 0 else -lag
        val startY = if (lag >= 0) lag else 0
        val len = min(x.size - startX, y.size - startY)
        if (len < 3) return null
        return pearson(x.copyOfRange(startX, startX + len), y.copyOfRange(startY, startY + len))
    }

    private fun oosStats(x: DoubleArray, y: DoubleArray): OosStats {
        val pairs = x.indices.map { x[it] to y[it] }
        if (pairs.size < 30) return OosStats(sampleCount = pairs.size)
        val folds = ArrayList<List<Pair<Double, Double>>>()
        if (pairs.size >= 620) {
            var trainStart = 0
            while (trainStart + 500 + 120 <= pairs.size) {
                folds.add(pairs.subList(trainStart + 500, trainStart + 620))
                trainStart += 20
            }
        } else {
            val split = max(1, (pairs.size * 0.7).toInt())
            if (pairs.size - split >= 10) folds.add(pairs.subList(split, pairs.size))
        }
        if (folds.isEmpty()) return OosStats(sampleCount = pairs.size)

        val validation = folds.flatten()
        val trainEnd = pairs.size - validation.size
        val train = pairs.subList(0, max(1, trainEnd))
        val orientation = sign(pearson(train.map { it.first }.toDoubleArray(), train.map { it.second }.toDoubleArray()) ?: 0.0)
            .let { if (it == 0.0) 1.0 else it }
        val oriented = validation.map { (px, py) -> px * orientation to py }
        val pred = oriented.map { it.first }.toDoubleArray()
        val actual = oriented.map { it.second }.toDoubleArray()
        val ic = pearson(pred, actual)
        val rankIc = pearson(ranks(pred), ranks(actual))
        val hitRate = oriented.count { sign(it.first) == sign(it.second) && sign(it.second) != 0.0 }
            .toDouble() / oriented.count { sign(it.second) != 0.0 }.coerceAtLeast(1)
        val positive = oriented.count { it.second > 0.0 }.toDouble() / oriented.size
        val negative = oriented.count { it.second < 0.0 }.toDouble() / oriented.size
        val spreads = folds.mapNotNull { fold ->
            val orientedFold = fold.map { (px, py) -> px * orientation to py }
            topBottomSpread(orientedFold)
        }
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

    private fun withBenjaminiHochbergQ(metrics: List<ResonanceMetric>, fdrFamily: String): List<ResonanceMetric> {
        val qByIndex = DoubleArray(metrics.size) { Double.NaN }
        val groups = metrics.mapIndexedNotNull { index, metric ->
            metric.p_value?.let { IndexedMetric(index, it, fdrKey(metric, fdrFamily)) }
        }.groupBy { it.familyKey }
        for (group in groups.values) {
            val indexed = group.sortedBy { it.pValue }
            var minQ = 1.0
            for (rankIndex in indexed.indices.reversed()) {
                val item = indexed[rankIndex]
                val rank = rankIndex + 1
                minQ = min(minQ, item.pValue * indexed.size / rank)
                qByIndex[item.index] = minQ.coerceIn(0.0, 1.0)
            }
        }
        return metrics.mapIndexed { index, metric ->
            if (qByIndex[index].isNaN()) metric else metric.copy(q_value = qByIndex[index])
        }
    }

    private fun fdrKey(metric: ResonanceMetric, fdrFamily: String): String {
        val id = metric.identity
        return when (fdrFamily) {
            "global" -> "global"
            "target-horizon-band-state" -> "${id.target_y}|h${id.horizon}|${id.band}|${id.state_id}"
            else -> "${id.target_y}|h${id.horizon}|${id.band}"
        }
    }

    private fun pearson(x: DoubleArray, y: DoubleArray): Double? {
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

    private fun targetRaw(record: SentimentFactorDailyRecord, target: String): Double? =
        when (target) {
            "Y1" -> record.y1Raw
            "Y2" -> record.y2Raw
            "Y3" -> record.y3Raw
            else -> null
        }

    private fun targetNext(label: org.shiroumi.database.sentiment.SentimentTargetLabels, target: String, horizon: Int): Double? =
        when (target to horizon) {
            "Y1" to 1 -> label.y1Next1
            "Y1" to 3 -> label.y1Next3
            "Y1" to 5 -> label.y1Next5
            "Y2" to 1 -> label.y2Next1
            "Y2" to 3 -> label.y2Next3
            "Y2" to 5 -> label.y2Next5
            "Y3" to 1 -> label.y3Next1
            "Y3" to 3 -> label.y3Next3
            "Y3" to 5 -> label.y3Next5
            else -> null
        }

    private fun pairRaw(left: List<Double?>, right: List<Double?>, transform: String): List<Double?> =
        left.indices.map { index ->
            val a = left[index]
            val b = right[index]
            if (a == null || b == null) return@map null
            when (transform) {
                "diff" -> a - b
                "product" -> a * b
                "ratio" -> a / (abs(b) + 1e-6)
                else -> null
            }
        }

    private fun beta3Stability(left: List<Double?>, right: List<Double?>, target: List<Double?>): Double? {
        val aligned = left.indices.mapNotNull { index ->
            val a = left[index]
            val b = right[index]
            val y = target[index]
            if (a == null || b == null || y == null) null else Triple(a, b, y)
        }
        if (aligned.size < 80) return null
        val signs = ArrayList<Double>()
        var start = 0
        while (start + 60 <= aligned.size) {
            val window = aligned.subList(start, start + 60)
            interactionBeta3(
                window.map { it.first }.toDoubleArray(),
                window.map { it.second }.toDoubleArray(),
                window.map { it.third }.toDoubleArray(),
            )?.takeIf { it != 0.0 }?.let { signs += sign(it) }
            start += 20
        }
        if (signs.isEmpty()) return null
        val positive = signs.count { it > 0.0 }
        val negative = signs.count { it < 0.0 }
        return max(positive, negative).toDouble() / signs.size
    }

    private fun interactionBeta3(left: DoubleArray, right: DoubleArray, target: DoubleArray): Double? {
        val n = left.size
        if (n < 5 || right.size != n || target.size != n) return null
        val xtx = Array(4) { DoubleArray(4) }
        val xty = DoubleArray(4)
        for (i in 0 until n) {
            val row = doubleArrayOf(1.0, left[i], right[i], left[i] * right[i])
            for (r in row.indices) {
                xty[r] += row[r] * target[i]
                for (c in row.indices) xtx[r][c] += row[r] * row[c]
            }
        }
        return solveLinear(xtx, xty)?.getOrNull(3)
    }

    private fun solveLinear(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray? {
        val n = vector.size
        val a = Array(n) { row -> DoubleArray(n + 1) { col -> if (col < n) matrix[row][col] else vector[row] } }
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { row -> abs(a[row][col]) }
            if (abs(a[pivot][col]) < 1e-12) return null
            val tmp = a[col]
            a[col] = a[pivot]
            a[pivot] = tmp
            val div = a[col][col]
            for (j in col..n) a[col][j] /= div
            for (row in 0 until n) {
                if (row == col) continue
                val factor = a[row][col]
                for (j in col..n) a[row][j] -= factor * a[col][j]
            }
        }
        return DoubleArray(n) { a[it][n] }
    }

    private data class BandSpec(val low: Double, val high: Double)
    private data class RollingStats(val mean: Double?, val stability: Double?, val recent: Double?)
    private data class CoherenceStats(
        val mean: Double?,
        val max: Double?,
        val coverage: Double?,
        val phaseStd: Double?,
        val leadDaysPhase: Double?,
    )
    private data class FamilyKey(val target: String, val horizon: Int, val band: String)
    private data class PairCandidate(val factorI: String, val factorJ: String, val score: Double)
    private data class IndexedMetric(val index: Int, val pValue: Double, val familyKey: String)
    private data class LeadLag(val leadDays: Double, val corr: Double?)
    private data class LagCandidate(val lag: Int, val corr: Double)
    private data class StateBucket(val level: Int, val label: String)
    private data class MarketState(
        val trend: StateBucket,
        val dispersion: StateBucket,
        val volume: StateBucket,
    )
    private data class MergedState(
        val trend: Set<Int>,
        val dispersion: Set<Int>,
        val volume: Set<Int>,
    ) {
        val id: String =
            "trend=${label(trend)},disp=${label(dispersion)},vol=${label(volume)}"

        fun includes(state: MarketState): Boolean =
            state.trend.level in trend && state.dispersion.level in dispersion && state.volume.level in volume

        private fun label(levels: Set<Int>): String =
            levels.sorted().joinToString("+") { LEVEL_LABELS.getValue(it) }
    }
    private data class StateSlice(val id: String, val indexes: Set<Int>)
    private data class OosStats(
        val ic: Double? = null,
        val rankIc: Double? = null,
        val hitRate: Double? = null,
        val baseline: Double? = null,
        val topBottomSpread: Double? = null,
        val topBottomSpreadConsistency: Double? = null,
        val beta: Double? = null,
        val sampleCount: Int? = null,
    )

    companion object {
        private const val MIN_SERIES_SIZE = 60
        private const val MIN_OBSERVATION_SAMPLE = 30
        private const val MIN_STATE_SAMPLE = 60

        private val TARGETS = setOf("Y1", "Y2", "Y3")
        private val PAIR_TRANSFORMS = setOf("diff", "product", "ratio")
        private val LEVEL_LABELS = mapOf(0 to "low", 1 to "mid", 2 to "high")
        private val BANDS = linkedMapOf(
            "F1b" to BandSpec(0.200, 0.333),
            "F2a" to BandSpec(0.125, 0.200),
            "F1a" to BandSpec(0.333, 0.499),
            "F2b" to BandSpec(0.100, 0.125),
        )
        private val FACTOR_NAMES = listOf(
            "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9a", "A9b", "A10", "A11", "A11a", "A12",
            "B1", "B3", "B3p", "B4", "B5", "B6", "B7",
            "C1", "C2", "C2p", "C3", "C4", "C5", "C6", "C7",
            "D1", "D2", "D3", "D4", "D5", "D6", "D7",
            "E1", "E2",
        )
    }
}
