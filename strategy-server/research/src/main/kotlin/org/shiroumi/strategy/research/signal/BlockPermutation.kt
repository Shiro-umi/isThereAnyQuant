package org.shiroumi.strategy.research.signal

import kotlin.random.Random

/**
 * 块置换（Block Permutation）显著性检验。
 *
 * 对应执行手册 §8：把序列按 block_size 切块后整块打乱，破坏跨块的时序相关，
 * 但保留块内自相关结构，从而得到"无真实领先关系"的零分布，用来给观测统计量算经验 p 值。
 *
 * block_size 按频带自适应（手册 §8 表）：F1a=5、F1b=10、F2a=20、F2b=20。
 *
 * 这是纯数学原语：不关心被检验的统计量是什么（相干性 / IC / 命中率…），
 * 只负责"按块重排 + 跑统计量 + 算经验 p 值"。具体统计量由研究内容以 lambda 传入。
 */
object BlockPermutation {

    /** 各频带推荐 block_size（手册 §8）。 */
    val blockSizeByBand: Map<String, Int> = mapOf(
        "F1a" to 5,
        "F1b" to 10,
        "F2a" to 20,
        "F2b" to 20,
    )

    /**
     * 置换检验结果。
     *
     * @property observed  观测统计量（未置换）
     * @property pValue     经验 p 值（单侧，右尾）：`(#{null >= observed} + 1) / (B + 1)`
     * @property nullMean   零分布均值（诊断用）
     * @property iterations 置换次数 B
     */
    data class Result(
        val observed: Double,
        val pValue: Double,
        val nullMean: Double,
        val iterations: Int,
    )

    /**
     * 对 [series] 做块置换检验。
     *
     * @param series    待检验序列（例如某频带的相干性时间序列、或对齐后的因子-收益对的派生量）
     * @param blockSize 块长度（按频带取，见 [blockSizeByBand]）
     * @param iterations 置换次数 B（手册常用 1000）
     * @param seed      随机种子，保证可复现（来自 ResearchContext.randomSeed）
     * @param statistic 在（可能被置换的）序列上计算的检验统计量；右尾越大越显著
     */
    fun test(
        series: DoubleArray,
        blockSize: Int,
        iterations: Int = 1000,
        seed: Long = 0L,
        statistic: (DoubleArray) -> Double,
    ): Result {
        require(blockSize in 1..series.size) { "blockSize=$blockSize 超出序列长度 ${series.size}" }
        require(iterations >= 1) { "iterations 必须 >= 1：$iterations" }

        val observed = statistic(series)
        val rng = Random(seed)
        var geCount = 0
        var nullSum = 0.0
        repeat(iterations) {
            val permuted = permuteBlocks(series, blockSize, rng)
            val stat = statistic(permuted)
            if (stat >= observed) geCount++
            nullSum += stat
        }
        return Result(
            observed = observed,
            pValue = (geCount + 1.0) / (iterations + 1.0),
            nullMean = nullSum / iterations,
            iterations = iterations,
        )
    }

    /** 把序列切成 [blockSize] 的块（末块可不足），整块随机重排后拼接。 */
    fun permuteBlocks(series: DoubleArray, blockSize: Int, rng: Random): DoubleArray {
        val blocks = ArrayList<IntRange>()
        var s = 0
        while (s < series.size) {
            blocks.add(s until minOf(s + blockSize, series.size))
            s += blockSize
        }
        blocks.shuffle(rng)
        val out = DoubleArray(series.size)
        var w = 0
        for (blk in blocks) {
            for (i in blk) {
                out[w++] = series[i]
            }
        }
        return out
    }
}
