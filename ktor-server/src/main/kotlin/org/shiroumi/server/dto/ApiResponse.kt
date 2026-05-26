package org.shiroumi.server.dto

import kotlinx.serialization.Serializable

/**
 * 通用 API 响应包装类
 * 所有 API 响应都使用此格式统一封装
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val code: String,
    val message: String,
    val data: T? = null
) {
    companion object {
        /**
         * 创建成功响应
         */
        fun <T> success(data: T, message: String = "Success"): ApiResponse<T> =
            ApiResponse(
                success = true,
                code = "SUCCESS",
                message = message,
                data = data
            )

        /**
         * 创建错误响应
         */
        fun <T> error(code: String, message: String, data: T? = null): ApiResponse<T> =
            ApiResponse(
                success = false,
                code = code,
                message = message,
                data = data
            )
    }
}
