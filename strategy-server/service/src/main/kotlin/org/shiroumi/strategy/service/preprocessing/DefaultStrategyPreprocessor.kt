package org.shiroumi.strategy.service.preprocessing

import kotlinx.datetime.LocalDate
import model.Candle
import model.PriceBasis
import org.shiroumi.database.strategy.daily.repository.DefaultStrategyBarRepository
import org.shiroumi.database.strategy.daily.repository.StrategyBarRepository
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedMarketWindow
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedStockWindow
import org.shiroumi.quant_kmp.strategy.daily.preprocessing.StrategyPreprocessor
import org.shiroumi.strategy.core.daily.preprocessing.PreparedBarFactory
import utils.logger

private val logger by logger("DefaultStrategyPreprocessor")

class DefaultStrategyPreprocessor(
    private val repository: StrategyBarRepository = DefaultStrategyBarRepository,
) : StrategyPreprocessor {

    override fun prepareStockWindows(
        tsCodes: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
        requiredHistory: Int,
        signalBasis: PriceBasis,
        executionBasis: PriceBasis,
    ): Map<String, PreparedStockWindow> {
        if (tsCodes.isEmpty()) {
            logger.warning("[数据预处理] 股票列表为空，跳过处理")
            return emptyMap()
        }

        val histories = repository.getBatchStockHistory(tsCodes, startDate, endDate)
        val firstAdjMap = repository.getFirstAdjMap(tsCodes)
        if (signalBasis == PriceBasis.HFQ) {
            val missingFirstAdj = tsCodes.distinct().filterNot { firstAdjMap[it]?.let { adj -> adj > 0f } == true }
            if (missingFirstAdj.isNotEmpty()) {
                logger.warning(
                    "[数据预处理][HFQ_SAMPLE_EXCLUDED] total=${tsCodes.distinct().size}, " +
                        "firstAdjHits=${tsCodes.distinct().size - missingFirstAdj.size}, missingFirstAdj=${missingFirstAdj.size}, " +
                        "sample=${missingFirstAdj.take(20).joinToString(",")}"
                )
            } else {
                logger.info("[数据预处理][HFQ_REFERENCE_OK] total=${tsCodes.distinct().size}, firstAdjHits=${firstAdjMap.size}, missingFirstAdj=0")
            }
        }

        return tsCodes.distinct().associateWith { tsCode ->
            val candles = histories[tsCode].orEmpty()
            toPreparedStockWindow(
                tsCode = tsCode,
                candles = candles,
                firstAdj = firstAdjMap[tsCode],
                requiredHistory = requiredHistory,
                signalBasis = signalBasis,
                executionBasis = executionBasis,
            )
        }
    }

    override fun prepareMarketWindow(
        tsCodes: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
        requiredHistory: Int,
        signalBasis: PriceBasis,
    ): PreparedMarketWindow {
        if (tsCodes.isEmpty()) {
            logger.warning("[数据预处理] 市场窗口股票列表为空")
            return PreparedMarketWindow(
                symbols = emptyList(),
                signalBasis = signalBasis,
                barsBySymbol = emptyMap(),
                sufficientHistory = false,
                requiredHistory = requiredHistory,
                reason = "股票列表为空",
            )
        }

        val stockWindows = prepareStockWindows(
            tsCodes = tsCodes,
            startDate = startDate,
            endDate = endDate,
            requiredHistory = requiredHistory,
            signalBasis = signalBasis,
            executionBasis = PriceBasis.RAW,
        )

        val barsBySymbol = stockWindows
            .filterValues { it.sufficientHistory }
            .mapValues { it.value.bars }

        val insufficient = stockWindows.filterValues { !it.sufficientHistory }.keys.sorted()

        return PreparedMarketWindow(
            symbols = tsCodes.distinct(),
            signalBasis = signalBasis,
            barsBySymbol = barsBySymbol,
            sufficientHistory = insufficient.isEmpty() && barsBySymbol.isNotEmpty(),
            requiredHistory = requiredHistory,
            reason = if (insufficient.isEmpty()) null else "历史不足: ${insufficient.joinToString(",")}",
        )
    }

    private fun toPreparedStockWindow(
        tsCode: String,
        candles: List<Candle>,
        firstAdj: Float?,
        requiredHistory: Int,
        signalBasis: PriceBasis,
        executionBasis: PriceBasis,
    ): PreparedStockWindow {
        if (candles.isEmpty()) {
            return PreparedStockWindow(
                tsCode = tsCode,
                signalBasis = signalBasis,
                executionBasis = executionBasis,
                bars = emptyList(),
                sufficientHistory = false,
                requiredHistory = requiredHistory,
                reason = "无行情数据",
            )
        }

        val normalizedFirstAdj = when (signalBasis) {
            PriceBasis.HFQ -> firstAdj?.takeIf { it > 0f }
                ?: return PreparedStockWindow(
                    tsCode = tsCode,
                    signalBasis = signalBasis,
                    executionBasis = executionBasis,
                    bars = emptyList(),
                    sufficientHistory = false,
                    requiredHistory = requiredHistory,
                    reason = "缺失 firstAdj",
                )
            else -> firstAdj?.takeIf { it > 0f } ?: 1f
        }
        val bars = candles.map { candle ->
            PreparedBarFactory.fromCandle(
                candle = candle,
                normalizedFirstAdj = normalizedFirstAdj,
                signalBasis = signalBasis,
                executionBasis = executionBasis,
            )
        }

        val sufficientHistory = bars.size >= requiredHistory
        return PreparedStockWindow(
            tsCode = tsCode,
            signalBasis = signalBasis,
            executionBasis = executionBasis,
            bars = bars,
            sufficientHistory = sufficientHistory,
            requiredHistory = requiredHistory,
            reason = if (sufficientHistory) null else "历史长度不足: ${bars.size} < $requiredHistory",
        )
    }
}
