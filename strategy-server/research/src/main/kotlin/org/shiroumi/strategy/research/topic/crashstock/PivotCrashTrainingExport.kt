package org.shiroumi.strategy.research.topic.crashstock

import kotlinx.datetime.LocalDate
import org.shiroumi.database.sentiment.SentimentFactorDailyRepository
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

/**
 * pivot-crash-stock 的 Input -> Training 边界导出器。
 *
 * 导出真实日频时序样本 `x=[n,seq_len,feature_dim]`：
 * 每步 = 个股截面特征 + 同日市场情绪因子，供 PyTorch/MPS Training 子段训练。
 */
fun main() {
    val startY = System.getProperty("quant.cte.startY")?.toIntOrNull() ?: 2024
    val endY = System.getProperty("quant.cte.endY")?.toIntOrNull() ?: 2026
    val fwd = System.getProperty("quant.cte.fwd")?.toIntOrNull() ?: 3
    val seqLen = System.getProperty("quant.cte.seqLen")?.toIntOrNull() ?: 20
    val includeMarket = System.getProperty("quant.cte.market")?.toBooleanStrictOrNull() ?: true
    val maxSamples = System.getProperty("quant.cte.maxSamples")?.toIntOrNull() ?: 200_000
    val labelKind = when ((System.getProperty("quant.cte.label") ?: "resid").lowercase()) {
        "abs" -> PivotCrashStockSample.LabelKind.ABS
        "rel" -> PivotCrashStockSample.LabelKind.REL
        else -> PivotCrashStockSample.LabelKind.RESID
    }
    val outDir = Path.of(
        System.getProperty(
            "quant.cte.out",
            "research/pivot-crash-stock/training/feasibility-${System.currentTimeMillis()}",
        ),
    ).toAbsolutePath()
    Files.createDirectories(outDir)

    val start = LocalDate(startY, 1, 1)
    val end = LocalDate(endY, 6, 30)
    println("# pivot-crash-stock Training dataset export")
    println("range=$start..$end fwd=$fwd seqLen=$seqLen market=$includeMarket label=$labelKind maxSamples=$maxSamples out=$outDir")

    val loaded = PivotCrashStockDataset.load(start, end)
    val samples = PivotCrashStockSample.assemble(
        seriesByTs = loaded.seriesByTs,
        diList = loaded.diList,
        dateOfDi = loaded.dateOfDi,
        stProfile = loaded.stProfile,
        listDiByTs = loaded.listDiByTs,
        fwd = fwd,
    )
    val marketFeatureNames = if (includeMarket) listOf(
        "A1", "A2", "A3", "A4", "A5",
        "B1", "B3", "B3p", "B4", "B5",
        "C1", "C2", "C2p", "C3",
        "D1", "D2", "D3", "D4", "D5",
        "E1", "E2", "VPM_ret", "VPM_turn",
    ) else emptyList()
    val marketByDate = loadMarketFeatures(start, end, marketFeatureNames)
    require(samples.isNotEmpty()) { "No samples exported; check DB facts and date range." }
    val orderedSamples = samples.sortedWith(compareBy<PivotCrashStockSample.Sample> { it.tradeDate }.thenBy { it.tsCode })
    val totalSequences = countSequences(orderedSamples, marketByDate, seqLen)
    require(totalSequences > 0) { "No sequence samples exported; check seqLen=$seqLen and sentiment_factor_daily coverage." }
    val n = min(maxSamples, totalSequences)
    val selectedPositions = evenPositions(totalSequences, n)
    val featureDim = PivotCrashStockSample.NF + marketFeatureNames.size
    val x = FloatArray(n * seqLen * featureDim)
    val y = FloatArray(n)
    fillSelectedSequences(orderedSamples, marketByDate, marketFeatureNames.size, seqLen, labelKind, selectedPositions, x, y)

    writeNpz(outDir.resolve("dataset.npz"), mapOf(
        "x" to NpyArray(x, intArrayOf(n, seqLen, featureDim)),
        "y" to NpyArray(y, intArrayOf(n)),
    ))
    Files.writeString(outDir.resolve("feature_schema.json"), featureSchemaJson(labelKind, fwd, seqLen, marketFeatureNames), StandardCharsets.UTF_8)
    Files.writeString(outDir.resolve("normalization.json"), normalizationJson(), StandardCharsets.UTF_8)
    val positives = y.count { it > 0.5f }
    println("exported dataset=${outDir.resolve("dataset.npz")} n=$n positives=$positives posRate=${"%.4f".format(positives.toDouble() / n)}")
    println("featureSchema=${outDir.resolve("feature_schema.json")}")
    println("normalization=${outDir.resolve("normalization.json")}")
}

private data class NpyArray(val values: FloatArray, val shape: IntArray)
private fun evenPositions(total: Int, n: Int): LongArray {
    if (n >= total) return LongArray(total) { it.toLong() }
    if (n <= 1) return longArrayOf(0L)
    val step = (total - 1).toDouble() / (n - 1)
    return LongArray(n) { (it * step).toLong() }
}

