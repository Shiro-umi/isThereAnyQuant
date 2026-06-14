package org.shiroumi.server.repository

import model.candle.Exchange
import org.shiroumi.server.runtime.stock.StockCatalogEntry
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * OPT-4-topk 验收测试。
 *
 * 被测对象是列表页 RANK_SCORE 排序分支抽出的纯函数 [TopKPageSelector.select]：它用有界堆做部分选择，
 * 只排出当前页所需的 TopK，把比较代价从全排序的 O(n log n) 降到 O(n log k)。
 *
 * 测试维度：
 * 1. 与"全量稳定排序 + drop/take"严格等价（DESC / ASC、随机 offset/pageSize、含大量打分相同的 tie）。
 * 2. 深翻页边界（末页、超末页、k == size 临界）退化为全排序且取页正确。
 * 3. 排序稳定性：打分相同的条目保留 catalog 原始顺序，深翻页页间无重复/遗漏。
 * 4. 性能收益：用计数比较器证明 TopK 在 k << n 时比较次数远低于全排序。
 */
class TopKPageSelectorTest {

    /** 构造一支股票目录条目；只有打分参与排序，其余字段用占位值。 */
    private fun entry(index: Int): StockCatalogEntry {
        val code = index.toString().padStart(6, '0')
        return StockCatalogEntry(
            tsCode = "$code.SZ",
            name = "Stock$index",
            cnSpell = "S$index",
            market = "主板",
            listStatus = "L",
            exchange = Exchange.SZ,
            lowerTsCode = "$code.sz",
            lowerName = "stock$index",
            lowerCnSpell = "s$index",
            lowerCodeWithoutSuffix = code
        )
    }

    /**
     * 参考实现：全量稳定排序后取第 [offset] 起 [pageSize] 条。
     * 用原始下标补全序，与 [TopKPageSelector] 的稳定语义一致，作为等价性黄金标准。
     */
    private fun referencePage(
        items: List<StockCatalogEntry>,
        comparator: Comparator<StockCatalogEntry>,
        offset: Int,
        pageSize: Int
    ): List<StockCatalogEntry> {
        if (offset >= items.size || pageSize <= 0) return emptyList()
        val stable = Comparator<Int> { i, j ->
            val c = comparator.compare(items[i], items[j])
            if (c != 0) c else i.compareTo(j)
        }
        return (0 until items.size).sortedWith(stable)
            .drop(offset).take(pageSize)
            .map { items[it] }
    }

    private fun scoreComparator(
        scores: Map<String, Double>,
        descending: Boolean
    ): Comparator<StockCatalogEntry> {
        val byScore = compareBy<StockCatalogEntry> { scores[it.tsCode] ?: 0.0 }
        return if (descending) byScore.reversed() else byScore
    }

    @Test
    fun `TopK 与全量排序在随机 offset pageSize 下严格等价`() {
        val rng = Random(20260614)
        val n = 5000
        val items = (0 until n).map { entry(it) }
        // 打分故意压缩到较小取值域，制造大量 tie，逼出稳定性差异。
        val scores = items.associate { it.tsCode to rng.nextInt(0, 50).toDouble() }

        for (descending in listOf(true, false)) {
            val cmp = scoreComparator(scores, descending)
            // 覆盖首页、浅翻页、深翻页与若干随机组合。
            val cases = buildList {
                add(0 to 20)
                add(0 to 1)
                add(20 to 20)
                add(2480 to 20)
                add(4980 to 20)
                repeat(40) {
                    val offset = rng.nextInt(0, n + 50)
                    val pageSize = rng.nextInt(1, 100)
                    add(offset to pageSize)
                }
            }
            for ((offset, pageSize) in cases) {
                val expected = referencePage(items, cmp, offset, pageSize)
                val actual = TopKPageSelector.select(items, cmp, offset, pageSize)
                assertEquals(
                    expected.map { it.tsCode },
                    actual.map { it.tsCode },
                    "desc=$descending offset=$offset pageSize=$pageSize 页内容不一致"
                )
            }
        }
    }

