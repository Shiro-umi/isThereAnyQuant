package org.shiroumi.strategy.breakdown

import java.io.File

/**
 * 破位加分窗口逻辑对齐校验 harness（临时校验，验证 BreakdownRerankService 的 searchsorted+[pos-2,pos] 窗口口径）。
 *
 * 复刻 BreakdownRerankService.brokeRecently 的核心：对每只票连续序列，逐 pos 判 [pos-LOOKBACK+1, pos]
 * 窗内是否有 detectBreakdownZigzag 命中。与 Python breakdown_rerank_ref.csv（probe recently_broke 同口径）
 * 逐 (code,pos) 比对 flag，零分歧即过。
 */
object BreakdownRerankAlignmentHarness {

    private const val TEMP = "/Users/zhouzheng/Code/quant/temp"
    private const val OHLC_DIR = "$TEMP/breakdown_ohlc"
    private const val REF_CSV = "$TEMP/breakdown_rerank_ref.csv"
    private const val LOOKBACK = 3

    @JvmStatic
    fun main(args: Array<String>) {
        // 读 Python 参考 flag: (code,pos) -> flag
        val ref = HashMap<String, HashMap<Int, Int>>()
        File(REF_CSV).useLines { lines ->
            var header = true
            for (line in lines) {
                if (header) { header = false; continue }
                if (line.isBlank()) continue
                val p = line.split(',')
                ref.getOrPut(p[0]) { HashMap() }[p[1].toInt()] = p[2].toInt()
            }
        }

        val files = File(OHLC_DIR).listFiles { f -> f.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()
        var total = 0
        var mismatch = 0
        var firstMismatch: String? = null
        for (file in files) {
            val code = file.name.removeSuffix(".csv")
            val arr = readArr(file)
            // 逐根预扫破位命中下标集合
            val hits = HashSet<Int>()
            for (t in 0 until arr.n) if (detectBreakdownZigzag(arr, t) != null) hits.add(t)
            val refCode = ref[code] ?: emptyMap()
            for (pos in 0 until arr.n) {
                val lo = maxOf(0, pos - LOOKBACK + 1)
                var kFlag = 0
                for (t in lo..pos) if (t in hits) { kFlag = 1; break }
                val pFlag = refCode[pos] ?: -1
                total++
                if (kFlag != pFlag) {
                    mismatch++
                    if (firstMismatch == null) firstMismatch = "$code,pos=$pos,kotlin=$kFlag,python=$pFlag"
                }
            }
        }
        println("==== BREAKDOWN RERANK ALIGNMENT ====")
        println("rows=$total mismatch=$mismatch firstMismatch=${firstMismatch ?: "<none>"}")
        println("pass=${mismatch == 0}")
        println("====================================")
    }

    private fun readArr(file: File): BreakdownArr {
        val open = ArrayList<Double>(); val high = ArrayList<Double>()
        val low = ArrayList<Double>(); val close = ArrayList<Double>()
        file.useLines { lines ->
            var header = true
            for (line in lines) {
                if (header) { header = false; continue }
                if (line.isBlank()) continue
                val p = line.split(',')
                open.add(p[1].toDouble()); high.add(p[2].toDouble())
                low.add(p[3].toDouble()); close.add(p[4].toDouble())
            }
        }
        return BreakdownArr(open.toDoubleArray(), high.toDoubleArray(), low.toDoubleArray(), close.toDoubleArray())
    }
}
