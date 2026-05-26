package org.shiroumi.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.shiroumi.network.SharedHttpClient

class GetIntradayCommand : CliktCommand(
    name = "get-intraday-candles",
    help = "获取指定股票的小周期K线数据（60分钟/30分钟/15分钟/5分钟）。通过 --code 或 --name 指定股票。"
) {

    private val code by option("--code", "-c", help = "股票代码（如 000001 或 000001.SZ）")

    private val name by option("--name", "-n", help = "股票名称（如 平安银行、贵州茅台，精确匹配）")

    private val period by option("--period", "-p", help = "K线周期，支持: 60min, 30min, 15min, 5min（默认 60min）")
        .default("60min")

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
                    SharedHttpClient.localServerUrl("/api/internal/cli/get-intraday-candles")
                ) {
                    code?.let { parameter("code", it) }
                    name?.let { parameter("name", it) }
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
