package org.shiroumi.tools.getcandlesasof

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
 * 历史日K线取数 CLI（回测专用）。
 *
 * 对外工具名仍是 get-candles 的"历史版"，只新增必填 --as-of：宿主在 wrapper 中写死注入，
 * agent 无感知。服务端按 as_of 截断历史表（trade_date <= as_of），绝不读 live 快照。
 */
class GetCandlesAsof : CliktCommand(
    name = "get-candles-asof",
    help = "获取指定股票截止信号日(as-of)的历史日K线数据（前复权，Markdown表格格式）。通过 --code 或 --name 指定股票。"
) {

    private val code by option("--code", "-c", help = "股票代码（如 000001 或 000001.SZ）")

    private val name by option("--name", "-n", help = "股票名称（如 平安银行、茅台）")

    private val asOf by option("--as-of", help = "信号日（YYYYMMDD），按此日期截断历史数据，不返回之后任何数据")
        .required()

    private val limit by option("--limit", "-l", help = "获取数据天数（默认60）")
        .int()
        .default(60)

    override fun run() {
        if (code.isNullOrBlank() && name.isNullOrBlank()) {
            echo("错误: 必须提供 --code 或 --name 参数之一", err = true)
            return
        }

        runBlocking {
            try {
                val response = SharedHttpClient.client.get(
                    SharedHttpClient.localServerUrl("/api/internal/cli/get-candles-asof")
                ) {
                    code?.let { parameter("code", it) }
                    name?.let { parameter("name", it) }
                    parameter("as_of", asOf)
                    parameter("limit", limit)
                }
                println(response.bodyAsText())
            } catch (e: Exception) {
                println("Error calling server: ${e.message}")
            }
        }
    }
}

fun main(args: Array<String>) = GetCandlesAsof().main(args)
