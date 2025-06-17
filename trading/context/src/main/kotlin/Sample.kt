// 默认周期30天，输入为shape为(stock_count, 30)
// 全部股票涨幅做归一化处理，涨跌幅全部映射到+—10%

data class StockDay(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)
typealias Matrix = List<List<StockDay>>

// 扩展函数定义
fun Double.round2(): Double {
    return "%.2f".format(this).toDouble()
}

/**
 * 连板数量
 * 分为2-7板和7板以上共8个维度
 *
 * 评分标准：
 * 一个2板票记2分以此类推 7板以上均记8分
 *
 * 计算方式：
 * 对每个维度计算总分，随后做归一化得到一个8维向量
 */

fun continuedUpLimit(input: Matrix): List<Int> {
    val mem = hashMapOf<Int, Int>()
    // 天数遍历
    (1 until 30).forEach { day ->
        input.forEachIndexed { stockIndex, stockDays ->
            val prevDayClose = stockDays[day - 1].close
            val todayClose = stockDays[day].close
            // 涨停计算 前一天收盘价*1.1保留两位小数
            // 如果当天涨停则count+1，否则清0
            mem[stockIndex] = if ((prevDayClose * 1.1).round2() == todayClose) {
                mem[stockIndex]?.plus(1) ?: 1
            } else 0
        }
    }
    // 统计全部2-7/7板以上并计算得分
    return mutableListOf(0, 0, 0, 0, 0, 0, 0).apply vector@{
        mem.forEach { (_, upLimitCount) ->
            if (upLimitCount in (2..7)) this@vector[upLimitCount] += 1
            else this@vector[this@vector.lastIndex] += 1
        }
        // 每个维度的计数与对应的记分相乘
        this@vector.mapIndexed { index, count -> count * (index + 2)}
    }
}

