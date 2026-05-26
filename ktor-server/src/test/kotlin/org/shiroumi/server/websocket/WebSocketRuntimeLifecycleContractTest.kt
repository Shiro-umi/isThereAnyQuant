package org.shiroumi.server.websocket

import kotlinx.coroutines.test.runTest
import model.ws.WsAction
import model.ws.WsEvent
import model.ws.WsTopic
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.shiroumi.server.subscription.intraday.IntradaySnapshotSubscriptionService
import org.shiroumi.server.subscription.strategy.StrategyPositionSubscriptionService
import org.shiroumi.server.subscription.strategy.StrategyPositionTrackingSubscriptionService
import org.shiroumi.server.websocket.service.AgentWebSocketService
import java.io.File
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * WebSocket 运行时生命周期契约测试。
 *
 * 这组测试锁定长期运行性能修复的结构性约束：
 * - session 出站必须经过 typed mailbox，快照类消息按业务 key 合并，事件类消息 bounded FIFO
 * - AGENT_STREAM 中间帧是增量 Delta，必须走 FIFO，不能 latest-only 覆盖
 * - command job 必须先登记再启动，完成后移除，避免快速命令留下 completed Job
 * - Agent state collector 必须按 session 保存并在会话结束时取消
 * - strategy-service snapshot 消费必须 conflated，避免慢前端反压 socket reader
 */
class WebSocketRuntimeLifecycleContractTest {

    @Test
    fun `websocket session data owns typed outbound mailbox and sender job`() {
        val properties = AppWebSocketConnectionManager.SessionData::class
            .declaredMemberProperties
            .map { it.name }
            .toSet()

        assertTrue("outboundMailbox" in properties, "SessionData 必须持有 per-session typed outboundMailbox")
        assertTrue("outboundJob" in properties, "SessionData 必须持有 per-session outbound sender Job")
        assertTrue("commandChannel" in properties, "SessionData 必须持有 per-session command channel")
        assertTrue("commandProcessorJob" in properties, "SessionData 必须持有 per-session command processor Job")
        assertTrue("activeCommandJobs" in properties, "SessionData 必须继续记录可取消的 command jobs")
    }

    @Test
    fun `websocket manager keeps slow client and command job lifecycle hooks`() {
        val members = AppWebSocketConnectionManager::class
            .declaredMemberFunctions
            .map { it.name }
            .toSet()

        listOf(
            "startOutboundSender",
            "startCommandProcessor",
            "enqueueEventToSession",
            "scheduleSlowSessionClose",
            "isSessionActive",
            "debugActiveCommandJobCount",
            "debugOutboundMailboxSnapshot",
        ).forEach { name ->
            assertTrue(name in members, "AppWebSocketConnectionManager.$name 是出站反压/Job 生命周期契约的一部分")
        }
    }

    @Test
    fun `outbound mailbox conflates snapshots by topic and target while preserving fifo events`() = runTest {
        val mailbox = AppWebSocketConnectionManager.OutboundMailbox(
            fifoCapacity = 2,
            conflatedKeyCapacity = 4
        )

        assertEquals(
            AppWebSocketConnectionManager.OutboundOfferResult.ACCEPTED,
            mailbox.offer(snapshot(topic = WsTopic.CANDLE_DATA, targetId = "000001.SZ:DAY", body = "old"))
        )
        assertEquals(
            AppWebSocketConnectionManager.OutboundOfferResult.ACCEPTED,
            mailbox.offer(snapshot(topic = WsTopic.CANDLE_DATA, targetId = "000001.SZ:DAY", body = "new"))
        )
        assertEquals(
            AppWebSocketConnectionManager.OutboundOfferResult.ACCEPTED,
            mailbox.offer(fifo(body = "agent-1"))
        )

        assertEquals(
            AppWebSocketConnectionManager.OutboundMailboxSnapshot(
                fifoSize = 1,
                conflatedSize = 1,
                closed = false
            ),
            mailbox.snapshot()
        )
        assertEquals("agent-1", mailbox.receive())
        assertEquals("new", mailbox.receive())
    }

