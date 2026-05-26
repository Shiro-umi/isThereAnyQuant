package org.shiroumi.database.strategy.daily.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import org.shiroumi.strategy.core.sentiment.restoreSentimentDerivedFields

class DailyMarketSentimentRepositoryTest {

    @Test
    fun `failed sentiment snapshot keeps zero derived fields when restored`() {
        val fields = restoreSentimentDerivedFields(
            bullRatio = 0.4,
            volZ = 0.0,
            accelZ = 0.0,
            sufficientHistory = false,
            reason = "共同交易日不足"
        )

        assertEquals(0.0, fields.ratioNorm)
        assertEquals(0.0, fields.volScore)
        assertEquals(0.0, fields.accelScore)
        assertEquals(0.0, fields.absoluteFloor)
        assertEquals(0.0, fields.volCap)
    }
}
