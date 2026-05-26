package org.shiroumi.server.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import model.dataprovider.ExecutionPhase
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 执行阶段判定服务。
 *
 * 它的职责不是直接广播市场消息，而是把交易时钟翻译成 DataProvider 能理解的阶段语义：
 * - OFF_MARKET
 * - TRADING_ACTIVE
 * - TRADING_BREAK
 *
 * 后续演进方向：
 * - 当前只按时钟和工作日判断
 * - 未来应接入真实交易日历，处理节假日和特殊交易日
 */
class ExecutionPhaseService(
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
    private val tickIntervalMs: Long = 60_000L,
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now(zoneId) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val started = AtomicBoolean(false)
    private val amOpen = LocalTime.of(9, 30)
    private val amClose = LocalTime.of(11, 30)
    private val pmOpen = LocalTime.of(13, 0)
    private val pmClose = LocalTime.of(15, 0)

    private val _phaseFlow = MutableStateFlow(computePhase())
    val phaseFlow: StateFlow<ExecutionPhase> = _phaseFlow.asStateFlow()
    private val _tradingTickFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val tradingTickFlow: SharedFlow<Unit> = _tradingTickFlow.asSharedFlow()

    /**
     * 获取当前阶段的同步便捷方法。
     */
    fun currentPhase(): ExecutionPhase = _phaseFlow.value

    /**
     * 启动阶段定时刷新。
     * 目前按固定时间间隔重新计算一次阶段。
     *
     * 这里必须保证幂等：
     * - 重复调用不能重复启动无限循环
     * - 否则同一个进程内会同时存在多个 phase ticker
     * - 后续所有 Provider 都会被重复广播刷新
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                val phase = computePhase(nowProvider())
                _phaseFlow.value = phase
                if (phase == ExecutionPhase.TRADING_ACTIVE) {
                    _tradingTickFlow.emit(Unit)
                }
                delay(tickIntervalMs)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    /**
     * 根据给定时间计算当前执行阶段。
     *
     * 规则：
     * - 周末一律视为闭市
     * - 上午盘 / 下午盘为 `TRADING_ACTIVE`
     * - 午休时段为 `TRADING_BREAK`
     * - 其余时间为 `OFF_MARKET`
     */
    fun computePhase(now: ZonedDateTime = nowProvider()): ExecutionPhase {
        val dayOfWeek = now.dayOfWeek
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return ExecutionPhase.OFF_MARKET
        }

        val time = now.toLocalTime()
        return when {
            time >= amOpen && time < amClose -> ExecutionPhase.TRADING_ACTIVE
            time >= pmOpen && time < pmClose -> ExecutionPhase.TRADING_ACTIVE
            time >= amClose && time < pmOpen -> ExecutionPhase.TRADING_BREAK
            else -> ExecutionPhase.OFF_MARKET
        }
    }
}
