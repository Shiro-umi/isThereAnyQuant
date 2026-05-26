package org.shiroumi.backtest.output

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import org.shiroumi.backtest.domain.Lot
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.domain.StockPosition

class FileBackedResultWriterTest {

    @Test fun `writePositions writeCashFlows writeLotContributions 各写一个 jsonl`() {
        val outputDir = Files.createTempDirectory("bt-writer-output")
        val writer = FileBackedResultWriter(outputDir)
        val date = LocalDate(2024, 1, 2)

        writer.writePositions(
            listOf(
                DailyPositionSnapshot(
                    tradeDate = date,
                    cash = Money.ofYuan(100_000.0),
                    equity = Money.ofYuan(123_456.0),
                    positions = listOf(
                        StockPosition(
                            tsCode = "000001.SZ",
                            lot = Lot(buyDate = date, quantity = 1000, cost = 11.5, settled = false),
                        ),
                    ),
                ),
            )
        )
        writer.writeCashFlows(
            listOf(
                CashFlow(tradeDate = date, amount = -Money.ofYuan(11_500.0), tag = CashFlowTag.BUY),
                CashFlow(tradeDate = date, amount = -Money.ofYuan(5.0), tag = CashFlowTag.COMMISSION),
            )
        )
        writer.writeLotContributions(
            listOf(
                LotContribution(
                    tsCode = "000001.SZ",
                    buyDate = date,
                    sellDate = LocalDate(2024, 1, 5),
                    holdingDays = 3,
                    pnl = Money.ofYuan(200.0),
                    returnRate = 0.0174,
                ),
            )
        )

        val positions = Files.readAllLines(outputDir.resolve(FileBackedResultWriter.POSITIONS_FILE))
        val cashFlows = Files.readAllLines(outputDir.resolve(FileBackedResultWriter.CASH_FLOWS_FILE))
        val lots = Files.readAllLines(outputDir.resolve(FileBackedResultWriter.LOT_CONTRIBUTIONS_FILE))

        assertEquals(1, positions.size)
        assertEquals(2, cashFlows.size)
        assertEquals(1, lots.size)
        assertTrue(positions[0].contains("\"tsCode\":\"000001.SZ\""))
        assertTrue(cashFlows[0].contains("\"tag\":\"BUY\""))
        assertTrue(lots[0].contains("\"holdingDays\":3"))
    }
}
