package org.shiroumi.strategy.research.eval

import org.shiroumi.strategy.research.output.ResonanceIdentity
import org.shiroumi.strategy.research.output.ResonanceMetric
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 独立裁判器 [ResonanceEvaluator] 的边界自检。
 *
 * 重点不是穷举所有数值，而是把"放水路径"逐条堵死：
 * - 缺字段必须 fail（不能因为没填就放过）；
 * - Reject 优先级（filtfilt 不一致 / lead 滞后）高于其他降档；
 * - 因子对额外两项只在 pair_* 下生效；
 * - A 全过、C 同步、B 兜底 三档边界值不混。
 */
class ResonanceEvaluatorTest {

    private fun identity(
        factor_type: String = "single",
        horizon: Int = 3,
        band: String = "F2a",
    ) = ResonanceIdentity(
        factor_name = "FAKE",
        factor_type = factor_type,
        factor_i = "x",
        factor_j = if (factor_type.startsWith("pair")) "y" else null,
        target_y = "Y2",
        horizon = horizon,
        band = band,
        state_id = "trend=up,disp=low,vol=mid",
    )

    /** 一份"全过且无降档触发"的 single 因子样本（所有 12 项达标）。 */
    private fun aCardSingle(): ResonanceMetric = ResonanceMetric(
        identity = identity(),
        stft_window = 64,
        norm_version = "v1",
        state_window = 60,
        mean_coherence = 0.6,
        coherence_coverage = 0.4,
        phase_std = PI / 5,          // < π/4
        lead_relation_stable = true,
        lead_days_lag = 2.0,          // horizon=3 → 1.0..3.0
        oos_ic = 0.06,
        hit_rate = 0.60,
        baseline = 0.50,              // hit_rate > baseline + 0.05
        top_bottom_spread = 0.02,
        top_bottom_spread_consistency = 0.75,
        q_value = 0.05,
        sample_count = 80,
        filtfilt_lfilter_consistent = true,
    )

    @Test
    fun `empty metric — all gates fail and result is Reject (fail-closed)`() {
        // 骨架阶段 FakeStudy 的产物：只有 identity + 配置，所有度量为 null
        val m = ResonanceMetric(
            identity = identity(),
            stft_window = 64,
            norm_version = "v1",
            state_window = 60,
        )
        val v = ResonanceEvaluator.evaluate(m)
        assertFalse(v.qualified, "空 metric 不得 qualified（fail-closed）")
        // 单因子 11 项硬条件全部失败
        assertEquals(11, v.gates.size)
        assertEquals(11, v.failedGates.size)
        // filtfilt_lfilter_consistent 缺字段 → null != true → 不一致判定不成立 → 不属于 Reject 路径
        // lead_days_lag 也是 null → 不触发 Reject 路径
        // 所以这里降到 B（其他瑕疵），不是 Reject
        assertEquals("B", v.conclusionLevel)
    }

    @Test
    fun `A class — all 11 single-factor gates pass`() {
        val v = ResonanceEvaluator.evaluate(aCardSingle())
        assertTrue(v.qualified, "全部通过应 qualified: failedGates=${v.failedGates}")
        assertEquals("A", v.conclusionLevel)
        assertTrue(v.failedGates.isEmpty())
    }

    @Test
    fun `Reject overrides everything when filtfilt_lfilter_consistent is false`() {
        val m = aCardSingle().copy(filtfilt_lfilter_consistent = false)
        val v = ResonanceEvaluator.evaluate(m)
        assertFalse(v.qualified)
        assertEquals("Reject", v.conclusionLevel)
    }

    @Test
    fun `Reject when lead_days_lag is below -0_5 (lagging — saw future is impossible)`() {
        val m = aCardSingle().copy(lead_days_lag = -1.0)
        val v = ResonanceEvaluator.evaluate(m)
        assertEquals("Reject", v.conclusionLevel)
    }

    @Test
    fun `C class — synchronous (lead in -0_5_0_5) with other defect prevents A`() {
        // 让 lead=0（同步），但 horizon=3 要求 1.0..3.0 → gate5 fail → 不 qualified
        val m = aCardSingle().copy(lead_days_lag = 0.0)
        val v = ResonanceEvaluator.evaluate(m)
        assertFalse(v.qualified)
        assertEquals("C", v.conclusionLevel)
    }

