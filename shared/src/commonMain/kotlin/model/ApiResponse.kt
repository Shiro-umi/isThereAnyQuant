package model

import kotlinx.serialization.Serializable

/**
 * 通用 API 响应包装类
 * 对应后端 ApiResponse<T> 结构
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val code: String,
    val message: String,
    val data: T? = null
)
