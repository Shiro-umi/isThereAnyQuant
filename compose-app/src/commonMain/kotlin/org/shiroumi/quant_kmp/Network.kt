package org.shiroumi.quant_kmp

// commonMain
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.shiroumi.configs.BuildConfigs
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// 期望一个函数，它能返回一个配置好的 HttpClient
expect fun createHttpClient(): HttpClient


class SocketClient {

    private val client: HttpClient by lazy {
        createHttpClient()
    }

    private var session: WebSocketSession? = null

    suspend fun open(onConnected: () -> Unit = {}, onReceiveText: (text: String) -> Unit) {
        val session = client.webSocketSession(
            method = HttpMethod.Get,
            host = BuildConfigs.BASE_URL.replace("http://", ""),
            port = BuildConfigs.PORT.toInt(),
            path = "/tasks",
        )
        this.session = session
        onConnected()
        println("Connection established! Ready to communicate.")
        val initialMessage = "Hello from Ktor Client!"
        session.send(Frame.Text(initialMessage))
        println("Sent: '$initialMessage'")

        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> {
                    val receivedText = frame.readText()
//                    println("Received: '$receivedText'")
                    onReceiveText(receivedText)
                    if (receivedText.equals("close", ignoreCase = true)) {
                        session.close(CloseReason(CloseReason.Codes.NORMAL, "Client requested close"))
                        println("Closing connection...")
                    }
                }

                is Frame.Binary -> println("Received binary data: ${frame.readBytes().size} bytes")
                is Frame.Close -> {
                    println("Server closed the connection: ${frame.readReason()}")
                    break
                }

                is Frame.Ping -> Unit
                is Frame.Pong -> Unit
            }
        }
    }

    suspend fun close() {
        session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client requested close"))
        session = null
    }

}