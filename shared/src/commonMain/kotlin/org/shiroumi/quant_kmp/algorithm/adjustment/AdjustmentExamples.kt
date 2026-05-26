package org.shiroumi.quant_kmp.algorithm.adjustment

import kotlinx.datetime.LocalDate

/**
 * 复权算法使用示例
 * 展示如何使用AdjustmentCalculator和AdjustmentService
 */
object AdjustmentExamples {

    /**
     * 示例1：基本的前复权计算
     */
    fun example1_BasicForwardAdjustment() {
        // 1. 准备K线数据
        val candles = listOf(
            OhlcvCandle(
                date = LocalDate(2024, 1, 1),
                open = 100.0, high = 105.0, low = 98.0,
                close = 102.0, volume = 10000.0, amount = 1020000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 2),
                open = 102.0, high = 108.0, low = 101.0,
                close = 106.0, volume = 12000.0, amount = 1272000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 1, 3),
                open = 106.0, high = 110.0, low = 104.0,
                close = 108.0, volume = 15000.0, amount = 1620000.0
            )
        )

        // 2. 定义除权除息事件（使用DSL）
        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 2), 2.0, "每股派息2元")
        }

        // 3. 执行前复权
        val result = candles.forwardAdjust(events, latestClose = 108.0)

        // 4. 使用结果
        println("前复权结果:")
        result.candles.forEach { candle ->
            println("${candle.date}: 开=${candle.open}, 收=${candle.close}, 量=${candle.volume}")
        }
    }

    /**
     * 示例2：综合除权除息事件
     */
    fun example2_CompositeAdjustment() {
        val candles = listOf(
            OhlcvCandle(
                date = LocalDate(2024, 6, 1),
                open = 50.0, high = 52.0, low = 49.0,
                close = 51.0, volume = 20000.0, amount = 1020000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 6, 2),
                open = 51.0, high = 53.0, low = 50.0,
                close = 52.0, volume = 22000.0, amount = 1144000.0
            ),
            OhlcvCandle(
                date = LocalDate(2024, 6, 3),
                open = 52.0, high = 54.0, low = 51.0,
                close = 53.0, volume = 25000.0, amount = 1325000.0
            )
        )

        // 使用综合除权除息事件（同时包含分红、送股、配股）
        val events = adjustmentEvents {
            composite(
                date = LocalDate(2024, 6, 2),
                dividend = 1.0,           // 每股派息1元
                stockSplitRatio = 0.5,    // 10送5
                rightsIssueRatio = 0.2,   // 10配2
                rightsIssuePrice = 8.0,   // 配股价8元
                description = "综合除权除息"
            )
        }

        // 执行前复权和后复权
        val forwardResult = candles.forwardAdjust(events, latestClose = 53.0)
        val backwardResult = candles.backwardAdjust(events, latestClose = 53.0)

        println("\n前复权 vs 后复权:")
        forwardResult.candles.zip(backwardResult.candles) { f, b ->
            println("${f.date}: 前复权=${f.close}, 后复权=${b.close}")
        }
    }

    /**
     * 示例3：批量处理多只股票
     */
    fun example3_BatchProcessing() {
        val service = AdjustmentService()

        // 多只股票数据
        val stockData = mapOf(
            "000001.SZ" to listOf(
                OhlcvCandle(LocalDate(2024, 1, 1), 10.0, 11.0, 9.5, 10.5, 50000.0, 525000.0),
                OhlcvCandle(LocalDate(2024, 1, 2), 10.5, 11.5, 10.0, 11.0, 55000.0, 605000.0)
            ),
            "000002.SZ" to listOf(
                OhlcvCandle(LocalDate(2024, 1, 1), 20.0, 21.0, 19.5, 20.5, 30000.0, 615000.0),
                OhlcvCandle(LocalDate(2024, 1, 2), 20.5, 21.5, 20.0, 21.0, 35000.0, 735000.0)
            )
        )

        // 每只股票的除权除息事件
        val stockEvents = mapOf(
            "000001.SZ" to adjustmentEvents {
                dividend(LocalDate(2024, 1, 2), 0.5, "分红")
            },
            "000002.SZ" to adjustmentEvents {
                stockSplit(LocalDate(2024, 1, 2), 0.3, "送股")
            }
        )

        // 批量复权
        val results = service.batchAdjust(stockData, stockEvents, AdjustmentType.FORWARD)

        results.forEach { (symbol, result) ->
            println("\n股票 $symbol:")
            result.candles.forEach { candle ->
                println("  ${candle.date}: 收盘价=${candle.close}")
            }
        }
    }

    /**
     * 示例4：计算收益率
     */
    fun example4_CalculateReturns() {
        val service = AdjustmentService()

        val candles = listOf(
            OhlcvCandle(LocalDate(2024, 1, 1), 100.0, 105.0, 98.0, 102.0, 10000.0, 1020000.0),
            OhlcvCandle(LocalDate(2024, 1, 2), 102.0, 108.0, 101.0, 106.0, 12000.0, 1272000.0),
            OhlcvCandle(LocalDate(2024, 1, 3), 106.0, 110.0, 104.0, 108.0, 15000.0, 1620000.0),
            OhlcvCandle(LocalDate(2024, 1, 4), 108.0, 112.0, 107.0, 110.0, 11000.0, 1210000.0),
            OhlcvCandle(LocalDate(2024, 1, 5), 110.0, 115.0, 109.0, 114.0, 13000.0, 1482000.0)
        )

        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "分红")
        }

        // 先进行复权
        val adjusted = candles.forwardAdjust(events, 114.0)

        // 计算收益率
        val totalReturn = service.calculateReturn(adjusted.candles, 0, 4)
        println("总收益率: ${(totalReturn * 100).roundTo(2)}%")

        // 计算累计收益率序列
        val cumulativeReturns = service.calculateCumulativeReturns(adjusted.candles)
        println("\n累计收益率:")
        adjusted.candles.zip(cumulativeReturns) { candle, ret ->
            println("  ${candle.date}: ${(ret * 100).roundTo(2)}%")
        }

        // 计算移动平均收益率
        val maReturns = service.calculateMovingAverageReturns(adjusted.candles, period = 3)
        println("\n3日移动平均收益率:")
        adjusted.candles.zip(maReturns) { candle, ret ->
            ret?.let { println("  ${candle.date}: ${(it * 100).roundTo(2)}%") }
        }
    }

    /**
     * 示例5：复权结果验证和分析
     */
    fun example5_ValidationAndAnalysis() {
        val service = AdjustmentService()

        val original = listOf(
            OhlcvCandle(LocalDate(2024, 1, 1), 100.0, 105.0, 98.0, 102.0, 10000.0, 1020000.0),
            OhlcvCandle(LocalDate(2024, 1, 2), 102.0, 108.0, 101.0, 106.0, 12000.0, 1272000.0),
            OhlcvCandle(LocalDate(2024, 1, 3), 106.0, 110.0, 104.0, 108.0, 15000.0, 1620000.0),
            OhlcvCandle(LocalDate(2024, 1, 4), 108.0, 112.0, 107.0, 110.0, 11000.0, 1210000.0),
            OhlcvCandle(LocalDate(2024, 1, 5), 110.0, 115.0, 109.0, 114.0, 13000.0, 1482000.0)
        )

        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "分红")
            stockSplit(LocalDate(2024, 1, 4), 0.5, "送股")
        }

        val result = original.forwardAdjust(events, 114.0)

        // 1. 验证复权结果
        val validation = service.validateAdjustment(result.candles)
        println("验证结果: ${if (validation.isValid) "通过" else "失败"}")
        if (!validation.isValid) {
            println("错误: ${validation.errors}")
        }

        // 2. 生成复权报告
        val report = AdjustmentAnalyzer.generateReport(result, original)
        println("\n复权报告:")
        println("  复权类型: ${report.adjustmentType}")
        println("  K线数量: ${report.totalCandles}")
        println("  除权除息事件: ${report.adjustmentEvents}")
        println("  异常数量: ${report.anomalyCount}")

        // 3. 分析复权影响
        println("\n复权影响分析:")
        println("  平均影响: ${(report.impactAnalysis.averageImpact * 100).roundTo(2)}%")
        println("  最大影响: ${(report.impactAnalysis.maxImpact * 100).roundTo(2)}%")
        println("  最小影响: ${(report.impactAnalysis.minImpact * 100).roundTo(2)}%")

        // 4. 价格对比
        println("\n价格对比:")
        report.priceComparisons.take(3).forEach { comp ->
            println("  ${comp.date}: ${comp.originalClose} -> ${comp.adjustedClose} (${(comp.changeRatio * 100).roundTo(2)}%)")
        }
    }

    /**
     * 示例6：自定义AdjustableCandle实现
     */
    fun example6_CustomCandleImplementation() {
        // 定义自定义K线数据类
        data class CustomStockCandle(
            override val date: LocalDate,
            override val open: Double,
            override val high: Double,
            override val low: Double,
            override val close: Double,
            override val volume: Double,
            override val amount: Double,
            val symbol: String,
            val marketCap: Double
        ) : AdjustableCandle {
            override fun withAdjustedPrices(
                open: Double,
                high: Double,
                low: Double,
                close: Double,
                volume: Double,
                amount: Double
            ): AdjustableCandle = copy(
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume,
                amount = amount
            )
        }

        // 使用自定义K线
        val customCandles = listOf(
            CustomStockCandle(
                date = LocalDate(2024, 1, 1),
                open = 100.0, high = 105.0, low = 98.0,
                close = 102.0, volume = 10000.0, amount = 1020000.0,
                symbol = "TEST", marketCap = 1000000000.0
            ),
            CustomStockCandle(
                date = LocalDate(2024, 1, 2),
                open = 102.0, high = 108.0, low = 101.0,
                close = 106.0, volume = 12000.0, amount = 1272000.0,
                symbol = "TEST", marketCap = 1000000000.0
            )
        )

        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 2), 2.0, "分红")
        }

        val result = customCandles.forwardAdjust(events, 106.0)

        println("\n自定义K线复权结果:")
        result.candles.forEach { candle ->
            if (candle is CustomStockCandle) {
                println("  ${candle.date}: 收=${candle.close}, 市值=${candle.marketCap}, 代码=${candle.symbol}")
            }
        }
    }

    /**
     * 示例7：复权因子序列计算（用于图表展示）
     */
    fun example7_FactorSeriesForChart() {
        val service = AdjustmentService()

        val candles = listOf(
            OhlcvCandle(LocalDate(2024, 1, 1), 100.0, 105.0, 98.0, 102.0, 10000.0, 1020000.0),
            OhlcvCandle(LocalDate(2024, 1, 2), 102.0, 108.0, 101.0, 106.0, 12000.0, 1272000.0),
            OhlcvCandle(LocalDate(2024, 1, 3), 106.0, 110.0, 104.0, 108.0, 15000.0, 1620000.0),
            OhlcvCandle(LocalDate(2024, 1, 4), 108.0, 112.0, 107.0, 110.0, 11000.0, 1210000.0),
            OhlcvCandle(LocalDate(2024, 1, 5), 110.0, 115.0, 109.0, 114.0, 13000.0, 1482000.0)
        )

        val events = adjustmentEvents {
            dividend(LocalDate(2024, 1, 3), 2.0, "分红")
        }

        val result = candles.forwardAdjust(events, 114.0)

        // 计算复权因子序列（用于在图表上显示复权影响）
        val factorSeries = service.calculateFactorSeries(
            candles,
            result.factors,
            AdjustmentType.FORWARD
        )

        println("\n复权因子序列:")
        candles.zip(factorSeries) { candle, factor ->
            println("  ${candle.date}: 复权因子=${factor.roundTo(4)}")
        }
    }

    /**
     * 运行所有示例
     */
    fun runAllExamples() {
        println("=".repeat(60))
        println("复权算法使用示例")
        println("=".repeat(60))

        example1_BasicForwardAdjustment()
        example2_CompositeAdjustment()
        example3_BatchProcessing()
        example4_CalculateReturns()
        example5_ValidationAndAnalysis()
        example6_CustomCandleImplementation()
        example7_FactorSeriesForChart()

        println("\n" + "=".repeat(60))
        println("所有示例运行完成")
        println("=".repeat(60))
    }
}

/**
 * 主函数 - 运行示例
 */
fun main() {
    AdjustmentExamples.runAllExamples()
}
