package org.shiroumi

import org.shiroumi.database.str
import org.shiroumi.database.today
import org.shiroumi.generated.dataclass.Candle
import org.shiroumi.generated.dataclass.Symbol
import org.shiroumi.generated.dataclass.TradingDate
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
        @Query("period") period: String = "daily", // 'daily', 'weekly', 'monthly'
        @Query("start_date") start: String? = "20000001", // eg: 20250101
        @Query("end_date") end: String? = today.str,
        @Query("adjust") limit: String = "hfq"
    ): List<Candle>
}