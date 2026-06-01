package org.shiroumi.strategy.research.topic.reversal

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.shiroumi.strategy.research.pipeline.ResearchContext
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 反转数据集的**本地装配缓存** —— 把「触库 + 全市场聚合」的一次性昂贵装配结果落盘复用。
 *
 * 动机（2026-06-01）：[PivotReversalDataset.load] 每次 run 都从 MySQL 重读情绪因子日表 + 市值加权聚合
 * 市场级序列（开盘 5min 路径还要逐股拉日 K 再加权），单次约 40–60s；而 walk-forward 的 39 个 fold 反复
 * 切的是**同一份不变的 samples**。把装配结果按「区间 + 方向 + 是否含 open5m + 标签口径参数」做 key 缓存到
 * [ResearchContext.workspace] 下，命中即跳过整段 DB 读取与聚合——让「换 loss / 换因子 / 换学习率」这类
 * **数据底座不变、只调模型**的迭代从分钟级降到秒级。这是把训练循环提速到可快速试错的真正落点
 * （瓶颈在装配 IO，不在 156 维 logistic 的梯度算力，GPU 无从着力）。
 *
 * 边界：缓存只包裹 [PivotReversalDataset.load] 这一个触库入口，纯内存的 `assemble` 与下游模型完全不变。
 * `--cache false` 可强制重建（数据底座变更或排查缓存一致性时用）。
 */
internal object PivotReversalDatasetCache {

    private val json = Json { ignoreUnknownKeys = true }

    /** 命中缓存则反序列化返回，否则调 [build] 装配并写缓存。`--cache false` 时直接 build 不读写。 */
    fun loadOrBuild(
        ctx: ResearchContext,
        direction: PivotReversalFeatures.Direction,
        build: () -> PivotReversalDataset,
    ): PivotReversalDataset {
        if (ctx.param("cache", "true") != "true") return build()

        val file = ctx.resolve("dataset_cache/${cacheKey(ctx, direction)}.json")
        if (file.exists()) {
            runCatching {
                val dto = json.decodeFromString<CachedDataset>(file.readText())
                println("pivot_reversal[cache]_hit=$file samples=${dto.samples.size}")
                return PivotReversalDataset(dto.samples.map { it.toSample() })
            }.onFailure { println("pivot_reversal[cache]_corrupt=$file (${it.message})，重建") }
        }

        val dataset = build()
        runCatching {
            val dto = CachedDataset(dataset.samples.map { CachedSample.from(it) })
            Files.createDirectories(file.parent)
            file.writeText(json.encodeToString(dto))
            println("pivot_reversal[cache]_write=$file samples=${dataset.samples.size}")
        }.onFailure { println("pivot_reversal[cache]_write_failed=${it.message}") }
        return dataset
    }

    /** 缓存 key：决定 samples 内容的全部装配输入（区间 + 方向 + open5m + 标签口径参数）。 */
    private fun cacheKey(ctx: ResearchContext, direction: PivotReversalFeatures.Direction): String {
        val open5m = ctx.param("open5m", "false")
        val open5mMode = ctx.param("open5m-mode", "full")
        val atrK = ctx.param("atr-k", "1.5")
        val futureWin = ctx.param("future-win", "7")
        val crash = ctx.param("crash-threshold", "0.01")
        val mode = if (open5m == "true") open5mMode else "na"
        val osc = if (ctx.param("osc", "false") == "true") ctx.param("osc-mode", "base") else "0"
        return "${direction.name}_${ctx.startDate}_${ctx.endDate}_o5${open5m}-$mode" +
            "_osc${osc}_atr${atrK}_fw${futureWin}_ct$crash"
    }

    @Serializable
    private data class CachedDataset(val samples: List<CachedSample>)

    /** [PivotReversalFeatures.Sample] 的可序列化镜像：LocalDate→epochDay，其余原样。 */
    @Serializable
    private data class CachedSample(
        val epochDay: Long,
        val label: Int,
        val regimeUp: Boolean,
        val labelTop: Int,
        val labelBot: Int,
        val zNegRIntra: Double,
        val zPcloseGap: Double,
        val zSUpper: Double,
        val zDoc: Double,
        val deltaIntra: Double,
        val zVp1: Double,
        val zNegVp2c: Double,
        val zVp2v: Double,
        val zNegVpBeta: Double,
        val zVdot: Double,
        val trendScore: Double,
        val extra: Map<String, Double>,
    ) {
        fun toSample(): PivotReversalFeatures.Sample = PivotReversalFeatures.Sample(
            tradeDate = LocalDate.fromEpochDays(epochDay.toInt()),
            label = label, regimeUp = regimeUp, labelTop = labelTop, labelBot = labelBot,
            zNegRIntra = zNegRIntra, zPcloseGap = zPcloseGap, zSUpper = zSUpper, zDoc = zDoc,
            deltaIntra = deltaIntra, zVp1 = zVp1, zNegVp2c = zNegVp2c, zVp2v = zVp2v,
            zNegVpBeta = zNegVpBeta, zVdot = zVdot, trendScore = trendScore, extra = extra,
        )

        companion object {
            fun from(s: PivotReversalFeatures.Sample): CachedSample = CachedSample(
                epochDay = s.tradeDate.toEpochDays().toLong(),
                label = s.label, regimeUp = s.regimeUp, labelTop = s.labelTop, labelBot = s.labelBot,
                zNegRIntra = s.zNegRIntra, zPcloseGap = s.zPcloseGap, zSUpper = s.zSUpper, zDoc = s.zDoc,
                deltaIntra = s.deltaIntra, zVp1 = s.zVp1, zNegVp2c = s.zNegVp2c, zVp2v = s.zVp2v,
                zNegVpBeta = s.zNegVpBeta, zVdot = s.zVdot, trendScore = s.trendScore, extra = s.extra,
            )
        }
    }
}