    @Test
    fun `outbound mailbox preserves agent stream deltas in fifo order`() = runTest {
        val mailbox = AppWebSocketConnectionManager.OutboundMailbox(
            fifoCapacity = 3,
            conflatedKeyCapacity = 4
        )

        assertEquals(
            AppWebSocketConnectionManager.OutboundOfferResult.ACCEPTED,
            mailbox.offer(agentUpdate(body = "partial-output-1"))
        )
        assertEquals(
            AppWebSocketConnectionManager.OutboundOfferResult.ACCEPTED,
            mailbox.offer(agentUpdate(body = "partial-output-2"))
        )
        assertEquals(
            AppWebSocketConnectionManager.OutboundOfferResult.ACCEPTED,
            mailbox.offer(agentComplete(body = "complete-output"))
        )

        assertEquals(
            AppWebSocketConnectionManager.OutboundMailboxSnapshot(
                fifoSize = 3,
                conflatedSize = 0,
                closed = false
            ),
            mailbox.snapshot()
        )
        assertEquals("partial-output-1", mailbox.receive())
        assertEquals("partial-output-2", mailbox.receive())
        assertEquals("complete-output", mailbox.receive())
    }

    @Test
    fun `outbound mailbox applies fifo overflow only to non conflated event backlog`() {
        val mailbox = AppWebSocketConnectionManager.OutboundMailbox(
            fifoCapacity = 1,
            conflatedKeyCapacity = 4
        )

        assertEquals(AppWebSocketConnectionManager.OutboundOfferResult.ACCEPTED, mailbox.offer(fifo("event-1")))
        assertEquals(AppWebSocketConnectionManager.OutboundOfferResult.OVERFLOW, mailbox.offer(fifo("event-2")))
        assertEquals(
            AppWebSocketConnectionManager.OutboundOfferResult.ACCEPTED,
            mailbox.offer(snapshot(topic = WsTopic.STOCK_LIST_UPDATE, targetId = null, body = "quote-1"))
        )
        assertEquals(
            AppWebSocketConnectionManager.OutboundOfferResult.ACCEPTED,
            mailbox.offer(snapshot(topic = WsTopic.STOCK_LIST_UPDATE, targetId = null, body = "quote-2"))
        )
        assertEquals(
            AppWebSocketConnectionManager.OutboundMailboxSnapshot(
                fifoSize = 1,
                conflatedSize = 1,
                closed = false
            ),
            mailbox.snapshot()
        )
    }

    @Test
    fun `handle command uses bounded per session command processor`() {
        val source = sourceFile(
            "ktor-server/src/main/kotlin/org/shiroumi/server/websocket/AppWebSocketConnectionManager.kt"
        ).readText()

        assertTrue(
            source.contains("COMMAND_CHANNEL_CAPACITY"),
            "命令入口必须有 bounded queue，避免长期运行时命令洪峰无限堆积"
        )
        assertTrue(
            source.contains("sessionData.commandChannel.trySend(command)"),
            "handleCommand 必须只入队，不为每条命令创建独立协程"
        )
        assertTrue(
            source.contains("for (command in sessionData.commandChannel)"),
            "每个 session 必须由单一 processor 串行处理命令"
        )
        assertFalse(
            source.contains("scope.launch(start = CoroutineStart.LAZY)"),
            "handleCommand 不能为每条命令启动独立 Job"
        )
    }

    @Test
    fun `agent service tracks and cancels state collector jobs`() {
        val properties = AgentWebSocketService::class
            .declaredMemberProperties
            .onEach { it.isAccessible = true }
            .map { it.name }
            .toSet()
        val members = AgentWebSocketService::class
            .declaredMemberFunctions
            .map { it.name }
            .toSet()

        assertTrue("sessionStateJobs" in properties, "AgentWebSocketService 必须保存每个 session 的 state collector Job")
        assertTrue("cleanupSessionBookkeeping" in members, "Agent session 关闭/失败/失效必须统一清理 state collector")
        assertTrue("closeUnboundRuntimeSession" in members, "Agent 创建完成前连接已断开时必须关闭未绑定 ACP session")
        assertTrue("scheduleIdleCleanup" in members, "Agent 创建成功但前端尚未订阅时必须有空闲清理兜底")
        assertTrue("shutdown" in members, "ApplicationStopping 必须能关闭 Agent collector 与 runtime")

        val source = sourceFile(
            "ktor-server/src/main/kotlin/org/shiroumi/server/websocket/service/AgentWebSocketService.kt"
        ).readText()
        assertTrue(
            source.contains("AppWebSocketConnectionManager.isSessionActive(webSocketSession)"),
            "Agent 创建协程必须校验来源 WebSocket 是否仍然活跃，避免 orphan runtime/session"
        )
        assertTrue(
            source.contains("scheduleIdleCleanup(sessionId, \"session created without subscriber\")"),
            "Agent session 创建成功后必须先挂无订阅者清理计时，防止订阅前断线留下 orphan"
        )
    }

