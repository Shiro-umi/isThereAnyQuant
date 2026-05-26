package org.shiroumi.server.repository

import org.shiroumi.server.dto.StockListRequest
import org.shiroumi.server.dto.StockListResponse
import org.shiroumi.server.dto.StockSuggestion

/**
 * 股票数据仓库接口
 */
interface StockRepository {

    /**
     * 获取股票列表
     *
     * @param request 股票列表查询请求
     * @return 股票列表响应
     */
    suspend fun getStocks(request: StockListRequest): StockListResponse

    /**
     * 搜索股票建议（用于自动补全）
     *
     * @param query 搜索关键词
     * @param limit 返回数量限制
     * @return 股票建议列表
     */
    suspend fun searchStocks(query: String, limit: Int): List<StockSuggestion>

    /**
     * 根据代码获取股票详情
     *
     * @param code 股票代码
     * @return 股票信息DTO，如果不存在返回null
     */
    suspend fun getStockByCode(code: String): org.shiroumi.server.dto.StockInfo?

    /**
     * 根据代码和日期范围获取股票日K线数据
     *
     * @param code 股票代码
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return K线数据列表
     */
    suspend fun getStockCandles(
        code: String,
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate
    ): List<model.Candle>
}
