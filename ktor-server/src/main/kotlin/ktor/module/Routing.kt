package org.shiroumi.ktor.module

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import Task
//import Trading

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
//            Trading("fileName").initialize(Trading.Type.Backtesting)
        }
        get("/task_init") {
            try {
                Task.init()
                call.respond(mapOf("res" to "success"))
            } catch (e: Exception) {
                call.respond(mapOf("res" to "fail"))
            }
        }
        get("/task_list") {
//            try {
//                val jobs = Task.getActiveJobs()
//                call.respond(mapOf("res" to jobs.toString()))
//            } catch (e: Exception) {
//                call.respond(mapOf("res" to "fail"))
//            }
        }
        get("/task_standby") {
            try {
                Task.standby()
                call.respond(mapOf("res" to "success"))
            } catch (e: Exception) {
                call.respond(mapOf("res" to "fail"))
            }

        }
    }
}