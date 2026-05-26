package org.shiroumi.server.runtime.market

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.candle.MarketStatus
import model.dataprovider.ExecutionPhase
import model.ws.MarketStatusPayload
import model.ws.WsAction
import model.ws.WsEvent
import model.ws.WsTopic
import org.shiroumi.server.runtime.ExecutionPhaseService
import org.shiroumi.server.websocket.AppWebSocketConnectionManager
import utils.logger
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 市场状态投影服务。
 *
 * 它负责把新的 `ExecutionPhaseService` 阶段语义投影成旧前端仍在消费的
 * `MARKET_STATUS` topic，而不是继续依赖旧 `MarketStatusService`。
 *
 * 设计原则：
 * 1. 只做投影与广播，不参与任何业务状态恢复
 * 2. topic / payload 保持不变，前端无需改协议
 * 3. 状态真相来自 `ExecutionPhaseService`
 */
class MarketStatusProjectionService(
    private val executionPhaseService: ExecutionPhaseService,
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val logger by logger("MarketStatusProjectionService")
    private val started = AtomicBoolean(false)
    private val json = Json { encodeDefaults = true }

    /**
     * 幂等启动 phase -> market status 的投影广播。
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var lastStatus: MarketStatus? = null
            executionPhaseService.phaseFlow.collectLatest { phase ->
                val payload = phase.toPayload()
                if (payload.status == lastStatus) {
                    return@collectLatest
                }
                lastStatus = payload.status
                logger.info("Broadcast market status from phase=$phase -> status=${payload.status}")
                AppWebSocketConnectionManager.broadcast(
                    WsEvent(
                        topic = WsTopic.MARKET_STATUS,
                        action = WsAction.UPDATE,
                        payload = json.encodeToString(payload)
                    )
                )
            }
        }
    }

    /**
     * 当前市场状态快照。
     *
     * 新 WebSocket 连接在阶段切换广播之间建立时，需要立即拿到一次当前状态，
     * 否则前端会停留在默认 CLOSED。
     */
    fun currentPayload(): MarketStatusPayload =
        executionPhaseService.computePhase().toPayload()

    /**
     * 把执行阶段翻译成前端现有 `MarketStatusPayload`。
     */
    private fun ExecutionPhase.toPayload(): MarketStatusPayload =
        MarketStatusPayload(
            status = when (this) {
                ExecutionPhase.TRADING_ACTIVE -> MarketStatus.OPEN
                ExecutionPhase.TRADING_BREAK,
                ExecutionPhase.OFF_MARKET -> MarketStatus.CLOSED
            },
            nextStatusTime = nextStatusChangeTime()
        )

    /**
     * 计算下次状态切换时间。
     *
     * 这里保留旧前端依赖的字符串语义（"HH:mm"），
     * 但底层逻辑不再依赖旧 `MarketStatusService`。
     */
    private fun nextStatusChangeTime(now: ZonedDateTime = ZonedDateTime.now(zoneId)): String? {
        val dayOfWeek = now.dayOfWeek
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return null
        }
        val time = now.toLocalTime()
        val amOpen = LocalTime.of(9, 30)
        val amClose = LocalTime.of(11, 30)
        val pmOpen = LocalTime.of(13, 0)
        val pmClose = LocalTime.of(15, 0)
        return when {
            time < amOpen -> "09:30"
            time < amClose -> "11:30"
            time < pmOpen -> "13:00"
            time < pmClose -> "15:00"
            else -> null
        }
    }
}
