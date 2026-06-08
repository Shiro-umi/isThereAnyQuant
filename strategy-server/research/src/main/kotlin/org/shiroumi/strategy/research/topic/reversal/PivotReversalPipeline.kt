package org.shiroumi.strategy.research.topic.reversal

import org.shiroumi.strategy.research.pipeline.ResearchContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * 趋势反转 topic 的研究管线入口 —— **双向**·未来 ≤7 日反转预测（t−1 信息 → 未来窗反转概率）。
 *
 * 设计依据（SSOT）：`private/research-docs/pivot-reversal-formula.html`。
 * 用户精确定义（2026-05-30）：市场级择时；反转锚点 = 未来 W 日最大回撤/反弹 ≥ k·ATR（自适应阈值）；
 * 双向 —— 顶反（上行域 + 未来大回撤，持多头该减）/ 底反（下行域 + 未来大反弹，空仓该进）；持仓周期 W=7。
 *
 * 对每个方向各跑一条：
 *   [PivotReversalDataset]（装配：t−1 因子层特征 + 双向 7 日 ATR 标签）
 *     → [PivotReversalStudy]（①体检 B + 融合公式 P^rev）
 *     → [PivotReversalEvaluation]（②按判别力口径裁判，落盘，文件名带方向）
 *
 * `--tune` 时各方向用 [PivotReversalTuningHarness]（NelderMead，情绪因子金矿可学权重）先调优再复跑诊断。
 */
object PivotReversalPipeline {

