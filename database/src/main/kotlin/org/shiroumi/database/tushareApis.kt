package org.shiroumi.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.shiroumi.configs.BuildConfigs
import org.shiroumi.network.BaseTushare
import org.shiroumi.network.tushare
import retrofit2.http.Body
import retrofit2.http.POST


val tushare: TuShareApi by tushare()

interface TuShareApi {

    @POST("/")
    suspend fun query(@Body body: RequestBody?): BaseTushare
}

suspend fun TuShareApi.getStockBasic() = query(tushareParams.ofApi("stock_basic").toJsonBody())

suspend fun TuShareApi.getAdjFactor(tsCode: String) =
    query(tushareParams.ofApi("adj_factor").carriesParam(mapOf("ts_code" to tsCode)).toJsonBody())

suspend fun TuShareApi.getDailyCandles(tsCode: String) =
    query(tushareParams.ofApi("daily").carriesParam(mapOf("ts_code" to tsCode)).toJsonBody())

val tushareParams: TushareParams
    get() = TushareParams()

class TushareParams {

    private var def: Def = Def()

    class Def {
        lateinit var apiName: String
        val token: String = BuildConfigs.TUSHARE_TOKEN
        var params: Map<String, String> = emptyMap()
    }

    fun ofApi(apiName: String): TushareParams {
        def.apiName = apiName
        return this
    }

    fun carriesParam(params: Map<String, String>): TushareParams {
        def.params = params
        return this
    }

    fun toJsonBody(): RequestBody {
        val params = buildJsonObject {
            put("api_name", JsonPrimitive(def.apiName))
            put("token", JsonPrimitive(def.token))
            put("params", buildJsonObject {
                def.params.forEach { k, v -> put(k, JsonPrimitive(v)) }
            })
        }
        return Json.encodeToString(params).toRequestBody(contentType = "application/json".toMediaType())
    }
}