package ktor.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import model.auth.AuthErrorCodes
import model.auth.AuthErrorResponse
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 时间戳格式化器
 */
private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

/**
 * 获取当前 UTC 时间戳字符串
 */
fun currentTimestamp(): String =
    LocalDateTime.now(ZoneOffset.UTC).format(timestampFormatter)

/**
 * 统一响应认证错误
 */
suspend fun ApplicationCall.respondAuthError(
    code: String,
    message: String,
    status: HttpStatusCode
) = respond(
    status,
    AuthErrorResponse(code, message, currentTimestamp())
)

/**
 * 根据错误码获取 HTTP 状态码
 */
fun getHttpStatus(errorCode: String): HttpStatusCode = when (errorCode) {
    AuthErrorCodes.INVALID_CREDENTIALS -> HttpStatusCode.Unauthorized
    AuthErrorCodes.USER_NOT_FOUND -> HttpStatusCode.NotFound
    AuthErrorCodes.USER_EXISTS -> HttpStatusCode.Conflict
    AuthErrorCodes.TOKEN_EXPIRED,
    AuthErrorCodes.TOKEN_INVALID,
    AuthErrorCodes.TOKEN_REVOKED -> HttpStatusCode.Unauthorized
    AuthErrorCodes.PASSWORD_WEAK,
    AuthErrorCodes.PASSWORD_INCORRECT -> HttpStatusCode.BadRequest
    AuthErrorCodes.UNAUTHORIZED -> HttpStatusCode.Unauthorized
    AuthErrorCodes.FORBIDDEN -> HttpStatusCode.Forbidden
    AuthErrorCodes.ACCOUNT_LOCKED -> HttpStatusCode.TooManyRequests
    AuthErrorCodes.ACCOUNT_DISABLED -> HttpStatusCode.Forbidden
    else -> HttpStatusCode.InternalServerError
}