    @Test
    fun `B class — non-synchronous defect (not A, not C, not Reject)`() {
        // lead 满足区间但 lead_relation_stable=false → 仅 gate4 fail
        val m = aCardSingle().copy(lead_relation_stable = false)
        val v = ResonanceEvaluator.evaluate(m)
        assertFalse(v.qualified)
        assertEquals(listOf(4), v.failedGates)
        assertEquals("B", v.conclusionLevel)
    }

    @Test
    fun `pair factor — must additionally pass delta_ic_vs_base and beta3_stability`() {
        // 把 single 样本平移到 pair_diff，并把额外两项设成不达标
        val m = aCardSingle().copy(
            identity = identity(factor_type = "pair_diff"),
            sample_count = 100,           // pair 门槛 80 — 这里只验额外两项不影响
            delta_ic_vs_base = 0.01,      // < 0.02
            beta3_stability = 0.50,       // < 0.70
        )
        val v = ResonanceEvaluator.evaluate(m)
        assertFalse(v.qualified, "pair 额外项不达标必须挡住")
        assertTrue(12 in v.failedGates, "缺失/不达标的因子对额外项应记为 gate 12: ${v.failedGates}")
    }

    @Test
    fun `pair factor — passes when all single gates and both pair extras pass`() {
        val m = aCardSingle().copy(
            identity = identity(factor_type = "pair_product"),
            sample_count = 100,
            delta_ic_vs_base = 0.03,
            beta3_stability = 0.80,
        )
        val v = ResonanceEvaluator.evaluate(m)
        assertTrue(v.qualified, "pair 全过应 qualified: failedGates=${v.failedGates}")
        assertEquals("A", v.conclusionLevel)
    }

    @Test
    fun `sample_count threshold differs single vs pair`() {
        // single 门槛 60，刚好 60 应通过；59 不通过
        val pass = aCardSingle().copy(sample_count = 60)
        assertTrue(ResonanceEvaluator.evaluate(pass).qualified)
        val fail = aCardSingle().copy(sample_count = 59)
        val v = ResonanceEvaluator.evaluate(fail)
        assertFalse(v.qualified)
        assertTrue(10 in v.failedGates)

        // pair 门槛 80，70 不通过
        val pairLow = aCardSingle().copy(
            identity = identity(factor_type = "pair_diff"),
            sample_count = 70,
            delta_ic_vs_base = 0.03,
            beta3_stability = 0.80,
        )
        val vp = ResonanceEvaluator.evaluate(pairLow)
        assertFalse(vp.qualified)
        assertTrue(10 in vp.failedGates)
    }

    @Test
    fun `top_bottom_spread must be positive AND consistency above threshold`() {
        // consistency 达标但 spread <= 0 → gate8 fail
        val negSpread = aCardSingle().copy(top_bottom_spread = -0.01)
        assertTrue(8 in ResonanceEvaluator.evaluate(negSpread).failedGates)
        // spread 正但 consistency 不够 → gate8 fail
        val lowCons = aCardSingle().copy(top_bottom_spread_consistency = 0.65)
        assertTrue(8 in ResonanceEvaluator.evaluate(lowCons).failedGates)
    }

    @Test
    fun `lead_days_lag must be inside horizon-specific range`() {
        // horizon=5 → 1.0..5.0；给 0.8（区间外但同步区也外）
        val m = aCardSingle().copy(
            identity = identity(horizon = 5),
            lead_days_lag = 0.8,
        )
        val v = ResonanceEvaluator.evaluate(m)
        assertFalse(v.qualified)
        assertTrue(5 in v.failedGates)
    }

    @Test
    fun `EvaluationReport aggregates levels and gate failures`() {
        val a = ResonanceEvaluator.evaluate(aCardSingle())
        val rej = ResonanceEvaluator.evaluate(aCardSingle().copy(filtfilt_lfilter_consistent = false))
        val report = EvaluationReport.of(listOf(a, rej))
        assertEquals(2, report.total)
        assertEquals(1, report.qualifiedCount)
        assertEquals(1, report.levelCounts["A"])
        assertEquals(1, report.levelCounts["Reject"])
        // Reject 的 gate11 应被记入失败计数
        assertTrue((report.gateFailureCount[11] ?: 0) >= 1)
    }
}
