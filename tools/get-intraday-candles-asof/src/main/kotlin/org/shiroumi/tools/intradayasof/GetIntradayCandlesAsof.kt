package org.shiroumi.tools.intradayasof

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
 * 历史小周期K线取数 CLI（回测专用）。
 *
 * 新增必填 --as-of：宿主在 wrapper 中写死注入，agent 无感知。
 * 服务端按信号日收盘截断历史分钟表（trade_time <= "{as_of} 15:00:00"），绝不读 live 快照。
 */
class GetIntradayCandlesAsof : CliktCommand(
    name = "get-intraday-candles-asof",
    help = "获取指定股票截止信号日(as-of)收盘的历史小周期K线数据（60分钟/30分钟/15分钟/5分钟）。通过 --code 或 --name 指定股票。"
) {

    private val code by option("--code", "-c", help = "股票代码（如 000001 或 000001.SZ）")

    private val name by option("--name", "-n", help = "股票名称（如 平安银行、贵州茅台，精确匹配）")

    private val asOf by option("--as-of", help = "信号日（YYYYMMDD），分钟线截到信号日收盘，不返回之后任何数据")
        .required()

    private val period by option("--period", "-p", help = "K线周期，支持: 60min, 30min, 15min, 5min（默认 15min）")
        .default("15min")

    private val limit by option("--limit", "-l", help = "获取数据条数（默认 100）")
        .int()
        .default(100)

    override fun run() {
        if (code.isNullOrBlank() && name.isNullOrBlank()) {
            echo("错误: 必须提供 --code 或 --name 参数之一", err = true)
            return
        }

        runBlocking {
            try {
                val response = SharedHttpClient.client.get(
                    SharedHttpClient.localServerUrl("/api/internal/cli/get-intraday-candles-asof")
                ) {
                    code?.let { parameter("code", it) }
                    name?.let { parameter("name", it) }
                    parameter("as_of", asOf)
                    parameter("period", period)
                    parameter("limit", limit)
                }
                println(response.bodyAsText())
            } catch (e: Exception) {
                println("Error calling server: ${e.message}")
            }
        }
    }
}

fun main(args: Array<String>) = GetIntradayCandlesAsof().main(args)
