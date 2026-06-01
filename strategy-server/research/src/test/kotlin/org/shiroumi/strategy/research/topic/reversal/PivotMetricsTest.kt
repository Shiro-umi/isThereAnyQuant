package org.shiroumi.strategy.research.topic.reversal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PivotMetrics] 的数值正确性测试 —— 诊断可信度的基石。
 *
 * AUC / 混淆矩阵 / 阈值选择 / ECDF 都是反转研究「判别力」读数的底层算子，
 * 必须先证明它们在已知答案上算对，体检 B 的结论才可信。
 */
class PivotMetricsTest {

    @Test
    fun `auc of perfectly separating scores is 1`() {
        // 正类分数全高于负类 → 完美排序 → AUC = 1
        val scores = doubleArrayOf(0.1, 0.2, 0.3, 0.8, 0.9, 1.0)
        val labels = intArrayOf(0, 0, 0, 1, 1, 1)
        assertEquals(1.0, PivotMetrics.rocAuc(scores, labels), 1e-9)
    }

    @Test
    fun `auc of inverted scores is 0`() {
        // 正类分数全低于负类 → 完美反向 → AUC = 0（取反后即 1，对应「符号反了」的强因子）
        val scores = doubleArrayOf(0.9, 0.8, 0.7, 0.1, 0.2, 0.0)
        val labels = intArrayOf(0, 0, 0, 1, 1, 1)
        assertEquals(0.0, PivotMetrics.rocAuc(scores, labels), 1e-9)
    }

    @Test
    fun `auc of ties is half`() {
        // 全部并列 → 无排序信息 → AUC = 0.5
        val scores = doubleArrayOf(0.5, 0.5, 0.5, 0.5)
        val labels = intArrayOf(0, 1, 0, 1)
        assertEquals(0.5, PivotMetrics.rocAuc(scores, labels), 1e-9)
    }

    @Test
    fun `auc symmetry - inverting scores gives 1 minus auc`() {
        val scores = doubleArrayOf(0.2, 0.9, 0.4, 0.7, 0.1, 0.85)
        val labels = intArrayOf(0, 1, 0, 1, 0, 1)
        val auc = PivotMetrics.rocAuc(scores, labels)
        val inv = PivotMetrics.rocAuc(DoubleArray(scores.size) { -scores[it] }, labels)
        assertEquals(1.0, auc + inv, 1e-9)
    }

    @Test
    fun `auc returns NaN for single class`() {
        assertTrue(PivotMetrics.rocAuc(doubleArrayOf(0.1, 0.2, 0.3), intArrayOf(0, 0, 0)).isNaN())
        assertTrue(PivotMetrics.rocAuc(doubleArrayOf(0.1, 0.2, 0.3), intArrayOf(1, 1, 1)).isNaN())
    }

    @Test
    fun `confusion matrix counts at threshold`() {
        val scores = doubleArrayOf(0.9, 0.6, 0.4, 0.1)
        val labels = intArrayOf(1, 0, 1, 0)
        val c = PivotMetrics.confusionAt(scores, labels, tau = 0.5)
        // 阈值 0.5：pred=[T,T,F,F]，label=[1,0,1,0]
        assertEquals(1, c.tp)   // 0.9/label1
        assertEquals(1, c.fp)   // 0.6/label0
        assertEquals(1, c.fn)   // 0.4/label1
        assertEquals(1, c.tn)   // 0.1/label0
        assertEquals(0.5, c.precision, 1e-9)
        assertEquals(0.5, c.recall, 1e-9)
        assertEquals(0.5, c.f1, 1e-9)
        assertEquals(0.5, c.balancedHit, 1e-9)   // (recall 0.5 + specificity 0.5)/2
    }

    @Test
    fun `balanced hit penalizes blanket alerting`() {
        // 「天天喊狼来了」：阈值极低，全部报警 → recall=1 但 specificity=0 → balancedHit=0.5（不能蒙混）
        val scores = doubleArrayOf(0.6, 0.6, 0.6, 0.6, 0.6)
        val labels = intArrayOf(1, 0, 0, 0, 0)
        val c = PivotMetrics.confusionAt(scores, labels, tau = 0.0)
        assertEquals(1.0, c.recall, 1e-9)
        assertEquals(0.5, c.balancedHit, 1e-9)   // 弃权=漏报为 0，但乱报把 specificity 砸到 0
    }

    @Test
    fun `best threshold maximizes f1`() {
        val scores = doubleArrayOf(0.1, 0.2, 0.8, 0.9)
        val labels = intArrayOf(0, 0, 1, 1)
        val (_, conf) = PivotMetrics.bestThreshold(scores, labels) { it.f1 }
        assertEquals(1.0, conf.f1, 1e-9)   // 存在能完美分开的阈值
    }

    @Test
    fun `ecdf is monotone non-decreasing and in unit interval`() {
        val train = doubleArrayOf(-2.0, -1.0, 0.0, 1.0, 2.0)
        val ecdf = PivotReversalScorer.fitEcdf(train)
        val probes = doubleArrayOf(-5.0, -1.5, 0.0, 0.5, 3.0)
        var prev = -1.0
        for (x in probes) {
            val p = ecdf(x)
            assertTrue(p in 0.0..1.0, "ECDF 必须落在 [0,1]：$p")
            assertTrue(p >= prev - 1e-12, "ECDF 必须单调不减")
            prev = p
        }
        assertEquals(0.0, ecdf(-10.0), 1e-9)   // 小于全部训练值
        assertEquals(1.0, ecdf(10.0), 1e-9)    // 大于全部训练值
    }

    @Test
    fun `spearman ic sign tracks ranking direction`() {
        // 分数与标签同向 → 正 IC
        val up = PivotMetrics.spearmanIc(doubleArrayOf(0.1, 0.2, 0.8, 0.9), intArrayOf(0, 0, 1, 1))
        assertTrue(up > 0.0)
        // 反向 → 负 IC
        val down = PivotMetrics.spearmanIc(doubleArrayOf(0.9, 0.8, 0.2, 0.1), intArrayOf(0, 0, 1, 1))
        assertTrue(down < 0.0)
    }
}
