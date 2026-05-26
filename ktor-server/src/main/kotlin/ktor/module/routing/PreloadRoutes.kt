package ktor.module.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.shiroumi.database.common.updater.updateCalendar
import org.shiroumi.database.common.updater.updateStockBasic
import org.shiroumi.database.stock.refreshStockDailyFq
import org.shiroumi.server.dataprovider.adapter.TushareHistoricalDailyBatchFetcher
import org.shiroumi.server.runtime.update.DefaultHistoricalDailyBatchSyncService
import utils.logger

private val logger by logger("PreloadRoutes")
private val historicalDailyBatchSyncService by lazy {
    DefaultHistoricalDailyBatchSyncService(
        remoteHistoricalDailyBatchFetcher = TushareHistoricalDailyBatchFetcher()
    )
}

/**
 * 预加载请求
 */
@Serializable
data class PreloadRequest(
    /** 预加载类型列表，为空则加载所有 */
    val types: List<PreloadType> = emptyList()
)

/**
 * 预加载类型
 */
@Serializable
enum class PreloadType {
    /** 股票基础信息 */
    STOCK_BASIC,
    /** 交易日历 */
    CALENDAR,
    /** 股票日线数据 */
    STOCK_DAILY,
    /** 股票日线复权数据 */
    STOCK_DAILY_FQ,
    /** 股票K线数据 */
    STOCK_CANDLE,

}

/**
 * 预加载响应
 */
@Serializable
data class PreloadResponse(
    val success: Boolean,
    val message: String,
    val results: Map<String, PreloadResult> = emptyMap()
)

/**
 * 单个预加载结果
 */
@Serializable
data class PreloadResult(
    val success: Boolean,
    val message: String,
    val durationMs: Long
)

/**
 * 预加载数据API
 * POST /api/preload
 * 
 * 请求示例:
 * ```json
 * {
 *   "types": ["STOCK_BASIC", "CALENDAR", "STOCK_DAILY"]
 * }
 * ```
 * 
 * 响应示例:
 * ```json
 * {
 *   "success": true,
 *   "message": "预加载完成: 3/3 项成功，总耗时 5000ms",
 *   "results": {
 *     "STOCK_BASIC": {
 *       "success": true,
 *       "message": "STOCK_BASIC 加载成功",
 *       "durationMs": 1500
 *     }
 *   }
 * }
 * ```
 */
fun Route.preloadData(route: String) = post(route) {
    val request = runCatching { call.receive<PreloadRequest>() }.getOrDefault(PreloadRequest())
    
    logger.info("开始预加载数据: types=${request.types}")
    
    val startTime = System.currentTimeMillis()
    
    runCatching {
        val results = executePreload(request)
        val totalDuration = System.currentTimeMillis() - startTime
        
        val allSuccess = results.values.all { it.success }
        val successCount = results.values.count { it.success }
        
        logger.info("预加载完成: 成功=${successCount}/${results.size}, 耗时=${totalDuration}ms")
        
        call.respond(
            HttpStatusCode.OK,
            PreloadResponse(
                success = allSuccess,
                message = "预加载完成: ${successCount}/${results.size} 项成功，总耗时 ${totalDuration}ms",
                results = results
            )
        )
    }.onFailure { e ->
        logger.error("预加载失败: ${e.message}")
        call.respond(
            HttpStatusCode.InternalServerError,
            PreloadResponse(
                success = false,
                message = "预加载失败: ${e.message}"
            )
        )
    }
}

/**
 * 执行预加载
 */
private suspend fun executePreload(request: PreloadRequest): Map<String, PreloadResult> = coroutineScope {
    val typesToLoad = request.types.ifEmpty { PreloadType.entries.toList() }
    val results = mutableMapOf<String, PreloadResult>()
    
    val deferreds = typesToLoad.map { type ->
        async {
            val itemStartTime = System.currentTimeMillis()
            val result = runCatching {
                when (type) {
                    PreloadType.STOCK_BASIC -> updateStockBasic()
                    PreloadType.CALENDAR -> updateCalendar()
                    PreloadType.STOCK_DAILY -> historicalDailyBatchSyncService.syncPendingTradeDates()
                    PreloadType.STOCK_DAILY_FQ -> refreshStockDailyFq()
                    PreloadType.STOCK_CANDLE -> {
                        historicalDailyBatchSyncService.syncPendingTradeDates()
                        refreshStockDailyFq()
                    }
                }
                val duration = System.currentTimeMillis() - itemStartTime
                PreloadResult(
                    success = true,
                    message = "${type.name} 加载成功",
                    durationMs = duration
                )
            }.getOrElse { e ->
                val duration = System.currentTimeMillis() - itemStartTime
                PreloadResult(
                    success = false,
                    message = "${type.name} 加载失败: ${e.message}",
                    durationMs = duration
                )
            }
            type.name to result
        }
    }
    
    deferreds.awaitAll().forEach { (name, result) ->
        results[name] = result
    }
    
    results
}
