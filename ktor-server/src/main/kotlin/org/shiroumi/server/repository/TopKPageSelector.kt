package org.shiroumi.server.repository

import java.util.PriorityQueue

/**
 * 列表页排序 + 分页的选择器。
 *
 * 列表页只需要某一页（`offset` 起 `pageSize` 条）的有序结果，不需要把全部 ~5000 条目完整排序。
 * 当请求页落在列表前部（深翻页除外）时，只需选出排名最靠前的 `offset + pageSize` 条即可覆盖该页，
 * 用有界堆做部分选择，把比较代价从全排序的 O(n log n) 降到 O(n log k)（k = offset + pageSize）。
 *
 * 设计要点：
 * - **稳定性**：以传入顺序（catalog 原始顺序）的下标作为次序键参与比较，保证打分相同的条目按原始顺序排列。
 *   这样深翻页时相邻页之间不会出现重复或遗漏。
 * - **等价性**：任意 offset / pageSize 下，选择器返回的页与"全量稳定排序后再 drop/take"完全一致。
 * - **降级**：当 k 覆盖到列表末尾（offset + pageSize >= size）时退化为全量稳定排序，行为与原实现一致。
 */
internal object TopKPageSelector {

    /**
     * 按 [comparator] 升序语义选出 [items] 中第 [offset]（含）起、最多 [pageSize] 条结果。
     *
     * [comparator] 表达"谁排在前面"的偏序：`comparator.compare(a, b) < 0` 表示 a 排在 b 前。
     * 打分相同的条目按 [items] 的原始下标升序排列，保证排序稳定。
     *
     * @param offset 已按 0 基规范化的起始下标，调用方需保证 >= 0。
     * @param pageSize 页大小，调用方需保证 >= 1。
     */
    fun <T> select(
        items: List<T>,
        comparator: Comparator<T>,
        offset: Int,
        pageSize: Int
    ): List<T> {
        val size = items.size
        if (size == 0 || offset >= size || pageSize <= 0) return emptyList()

        // 以原始下标做次序键，把偏序补成全序，确保稳定。
        val stable = Comparator<Int> { i, j ->
            val c = comparator.compare(items[i], items[j])
            if (c != 0) c else i.compareTo(j)
        }

        val k = offset.toLong() + pageSize
        // 深翻页（k 覆盖列表末尾）：TopK 不再省工，退化为全量稳定排序。
        if (k >= size) {
            val sorted = (0 until size).sortedWith(stable)
            return sorted.subList(offset, size).map { items[it] }
        }

        val kInt = k.toInt()
        // 有界最大堆：堆顶是"已选 TopK 中最差的一个"。堆满后只接纳比堆顶更优的条目。
        // 比较反转（stable.reversed()）使 poll() 弹出当前最差元素。
        val worstFirst = stable.reversed()
        val heap = PriorityQueue(kInt, worstFirst)
        for (index in 0 until size) {
            if (heap.size < kInt) {
                heap.add(index)
            } else if (stable.compare(index, heap.peek()) < 0) {
                heap.poll()
                heap.add(index)
            }
        }

        // 堆中即排名最靠前的 k 个下标；升序整理后切出目标页。
        val topIndices = ArrayList<Int>(heap.size)
        topIndices.addAll(heap)
        topIndices.sortWith(stable)
        return topIndices.subList(offset, kInt).map { items[it] }
    }
}
