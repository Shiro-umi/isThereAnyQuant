package ktor.module.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import model.candle.CandleChartData
import model.candle.CandleData
import model.candle.Exchange
import org.shiroumi.database.stock.StockReader
import org.shiroumi.server.dataprovider.bootstrap.DataProviderBootstrap
import org.shiroumi.server.dto.ApiResponse
import org.shiroumi.server.dto.MatchType
import org.shiroumi.server.dto.PaginationInfo
import org.shiroumi.server.dto.SortField
import org.shiroumi.server.dto.SortOrder
import org.shiroumi.server.dto.StockInfo
import org.shiroumi.server.dto.StockListRequest
import org.shiroumi.server.dto.StockListResponse
import org.shiroumi.server.dto.StockSearchSuggestionsResponse
import org.shiroumi.server.dto.StockSuggestion
import org.shiroumi.server.repository.StockRepositoryImpl
import utils.logger

private val logger by logger("StockRoutes")

// 股票数据仓库实例
private val stockRepository = StockRepositoryImpl(
    stockCatalogSnapshotService = DataProviderBootstrap.stockCatalogSnapshotService
)

/**
 * 股票相关 API 路由
 * 提供股票列表查询、搜索等功能
 */
fun Route.stockRoutes() {
    route("/stocks") {
        // 获取股票列表
        // GET /api/v1/stocks?page=1&pageSize=20&search=&exchange=&industry=&sortBy=CODE&sortOrder=ASC
        get {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 20
            val search = call.request.queryParameters["search"]
            val exchange = call.request.queryParameters["exchange"]?.let { Exchange.valueOf(it) }
            val industry = call.request.queryParameters["industry"]
            val sortBy = call.request.queryParameters["sortBy"]?.let { SortField.valueOf(it) } ?: SortField.CODE
            val sortOrder = call.request.queryParameters["sortOrder"]?.let { SortOrder.valueOf(it) } ?: SortOrder.ASC

            logger.info("获取股票列表: page=$page, pageSize=$pageSize, search=$search")

            // 从 Repository 获取真实数据
            val request = StockListRequest(
                page = page,
                pageSize = pageSize,
                search = search,
                exchange = exchange,
                industry = industry,
                sortBy = sortBy,
                sortOrder = sortOrder
            )

            val response = stockRepository.getStocks(request)

            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        // 搜索建议
        // GET /api/v1/stocks/search?q=茅台
        get("/search") {
            val query = call.request.queryParameters["q"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<StockSearchSuggestionsResponse>("MISSING_QUERY", "Missing search query parameter 'q'")
                )

            logger.info("股票搜索: query=$query")

            // 从 Repository 获取真实搜索建议
            val suggestions = stockRepository.searchStocks(query, limit = 20)

            val response = StockSearchSuggestionsResponse(suggestions = suggestions)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        // 获取单只股票详情
        // GET /api/v1/stocks/{code}
        get("/{code}") {
            val code = call.parameters["code"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<StockInfo>("MISSING_CODE", "Stock code is required")
                )

            logger.info("获取股票详情: code=$code")

            // 从 Repository 获取真实数据
            val stock = stockRepository.getStockByCode(code)
                ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    ApiResponse.error<StockInfo>("STOCK_NOT_FOUND", "Stock not found: $code")
                )

            call.respond(HttpStatusCode.OK, ApiResponse.success(stock))
        }

        // 获取单只股票指定日期范围的日K线数据
        // GET /api/v1/stocks/{code}/candles?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
        get("/{code}/candles") {
            val code = call.parameters["code"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<String>("MISSING_CODE", "Stock code is required")
                )

            val startDateStr = call.request.queryParameters["startDate"]
            val endDateStr = call.request.queryParameters["endDate"]

            if (startDateStr == null || endDateStr == null) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<String>("MISSING_DATES", "startDate and endDate are required")
                )
            }

            val startDate = try {
                LocalDate.parse(startDateStr)
            } catch (e: Exception) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<String>("INVALID_DATE", "startDate format should be YYYY-MM-DD")
                )
            }

            val endDate = try {
                LocalDate.parse(endDateStr)
            } catch (e: Exception) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<String>("INVALID_DATE", "endDate format should be YYYY-MM-DD")
                )
            }

            logger.info("获取K线数据: code=$code, startDate=$startDate, endDate=$endDate")

            val candles = stockRepository.getStockCandles(code, startDate, endDate)

            if (candles.isEmpty()) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiResponse.error<String>("NO_DATA", "股票 $code 在指定日期范围内无数据")
                )
            } else {
                val normalizedCode = if (code.contains(".")) code else {
                    val padded = code.padStart(6, '0')
                    when {
                        padded.startsWith("6") -> "$padded.SH"
                        padded.startsWith("0") || padded.startsWith("3") -> "$padded.SZ"
                        padded.startsWith("8") || padded.startsWith("4") -> "$padded.BJ"
                        else -> "$padded.SZ"
                    }
                }

                val response = CandleChartData(
                    code = code,
                    name = StockReader.getStockName(normalizedCode) ?: code,
                    candles = candles.map { candle ->
                        CandleData(
                            date = candle.date.toString(),
                            open = candle.open,
                            high = candle.high,
                            low = candle.low,
                            close = candle.close,
                            volume = candle.volume,
                            turnover = candle.turnoverReal,
                            changePercent = null
                        )
                    },
                    volumes = candles.map { it.volume },
                    ema20 = emptyList(),
                    rsi6 = emptyList(),
                    macdDif = emptyList(),
                    macdDea = emptyList(),
                    macdBar = emptyList()
                )
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
