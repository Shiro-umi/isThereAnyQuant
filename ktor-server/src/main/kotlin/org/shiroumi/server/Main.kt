package org.shiroumi.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import ktor.module.ktorRouting
import logger
import org.shiroumi.configs.BuildConfigs
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration


private val logger by logger("Processor")

private val json1 = Json { prettyPrint = true }


// vm entry
@OptIn(ExperimentalAtomicApi::class)
fun main(args: Array<String>) {
    runBlocking {
        embeddedServer(
            io.ktor.server.cio.CIO,
            port = BuildConfigs.PORT.toInt(),
            host = "0.0.0.0",
            module = Application::module
        ).start(wait = true)
    }
}

fun Application.module() {

    install(ContentNegotiation) {
        // 使用 kotlinx.serialization.json
        // 关键点：在这里显式指定使用 UTF-8 编码
        json(
            json = Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            },
            contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8)
        )
    }

    install(Compression) {
        gzip {
            priority = 1.0 // 设置 gzip 的优先级
            matchContentType(
                ContentType.Text.CSS,
                ContentType.Application.Wasm,
                ContentType.Application.JavaScript,
                ContentType.Text.Html,
                ContentType.Application.Json,
                ContentType.Image.SVG,
                ContentType.Font.Any
            )
        }
        deflate {
            priority = 0.1
        }
    }

    install(WebSockets) {
        pingPeriod = Duration.parse("15s")
        timeout = Duration.parse("15s")
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    ktorRouting()
}

val today: String
    get() = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

val rootDir: String = System.getProperty("user.dir")