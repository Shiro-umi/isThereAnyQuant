package ktor.module

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.websocket.WebSocketDeflateExtension
import java.util.zip.Deflater
import kotlin.time.Duration

private const val MAX_APP_STREAM_FRAME_BYTES = 1L * 1024 * 1024
// 小于该阈值的小数据帧（SUBSCRIBE ACK、DATA_UPDATE 通知等）跳过压缩，
// 避免压缩开销大于压缩节省。控制帧（Ping/Pong/Close）由 WebSocket 协议本身保证不被压缩。
private const val COMPRESS_MIN_FRAME_BYTES = 4 * 1024

fun Application.websockets() = install(plugin = WebSockets) {
    pingPeriod = Duration.parse("15s")
    timeout = Duration.parse("45s")
    maxFrameSize = MAX_APP_STREAM_FRAME_BYTES
    masking = false
    // permessage-deflate (RFC 7692)。客户端不声明此扩展时握手自动 fallback 到无压缩，
    // 因此 wasmJs 浏览器原生 WebSocket 与 Android Ktor client 各自决定是否启用，不需要服务端做模式判别。
    extensions {
        install(WebSocketDeflateExtension) {
            compressionLevel = Deflater.DEFAULT_COMPRESSION
            compressIfBiggerThan(COMPRESS_MIN_FRAME_BYTES)
        }
    }
}
