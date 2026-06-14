package org.shiroumi.server.data.provider

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.test.runTest
import model.Candle
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import model.ws.CandleSubscribeRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.shiroumi.server.data.facade.CandleDataFacade
import org.shiroumi.server.data.snapshot.CandleSnapshotManager
import org.shiroumi.server.data.snapshot.CandleSnapshotState
import org.shiroumi.server.data.subscription.CandleProjectionService
import org.shiroumi.server.data.api.CandleApiLayer
import kotlinx.datetime.LocalDate
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi

/**
 * OPT-1-subscription：订阅模型去全局单股互斥的回归测试。
 *
 * 这套测试只验证 `CandleDataProvider` 的订阅记账语义，因此把数据真相（snapshot）与订阅记账解耦：
 * 通过 [RecordingFacade] 替换 `readSnapshot` 的返回，provider 就能在不拉起整条数据库链路的前提下
 * 跑完订阅 / 退订 / 清理 / 推送的全部分支。
 *
 * 核心被验证的不变量（即本次优化的收益）：
 * 1. 同 session 同 period 两只不同股票并存订阅，互不剔除（去掉了旧的全局单股互斥）。
 * 2. 退订基于引用计数，归零才真正摘除；同一 key 多份引用（多组件）互不误伤。
 * 3. 一个 session / 一个组件的退订不影响另一个 session / 跨周期的订阅。
 */
@OptIn(ExperimentalUuidApi::class)
class CandleDataProviderSubscriptionTest {

    // ---- 被测组件装配 ----------------------------------------------------

    /**
     * 可控读口替身：直接按 [CandleKey] 返回预置 snapshot，readSnapshot 不触碰数据库。
     * 默认返回 null（PROVIDER_SNAPSHOT_MISS），订阅记账分支因此完全确定。
     */
    private class RecordingFacade(
        snapshotManager: CandleSnapshotManager
    ) : CandleDataFacade(snapshotManager) {
        val snapshots = ConcurrentHashMap<CandleKey, CandleSnapshotState>()
        override fun readSnapshot(key: CandleKey, requestSeq: Long?): CandleSnapshotState? = snapshots[key]
    }

    private fun newProvider(): Pair<CandleDataProvider, RecordingFacade> {
        // CandleApiLayer / CandleSnapshotManager 构造期零副作用（不连库、不发网络），
        // 仅作为 super 构造入参；RecordingFacade 覆写 readSnapshot 后 manager 永不被读。
        val manager = CandleSnapshotManager(CandleApiLayer())
        val facade = RecordingFacade(manager)
        val provider = CandleDataProvider(facade, CandleProjectionService())
        return provider to facade
    }

    // ---- 测试夹具 --------------------------------------------------------

