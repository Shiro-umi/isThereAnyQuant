package ktor.module.routing

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.Json
import ktor.auth.TokenVerificationResult
import ktor.module.jwtService
import model.ws.WsCommand
import org.shiroumi.server.websocket.AppWebSocketConnectionManager
import utils.logger
import java.util.UUID

private val logger by logger("AppStreamRoute")
private val json = Json { ignoreUnknownKeys = true }

/**
 * 全局 WebSocket 多路复用入口路由
 *
 * 作用：替代以前分散的各种 WebSocket 端点（/ws/data-update, /ws/task/{uuid}, /ws/strategy/{taskId}）。
 * App 在启动时通过单例建立此连接，所有后台向前端的主动推送，以及前端的动态订阅指令均通过此路由传输。
 */
fun Route.appStreamWebSocket() {
    webSocket("/ws/app-stream") {
        logger.info("New client trying to connect to multiplexing channel: /ws/app-stream")
        
        val token = call.request.queryParameters["token"]
        if (token.isNullOrEmpty()) {
            logger.warning("WebSocket connection rejected: No token provided")
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }

        val jwtService = call.application.jwtService
        val verificationResult = jwtService.verifyAccessToken(token)
        if (verificationResult !is TokenVerificationResult.Valid) {
            logger.warning("WebSocket connection rejected: Invalid token")
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }

        val userId = jwtService.extractUserId(verificationResult.decodedJWT)
        val username = jwtService.extractUsername(verificationResult.decodedJWT)
        if (userId == null || username == null) {
            logger.warning("WebSocket connection rejected: Missing userId or username in token")
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid token payload"))
            return@webSocket
        }

        logger.info("User $username ($userId) connected to multiplexing channel: /ws/app-stream")

        // 注册全局连接
        AppWebSocketConnectionManager.addConnection(this, userId)

        try {
            // 保持连接并处理客户端发送的控制指令
            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    try {
                        // 解析客户端订阅或取消订阅的指令
                        val command = json.decodeFromString<WsCommand>(text)
                        AppWebSocketConnectionManager.handleCommand(this, command)
                    } catch (e: Exception) {
                        logger.warning("Failed to parse incoming WsCommand: $text, error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("WebSocket multiplexing connection error: ${e.message}")
        } finally {
            // 客户端断开连接时，自动清理其所有的订阅关系
            AppWebSocketConnectionManager.removeConnection(this)
            logger.info("Client disconnected from multiplexing channel: /ws/app-stream")
        }
    }
}
