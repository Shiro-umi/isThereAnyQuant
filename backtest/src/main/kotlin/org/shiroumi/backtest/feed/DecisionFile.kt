package org.shiroumi.backtest.feed

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.shiroumi.backtest.domain.StrategyDecision

/**
 * 本地文件模式下「策略决策」的稳定序列化结构。
 *
 * 对齐 docs/architecture/backtest-engine-design.md §11.3：
 *  - 文件命名 `{executionDate}.json`（执行日，即 T+1 开盘日）
 *  - `formatVersion` 控制反序列化分派，当前固定 1
 *  - `decisions` 直接复用 [StrategyDecision] 多态序列化（`type` 作为判别字段）
 *
 * 策略 schema 变更只会影响 [DecisionFileExporter] 内的 DB 映射逻辑，
 * 本文件结构保持向前兼容；如果确需破坏性升级，再递增 [formatVersion]。
 */
@Serializable
data class DecisionFile(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val executionDate: LocalDate,
    val decisions: List<StrategyDecision>,
) {
    companion object {
        /** 当前文件格式版本。 */
        const val CURRENT_FORMAT_VERSION: Int = 1
    }
}

/**
 * DecisionFile 专用 Json 实例。
 *
 * 与 [StrategyDecision] 的 sealed 多态序列化对齐：使用 `type` 作为判别字段，
 * 容忍未来新增的未知字段，便于跨版本读写。
 */
val DecisionFileJson: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    prettyPrint = true
    serializersModule = SerializersModule {
        polymorphic(StrategyDecision::class) {
            subclass(StrategyDecision.TargetPortfolioDecision::class)
            subclass(StrategyDecision.TradeIntentDecision::class)
        }
    }
}
