package ktor.module

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

fun Application.contentNegotiation() = install(plugin = ContentNegotiation) {
    json(
        json = Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        },
        contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8)
    )
}