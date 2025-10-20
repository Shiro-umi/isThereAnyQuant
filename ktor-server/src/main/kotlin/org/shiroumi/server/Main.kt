package org.shiroumi.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import ktor.module.compression
import ktor.module.contentNegotiation
import ktor.module.ktorRouting
import ktor.module.websockets
import org.shiroumi.configs.BuildConfigs
import utils.logger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.concurrent.atomics.ExperimentalAtomicApi


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
    contentNegotiation()
    compression()
    websockets()
    ktorRouting()
}

val today: String
    get() = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

val rootDir: String = System.getProperty("user.dir")