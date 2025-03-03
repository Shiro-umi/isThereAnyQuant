package org.shiroumi.ktor.module

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.ktorRouting() {
    install(ContentNegotiation) {
        json()
    }
    routing {
        post("/test") {
//            val res = BackTesting.test(a = 1, b = 5, port = (6000 until 6500).random())
            call.respond(mapOf("res" to "res"))
        }
    }
}