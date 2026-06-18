package org.shiroumi.tools.limitlistasof

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.shiroumi.network.SharedHttpClient

/**
 * 历史涨跌停与炸板查询 CLI（回测专用）。
 *
 * 新增必填 --as-of：宿主在 wrapper 中写死注入，agent 无感知。
 * 服务端把 end_date 强制收敛到 as_of（end_date <= as_of），绝不返回信号日之后的涨跌停记录。
 */
class GetLimitListAsof : CliktCommand(
    name = "get-limit-list-asof",
    help = "查询指定股票截止信号日(as-of)的历史涨跌停、炸板和封板强度数据（Tushare limit_list_d，JSON格式）。"
) {
    private val code by option("--code", "-c", help = "股票代码（如 000001.SZ、600519.SH）")
    private val name by option("--name", "-n", help = "股票名称（如 平安银行、贵州茅台，精确匹配）")
    private val asOf by option("--as-of", help = "信号日（YYYYMMDD），end_date 收敛到此日，不返回之后任何记录")
        .required()
    private val startDate by option("--start-date", help = "开始日期（YYYYMMDD），晚于 as_of 时自动收敛到 as_of")
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
                    SharedHttpClient.localServerUrl("/api/internal/cli/get-limit-list-asof")
                ) {
                    code?.let { parameter("code", it) }
                    name?.let { parameter("name", it) }
                    parameter("as_of", asOf)
                    startDate?.let { parameter("start_date", it) }
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

fun main(args: Array<String>) = GetLimitListAsof().main(args)
