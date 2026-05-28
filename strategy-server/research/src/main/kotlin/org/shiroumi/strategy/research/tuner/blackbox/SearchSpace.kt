package org.shiroumi.strategy.research.tuner.blackbox

/**
 * 黑盒优化的搜索空间。
 *
 * - [ContinuousSpace]：连续维度，范围 + 初值，由 Nelder-Mead / SPSA 等无导数连续优化器消费
 * - [DiscreteSpace]：离散维度，枚举集合，由 Hill Climbing / Simulated Annealing 等离散优化器消费
 *
 * 维度的 [Dim.key] 用于序列化回 `Map<String, String>`，与
 * [org.shiroumi.strategy.research.pipeline.ResearchContext.params] 同构。
 */
sealed interface SearchSpace {
    val dims: List<Dim>

    /** 校验合法性（构造期一次性检查）。 */
    fun validate()
}

sealed interface Dim {
    val key: String
}

/**
 * 连续维度：`[lower, upper]` 闭区间内的实数。
 */
data class ContinuousDim(
    override val key: String,
    val lower: Double,
    val upper: Double,
    val initial: Double,
) : Dim {
    init {
        require(lower.isFinite() && upper.isFinite()) { "$key: 边界必须有限" }
        require(lower < upper) { "$key: lower=$lower 必须 < upper=$upper" }
        require(initial in lower..upper) { "$key: initial=$initial 必须落在 [$lower, $upper]" }
    }
}

/**
 * 离散维度：从 [choices] 中选一个 String。
 *
 * 所有取值序列化都用 String，与 `ResearchContext.params` 同构；
 * 整数/布尔/枚举等都先在调用侧 toString，再在 ObjectiveFunction 内 parse 回去。
 */
data class DiscreteDim(
    override val key: String,
    val choices: List<String>,
    val initial: String,
) : Dim {
    init {
        require(choices.isNotEmpty()) { "$key: choices 不能为空" }
        require(choices.distinct().size == choices.size) { "$key: choices 不能重复：$choices" }
        require(initial in choices) { "$key: initial=$initial 必须在 choices=$choices 内" }
    }
}

data class ContinuousSpace(override val dims: List<ContinuousDim>) : SearchSpace {
    override fun validate() {
        require(dims.isNotEmpty()) { "ContinuousSpace.dims 不能为空" }
        val keys = dims.map { it.key }
        require(keys.distinct().size == keys.size) { "ContinuousSpace.dims.key 不能重复：$keys" }
    }

    init {
        validate()
    }

    /** 在 `[lower, upper]` 闭区间把向量限制回合法范围。 */
    fun clamp(vector: DoubleArray): DoubleArray {
        require(vector.size == dims.size) { "向量维度 ${vector.size} 与空间维度 ${dims.size} 不一致" }
        return DoubleArray(vector.size) { i ->
            val d = dims[i]
            vector[i].coerceIn(d.lower, d.upper)
        }
    }

    /** 把向量编码为 `Map<String, String>`（精度 12 位，足够研究复现）。 */
    fun encode(vector: DoubleArray): Map<String, String> {
        require(vector.size == dims.size)
        return dims.mapIndexed { i, d -> d.key to formatNumber(vector[i]) }.toMap()
    }

    /** 取初值向量。 */
    val initialVector: DoubleArray get() = DoubleArray(dims.size) { dims[it].initial }

    /** 各维度宽度（upper - lower）。 */
    val widths: DoubleArray get() = DoubleArray(dims.size) { dims[it].upper - dims[it].lower }

    companion object {
        private fun formatNumber(value: Double): String {
            // 保留至多 12 位有效数字；避免科学计数法时表达不直观
            val formatted = "%.12g".format(value).trim()
            return formatted.trimEnd('0').trimEnd('.')
                .ifEmpty { "0" }
        }
    }
}

data class DiscreteSpace(override val dims: List<DiscreteDim>) : SearchSpace {
    override fun validate() {
        require(dims.isNotEmpty()) { "DiscreteSpace.dims 不能为空" }
        val keys = dims.map { it.key }
        require(keys.distinct().size == keys.size) { "DiscreteSpace.dims.key 不能重复：$keys" }
    }

    init {
        validate()
    }

    /** 用索引向量编码当前位置（每维一个 choices 下标）。 */
    fun encodeIndices(indices: IntArray): Map<String, String> {
        require(indices.size == dims.size)
        return dims.mapIndexed { i, d -> d.key to d.choices[indices[i]] }.toMap()
    }

    /** 取初值索引。 */
    val initialIndices: IntArray
        get() = IntArray(dims.size) { i -> dims[i].choices.indexOf(dims[i].initial) }
}
