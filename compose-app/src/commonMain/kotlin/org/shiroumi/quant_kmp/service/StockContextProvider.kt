package org.shiroumi.quant_kmp.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import model.candle.StockInfo

/**
 * 全局股票上下文管理器（单例）
 *
 * 用于在不同模块间传递当前选中的股票信息。
 * - CandleViewModel 在用户选中股票时调用 [updateSelectedStock]
 * - AgentSidebarContent 通过 [selectedStock] 订阅当前选中的股票
 *
 * 设计原因：
 * CandleViewModel 在 CandleScreen 内部创建，生命周期局限于 Candle 页面；
 * AgentSidebar 在 Navigation 层，两者没有直接的父子关系。
 * 使用全局单例是最简洁且不破坏现有架构的方案。
 */
object StockContextProvider {

    private val _selectedStock = MutableStateFlow<StockInfo?>(null)

    /**
     * 当前选中的股票信息
     *
     * - 值为 null 表示当前尚未建立任何股票上下文
     * - 值为 StockInfo 表示最近一次选中的股票信息
     *
     * 注：是否在当前界面展示该上下文，由上层路由决定；
     * 这里保留最近一次选择，便于跨 tab 返回时恢复。
     */
    val selectedStock: StateFlow<StockInfo?> = _selectedStock.asStateFlow()

    /**
     * 更新当前选中的股票
     *
     * 由 CandleViewModel 在用户选择股票时调用
     *
     * @param stock 新选中的股票，null 表示清除选择
     */
    fun updateSelectedStock(stock: StockInfo?) {
        _selectedStock.value = stock
    }

    /**
     * 清除当前选中的股票上下文
     *
     * 通常在 CandleViewModel 被清理时调用
     */
    fun clearContext() {
        _selectedStock.value = null
    }
}