    @Test
    fun `strategy service subscriptions use conflated remote snapshot buffers`() {
        listOf(
            sourceFile("ktor-server/src/main/kotlin/org/shiroumi/server/subscription/intraday/IntradaySnapshotSubscriptionService.kt"),
            sourceFile("ktor-server/src/main/kotlin/org/shiroumi/server/subscription/strategy/StrategyPositionSubscriptionService.kt"),
            sourceFile("ktor-server/src/main/kotlin/org/shiroumi/server/subscription/strategy/StrategyPositionTrackingSubscriptionService.kt"),
        ).forEach { file ->
            val source = file.readText()
            assertTrue(
                source.contains("flow.buffer(Channel.CONFLATED).collectLatest"),
                "${file.name} 必须 conflated 消费 strategy-service snapshot，避免慢前端反压 socket reader"
            )
        }

        listOf(
            IntradaySnapshotSubscriptionService::class,
            StrategyPositionSubscriptionService::class,
            StrategyPositionTrackingSubscriptionService::class,
        ).forEach { klass ->
            assertNotNull(klass.declaredMemberFunctions.firstOrNull { it.name == "subscribe" })
        }
    }

    @Test
    fun `stock list context push is latest only per session`() {
        val source = sourceFile(
            "ktor-server/src/main/kotlin/org/shiroumi/server/runtime/stock/StockListContextRuntimeService.kt"
        ).readText()

        assertTrue(source.contains("currentPushJobs"), "股票列表上下文当前快照推送必须按 session 保存 Job")
        assertTrue(source.contains("currentPushJobs.remove(session)?.cancel()"), "新上下文必须取消旧的当前快照推送")
        assertTrue(source.contains("currentPushJobs.remove(session, job)"), "旧 Job 完成时不能误删新 Job")
        assertTrue(source.contains("sessionVisibleStocks[session] != tsCodes"), "发送前必须丢弃过期上下文")
        assertTrue(source.contains("refreshContextQuotes(normalizedCodes)"), "新上下文必须触发当前关注股票的优先实时报价刷新")
        assertTrue(source.contains("priorityRefreshCooldownMs"), "优先报价刷新必须有短冷却，避免滚动上下文刷爆 rt_k")
    }

    @Test
    fun `frontend websocket reader is not backpressured by global event flow`() {
        val source = sourceFile(
            "compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/service/GlobalWebSocketClient.kt"
        ).readText()

        assertTrue(source.contains("BufferOverflow.DROP_OLDEST"), "前端全局事件流满载时必须丢旧事件")
        assertTrue(source.contains("_eventsFlow.tryEmit(event)"), "WebSocket reader 不能 suspend 在 eventsFlow.emit")
        assertFalse(source.contains("_eventsFlow.emit(event)"), "WebSocket reader 不能被全局事件流反压")
    }

    private fun sourceFile(path: String): File {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull { it.exists() }
            ?.let { return it }
        error("Cannot locate source file: $path from user.dir=${System.getProperty("user.dir")}")
    }

    private fun snapshot(topic: WsTopic, targetId: String?, body: String): AppWebSocketConnectionManager.OutboundEnvelope =
        AppWebSocketConnectionManager.OutboundEnvelope.from(
            WsEvent(topic = topic, action = WsAction.UPDATE, targetId = targetId),
            body
        )

    private fun fifo(body: String): AppWebSocketConnectionManager.OutboundEnvelope =
        AppWebSocketConnectionManager.OutboundEnvelope.from(
            WsEvent(topic = WsTopic.SYSTEM, action = WsAction.UPDATE, targetId = "system-event"),
            body
        )

    private fun agentUpdate(body: String): AppWebSocketConnectionManager.OutboundEnvelope =
        AppWebSocketConnectionManager.OutboundEnvelope.from(
            WsEvent(topic = WsTopic.AGENT_STREAM, action = WsAction.UPDATE, targetId = "agent-session"),
            body
        )

    private fun agentComplete(body: String): AppWebSocketConnectionManager.OutboundEnvelope =
        AppWebSocketConnectionManager.OutboundEnvelope.from(
            WsEvent(topic = WsTopic.AGENT_STREAM, action = WsAction.COMPLETE, targetId = "agent-session"),
            body
        )
}
