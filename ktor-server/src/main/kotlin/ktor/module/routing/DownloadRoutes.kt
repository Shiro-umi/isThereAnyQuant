package ktor.module.routing

import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.shiroumi.server.rootDir
import java.io.File

/**
 * APK 下载路由
 * 动态扫描 compose-app/build/outputs/apk 目录，优先返回 release APK，其次 debug APK
 */
fun Route.downloadRoutes() {
    get("/api/download/apk") {
        // 候选搜索目录：部署包内置优先，源码构建产物次之
        val candidateDirs = listOf(
            File("data/apk"),
            File(rootDir, "compose-app/build/outputs/apk"),
        )

        val allApks = candidateDirs
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList() }

        // 优先返回 release APK，其次 debug，最后取最新修改的
        val apkFile = allApks.firstOrNull { it.path.contains("release", ignoreCase = true) }
            ?: allApks.firstOrNull { it.path.contains("debug", ignoreCase = true) }
            ?: allApks.maxByOrNull { it.lastModified() }

        if (apkFile == null || !apkFile.exists()) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "APK 文件尚未构建"))
            return@get
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName,
                "quant-app.apk"
            ).toString()
        )
        call.respondFile(apkFile)
    }
}
