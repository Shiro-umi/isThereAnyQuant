package org.shiroumi.agent.acp

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicReference

private val guardLogger = KotlinLogging.logger {}

/**
 * 沙箱灰度回退闸门。把「沙箱要不要真开」从静态 `-Dquant.agent.sandbox.disable` 旗标
 * 升级为【进程级、可观测、可自动回退】的状态机,但绝不改 [SandboxProfile.render]/[execPrefix]。
 *
 * 健康态([State.ARMED]):[effectiveTier] 恒等返回 requested → [SandboxProfile.isEnabled] 行为与
 * 升级前逐字等价(OFF 态字节等价)。仅在故障路径(launch/newSession 超时率越阈,或窗口内成功长期停滞)
 * 才 CAS 置 [State.TRIPPED],之后 [effectiveTier] 返回 [SandboxTier.OFF] —— 等价运维手搓 disable,
 * 但全自动、不重启。每档([SandboxTier.USER]/[SandboxTier.BACKFILL])独立一套状态与计数。
 *
 * 设计约束:
 *  - 不持有任何 runtime/AcpClient 引用(onTrip 由调用方注入回调),保持 agent 模块零 ktor 反向依赖。
 *  - 不引入新外部依赖:状态用 [AtomicReference] 打包,无锁滑窗。
 *  - 配置从 `System.getProperty("quant.agent.sandbox.rollout.*")` 读,缺省安全默认。
 *
 * 「成功停滞」检测兜底「withTimeout 没抛 CancellationException 则 timeout 计数不增」的场景:
 * 即便故障不以超时异常显形(协程取消被吞),只要窗口内迟迟没有 [recordSuccess],也判不健康 TRIP。
 */
object SandboxRolloutGuard {

    enum class State { ARMED, TRIPPED }

    private const val PROP_PREFIX = "quant.agent.sandbox.rollout."

    /** 滑窗时长(ms):窗口滚动后计数清零重新统计。默认 10 分钟。 */
    private val windowMs: Long = longProp("windowMs", 600_000L)
    /** 触发 TRIP 的最小样本数:样本不足不轻易 TRIP,避免冷启动抖动误伤。默认 4。 */
    private val minSamples: Int = intProp("minSamples", 4)
    /** 超时率阈值:窗口内 timeout/(success+timeout) 越此值即 TRIP。默认 0.3。 */
    private val timeoutRateThreshold: Double = doubleProp("timeoutRateThreshold", 0.3)
    /** 成功停滞判据:窗口内有 >=此数量 timeout 且 success==0 即 TRIP(协程取消被吞兜底)。默认 3。 */
    private val stallTimeoutFloor: Int = intProp("stallTimeoutFloor", 3)
    /** rearm 前置:窗口内需连续 success 且零 timeout 达此数才允许重新武装。默认 3。 */
    private val healthyStreakToRearm: Int = intProp("healthyStreakToRearm", 3)
    /** 配置层强制压低的地板档(可把某档直接钉死 OFF):`quant.agent.sandbox.rollout.floorOff.USER=true`。 */
    private fun floorOff(tier: SandboxTier): Boolean =
        System.getProperty("${PROP_PREFIX}floorOff.${tier.name}")?.equals("true", true) == true

    /** 每档一份独立窗口快照。windowStart 滚动即清零。 */
    private data class Window(
        val state: State,
        val windowStart: Long,
        val success: Long,
        val timeout: Long,
        val healthyStreak: Int,
    )

    private val windows: Map<SandboxTier, AtomicReference<Window>> = mapOf(
        SandboxTier.USER to AtomicReference(freshWindow(0L)),
        SandboxTier.BACKFILL to AtomicReference(freshWindow(0L)),
    )

    private fun freshWindow(now: Long) = Window(State.ARMED, now, 0L, 0L, 0)

    private fun ref(tier: SandboxTier): AtomicReference<Window>? = windows[tier]

    /**
     * 返回该档实际生效的沙箱档位。健康态恒等返回 requested;TRIPPED 或配置地板压 OFF 时返回
     * [SandboxTier.OFF]。[SandboxTier.OFF] 本身恒等返回(无 window)。
     *
     * 注意:窗口滚动检查在此惰性触发(读路径),保证 TRIPPED 在新窗口能被 [recordSuccess] 经 rearm 流程恢复。
     */
    fun effectiveTier(requested: SandboxTier): SandboxTier {
        if (requested == SandboxTier.OFF) return SandboxTier.OFF
        if (floorOff(requested)) return SandboxTier.OFF
        val r = ref(requested) ?: return requested
        return if (r.get().state == State.TRIPPED) SandboxTier.OFF else requested
    }

