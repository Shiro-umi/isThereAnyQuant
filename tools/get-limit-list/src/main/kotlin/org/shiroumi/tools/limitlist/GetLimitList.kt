package org.shiroumi.tools.limitlist

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
 * 个股涨跌停与炸板查询 CLI。
 *
 * Agent 用它判断涨停强度、炸板次数、封单强度和连板状态。
 * 真实数据由 Ktor internal CLI 路由统一从 Tushare limit_list_d 获取。
 */
class GetLimitList : CliktCommand(
    name = "get-limit-list",
    help = "查询指定股票涨跌停、炸板和封板强度数据（Tushare limit_list_d，JSON格式）。"
) {
    private val code by option("--code", "-c", help = "股票代码（如 000001.SZ、600519.SH）")
    private val name by option("--name", "-n", help = "股票名称（如 平安银行、贵州茅台，精确匹配）")
    private val startDate by option("--start-date", help = "开始日期（YYYYMMDD）")
    private val endDate by option("--end-date", help = "结束日期（YYYYMMDD）")
    private val tradeDate by option("--trade-date", help = "单日查询日期（YYYYMMDD）")
    private val limitType by option("--limit-type", help = "涨跌停类型：U=涨停，D=跌停，Z=炸板")
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
                    SharedHttpClient.localServerUrl("/api/internal/cli/get-limit-list")
                ) {
                    code?.let { parameter("code", it) }
                    name?.let { parameter("name", it) }
                    startDate?.let { parameter("start_date", it) }
                    endDate?.let { parameter("end_date", it) }
                    tradeDate?.let { parameter("trade_date", it) }
                    limitType?.let { parameter("limit_type", it.uppercase()) }
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

fun main(args: Array<String>) = GetLimitList().main(args)
