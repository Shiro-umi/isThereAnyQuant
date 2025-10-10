package org.shiroumi.quant_kmp

// commonMain
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*

// 期望一个函数，它能返回一个配置好的 HttpClient
expect fun createHttpClient(): HttpClient

suspend fun HttpClient.connectSocket(onReceiveText: (text: String) -> Unit) {
//    val client = createHttpClient()
    println("Connecting to WebSocket server...")

    // 2. 使用 webSocket 块建立连接
    webSocket(
        method = HttpMethod.Get,
        host = BuildConfigs.BASE_URL,
        port = BuildConfigs.PORT.toInt(),
        path = "/tasks"
    ) {
        // 'this' 是一个 DefaultClientWebSocketSession 对象
        println("Connection established! Ready to communicate.")

        // 3. 发送消息给服务器
        // 例如，在连接成功后发送一条身份验证或初始化消息
        val initialMessage = "Hello from Ktor Client!"
        send(Frame.Text(initialMessage))
        println("Sent: '$initialMessage'")

        // 4. 持续接收来自服务器的消息
        // incoming 是一个 ReceiveChannel<Frame>
        // for-loop 会挂起，直到有新消息或连接关闭
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> {
                    val receivedText = frame.readText()
                    println("Received: '$receivedText'")
                    onReceiveText(receivedText)
                    // 在这里处理收到的文本消息
                    // 例如，如果服务器说 "close", 我们就主动断开
                    if (receivedText.equals("close", ignoreCase = true)) {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Client requested close"))
                        println("Closing connection...")
                    }
                }
                is Frame.Binary -> println("Received binary data: ${frame.readBytes().size} bytes")
                is Frame.Close -> {
                    println("Server closed the connection: ${frame.readReason()}")
                    break // 收到关闭帧后退出循环
                }
                is Frame.Ping -> { /* Ktor 内部会自动处理 Pong */ }
                is Frame.Pong -> { /* Ktor 内部会自动处理 Ping */ }
            }
        }
    }

    println("WebSocket session finished.")
    close() // 5. 关闭客户端，释放资源
}

//object Network {
//
//    private val ktorfit: Ktorfit by lazy {
//        val ktorClient = createHttpClient()
//
//        Ktorfit.Builder()
//            .baseUrl("https://127.0.0.1:9870/")
//            .httpClient(ktorClient)
//            .build()
//    }
//
//    val apiService: ApiService by lazy {
//        ktorfit.create<ApiService>()
//    }
//}
//interface ApiService {
////    suspend fun getPosts(): List<Post>
//
//    @GET("/tasks/submit")
//    suspend fun submitTask(@Query("ts_code") tsCode: String): String
//}