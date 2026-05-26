package model.agent

import kotlinx.serialization.Serializable

/**
 * 前端请求生成分享链接时的 body（可选字段）。
 *
 * @property themeName 当前 AppColorTheme.name（如 "WarmBrownRed"）
 * @property isDark 当前是否 Dark 模式
 */
@Serializable
data class CreateShareRequest(
    val themeName: String? = null,
    val isDark: Boolean? = null,
)

/**
 * 生成分享链接的响应。
 *
 * @property shareToken 分享 token（base62 随机串）
 * @property shareUrl 完整公开链接，由后端按当前部署环境拼接（http/https + host + 可选端口 + /s/{token}）
 * @property sharedAt ISO-8601 字符串
 */
@Serializable
data class CreateShareResponse(
    val shareToken: String,
    val shareUrl: String,
    val sharedAt: String,
)

/**
 * 分享访问统计。
 */
@Serializable
data class ShareStatsDto(
    val shareToken: String?,
    val shareUrl: String?,
    val viewCount: Long,
    val lastViewedAt: String?,
)
