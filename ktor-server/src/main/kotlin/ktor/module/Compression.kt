package ktor.module

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType

fun Application.compression() = install(plugin = Compression) {
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