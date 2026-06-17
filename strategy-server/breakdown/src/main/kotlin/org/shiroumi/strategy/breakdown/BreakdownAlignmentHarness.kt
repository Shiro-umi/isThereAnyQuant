package org.shiroumi.strategy.breakdown

import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.math.abs
import kotlin.math.round

/**
 * 破位检测器数值对齐校验 harness（硬闸门）。
 *
 * 对齐基准（主控已导出，本 harness 直接读，不复算 Python）：
 *   - breakdown_ohlc/{code}.csv：与 Python build_candles 完全同源的连续有效 OHLC 序列
 *     （close>0 过滤 + trade_date 升序 + 去重）。Kotlin 直接读这份喂 scanBreakdownEvents，
 *     绕开 QFQ 源差异，纯算法逐根对齐。
 *   - breakdown_truth.csv (code,date,t,lvl,proto)：90 票逐根 Python 真值破位事件。
 *
 * 比对口径（零容差闸门）：
 *   - 事件集合（命中布尔，按 (code,t)）必须 100% 一致：Kotlin 多检出或漏检任一事件即失败。
 *   - lvl 绝对差 <= 1e-4（纯算法同源 OHLC，实际应可达 1e-6）。
 *   - 首个分歧精确到 (code,t,date)，供下一步定位口径环节。
 *
 * pass = eventSetMatch 且 lvlMaxDiff <= 1e-4。
 */
object BreakdownAlignmentHarness {

    private const val TEMP = "/Users/zhouzheng/Code/quant/temp"
    private const val OHLC_DIR = "$TEMP/breakdown_ohlc"
    private const val TRUTH_CSV = "$TEMP/breakdown_truth.csv"
    private const val LVL_TOL = 1e-4

    /** Python 真值单条事件：键 (code,t)，附 date 与 lvl。 */
    private data class TruthEvent(val code: String, val t: Int, val date: String, val lvl: Double)

    /** Kotlin 检出单条事件：键 (code,t)，附 date 与 lvl。 */
    private data class KotlinEvent(val code: String, val t: Int, val date: String, val lvl: Double)

