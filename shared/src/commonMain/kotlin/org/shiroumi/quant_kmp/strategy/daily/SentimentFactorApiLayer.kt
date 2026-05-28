package org.shiroumi.quant_kmp.strategy.daily

import kotlinx.datetime.LocalDate
import org.shiroumi.quant_kmp.strategy.daily.model.SentimentFactorSnapshot

/**
 * 市场情绪因子数据访问 —— 遵循项目 Provider ← Snapshot → ApiLayer 三层架构。
 *
 * 对应 Candle Data Chain 中的 ApiLayer 角色（参见 [CandleApiLayer]）：
 * 负责从底层存储读取因子数据，对上层提供统一的快照访问。
 *
 * 研究环境: [DbSentimentFactorApiLayer] — 从 sentiment_factor_daily 表读取
 * 生产环境: [SentimentFactorSnapshotStore] — 从内存缓存读取（盘后刷新）
 *
 * 两个实现对外暴露完全一致的接口，确保 Study / StateClassifier 计算逻辑零改动。
 */
interface SentimentFactorApiLayer {
    /**
     * 获取单日快照。
     * 生产环境中为纯内存读取（O(1)），盘中可高频调用。
     */
    suspend fun snapshot(tradeDate: LocalDate): SentimentFactorSnapshot?

    /**
     * 获取区间历史快照，按 tradeDate 升序排列。
     * 研究环境需要 ≥500 天窗口做预处理和 walk-forward。
     */
    suspend fun history(startDate: LocalDate, endDate: LocalDate): List<SentimentFactorSnapshot>

    /**
     * 最新可用交易日。
     */
    suspend fun latestTradeDate(): LocalDate?
}
