package ktor.module.routing

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.CandleData
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import model.ws.CandleSubscribeRequest
import org.shiroumi.database.stock.StockReader
import org.shiroumi.database.strategy.daily.repository.DailyStrategyAuditRepository
import org.shiroumi.network.apis.getLimitListD
import org.shiroumi.network.apis.getResearchReport
import org.shiroumi.network.apis.tushare
import org.shiroumi.server.data.bootstrap.DataLayerBootstrap
import org.shiroumi.server.dto.ApiResponse
import org.shiroumi.server.route.StrategySentimentResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun Route.internalCliRoutes() {
    route("/api/internal/cli") {
        // Intercept to only allow local loopback
        intercept(ApplicationCallPipeline.Plugins) {
            val host = call.request.local.remoteHost
            if (host != "127.0.0.1" && host != "0:0:0:0:0:0:0:1" && host != "localhost") {
                call.respond(HttpStatusCode.Forbidden, "Local CLI access only")
                finish()
            }
        }

        get("/get-emotion") {
            try {
                val records = DailyStrategyAuditRepository.getRecentRecords(1)
                val summary = records.firstOrNull()
                
                if (summary == null) {
                    call.respond(HttpStatusCode.NotFound, ApiResponse.error<StrategySentimentResponse>("NOT_FOUND", "No emotional data available"))
                } else {
                    val data = StrategySentimentResponse(
                        tradeDate = summary.tradeDate.toString(),
                        sentimentExposure = summary.sentimentExposure,
                        bullRatio = summary.bullRatio,
                        marketVol = summary.marketVol,
                        fftScore = summary.fftScore,
                        residualScore = summary.residualScore,
                        accelZ = summary.accelZ,
                        volZ = summary.volZ,
                        selectedCount = summary.selectedCount,
                        emptyReason = summary.emptyReason,
                        ratioNorm = summary.ratioNorm,
                        volScore = summary.volScore,
                        accelScore = summary.accelScore,
                        absoluteFloor = summary.absoluteFloor,
                        volCap = summary.volCap,
                    )
                    call.respond(ApiResponse.success(data))
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse.error<StrategySentimentResponse>("INTERNAL_ERROR", e.message ?: "Server error")
                )
            }
        }
        /**
         * GET /api/internal/cli/get-candles?code=000001.SZ&limit=60
         * GET /api/internal/cli/get-candles?name=平安银行&limit=60
         * 获取指定股票最近 N 天的日K线数据，输出 Markdown 表格格式
         * 使用前复权价格
         * 支持两种查询方式（二选一）：code（ts_code）或 name（精确匹配）
         */
        get("/get-candles") {
            try {
                val codeParam = call.request.queryParameters["code"]
                val nameParam = call.request.queryParameters["name"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 60

                val resolved = resolveStock(codeParam, nameParam)
                    ?: run {
                        call.respondText("错误: 必须提供 code 或 name 参数之一", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                val (tsCode, stockName) = resolved

                val request = CandleSubscribeRequest(
                    tsCode = tsCode,
                    period = CandlePeriod.DAY,
                    limit = limit,
                    useAdjusted = true
                )
                val candleDataList = projectSnapshot(tsCode, request)
                if (candleDataList.isEmpty()) {
                    call.respondText("错误: 未找到 $tsCode ($stockName) 的K线数据（快照未就绪）", status = HttpStatusCode.NotFound)
                    return@get
                }

                val md = buildString {
                    appendLine("# $tsCode $stockName 日K线数据（前复权）")
                    appendLine()
                    appendLine("- 数据范围: ${candleDataList.first().date} ~ ${candleDataList.last().date}")
                    appendLine("- 数据条数: ${candleDataList.size}")
                    appendLine()
                    appendLine("| 日期 | 开盘 | 最高 | 最低 | 收盘 | 成交量 | 成交额 | 涨跌幅(%) |")
                    appendLine("|------|------|------|------|------|--------|--------|-----------|")

                    for (c in candleDataList) {
                        val changePct = c.changePercent?.let { formatF(it, 2) } ?: "-"
                        appendLine(
                            "| ${c.date} | ${formatF(c.open, 2)} | ${formatF(c.high, 2)} | ${formatF(c.low, 2)} | ${formatF(c.close, 2)} | ${formatF(c.volume, 0)} | ${formatF(c.turnover, 0)} | $changePct |"
                        )
                    }
                }

                call.respondText(md, contentType = ContentType.Text.Plain)
            } catch (e: Exception) {
                call.respondText("错误: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        /**
         * GET /api/internal/cli/get-intraday-candles?code=000001.SZ&period=60min&limit=100
         * GET /api/internal/cli/get-intraday-candles?name=平安银行&period=30min&limit=50
         * 获取指定股票的小周期K线数据（60分钟/30分钟/15分钟/5分钟），输出 Markdown 表格格式
         * 支持两种查询方式（二选一）：code（ts_code）或 name（精确匹配）
         * 
         * 智能路由逻辑：
         * - 交易时间内 → 调用 getRtMinDailyCandles() 获取实时数据
         * - 非交易时间 → 调用 getMinuteCandles() 获取历史数据
         * 
         * 注意：分钟数据单位差异已在 MinuteCandleService 中处理
         * - rt_min_daily 返回的 vol 单位是"股"
         * - stk_mins 返回的 vol 单位是"手"(100股)
         * 两者都已统一转换为"手"
         */
        get("/get-intraday-candles") {
            try {
                val codeParam = call.request.queryParameters["code"]
                val nameParam = call.request.queryParameters["name"]
                val periodParam = call.request.queryParameters["period"] ?: "60min"
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100

                val resolved = resolveStock(codeParam, nameParam)
                    ?: run {
                        call.respondText("错误: 必须提供 code 或 name 参数之一", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                val (tsCode, stockName) = resolved

                val period = when (periodParam.lowercase()) {
                    "60min" -> CandlePeriod.MIN_60
                    "30min" -> CandlePeriod.MIN_30
                    "15min" -> CandlePeriod.MIN_15
                    "5min" -> CandlePeriod.MIN_5
                    else -> {
                        call.respondText("错误: 不支持的周期 '$periodParam'，支持: 60min, 30min, 15min, 5min", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                }

                val request = CandleSubscribeRequest(
                    tsCode = tsCode,
                    period = period,
                    limit = limit,
                    useAdjusted = true
                )
                val recentCandles = projectSnapshot(tsCode, request)
                if (recentCandles.isEmpty()) {
                    call.respondText("错误: 未找到 $tsCode ($stockName) 的分钟K线数据（快照未就绪）", status = HttpStatusCode.NotFound)
                    return@get
                }

                val md = buildString {
                    appendLine("# $tsCode $stockName ${periodParam}K线数据")
                    appendLine()
                    appendLine("- 数据范围: ${recentCandles.first().date} ~ ${recentCandles.last().date}")
                    appendLine("- 数据条数: ${recentCandles.size}")
                    appendLine("- 数据来源: 内存快照（与前端一致）")
                    appendLine()
                    appendLine("| 时间 | 开盘 | 最高 | 最低 | 收盘 | 成交量(手) | 成交额(元) |")
                    appendLine("|------|------|------|------|------|-----------|-----------|")

                    for (c in recentCandles) {
                        appendLine(
                            "| ${c.date} | ${formatF(c.open, 2)} | ${formatF(c.high, 2)} | ${formatF(c.low, 2)} | ${formatF(c.close, 2)} | ${formatF(c.volume, 0)} | ${formatF(c.turnover, 0)} |"
                        )
                    }
                }

                call.respondText(md, contentType = ContentType.Text.Plain)
            } catch (e: Exception) {
                call.respondText("错误: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        /**
         * GET /api/internal/cli/get-research-reports?code=000001.SZ
         * GET /api/internal/cli/get-research-reports?name=平安银行&limit=5
         *
         * 获取指定股票对应的券商研究报告，默认返回最近 90 天的最新 20 条。
         * 这里默认输出 JSON，优先服务 agent 的稳定解析能力。
         */
        get("/get-research-reports") {
            try {
                val codeParam = call.request.queryParameters["code"]
                val nameParam = call.request.queryParameters["name"]
                val tradeDate = call.request.queryParameters["trade_date"]?.trim().orEmpty().ifBlank { null }
                val startDateParam = call.request.queryParameters["start_date"]?.trim().orEmpty().ifBlank { null }
                val endDateParam = call.request.queryParameters["end_date"]?.trim().orEmpty().ifBlank { null }
                val reportType = call.request.queryParameters["report_type"]?.trim().orEmpty().ifBlank { null }
                val inst = call.request.queryParameters["inst"]?.trim().orEmpty().ifBlank { null }
                val format = call.request.queryParameters["format"]?.trim().orEmpty().ifBlank { "json" }.lowercase()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

                if (format != "json") {
                    call.respondText("错误: 当前仅支持 format=json", status = HttpStatusCode.BadRequest)
                    return@get
                }
                if (limit <= 0) {
                    call.respondText("错误: limit 必须大于 0", status = HttpStatusCode.BadRequest)
                    return@get
                }

                val resolved = resolveStock(codeParam, nameParam)
                    ?: run {
                        call.respondText("错误: 必须提供 code 或 name 参数之一", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                val (tsCode, stockName) = resolved

                val window = resolveResearchWindow(
                    tradeDate = tradeDate,
                    startDate = startDateParam,
                    endDate = endDateParam
                )

                val reports = tushare.getResearchReport(
                    tsCode = tsCode,
                    tradeDate = window.tradeDate,
                    startDate = window.startDate,
                    endDate = window.endDate,
                    reportType = reportType,
                    instCsname = inst
                ).check()
                    ?.toColumns()
                    .orEmpty()
                    .map { column ->
                        ResearchReportItem(
                            tradeDate = column provides "trade_date",
                            title = column provides "title",
                            summary = column provides "abstr",
                            reportType = column provides "report_type",
                            author = column provides "author",
                            stockName = (column provides "name").ifBlank { stockName },
                            tsCode = (column provides "ts_code").ifBlank { tsCode },
                            institution = column provides "inst_csname",
                            industry = column provides "ind_name",
                            url = column provides "url"
                        )
                    }
                    .sortedByDescending { it.tradeDate }
                    .take(limit)

                val response = ResearchReportCliResponse(
                    query = ResearchReportQueryEcho(
                        code = codeParam,
                        name = nameParam,
                        tsCode = tsCode,
                        stockName = stockName,
                        tradeDate = window.tradeDate,
                        startDate = window.startDate,
                        endDate = window.endDate,
                        reportType = reportType,
                        institution = inst,
                        limit = limit,
                        format = format
                    ),
                    totalCount = reports.size,
                    reports = reports
                )

                call.respondText(
                    text = cliJson.encodeToString(response),
                    contentType = ContentType.Application.Json
                )
            } catch (e: Exception) {
                call.respondText("错误: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        /**
         * GET /api/internal/cli/get-limit-list?code=000001.SZ&limit=20
         * GET /api/internal/cli/get-limit-list?name=平安银行&start_date=20260401&end_date=20260425
         *
         * 查询个股涨跌停、炸板与封板强度数据。
         * 数据源：Tushare `limit_list_d`，优先服务 agent 的买卖点分析。
         */
        get("/get-limit-list") {
            try {
                val codeParam = call.request.queryParameters["code"]
                val nameParam = call.request.queryParameters["name"]
                val tradeDate = call.request.queryParameters["trade_date"]?.trim().orEmpty().ifBlank { null }
                val startDateParam = call.request.queryParameters["start_date"]?.trim().orEmpty().ifBlank { null }
                val endDateParam = call.request.queryParameters["end_date"]?.trim().orEmpty().ifBlank { null }
                val limitType = call.request.queryParameters["limit_type"]?.trim().orEmpty().ifBlank { null }
                val format = call.request.queryParameters["format"]?.trim().orEmpty().ifBlank { "json" }.lowercase()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

                if (format != "json") {
                    call.respondText("错误: 当前仅支持 format=json", status = HttpStatusCode.BadRequest)
                    return@get
                }
                if (limit <= 0) {
                    call.respondText("错误: limit 必须大于 0", status = HttpStatusCode.BadRequest)
                    return@get
                }
                if (limitType != null && limitType !in LIMIT_LIST_TYPES) {
                    call.respondText("错误: limit_type 仅支持 U、D、Z", status = HttpStatusCode.BadRequest)
                    return@get
                }

                val resolved = resolveStock(codeParam, nameParam)
                    ?: run {
                        call.respondText("错误: 必须提供 code 或 name 参数之一", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                val (tsCode, stockName) = resolved
                val window = resolveLimitListWindow(
                    tradeDate = tradeDate,
                    startDate = startDateParam,
                    endDate = endDateParam
                )

                val records = tushare.getLimitListD(
                    tsCode = tsCode,
                    tradeDate = window.tradeDate,
                    startDate = window.startDate,
                    endDate = window.endDate,
                    limitType = limitType
                ).check()
                    ?.toColumns()
                    .orEmpty()
                    .map { column ->
                        LimitListItem(
                            tradeDate = column provides "trade_date",
                            tsCode = (column provides "ts_code").ifBlank { tsCode },
                            name = (column provides "name").ifBlank { stockName },
                            industry = column provides "industry",
                            close = column provides "close",
                            pctChg = column provides "pct_chg",
                            amount = column provides "amount",
                            limitAmount = column provides "limit_amount",
                            floatMv = column provides "float_mv",
                            totalMv = column provides "total_mv",
                            turnoverRatio = column provides "turnover_ratio",
                            fdAmount = column provides "fd_amount",
                            firstTime = column provides "first_time",
                            lastTime = column provides "last_time",
                            openTimes = column provides "open_times",
                            upStat = column provides "up_stat",
                            limitTimes = column provides "limit_times",
                            limit = column provides "limit",
                            limitType = (column provides "limit_type").ifBlank { column provides "limit" },
                            strength = LimitStrength.from(column)
                        )
                    }
                    .sortedByDescending { it.tradeDate }
                    .take(limit)

                val response = LimitListCliResponse(
                    query = LimitListQueryEcho(
                        code = codeParam,
                        name = nameParam,
                        tsCode = tsCode,
                        stockName = stockName,
                        tradeDate = window.tradeDate,
                        startDate = window.startDate,
                        endDate = window.endDate,
                        limitType = limitType,
                        limit = limit,
                        format = format
                    ),
                    totalCount = records.size,
                    records = records
                )

                call.respondText(
                    text = cliJson.encodeToString(response),
                    contentType = ContentType.Application.Json
                )
            } catch (e: Exception) {
                call.respondText("错误: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
        }

        /**
         * GET /api/internal/cli/get-industry-research-reports?ind_name=半导体
         * GET /api/internal/cli/get-industry-research-reports?ind_name=银行&limit=5
         *
         * 获取指定行业对应的券商行业研报。
         * 这是独立的 agent CLI 入口，避免上层在“个股研报 / 行业研报”之间自己切换参数形态。
         *
         * 当前固定约束：
         * - `report_type` 固定为行业研报
         * - 默认最近 90 天
         * - 默认返回 JSON
         */
        get("/get-industry-research-reports") {
            try {
                val indName = call.request.queryParameters["ind_name"]?.trim().orEmpty().ifBlank { null }
                val tradeDate = call.request.queryParameters["trade_date"]?.trim().orEmpty().ifBlank { null }
                val startDateParam = call.request.queryParameters["start_date"]?.trim().orEmpty().ifBlank { null }
                val endDateParam = call.request.queryParameters["end_date"]?.trim().orEmpty().ifBlank { null }
                val inst = call.request.queryParameters["inst"]?.trim().orEmpty().ifBlank { null }
                val format = call.request.queryParameters["format"]?.trim().orEmpty().ifBlank { "json" }.lowercase()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

                if (indName.isNullOrBlank()) {
                    call.respondText("错误: 必须提供 ind_name 参数", status = HttpStatusCode.BadRequest)
                    return@get
                }
                if (format != "json") {
                    call.respondText("错误: 当前仅支持 format=json", status = HttpStatusCode.BadRequest)
                    return@get
                }
                if (limit <= 0) {
                    call.respondText("错误: limit 必须大于 0", status = HttpStatusCode.BadRequest)
                    return@get
                }

                val window = resolveResearchWindow(
                    tradeDate = tradeDate,
                    startDate = startDateParam,
                    endDate = endDateParam
                )

                val reports = tushare.getResearchReport(
                    tradeDate = window.tradeDate,
                    startDate = window.startDate,
                    endDate = window.endDate,
                    reportType = INDUSTRY_REPORT_TYPE,
                    instCsname = inst,
                    indName = indName
                ).check()
                    ?.toColumns()
                    .orEmpty()
                    .map { column ->
                        ResearchReportItem(
                            tradeDate = column provides "trade_date",
                            title = column provides "title",
                            summary = column provides "abstr",
                            reportType = (column provides "report_type").ifBlank { INDUSTRY_REPORT_TYPE },
                            author = column provides "author",
                            stockName = column provides "name",
                            tsCode = column provides "ts_code",
                            institution = column provides "inst_csname",
                            industry = (column provides "ind_name").ifBlank { indName },
                            url = column provides "url"
                        )
                    }
                    .sortedByDescending { it.tradeDate }
                    .take(limit)

                val response = IndustryResearchReportCliResponse(
                    query = IndustryResearchReportQueryEcho(
                        indName = indName,
                        tradeDate = window.tradeDate,
                        startDate = window.startDate,
                        endDate = window.endDate,
                        reportType = INDUSTRY_REPORT_TYPE,
                        institution = inst,
                        limit = limit,
                        format = format
                    ),
                    totalCount = reports.size,
                    reports = reports
                )

                call.respondText(
                    text = cliJson.encodeToString(response),
                    contentType = ContentType.Application.Json
                )
            } catch (e: Exception) {
                call.respondText("错误: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
        }
    }
}

/**
 * 根据 code 或 name 解析 (tsCode, stockName)。
 */
private fun resolveStock(codeParam: String?, nameParam: String?): Pair<String, String>? {
    if (codeParam.isNullOrBlank() && nameParam.isNullOrBlank()) return null
    if (!codeParam.isNullOrBlank()) {
        val tsCode = normalizeStockCode(codeParam)
        val name = StockReader.getStockName(tsCode) ?: "未知"
        return tsCode to name
    }
    val infoMap = StockReader.getStockInfoMap()
    val match = infoMap.entries.firstOrNull { it.value == nameParam } ?: return null
    return match.key to match.value
}

/**
 * 从新数据层快照投影 K 线数据，确保与前端 WebSocket 下发的数据同源。
 *
 * 新语义下不再区分：
 * - DAY 走独立服务
 * - 小周期走 provider activation
 *
 * CLI 和 WebSocket 都统一从 `server.data.*` facade 读取。
 */
private suspend fun projectSnapshot(
    tsCode: String,
    request: CandleSubscribeRequest
): List<CandleData> {
    val projection = DataLayerBootstrap.projectionService
    val facade = DataLayerBootstrap.candleFacade
    val key = CandleKey(tsCode, request.period)
    val snapshot = facade.awaitSnapshot(key, timeoutMs = 15_000L) ?: return emptyList()
    return projection.project(key, request, snapshot).candles
}

/**
 * JS/Wasm 无 `String.format`，此处服务端用 round 手动格式化保持统一风格。
 */
private fun formatF(value: Float, scale: Int): String {
    if (value.isNaN() || value.isInfinite()) return "-"
    val factor = Math.pow(10.0, scale.toDouble())
    val rounded = Math.round(value.toDouble() * factor) / factor
    return if (scale == 0) rounded.toLong().toString()
    else String.format("%.${scale}f", rounded)
}

private val cliJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

private val tushareDateFormatter: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
private const val INDUSTRY_REPORT_TYPE = "行业研报"
private val LIMIT_LIST_TYPES = setOf("U", "D", "Z")

/**
 * 解析研报查询时间窗口。
 *
 * 这是 CLI 层的业务默认值，不应该下沉到 network：
 * - 指定 `trade_date` 时按单日查
 * - 指定 `start/end` 时按区间查
 * - 否则默认最近 90 天
 */
private fun resolveResearchWindow(
    tradeDate: String?,
    startDate: String?,
    endDate: String?
): ResearchWindow {
    if (!tradeDate.isNullOrBlank()) {
        return ResearchWindow(
            tradeDate = tradeDate,
            startDate = null,
            endDate = null
        )
    }
    if (!startDate.isNullOrBlank() || !endDate.isNullOrBlank()) {
        return ResearchWindow(
            tradeDate = null,
            startDate = startDate,
            endDate = endDate
        )
    }
    val end = LocalDate.now()
    val start = end.minusDays(90)
    return ResearchWindow(
        tradeDate = null,
        startDate = start.format(tushareDateFormatter),
        endDate = end.format(tushareDateFormatter)
    )
}

private fun resolveLimitListWindow(
    tradeDate: String?,
    startDate: String?,
    endDate: String?
): LimitListWindow {
    if (!tradeDate.isNullOrBlank()) {
        return LimitListWindow(
            tradeDate = tradeDate,
            startDate = null,
            endDate = null
        )
    }
    if (!startDate.isNullOrBlank() || !endDate.isNullOrBlank()) {
        return LimitListWindow(
            tradeDate = null,
            startDate = startDate,
            endDate = endDate
        )
    }
    val end = LocalDate.now()
    val start = end.minusDays(60)
    return LimitListWindow(
        tradeDate = null,
        startDate = start.format(tushareDateFormatter),
        endDate = end.format(tushareDateFormatter)
    )
}

private data class ResearchWindow(
    val tradeDate: String?,
    val startDate: String?,
    val endDate: String?
)

private data class LimitListWindow(
    val tradeDate: String?,
    val startDate: String?,
    val endDate: String?
)

@Serializable
private data class ResearchReportCliResponse(
    val query: ResearchReportQueryEcho,
    val totalCount: Int,
    val reports: List<ResearchReportItem>
)

@Serializable
private data class IndustryResearchReportCliResponse(
    val query: IndustryResearchReportQueryEcho,
    val totalCount: Int,
    val reports: List<ResearchReportItem>
)

@Serializable
private data class LimitListCliResponse(
    val query: LimitListQueryEcho,
    val totalCount: Int,
    val records: List<LimitListItem>
)

@Serializable
private data class ResearchReportQueryEcho(
    val code: String?,
    val name: String?,
    val tsCode: String,
    val stockName: String,
    val tradeDate: String?,
    val startDate: String?,
    val endDate: String?,
    val reportType: String?,
    val institution: String?,
    val limit: Int,
    val format: String
)

@Serializable
private data class IndustryResearchReportQueryEcho(
    val indName: String,
    val tradeDate: String?,
    val startDate: String?,
    val endDate: String?,
    val reportType: String,
    val institution: String?,
    val limit: Int,
    val format: String
)

@Serializable
private data class LimitListQueryEcho(
    val code: String?,
    val name: String?,
    val tsCode: String,
    val stockName: String,
    val tradeDate: String?,
    val startDate: String?,
    val endDate: String?,
    val limitType: String?,
    val limit: Int,
    val format: String
)

@Serializable
private data class ResearchReportItem(
    val tradeDate: String,
    val title: String,
    /**
     * 对外统一摘要字段。
     *
     * 数据来源仍然是 Tushare 的 `abstr` 字段，也就是 PDF/研报摘要本身。
     * 这里只保留 `summary` 这个对外名字，避免返回结构出现重复字段。
     */
    val summary: String,
    val reportType: String,
    val author: String,
    val stockName: String,
    val tsCode: String,
    val institution: String,
    val industry: String,
    val url: String
)

@Serializable
private data class LimitListItem(
    val tradeDate: String,
    val tsCode: String,
    val name: String,
    val industry: String,
    val close: String,
    val pctChg: String,
    /**
     * 成交额，Tushare 原始单位。
     */
    val amount: String,
    /**
     * 板上成交金额，Tushare 原始单位。
     */
    val limitAmount: String,
    /**
     * 流通市值，Tushare 原始单位。
     */
    val floatMv: String,
    /**
     * 总市值，Tushare 原始单位。
     */
    val totalMv: String,
    val turnoverRatio: String,
    /**
     * 封单金额，Tushare 原始单位。
     */
    val fdAmount: String,
    val firstTime: String,
    val lastTime: String,
    /**
     * 打开次数。涨停数据中 open_times > 0 可视为炸板/开板回封痕迹。
     */
    val openTimes: String,
    val upStat: String,
    val limitTimes: String,
    /**
     * D跌停，U涨停，Z炸板。
     */
    val limit: String,
    val limitType: String,
    val strength: LimitStrength
)

@Serializable
private data class LimitStrength(
    val opened: Boolean,
    val openTimes: Int,
    val firstSealTime: String,
    val lastSealTime: String,
    val consecutiveLimitCount: Int?,
    val sealAmount: String,
    val boardAmount: String
) {
    companion object {
        fun from(column: org.shiroumi.network.apis.Column): LimitStrength {
            val openTimes = (column provides "open_times").toIntOrNull() ?: 0
            val limitType = (column provides "limit_type").ifBlank { column provides "limit" }
            return LimitStrength(
                opened = limitType == "Z" || openTimes > 0,
                openTimes = openTimes,
                firstSealTime = column provides "first_time",
                lastSealTime = column provides "last_time",
                consecutiveLimitCount = parseConsecutiveCount(column provides "up_stat"),
                sealAmount = column provides "fd_amount",
                boardAmount = column provides "limit_amount"
            )
        }

        private fun parseConsecutiveCount(upStat: String): Int? {
            val firstNumber = Regex("""\d+""").find(upStat)?.value ?: return null
            return firstNumber.toIntOrNull()
        }
    }
}

/**
 * 规范化股票代码：支持简写（如 000001）自动补全后缀
 */
private fun normalizeStockCode(code: String): String {
    if (code.contains(".")) return code
    val paddedCode = code.padStart(6, '0')
    return when {
        paddedCode.startsWith("6") -> "$paddedCode.SH"
        paddedCode.startsWith("0") || paddedCode.startsWith("3") -> "$paddedCode.SZ"
        paddedCode.startsWith("8") || paddedCode.startsWith("4") -> "$paddedCode.BJ"
        else -> "$paddedCode.SZ"
    }
}
