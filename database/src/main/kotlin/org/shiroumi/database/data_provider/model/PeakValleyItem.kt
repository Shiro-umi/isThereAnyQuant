package org.shiroumi.database.data_provider.model

data class PeakValleyItem(
    val date: String,
    private val _open: Double,
    private val _close: Double,
    private val _high: Double,
    private val _low: Double,
    var smoothedPrice: Double = 0.0,
    var firstDerivative: Double = 0.0,
    var secondDerivative: Double = 0.0
) {
    val isIncrease: Boolean = _close > _open

    val value: Double
        get() = if (isIncrease) _high else _low
}

data class ExtremePoint(
    val index: Int,
    val price: Double,
    val type: ExtremeType,
    val date: String
)

enum class ExtremeType {
    PEAK, VALLEY
}