    @JvmStatic
    fun main(args: Array<String>) {
        // ---- 1. 读 Python 真值，按 code 分组，键 (code,t) ----
        val truthByCode = LinkedHashMap<String, LinkedHashMap<Int, TruthEvent>>()
        var pythonTotal = 0
        File(TRUTH_CSV).useLines { lines ->
            var header = true
            for (line in lines) {
                if (header) { header = false; continue }
                if (line.isBlank()) continue
                val p = line.split(',')
                // code,date,t,lvl,proto
                val code = p[0]
                val date = p[1]
                val t = p[2].toInt()
                val lvl = p[3].toDouble()
                truthByCode.getOrPut(code) { LinkedHashMap() }[t] = TruthEvent(code, t, date, lvl)
                pythonTotal++
            }
        }

        // ---- 2. 逐票读同源 OHLC，跑 scanBreakdownEvents（按 t 收集） ----
        val ohlcFiles = File(OHLC_DIR).listFiles { f -> f.name.endsWith(".csv") }
            ?.sortedBy { it.name } ?: emptyList()

        var kotlinTotal = 0
        var lvlMaxDiff = 0.0
        var firstMismatch: String? = null
        var firstMismatchReason: String? = null

        // 全局首个分歧需按 (code 升序, t 升序) 稳定定位，因此按 code 字典序遍历，code 内按 t 升序比对。
        for (file in ohlcFiles) {
            val code = file.name.removeSuffix(".csv")
            val series = readSeries(file)
            // 逐根 t 调检测器，收集 (t -> (date, lvl))
            val kotlinEvents = LinkedHashMap<Int, KotlinEvent>()
            for (t in 0 until series.arr.n) {
                val ev = detectBreakdownZigzag(series.arr, t) ?: continue
                kotlinEvents[t] = KotlinEvent(code, t, series.dates[t].toString(), ev.lvl)
            }
            kotlinTotal += kotlinEvents.size

            val truth = truthByCode[code] ?: LinkedHashMap()

            // 命中布尔对齐：(code,t) 集合并集，逐 t 升序比对
            val allT = (kotlinEvents.keys + truth.keys).toSortedSet()
            for (t in allT) {
                val kHit = kotlinEvents.containsKey(t)
                val pHit = truth.containsKey(t)
                if (kHit && pHit) {
                    // 双方命中：比 lvl
                    val ke = kotlinEvents.getValue(t)
                    val pe = truth.getValue(t)
                    val diff = abs(ke.lvl - pe.lvl)
                    if (diff > lvlMaxDiff) lvlMaxDiff = diff
                    if (diff > LVL_TOL && firstMismatch == null) {
                        firstMismatch = "$code,t=$t,${ke.date}"
                        firstMismatchReason = "lvl 不一致: kotlin=${fmt(ke.lvl)} python=${fmt(pe.lvl)} 绝对差=${fmt(diff)}"
                    }
                } else if (kHit && !pHit) {
                    // Kotlin 多检出（假阳性）：事件集合不一致
                    if (firstMismatch == null) {
                        val ke = kotlinEvents.getValue(t)
                        firstMismatch = "$code,t=$t,${ke.date}"
                        firstMismatchReason = "Kotlin 多检出事件而 Python 无（命中布尔分歧，疑似 cross_below / trend_ok / clip 任一环节比 Python 宽松）"
                    }
                } else if (!kHit && pHit) {
                    // Kotlin 漏检：事件集合不一致
                    if (firstMismatch == null) {
                        val pe = truth.getValue(t)
                        firstMismatch = "$code,t=$t,${pe.date}"
                        firstMismatchReason = "Kotlin 漏检而 Python 有事件（命中布尔分歧，疑似某环节比 Python 严格或回归系数/序列下标错位）"
                    }
                }
            }
        }

        val eventSetMatch = (kotlinTotal == pythonTotal) && run {
            // 集合完全一致：逐票 (t 集合) 完全相等
            var allEqual = true
            for (file in ohlcFiles) {
                val code = file.name.removeSuffix(".csv")
                val series = readSeries(file)
                val kSet = LinkedHashSet<Int>()
                for (t in 0 until series.arr.n) {
                    if (detectBreakdownZigzag(series.arr, t) != null) kSet.add(t)
                }
                val pSet = (truthByCode[code] ?: emptyMap()).keys
                if (kSet != pSet) { allEqual = false; break }
            }
            allEqual
        }

        val pass = eventSetMatch && lvlMaxDiff <= LVL_TOL

        // ---- 3. 结构化输出（机器可读，供主控解析） ----
        println("==== BREAKDOWN ALIGNMENT RESULT ====")
        println("eventSetMatch=$eventSetMatch")
        println("lvlMaxDiff=${fmt(lvlMaxDiff)}")
        println("kotlinTotalEvents=$kotlinTotal")
        println("pythonTotalEvents=$pythonTotal")
        println("firstMismatch=${firstMismatch ?: "<none>"}")
        println("firstMismatchReason=${firstMismatchReason ?: "<none>"}")
        println("pass=$pass")
        println("====================================")
    }

    /**
     * 读同源 OHLC CSV (date,open,high,low,close)。已是连续有效升序去重序列，直接喂检测器。
     * close 全程 Double（与生产 close_qfq.toDouble() 同口径）。
     */
    private fun readSeries(file: File): BreakdownCandleSeries {
        val dates = ArrayList<LocalDate>()
        val open = ArrayList<Double>()
        val high = ArrayList<Double>()
        val low = ArrayList<Double>()
        val close = ArrayList<Double>()
        file.useLines { lines ->
            var header = true
            for (line in lines) {
                if (header) { header = false; continue }
                if (line.isBlank()) continue
                val p = line.split(',')
                // date,open,high,low,close
                dates.add(LocalDate.parse(p[0]))
                open.add(p[1].toDouble())
                high.add(p[2].toDouble())
                low.add(p[3].toDouble())
                close.add(p[4].toDouble())
            }
        }
        val arr = BreakdownArr(
            open = open.toDoubleArray(),
            high = high.toDoubleArray(),
            low = low.toDoubleArray(),
            close = close.toDoubleArray(),
        )
        return BreakdownCandleSeries(dates = dates, arr = arr)
    }

    /** 展示用定点格式（禁 String.format，手实现 8 位小数，项目惯例）。 */
    private fun fmt(v: Double): String {
        if (!v.isFinite()) return v.toString()
        val scale = 100_000_000.0 // 8 位
        val scaled = round(v * scale) / scale
        return scaled.toString()
    }
}
