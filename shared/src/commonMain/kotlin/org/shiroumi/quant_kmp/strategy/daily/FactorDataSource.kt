package org.shiroumi.quant_kmp.strategy.daily

import kotlinx.datetime.LocalDate
import org.shiroumi.quant_kmp.strategy.daily.model.FactorSnapshot

/**
 * 因子数据源 —— 研究和生产环境共用的数据抽象。
 *
 * 研究环境: [DbFactorDataSource] — 从 sentiment_factor_daily 表读取
 * 生产环境: [SnapshotFactorDataSource] — 从内存缓存读取（盘后刷新）
 *
 * 两个实现对外暴露完全一致的接口，确保 Study 计算逻辑零改动。
 */
interface FactorDataSource {
    /**
     * 获取单日快照。
     * 生产环境中为纯内存读取（O(1)），盘中可高频调用。
     */
    suspend fun snapshot(tradeDate: LocalDate): FactorSnapshot?

    /**
     * 获取区间历史快照，按 tradeDate 升序排列。
     * 研究环境需要 ≥500 天窗口做预处理和 walk-forward。
     */
    suspend fun history(startDate: LocalDate, endDate: LocalDate): List<FactorSnapshot>

    /**
     * 最新可用交易日。
     */
    suspend fun latestTradeDate(): LocalDate?
}