private fun loadMarketFeatures(
    start: LocalDate,
    end: LocalDate,
    names: List<String>,
): Map<LocalDate, FloatArray> {
    val records = SentimentFactorDailyRepository.findBetween(start, end)
    val rawRows = records.map { record ->
        record.tradeDate to DoubleArray(names.size) { i ->
            when (val name = names[i]) {
                "VPM_ret" -> record.vpmRet ?: 0.0
                "VPM_turn" -> record.vpmTurn ?: 0.0
                else -> record.factors[name] ?: 0.0
            }
        }
    }
    if (rawRows.isEmpty()) return emptyMap()
    val mean = DoubleArray(names.size)
    val sd = DoubleArray(names.size)
    for ((_, row) in rawRows) for (i in names.indices) mean[i] += row[i]
    for (i in names.indices) mean[i] /= rawRows.size
    for ((_, row) in rawRows) for (i in names.indices) {
        val d = row[i] - mean[i]
        sd[i] += d * d
    }
    for (i in names.indices) {
        sd[i] = kotlin.math.sqrt(sd[i] / maxOf(1, rawRows.size - 1)).let { if (it == 0.0) 1.0 else it }
    }
    return rawRows.associate { (date, row) ->
        date to FloatArray(names.size) { i -> ((row[i] - mean[i]) / sd[i]).coerceIn(-5.0, 5.0).toFloat() }
    }
}

private fun countSequences(
    samples: List<PivotCrashStockSample.Sample>,
    marketByDate: Map<LocalDate, FloatArray>,
    seqLen: Int,
): Int {
    val countsByTs = HashMap<String, Int>()
    var total = 0
    for (sample in samples) {
        if (!marketByDate.containsKey(sample.tradeDate)) {
            countsByTs.remove(sample.tsCode)
            continue
        }
        val count = (countsByTs[sample.tsCode] ?: 0) + 1
        countsByTs[sample.tsCode] = count
        if (count >= seqLen) total++
    }
    return total
}

private fun fillSelectedSequences(
    samples: List<PivotCrashStockSample.Sample>,
    marketByDate: Map<LocalDate, FloatArray>,
    marketDim: Int,
    seqLen: Int,
    labelKind: PivotCrashStockSample.LabelKind,
    selectedPositions: LongArray,
    x: FloatArray,
    y: FloatArray,
) {
    val featureDim = PivotCrashStockSample.NF + marketDim
    val buffersByTs = HashMap<String, ArrayDeque<Pair<FloatArray, Float>>>()
    var sequenceIndex = 0L
    var selectedCursor = 0
    for (sample in samples) {
        val market = marketByDate[sample.tradeDate]
        if (market == null) {
            buffersByTs.remove(sample.tsCode)
            continue
        }
        val row = FloatArray(featureDim)
        for (j in 0 until PivotCrashStockSample.NF) row[j] = sample.features[j].toFloat()
        for (j in 0 until marketDim) row[PivotCrashStockSample.NF + j] = market[j]
        val buffer = buffersByTs.getOrPut(sample.tsCode) { ArrayDeque() }
        buffer.addLast(row to sample.labelBy(labelKind).toFloat())
        while (buffer.size > seqLen) buffer.removeFirst()
        if (buffer.size == seqLen) {
            if (selectedCursor < selectedPositions.size && sequenceIndex == selectedPositions[selectedCursor]) {
                var t = 0
                for ((seqRow, _) in buffer) {
                    for (j in 0 until featureDim) x[(selectedCursor * seqLen + t) * featureDim + j] = seqRow[j]
                    t++
                }
                y[selectedCursor] = buffer.last().second
                selectedCursor++
            }
            sequenceIndex++
            if (selectedCursor >= selectedPositions.size) return
        }
    }
}

private fun writeNpz(path: Path, arrays: Map<String, NpyArray>) {
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        for ((name, array) in arrays) {
            zip.putNextEntry(ZipEntry("$name.npy"))
            zip.write(npyBytes(array))
            zip.closeEntry()
        }
    }
}

private fun npyBytes(array: NpyArray): ByteArray {
    val shape = array.shape.joinToString(", ", postfix = if (array.shape.size == 1) "," else "")
    val dict = "{'descr': '<f4', 'fortran_order': False, 'shape': ($shape), }"
    val magicLen = 10
    val baseHeaderLen = dict.length + 1
    val padding = (16 - ((magicLen + baseHeaderLen) % 16)) % 16
    val header = dict + " ".repeat(padding) + "\n"
    val body = ByteBuffer.allocate(array.values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    array.values.forEach { body.putFloat(it) }
    return ByteArrayOutputStream().use { out ->
        out.write(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))
        out.write(byteArrayOf(1, 0))
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(header.length.toShort()).array())
        out.write(header.toByteArray(StandardCharsets.US_ASCII))
        out.write(body.array())
        out.toByteArray()
    }
}

private fun featureSchemaJson(
    labelKind: PivotCrashStockSample.LabelKind,
    fwd: Int,
    seqLen: Int,
    marketFeatureNames: List<String>,
): String =
    buildString {
        appendLine("{")
        appendLine("  \"topic\": \"pivot-crash-stock\",")
        appendLine("  \"formatVersion\": 1,")
        appendLine("  \"seqLen\": $seqLen,")
        appendLine("  \"labelKind\": \"$labelKind\",")
        appendLine("  \"targetHorizon\": $fwd,")
        val names = PivotCrashStockSample.FEATURE_NAMES + marketFeatureNames.map { "market.$it" }
        appendLine("  \"featureNames\": [")
        names.forEachIndexed { i, name ->
            append("    \"").append(name).append("\"")
            appendLine(if (i == names.lastIndex) "" else ",")
        }
        appendLine("  ]")
        appendLine("}")
    }

private fun normalizationJson(): String =
    """
    {
      "formatVersion": 1,
      "owner": "PivotCrashStockSample",
      "method": "cross_section_zscore",
      "winsor": [-5.0, 5.0],
      "missingFill": 0.0,
      "note": "Current feasibility export uses already normalized single-step features from Kotlin Input stage."
    }
    """.trimIndent() + "\n"
