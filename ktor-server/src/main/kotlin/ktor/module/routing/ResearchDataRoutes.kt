package ktor.module.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import org.shiroumi.database.common.repository.StockBasicRepository
import org.shiroumi.database.kpl.KplListRepository
import org.shiroumi.database.moneyflow.StockMoneyFlowRepository
import org.shiroumi.database.sentiment.SentimentFactorDailyRepository
import org.shiroumi.database.stock.LimitListDRepository
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.database.stock.StockMinute15mRepository
import org.shiroumi.database.stock.TopInstRepository
import org.shiroumi.database.stock.TopListRepository
import org.shiroumi.server.dto.ApiResponse

private const val MaxResearchDailyLimit = 100_000

fun Route.researchDataRoutes() {
    route("/api/internal/research/profit-prediction") {
        get("/daily-ohlcv") {
            val start = call.parseDateParam("start") ?: return@get
            val end = call.parseDateParam("end") ?: return@get
            val limit = call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, MaxResearchDailyLimit)
                ?: 50_000
            val afterTsCode = call.request.queryParameters["afterTsCode"]?.takeIf { it.isNotBlank() }
            val afterTradeDate = call.request.queryParameters["afterTradeDate"]?.takeIf { it.isNotBlank() }?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            }

            val rows = StockDailyCandleRepository.streamOhlcvForResearchPage(
                startDate = start,
                endDate = end,
                afterTsCode = afterTsCode,
                afterTradeDate = afterTradeDate,
                limit = limit,
            ).map {
                ResearchDailyOhlcvDto(
                    tsCode = it.tsCode,
                    tradeDate = it.tradeDate.toString(),
                    openQfq = it.openQfq,
                    highQfq = it.highQfq,
                    lowQfq = it.lowQfq,
                    closeQfq = it.closeQfq,
                    volumeQfq = it.volumeQfq,
                    turnoverReal = it.turnoverReal,
                )
            }
            val next = rows.lastOrNull()?.let { ResearchCursorDto(it.tsCode, it.tradeDate) }
            call.respond(ApiResponse.success(ResearchDailyOhlcvPageDto(rows, next)))
        }
    }

    route("/api/internal/research/pivot-crash-stock") {
        get("/daily-projection") {
            val start = call.parseDateParam("start") ?: return@get
            val end = call.parseDateParam("end") ?: return@get
            val limit = call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, MaxResearchDailyLimit)
                ?: 50_000
            val afterTsCode = call.request.queryParameters["afterTsCode"]?.takeIf { it.isNotBlank() }
            val afterTradeDate = call.request.queryParameters["afterTradeDate"]?.takeIf { it.isNotBlank() }?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            }

            val rows = StockDailyCandleRepository.streamCloseForAggregationPage(
                startDate = start,
                endDate = end,
                afterTsCode = afterTsCode,
                afterTradeDate = afterTradeDate,
                limit = limit,
            ).map {
                ResearchDailyProjectionDto(
                    tsCode = it.tsCode,
                    tradeDate = it.tradeDate.toString(),
                    closeQfq = it.closeQfq,
                    peTtm = it.peTtm,
                    turnover = it.turnover,
                    mvCirc = it.mvCirc,
                )
            }
            val next = rows.lastOrNull()?.let { ResearchCursorDto(it.tsCode, it.tradeDate) }
            call.respond(ApiResponse.success(ResearchDailyProjectionPageDto(rows, next)))
        }

        get("/profiles") {
            val rows = StockBasicRepository.findProfiles().map {
                ResearchStockProfileDto(
                    tsCode = it.tsCode,
                    name = it.name,
                    listDate = it.listDate?.toString(),
                )
            }
            call.respond(ApiResponse.success(rows))
        }

        get("/sentiment-factors") {
            val start = call.parseDateParam("start") ?: return@get
            val end = call.parseDateParam("end") ?: return@get
            val rows = SentimentFactorDailyRepository.findBetween(start, end).map {
                ResearchSentimentFactorDto(
                    tradeDate = it.tradeDate.toString(),
                    factors = it.factors,
                    vpmRet = it.vpmRet,
                    vpmTurn = it.vpmTurn,
                )
            }
            call.respond(ApiResponse.success(rows))
        }

        get("/moneyflow") {
            val start = call.parseDateParam("start") ?: return@get
            val end = call.parseDateParam("end") ?: return@get
            val limit = call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, MaxResearchDailyLimit)
                ?: 50_000
            val afterTsCode = call.request.queryParameters["afterTsCode"]?.takeIf { it.isNotBlank() }
            val afterTradeDate = call.request.queryParameters["afterTradeDate"]?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            // 表存 YYYYMMDD，对外口径统一 YYYY-MM-DD：入参转紧凑、出参由 Repository 还原 ISO
            val rows = StockMoneyFlowRepository.streamMoneyFlowPage(
                startDate = start.toCompact(),
                endDate = end.toCompact(),
                afterTsCode = afterTsCode,
                afterTradeDate = afterTradeDate?.toCompact(),
                limit = limit,
            ).map {
                ResearchMoneyFlowDto(
                    tsCode = it.tsCode,
                    tradeDate = it.tradeDate,
                    buyLgAmount = it.buyLgAmount,
                    sellLgAmount = it.sellLgAmount,
                    buyElgAmount = it.buyElgAmount,
                    sellElgAmount = it.sellElgAmount,
                    netMfAmount = it.netMfAmount,
                )
            }
            // next 游标 tradeDate 保持 ISO（与入参 LocalDate.parse 解析一致），否则游标失效会无限拉首页
            val next = rows.lastOrNull()?.let {
                ResearchCursorDto(it.tsCode, it.tradeDate)
            }
            call.respond(ApiResponse.success(ResearchMoneyFlowPageDto(rows, next)))
        }

        get("/minute15-structure") {
            val start = call.parseDateParam("start") ?: return@get
            val end = call.parseDateParam("end") ?: return@get
            val limit = call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, MaxResearchDailyLimit)
                ?: 50_000
            val afterTsCode = call.request.queryParameters["afterTsCode"]?.takeIf { it.isNotBlank() }
            val afterTradeDate = call.request.queryParameters["afterTradeDate"]?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val rows = StockMinute15mRepository.streamDailyStructurePage(
                startDate = start,
                endDate = end,
                afterTsCode = afterTsCode,
                afterTradeDate = afterTradeDate,
                limit = limit,
            ).map {
                ResearchMinute15StructureDto(
                    tsCode = it.tsCode,
                    tradeDate = it.tradeDate.toString(),
                    bars = it.bars,
                    totalVol = it.totalVol,
                    totalAmount = it.totalAmount,
                    firstOpen = it.firstOpen,
                    firstHigh = it.firstHigh,
                    firstLow = it.firstLow,
                    firstClose = it.firstClose,
                    firstVol = it.firstVol,
                    firstAmount = it.firstAmount,
                    lastOpen = it.lastOpen,
                    lastHigh = it.lastHigh,
                    lastLow = it.lastLow,
                    lastClose = it.lastClose,
                    lastVol = it.lastVol,
                    lastAmount = it.lastAmount,
                )
            }
            val next = rows.lastOrNull()?.let { ResearchCursorDto(it.tsCode, it.tradeDate) }
            call.respond(ApiResponse.success(ResearchMinute15StructurePageDto(rows, next)))
        }

        get("/limit-list") {
            val start = call.parseDateParam("start") ?: return@get
            val end = call.parseDateParam("end") ?: return@get
            // 涨跌停事件稀疏（每日仅命中股，数百只），区间全量返回即可，无需分页
            val rows = LimitListDRepository.findRange(start, end).map {
                ResearchLimitListDto(
                    tsCode = it.tsCode,
                    tradeDate = it.tradeDate.toString(),
                    limitType = it.limitType,
                    pctChg = it.pctChg,
                    openTimes = it.openTimes,
                    fdAmount = it.fdAmount,
                    limitAmount = it.limitAmount,
                    floatMv = it.floatMv,
                    upStat = it.upStat,
                    limitTimes = it.limitTimes,
                )
            }
            call.respond(ApiResponse.success(rows))
        }

        get("/kpl-list") {
            val start = call.parseDateParam("start") ?: return@get
            val end = call.parseDateParam("end") ?: return@get
            // 题材事件稀疏（每日数十~百条涨停股），区间全量返回
            val rows = KplListRepository.findRange(start.toCompact(), end.toCompact()).map {
                ResearchKplListDto(
                    tsCode = it.tsCode,
                    tradeDate = it.tradeDate,
                    theme = it.theme,
                    status = it.status,
                    tag = it.tag,
                )
            }
            call.respond(ApiResponse.success(rows))
        }

        get("/top-list") {
            val start = call.parseDateParam("start") ?: return@get
            val end = call.parseDateParam("end") ?: return@get
            // 龙虎榜汇总稀疏（每日数十~千条上榜股），区间全量返回
            val rows = TopListRepository.findRange(start.toCompact(), end.toCompact()).map {
                ResearchTopListDto(
                    tsCode = it.tsCode,
                    tradeDate = it.tradeDate,
                    netAmount = it.netAmount,
                    netRate = it.netRate,
                    amount = it.amount,
                )
            }
            call.respond(ApiResponse.success(rows))
        }

        get("/top-inst") {
            val start = call.parseDateParam("start") ?: return@get
            val end = call.parseDateParam("end") ?: return@get
            // 营业部明细每日数千~万条；exalter 原文透传，散户/机构分类在 pytorch 装配层做
            val rows = TopInstRepository.findRange(start.toCompact(), end.toCompact()).map {
                ResearchTopInstDto(
                    tsCode = it.tsCode,
                    tradeDate = it.tradeDate,
                    exalter = it.exalter,
                    side = it.side,
                    buy = it.buy,
                    sell = it.sell,
                    netBuy = it.netBuy,
                )
            }
            call.respond(ApiResponse.success(rows))
        }
    }
}

