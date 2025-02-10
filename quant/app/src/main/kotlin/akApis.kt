package org.shiroumi

import org.shiroumi.model.network.Symbol
import org.shiroumi.network.api
import retrofit2.http.GET

val akApi: AkApi by api()

// AkShare api def
interface AkApi {
    // 沪深京全部code-name
    @GET("stock_info_a_code_name")
    suspend fun getStockSymbol(): List<Symbol>

}