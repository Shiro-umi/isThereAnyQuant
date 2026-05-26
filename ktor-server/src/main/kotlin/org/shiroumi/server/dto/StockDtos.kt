package org.shiroumi.server.dto

import kotlinx.serialization.Serializable
import model.candle.Exchange

/**
 * 股票列表查询请求
 * GET /api/v1/stocks
 */
@Serializable
data class StockListRequest(
    val page: Int = 1,
    val pageSize: Int = 20,
    val search: String? = null,
    val exchange: Exchange? = null,
    val industry: String? = null,
    val sortBy: SortField = SortField.CODE,
    val sortOrder: SortOrder = SortOrder.ASC
)

/**
 * 排序字段
 */
@Serializable
enum class SortField {
    CODE,
    NAME,
    PRICE,
    CHANGE_PERCENT,
    VOLUME,
    MARKET_CAP,
    RANK_SCORE
}

/**
 * 排序方向
 */
@Serializable
enum class SortOrder {
    ASC,
    DESC
}

/**
 * 股票列表查询响应
 */
@Serializable
data class StockListResponse(
    val stocks: List<StockInfo>,
    val pagination: PaginationInfo
)

/**
 * 股票信息
 */
@Serializable
data class StockInfo(
    val code: String,
    val name: String,
    val exchange: Exchange,
    val industry: String,
    val sector: String,
    val latestPrice: Float,
    val changePercent: Float,
    val changeAmount: Float,
    val volume: Float,
    val turnover: Float,
    val marketCap: Float,
    val peRatio: Float? = null,
    val pbRatio: Float? = null,
    val dayHigh: Float,
    val dayLow: Float,
    val openPrice: Float,
    val prevClose: Float,
    val updateTime: Long
)

/**
 * 股票搜索建议响应
 */
@Serializable
data class StockSearchSuggestionsResponse(
    val suggestions: List<StockSuggestion>
)

/**
 * 股票搜索建议
 */
@Serializable
data class StockSuggestion(
    val code: String,
    val name: String,
    val exchange: Exchange,
    val matchType: MatchType
)

/**
 * 匹配类型
 */
@Serializable
enum class MatchType {
    CODE,       // 代码匹配
    NAME,       // 名称匹配
    PINYIN      // 拼音匹配
}
