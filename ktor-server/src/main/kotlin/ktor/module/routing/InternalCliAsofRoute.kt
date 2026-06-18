package ktor.module.routing

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.shiroumi.database.stock.StockDailyCandleRepository
import org.shiroumi.database.stock.StockMinute15mRepository
import org.shiroumi.database.stock.StockReader
import org.shiroumi.network.apis.getLimitListD
import org.shiroumi.network.apis.tushare
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * L0 地基层：历史取数 endpoint。
 *
 * 这些 endpoint 专供回测链路使用，与现网 agent 取数（live 快照）严格隔离：
 * - 强制要求 `as_of` 参数（缺失返回 400），由宿主在 wrapper 中写死注入，agent 无感知。
 * - 数据源固定为数据库历史表与 Tushare 历史接口，绝不读取内存 live 快照。
 * - 按 `as_of` 截断：日线 `trade_date <= as_of`；分钟线 `trade_time <= 信号日收盘`；
 *   涨跌停 `end_date <= as_of`。保证不泄漏信号日 T 盘后或 T+1 的任何数据。
 *
 * 防未来函数口径：回测是 agent 在信号日 T 盘后分析、T+1 开盘按 limitPrice 撮合，
 * 不存在「T+1 盘中截断」。分钟线统一截到信号日 T 收盘（15:00:00）。
 *
 * 本扩展只能在已套用本地回环拦截的 `route("/api/internal/cli")` 块内注册。
 */
