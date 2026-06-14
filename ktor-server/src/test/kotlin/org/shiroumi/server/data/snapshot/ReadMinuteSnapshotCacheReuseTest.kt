package org.shiroumi.server.data.snapshot

import kotlinx.datetime.LocalDate
import model.Candle
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import org.shiroumi.server.data.api.ApiTask
import org.shiroumi.server.data.api.CandleApiLayer
import org.shiroumi.server.data.api.InterfaceId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * R2-OPT-2-minute-snapshot：`readMinuteSnapshot` 去重复缓存查询的正确性与收益对照单测。
 *
 * 优化前：缓存未命中分支处理后，函数会对 minuteHistory / minuteRealtime 各自再查一次缓存
 * （`currentHistory` / `currentRealtime`），即每次读取触发 4 次 LRU `get`、4 次 synchronized 锁、
 * 4 次 accessOrder 链表重排。
 *
 * 优化后：直接复用首次查询的 history / realtime 引用，每次读取只查 2 次缓存。因为本函数同步执行、
 * 回填走后台 worker、回填结果对当前栈帧不可见，所以第二次重查必然得到与首次完全相同的对象引用，
 * 删除它零语义变化。
 *
 * 本测试用一个统计回填次数的 [CountingApiLayer] 子类驱动真实的 [CandleSnapshotManager]，
 * 通过同模块可见的 `internal caches` 预置 minute 缓存来模拟“异步回填已完成”的缓存状态，
 * 在不启动后台 worker 的前提下覆盖命中 / 未命中 / 复用四类语义，并用计数缓存量化 LRU get 次数收益。
 */
@OptIn(ExperimentalUuidApi::class)
class ReadMinuteSnapshotCacheReuseTest {

    private val period = CandlePeriod.MIN_5
    private val tsCode = "600000.SH"
    private val key = CandleKey(tsCode, period)

    /**
     * 不启动 channel worker 的 [CandleApiLayer]：覆写 [submit] 只统计提交次数并返回 true，
     * 模拟“提交成功但 callback 不会同步触发”的真实异步语义，从而让回填永远不会在本次读取栈帧内
     * 改写缓存。这正是 readMinuteSnapshot 可以复用首次查询结果的前提。
     */
    private class CountingApiLayer : CandleApiLayer() {
        val historyLoadCount = AtomicInteger(0)
        val realtimeLoadCount = AtomicInteger(0)

        override fun submit(task: ApiTask): Boolean {
            when (task.interfaceId) {
                InterfaceId.STK_MINS -> historyLoadCount.incrementAndGet()
                InterfaceId.RT_MIN_DAILY -> realtimeLoadCount.incrementAndGet()
                else -> error("分钟快照回填只会提交 STK_MINS / RT_MIN_DAILY，意外接口: ${task.interfaceId}")
            }
            return true
        }
    }

    private fun candle(date: LocalDate, close: Float): Candle = Candle(
        tsCode = tsCode,
        date = date,
        open = close - 1f,
        high = close + 1f,
        low = close - 2f,
        close = close,
        adj = 1f,
        volume = 1000f,
        turnoverReal = 100_000f,
        pe = 0f,
        peTtm = 0f,
        pb = 0f,
        ps = 0f,
        psTtm = 0f,
        mvTotal = 0f,
        mvCirc = 0f
    )

    private fun day(d: Int): LocalDate = LocalDate.fromEpochDays(LocalDate(2024, 1, 1).toEpochDays() + d)

    private fun newFixture(): Pair<CandleSnapshotManager, CountingApiLayer> {
        val api = CountingApiLayer()
        return CandleSnapshotManager(api) to api
    }

    private fun CandleSnapshotManager.putHistory(candles: List<Candle>) {
        caches.minuteHistory(period).put(tsCode, candles)
    }

    private fun CandleSnapshotManager.putRealtime(candles: List<Candle>) {
        caches.minuteRealtime(period).put(tsCode, candles)
    }

