package org.shiroumi.ktor.module

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.shiroumi.trading.BackTesting

fun Application.ktorRouting() {
    install(ContentNegotiation) {
        json()
    }
    routing {
        post("/test") {
            call.respond(mapOf("res" to "res"))
        }
        post("/backtest") {
            println("request: /backtest")
            BackTesting("fileName").initialize()
        }
    }
}