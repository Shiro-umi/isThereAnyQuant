package org.shiroumi.tools.researchreports

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
 * 券商研报查询 CLI。
 *
 * 设计边界和现有 `get-candles` 保持一致：
 * - 只负责参数解析
 * - 只负责调用本机 Ktor internal CLI 路由
 * - 只负责原样输出服务端返回文本
 *
 * 这样 agent 只需要记住一个稳定的本地命令，
 * 无需接触 Tushare 直接调用、鉴权和默认窗口逻辑。
 */
class GetResearchReports : CliktCommand(
    name = "get-research-reports",
    help = "获取指定股票对应的券商研究报告（默认最近90天、默认20条、JSON格式）。通过 --code 或 --name 指定股票。"
) {

    private val code by option("--code", "-c", help = "股票代码（如 000001 或 000001.SZ）")
    private val name by option("--name", "-n", help = "股票名称（如 平安银行、贵州茅台，精确匹配）")
    private val startDate by option("--start-date", help = "开始日期（YYYYMMDD）")
    private val endDate by option("--end-date", help = "结束日期（YYYYMMDD）")
    private val tradeDate by option("--trade-date", help = "单日查询日期（YYYYMMDD）")
    private val reportType by option("--report-type", help = "研报类型（如 个股研报）")
    private val inst by option("--inst", help = "券商机构简称")
    private val limit by option("--limit", "-l", help = "返回条数（默认20）").int().default(20)
    private val format by option("--format", help = "输出格式（当前仅支持 json）").default("json")

    override fun run() {
        if (code.isNullOrBlank() && name.isNullOrBlank()) {
            echo("错误: 必须提供 --code 或 --name 参数之一", err = true)
            return
        }

        runBlocking {
            try {
                val response = SharedHttpClient.client.get(
                    SharedHttpClient.localServerUrl("/api/internal/cli/get-research-reports")
                ) {
                    code?.let { parameter("code", it) }
                    name?.let { parameter("name", it) }
                    startDate?.let { parameter("start_date", it) }
                    endDate?.let { parameter("end_date", it) }
                    tradeDate?.let { parameter("trade_date", it) }
                    reportType?.let { parameter("report_type", it) }
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

fun main(args: Array<String>) = GetResearchReports().main(args)