fun Route.internalCliAsofRoutes() {
    /**
     * GET /api/internal/cli/get-candles-asof?code=000001.SZ&as_of=20240102&limit=60
     * 历史日K线（前复权），数据源：stock_daily_data，按 trade_date <= as_of 截断。
     */
    get("/get-candles-asof") {
        try {
            val codeParam = call.request.queryParameters["code"]
            val nameParam = call.request.queryParameters["name"]
            val asOfParam = call.request.queryParameters["as_of"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 60

            val asOf = AsofCli.parseAsOf(asOfParam)
                ?: run {
                    call.respondText(
                        "错误: 必须提供 as_of 参数（格式 YYYYMMDD）",
                        status = HttpStatusCode.BadRequest
                    )
                    return@get
                }
            if (limit <= 0) {
                call.respondText("错误: limit 必须大于 0", status = HttpStatusCode.BadRequest)
                return@get
            }

            val resolved = AsofCli.resolveStock(codeParam, nameParam)
                ?: run {
                    call.respondText("错误: 必须提供 code 或 name 参数之一", status = HttpStatusCode.BadRequest)
                    return@get
                }
            val (tsCode, stockName) = resolved

            // 历史表读取：endDateInclusive = as_of，保证返回行最大日期不超过 as_of。
            val candles = StockDailyCandleRepository.findRecent(
                tsCode = tsCode,
                limit = limit,
                endDateInclusive = asOf
            )
            if (candles.isEmpty()) {
                call.respondText(
                    "错误: 未找到 $tsCode ($stockName) 截止 $asOf 的历史日K线数据",
                    status = HttpStatusCode.NotFound
                )
                return@get
            }

            val md = buildString {
                appendLine("# $tsCode $stockName 日K线数据（前复权，截止 $asOf）")
                appendLine()
                appendLine("- 数据范围: ${candles.first().date} ~ ${candles.last().date}")
                appendLine("- 数据条数: ${candles.size}")
                appendLine("- 数据来源: 历史数据库（信号日截断 as_of=$asOf）")
                appendLine()
                appendLine("| 日期 | 开盘 | 最高 | 最低 | 收盘 | 成交量 | 成交额 |")
                appendLine("|------|------|------|------|------|--------|--------|")
                for (c in candles) {
                    val o = if (c.openQfq > 0f) c.openQfq else c.open
                    val h = if (c.highQfq > 0f) c.highQfq else c.high
                    val l = if (c.lowQfq > 0f) c.lowQfq else c.low
                    val cl = if (c.closeQfq > 0f) c.closeQfq else c.close
                    val v = if (c.volumeQfq > 0f) c.volumeQfq else c.volume
                    appendLine(
                        "| ${c.date} | ${AsofCli.fmt(o, 2)} | ${AsofCli.fmt(h, 2)} | ${AsofCli.fmt(l, 2)} | ${AsofCli.fmt(cl, 2)} | ${AsofCli.fmt(v, 0)} | ${AsofCli.fmt(c.turnoverReal, 0)} |"
                    )
                }
            }
            call.respondText(md, contentType = ContentType.Text.Plain)
        } catch (e: Exception) {
            call.respondText("错误: ${e.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    /**
     * GET /api/internal/cli/get-intraday-candles-asof?code=000001.SZ&as_of=20240102&period=15min&limit=100
     * 历史小周期K线，数据源：stock_minute_15m。
     *
     * 当前历史分钟库仅有 15 分钟粒度。period 接受 60min/30min/15min/5min，
     * 15min 直接返回真实行；其余粒度由 15min 等比聚合得到，不伪造更细粒度数据。
     *
     * 按信号日收盘截断：trade_time <= "{as_of} 15:00:00"。
     */
    get("/get-intraday-candles-asof") {
        try {
            val codeParam = call.request.queryParameters["code"]
            val nameParam = call.request.queryParameters["name"]
            val asOfParam = call.request.queryParameters["as_of"]
            val periodParam = call.request.queryParameters["period"] ?: "15min"
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100

            val asOf = AsofCli.parseAsOf(asOfParam)
                ?: run {
                    call.respondText(
                        "错误: 必须提供 as_of 参数（格式 YYYYMMDD）",
                        status = HttpStatusCode.BadRequest
                    )
                    return@get
                }
            if (limit <= 0) {
                call.respondText("错误: limit 必须大于 0", status = HttpStatusCode.BadRequest)
                return@get
            }
            if (!AsofCli.isSupportedPeriod(periodParam)) {
                call.respondText(
                    "错误: 不支持的周期 '$periodParam'，支持: 60min, 30min, 15min, 5min",
                    status = HttpStatusCode.BadRequest
                )
                return@get
            }

            val resolved = AsofCli.resolveStock(codeParam, nameParam)
                ?: run {
                    call.respondText("错误: 必须提供 code 或 name 参数之一", status = HttpStatusCode.BadRequest)
                    return@get
                }
            val (tsCode, stockName) = resolved

            // 单根 15min 基础行数：聚合粒度越大，需要的基础行越多。
            val baseLimit = limit * AsofCli.aggregateFactor(periodParam)
            val upperBound = AsofCli.intradayUpperBound(asOf)
            val rows = StockMinute15mRepository.findRecentForCodeAsOf(
                tsCode = tsCode,
                tradeTimeUpperBoundInclusive = upperBound,
                limit = baseLimit
            )
            if (rows.isEmpty()) {
                call.respondText(
                    "错误: 未找到 $tsCode ($stockName) 截止 $asOf 的历史分钟K线数据",
                    status = HttpStatusCode.NotFound
                )
                return@get
            }

            val bars = AsofCli.aggregateMinuteBars(rows, periodParam).takeLast(limit)

            val md = buildString {
                appendLine("# $tsCode $stockName ${periodParam}K线数据（截止 $asOf 收盘）")
                appendLine()
                appendLine("- 数据范围: ${bars.first().tradeTime} ~ ${bars.last().tradeTime}")
                appendLine("- 数据条数: ${bars.size}")
                appendLine("- 数据来源: 历史数据库（信号日收盘截断 as_of=$asOf 15:00:00）")
                appendLine()
                appendLine("| 时间 | 开盘 | 最高 | 最低 | 收盘 | 成交量(手) | 成交额(元) |")
                appendLine("|------|------|------|------|------|-----------|-----------|")
                for (b in bars) {
                    appendLine(
                        "| ${b.tradeTime} | ${AsofCli.fmt(b.open, 2)} | ${AsofCli.fmt(b.high, 2)} | ${AsofCli.fmt(b.low, 2)} | ${AsofCli.fmt(b.close, 2)} | ${AsofCli.fmt(b.vol, 0)} | ${AsofCli.fmt(b.amount, 0)} |"
                    )
                }
            }
            call.respondText(md, contentType = ContentType.Text.Plain)
        } catch (e: Exception) {
            call.respondText("错误: ${e.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    /**
     * GET /api/internal/cli/get-limit-list-asof?code=000001.SZ&as_of=20240102&limit=20
     * 历史涨跌停、炸板与封板强度，数据源：Tushare limit_list_d。
     *
     * 按 as_of 截断：end_date 强制不超过 as_of。即使调用方传入更晚的 end_date，
     * 也会被收敛到 as_of，绝不返回信号日之后的涨跌停记录。
     */
    get("/get-limit-list-asof") {
        try {
            val codeParam = call.request.queryParameters["code"]
            val nameParam = call.request.queryParameters["name"]
            val asOfParam = call.request.queryParameters["as_of"]
            val startDateParam = call.request.queryParameters["start_date"]?.trim().orEmpty().ifBlank { null }
            val limitType = call.request.queryParameters["limit_type"]?.trim().orEmpty().ifBlank { null }
            val format = call.request.queryParameters["format"]?.trim().orEmpty().ifBlank { "json" }.lowercase()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

            val asOf = AsofCli.parseAsOf(asOfParam)
                ?: run {
                    call.respondText(
                        "错误: 必须提供 as_of 参数（格式 YYYYMMDD）",
                        status = HttpStatusCode.BadRequest
                    )
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
            if (limitType != null && limitType !in AsofCli.LIMIT_LIST_TYPES) {
                call.respondText("错误: limit_type 仅支持 U、D、Z", status = HttpStatusCode.BadRequest)
                return@get
            }

            val resolved = AsofCli.resolveStock(codeParam, nameParam)
                ?: run {
                    call.respondText("错误: 必须提供 code 或 name 参数之一", status = HttpStatusCode.BadRequest)
                    return@get
                }
            val (tsCode, stockName) = resolved

            // end_date 强制收敛到 as_of；start_date 缺省回看 60 个自然日，且不晚于 as_of。
            val endDate = AsofCli.toTushareDate(asOf)
            val startDate = AsofCli.resolveAsOfStartDate(startDateParam, asOf)

            val records = tushare.getLimitListD(
                tsCode = tsCode,
                tradeDate = null,
                startDate = startDate,
                endDate = endDate,
                limitType = limitType
            ).check()
                ?.toColumns()
                .orEmpty()
                .map { column ->
                    AsofLimitListItem(
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
                        limitType = (column provides "limit_type").ifBlank { column provides "limit" }
                    )
                }
                // 双保险：即使上游返回越界数据，也在应用层再过滤一次 trade_date <= as_of。
                .filter { AsofCli.tradeDateNotAfter(it.tradeDate, endDate) }
                .sortedByDescending { it.tradeDate }
                .take(limit)

            val response = AsofLimitListCliResponse(
                query = AsofLimitListQueryEcho(
                    code = codeParam,
                    name = nameParam,
                    tsCode = tsCode,
                    stockName = stockName,
                    asOf = endDate,
                    startDate = startDate,
                    endDate = endDate,
                    limitType = limitType,
                    limit = limit,
                    format = format
                ),
                totalCount = records.size,
                records = records
            )

            call.respondText(
                text = asofCliJson.encodeToString(response),
                contentType = ContentType.Application.Json
            )
        } catch (e: Exception) {
            call.respondText("错误: ${e.message}", status = HttpStatusCode.InternalServerError)
        }
    }
}

private val asofCliJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

/**
 * 历史取数纯逻辑（无 IO、无路由依赖），单独抽出以便测试覆盖 as_of 截断语义。
 */
object AsofCli {

    val LIMIT_LIST_TYPES = setOf("U", "D", "Z")

    private val INPUT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    /** 信号日收盘时刻：A 股收盘 15:00:00，分钟线截到这一刻。 */
    private const val INTRADAY_CLOSE_SUFFIX = " 15:00:00"

    /** 一根聚合 K 线由多少根 15min 基础 K 线合成。 */
    private val AGGREGATE_FACTOR = mapOf(
        "60min" to 4,
        "30min" to 2,
        "15min" to 1,
        "5min" to 1,
    )

    /** 聚合后的小周期 K 线，时间戳取该桶最后一根基础 K 线的时间。 */
    data class IntradayBar(
        val tradeTime: String,
        val open: Float,
        val high: Float,
        val low: Float,
        val close: Float,
        val vol: Float,
        val amount: Float,
    )

    /** 解析 YYYYMMDD 为 kotlinx LocalDate，非法格式返回 null。 */
    fun parseAsOf(raw: String?): kotlinx.datetime.LocalDate? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.length != 8 || trimmed.any { !it.isDigit() }) return null
        return try {
            val javaDate = LocalDate.parse(trimmed, INPUT_DATE_FORMATTER)
            kotlinx.datetime.LocalDate(javaDate.year, javaDate.monthValue, javaDate.dayOfMonth)
        } catch (_: Exception) {
            null
        }
    }

    fun isSupportedPeriod(period: String): Boolean = period.lowercase() in AGGREGATE_FACTOR.keys

    fun aggregateFactor(period: String): Int = AGGREGATE_FACTOR[period.lowercase()] ?: 1

    /** 信号日收盘时间戳，形如 "2024-01-02 15:00:00"。 */
    fun intradayUpperBound(asOf: kotlinx.datetime.LocalDate): String = "$asOf$INTRADAY_CLOSE_SUFFIX"

    /** kotlinx LocalDate -> Tushare YYYYMMDD。 */
    fun toTushareDate(date: kotlinx.datetime.LocalDate): String {
        val y = date.year.toString().padStart(4, '0')
        val m = date.monthNumber.toString().padStart(2, '0')
        val d = date.dayOfMonth.toString().padStart(2, '0')
        return "$y$m$d"
    }

    /**
     * 收敛涨跌停查询 start_date：缺省回看 60 个自然日；
     * 若调用方传入的 start_date 晚于 as_of，则收敛到 as_of。
     */
    fun resolveAsOfStartDate(startDateRaw: String?, asOf: kotlinx.datetime.LocalDate): String {
        val asOfStr = toTushareDate(asOf)
        if (!startDateRaw.isNullOrBlank()) {
            return if (startDateRaw > asOfStr) asOfStr else startDateRaw
        }
        val end = LocalDate.of(asOf.year, asOf.monthNumber, asOf.dayOfMonth)
        return end.minusDays(60).format(INPUT_DATE_FORMATTER)
    }

    /** Tushare trade_date（YYYYMMDD）是否不晚于上界（YYYYMMDD），用于应用层双保险过滤。 */
    fun tradeDateNotAfter(tradeDate: String, upperBoundInclusive: String): Boolean {
        val normalized = tradeDate.trim()
        if (normalized.isEmpty()) return false
        return normalized <= upperBoundInclusive
    }

    /**
     * 由 15min 基础行等比聚合为目标周期 K 线。
     *
     * 输入按时间正序。每 [aggregateFactor] 根合成一根：
     * 开=桶首开、高=桶内最高、低=桶内最低、收=桶尾收、量额累加，时间取桶尾。
     * factor=1 时原样返回（含 5min 回退到 15min 的情形）。
     */
    fun aggregateMinuteBars(
        rowsAsc: List<StockMinute15mRepository.Minute15mRow>,
        period: String,
    ): List<IntradayBar> {
        val factor = aggregateFactor(period)
        if (factor <= 1) {
            return rowsAsc.map {
                IntradayBar(it.tradeTime, it.open, it.high, it.low, it.close, it.vol, it.amount)
            }
        }
        return rowsAsc.chunked(factor).map { bucket ->
            IntradayBar(
                tradeTime = bucket.last().tradeTime,
                open = bucket.first().open,
                high = bucket.maxOf { it.high },
                low = bucket.minOf { it.low },
                close = bucket.last().close,
                vol = bucket.sumOf { it.vol.toDouble() }.toFloat(),
                amount = bucket.sumOf { it.amount.toDouble() }.toFloat(),
            )
        }
    }

    /**
     * 解析 (tsCode, stockName)。code 优先；否则按 name 精确匹配。
     * 与现网 CLI 路由口径一致（复用 StockReader）。
     */
    fun resolveStock(codeParam: String?, nameParam: String?): Pair<String, String>? {
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

    /** 规范化股票代码：简写自动补全交易所后缀。 */
    fun normalizeStockCode(code: String): String {
        if (code.contains(".")) return code
        val paddedCode = code.padStart(6, '0')
        return when {
            paddedCode.startsWith("6") -> "$paddedCode.SH"
            paddedCode.startsWith("0") || paddedCode.startsWith("3") -> "$paddedCode.SZ"
            paddedCode.startsWith("8") || paddedCode.startsWith("4") -> "$paddedCode.BJ"
            else -> "$paddedCode.SZ"
        }
    }

    /** JS/Wasm 无 String.format，服务端用 round 手动格式化，统一风格。 */
    fun fmt(value: Float, scale: Int): String {
        if (value.isNaN() || value.isInfinite()) return "-"
        val factor = Math.pow(10.0, scale.toDouble())
        val rounded = Math.round(value.toDouble() * factor) / factor
        return if (scale == 0) rounded.toLong().toString()
        else String.format("%.${scale}f", rounded)
    }
}

@Serializable
private data class AsofLimitListCliResponse(
    val query: AsofLimitListQueryEcho,
    val totalCount: Int,
    val records: List<AsofLimitListItem>
)

@Serializable
private data class AsofLimitListQueryEcho(
    val code: String?,
    val name: String?,
    val tsCode: String,
    val stockName: String,
    val asOf: String,
    val startDate: String,
    val endDate: String,
    val limitType: String?,
    val limit: Int,
    val format: String
)

@Serializable
private data class AsofLimitListItem(
    val tradeDate: String,
    val tsCode: String,
    val name: String,
    val industry: String,
    val close: String,
    val pctChg: String,
    val amount: String,
    val limitAmount: String,
    val floatMv: String,
    val totalMv: String,
    val turnoverRatio: String,
    val fdAmount: String,
    val firstTime: String,
    val lastTime: String,
    val openTimes: String,
    val upStat: String,
    val limitTimes: String,
    val limit: String,
    val limitType: String
)
