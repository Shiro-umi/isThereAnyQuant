package org.shiroumi.server

import kotlinx.coroutines.runBlocking
import org.shiroumi.config.ConfigManager
import org.shiroumi.database.macro.MacroMonthlyRecord
import org.shiroumi.database.macro.MacroMonthlyRepository
import org.shiroumi.network.apis.TushareForm
import org.shiroumi.network.apis.getPmi
import org.shiroumi.network.apis.getShibor
import org.shiroumi.network.apis.getSocialFinanceMonthly
import org.shiroumi.network.apis.tushare

/**
 * 宏观月频数据落库（macro_monthly）—— 社融 + PMI + 利率 三源对齐。
 *
 * 设计文档：private/research-docs/macro-fundamental-pressure-formula.html §6/§7（H2 最小验证数据集）
 * 利率为日频，按 YYYYMM 取**月末值**对齐到月频。三源按 yearMonth upsert 合并补齐。
 *
 * 运行：./gradlew :ktor-server:syncMacroMonthly -Dquant.macro.startM=200801 -Dquant.macro.endM=202612
 */
fun main() = runBlocking {
    ConfigManager.load()
    val startM = System.getProperty("quant.macro.startM")?.trim()?.takeIf { it.isNotEmpty() } ?: "200801"
    val endM = System.getProperty("quant.macro.endM")?.trim()?.takeIf { it.isNotEmpty() } ?: "202612"
    println("# 宏观月频落库 macro_monthly  区间 $startM ~ $endM")

    // 月度 record 累积器（按 yearMonth 合并）
    val byYm = linkedMapOf<Int, MacroMonthlyRecord>()
    fun merge(ym: Int, patch: (MacroMonthlyRecord) -> MacroMonthlyRecord) {
        byYm[ym] = patch(byYm[ym] ?: MacroMonthlyRecord(yearMonth = ym))
    }

    // ── 社融 ──
    val sf = tushare.getSocialFinanceMonthly(startM = startM, endM = endM).check()
    sf?.let { form ->
        rows(form).forEach { r ->
            val ym = r["month"]?.toIntOrNull() ?: return@forEach
            merge(ym) { it.copy(
                sfIncMonth = r["inc_month"]?.toDoubleOrNull(),
                sfIncCumval = r["inc_cumval"]?.toDoubleOrNull(),
                sfStkEndval = r["stk_endval"]?.toDoubleOrNull(),
            ) }
        }
    }
    println("- 社融 sf_month: ${sf?.items?.size ?: 0} 行")

    // ── PMI ──
    val pmi = tushare.getPmi(startM = startM, endM = endM).check()
    pmi?.let { form ->
        rows(form).forEach { r ->
            val ym = r["month"]?.toIntOrNull() ?: return@forEach
            merge(ym) { it.copy(
                pmiMfg = r["pmi010000"]?.toDoubleOrNull(),
                pmiNonMfg = r["pmi020100"]?.toDoubleOrNull(),
                pmiComposite = r["pmi030000"]?.toDoubleOrNull(),
            ) }
        }
    }
    println("- PMI cn_pmi: ${pmi?.items?.size ?: 0} 行")

    // ── 利率 shibor（日频 → 月末值）──
    // shibor 单次 ≤2000 条，约 8 年日频，分段按年拉以稳妥。
    val startYear = startM.substring(0, 4).toInt()
    val endYear = endM.substring(0, 4).toInt()
    val monthlyLastShibor = linkedMapOf<Int, Triple<Double?, Double?, Double?>>() // ym -> (on,3m,1y) 月末值
    for (y in startYear..endYear) {
        val form = tushare.getShibor(startDate = "${y}0101", endDate = "${y}1231").check() ?: continue
        rows(form).forEach { r ->
            val date = r["date"] ?: return@forEach           // YYYYMMDD
            val ym = date.substring(0, 6).toIntOrNull() ?: return@forEach
            // 取该月最大日期（月末）的值；rows 按日期升序，后写覆盖前写即为月末
            monthlyLastShibor[ym] = Triple(
                r["on"]?.toDoubleOrNull(),
                r["3m"]?.toDoubleOrNull(),
                r["1y"]?.toDoubleOrNull(),
            )
        }
    }
    monthlyLastShibor.forEach { (ym, t) ->
        merge(ym) { it.copy(shiborOn = t.first, shibor3m = t.second, shibor1y = t.third) }
    }
    println("- 利率 shibor: ${monthlyLastShibor.size} 个月（月末值）")

    // ── 落库 ──
    val records = byYm.values.sortedBy { it.yearMonth }
    MacroMonthlyRepository.upsert(records)
    val total = MacroMonthlyRepository.count()
    println("- upsert ${records.size} 月  → macro_monthly 现有 $total 行")

    // ── 回读自测 ──
    val readback = MacroMonthlyRepository.findBetween(startM.toInt(), endM.toInt())
    val withSf = readback.count { it.sfIncMonth != null }
    val withPmi = readback.count { it.pmiMfg != null }
    val withRate = readback.count { it.shibor1y != null }
    println("- 回读 ${readback.size} 月：社融非空 $withSf / PMI非空 $withPmi / 利率非空 $withRate")
    readback.takeLast(3).forEach {
        val y1 = it.shibor1y
        val on = it.shiborOn
        val spread = if (y1 != null && on != null)
            (kotlin.math.round((y1 - on) * 1000) / 1000.0).toString() else "—"
        println("  ${it.yearMonth}: 社融增量=${it.sfIncMonth} 制造业PMI=${it.pmiMfg} 1Y利率=${it.shibor1y} 期限利差(1Y-ON)=$spread")
    }
    println("# 落库完成")
}

private fun rows(form: TushareForm): List<Map<String, String>> =
    form.items.map { item -> form.fields.zip(item).associate { (k, v) -> k to (v ?: "") } }
