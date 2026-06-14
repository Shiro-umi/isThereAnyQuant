package org.shiroumi.server.runtime.stock

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import model.ws.StockListUpdatePayload
import model.ws.WsEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.shiroumi.server.data.facade.LatestCandleQuote
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * R2-OPT-1-push-quotes：反向索引去二次遍历的正确性 + 收益对照测试。
 *
 * 这里不依赖真实 DB / WebSocket：
 * - session 用 JDK 动态代理伪造，仅作 identity map key（只响应 equals/hashCode/toString）
 * - quoteReader 注入纯内存桩，恒定返回一条报价，保证所有变更 code 都能产出推送
 * - eventSender 注入纯内存桩，记录「每个 session 收到的 code 序列」，用于断言去重 / 命中
 *
 * 收益对照用「旧实现」（展开全 session 求并集 + 全表二次遍历）作为 oracle，
 * 在相同随机输入下逐 session 比对推送集合等价；并用计数器量化扫描步数差。
 */
class StockListContextRuntimeServicePushIndexTest {

    private val json = Json { encodeDefaults = true }

    /** 仅用作 identity map key 的会话伪实例。 */
    private fun fakeSession(name: String): DefaultWebSocketServerSession {
        val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeSession($name)"
                else -> throw UnsupportedOperationException(
                    "FakeSession only serves as an identity key, method=${method.name}"
                )
            }
        }
        return Proxy.newProxyInstance(
            DefaultWebSocketServerSession::class.java.classLoader,
            arrayOf(DefaultWebSocketServerSession::class.java),
            handler
        ) as DefaultWebSocketServerSession
    }

    private fun stubQuote(tsCode: String) = LatestCandleQuote(
        tsCode = tsCode,
        latestPrice = 10.0f,
        preClose = 9.9f,
        changePercent = 1.0f,
        changeAmount = 0.1f,
        volume = 1000.0f,
        turnover = 10000.0f
    )

    /** 每个 session 收到推送的 code（按到达顺序）记录器。 */
    private class PushRecorder(private val json: Json) {
        val received = ConcurrentHashMap<DefaultWebSocketServerSession, MutableList<List<String>>>()

        val sender: suspend (DefaultWebSocketServerSession, WsEvent) -> Unit = { session, event ->
            val raw = requireNotNull(event.payload) { "STOCK_LIST_UPDATE payload 不应为空" }
            val payload = json.decodeFromString(StockListUpdatePayload.serializer(), raw)
            received.getOrPut(session) { mutableListOf() }
                .add(payload.stocks.map { it.code })
        }

        fun codesFor(session: DefaultWebSocketServerSession): List<String> =
            received[session]?.flatten() ?: emptyList()

        fun pushCount(session: DefaultWebSocketServerSession): Int =
            received[session]?.size ?: 0

        /** 清掉「页面建立时的初始推送」，只保留后续 onDataSync 的变更推送供断言。 */
        fun reset() = received.clear()
    }

    private fun newService(recorder: PushRecorder): StockListContextRuntimeService =
        StockListContextRuntimeService(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            quoteReader = { code -> stubQuote(code) },
            quoteRefresher = { /* no-op：测试不触发外部刷新 */ },
            eventSender = recorder.sender
        )

    private fun dayKeys(vararg codes: String): List<CandleKey> =
        codes.map { CandleKey(it, CandlePeriod.DAY) }

    // 用例 1：多 session 部分股票变动，只推受影响 session。
    @Test
    fun `onDataSync only pushes sessions watching the changed codes`() = runTest {
        val recorder = PushRecorder(json)
        val service = newService(recorder)

        val s1 = fakeSession("s1")
        val s2 = fakeSession("s2")
        val s3 = fakeSession("s3")
        service.updateVisibleStocks(s1, listOf("000001.SZ", "000002.SZ"))
        service.updateVisibleStocks(s2, listOf("600519.SH"))
        service.updateVisibleStocks(s3, listOf("000300.SZ"))
        recorder.reset()

        // 只有 000001.SZ（s1 关注）与 600519.SH（s2 关注）变更。
        service.onDataSync(dayKeys("000001.SZ", "600519.SH"))

        assertEquals(listOf("000001.SZ"), recorder.codesFor(s1))
        assertEquals(listOf("600519.SH"), recorder.codesFor(s2))
        // s3 不关注任何变更 code，必须零推送。
        assertEquals(0, recorder.pushCount(s3))
    }

    // 用例 2：updateVisibleStocks 后反向索引与前向索引同步（替换两次）。
    @Test
    fun `updateVisibleStocks keeps reverse index in sync across replacements`() {
        val recorder = PushRecorder(json)
        val service = newService(recorder)
        val s1 = fakeSession("s1")

        service.updateVisibleStocks(s1, listOf("A", "B", "C"))
        var idx = service.reverseIndexSnapshot()
        assertEquals(setOf("A", "B", "C"), idx.keys)
        idx.values.forEach { assertEquals(setOf(s1), it) }

        // 替换为部分重叠列表：B 保留、C 移除、D 新增。
        service.updateVisibleStocks(s1, listOf("B", "D"))
        idx = service.reverseIndexSnapshot()
        assertEquals(setOf("B", "D"), idx.keys, "C 应被移除、D 应被新增、B 应保留")
        assertFalse(idx.containsKey("A"))
        assertFalse(idx.containsKey("C"))
        assertEquals(setOf(s1), idx["B"])
        assertEquals(setOf(s1), idx["D"])
    }

    // 用例 3：cleanupSession 后反向索引无残留孤立 key。
    @Test
    fun `cleanupSession leaves no orphan entries in reverse index`() {
        val recorder = PushRecorder(json)
        val service = newService(recorder)
        val s1 = fakeSession("s1")
        val s2 = fakeSession("s2")

        service.updateVisibleStocks(s1, listOf("A", "B", "C"))
        // s2 也看 B，确保清理 s1 不会误删 s2 的反向边。
        service.updateVisibleStocks(s2, listOf("B"))

        service.cleanupSession(s1)
        val idx = service.reverseIndexSnapshot()

        // 只剩 s2 仍在看的 B；A / C 的空集合 key 必须被删除。
        assertEquals(setOf("B"), idx.keys)
        assertEquals(setOf(s2), idx["B"])
    }

    // 用例 4：多 session 同一 code，code 变动各只收一次推送（不重复）。
    @Test
    fun `shared code change pushes each watching session exactly once`() = runTest {
        val recorder = PushRecorder(json)
        val service = newService(recorder)
        val s1 = fakeSession("s1")
        val s2 = fakeSession("s2")
        val s3 = fakeSession("s3")
        // 三个 session 都关注 SHARED；s1 还额外看 X。
        service.updateVisibleStocks(s1, listOf("SHARED", "X"))
        service.updateVisibleStocks(s2, listOf("SHARED"))
        service.updateVisibleStocks(s3, listOf("SHARED"))
        recorder.reset()

        service.onDataSync(dayKeys("SHARED"))

        listOf(s1, s2, s3).forEach { session ->
            assertEquals(1, recorder.pushCount(session), "$session 应只收到一次推送")
            assertEquals(listOf("SHARED"), recorder.codesFor(session))
        }
    }

    // 用例 4b：单 session 命中多个变更 code，聚合成一次推送（去重 + 合并）。
    @Test
    fun `multiple changed codes for one session collapse into a single push`() = runTest {
        val recorder = PushRecorder(json)
        val service = newService(recorder)
        val s1 = fakeSession("s1")
        service.updateVisibleStocks(s1, listOf("A", "B", "C"))
        recorder.reset()

        service.onDataSync(dayKeys("A", "B"))

        assertEquals(1, recorder.pushCount(s1), "一个 session 多 code 变更只发一帧")
        assertEquals(setOf("A", "B"), recorder.codesFor(s1).toSet())
    }

    // 用例 5：与旧实现等价性 —— 随机输入下逐 session 推送集合一致，并量化扫描步数收益。
    @Test
    fun `new reverse-index push is equivalent to legacy full-scan and scans far fewer steps`() = runTest {
        val rng = java.util.Random(20260614L)
        val pool = (1..200).map { "S%03d".format(it) }

        repeat(40) { round ->
            val recorder = PushRecorder(json)
            val service = newService(recorder)

            // 构造 80 个 session，各随机看 1..15 只股票。
            val sessionCodes = LinkedHashMap<DefaultWebSocketServerSession, List<String>>()
            repeat(80) { i ->
                val session = fakeSession("r$round-s$i")
                val k = 1 + rng.nextInt(15)
                val codes = (0 until k).map { pool[rng.nextInt(pool.size)] }.distinct()
                sessionCodes[session] = codes
                service.updateVisibleStocks(session, codes)
            }

            // 随机挑 1..10 个变更 code（含可能没人看的）。
            val changeCount = 1 + rng.nextInt(10)
            val changed = (0 until changeCount).map { pool[rng.nextInt(pool.size)] }.distinct()

            // 旧实现 oracle：展开全 session 求并集，逐 session 过滤 changed。
            val unionSet = sessionCodes.values.flatten().toSet()
            val effectiveChanged = changed.filter { it in unionSet }.toSet()
            val legacyExpected = LinkedHashMap<DefaultWebSocketServerSession, Set<String>>()
            // 旧实现扫描步数 = 全部 (session, visibleCode) 二元组数（每个都要 `it in changedDayCodes` 判断）。
            var legacySteps = 0
            sessionCodes.forEach { (session, codes) ->
                legacySteps += codes.size
                val hit = codes.filter { it in effectiveChanged }.toSet()
                if (hit.isNotEmpty()) legacyExpected[session] = hit
            }

            // 丢弃页面建立时的初始推送，只保留变更推送做等价比对。
            recorder.reset()
            // 新实现：反向索引驱动推送。
            service.onDataSync(changed.map { CandleKey(it, CandlePeriod.DAY) })

            // 逐 session 比对推送集合（值等价）。
            val newActual = LinkedHashMap<DefaultWebSocketServerSession, Set<String>>()
            recorder.received.forEach { (session, frames) ->
                // 每个受影响 session 恰好一帧。
                assertEquals(1, frames.size, "round=$round 每个 session 至多一帧")
                newActual[session] = frames.single().toSet()
            }
            assertEquals(
                legacyExpected.mapValues { it.value },
                newActual,
                "round=$round 新旧推送集合必须逐 session 等价"
            )

            // 新实现扫描步数 = sum over changed code of sessionsPerCode（反向边数）。
            val idx = service.reverseIndexSnapshot()
            var newSteps = 0
            effectiveChanged.forEach { code -> newSteps += idx[code]?.size ?: 0 }

            // 变更 code 远少于可见总量时，新实现扫描步数不超过旧实现（常态显著更少）。
            assertTrue(
                newSteps <= legacySteps,
                "round=$round 反向索引扫描步数($newSteps)应 <= 全表扫描步数($legacySteps)"
            )
        }
    }

    // 用例 6：边界 —— 空列表更新清空该 session 的所有反向边。
    @Test
    fun `empty update clears all reverse edges for that session`() {
        val recorder = PushRecorder(json)
        val service = newService(recorder)
        val s1 = fakeSession("s1")
        val s2 = fakeSession("s2")
        service.updateVisibleStocks(s1, listOf("A", "B"))
        service.updateVisibleStocks(s2, listOf("B"))

        // 空列表更新：等价于该 session 退出页面上下文。
        service.updateVisibleStocks(s1, emptyList())
        val idx = service.reverseIndexSnapshot()

        // A 完全无人看 -> key 删除；B 仍有 s2 -> 保留且只含 s2。
        assertFalse(idx.containsKey("A"))
        assertEquals(setOf("B"), idx.keys)
        assertEquals(setOf(s2), idx["B"])
    }

    // 用例 6b：空列表 / 空变更下 onDataSync 不抛异常、零推送。
    @Test
    fun `onDataSync is a no-op when nobody watches the changed codes`() = runTest {
        val recorder = PushRecorder(json)
        val service = newService(recorder)
        val s1 = fakeSession("s1")
        service.updateVisibleStocks(s1, listOf("A"))
        recorder.reset()

        // 变更 code 无人关注。
        service.onDataSync(dayKeys("Z"))
        // 非 DAY 周期变更应被忽略。
        service.onDataSync(listOf(CandleKey("A", CandlePeriod.MIN_30)))

        assertEquals(0, recorder.pushCount(s1))
    }
}