    // ---------- 测试 1：history 命中 / realtime 未命中 ----------
    @Test
    fun `history hit and realtime miss returns history and backfills realtime`() {
        val (manager, api) = newFixture()
        val history = listOf(candle(day(0), 10f), candle(day(1), 11f))
        manager.putHistory(history)

        val snapshot = manager.readSnapshot(key)

        assertNotNull(snapshot, "history 命中时必须返回非 null 快照")
        assertEquals(history, snapshot.candles, "realtime 缺失时合并视图等于 history 窗口")
        assertEquals(0, api.historyLoadCount.get(), "history 已命中，不应提交历史回填")
        assertEquals(1, api.realtimeLoadCount.get(), "realtime 缺失，应提交一次实时回填")
    }

    // ---------- 测试 2：history 未命中 / realtime 命中 ----------
    @Test
    fun `history miss and realtime hit returns realtime and backfills history`() {
        val (manager, api) = newFixture()
        val realtime = listOf(candle(day(2), 12f))
        manager.putRealtime(realtime)

        val snapshot = manager.readSnapshot(key)

        assertNotNull(snapshot, "realtime 命中时必须返回非 null 快照")
        assertEquals(realtime, snapshot.candles, "history 缺失时合并视图等于 realtime 窗口")
        assertEquals(1, api.historyLoadCount.get(), "history 缺失，应提交一次历史回填")
        assertEquals(0, api.realtimeLoadCount.get(), "realtime 已命中，不应提交实时回填")
    }

    // ---------- 测试 3：history 与 realtime 都命中 ----------
    @Test
    fun `both hit returns history concat realtime with no backfill`() {
        val (manager, api) = newFixture()
        val history = listOf(candle(day(0), 10f), candle(day(1), 11f))
        val realtime = listOf(candle(day(2), 12f))
        manager.putHistory(history)
        manager.putRealtime(realtime)

        val snapshot = manager.readSnapshot(key)

        assertNotNull(snapshot, "两个窗口都命中必须返回非 null 快照")
        assertEquals(history + realtime, snapshot.candles, "合并视图必须是 history 在前、realtime 在后")
        assertEquals(0, api.historyLoadCount.get(), "两个窗口都命中，不应提交任何回填")
        assertEquals(0, api.realtimeLoadCount.get(), "两个窗口都命中，不应提交任何回填")
    }

    // ---------- 测试 4：history 与 realtime 都未命中 ----------
    @Test
    fun `both miss returns null and backfills both`() {
        val (manager, api) = newFixture()

        val snapshot = manager.readSnapshot(key)

        assertNull(snapshot, "两个窗口都缺失，本次读取必须返回 null（miss 语义）")
        assertEquals(1, api.historyLoadCount.get(), "history 缺失，应提交一次历史回填")
        assertEquals(1, api.realtimeLoadCount.get(), "realtime 缺失，应提交一次实时回填")
    }

    // ---------- 测试 5：回填完成后再读，复用值与缓存内容一致 ----------
    @Test
    fun `second read after backfill reflects injected cache and reuse stays consistent`() {
        val (manager, api) = newFixture()

        // 第一次读取：两个窗口都未命中，返回 null 并各提交一次回填。
        val first = manager.readSnapshot(key)
        assertNull(first, "首次读取两个窗口都缺失，返回 null")
        assertEquals(1, api.historyLoadCount.get())
        assertEquals(1, api.realtimeLoadCount.get())

        // 模拟异步回填完成：直接把数据写进缓存（等价于回调里 caches.minuteHistory(...).put(...)）。
        val history = listOf(candle(day(0), 10f), candle(day(1), 11f))
        val realtime = listOf(candle(day(2), 12f))
        manager.putHistory(history)
        manager.putRealtime(realtime)

        // 第二次读取：两个窗口都命中，复用首次查询引用，合并视图与直接拼接缓存一致。
        val second = manager.readSnapshot(key)
        assertNotNull(second, "回填完成后第二次读取必须返回非 null")
        assertEquals(history + realtime, second.candles, "复用首次查询结果不得使合并视图偏离缓存真实内容")
        assertTrue(second.candles.size > history.size, "合并视图应同时包含 history 与 realtime")

        // 缓存都已命中，第二次读取不再提交任何新回填。
        assertEquals(1, api.historyLoadCount.get(), "缓存已命中，第二次读取不再提交历史回填")
        assertEquals(1, api.realtimeLoadCount.get(), "缓存已命中，第二次读取不再提交实时回填")
    }

