@file:OptIn(ExperimentalUuidApi::class)

package org.shiroumi.quant_kmp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi


@Serializable
data class StrategyModel(
    val baseInfo: BasicInfo,
    val keyPaSignal: KeyPaSignal?,
    val tradeStrategy: TradeStrategy,
    val attentionAndRisk: List<AttentionAndRisk>,
    val summarise: Summarise,
)

@Serializable
data class BasicInfo(
    val name: String,
    val date: String,
    val periodVolatility: String,
    val description: String
)

@Serializable
data class KeyPaSignal(
    val overview: CycleOverview,
    val area: Area,
    val signal: Signal,
    val description: String,
)

@Serializable
data class TradeStrategy(
    val riskUnit: RiskUnit,
    val riskRewardRatio: RiskRewardRatio?,
    val strategy: List<Strategy>,
)

@Serializable
data class CycleOverview(
    val `class`: List<CycleOverviewItem>,
    val description: String
)

@Serializable
data class CycleOverviewItem(
    val cycle: String,
    val value: String
)

@Serializable
data class Area(
    val highProbArea: HighProbArea,
    @SerialName("s_r_area")
    val srArea: SrArea,
    val description: String
)

@Serializable
data class HighProbArea(
    val high: Float,
    val low: Float
)

@Serializable
data class SrArea(
    val s: Float,
    val r: Float
)

@Serializable
data class Signal(
    val signalCandle: String,
    val candleClassScore: Float,
    val description: String
)

@Serializable
data class RiskUnit(
    val value: String?,
    val reason: String
)

@Serializable
data class RiskRewardRatio(
    val value: String?,
    val reason: String
)

@Serializable
data class Strategy(
    val buy: BuyStrategy,
    val takeProfit: TakeProfitStrategy,
    val stopLoss: StopLossStrategy,
)

@Serializable
data class BuyStrategy(
    val trigger: String,
    val position: Float,
    val reason: String
)

@Serializable
data class TakeProfitStrategy(
    val trigger: String,
    val position: Float,
    val expectProfit: String,
    val reason: String
)

@Serializable
data class StopLossStrategy(
    val trigger: String,
    val expectLoss: String,
    val reason: String
)

@Serializable
data class AttentionAndRisk(
    val keyword: String,
    val description: String,
)

@Serializable
data class Summarise(
    val riskProfitRatio: String,
    val finalScore: Float,
    val tradingAdvice: String,
)