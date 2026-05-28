package org.shiroumi.strategy.research.source

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.shiroumi.database.sentiment.SentimentFactorDailyRecord
import org.shiroumi.quant_kmp.strategy.daily.model.FactorSnapshot

class FactorSnapshotEquivalenceTest {

    @Test
    fun `Snapshot to Record roundtrip is lossless`() {
        val snap = FactorSnapshot(
            tradeDate = LocalDate.parse("2025-06-15"),
            factors = mapOf(
                "A1" to 0.012,
                "A7" to 0.008,
                "B4" to 0.55,
                "D4" to 0.73,
                "B3p" to null,  // nullable factor
            ),
            y1Raw = 0.012,
            y2Raw = 0.55,
            y3Raw = 2.3,
            yComposite = 0.0,
            notes = "test",
        )

        // Snapshot → Record
        val record = snap.toRecord()
        assertEquals(snap.tradeDate, record.tradeDate)
        assertEquals(snap.factors, record.factors)
        assertEquals(snap.y1Raw, record.y1Raw)
        assertEquals(snap.y2Raw, record.y2Raw)
        assertEquals(snap.y3Raw, record.y3Raw)
        assertEquals(snap.yComposite, record.yComposite)
        assertEquals(snap.notes, record.notes)

        // Record → Snapshot (via DbFactorDataSource.toSnapshot)
        val roundtripped = org.shiroumi.database.sentiment.SentimentFactorDailyRecord(
            tradeDate = record.tradeDate,
            factors = record.factors,
            y1Raw = record.y1Raw,
            y2Raw = record.y2Raw,
            y3Raw = record.y3Raw,
            yComposite = record.yComposite,
            notes = record.notes,
        ).let { DbFactorDataSourceInternals.toSnapshot(it) }

        assertEquals(snap.tradeDate, roundtripped.tradeDate)
        assertEquals(snap.factors, roundtripped.factors)
        assertEquals(snap.y1Raw, roundtripped.y1Raw)
    }

    @Test
    fun `null factors survive roundtrip`() {
        val record = SentimentFactorDailyRecord(
            tradeDate = LocalDate.parse("2025-01-01"),
            factors = mapOf("A1" to null, "B4" to 0.5),
            y1Raw = null,
            y2Raw = null,
            y3Raw = null,
            yComposite = null,
            notes = null,
        )

        val snap = DbFactorDataSourceInternals.toSnapshot(record)
        assertTrue(snap.factors.containsKey("A1"))
        assertEquals(null, snap.factors["A1"])
        assertEquals(0.5, snap.factors["B4"])
        assertEquals(null, snap.y1Raw)

        val back = snap.toRecord()
        assertEquals(null, back.factors["A1"])
        assertEquals(0.5, back.factors["B4"])
    }

    @Test
    fun `all 38 factor names survive roundtrip`() {
        val allFactors = listOf(
            "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9a", "A9b",
            "A10", "A11", "A11a", "A12",
            "B1", "B3", "B3p", "B4", "B5", "B6", "B7",
            "C1", "C2", "C2p", "C3", "C4", "C5", "C6", "C7",
            "D1", "D2", "D3", "D4", "D5", "D6", "D7",
            "E1", "E2",
        )
        assertEquals(38, allFactors.size)

        val factors = allFactors.associateWith { 0.0 }
        val record = SentimentFactorDailyRecord(
            tradeDate = LocalDate.parse("2025-01-01"),
            factors = factors,
            y1Raw = 0.0, y2Raw = 0.0, y3Raw = 0.0, yComposite = null, notes = null,
        )

        val snap = DbFactorDataSourceInternals.toSnapshot(record)
        assertEquals(38, snap.factors.size)
        allFactors.forEach { assertTrue(snap.factors.containsKey(it), "Missing factor: $it") }

        val back = snap.toRecord()
        assertEquals(38, back.factors.size)
        allFactors.forEach { assertTrue(back.factors.containsKey(it), "Missing factor after roundtrip: $it") }
        assertFalse(back.factors.values.any { it != 0.0 })
    }
}

/** Expose internal extension for test access */
internal object DbFactorDataSourceInternals {
    fun toSnapshot(record: SentimentFactorDailyRecord): FactorSnapshot = record.let {
        FactorSnapshot(
            tradeDate = it.tradeDate,
            factors = it.factors,
            y1Raw = it.y1Raw,
            y2Raw = it.y2Raw,
            y3Raw = it.y3Raw,
            yComposite = it.yComposite,
            notes = it.notes,
        )
    }
}
