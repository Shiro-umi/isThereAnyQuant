package org.shiroumi.database

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.shiroumi.configs.BuildConfigs
import org.shiroumi.network.tushare
import retrofit2.http.Body
import retrofit2.http.POST


val tushare: TuShareApi by tushare()

interface TuShareApi {

    @POST("/")
    suspend fun query(@Body body: RequestBody?): String
}

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
        val params = mutableMapOf(
            "api_name" to def.apiName,
            "token" to def.token,
        ).apply { putAll(params) }
        return Json.encodeToString(params).toRequestBody(contentType = "application/json".toMediaType())
    }
}

internal val params: MutableMap<String, String>
    get() = mutableMapOf("token" to BuildConfigs.TUSHARE_TOKEN)

suspend fun TuShareApi.getStockBasic(params: Map<String, String>? = null): String {
    val params = tushareParams.ofApi("stock_basic").carriesParam(params ?: mapOf()).toJsonBody()
    return query(params)
}