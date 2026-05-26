package org.shiroumi.strategy.core.daily.seed

import model.dataprovider.SentimentRuntimeSeed
import model.dataprovider.SentimentSymbolStateSeed
import org.shiroumi.strategy.core.daily.MarketSentimentRollingState
import org.shiroumi.strategy.core.daily.SymbolSentimentState

internal fun SymbolSentimentState.toSeed(): SentimentSymbolStateSeed = SentimentSymbolStateSeed(
    tsCode = tsCode,
    emaShort = emaShort,
    emaLong = emaLong,
    prevClose = prevClose,
    recentReturns = recentReturns,
    nextReturnIndex = nextReturnIndex,
    returnWindowSize = returnWindowSize,
    returnSum = returnSum,
    returnSumSq = returnSumSq
)

internal fun SentimentSymbolStateSeed.toDomain(): SymbolSentimentState = SymbolSentimentState(
    tsCode = tsCode,
    emaShort = emaShort,
    emaLong = emaLong,
    prevClose = prevClose,
    recentReturns = recentReturns,
    nextReturnIndex = nextReturnIndex,
    returnWindowSize = returnWindowSize,
    returnSum = returnSum,
    returnSumSq = returnSumSq
)

fun MarketSentimentRollingState.toRuntimeSeed(
    scope: String,
    forTradeDate: kotlinx.datetime.LocalDate,
    requiredHistory: Int
): SentimentRuntimeSeed = SentimentRuntimeSeed(
    scope = scope,
    forTradeDate = forTradeDate,
    sourceTradeDate = tradeDate,
    signalBasis = signalBasis,
    requiredHistory = requiredHistory,
    sampleSize = sampleCodes.size,
    sampleCodes = sampleCodes,
    symbolStates = symbolStates.map { it.toSeed() },
    bullRatioHistory = bullRatioHistory,
    marketVolHistory = marketVolHistory,
    accelHistory = accelHistory,
    combinedHistory = combinedHistory,
    totalDays = totalDays
)

fun SentimentRuntimeSeed.toRollingState(): MarketSentimentRollingState = MarketSentimentRollingState(
    tradeDate = sourceTradeDate,
    signalBasis = signalBasis,
    sampleCodes = sampleCodes,
    symbolStates = symbolStates.map { it.toDomain() },
    bullRatioHistory = bullRatioHistory,
    marketVolHistory = marketVolHistory,
    accelHistory = accelHistory,
    combinedHistory = combinedHistory,
    totalDays = totalDays
)
