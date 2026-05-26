package org.shiroumi.quant_kmp.core.network

/**
 * 网络错误类型密封类
 * 定义所有可能的网络错误场景
 */
sealed class NetworkError {
    /**
     * HTTP 错误（4xx, 5xx 等）
     */
    data class HttpError(val code: Int, val message: String) : NetworkError()

    /**
     * 网络异常（连接失败、超时等）
     */
    data class NetworkException(val cause: Throwable) : NetworkError()

    /**
     * 序列化错误（JSON 解析失败等）
     */
    data class SerializationError(val cause: Throwable) : NetworkError()

    /**
     * 服务器业务错误
     */
    data class ServerError(val message: String) : NetworkError()

    /**
     * 未知错误
     */
    data object Unknown : NetworkError()
}

/**
 * 将 NetworkError 转换为用户友好的错误消息
 */
fun NetworkError.toUserMessage(): String = when (this) {
    is NetworkError.HttpError -> when (code) {
        400 -> "请求参数错误"
        401 -> "未授权，请重新登录"
        403 -> "访问被拒绝"
        404 -> "请求的资源不存在"
        408 -> "请求超时，请重试"
        429 -> "请求过于频繁，请稍后再试"
        in 500..599 -> "服务器繁忙，请稍后再试"
        else -> "网络请求失败 ($code)"
    }

    is NetworkError.NetworkException -> {
        val message = cause.message ?: ""
        when {
            message.contains("UnknownHost", ignoreCase = true) ||
            message.contains("ConnectException", ignoreCase = true) -> "网络连接失败，请检查网络设置"
            message.contains("SocketTimeout", ignoreCase = true) ||
            message.contains("Timeout", ignoreCase = true) -> "连接超时，请检查网络后重试"
            message.contains("IOException", ignoreCase = true) -> "网络异常，请稍后重试"
            else -> "网络连接异常"
        }
    }

    is NetworkError.SerializationError -> "数据解析失败，请稍后重试"
    is NetworkError.ServerError -> message
    NetworkError.Unknown -> "发生未知错误，请稍后重试"
}

/**
 * 将 NetworkError 转换为技术日志消息
 */
fun NetworkError.toLogMessage(): String = when (this) {
    is NetworkError.HttpError -> "HTTP Error: $code - $message"
    is NetworkError.NetworkException -> "Network Exception: ${cause.message ?: cause::class.simpleName}"
    is NetworkError.SerializationError -> "Serialization Error: ${cause.message ?: cause::class.simpleName}"
    is NetworkError.ServerError -> "Server Error: $message"
    NetworkError.Unknown -> "Unknown Network Error"
}
