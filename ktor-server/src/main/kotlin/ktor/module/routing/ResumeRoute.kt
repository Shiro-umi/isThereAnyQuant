package ktor.module.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

private const val ResumeHtmlPathProperty = "quant.resume.html"
private const val ResumeHtmlPathEnv = "QUANT_RESUME_HTML"
private const val ResumeRoutePath = "/resume"

/**
 * 默认读项目内的本地占位软链接（不进 git，见 .gitignore `.local/`）。
 * 软链接指向运行机上的真实简历文件，仓库里只保留这个相对路径，不泄露本地绝对路径。
 * 可用 system property `quant.resume.html` 或环境变量 `QUANT_RESUME_HTML` 覆盖（部署灵活）。
 */
private const val DefaultResumeHtmlPath = "ktor-server/.local/resume.html"

fun Route.resumeRoute() {
    get(ResumeRoutePath) {
        val htmlFile = resolveResumeHtmlFile()
        if (!htmlFile.isFile) {
            call.respondText(
                text = "Resume HTML not found: ${htmlFile.absolutePath}",
                status = HttpStatusCode.NotFound
            )
            return@get
        }

        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(
            text = htmlFile.readText(Charsets.UTF_8),
            contentType = ContentType.Text.Html.withCharset(Charsets.UTF_8),
            status = HttpStatusCode.OK
        )
    }
}

private fun resolveResumeHtmlFile(): File {
    val configuredPath = System.getProperty(ResumeHtmlPathProperty)
        ?.takeIf(String::isNotBlank)
        ?: System.getenv(ResumeHtmlPathEnv)?.takeIf(String::isNotBlank)

    return File(configuredPath ?: DefaultResumeHtmlPath).absoluteFile
}
