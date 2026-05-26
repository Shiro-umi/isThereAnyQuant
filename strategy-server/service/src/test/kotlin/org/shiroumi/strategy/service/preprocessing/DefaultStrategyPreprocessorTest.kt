package org.shiroumi.strategy.service.preprocessing

import kotlinx.datetime.LocalDate
import model.Candle
import model.PriceBasis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.shiroumi.database.strategy.daily.repository.StrategyBarRepository
import kotlin.uuid.ExperimentalUuidApi

class DefaultStrategyPreprocessorTest {

    @Test
    @DisplayName("应基于 firstAdj 运行时推导 HFQ 价格")
    fun testPrepareStockWindowsWithHfq() {
        val repository = object : StrategyBarRepository {
            override fun getStockHistory(tsCode: String, startDate: LocalDate, endDate: LocalDate): List<Candle> = emptyList()

            override fun getBatchStockHistory(
                tsCodes: List<String>,
                startDate: LocalDate,
                endDate: LocalDate,
            ): Map<String, List<Candle>> = mapOf(
                "600000.SH" to listOf(
                    candle(date = LocalDate(2024, 1, 1), close = 10f, adj = 1.0f),
                    candle(date = LocalDate(2024, 1, 2), close = 12f, adj = 1.5f),
                )
            )

            override fun getFirstAdjMap(tsCodes: List<String>): Map<String, Float> = mapOf("600000.SH" to 1.0f)
        }

        val preprocessor = DefaultStrategyPreprocessor(repository)
        val windows = preprocessor.prepareStockWindows(
            tsCodes = listOf("600000.SH"),
            startDate = LocalDate(2024, 1, 1),
            endDate = LocalDate(2024, 1, 2),
            requiredHistory = 2,
            signalBasis = PriceBasis.HFQ,
            executionBasis = PriceBasis.RAW,
        )

        val bars = windows.getValue("600000.SH").bars
        assertTrue(windows.getValue("600000.SH").sufficientHistory)
        assertEquals(2, bars.size)
        assertEquals(18.0, bars.last().close, 1e-9)
        assertEquals(12.0, bars.last().executionClose, 1e-9)
        assertEquals(1.5, bars.last().hfqFactor, 1e-9)
    }

    @Test
    @DisplayName("历史不足时应显式标记 insufficient_history")
    fun testInsufficientHistoryFlag() {
        val repository = object : StrategyBarRepository {
            override fun getStockHistory(tsCode: String, startDate: LocalDate, endDate: LocalDate): List<Candle> = emptyList()
            override fun getBatchStockHistory(tsCodes: List<String>, startDate: LocalDate, endDate: LocalDate): Map<String, List<Candle>> =
                mapOf("600000.SH" to listOf(candle(date = LocalDate(2024, 1, 1), close = 10f, adj = 1.0f)))
            override fun getFirstAdjMap(tsCodes: List<String>): Map<String, Float> = mapOf("600000.SH" to 1.0f)
        }

        val preprocessor = DefaultStrategyPreprocessor(repository)
        val window = preprocessor.prepareStockWindows(
            tsCodes = listOf("600000.SH"),
            startDate = LocalDate(2024, 1, 1),
            endDate = LocalDate(2024, 1, 1),
            requiredHistory = 10,
            signalBasis = PriceBasis.HFQ,
            executionBasis = PriceBasis.RAW,
        ).getValue("600000.SH")

        assertFalse(window.sufficientHistory)
        assertTrue(window.reason?.contains("历史长度不足") == true)
    }

    @Test
    @DisplayName("HFQ 缺失 firstAdj 时应剔除样本而不是兜底为 1f")
    fun testExcludeStockWhenFirstAdjMissingInHfq() {
        val repository = object : StrategyBarRepository {
            override fun getStockHistory(tsCode: String, startDate: LocalDate, endDate: LocalDate): List<Candle> = emptyList()
            override fun getBatchStockHistory(
                tsCodes: List<String>,
                startDate: LocalDate,
                endDate: LocalDate,
            ): Map<String, List<Candle>> = mapOf(
                "600000.SH" to listOf(
                    candle(date = LocalDate(2024, 1, 1), close = 10f, adj = 2.0f),
                    candle(date = LocalDate(2024, 1, 2), close = 12f, adj = 2.5f),
                )
            )
            override fun getFirstAdjMap(tsCodes: List<String>): Map<String, Float> = emptyMap()
        }

        val preprocessor = DefaultStrategyPreprocessor(repository)
        val window = preprocessor.prepareStockWindows(
            tsCodes = listOf("600000.SH"),
            startDate = LocalDate(2024, 1, 1),
            endDate = LocalDate(2024, 1, 2),
            requiredHistory = 2,
            signalBasis = PriceBasis.HFQ,
            executionBasis = PriceBasis.RAW,
        ).getValue("600000.SH")

        assertFalse(window.sufficientHistory)
        assertTrue(window.bars.isEmpty())
        assertEquals("缺失 firstAdj", window.reason)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun candle(date: LocalDate, close: Float, adj: Float): Candle = Candle(
        tsCode = "600000.SH",
        date = date,
        open = close * 0.9f,
        high = close * 1.1f,
        low = close * 0.85f,
        close = close,
        adj = adj,
        openQfq = close * 0.8f,
        closeQfq = close * 0.8f,
        highQfq = close * 0.88f,
        lowQfq = close * 0.76f,
        volume = 1000f,
        volumeQfq = 900f,
        turnoverReal = 10000f,
        pe = 10f,
        peTtm = 10f,
        pb = 1f,
        ps = 1f,
        psTtm = 1f,
        mvTotal = 100000f,
        mvCirc = 50000f,
    )
}
