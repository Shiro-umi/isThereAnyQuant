package ktor.module.routing

import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.routing.Route

fun Route.composeApp(route: String) = singlePageApplication {
    useResources = true
    applicationRoute = route
    filesPath = "static"
}