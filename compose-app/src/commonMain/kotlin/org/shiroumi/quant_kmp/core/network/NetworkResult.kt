package org.shiroumi.quant_kmp.core.network

/**
 * 网络请求结果类型别名
 * 使用 Kotlin 标准库 Result 包装网络请求结果
 */
typealias NetworkResult<T> = Result<T>

/**
 * 处理网络错误的扩展函数
 * 当 Result 为失败状态时，执行指定操作
 *
 * @param action 处理 NetworkError 的回调
 * @return 返回原始的 Result 以支持链式调用
 */
inline fun <T> NetworkResult<T>.onNetworkError(
    action: (NetworkError) -> Unit
): NetworkResult<T> {
    exceptionOrNull()?.let { throwable ->
        action(throwable.toNetworkError())
    }
    return this
}

/**
 * 将 Throwable 转换为 NetworkError
 */
fun Throwable.toNetworkError(): NetworkError = when (this) {
    is NetworkErrorException -> this.error
    else -> NetworkError.NetworkException(this)
}

/**
 * 用于包装 NetworkError 的异常类
 * 便于在协程和 Result 中传递
 */
class NetworkErrorException(val error: NetworkError) : Exception(error.toLogMessage())

/**
 * 创建成功的 NetworkResult
 */
fun <T> networkSuccess(value: T): NetworkResult<T> =
    Result.success(value)

/**
 * 创建失败的 NetworkResult
 */
fun <T> networkFailure(error: NetworkError): NetworkResult<T> =
    Result.failure(NetworkErrorException(error))

/**
 * 获取 NetworkResult 中的 NetworkError（如果存在）
 */
fun <T> NetworkResult<T>.networkErrorOrNull(): NetworkError? =
    exceptionOrNull()?.toNetworkError()

/**
 * 获取 NetworkResult 中的用户友好错误消息
 */
fun <T> NetworkResult<T>.errorMessageOrNull(): String? =
    networkErrorOrNull()?.toUserMessage()

/**
 * 映射成功的值，保持错误不变
 */
inline fun <T, R> NetworkResult<T>.mapNetworkSuccess(
    transform: (T) -> R
): NetworkResult<R> = mapCatching(transform)

/**
 * 恢复网络错误为默认值
 *
 * @param default 默认值提供函数
 * @return 成功时返回原值，失败时返回默认值
 */
inline fun <T> NetworkResult<T>.recoverNetworkError(
    default: (NetworkError) -> T
): T = getOrElse { throwable ->
    default(throwable.toNetworkError())
}

/**
 * 在失败时执行副作用操作，然后返回原结果
 */
inline fun <T> NetworkResult<T>.doOnNetworkError(
    action: (NetworkError) -> Unit
): NetworkResult<T> = onFailure { throwable ->
    action(throwable.toNetworkError())
}

/**
 * 在成功时执行副作用操作
 */
inline fun <T> NetworkResult<T>.doOnNetworkSuccess(
    action: (T) -> Unit
): NetworkResult<T> = onSuccess(action)
