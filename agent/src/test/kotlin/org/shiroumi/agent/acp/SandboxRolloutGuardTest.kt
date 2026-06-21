package org.shiroumi.agent.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.BeforeTest

/**
 * SandboxRolloutGuard 状态机单测(纯逻辑,无 LLM/无沙箱)。验收场景 F/K:
 *  - 健康态 effectiveTier 恒等返回(OFF 态字节等价的前提)
 *  - 超时率越阈 / 成功停滞 → TRIP → effectiveTier 降 OFF
 *  - forceTrip/rearm 运维钩子
 *  - floorOff 配置地板
 *
 * 每个用例先 rearm 两档,清掉跨用例的状态残留(guard 是 object 单例)。
 */
class SandboxRolloutGuardTest {

    @BeforeTest
    fun reset() {
        // 清掉可能的 floorOff 系统属性,并把两档 rearm 回 ARMED 新窗口
        System.clearProperty("quant.agent.sandbox.rollout.floorOff.USER")
        System.clearProperty("quant.agent.sandbox.rollout.floorOff.BACKFILL")
        SandboxRolloutGuard.rearm(SandboxTier.USER)
        SandboxRolloutGuard.rearm(SandboxTier.BACKFILL)
    }

    @Test
    fun armedIsIdentity() {
        // 健康态:恒等返回 requested(这是 isEnabled 行为与升级前逐字等价的根)
        assertEquals(SandboxTier.USER, SandboxRolloutGuard.effectiveTier(SandboxTier.USER))
        assertEquals(SandboxTier.BACKFILL, SandboxRolloutGuard.effectiveTier(SandboxTier.BACKFILL))
        // OFF 恒等
        assertEquals(SandboxTier.OFF, SandboxRolloutGuard.effectiveTier(SandboxTier.OFF))
    }

    @Test
    fun stallTimeoutsTripToOff() {
        // 成功停滞:success==0 且 timeout>=stallTimeoutFloor(默认3) → TRIP
        var tripped = false
        repeat(3) { SandboxRolloutGuard.recordTimeout(SandboxTier.USER) { tripped = true } }
        assertTrue(tripped, "3 次纯超时(零成功)应触发 TRIP")
        assertEquals(SandboxTier.OFF, SandboxRolloutGuard.effectiveTier(SandboxTier.USER), "TRIP 后应降 OFF")
        // 另一档不受影响(每档独立)
        assertEquals(SandboxTier.BACKFILL, SandboxRolloutGuard.effectiveTier(SandboxTier.BACKFILL))
    }

    @Test
    fun timeoutRateTripsWhenOverThreshold() {
        // 超时率越阈:minSamples=4,threshold=0.3。先 3 成功 + 2 超时 = 2/5=0.4 越阈
        repeat(3) { SandboxRolloutGuard.recordSuccess(SandboxTier.USER) }
        var tripped = false
        SandboxRolloutGuard.recordTimeout(SandboxTier.USER) { tripped = true } // 1/4=0.25 未越
        assertTrue(!tripped, "1/4=0.25 未越阈,不应 TRIP")
        SandboxRolloutGuard.recordTimeout(SandboxTier.USER) { tripped = true } // 2/5=0.4 越阈
        assertTrue(tripped, "2/5=0.4 越阈应 TRIP")
        assertEquals(SandboxTier.OFF, SandboxRolloutGuard.effectiveTier(SandboxTier.USER))
    }

    @Test
    fun forceTripAndRearm() {
        SandboxRolloutGuard.forceTrip(SandboxTier.USER)
        assertEquals(SandboxTier.OFF, SandboxRolloutGuard.effectiveTier(SandboxTier.USER), "forceTrip 后降 OFF")
        SandboxRolloutGuard.rearm(SandboxTier.USER)
        assertEquals(SandboxTier.USER, SandboxRolloutGuard.effectiveTier(SandboxTier.USER), "rearm 后恢复恒等")
    }

    @Test
    fun floorOffForcesOff() {
        System.setProperty("quant.agent.sandbox.rollout.floorOff.USER", "true")
        assertEquals(SandboxTier.OFF, SandboxRolloutGuard.effectiveTier(SandboxTier.USER), "floorOff 配置应钉死 OFF")
        // BACKFILL 不带 floorOff 仍恒等
        assertEquals(SandboxTier.BACKFILL, SandboxRolloutGuard.effectiveTier(SandboxTier.BACKFILL))
    }

    @Test
    fun autoRearmAfterHealthyStreak() {
        // TRIP 后连续 healthyStreakToRearm(默认3)次成功且零超时 → 自动 re-ARM
        SandboxRolloutGuard.forceTrip(SandboxTier.USER)
        assertEquals(SandboxTier.OFF, SandboxRolloutGuard.effectiveTier(SandboxTier.USER))
        repeat(3) { SandboxRolloutGuard.recordSuccess(SandboxTier.USER) }
        assertEquals(SandboxTier.USER, SandboxRolloutGuard.effectiveTier(SandboxTier.USER), "连续3成功应自动 re-ARM")
    }
}
