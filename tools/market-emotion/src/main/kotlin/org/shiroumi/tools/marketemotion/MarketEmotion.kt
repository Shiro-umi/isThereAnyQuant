package org.shiroumi.tools.marketemotion

import com.github.ajalt.clikt.core.CliktCommand
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.shiroumi.network.SharedHttpClient

class MarketEmotion : CliktCommand(
    name = "market-emotion",
    help = "获取市场情绪参数"
) {

    override fun run() {
        runBlocking {
            try {
                val response = SharedHttpClient.client.get(
                    SharedHttpClient.localServerUrl("/api/internal/cli/get-emotion")
                )
                println(response.bodyAsText())
            } catch (e: Exception) {
                println("Error calling server: ${e.message}")
            }
        }
    }
}

fun main(args: Array<String>) = MarketEmotion().main(args)
