package org.shiroumi.strategy.service

import kotlinx.coroutines.runBlocking
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.common.repository.StockBasicRepository
import utils.logger
import java.io.File

/**
 * 导出 ST 股票名单 CSV,供研究层数据装配剔除 ST(±5% 涨跌幅,非交易标的,标签结构性负例噪声)。
 *
 * 口径:stock_basic.name 含 "ST"(含 *ST / S*ST / ST)即判定为 ST。
 * 已知近似:name 是当前快照(非逐日 ST 状态),戴帽/摘帽历史不可得。ST 状态相对持久
 * (通常 ≥1 年),回测区间不到 2 年,当前名单能剔掉绝大多数 ST 样本。与生产选股
 * delisted-filter 的 name 兜底口径一致。
 *
 * 输出: temp/st_names.csv,列 ts_code,name。研究装配脚本读它构造剔除集合。
 *
 * 运行: ./gradlew :strategy-server:service:exportStNames
 */
private val stLogger by logger("ExportStNames")

fun main() = runBlocking {
    Class.forName("com.mysql.cj.jdbc.Driver")
    ConfigManager.load()

    val out = System.getProperty("quant.st.out", "temp/st_names.csv")
    val profiles = StockBasicRepository.findProfiles()
    val st = profiles.filter { it.name.contains("ST", ignoreCase = true) }

    File(out).bufferedWriter().use { w ->
        w.write("ts_code,name\n")
        for (p in st.sortedBy { it.tsCode }) {
            w.write("${p.tsCode},${p.name}\n")
        }
    }
    stLogger.info("[export-st] 全市场 ${profiles.size} 只,其中 ST ${st.size} 只 -> $out")
    println("[export-st] ST ${st.size} / 全市场 ${profiles.size} -> $out")
}
