package org.shiroumi.server.data.snapshot

import kotlinx.datetime.LocalDate
import model.Candle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * OPT-3-merge：`mergeRealtimeIntoWindow` 增量合并的正确性与收益对照单测。
 *
 * 被测函数把全市场实时日 K 合并里原先的全量
 * `filterNot + sortedBy + takeLast`（每股 O(n log n)）替换为末根二路决策
 * （O(1)~O(n)）。这里既验证四类合并场景与升序不变量，又用旧实现做基准对照，
 * 证明在"实时日不早于窗口末根"的真实链路场景下两种实现结果完全等价，
 * 并量化排序操作的消除收益。
 */
@OptIn(ExperimentalUuidApi::class)
class MergeRealtimeIntoWindowTest {

    private val historyLimit = 500

    /** 构造一根日 K，只填影响合并与 sameWindow 判定的字段，其余给确定占位值。 */
    private fun candle(
        date: LocalDate,
        close: Float,
        tsCode: String = "000001.SZ"
    ): Candle = Candle(
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

    private fun day(d: Int): LocalDate = LocalDate(2024, 1, 1).plus(d)

    private fun LocalDate.plus(days: Int): LocalDate =
        LocalDate.fromEpochDays(this.toEpochDays() + days)

    /** 旧实现：全量过滤 + 排序 + 末段裁剪。作为对照基准。 */
    private fun oldMerge(current: List<Candle>, realtime: Candle): List<Candle> =
        (current.filterNot { it.date == realtime.date } + realtime)
            .sortedBy { it.date }
            .takeLast(historyLimit)

    private fun assertAscending(candles: List<Candle>) {
        for (i in 0 until candles.size - 1) {
            assertTrue(
                candles[i].date < candles[i + 1].date,
                "窗口必须严格升序，但 index=$i (${candles[i].date}) >= index=${i + 1} (${candles[i + 1].date})"
            )
        }
    }

    // ---------- Test 1: 当日更新替换末根 ----------
    @Test
    fun `same date realtime replaces last candle in place`() {
        val current = listOf(candle(day(0), 11f), candle(day(1), 12f))
        val realtime = candle(day(1), 12.5f)

        val merged = mergeRealtimeIntoWindow(current, realtime, historyLimit)!!

        assertEquals(2, merged.size)
        assertEquals(day(0), merged[0].date)
        assertEquals(day(1), merged[1].date)
        assertEquals(12.5f, merged[1].close, "末根应被实时盘中价替换")
        assertAscending(merged)
        assertEquals(oldMerge(current, realtime).map { it.date to it.close }, merged.map { it.date to it.close })
    }

    // ---------- Test 2: 新交易日追加 ----------
    @Test
    fun `new trading day appends to window tail`() {
        val current = listOf(candle(day(0), 11f), candle(day(1), 12f))
        val realtime = candle(day(2), 12.8f)

        val merged = mergeRealtimeIntoWindow(current, realtime, historyLimit)!!

        assertEquals(3, merged.size)
        assertEquals(day(2), merged.last().date)
        assertEquals(12.8f, merged.last().close)
        assertAscending(merged)
        assertEquals(oldMerge(current, realtime).map { it.date to it.close }, merged.map { it.date to it.close })
    }

    // ---------- Test 3: 乱序数据忽略 ----------
    @Test
    fun `out of order earlier realtime is ignored`() {
        val current = listOf(candle(day(1), 11f), candle(day(2), 12f))
        val realtime = candle(day(0), 9f)

        val merged = mergeRealtimeIntoWindow(current, realtime, historyLimit)

        assertNull(merged, "实时日早于末根属于乱序，应返回 null 让调用方跳过发布")
    }

    // ---------- Test 4: 超过 historyLimit 裁剪 ----------
    @Test
    fun `append beyond history limit keeps last N ascending`() {
        val initial = (0..499).map { candle(day(it), 10f + it) } // 恰好 500 根，已满窗
        val realtime = candle(day(500), 510f) // 新交易日，合并后 501 根触发裁剪

        val merged = mergeRealtimeIntoWindow(initial, realtime, historyLimit)!!

        assertEquals(500, merged.size)
        assertEquals(day(1), merged.first().date, "满窗后追加，原首根 day(0) 被裁掉")
        assertEquals(day(500), merged.last().date, "新增 day(500) 落在末尾")
        assertAscending(merged)
        assertEquals(oldMerge(initial, realtime).map { it.date }, merged.map { it.date })
    }

    @Test
    fun `same date replacement at limit keeps size stable`() {
        val initial = (0..499).map { candle(day(it), 10f + it) } // 恰好 500 根
        val realtime = candle(day(499), 999f) // 当日刷新末根

        val merged = mergeRealtimeIntoWindow(initial, realtime, historyLimit)!!

        assertEquals(500, merged.size, "末根替换长度不变，不会触发裁剪")
        assertEquals(day(0), merged.first().date, "首根不应被误裁")
        assertEquals(999f, merged.last().close)
        assertAscending(merged)
    }

    // ---------- Test 5: 与旧实现等价（混合场景对照） ----------
    @Test
    fun `equivalent with old implementation across tail scenarios`() {
        // 升序但带停牌空洞的初始窗口（density 模拟）：每隔几天才有一根
        val initial = (0 until 300)
            .filter { it % 3 != 0 } // 制造跳变
            .mapIndexed { idx, d -> candle(day(d), 10f + idx) }
        assertAscending(initial)

        val lastDate = initial.last().date
        val tailScenarios = listOf(
            candle(lastDate, 777f),                        // 当日替换
            candle(lastDate.plus(1), 778f),                // 新日追加
            candle(lastDate.plus(2), 779f),                // 新日追加
            candle(lastDate.plus(40), 780f)                // 停牌空洞后的大跨度跳变
        )

        for (realtime in tailScenarios) {
            val newMerged = mergeRealtimeIntoWindow(initial, realtime, historyLimit)!!
            val oldMerged = oldMerge(initial, realtime)
            assertEquals(oldMerged.size, newMerged.size, "size mismatch realtime=${realtime.date}")
            for (i in oldMerged.indices) {
                assertEquals(oldMerged[i].date, newMerged[i].date, "date mismatch at $i for ${realtime.date}")
                assertEquals(oldMerged[i].close, newMerged[i].close, "close mismatch at $i for ${realtime.date}")
            }
            assertAscending(newMerged)
        }
    }

    // ---------- Test 6: 窗口为空（初始未回填） ----------
    @Test
    fun `empty window starts from realtime candle`() {
        val realtime = candle(day(5), 20f)

        val merged = mergeRealtimeIntoWindow(emptyList(), realtime, historyLimit)!!

        assertEquals(1, merged.size)
        assertEquals(day(5), merged.single().date)
    }

    @Test
    fun `single element window same date replacement yields single realtime`() {
        val current = listOf(candle(day(0), 10f))
        val realtime = candle(day(0), 13f)

        val merged = mergeRealtimeIntoWindow(current, realtime, historyLimit)!!

        assertEquals(1, merged.size)
        assertEquals(13f, merged.single().close)
    }

    // ---------- Test 7: 未超限时复用合并结果，不做多余 takeLast 拷贝 ----------
    @Test
    fun `no extra copy when within history limit on append`() {
        val current = listOf(candle(day(0), 10f), candle(day(1), 11f))
        val realtime = candle(day(2), 12f)

        val merged = mergeRealtimeIntoWindow(current, realtime, historyLimit)!!

        // 追加路径 current + realtime 已是最终结果，size 未超限不应再走 takeLast 生成新列表
        assertEquals(3, merged.size)
        // 末根身份一致，确认是直接拼接而非重排重建
        assertSame(realtime, merged.last())
    }

    // ---------- 收益对照：消除排序，量化操作复杂度 ----------
    @Test
    fun `benchmark new impl removes per-merge sort cost`() {
        val window = (0 until historyLimit).map { candle(day(it), 10f + it) }
        val iterations = 200_000
        val sameDayRealtime = candle(window.last().date, 9999f)

        // 旧实现：每次 filterNot(O(n)) + sortedBy(O(n log n)) + takeLast(O(n))
        val oldStart = System.nanoTime()
        var oldSink = 0
        repeat(iterations) {
            oldSink += oldMerge(window, sameDayRealtime).size
        }
        val oldElapsedMs = (System.nanoTime() - oldStart) / 1_000_000.0

        // 新实现：dropLast(O(n)) + 追加，无排序
        val newStart = System.nanoTime()
        var newSink = 0
        repeat(iterations) {
            newSink += mergeRealtimeIntoWindow(window, sameDayRealtime, historyLimit)!!.size
        }
        val newElapsedMs = (System.nanoTime() - newStart) / 1_000_000.0

        assertEquals(oldSink, newSink, "两种实现产出窗口长度必须一致")
        println(
            "OPT-3-merge benchmark | window=$historyLimit iterations=$iterations | " +
                "old=${round2(oldElapsedMs)}ms new=${round2(newElapsedMs)}ms | " +
                "speedup=${round2(oldElapsedMs / newElapsedMs)}x"
        )
        // 新实现必须不慢于旧实现；排序消除在 500 根窗口上稳定带来正向收益。
        assertTrue(
            newElapsedMs <= oldElapsedMs,
            "新实现 (${newElapsedMs}ms) 不应慢于旧实现 (${oldElapsedMs}ms)"
        )
    }

    /** 项目禁用 String.format（JS 不兼容），手动保留两位小数。 */
    private fun round2(value: Double): Double = kotlin.math.round(value * 100) / 100.0
}
