package ktor.module

import io.ktor.server.application.*
import io.ktor.server.routing.*
import ktor.module.routing.*

fun Application.ktorRouting() {
    routing {
        composeApp(route = "/")
        submitAgentTask(route = "/tasks/submit")
        getStrategy(route = "/strategy")
        candleImg(route = "/candleImg")
        taskSubscribe(route = "/tasks")
    }
}