package org.shiroumi.backtest.feed

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.shiroumi.backtest.domain.StrategyDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 外部 EMA20 池选股源单测。
 *
 * 覆盖核心正确性：
 *  - 信号日 T → 执行日 T+1 映射（防未来函数，与 agent 买点口径对齐）
 *  - modelScore 降序保序填入 targetWeights（闸门 MODEL_SCORE 取原始顺序即 Top N）
 *  - 等权 1/N
 *  - 容忍清单未消费的额外字段
 *  - 缺失映射 / 空清单的边界处理
 */
class Ema20PoolDecisionFeedTest {

    /** 简单顺延一天的 nextTradingDay 桩，足够验证 T→T+1 映射方向。 */
    private val plusOneDay: (LocalDate) -> LocalDate? = { it.plus(1, DateTimeUnit.DAY) }

    private fun writeSelection(json: String): Path {
        val path = Files.createTempFile("ema20-pool", ".json")
        Files.writeString(path, json)
        return path
    }

    @Test fun `signalDate 映射到下一交易日作为 effectiveDate`() {
        val path = writeSelection(
            """
            {"window":"x","pool":"y","count":1,"picks":[
              {"signalDate":"2026-03-03","tsCode":"300658.SZ","modelScore":0.78,"ema20Slope10":0.15,"rank":0}
            ]}
            """.trimIndent(),
        )
        val feed = Ema20PoolDecisionFeed(path, nextTradingDay = plusOneDay)

        // 信号日 2026-03-03 → 执行日 2026-03-04
        assertTrue(feed.decisionsFor(LocalDate.parse("2026-03-03")).isEmpty(), "信号日当天不应有决策")
        val decision = feed.decisionsFor(LocalDate.parse("2026-03-04")).single()
            as StrategyDecision.TargetPortfolioDecision
        assertEquals(LocalDate.parse("2026-03-04"), decision.effectiveDate)
        assertEquals(setOf("300658.SZ"), decision.targetWeights.keys)
        Files.deleteIfExists(path)
    }

    @Test fun `targetWeights 按清单原始顺序保序且等权`() {
        val path = writeSelection(
            """
            {"picks":[
              {"signalDate":"2026-03-03","tsCode":"AAA.SZ","modelScore":0.90},
              {"signalDate":"2026-03-03","tsCode":"BBB.SZ","modelScore":0.80},
              {"signalDate":"2026-03-03","tsCode":"CCC.SZ","modelScore":0.70},
              {"signalDate":"2026-03-03","tsCode":"DDD.SZ","modelScore":0.60},
              {"signalDate":"2026-03-03","tsCode":"EEE.SZ","modelScore":0.50}
            ]}
            """.trimIndent(),
        )
        val feed = Ema20PoolDecisionFeed(path, nextTradingDay = plusOneDay)
        val decision = feed.decisionsFor(LocalDate.parse("2026-03-04")).single()
            as StrategyDecision.TargetPortfolioDecision

        // 保序：闸门 MODEL_SCORE 取原始顺序，必须严格等于清单降序
        assertEquals(
            listOf("AAA.SZ", "BBB.SZ", "CCC.SZ", "DDD.SZ", "EEE.SZ"),
            decision.targetWeights.keys.toList(),
            "targetWeights 必须保持清单 modelScore 降序原始顺序",
        )
        // 等权 1/5
        assertTrue(decision.targetWeights.values.all { kotlin.math.abs(it - 0.2) < 1e-9 }, "等权 1/N")
        assertEquals(0.0, decision.sentimentExposure, "外部池不承载情绪水位")
        Files.deleteIfExists(path)
    }

    @Test fun `多信号日各自映射到各自执行日`() {
        val path = writeSelection(
            """
            {"picks":[
              {"signalDate":"2026-03-03","tsCode":"AAA.SZ","modelScore":0.9},
              {"signalDate":"2026-03-04","tsCode":"BBB.SZ","modelScore":0.8}
            ]}
            """.trimIndent(),
        )
        val feed = Ema20PoolDecisionFeed(path, nextTradingDay = plusOneDay)

        assertEquals(
            setOf("AAA.SZ"),
            (feed.decisionsFor(LocalDate.parse("2026-03-04")).single()
                as StrategyDecision.TargetPortfolioDecision).targetWeights.keys,
        )
        assertEquals(
            setOf("BBB.SZ"),
            (feed.decisionsFor(LocalDate.parse("2026-03-05")).single()
                as StrategyDecision.TargetPortfolioDecision).targetWeights.keys,
        )
        Files.deleteIfExists(path)
    }

    @Test fun `nextTradingDay 返回 null 的信号日被跳过`() {
        val path = writeSelection(
            """
            {"picks":[{"signalDate":"2026-03-03","tsCode":"AAA.SZ","modelScore":0.9}]}
            """.trimIndent(),
        )
        // 映射全返回 null（如信号日落在日历末端，无下一交易日）
        val feed = Ema20PoolDecisionFeed(path, nextTradingDay = { null })
        assertTrue(feed.decisionsFor(LocalDate.parse("2026-03-04")).isEmpty(), "无下一交易日的信号日应被跳过，不抛异常")
        Files.deleteIfExists(path)
    }

    @Test fun `同信号日重复标的保留首个不重复计权`() {
        val path = writeSelection(
            """
            {"picks":[
              {"signalDate":"2026-03-03","tsCode":"AAA.SZ","modelScore":0.9},
              {"signalDate":"2026-03-03","tsCode":"AAA.SZ","modelScore":0.8},
              {"signalDate":"2026-03-03","tsCode":"BBB.SZ","modelScore":0.7}
            ]}
            """.trimIndent(),
        )
        val feed = Ema20PoolDecisionFeed(path, nextTradingDay = plusOneDay)
        val decision = feed.decisionsFor(LocalDate.parse("2026-03-04")).single()
            as StrategyDecision.TargetPortfolioDecision

        assertEquals(listOf("AAA.SZ", "BBB.SZ"), decision.targetWeights.keys.toList(), "重复标的去重保首个")
        // 权重分母用去重后只数（2 只各 0.5），权重和恒为 1，退化场景不破坏组合权重。
        assertTrue(decision.targetWeights.values.all { kotlin.math.abs(it - 0.5) < 1e-9 }, "去重后等权各 0.5")
        assertEquals(1.0, decision.targetWeights.values.sum(), 1e-9, "权重和恒为 1")
        Files.deleteIfExists(path)
    }
}
