package org.shiroumi.strategy.core.daily.preprocessing

import model.Candle
import model.PriceBasis
import org.shiroumi.quant_kmp.strategy.daily.model.PreparedBar

object PreparedBarFactory {
    fun fromCandle(
        candle: Candle,
        normalizedFirstAdj: Float,
        signalBasis: PriceBasis,
        executionBasis: PriceBasis,
    ): PreparedBar {
        val hfqFactor = (candle.adj / normalizedFirstAdj).toDouble().takeIf { it > 0.0 } ?: 1.0
        return PreparedBar(
            tsCode = candle.tsCode,
            date = candle.date,
            signalBasis = signalBasis,
            executionBasis = executionBasis,
            open = candle.openPrice(signalBasis, hfqFactor),
            high = candle.highPrice(signalBasis, hfqFactor),
            low = candle.lowPrice(signalBasis, hfqFactor),
            close = candle.price(signalBasis, hfqFactor),
            volume = candle.volumeValue(signalBasis, hfqFactor),
            executionOpen = candle.openPrice(executionBasis, hfqFactor),
            executionClose = candle.price(executionBasis, hfqFactor),
            rawOpen = candle.openPrice(PriceBasis.RAW),
            rawHigh = candle.highPrice(PriceBasis.RAW),
            rawLow = candle.lowPrice(PriceBasis.RAW),
            rawClose = candle.price(PriceBasis.RAW),
            rawVolume = candle.volumeValue(PriceBasis.RAW),
            qfqOpen = candle.openPrice(PriceBasis.QFQ),
            qfqHigh = candle.highPrice(PriceBasis.QFQ),
            qfqLow = candle.lowPrice(PriceBasis.QFQ),
            qfqClose = candle.price(PriceBasis.QFQ),
            qfqVolume = candle.volumeValue(PriceBasis.QFQ),
            hfqFactor = hfqFactor,
        )
    }
}
