package org.shiroumi.network.apis

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.shiroumi.config.ConfigHolder
import org.shiroumi.network.ApiClient
import org.shiroumi.network.TushareRateLimiter
import org.shiroumi.network.tushare
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 全局 API 实例
 */
val tushare: TuShareApi by tushare()

/**
 * Tushare API 接口
 */
interface TuShareApi {
    suspend fun query(body: TushareRequest): BaseTushare
}

/**
 * Tushare API 实现
 */
class TuShareApiImpl(private val client: ApiClient) : TuShareApi {
    override suspend fun query(body: TushareRequest): BaseTushare {
        TushareRateLimiter.acquire(body.apiName, body.token)
        return client.post("/", body)
    }
}

/**
 * Tushare 请求体
 */
@Serializable
data class TushareRequest(
    @SerialName("api_name") val apiName: String,
    val token: String,
    val params: Map<String, String> = emptyMap(),
    val fields: List<String> = emptyList()
)

suspend fun TuShareApi.getThsHotStocks(tradeDate: String) = query(
    tushareParams.ofApi("ths_hot")
        .carriesParam(mutableMapOf<String, String>().apply {
            put("trade_date", tradeDate)
            put("market", "热股")
            put("is_new", "N")
        }).toRequest()
)

suspend fun TuShareApi.getStockBasic() = query(
    tushareParams.ofApi("stock_basic")
        .requireFields(
            listOf(
                "ts_code",
                "symbol",
                "name",
                "area",
                "industry",
                "fullname",
                "enname",
                "cnspell",
                "market",
                "exchange",
                "curr_type",
                "list_status",
                "list_date",
                "delist_date",
                "is_hs",
                "act_name",
                "act_ent_type",
            )
        ).toRequest()
)

