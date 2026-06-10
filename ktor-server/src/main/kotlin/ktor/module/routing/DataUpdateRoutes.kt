package ktor.module.routing

import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.shiroumi.server.service.DataUpdateService
import utils.logger

private val logger by logger("DataUpdateRoutes")

@Serializable
private data class TriggerUpdateResponse(
    val success: Boolean,
    val message: String,
)

/**
 * 数据更新相关路由
 */
fun Route.dataUpdateRoutes() {

    /**
     * REST API: 获取当前数据更新状态
     */
    get("/api/data/status") {
        val status = DataUpdateService.getCurrentStatus()
        call.respond(mapOf("status" to status))
    }
    
    /**
     * REST API: 手动触发数据更新（仅管理员）
     */
    post("/api/data/trigger") {
        // TODO: 添加管理员权限校验

        val success = DataUpdateService.triggerUpdate()
        call.respond(
            TriggerUpdateResponse(
                success = success,
                message = if (success) "数据更新已启动" else "数据更新正在进行中"
            )
        )
    }
}
