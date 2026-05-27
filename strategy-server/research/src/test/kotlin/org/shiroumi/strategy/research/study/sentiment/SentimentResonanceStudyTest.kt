package org.shiroumi.strategy.research.study.sentiment

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.shiroumi.database.sentiment.SentimentFactorDailyRecord
import org.shiroumi.strategy.research.eval.ResonanceEvaluator
import org.shiroumi.strategy.research.pipeline.ResearchContext
import kotlin.io.path.Path
import kotlin.math.PI
import kotlin.math.sin

class SentimentResonanceStudyTest {
    @Test
    fun `study emits uncensored metrics and evaluation remains external`() {
        val records = syntheticRecords()
        val ctx = ResearchContext(
            startDate = records.first().tradeDate,
            endDate = records.last().tradeDate,
            workspace = Path("build/test-resonance-study"),
            params = mapOf(
                "factors" to "A1",
                "targets" to "Y1",
                "horizons" to "3",
                "bands" to "F2a",
            ),
        )

        val metrics = SentimentResonanceStudy().runRecords(ctx, records)

        assertEquals(1, metrics.size)
        val metric = metrics.single()
        assertEquals("A1", metric.identity.factor_i)
        assertEquals("Y1", metric.identity.target_y)
        assertEquals(3, metric.identity.horizon)
        assertEquals("F2a", metric.identity.band)
        assertNotNull(metric.mean_coherence)
        assertNotNull(metric.oos_ic)
        assertNotNull(metric.q_value)
        assertTrue(metric.sample_count!! >= 30)

        val verdict = ResonanceEvaluator.evaluate(metric)
        val card = SentimentEvaluation().run(ctx, metrics).single()
        assertEquals(verdict.qualified, card.qualified)
        assertEquals(verdict.conclusionLevel, card.conclusion_level)
    }

    private fun syntheticRecords(): List<SentimentFactorDailyRecord> {
        val start = LocalDate.parse("2024-01-02")
        return (0 until 180).map { index ->
            val x = sin(2.0 * PI * index / 6.0)
            val y = sin(2.0 * PI * (index - 3) / 6.0)
            SentimentFactorDailyRecord(
                tradeDate = LocalDate.fromEpochDays(start.toEpochDays() + index),
                factors = mapOf("A1" to x),
                y1Raw = y,
                y2Raw = y,
                y3Raw = y,
                yComposite = y,
            )
        }
    }
}