    /** 记录一次 launch+newSession 成功。健康信号,推进 healthyStreak,满足前置则自动 rearm。 */
    fun recordSuccess(tier: SandboxTier) {
        val r = ref(tier) ?: return
        val now = nowMs()
        r.updateAndGet { w ->
            val base = if (now - w.windowStart > windowMs) freshWindow(now).copy(state = w.state) else w
            val streak = base.healthyStreak + 1
            val rearmed = base.state == State.TRIPPED && streak >= healthyStreakToRearm && base.timeout == 0L
            if (rearmed) {
                guardLogger.warn { "[SandboxGuard] tier=$tier re-ARMED after healthyStreak=$streak" }
                base.copy(state = State.ARMED, success = base.success + 1, healthyStreak = streak)
            } else {
                base.copy(success = base.success + 1, healthyStreak = streak)
            }
        }
    }

    /**
     * 记录一次 launch/newSession 超时。不健康信号。越阈或成功停滞即 CAS 置 TRIPPED 并回调 onTrip。
     * onTrip 只在【本次调用真正触发 ARMED→TRIPPED 跃迁】时执行一次。
     */
    fun recordTimeout(tier: SandboxTier, onTrip: () -> Unit = {}) {
        val r = ref(tier) ?: return
        val now = nowMs()
        var tripped = false
        r.updateAndGet { w ->
            val base = if (now - w.windowStart > windowMs) freshWindow(now).copy(state = w.state) else w
            val next = base.copy(timeout = base.timeout + 1, healthyStreak = 0)
            val total = next.success + next.timeout
            val rateTrip = total >= minSamples && next.timeout.toDouble() / total >= timeoutRateThreshold
            val stallTrip = next.success == 0L && next.timeout >= stallTimeoutFloor
            if (next.state == State.ARMED && (rateTrip || stallTrip)) {
                tripped = true
                next.copy(state = State.TRIPPED)
            } else next
        }
        if (tripped) {
            guardLogger.error { "[SandboxGuard] tier=$tier TRIPPED → effectiveTier 降 OFF(沙箱自动回退,链路裸跑)。请尽快排查 agent 启动链路!" }
            runCatching { onTrip() }.onFailure { guardLogger.warn(it) { "[SandboxGuard] onTrip callback failed" } }
        }
    }

    /** 运维热钩子:手动秒退某档到 OFF(等价 disable 但不重启)。 */
    fun forceTrip(tier: SandboxTier) {
        val r = ref(tier) ?: return
        r.updateAndGet { it.copy(state = State.TRIPPED, healthyStreak = 0) }
        guardLogger.warn { "[SandboxGuard] tier=$tier force-TRIPPED via ops" }
    }

    /** 运维热钩子:手动重新武装某档(立即,不等 healthyStreak)。 */
    fun rearm(tier: SandboxTier) {
        val r = ref(tier) ?: return
        r.updateAndGet { freshWindow(nowMs()) }
        guardLogger.warn { "[SandboxGuard] tier=$tier re-ARMED via ops" }
    }

    /** 观测快照(ops GET 用)。 */
    fun snapshot(): Map<String, Map<String, Any>> = windows.mapKeys { it.key.name }.mapValues { (_, ref) ->
        val w = ref.get()
        mapOf(
            "state" to w.state.name,
            "success" to w.success,
            "timeout" to w.timeout,
            "healthyStreak" to w.healthyStreak,
            "windowAgeMs" to (nowMs() - w.windowStart),
        )
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun longProp(name: String, def: Long): Long =
        System.getProperty("$PROP_PREFIX$name")?.toLongOrNull() ?: def
    private fun intProp(name: String, def: Int): Int =
        System.getProperty("$PROP_PREFIX$name")?.toIntOrNull() ?: def
    private fun doubleProp(name: String, def: Double): Double =
        System.getProperty("$PROP_PREFIX$name")?.toDoubleOrNull() ?: def
}
