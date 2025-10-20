package ktor.module

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration

fun Application.websockets() = install(plugin = WebSockets) {
    pingPeriod = Duration.parse("15s")
    timeout = Duration.parse("15s")
    maxFrameSize = Long.MAX_VALUE
    masking = false
}