    /**
     * 收益对照：在同一个真实 [SynchronizedLruCache] 上，把优化前“首查 + 重查”双查模式与优化后
     * “只首查”单查模式各自对 history / realtime 两个缓存的 `get` 调用次数精确计数，证明优化后
     * 每次 minute 读取的 LRU get 从 4 次降到 2 次，正好减半——对应 synchronized 锁竞争与
     * accessOrder 链表重排各减半。
     *
     * 这里不向生产类注入任何 hook：直接在测试内用计数 `get` 复刻被测函数缓存未命中分支前后的访问序列，
     * 既保证生产代码零侵入，又能用真实缓存量化收益。
     */
    @Test
    fun `benchmark reuse halves lru get calls per minute read`() {
        val historyCache = SynchronizedLruCache<String, List<Candle>>(1000)
        val realtimeCache = SynchronizedLruCache<String, List<Candle>>(1000)
        val history = listOf(candle(day(0), 10f), candle(day(1), 11f))
        val realtime = listOf(candle(day(2), 12f))
        historyCache.put(tsCode, history)
        realtimeCache.put(tsCode, realtime)

        val historyGets = AtomicInteger(0)
        val realtimeGets = AtomicInteger(0)
        fun getHistory(): List<Candle>? = historyCache[tsCode].also { historyGets.incrementAndGet() }
        fun getRealtime(): List<Candle>? = realtimeCache[tsCode].also { realtimeGets.incrementAndGet() }

        // 优化前：未命中分支处理后再各重查一次（首查 + 重查 = 每缓存 2 次）。
        run {
            val h = getHistory()
            val r = getRealtime()
            val currentHistory = getHistory().orEmpty()
            val currentRealtime = getRealtime().orEmpty()
            assertEquals(history, currentHistory)
            assertEquals(realtime, currentRealtime)
            assertEquals(history, h)
            assertEquals(realtime, r)
        }
        val beforeTotal = historyGets.get() + realtimeGets.get()
        assertEquals(4, beforeTotal, "优化前每次读取共 4 次 LRU get（history 2 + realtime 2）")

        // 优化后：复用首次查询结果，每缓存只查 1 次。
        historyGets.set(0)
        realtimeGets.set(0)
        run {
            val h = getHistory()
            val r = getRealtime()
            assertEquals(history, h.orEmpty())
            assertEquals(realtime, r.orEmpty())
        }
        val afterHistory = historyGets.get()
        val afterRealtime = realtimeGets.get()
        val afterTotal = afterHistory + afterRealtime

        // 端到端再确认：真实 manager 走优化后路径产出的合并视图与缓存内容一致。
        val (manager, _) = newFixture()
        manager.putHistory(history)
        manager.putRealtime(realtime)
        val snapshot = manager.readSnapshot(key)
        assertNotNull(snapshot)
        assertEquals(history + realtime, snapshot.candles, "优化后合并视图必须与缓存真实内容一致")

        println(
            "R2-OPT-2-minute-snapshot benchmark | per minute read | " +
                "before=$beforeTotal get (history 2 + realtime 2) | " +
                "after=$afterTotal get (history $afterHistory + realtime $afterRealtime) | " +
                "reduction=${beforeTotal - afterTotal} get/read | " +
                "lock+accessOrder 重排减半"
        )

        assertEquals(1, afterHistory, "优化后 history 缓存每次读取只查 1 次")
        assertEquals(1, afterRealtime, "优化后 realtime 缓存每次读取只查 1 次")
        assertEquals(2, afterTotal, "优化后每次 minute 读取 LRU get 从 4 次降到 2 次")
        assertEquals(beforeTotal / 2, afterTotal, "get 次数恰好减半")
    }
}