    @Test
    fun `深翻页与超末页边界返回正确条数`() {
        val n = 100
        val items = (0 until n).map { entry(it) }
        val scores = items.mapIndexed { i, e -> e.tsCode to (n - i).toDouble() }.toMap()
        val cmp = scoreComparator(scores, descending = true)

        // 末页：offset=80 取 20 条满页。
        val lastFull = TopKPageSelector.select(items, cmp, 80, 20)
        assertEquals(20, lastFull.size)
        assertEquals(referencePage(items, cmp, 80, 20).map { it.tsCode }, lastFull.map { it.tsCode })

        // 末页跨界：offset=90 只剩 10 条，不能凑满 20。
        val tail = TopKPageSelector.select(items, cmp, 90, 20)
        assertEquals(10, tail.size)
        assertEquals(referencePage(items, cmp, 90, 20).map { it.tsCode }, tail.map { it.tsCode })

        // 超末页：offset 超过总数返回空。
        assertTrue(TopKPageSelector.select(items, cmp, 100, 20).isEmpty())
        assertTrue(TopKPageSelector.select(items, cmp, 150, 20).isEmpty())

        // k == size 临界（offset=80, pageSize=20 → k=100）走全排序降级路径，结果仍正确。
        val boundary = TopKPageSelector.select(items, cmp, 80, 20)
        assertEquals(referencePage(items, cmp, 80, 20).map { it.tsCode }, boundary.map { it.tsCode })
    }

    @Test
    fun `空列表与非法 pageSize 返回空`() {
        val items = (0 until 10).map { entry(it) }
        val scores = items.mapIndexed { i, e -> e.tsCode to i.toDouble() }.toMap()
        val cmp = scoreComparator(scores, descending = true)

        assertTrue(TopKPageSelector.select(emptyList(), cmp, 0, 20).isEmpty())
        assertTrue(TopKPageSelector.select(items, cmp, 0, 0).isEmpty())
        assertTrue(TopKPageSelector.select(items, cmp, 0, -5).isEmpty())
    }

    @Test
    fun `打分相同的条目保留 catalog 原始顺序`() {
        // 5 支股票，打分 [1,2,2,2,3]，DESC 期望 3 → 三个 2（按原序 idx1,2,3） → 1。
        val items = (0 until 5).map { entry(it) }
        val scoreList = listOf(1.0, 2.0, 2.0, 2.0, 3.0)
        val scores = items.mapIndexed { i, e -> e.tsCode to scoreList[i] }.toMap()
        val cmp = scoreComparator(scores, descending = true)

        val page = TopKPageSelector.select(items, cmp, 0, 5)
        assertEquals(
            listOf(items[4].tsCode, items[1].tsCode, items[2].tsCode, items[3].tsCode, items[0].tsCode),
            page.map { it.tsCode }
        )
    }

    @Test
    fun `深翻页相邻页拼接等于全量排序无重复无遗漏`() {
        val rng = Random(99)
        val n = 1000
        val items = (0 until n).map { entry(it) }
        // 仅 10 个取值，制造海量 tie，最考验稳定性。
        val scores = items.associate { it.tsCode to rng.nextInt(0, 10).toDouble() }
        val cmp = scoreComparator(scores, descending = true)

        val pageSize = 37
        val stitched = ArrayList<String>(n)
        var offset = 0
        while (offset < n) {
            stitched += TopKPageSelector.select(items, cmp, offset, pageSize).map { it.tsCode }
            offset += pageSize
        }

        val fullSorted = referencePage(items, cmp, 0, n).map { it.tsCode }
        assertEquals(fullSorted, stitched, "逐页拼接结果必须与全量稳定排序完全一致")
        assertEquals(n, stitched.toSet().size, "拼接结果不能有重复")
    }

    @Test
    fun `TopK 比较次数显著低于全量排序`() {
        val rng = Random(7)
        val n = 5000
        val items = (0 until n).map { entry(it) }
        val scores = items.associate { it.tsCode to rng.nextDouble() }
        val baseCmp = scoreComparator(scores, descending = true)

        val offset = 0
        val pageSize = 20
        val k = offset + pageSize

        // 计数比较器：统计 TopK 路径真实比较次数。
        var topKCompares = 0L
        val countingCmp = Comparator<StockCatalogEntry> { a, b ->
            topKCompares++
            baseCmp.compare(a, b)
        }
        TopKPageSelector.select(items, countingCmp, offset, pageSize)

        // 全量稳定排序基准：统计同口径比较次数。
        var fullSortCompares = 0L
        val stable = Comparator<Int> { i, j ->
            fullSortCompares++
            baseCmp.compare(items[i], items[j])
        }
        (0 until n).sortedWith(stable)

        println("OPT-4-topk 基准: n=$n k=$k TopK比较=$topKCompares 全排序比较=$fullSortCompares")
        // 理论上界：TopK 约 O(n log k)，全排序约 O(n log n)。k=20 时差距应在数倍以上。
        assertTrue(
            topKCompares < fullSortCompares / 3,
            "TopK 比较次数($topKCompares)应远低于全排序($fullSortCompares)"
        )
    }
}
