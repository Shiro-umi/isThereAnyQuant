package org.shiroumi.quant_kmp.data.candle

import kotlinx.datetime.LocalDate
import model.candle.CandleChartData
import model.candle.CandlePeriod
import model.candle.Exchange
import model.candle.StockInfo
import model.candle.StockListResponse
import model.candle.StrategySentimentResponse

/**
 * K 线页面仍需保留的 HTTP 只读仓库接口。
 *
 * 这里已经不再承担 K 线主读链路职责。
 * 当前仅保留：
 * - 股票列表/搜索
 * - 历史情绪曲线
 *
 * 策略选股列表不走 HTTP 仓库，统一消费 `STRATEGY_POSITIONS.nextSessionSelections`。
 */
interface CandleRepository {

    /**
     * 获取股票列表（分页）
     *
     * @param page 页码，从1开始
     * @param pageSize 每页数量
     * @param exchange 交易所筛选，null表示全部
     * @return 股票列表响应结果
     */
    suspend fun getStocks(
        page: Int = 1,
        pageSize: Int = 20,
        exchange: Exchange? = null,
        search: String? = null
    ): Result<StockListResponse>

    /**
     * 搜索股票
     *
     * @param query 搜索关键词（股票代码或名称）
     * @return 匹配的股票信息列表结果
     */
    suspend fun searchStocks(query: String): Result<List<StockInfo>>

    /**
     * 根据代码获取单只股票详情。
     *
     * 主要用于补齐策略选股股票，避免它们因为不在当前分页结果中而无法展示。
     */
    suspend fun getStockByCode(code: String): Result<StockInfo>

    /**
     * 获取最近的情绪指标曲线
     */
    suspend fun getStrategySentiment(limit: Int = 60): Result<List<StrategySentimentResponse>>

    /**
     * 获取单只股票指定日期范围的日K线数据
     */
    suspend fun getStockCandles(
        code: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<CandleChartData>
}
