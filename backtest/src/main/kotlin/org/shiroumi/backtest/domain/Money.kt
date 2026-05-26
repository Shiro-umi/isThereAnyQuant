package org.shiroumi.backtest.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 资金额（人民币元，精度到分）。
 *
 * 为什么不用 [Double]：
 *  - A 股回测涉及大量加减乘除，浮点累积误差会被放大到「权益曲线肉眼可见漂移」级别；
 *  - 价格、费用、佣金最低收 5 元等边界条件都需要严格小数处理。
 *
 * 实现选择：内部以 [BigDecimal] 表达，scale=4（保留 0.0001 元）。
 * 对外提供加减乘除、与 [Long] 股数运算、按方向取整等基本运算。
 *
 * 不支持负值场景由调用方约束；本类型允许负值以便表达"现金流出"。
 */
@Serializable(with = MoneySerializer::class)
class Money private constructor(val value: BigDecimal) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(value.add(other.value).setScale(SCALE, ROUND))
    operator fun minus(other: Money): Money = Money(value.subtract(other.value).setScale(SCALE, ROUND))
    operator fun times(multiplier: Long): Money = Money(value.multiply(BigDecimal.valueOf(multiplier)).setScale(SCALE, ROUND))
    operator fun times(multiplier: Double): Money = Money(value.multiply(BigDecimal.valueOf(multiplier)).setScale(SCALE, ROUND))
    operator fun div(divisor: Long): Money {
        require(divisor != 0L) { "Money 除法的除数不能为 0" }
        return Money(value.divide(BigDecimal.valueOf(divisor), SCALE, ROUND))
    }
    operator fun div(divisor: Double): Money {
        require(divisor != 0.0) { "Money 除法的除数不能为 0" }
        return Money(value.divide(BigDecimal.valueOf(divisor), SCALE, ROUND))
    }

    operator fun unaryMinus(): Money = Money(value.negate())

    override fun compareTo(other: Money): Int = value.compareTo(other.value)
    override fun equals(other: Any?): Boolean = other is Money && this.value.compareTo(other.value) == 0
    override fun hashCode(): Int = value.stripTrailingZeros().hashCode()
    override fun toString(): String = value.toPlainString()

    /** 取出"元"为单位的 Double 表示，仅用于展示与回归计算，不可用于核算。 */
    fun toDouble(): Double = value.toDouble()

    /** 是否为零（用 BigDecimal.compareTo，避免 scale 差异导致的不等）。 */
    val isZero: Boolean get() = value.signum() == 0

    /** 是否为正。 */
    val isPositive: Boolean get() = value.signum() > 0

    /** 是否为负。 */
    val isNegative: Boolean get() = value.signum() < 0

    companion object {
        const val SCALE: Int = 4
        val ROUND: RoundingMode = RoundingMode.HALF_UP

        val ZERO: Money = Money(BigDecimal.ZERO.setScale(SCALE, ROUND))

        fun ofYuan(yuan: BigDecimal): Money = Money(yuan.setScale(SCALE, ROUND))
        fun ofYuan(yuan: Double): Money = Money(BigDecimal.valueOf(yuan).setScale(SCALE, ROUND))
        fun ofYuan(yuan: Long): Money = Money(BigDecimal.valueOf(yuan).setScale(SCALE, ROUND))
        fun ofYuan(yuan: Int): Money = ofYuan(yuan.toLong())

        /** 价格（元/股）× 数量（股）→ 资金额。专为撮合金额折算设计。 */
        fun ofTrade(priceYuanPerShare: Double, quantityShares: Long): Money {
            val amount = BigDecimal.valueOf(priceYuanPerShare).multiply(BigDecimal.valueOf(quantityShares))
            return Money(amount.setScale(SCALE, ROUND))
        }
    }
}

internal object MoneySerializer : KSerializer<Money> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Money) {
        encoder.encodeString(value.toString())
    }
    override fun deserialize(decoder: Decoder): Money {
        return Money.ofYuan(BigDecimal(decoder.decodeString()))
    }
}
