package org.shiroumi.quant_kmp.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import org.shiroumi.quant_kmp.NavDest

class NavigationStateSerializationTest {

    @Test
    fun allTopLevelDestinationsCanRoundTrip() {
        val routes = listOf(
            NavDest.Candle(),
            NavDest.Sentiment,
            NavDest.PositionTracking,
            NavDest.AgentResults(),
            NavDest.Settings
        )

        routes.forEach { route ->
            assertEquals(route, decodeNavDest(encodeNavDest(route)))
        }
    }
}
