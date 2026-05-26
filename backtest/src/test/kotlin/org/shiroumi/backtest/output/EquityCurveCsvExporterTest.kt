package org.shiroumi.backtest.output

import java.nio.file.Files
import org.shiroumi.backtest.domain.Money
import org.shiroumi.backtest.testing.T1
import kotlin.test.Test
import kotlin.test.assertTrue

class EquityCurveCsvExporterTest {

    @Test fun `导出 equityCurve CSV`() {
        val path = Files.createTempDirectory("backtest-equity").resolve("equity.csv")

        EquityCurveCsvExporter.export(
            points = listOf(
                EquityPoint(
                    tradeDate = T1,
                    cash = Money.ofYuan(9_000),
                    positionValue = Money.ofYuan(1_100),
                    equity = Money.ofYuan(10_100),
                )
            ),
            path = path,
        )

        val content = Files.readString(path)
        assertTrue(content.contains("trade_date,cash,position_value,equity"))
        assertTrue(content.contains("2024-01-03,9000.0,1100.0,10100.0"))
    }
}
