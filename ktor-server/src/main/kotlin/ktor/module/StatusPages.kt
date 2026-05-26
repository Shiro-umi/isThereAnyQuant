package ktor.module

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.shiroumi.server.dto.ApiResponse
import utils.logger

private val logger by logger("StatusPages")

/**
 * 配置全局异常处理
 * 统一处理各类异常并返回标准化的 API 响应格式
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        // 处理 BadRequestException（参数错误）
        exception<BadRequestException> { call, cause ->
            logger.warning("BadRequest: ${cause.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Nothing>("INVALID_PARAMETER", cause.message ?: "Bad Request")
            )
        }

        // 处理 NotFoundException（资源未找到）
        exception<NotFoundException> { call, cause ->
            logger.warning("NotFound: ${cause.message}")
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse.error<Nothing>("RESOURCE_NOT_FOUND", cause.message ?: "Not Found")
            )
        }

        // 处理 IllegalArgumentException（参数非法）
        exception<IllegalArgumentException> { call, cause ->
            logger.warning("IllegalArgument: ${cause.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Nothing>("INVALID_PARAMETER", cause.message ?: "Invalid Argument")
            )
        }

        // 处理 NoSuchElementException（元素不存在）
        exception<NoSuchElementException> { call, cause ->
            logger.warning("NoSuchElement: ${cause.message}")
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse.error<Nothing>("RESOURCE_NOT_FOUND", cause.message ?: "Resource Not Found")
            )
        }

        // 处理所有其他未捕获的异常
        exception<Throwable> { call, cause ->
            logger.error("Internal Error: ${cause.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse.error<Nothing>("INTERNAL_ERROR", "Internal Server Error")
            )
        }
    }
}
