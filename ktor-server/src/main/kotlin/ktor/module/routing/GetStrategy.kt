package ktor.module.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.shiroumi.database.functioncalling.fetchDoneTask

fun Route.getStrategy(route: String) =  get(route) {
    val uuid = call.queryParameters["uuid"] ?: return@get call.respond(
        HttpStatusCode.BadRequest,
        "task uuid can't be null or blank"
    )
    call.respond(
        HttpStatusCode.OK,
        fetchDoneTask(uuid)
    )
}