suspend fun TuShareApi.getAdjFactor(
    tsCode: String? = null,
    date: String? = null,
    startDate: String? = null,
    endDate: String? = null
) =
    query(
        tushareParams.ofApi("adj_factor").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
                startDate?.let { put("start_date", it) }
                endDate?.let { put("end_date", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getDailyCandles(tsCode: String? = null, date: String? = null) =
    query(
        tushareParams.ofApi("daily").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getDailyLimit(tsCode: String? = null, date: String? = null) =
    query(
        tushareParams.ofApi("stk_limit").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getLimitListD(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null,
    limitType: String? = null,
    exchange: String? = null
) =
    query(
        tushareParams.ofApi("limit_list_d")
            .carriesParam(
                mutableMapOf<String, String>().apply {
                    tsCode?.let { put("ts_code", it) }
                    tradeDate?.let { put("trade_date", it) }
                    startDate?.let { put("start_date", it) }
                    endDate?.let { put("end_date", it) }
                    limitType?.let { put("limit_type", it) }
                    exchange?.let { put("exchange", it) }
                }
            )
            .requireFields(
                listOf(
                    "trade_date",
                    "ts_code",
                    "industry",
                    "name",
                    "close",
                    "pct_chg",
                    "amount",
                    "limit_amount",
                    "float_mv",
                    "total_mv",
                    "turnover_ratio",
                    "fd_amount",
                    "first_time",
                    "last_time",
                    "open_times",
                    "up_stat",
                    "limit_times",
                    "limit"
                )
            )
            .toRequest()
    )


suspend fun TuShareApi.getDailyInfo(tsCode: String? = null, date: String? = null) =
    query(
        tushareParams.ofApi("daily_basic").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getCalendar() = query(tushareParams.ofApi("trade_cal").toRequest())

suspend fun TuShareApi.getIndexDaily(
    tsCode: String? = null,
    date: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("index_daily").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            date?.let { put("trade_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).toRequest())

suspend fun TuShareApi.getIndexBasic(
    tsCode: String? = null,
    market: String? = null,
    publisher: String? = null,
    category: String? = null
) = query(
    tushareParams.ofApi("index_basic").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            market?.let { put("market", it) }
            publisher?.let { put("publisher", it) }
            category?.let { put("category", it) }
        }
    ).toRequest())

suspend fun TuShareApi.getSwIndexClassify(src: String = "SW2021", parentCode: String? = null) =
    query(
        tushareParams.ofApi("index_classify").carriesParam(
            mutableMapOf<String, String>().apply {
                put("src", src)
                parentCode?.let { put("parent_code", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getSwIndexDailyCandle(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null
) =
    query(
        tushareParams.ofApi("sw_daily").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                tradeDate?.let { put("trade_date", it) }
                startDate?.let { put("start_date", it) }
                endDate?.let { put("end_date", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getSwIndexMember(
    l1Code: String? = null,
    l2Code: String? = null,
    l3Code: String? = null,
    tsCode: String? = null
) = query(
    tushareParams.ofApi("index_member_all").carriesParam(
        mutableMapOf<String, String>().apply {
            l1Code?.let { put("l1_code", it) }
            l2Code?.let { put("l2_code", it) }
            l3Code?.let { put("l3_code", it) }
            tsCode?.let { put("ts_code", it) }
        }
    ).toRequest())

suspend fun TuShareApi.getKplConcept(tradeDate: String? = null) = query(
    tushareParams.ofApi("kpl_concept").carriesParam(
        mutableMapOf<String, String>().apply {
            tradeDate?.let { put("trade_date", it) }
        }
    ).toRequest()
)

suspend fun TuShareApi.getKplConceptCons(tsCode: String? = null, tradeDate: String? = null) = query(
    tushareParams.ofApi("kpl_concept_cons").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            tradeDate?.let { put("trade_date", it) }
        }
    ).toRequest()
)

suspend fun TuShareApi.getAuction(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("stk_auction").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            tradeDate?.let { put("trade_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).toRequest()
)

/**
 * 获取股票当日实时分钟行情数据 (rt_min_daily)
 *
 * 一次性获取当天该级别全量K线（含正在形成中的最新一根），适合盘中实时刷新。
 *
 * @param tsCode 股票代码，如 600000.SH
 * @param freq 分钟频度：1min / 5min / 15min / 30min / 60min
 */
suspend fun TuShareApi.getRtMinDaily(
    tsCode: String,
    freq: String
) = query(
    tushareParams.ofApi("rt_min_daily").carriesParam(
        mutableMapOf<String, String>().apply {
            put("ts_code", tsCode)
            put("freq", freq.uppercase()) // Tushare docs recommend uppercase freq
        }
    ).requireFields(
        listOf("ts_code", "time", "open", "close", "high", "low", "vol", "amount")
    ).toRequest()
)

/**
 * 获取股票分钟级历史行情数据 (stk_mins)
 *
 * @param tsCode 股票代码，如 600000.SH
 * @param freq 分钟频度：1min / 5min / 15min / 30min / 60min
 * @param startDate 开始时间，格式：2023-08-25 09:00:00
 * @param endDate 结束时间，格式：2023-08-25 19:00:00
 */
suspend fun TuShareApi.getStkMins(
    tsCode: String,
    freq: String,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("stk_mins").carriesParam(
        mutableMapOf<String, String>().apply {
            put("ts_code", tsCode)
            put("freq", freq)
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf("ts_code", "trade_time", "open", "close", "high", "low", "vol", "amount")
    ).toRequest()
)

/**
 * 获取股票周K线数据 (weekly)
 *
 * @param tsCode 股票代码，如 600000.SH
 * @param startDate 开始日期，格式：YYYYMMDD
 * @param endDate 结束日期，格式：YYYYMMDD
 */
suspend fun TuShareApi.getWeeklyCandles(
    tsCode: String,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("weekly").carriesParam(
        mutableMapOf<String, String>().apply {
            put("ts_code", tsCode)
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf("ts_code", "trade_date", "open", "close", "high", "low", "vol", "amount")
    ).toRequest()
)

/**
 * 获取股票月K线数据 (monthly)
 *
 * @param tsCode 股票代码，如 600000.SH
 * @param startDate 开始日期，格式：YYYYMMDD
 * @param endDate 结束日期，格式：YYYYMMDD
 */
suspend fun TuShareApi.getMonthlyCandles(
    tsCode: String,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("monthly").carriesParam(
        mutableMapOf<String, String>().apply {
            put("ts_code", tsCode)
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf("ts_code", "trade_date", "open", "close", "high", "low", "vol", "amount")
    ).toRequest()
)

/**
 * 获取实时日线行情 (rt_k)
 *
 * 支持股票代码和通配符，如 6*.SH, 3*.SZ, 688*.SH
 *
 * @param tsCode 股票代码或通配符列表，多个代码用英文逗号分隔
 */
suspend fun TuShareApi.getRtDaily(
    tsCode: String
) = query(
    tushareParams.ofApi("rt_k").carriesParam(
        mutableMapOf<String, String>().apply {
            put("ts_code", tsCode)
        }
    ).requireFields(
        listOf("ts_code", "name", "pre_close", "open", "close", "high", "low", "vol", "amount")
    ).toRequest()
)

/**
 * 获取券商研究报告。
 *
 * network 层只负责把上层筛选条件映射为 Tushare 参数，不负责：
 * - CLI 默认查询窗口
 * - 股票代码归一化
 * - 输出格式或摘要裁剪
 */
suspend fun TuShareApi.getResearchReport(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null,
    reportType: String? = null,
    instCsname: String? = null,
    indName: String? = null
) = query(
    tushareParams.ofApi("research_report").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            tradeDate?.let { put("trade_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
            reportType?.let { put("report_type", it) }
            instCsname?.let { put("inst_csname", it) }
            indName?.let { put("ind_name", it) }
        }
    ).requireFields(
        listOf(
            "trade_date",
            "title",
            "abstr",
            "report_type",
            "author",
            "name",
            "ts_code",
            "inst_csname",
            "ind_name",
            "url"
        )
    ).toRequest()
)

val tushareParams: TushareParams
    get() = TushareParams()

class TushareParams {

    private var def: Def = Def()

    class Def {
        lateinit var apiName: String
        var params: Map<String, String> = emptyMap()
        var fields: List<String> = emptyList()
    }

    fun ofApi(apiName: String): TushareParams {
        def.apiName = apiName
        return this
    }

    fun carriesParam(params: Map<String, String>): TushareParams {
        def.params = params
        return this
    }

    fun requireFields(fields: List<String>): TushareParams {
        def.fields = fields
        return this
    }

    fun toRequest(): TushareRequest {
        val token = ConfigHolder.config.externalApis.tushareToken
        return TushareRequest(
            apiName = def.apiName,
            token = token,
            params = def.params,
            fields = def.fields
        )
    }
}

@Serializable
data class BaseTushare(
    @SerialName("request_id")
    val requestId: String,
    val code: String,
    val msg: String,
    private val data: TushareForm? = null
) {

    suspend fun check() = suspendCancellableCoroutine { c ->
        if (code != "0") {
            c.resumeWithException(Exception("request failed. code: $code, msg: $msg"))
            return@suspendCancellableCoroutine
        }
        c.resume(data)
    }
}

@Serializable
data class TushareForm(
    val fields: List<String>,
    val items: List<List<String?>>
) {
    fun toColumns(sortKey: String? = null): List<Column> {
        val sorted = sortKey?.let { key ->
            var keyIndex = fields.indexOf(key)
            
            // Handle common aliases for sorting
            if (keyIndex == -1 && (key == "trade_time" || key == "time")) {
                keyIndex = fields.indexOf(if (key == "trade_time") "time" else "trade_time")
            }

            if (keyIndex != -1) {
                items.sortedBy { item: List<String?> -> item[keyIndex] }
            } else {
                items
            }
        } ?: items
        return sorted.map { Column(fields = fields, items = it) }
    }
}

data class Column(
    val fields: List<String>,
    val items: List<String?>
) {

    infix fun provides(key: String): String {
        val index = fields.indexOf(key)
        if (index != -1 && index < items.size) {
            return items[index] ?: ""
        }

        // Handle common aliases for data access
        if (key == "trade_time" || key == "time") {
            val altKey = if (key == "trade_time") "time" else "trade_time"
            val altIndex = fields.indexOf(altKey)
            if (altIndex != -1 && altIndex < items.size) {
                return items[altIndex] ?: ""
            }
        }

        return ""
    }
}
