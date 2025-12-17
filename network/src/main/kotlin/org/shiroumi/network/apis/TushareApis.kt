package org.shiroumi.network.apis

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.shiroumi.configs.BuildConfigs
import org.shiroumi.network.tushare
import retrofit2.http.Body
import retrofit2.http.POST
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


val tushare: TuShareApi by tushare()

interface TuShareApi {

    @POST("/")
    suspend fun query(@Body body: RequestBody?): BaseTushare
}

suspend fun TuShareApi.getThsHotStocks(tradeDate: String) = query(
    tushareParams.ofApi("ths_hot")
        .carriesParam(mutableMapOf<String, String>().apply {
            put("trade_date", tradeDate)
            put("market", "热股")
            put("is_new", "N")
        }).toJsonBody()
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
        ).toJsonBody()
)

suspend fun TuShareApi.getAdjFactor(tsCode: String? = null, date: String? = null) =
    query(
        tushareParams.ofApi("adj_factor").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
            }
        ).toJsonBody())

suspend fun TuShareApi.getDailyCandles(tsCode: String? = null, date: String? = null) =
    query(
        tushareParams.ofApi("daily").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
            }
        ).toJsonBody())

suspend fun TuShareApi.getDailyLimit(tsCode: String? = null, date: String? = null) =
    query(
        tushareParams.ofApi("stk_limit").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
            }
        ).toJsonBody())


suspend fun TuShareApi.getDailyInfo(tsCode: String? = null, date: String? = null) =
    query(
        tushareParams.ofApi("daily_basic").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
            }
        ).toJsonBody())

suspend fun TuShareApi.getCalendar() = query(tushareParams.ofApi("trade_cal").toJsonBody())

suspend fun TuShareApi.getIndexDaily(
    tsCode: String? = null,
    date: String? = null,
    startDate: String? = null,
    endDate: String? = null
) =
    query(
        tushareParams.ofApi("index_daily").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
                startDate?.let { put("start_date", it) }
                endDate?.let { put("end_date", it) }
            }
        ).toJsonBody())

suspend fun TuShareApi.getSwIndexClassify(src: String = "SW2021", parentCode: String? = null) =
    query(
        tushareParams.ofApi("index_classify").carriesParam(
            mutableMapOf<String, String>().apply {
                put("src", src)
                parentCode?.let { put("parent_code", it) }
            }
        ).toJsonBody())

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
        ).toJsonBody())

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
    ).toJsonBody())


val tushareParams: TushareParams
    get() = TushareParams()

class TushareParams {

    private var def: Def = Def()

    class Def {
        lateinit var apiName: String
        val token: String = BuildConfigs.TUSHARE_TOKEN
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

    fun toJsonBody(): RequestBody {
        val params = buildJsonObject {
            put("api_name", JsonPrimitive(def.apiName))
            put("token", JsonPrimitive(def.token))
            put("params", buildJsonObject {
                def.params.forEach { k, v -> put(k, JsonPrimitive(v)) }
            })
            put("fields", buildJsonArray {
                def.fields.forEach { f -> add(JsonPrimitive(f)) }
            })
        }
        return Json.encodeToString(params).toRequestBody(contentType = "application/json".toMediaType())
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
            val keyIndex = fields.indexOf(sortKey)
            items.sortedBy { item -> item[keyIndex] }
        } ?: items
        return sorted.map { Column(fields = fields, items = it) }
    }
}

data class Column(
    val fields: List<String>,
    val items: List<String?>
) {

    infix fun provides(key: String) = items[fields.indexOf(key)] ?: ""
}