private fun LocalDate.toCompact(): String =
    "%04d%02d%02d".format(year, monthNumber, dayOfMonth)

private suspend fun io.ktor.server.application.ApplicationCall.parseDateParam(name: String): LocalDate? {
    val raw = request.queryParameters[name]
        ?: run {
            respond(HttpStatusCode.BadRequest, ApiResponse.error<String>("MISSING_DATE", "Missing query parameter '$name'"))
            return null
        }
    return runCatching { LocalDate.parse(raw) }.getOrElse {
        respond(HttpStatusCode.BadRequest, ApiResponse.error<String>("INVALID_DATE", "$name format should be YYYY-MM-DD"))
        null
    }
}

@Serializable
data class ResearchDailyProjectionDto(
    val tsCode: String,
    val tradeDate: String,
    val closeQfq: Double,
    val peTtm: Double?,
    val turnover: Double?,
    val mvCirc: Double?,
)

@Serializable
data class ResearchCursorDto(
    val tsCode: String,
    val tradeDate: String,
)

@Serializable
data class ResearchDailyProjectionPageDto(
    val rows: List<ResearchDailyProjectionDto>,
    val next: ResearchCursorDto?,
)

@Serializable
data class ResearchDailyOhlcvDto(
    val tsCode: String,
    val tradeDate: String,
    val openQfq: Double,
    val highQfq: Double,
    val lowQfq: Double,
    val closeQfq: Double,
    val volumeQfq: Double,
    val turnoverReal: Double,
)

