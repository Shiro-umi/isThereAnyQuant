package org.shiroumi.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.shiroumi.network.SharedHttpClient

class EmotionCommand : CliktCommand(name = "get-emotion", help = "Fetch emotion parameters from local Server") {

    override fun run() {
        // Run network call inside coroutine context
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
