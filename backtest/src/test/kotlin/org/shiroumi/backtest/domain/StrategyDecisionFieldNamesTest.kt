package org.shiroumi.backtest.domain

import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 守护边界：策略侧产出的 [StrategyDecision] 任意子类型字段中，**禁止出现账户相关命名**。
 *
 * 对齐设计文档 §1.5：策略零账户感知。任何 quantity / cash / position /
 * availableQty / availableCash 类字段一旦出现，意味着策略可以借此感知账户状态，
 * 破坏「同策略 × 不同初始资金 → 相同权重序列」的硬不变量。
 */
class StrategyDecisionFieldNamesTest {

    private val forbiddenSubstrings = listOf(
        "quantity",
        "cash",
        "position",
        "availableqty",
        "availablecash",
        "currentqty",
        "currentposition",
        "ledger",
        "equity",
        "lot",
    )

    @Test
    fun `策略决策类型不得携带账户字段`() {
        val subTypes = StrategyDecision::class.sealedSubclasses
        assertTrue(subTypes.isNotEmpty(), "StrategyDecision 应有至少一个子类型")

        val violations = mutableListOf<String>()
        for (sub in subTypes) {
            for (prop in sub.memberProperties) {
                val name = prop.name.lowercase()
                forbiddenSubstrings.firstOrNull { it in name }?.let { hit ->
                    violations += "${sub.simpleName}.${prop.name} 命中禁止字段子串『$hit』"
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail("StrategyDecision 暴露了账户字段：\n - " + violations.joinToString("\n - "))
        }
    }
}