@Serializable
data class ResearchDailyOhlcvPageDto(
    val rows: List<ResearchDailyOhlcvDto>,
    val next: ResearchCursorDto?,
)

@Serializable
data class ResearchStockProfileDto(
    val tsCode: String,
    val name: String,
    val listDate: String?,
)

@Serializable
data class ResearchSentimentFactorDto(
    val tradeDate: String,
    val factors: Map<String, Double?>,
    val vpmRet: Double?,
    val vpmTurn: Double?,
)

@Serializable
data class ResearchMoneyFlowDto(
    val tsCode: String,
    val tradeDate: String,
    val buyLgAmount: Double?,
    val sellLgAmount: Double?,
    val buyElgAmount: Double?,
    val sellElgAmount: Double?,
    val netMfAmount: Double?,
)

@Serializable
data class ResearchMoneyFlowPageDto(
    val rows: List<ResearchMoneyFlowDto>,
    val next: ResearchCursorDto?,
)

@Serializable
data class ResearchMinute15StructureDto(
    val tsCode: String,
    val tradeDate: String,
    val bars: Int,
    val totalVol: Double,
    val totalAmount: Double,
    val firstOpen: Double,
    val firstHigh: Double,
    val firstLow: Double,
    val firstClose: Double,
    val firstVol: Double,
    val firstAmount: Double,
    val lastOpen: Double,
    val lastHigh: Double,
    val lastLow: Double,
    val lastClose: Double,
    val lastVol: Double,
    val lastAmount: Double,
)

@Serializable
data class ResearchMinute15StructurePageDto(
    val rows: List<ResearchMinute15StructureDto>,
    val next: ResearchCursorDto?,
)

@Serializable
data class ResearchKplListDto(
    val tsCode: String,
    val tradeDate: String,
    val theme: String?,      // 题材（可能多个，顿号分隔）
    val status: String?,     // 连板高度「N天M板」
    val tag: String?,        // 涨停/跌停/炸板
)

@Serializable
data class ResearchLimitListDto(
    val tsCode: String,
    val tradeDate: String,
    val limitType: String,        // U=涨停 D=跌停 Z=炸板
    val pctChg: Double?,
    val openTimes: Int?,          // 打开次数（封板质量）
    val fdAmount: Double?,        // 封单额
    val limitAmount: Double?,     // 板上成交额
    val floatMv: Double?,
    val upStat: String?,          // 连板状态
    val limitTimes: Int?,
)

@Serializable
data class ResearchTopListDto(
    val tsCode: String,
    val tradeDate: String,
    val netAmount: Double?,       // 龙虎榜净买入额（方向）
    val netRate: Double?,         // 净买额占比
    val amount: Double?,          // 总成交额（席位特征归一化分母）
)

@Serializable
data class ResearchTopInstDto(
    val tsCode: String,
    val tradeDate: String,
    val exalter: String,          // 营业部名称原文（含「拉萨」=散户、「机构专用」=机构，分类在 pytorch 做）
    val side: String,             // 0=买入前5 / 1=卖出前5
    val buy: Double?,
    val sell: Double?,
    val netBuy: Double?,
)
