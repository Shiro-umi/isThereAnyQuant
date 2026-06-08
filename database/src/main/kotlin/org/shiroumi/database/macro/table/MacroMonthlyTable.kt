package org.shiroumi.database.macro.table

import org.jetbrains.exposed.v1.core.Table

/**
 * 宏观月频事实表（macro_monthly）—— factor topic「宏观—基本面抛压因子」线 M 的事实底座。
 *
 * 设计文档：private/research-docs/macro-fundamental-pressure-formula.html §6 / §7
 * 单主键 yearMonth（YYYYMM），承载三个宏观月频源对齐后的原始量：
 *  - 社融（sf_month）：当月增量 / 累计 / 存量
 *  - PMI（cn_pmi）：制造业 / 非制造业 / 综合
 *  - 利率（shibor，取月末值）：隔夜 / 3M / 1Y，期限利差 = 1Y − 隔夜 由研究层派生
 *
 * 仅存原始量，MAC1/MAC2/MAC3 顺逆度因子由 research 层在这些序列上推导（先聚合后推导口径同 VPM）。
 */
object MacroMonthlyTable : Table(name = "macro_monthly") {
    /** 年月 YYYYMM，如 202401 */
    val yearMonth = integer("year_month")

    // ── 社融 sf_month ──
    val sfIncMonth = double("sf_inc_month").nullable()    // 社融增量当月值（亿元）
    val sfIncCumval = double("sf_inc_cumval").nullable()  // 社融增量累计值（亿元）
    val sfStkEndval = double("sf_stk_endval").nullable()  // 社融存量期末值（万亿元）

    // ── PMI cn_pmi ──
    val pmiMfg = double("pmi_mfg").nullable()       // 制造业 PMI（pmi010000）
    val pmiNonMfg = double("pmi_non_mfg").nullable() // 非制造业商务活动（pmi020100）
    val pmiComposite = double("pmi_composite").nullable() // 综合 PMI 产出（pmi030000）

    // ── 利率 shibor（月末值）──
    val shiborOn = double("shibor_on").nullable()  // 隔夜
    val shibor3m = double("shibor_3m").nullable()  // 3 个月
    val shibor1y = double("shibor_1y").nullable()  // 1 年

    override val primaryKey = PrimaryKey(yearMonth, name = "pk_macro_monthly")
}
