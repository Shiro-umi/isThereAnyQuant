package org.shiroumi.tools.industryresearchreports

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.shiroumi.network.SharedHttpClient

/**
 * 行业研报查询 CLI。
 *
 * 它和 `get-research-reports` 的区别只在查询主键：
 * - 个股研报按股票查
 * - 行业研报按 `ind_name` 查，并固定 report_type 为行业研报
 *
 * 工具本身仍只做参数透传和结果打印，不承担业务格式化。
 */
class GetIndustryResearchReports : CliktCommand(
    name = "get-industry-research-reports",
    help = "获取指定行业对应的券商行业研报（默认最近90天、默认20条、JSON格式）。通过 --ind-name 指定行业。"
) {

    private val indName by option("--ind-name", help = "行业名称（如 半导体、银行、人工智能）")
    private val startDate by option("--start-date", help = "开始日期（YYYYMMDD）")
    private val endDate by option("--end-date", help = "结束日期（YYYYMMDD）")
    private val tradeDate by option("--trade-date", help = "单日查询日期（YYYYMMDD）")
    private val inst by option("--inst", help = "券商机构简称")
    private val limit by option("--limit", "-l", help = "返回条数（默认20）").int().default(20)
    private val format by option("--format", help = "输出格式（当前仅支持 json）").default("json")

    override fun run() {
        if (indName.isNullOrBlank()) {
            echo("错误: 必须提供 --ind-name 参数", err = true)
            return
        }

        runBlocking {
            try {
                val response = SharedHttpClient.client.get(
                    SharedHttpClient.localServerUrl("/api/internal/cli/get-industry-research-reports")
                ) {
                    parameter("ind_name", indName)
                    startDate?.let { parameter("start_date", it) }
                    endDate?.let { parameter("end_date", it) }
                    tradeDate?.let { parameter("trade_date", it) }
                    inst?.let { parameter("inst", it) }
                    parameter("limit", limit)
                    parameter("format", format)
                }
                println(response.bodyAsText())
            } catch (e: Exception) {
                println("Error calling server: ${e.message}")
            }
        }
    }
}

fun main(args: Array<String>) = GetIndustryResearchReports().main(args)
