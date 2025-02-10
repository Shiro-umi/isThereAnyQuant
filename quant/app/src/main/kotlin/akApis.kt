package org.shiroumi

import org.shiroumi.model.network.StockData
import org.shiroumi.model.network.Symbol
import org.shiroumi.model.network.TradingDate
import org.shiroumi.network.api
import retrofit2.http.GET
import retrofit2.http.Query

val akApi: AkApi by api()

// AkShare api def
interface AkApi {
    @GET("tool_trade_date_hist_sina")
    suspend fun getTradingDate(): List<TradingDate>

    // sh sz bj code-name
    @GET("stock_info_a_code_name")
    suspend fun getStockSymbol(): List<Symbol>

    // sh sz bj history candles<daily>
    @GET("stock_zh_a_hist")
    suspend fun getStockHist(
        @Query("symbol") symbol: String,
        @Query("period")  period: String = "daily", // 'daily', 'weekly', 'monthly'
        @Query("start_date") start: String = "start_date", // eg: 20250101
        @Query("end_date") end: String = "end_date",
        @Query("adjust") limit: String = "hfq"
    ): List<StockData>
}