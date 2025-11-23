package ktor.module.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import org.shiroumi.database.functioncalling.fetchDoneTask

fun Route.getStrategy(route: String) = get(route) {
    val uuid = call.queryParameters["uuid"] ?: return@get call.respond(
        HttpStatusCode.BadRequest,
        "task uuid can't be null or blank"
    )
    val res = fetchDoneTask(uuid)
    call.respond(
        HttpStatusCode.OK,
        res
    )
}