    /**
     * 用 JDK 动态代理伪造 [DefaultWebSocketServerSession]：每个代理实例都是独立对象，
     * equals/hashCode 走系统身份语义，正好满足 provider 把 session 当 Map key 的用法。
     * 零外部依赖（不引入 mockk）。
     */
    private fun mockSession(tag: String): DefaultWebSocketServerSession {
        val handler = object : InvocationHandler {
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MockSession($tag)"
                else -> throw UnsupportedOperationException("MockSession 不支持 ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            DefaultWebSocketServerSession::class.java.classLoader,
            arrayOf(DefaultWebSocketServerSession::class.java),
            handler
        ) as DefaultWebSocketServerSession
    }

    private fun request(tsCode: String, period: CandlePeriod, seq: Long): CandleSubscribeRequest =
        CandleSubscribeRequest(tsCode = tsCode, period = period, limit = 100, requestSeq = seq)

    private fun key(tsCode: String, period: CandlePeriod) = CandleKey(tsCode, period)

    private fun snapshot(key: CandleKey, version: Long, candle: Candle): CandleSnapshotState =
        CandleSnapshotState(
            key = key,
            candles = listOf(candle),
            version = version,
            updatedAt = System.currentTimeMillis(),
            sourceTag = "test",
            hasRealtimeWindow = false
        )

    private fun candle(tsCode: String): Candle = Candle(
        tsCode = tsCode,
        date = LocalDate(2024, 1, 2),
        open = 10f, high = 11f, low = 9.5f, close = 10.5f, adj = 1f,
        volume = 1000f, turnoverReal = 10500f,
        pe = 0f, peTtm = 0f, pb = 0f, ps = 0f, psTtm = 0f, mvTotal = 0f, mvCirc = 0f
    )

    // ---- 反射读私有状态（沿用仓库既有测试约定，不为测试放开生产可见性） -------

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(provider: CandleDataProvider, name: String): T {
        val field = CandleDataProvider::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(provider) as T
    }

    private fun subscribers(p: CandleDataProvider) =
        readField<ConcurrentHashMap<CandleKey, MutableSet<DefaultWebSocketServerSession>>>(p, "subscribers")

    private fun refCount(p: CandleDataProvider) =
        readField<ConcurrentHashMap<Pair<DefaultWebSocketServerSession, CandleKey>, Int>>(p, "subscriptionRefCount")

    private fun requests(p: CandleDataProvider) =
        readField<ConcurrentHashMap<Pair<DefaultWebSocketServerSession, CandleKey>, CandleSubscribeRequest>>(p, "subscriptionRequests")

    private fun lastSentVersion(p: CandleDataProvider) =
        readField<ConcurrentHashMap<CandleKey, Long>>(p, "lastSentVersion")

    // ================================================================
    // 场景 1：同 session 同 period 两只不同股票订阅互不剔除
    // ================================================================
    @Test
    fun `same session same period two stocks coexist`() = runTest {
        val (provider, _) = newProvider()
        val session = mockSession("s1")
        val keyA = key("000001.SZ", CandlePeriod.DAY)
        val keyB = key("600519.SH", CandlePeriod.DAY)

        provider.subscribe(session, keyA, request("000001.SZ", CandlePeriod.DAY, 1))
        assertTrue(subscribers(provider)[keyA]?.contains(session) == true, "A 应在 subscribers")
        assertEquals(1, refCount(provider)[session to keyA], "A 引用计数应为 1")

        provider.subscribe(session, keyB, request("600519.SH", CandlePeriod.DAY, 2))

        // 关键收益断言：旧的全局单股互斥被去掉，A 不应被 B 剔除。
        assertTrue(subscribers(provider)[keyA]?.contains(session) == true, "B 订阅后 A 仍应在 subscribers（不再互斥）")
        assertEquals(1, refCount(provider)[session to keyA], "A 引用计数应保持 1")
        assertTrue(subscribers(provider)[keyB]?.contains(session) == true, "B 应在 subscribers")
        assertEquals(1, refCount(provider)[session to keyB], "B 引用计数应为 1")

        // 两只股票的请求都被忠实记录。
        assertTrue(requests(provider).containsKey(session to keyA), "A 的请求应被记录")
        assertTrue(requests(provider).containsKey(session to keyB), "B 的请求应被记录")
    }

    // ================================================================
    // 场景 2：切股时旧订阅释放（退订归零彻底清理）
    // ================================================================
    @Test
    fun `unsubscribe releases stale subscription`() = runTest {
        val (provider, _) = newProvider()
        val session = mockSession("s1")
        val keyA = key("000001.SZ", CandlePeriod.DAY)

        provider.subscribe(session, keyA, request("000001.SZ", CandlePeriod.DAY, 1))
        // 模拟 provider 已下发过一次（手工写入 lastSentVersion，验证退订归零会一并清理）。
        lastSentVersion(provider)[keyA] = 7L

        provider.unsubscribe(session, keyA)

        assertNull(refCount(provider)[session to keyA], "退订归零后引用计数 entry 应被删除")
        assertFalse(subscribers(provider)[keyA]?.contains(session) == true, "A 应从 subscribers 摘除")
        assertNull(subscribers(provider)[keyA], "无人订阅后 key 槽位应被移除")
        assertFalse(requests(provider).containsKey(session to keyA), "请求快照应被清理")
        assertNull(lastSentVersion(provider)[keyA], "lastSentVersion 应被一并清理")
    }

    // ================================================================
    // 场景 3：一个组件退订不影响另一个（跨 session + 跨周期独立）
    // ================================================================
    @Test
    fun `one unsubscribe does not affect others`() = runTest {
        val (provider, _) = newProvider()
        val session1 = mockSession("s1")
        val session2 = mockSession("s2")
        val keyDay = key("000001.SZ", CandlePeriod.DAY)
        val keyMin5 = key("000001.SZ", CandlePeriod.MIN_5)

        // session1 订阅 A/DAY 和 A/MIN_5（跨周期叠加）。
        provider.subscribe(session1, keyDay, request("000001.SZ", CandlePeriod.DAY, 1))
        provider.subscribe(session1, keyMin5, request("000001.SZ", CandlePeriod.MIN_5, 2))
        // session2 也订阅 A/DAY（同 key 不同 session）。
        provider.subscribe(session2, keyDay, request("000001.SZ", CandlePeriod.DAY, 3))

        assertEquals(2, subscribers(provider)[keyDay]?.size, "A/DAY 应有两个 session")

        // session1 退订 A/DAY。
        provider.unsubscribe(session1, keyDay)

        assertNull(refCount(provider)[session1 to keyDay], "session1 的 A/DAY 引用应归零删除")
        assertFalse(subscribers(provider)[keyDay]?.contains(session1) == true, "session1 应从 A/DAY 摘除")
        // session2 不受影响。
        assertTrue(subscribers(provider)[keyDay]?.contains(session2) == true, "session2 的 A/DAY 应保留")
        assertEquals(1, refCount(provider)[session2 to keyDay], "session2 引用计数应保持 1")
        // session1 的跨周期 MIN_5 订阅不受影响。
        assertTrue(subscribers(provider)[keyMin5]?.contains(session1) == true, "session1 的 A/MIN_5 应保留")
        assertEquals(1, refCount(provider)[session1 to keyMin5], "session1 的 MIN_5 引用计数应为 1")
    }

    // ================================================================
    // 引用计数核心：同 (session,key) 多份引用，逐份释放，最后一份才摘除
    // ================================================================
    @Test
    fun `reference counting tracks multiple components on same key`() = runTest {
        val (provider, _) = newProvider()
        val session = mockSession("s1")
        val keyA = key("000001.SZ", CandlePeriod.DAY)

        // 两个独立组件订阅同一 (session, key)。
        provider.subscribe(session, keyA, request("000001.SZ", CandlePeriod.DAY, 1))
        provider.subscribe(session, keyA, request("000001.SZ", CandlePeriod.DAY, 2))
        assertEquals(2, refCount(provider)[session to keyA], "两份引用计数应为 2")
        assertEquals(1, subscribers(provider)[keyA]?.size, "subscribers 中 session 只出现一次")

        // 组件 1 onDispose：计数降到 1，订阅仍在（不误伤组件 2）。
        provider.unsubscribe(session, keyA)
        assertEquals(1, refCount(provider)[session to keyA], "释放一份后计数应为 1")
        assertTrue(subscribers(provider)[keyA]?.contains(session) == true, "仍有一份引用，订阅不应被掐断")

        // 组件 2 onDispose：计数归零，彻底摘除。
        provider.unsubscribe(session, keyA)
        assertNull(refCount(provider)[session to keyA], "最后一份释放后计数 entry 应删除")
        assertNull(subscribers(provider)[keyA], "无引用后 key 槽位应被移除")
    }

    // ================================================================
    // 计数下溢防护：对未订阅 / 已归零的 (session,key) 重复退订不产生负数
    // ================================================================
    @Test
    fun `unsubscribe never underflows`() = runTest {
        val (provider, _) = newProvider()
        val session = mockSession("s1")
        val keyA = key("000001.SZ", CandlePeriod.DAY)

        // 从未订阅就退订：无副作用。
        provider.unsubscribe(session, keyA)
        assertNull(refCount(provider)[session to keyA], "未订阅退订不应写入计数")

        // 订阅一次后退订两次：计数不应为负。
        provider.subscribe(session, keyA, request("000001.SZ", CandlePeriod.DAY, 1))
        provider.unsubscribe(session, keyA)
        provider.unsubscribe(session, keyA)
        assertNull(refCount(provider)[session to keyA], "重复退订归零后无残骸")
        assertFalse(refCount(provider).values.any { it < 0 }, "任何计数都不应为负")
    }

    // ================================================================
    // 补充验证 A：cleanupSession 清空 session 全部订阅（无论计数残值），且无残骸
    // ================================================================
    @Test
    fun `cleanupSession clears all bindings regardless of refcount`() = runTest {
        val (provider, _) = newProvider()
        val session1 = mockSession("s1")
        val session2 = mockSession("s2")
        val keyDay = key("000001.SZ", CandlePeriod.DAY)
        val keyMin5 = key("000001.SZ", CandlePeriod.MIN_5)

        // session1 在 DAY 上叠了两份引用，又订了 MIN_5；session2 共享 DAY。
        provider.subscribe(session1, keyDay, request("000001.SZ", CandlePeriod.DAY, 1))
        provider.subscribe(session1, keyDay, request("000001.SZ", CandlePeriod.DAY, 2))
        provider.subscribe(session1, keyMin5, request("000001.SZ", CandlePeriod.MIN_5, 3))
        provider.subscribe(session2, keyDay, request("000001.SZ", CandlePeriod.DAY, 4))
        assertEquals(2, refCount(provider)[session1 to keyDay], "session1 DAY 应有两份引用")

        provider.cleanupSession(session1)

        // session1 的所有绑定（含多份引用的 DAY 与跨周期 MIN_5）一次性清空。
        assertNull(refCount(provider)[session1 to keyDay], "session1 DAY 引用应被清空")
        assertNull(refCount(provider)[session1 to keyMin5], "session1 MIN_5 引用应被清空")
        assertFalse(requests(provider).containsKey(session1 to keyDay), "session1 DAY 请求应被清空")
        assertFalse(requests(provider).containsKey(session1 to keyMin5), "session1 MIN_5 请求应被清空")
        assertNull(subscribers(provider)[keyMin5], "session1 独占的 MIN_5 槽位应被移除")
        // 无任何引用计数残骸属于 session1。
        assertFalse(
            refCount(provider).keys.any { it.first === session1 },
            "cleanupSession 后不应残留 session1 的引用计数"
        )

        // session2 完全不受影响。
        assertTrue(subscribers(provider)[keyDay]?.contains(session2) == true, "session2 DAY 订阅应保留")
        assertEquals(1, refCount(provider)[session2 to keyDay], "session2 引用计数应保持 1")
    }

    // ================================================================
    // 补充验证 B：onDataSync 只向仍在 subscribers 的 key 推进版本，
    //            无订阅者 / 已退订归零的 key 不被推送（不写 lastSentVersion）
    // ================================================================
    @Test
    fun `onDataSync only advances keys with live subscribers`() = runTest {
        val (provider, facade) = newProvider()
        val session = mockSession("s1")
        val subscribedKey = key("000001.SZ", CandlePeriod.DAY)
        val unsubscribedKey = key("600519.SH", CandlePeriod.DAY)

        // 两个 key 都有可读 snapshot，但只有 subscribedKey 有活订阅。
        facade.snapshots[subscribedKey] = snapshot(subscribedKey, version = 5L, candle("000001.SZ"))
        facade.snapshots[unsubscribedKey] = snapshot(unsubscribedKey, version = 9L, candle("600519.SH"))

        // 订阅 subscribedKey；初次 subscribe 命中 snapshot 会写入 lastSentVersion=5。
        provider.subscribe(session, subscribedKey, request("000001.SZ", CandlePeriod.DAY, 1))
        assertEquals(5L, lastSentVersion(provider)[subscribedKey], "订阅时应已对齐到 snapshot 版本 5")

        // 推进 subscribedKey 的版本到 6，并把无订阅者的 unsubscribedKey 一起塞进 changedKeys。
        facade.snapshots[subscribedKey] = snapshot(subscribedKey, version = 6L, candle("000001.SZ"))
        provider.onDataSync(listOf(subscribedKey, unsubscribedKey))

        // 有活订阅的 key 版本被推进；无订阅者的 key 被跳过，不写 lastSentVersion。
        assertEquals(6L, lastSentVersion(provider)[subscribedKey], "活订阅 key 版本应推进到 6")
        assertNull(lastSentVersion(provider)[unsubscribedKey], "无订阅者 key 不应被推送/记录版本")

        // 退订后再次 onDataSync：归零 key 不再收消息，版本不再推进。
        provider.unsubscribe(session, subscribedKey)
        assertNull(lastSentVersion(provider)[subscribedKey], "退订归零后版本记录应被清理")
        facade.snapshots[subscribedKey] = snapshot(subscribedKey, version = 7L, candle("000001.SZ"))
        provider.onDataSync(listOf(subscribedKey))
        assertNull(lastSentVersion(provider)[subscribedKey], "退订后该 key 不应再被推送")
    }
}
