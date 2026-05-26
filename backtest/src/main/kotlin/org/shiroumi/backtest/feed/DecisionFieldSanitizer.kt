package org.shiroumi.backtest.feed

/**
 * 策略适配层字段过滤器。
 *
 * 回测只接受“策略观点”，所有账户字段即使出现在上游脏数据 metadata 中，也必须在适配时丢弃。
 */
object DecisionFieldSanitizer {
    private val accountFieldNames = setOf(
        "cash",
        "cashamount",
        "availablecash",
        "quantity",
        "currentquantity",
        "position",
        "currentposition",
        "availableqty",
    )

    fun sanitizeMetadata(metadata: Map<String, String>): Map<String, String> =
        metadata.filterKeys { key -> !isAccountField(key) }

    fun isAccountField(fieldName: String): Boolean {
        val normalized = fieldName.filter { it.isLetterOrDigit() }.lowercase()
        return normalized in accountFieldNames
    }
}
