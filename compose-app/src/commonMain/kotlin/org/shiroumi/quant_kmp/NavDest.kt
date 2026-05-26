package org.shiroumi.quant_kmp

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed class NavDest : NavKey {
    @Serializable
    data class Candle(val code: String? = null) : NavDest()

    @Serializable
    data object Sentiment : NavDest()

    @Serializable
    data object PositionTracking : NavDest()

    @Serializable
    data class AgentResults(val resultId: String? = null) : NavDest()

    @Serializable
    data object Settings : NavDest()
}
