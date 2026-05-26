package ktor.module.routing

import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Route

fun Route.composeApp(route: String) = singlePageApplication {
    useResources = true
    applicationRoute = route
    filesPath = "static"
}

/**
 * 匿名分享页使用的独立静态资源（CSS / JS）。
 *
 * 放在 `src/main/resources/share-static/` 下，与前端 Compose 构建产物 `static/`
 * 互不污染，便于直接维护手写的 share 页样式与脚本。
 */
fun Route.shareStaticResources() {
    staticResources(remotePath = "/static/share", basePackage = "share-static")
}