    fun run(ctx: ResearchContext): List<Path> {
        val tune = ctx.param("tune", "false") == "true"
        val walkForward = ctx.param("walk-forward", "false") == "true"
        if (ctx.param("ml-probe", "false") == "true") {
            runCrashMlProbe(ctx)
            val d = ctx.resolve("out/pivot_reversal/.keep").parent
            return Files.list(d).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
        }
        // --direction 指定单方向（top/bottom/crash）；缺省跑 top+bottom（不含 crash，避免干扰反转研究）。
        val directions = when (val d = ctx.param("direction", "")) {
            "" -> listOf(PivotReversalFeatures.Direction.TOP, PivotReversalFeatures.Direction.BOTTOM)
            else -> listOf(PivotReversalFeatures.Direction.valueOf(d.uppercase()))
        }
        for (direction in directions) {
            if (walkForward) runWalkForward(ctx, direction) else runDirection(ctx, direction, tune)
        }
        val dir = ctx.resolve("out/pivot_reversal/.keep").parent
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
        }
    }

    /** Walk-forward 滚动验证：跨全程样本外拼接，真实泛化检验（用户选定）。 */
    private fun runWalkForward(ctx: ResearchContext, direction: PivotReversalFeatures.Direction) {
        val tag = direction.name.lowercase()
        val loaded = PivotReversalDataset.load(ctx, direction)
        // 情绪条件域：crash-regime=low 时只在「情绪研究预测次日衰减」的低亢奋子集内做条件预测——
        // 大阴线几乎全集中于此（实测大阴线率 32% vs 全样本 14%），子集内专训不被无关样本稀释，是双70 唯一可能路径。
        val regimeMax = ctx.param("crash-regime-max", "1.0").toDouble()
        val dataset = if (regimeMax < 1.0)
            PivotReversalDataset(loaded.samples.filter { (it.trendScore) < regimeMax })
        else loaded
        val n = dataset.samples.size
        println("pivot_reversal[$tag]_wf_samples=$n positives=${dataset.positives} (regimeMax=${fmt(regimeMax)})")
        if (n < 600) {
            println("pivot_reversal[$tag]_wf_skip=样本不足，无法 walk-forward")
            return
        }
        // 域内体检 B：在（已过滤的）子集内算单因子 AUC，定位「情绪之外」仍有判别力的因子——
        // trendScore 在低亢奋域失方差，域内 precision 增量必须靠量价/形态/高阶因子。
        if (regimeMax < 1.0 && ctx.param("regime-probe", "false") == "true") {
            val lbl = IntArray(n) { dataset.samples[it].label }
            val keys = dataset.samples.first().extra.keys
            val ranked = keys.map { key ->
                val s = DoubleArray(n) { dataset.samples[it].extra[key] ?: 0.0 }
                key to PivotMetrics.rocAuc(s, lbl)
            }.filter { !it.second.isNaN() }
                .sortedByDescending { kotlin.math.abs(it.second - 0.5) }
                .take(15)
            println("pivot_reversal[crash]_regimeProbe (域内单因子AUC top15, |AUC-0.5|降序):")
            ranked.forEach { (k, a) -> println("  $k = ${fmt(a)}") }
        }
        val harness = PivotReversalTuningHarness(dataset)
        val wf = PivotWalkForward(
            harness = harness,
            samples = dataset.samples,
            initTrain = (n * 0.4).toInt(),                       // 初始用前 40% 历史
            step = ctx.param("wf-step", "60").toInt(),           // 每段约 3 个月
            rebalanceEvery = ctx.param("wf-rebalance", "4").toInt(),  // 每 ~年重调权重
            tuneIter = ctx.param("wf-iter", "150").toInt(),
        )
        val r = wf.run(ctx)
        val posOos = r.oosLabels.count { it == 1 }
        println("pivot_reversal[$tag]_wf_oos_n=${r.oosLabels.size} oos_pos=$posOos folds=${r.foldCount}")
        println("pivot_reversal[$tag]_wf_auc=${fmt(r.auc)} tau=${fmt(r.tau)} P=${fmt(r.precision)} R=${fmt(r.recall)} F1=${fmt(r.f1)} balHit=${fmt(r.balancedHit)}")
        // 精度优先工作点：在 OOS 预测上卡 precision 门，看不同精度要求下能覆盖多少（实战预警口径）。
        for (gate in listOf(0.6, 0.7, 0.8)) {
            val (tau, c) = PivotMetrics.thresholdForPrecision(r.oosScores, r.oosLabels, gate)
            println("pivot_reversal[$tag]_wf_precGate${(gate * 100).toInt()}: tau=${fmt(tau)} P=${fmt(c.precision)} R=${fmt(c.recall)} F1=${fmt(c.f1)} 命中=${c.tp}/${c.tp + c.fp}")
        }
        // 情绪置信度分层（CRASH）：按 t−1 亢奋度 P^up 三分位切子集，看「高亢奋子集」内大阴线是否更可预测——
        // 双70 不必全样本，可在情绪研究划出的高确信子集内达成（条件预测=只在高把握时段预警）。
        if (tag == "crash") {
            val tr = r.oosTrend
            val sortedTr = tr.sorted()
            val q1 = sortedTr[(tr.size * 0.33).toInt()]
            val q2 = sortedTr[(tr.size * 0.67).toInt()]
            val strata = listOf(
                "亢奋低(P^up<${fmt(q1)})" to { v: Double -> v < q1 },
                "亢奋中" to { v: Double -> v in q1..q2 },
                "亢奋高(P^up>${fmt(q2)})" to { v: Double -> v > q2 },
            )
            for ((name, pred) in strata) {
                val idx = tr.indices.filter { pred(tr[it]) }
                if (idx.size < 100) continue
                val ss = DoubleArray(idx.size) { r.oosScores[idx[it]] }
                val ll = IntArray(idx.size) { r.oosLabels[idx[it]] }
                val pos = ll.count { it == 1 }
                val subAuc = PivotMetrics.rocAuc(ss, ll)
                val (_, c70) = PivotMetrics.thresholdForPrecision(ss, ll, 0.70)
                println("pivot_reversal[crash]_strata[$name]: n=${idx.size} 大阴线率=${fmt(pos.toDouble() / idx.size)} subAUC=${fmt(subAuc)} @P70: P=${fmt(c70.precision)} R=${fmt(c70.recall)}")
            }
        }
    }

    private fun fmt(x: Double) = if (x.isNaN()) "NaN" else "${kotlin.math.round(x * 10000) / 10000.0}"

    /**
     * 纯 Kotlin 梯度探针 + **域条件内生化消融**（2026-05-30）。
     *
     * 旧路径（`--crash-regime-max τ`）把「情绪极度转弱域」作**模型外硬门控**（filter 后才训练，丢域外样本）。
     * 本轮把该条件内生为公式可学项，做四档消融对比（全 walk-forward OOS、全样本不丢），数学上分离「择时」与「择日」贡献：
     *   ① baseline       ：纯 logistic（无状态特征、无软门控）—— 全样本基线
     *   ② +regimeState   ：加递推域状态特征 s_t（EWMA 高危记忆）—— 把「域」内生为**特征**（择时信息进 logit）
     *   ③ +softGate      ：加可学乘性软门控 σ(γ(τ−r)) —— 把「域」内生为**结构**（择时项乘在择日概率上）
     *   ④ +both          ：状态特征 + 软门控叠加
     * 软门控是旧硬门控的严格泛化（γ→∞ 退化为硬切），故 ③/④ OOS 数学上不应劣于「硬门控全样本」。
     *
     * `--ablation true` 跑全四档对比；否则跑单档（沿用 `--crash-regime-max` 旧语义，便于回溯历史结果）。
     */
    private fun runCrashMlProbe(ctx: ResearchContext) {
        val loaded = PivotReversalDataset.load(ctx, PivotReversalFeatures.Direction.CRASH)
        if (ctx.param("ablation", "false") == "true") { runCrashAblation(ctx, loaded.samples); return }

        val regimeMax = ctx.param("crash-regime-max", "1.0").toDouble()
        val samples = if (regimeMax < 1.0) loaded.samples.filter { it.trendScore < regimeMax } else loaded.samples
        val n = samples.size
        if (n < 600) { println("pivot_reversal[crash_ml]_skip=样本不足($n)"); return }
        val keys = CrashLogisticModel.allFeatureKeys(samples.first())   // 吃全部因子
        // open5m 单因子探针：逐个 open5m 因子算对大阴线标签的翻号 AUC（按 |AUC−0.5| 降序）。
        // 回答「开盘5min是否反向/有无信息」：AUC>0.5=正向(越大越易大阴线)、<0.5=反向、≈0.5=噪声。
        // logistic 自动定权重符号，故方向无所谓；此探针看的是「单因子判别力幅度」是否非零。
        val probePrefix = when {
            ctx.param("osc", "false") == "true" -> "osc"
            ctx.param("open5m", "false") == "true" -> "o5"
            else -> null
        }
        if (probePrefix != null) {
            val lbl = IntArray(n) { samples[it].label }
            val ranked = keys.filter { it.startsWith(probePrefix) }.map { key ->
                val s = DoubleArray(n) { CrashLogisticModel.featureVector(samples[it], listOf(key))[0] }
                key to PivotMetrics.rocAuc(s, lbl)
            }.sortedByDescending { kotlin.math.abs(it.second - 0.5) }
            println("pivot_reversal[crash_ml]_${probePrefix}Probe (单因子AUC, |AUC-0.5|降序; >0.5正向/<0.5反向/≈0.5噪声):")
            ranked.forEach { (k, a) -> println("  $k = ${fmt(a)} ${if (a > 0.5) "正向" else if (a < 0.5) "反向" else "中性"}") }
        }
        val l2 = ctx.param("ml-l2", "0.02").toDouble()
        val lr = ctx.param("ml-lr", "0.05").toDouble()
        val iter = ctx.param("ml-iter", "400").toInt()
        val useGate = ctx.param("ml-gate", "false") == "true"
        val r = runMlWalkForward(ctx, samples, keys, l2, lr, iter, useGate, lossKindOf(ctx), ctx.param("fbeta", "0.5").toDouble())
        println("pivot_reversal[crash_ml]_oos_n=${r.labels.size} oos_pos=${r.labels.count { it == 1 }} folds=${r.folds} k=${keys.size} l2=$l2 gate=$useGate loss=${ctx.param("ml-loss", "focal")} (regimeMax=${fmt(regimeMax)})")
        reportMl("crash_ml", r)

        // 导出可上线 baseline 模型（--export-baseline true）：用全样本训最终模型，阈值取自上面 OOS 工作点。
        if (ctx.param("export-baseline", "false") == "true") {
            exportBaseline(ctx, samples, keys, l2, lr, iter, r)
        }
    }

    /**
     * 导出极端行情预测 baseline：全样本训最终 [CrashLogisticModel]（不启门控），把权重/偏置/因子键
     * + 多档工作点阈值（来自 walk-forward OOS [r]，泛化口径）落盘成 [CrashBaselineModel] JSON，供实盘加载。
     */
    private fun exportBaseline(
        ctx: ResearchContext,
        samples: List<PivotReversalFeatures.Sample>,
        keys: List<String>,
        l2: Double, lr: Double, iter: Int,
        oos: MlOos,
    ) {
        val model = CrashLogisticModel(samples, keys, l2 = l2, useGate = false, lossKind = lossKindOf(ctx))
        model.train(maxIter = iter, learningRate = lr, patience = 60)

        // 工作点阈值取自 OOS（泛化口径，避免用全样本拟合阈值乐观偏差）。
        val thresholds = buildList<CrashBaselineModel.Threshold> {
            for (p in listOf(0.6, 0.7, 0.8)) {
                val (tau, c) = PivotMetrics.thresholdForPrecision(oos.scores, oos.labels, p)
                add(CrashBaselineModel.Threshold("precision${(p * 100).toInt()}", tau, c.precision, c.recall))
            }
            for (rg in listOf(0.85, 0.90, 0.95)) {
                val (tau, c) = PivotMetrics.thresholdForRecall(oos.scores, oos.labels, rg)
                add(CrashBaselineModel.Threshold("recall${(rg * 100).toInt()}", tau, c.precision, c.recall))
            }
        }
        val baseline = CrashBaselineModel(
            meta = CrashBaselineModel.Meta(
                name = "crash_baseline_osc_hurst",
                trainStart = ctx.startDate.toString(),
                trainEnd = ctx.endDate.toString(),
                sampleCount = samples.size,
                positiveCount = samples.count { it.label == 1 },
                featureCount = keys.size,
                l2 = l2,
                wfAuc = PivotMetrics.rocAuc(oos.scores, oos.labels),
                crashThreshold = ctx.param("crash-threshold", "0.01").toDouble(),
                note = "极端行情(大阴线)预测baseline：t-1日频因子+技术振荡器(RSI/%B/Z/ADX)+Hurst状态门控。",
            ),
            featureKeys = keys,
            weights = model.bestWeights.toList(),
            bias = model.bestBias,
            thresholds = thresholds,
        )
        val out = ctx.resolve("baseline/crash_baseline.json")
        java.nio.file.Files.writeString(out, baseline.toJson())
        println("pivot_reversal[crash_ml]_baseline_exported=$out k=${keys.size} thresholds=${thresholds.size}")
    }

    /** 单档纯 Kotlin 梯度 walk-forward：anchored 扩展训练，逐窗 OOS 拼接。gateTau/gateGamma 为各窗学得门参数均值（useGate 时）。 */
    private data class MlOos(
        val scores: DoubleArray, val labels: IntArray, val trend: DoubleArray, val folds: Int,
        val gateTau: Double = Double.NaN, val gateGamma: Double = Double.NaN,
        // 软门控对域外样本的平均门开度：若 ≈1 则门退化为冗余（实证「条件已被 logit 线性吸收」）。
        val gateMeanOpenHi: Double = Double.NaN,   // P^up>0.5（理应被门压低）样本上的平均门开度
        val gateMeanOpenLo: Double = Double.NaN,   // P^up<0.077（深域）样本上的平均门开度
    )

    private fun lossKindOf(ctx: ResearchContext): CrashLogisticModel.LossKind =
        if (ctx.param("ml-loss", "focal").lowercase() == "fbeta")
            CrashLogisticModel.LossKind.SOFT_FBETA else CrashLogisticModel.LossKind.FOCAL

    private fun runMlWalkForward(
        ctx: ResearchContext,
        samples: List<PivotReversalFeatures.Sample>,
        keys: List<String>,
        l2: Double, lr: Double, iter: Int, useGate: Boolean,
        lossKind: CrashLogisticModel.LossKind = CrashLogisticModel.LossKind.FOCAL,
        fBeta: Double = 0.5,
    ): MlOos {
        val n = samples.size
        val step = ctx.param("wf-step", "60").toInt()
        val initTrain = (n * 0.4).toInt()
        val oosScores = ArrayList<Double>(); val oosLabels = ArrayList<Int>(); val oosTrend = ArrayList<Double>()
        var cut = initTrain; var folds = 0
        var tauSum = 0.0; var gammaSum = 0.0; var openHiSum = 0.0; var openHiN = 0; var openLoSum = 0.0; var openLoN = 0
        while (cut < n) {
            val trainWin = samples.subList(0, cut)
            val end = minOf(cut + step, n)
            val predictWin = samples.subList(cut, end)
            val model = CrashLogisticModel(trainWin, keys, l2 = l2, useGate = useGate, lossKind = lossKind, fBeta = fBeta)
            model.train(maxIter = iter, learningRate = lr, patience = 60)
            for (sm in predictWin) { oosScores += model.scoreOf(sm); oosLabels += sm.label; oosTrend += sm.trendScore }
            if (useGate) {
                tauSum += model.bestGateTau; gammaSum += model.bestGateGamma
                // 门开度 g = σ(γ(τ−r))：r 大（高亢奋）应被压低；r 小（深域）应≈1。
                for (sm in predictWin) {
                    val g = 1.0 / (1.0 + kotlin.math.exp(-model.bestGateGamma * (model.bestGateTau - sm.trendScore)))
                    if (sm.trendScore > 0.5) { openHiSum += g; openHiN++ }
                    if (sm.trendScore < 0.077) { openLoSum += g; openLoN++ }
                }
            }
            folds++; cut = end
        }
        return MlOos(
            oosScores.toDoubleArray(), oosLabels.toIntArray(), oosTrend.toDoubleArray(), folds,
            gateTau = if (useGate) tauSum / folds else Double.NaN,
            gateGamma = if (useGate) gammaSum / folds else Double.NaN,
            gateMeanOpenHi = if (openHiN > 0) openHiSum / openHiN else Double.NaN,
            gateMeanOpenLo = if (openLoN > 0) openLoSum / openLoN else Double.NaN,
        )
    }

    /** 输出一档梯度 OOS 的 AUC / 精度门 / 深域分层。 */
    private fun reportMl(tag: String, r: MlOos) {
        val auc = PivotMetrics.rocAuc(r.scores, r.labels)
        println("pivot_reversal[$tag]_wf_auc=${fmt(auc)}")
        if (!r.gateTau.isNaN()) {
            // 实证门是否退化：若高亢奋样本门开度仍≈1，说明门没起到「压低域外」作用——域信息已被 logit 线性吸收。
            println("pivot_reversal[$tag]_gate: tau=${fmt(r.gateTau)} gamma=${fmt(r.gateGamma)} 门开度[P^up>0.5]=${fmt(r.gateMeanOpenHi)} 门开度[深域P^up<0.077]=${fmt(r.gateMeanOpenLo)}")
        }
        for (gate in listOf(0.6, 0.7, 0.8)) {
            val (tau, c) = PivotMetrics.thresholdForPrecision(r.scores, r.labels, gate)
            println("pivot_reversal[$tag]_precGate${(gate * 100).toInt()}: tau=${fmt(tau)} P=${fmt(c.precision)} R=${fmt(c.recall)} 命中=${c.tp}/${c.tp + c.fp}")
        }
        // 召回门：舍弃精度、把 recall 推到目标值时，付出的 precision 代价（预警次数=tp+fp）。
        for (rg in listOf(0.85, 0.90, 0.95)) {
            val (tau, c) = PivotMetrics.thresholdForRecall(r.scores, r.labels, rg)
            println("pivot_reversal[$tag]_recallGate${(rg * 100).toInt()}: tau=${fmt(tau)} R=${fmt(c.recall)} P=${fmt(c.precision)} 预警=${c.tp + c.fp}次 抓住=${c.tp}/${c.tp + c.fn}")
        }
        val deep = r.trend.indices.filter { r.trend[it] < 0.077 }
        if (deep.size >= 100) {
            val ds = DoubleArray(deep.size) { r.scores[deep[it]] }; val dl = IntArray(deep.size) { r.labels[deep[it]] }
            val (_, c) = PivotMetrics.thresholdForPrecision(ds, dl, 0.70)
            println("pivot_reversal[$tag]_deep(P^up<0.077): n=${deep.size} subAUC=${fmt(PivotMetrics.rocAuc(ds, dl))} @P70: P=${fmt(c.precision)} R=${fmt(c.recall)}")
        }
    }

    /** 四档消融对比：全样本 walk-forward，分离「域作特征」与「域作门控」对 P80@高召回的边际贡献。 */
    private fun runCrashAblation(ctx: ResearchContext, all: List<PivotReversalFeatures.Sample>) {
        val n = all.size
        if (n < 600) { println("pivot_reversal[crash_abl]_skip=样本不足($n)"); return }
        val l2 = ctx.param("ml-l2", "0.02").toDouble()
        val lr = ctx.param("ml-lr", "0.05").toDouble()
        val iter = ctx.param("ml-iter", "400").toInt()
        val full = CrashLogisticModel.allFeatureKeys(all.first())
        val noState = full.filterNot { it.startsWith("regimeState") }   // 剔除递推状态特征（含 _d1/_d2 与交互）
        println("pivot_reversal[crash_abl]_n=$n pos=${all.count { it.label == 1 }} kFull=${full.size} kNoState=${noState.size} l2=$l2")
        val archs = listOf(
            Quad("①baseline", noState, false),
            Quad("②+regimeState", full, false),
            Quad("③+softGate", noState, true),
            Quad("④+both", full, true),
        )
        for (a in archs) {
            val r = runMlWalkForward(ctx, all, a.keys, l2, lr, iter, a.gate)
            println("---- ablation[${a.name}] k=${a.keys.size} gate=${a.gate} oos_n=${r.labels.size} pos=${r.labels.count { it == 1 }} ----")
            reportMl("crash_abl${a.name}", r)
        }
    }

    private data class Quad(val name: String, val keys: List<String>, val gate: Boolean)

    private fun runDirection(ctx: ResearchContext, direction: PivotReversalFeatures.Direction, tune: Boolean) {
        val tag = direction.name.lowercase()
        val dataset = PivotReversalDataset.load(ctx, direction)
        println("pivot_reversal[$tag]_samples=${dataset.samples.size} positives=${dataset.positives}")

        val params = if (tune) {
            val harness = PivotReversalTuningHarness(dataset)
            val (best, result) = harness.tune(ctx, maxIter = ctx.param("tune-iter", "400").toInt())
            println("pivot_reversal[$tag]_tune_best_score=${result.best.observation.score}")
            println("pivot_reversal[$tag]_tune_detail=${result.best.observation.detail}")
            best
        } else {
            PivotReversalScorer.Params()
        }

        val report = PivotReversalStudy(params, direction, preloaded = dataset).run(ctx, Unit)
        PivotReversalEvaluation(tag = tag).run(ctx, report)
    }
}
