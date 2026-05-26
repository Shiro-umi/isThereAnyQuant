package org.shiroumi.server.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import ktor.auth.getCurrentUserId
import model.agent.CreateShareRequest
import model.agent.CreateShareResponse
import model.agent.ShareStatsDto
import org.shiroumi.config.ShareUrlBuilder
import org.shiroumi.database.agent.repository.AgentAnalysisResultRepository
import org.shiroumi.database.agent.repository.AgentShareKlineAllowlistRepository
import org.shiroumi.database.agent.repository.AgentShareViewLogRepository
import org.shiroumi.database.agent.repository.ShareKlineEntry
import org.shiroumi.database.agent.repository.toDto
import org.shiroumi.server.dto.ApiResponse
import org.shiroumi.server.share.QuantBlockExtractor
import org.shiroumi.server.share.ShareTokenGenerator
import java.util.UUID

/**
 * Agent 分析结果历史记录路由
 *
 * 提供当前用户的分析结果查询与删除能力。
 */
fun Route.agentAnalysisRoutes() {
    authenticate("auth-jwt") {
        route("/agent/analysis") {

            /**
             * GET /api/v1/agent/analysis
             * 查询当前用户的分析历史列表
             */
            get {
                val userId = call.getCurrentUserId()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiResponse.error<String>("UNAUTHORIZED", "用户未登录")
                    )

                val tsCode = call.request.queryParameters["tsCode"]
                val type = call.request.queryParameters["type"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20

                try {
                    val results = AgentAnalysisResultRepository.findByUser(
                        userId = userId,
                        tsCode = tsCode,
                        type = type,
                        limit = limit
                    ).map { it.toDto() }

                    call.respond(HttpStatusCode.OK, ApiResponse.success(results))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiResponse.error<String>("INTERNAL_ERROR", e.message ?: "查询失败")
                    )
                }
            }

            /**
             * GET /api/v1/agent/analysis/{id}
             * 获取单条分析详情
             */
            get("/{id}") {
                val userId = call.getCurrentUserId()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiResponse.error<String>("UNAUTHORIZED", "用户未登录")
                    )

                val idStr = call.parameters["id"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<String>("INVALID_PARAMETER", "缺少 ID 参数")
                    )

                val id = try {
                    UUID.fromString(idStr)
                } catch (e: IllegalArgumentException) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<String>("INVALID_PARAMETER", "ID 格式错误")
                    )
                }

                try {
                    val result = AgentAnalysisResultRepository.findById(id)
                    if (result == null || result.userId != userId) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiResponse.error<String>("RESOURCE_NOT_FOUND", "记录不存在")
                        )
                    } else {
                        call.respond(HttpStatusCode.OK, ApiResponse.success(result.toDto()))
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiResponse.error<String>("INTERNAL_ERROR", e.message ?: "查询失败")
                    )
                }
            }

            /**
             * POST /api/v1/agent/analysis/{id}/share
             * 为该分析记录生成（或返回已有的）公开分享 token，并写入 K 线白名单。
             * 幂等：重复调用返回同一个 token。
             */
            post("/{id}/share") {
                val userId = call.getCurrentUserId()
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiResponse.error<String>("UNAUTHORIZED", "用户未登录")
                    )

                val id = parseUuidOr(call) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse.error<String>("INVALID_PARAMETER", it))
                } ?: return@post

                val record = AgentAnalysisResultRepository.findById(id)
                if (record == null) {
                    return@post call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<String>(
                            "RESOURCE_NOT_FOUND",
                            "记录不存在: id=$id, currentUser=$userId"
                        )
                    )
                }
                if (record.userId != userId) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse.error<String>(
                            "FORBIDDEN",
                            "无权分享他人记录: owner=${record.userId}, currentUser=$userId"
                        )
                    )
                }

                val candidateToken = ShareTokenGenerator.newShareToken()
                val shareReq = runCatching { call.receiveNullable<CreateShareRequest>() }.getOrNull()
                val token = AgentAnalysisResultRepository.grantShareToken(
                    id = id,
                    userId = userId,
                    tokenIfAbsent = candidateToken,
                    themeName = shareReq?.themeName,
                    isDark = shareReq?.isDark,
                )
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<String>("RESOURCE_NOT_FOUND", "记录不存在")
                    )

                // 解析报告中所有 quant-kline 块，写入白名单（幂等）
                val klineSpecs = QuantBlockExtractor.extractKlineEntries(record.contentMd)
                if (klineSpecs.isNotEmpty()) {
                    val entries = klineSpecs.map { spec ->
                        ShareKlineEntry(
                            shareToken = token,
                            blockKey = ShareTokenGenerator.blockKey(
                                tsCode = spec.tsCode,
                                period = spec.period,
                                startDate = spec.startDate,
                                endDate = spec.endDate,
                                limitCount = spec.limitCount,
                                indicators = spec.indicators,
                                useAdjusted = spec.useAdjusted,
                            ),
                            tsCode = spec.tsCode,
                            period = spec.period,
                            startDate = spec.startDate,
                            endDate = spec.endDate,
                            limitCount = spec.limitCount,
                            indicators = spec.indicators,
                            useAdjusted = spec.useAdjusted,
                        )
                    }
                    AgentShareKlineAllowlistRepository.upsertBatch(entries)
                }

                val updated = AgentAnalysisResultRepository.findById(id)
                val sharedAt = updated?.sharedAt?.toString() ?: ""

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        CreateShareResponse(
                            shareToken = token,
                            shareUrl = ShareUrlBuilder.build(token),
                            sharedAt = sharedAt,
                        )
                    )
                )
            }

            /**
             * GET /api/v1/agent/analysis/{id}/share/stats
             * 返回该分析记录的分享访问统计。未分享时 shareToken/shareUrl 为 null。
             */
            get("/{id}/share/stats") {
                val userId = call.getCurrentUserId()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiResponse.error<String>("UNAUTHORIZED", "用户未登录")
                    )

                val id = parseUuidOr(call) {
                    call.respond(HttpStatusCode.BadRequest, ApiResponse.error<String>("INVALID_PARAMETER", it))
                } ?: return@get

                val record = AgentAnalysisResultRepository.findById(id)
                if (record == null || record.userId != userId) {
                    return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<String>("RESOURCE_NOT_FOUND", "记录不存在")
                    )
                }

                val token = record.shareToken
                val stats = token?.let { AgentShareViewLogRepository.stats(it) }

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        ShareStatsDto(
                            shareToken = token,
                            shareUrl = token?.let { ShareUrlBuilder.build(it) },
                            viewCount = stats?.viewCount ?: 0L,
                            lastViewedAt = stats?.lastViewedAt?.toString(),
                        )
                    )
                )
            }

            /**
             * DELETE /api/v1/agent/analysis/{id}
             * 删除单条分析记录
             */
            delete("/{id}") {
                val userId = call.getCurrentUserId()
                    ?: return@delete call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiResponse.error<String>("UNAUTHORIZED", "用户未登录")
                    )

                val idStr = call.parameters["id"]
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<String>("INVALID_PARAMETER", "缺少 ID 参数")
                    )

                val id = try {
                    UUID.fromString(idStr)
                } catch (e: IllegalArgumentException) {
                    return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<String>("INVALID_PARAMETER", "ID 格式错误")
                    )
                }

                try {
                    val deleted = AgentAnalysisResultRepository.delete(id, userId)
                    if (deleted) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ApiResponse.error<String>("RESOURCE_NOT_FOUND", "记录不存在或无权删除")
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiResponse.error<String>("INTERNAL_ERROR", e.message ?: "删除失败")
                    )
                }
            }
        }
    }
}

private suspend inline fun parseUuidOr(
    call: ApplicationCall,
    onInvalid: (String) -> Unit,
): UUID? {
    val idStr = call.parameters["id"]
    if (idStr == null) {
        onInvalid("缺少 ID 参数")
        return null
    }
    return try {
        UUID.fromString(idStr)
    } catch (_: IllegalArgumentException) {
        onInvalid("ID 格式错误")
        null
    }
}
