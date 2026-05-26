package org.shiroumi.database.strategy.daily.repository

import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.shiroumi.database.stockDb
import org.shiroumi.strategy.core.daily.StockFactorSnapshot
import org.shiroumi.database.strategy.daily.table.DailyStockFactorTable
import org.shiroumi.database.transaction
import utils.logger

private val dailyStockFactorLogger by logger("DailyStockFactorRepository")
private const val FACTOR_UPSERT_BATCH_SIZE = 5_000

object DailyStockFactorRepository {
    fun countByDate(tradeDate: LocalDate): Long {
        return stockDb.transaction(DailyStockFactorTable, log = false) {
            DailyStockFactorTable.selectAll()
                .where { DailyStockFactorTable.tradeDate eq tradeDate }
                .count()
        }
    }

    fun deleteByDate(tradeDate: LocalDate): Int {
        return stockDb.transaction(DailyStockFactorTable, log = false) {
            DailyStockFactorTable.deleteWhere { DailyStockFactorTable.tradeDate eq tradeDate }
        }
    }

    fun replaceForDate(
        tradeDate: LocalDate,
        snapshots: List<StockFactorSnapshot>,
    ) {
        stockDb.transaction(DailyStockFactorTable, log = false) {
            DailyStockFactorTable.deleteWhere {
                DailyStockFactorTable.tradeDate eq tradeDate
            }
        }
        if (snapshots.isEmpty()) return

        val totalStart = System.currentTimeMillis()
        val batches = snapshots.chunked(FACTOR_UPSERT_BATCH_SIZE)
        batches.forEachIndexed { batchIndex, batch ->
            val batchStart = System.currentTimeMillis()
            stockDb.transaction(DailyStockFactorTable, log = false) {
                DailyStockFactorTable.batchUpsert(batch) { snapshot ->
                    this[DailyStockFactorTable.tradeDate] = tradeDate
                    this[DailyStockFactorTable.tsCode] = snapshot.tsCode
                    this[DailyStockFactorTable.signalBasis] = snapshot.signalBasis
                    this[DailyStockFactorTable.executionBasis] = snapshot.executionBasis
                    this[DailyStockFactorTable.sufficientHistory] = snapshot.sufficientHistory
                    this[DailyStockFactorTable.requiredHistory] = snapshot.requiredHistory
                    this[DailyStockFactorTable.open] = snapshot.open
                    this[DailyStockFactorTable.high] = snapshot.high
                    this[DailyStockFactorTable.low] = snapshot.low
                    this[DailyStockFactorTable.close] = snapshot.close
                    this[DailyStockFactorTable.volume] = snapshot.volume
                    this[DailyStockFactorTable.executionOpen] = snapshot.executionOpen
                    this[DailyStockFactorTable.executionClose] = snapshot.executionClose
                    this[DailyStockFactorTable.hfqFactor] = snapshot.hfqFactor
                    this[DailyStockFactorTable.ema10] = snapshot.ema10
                    this[DailyStockFactorTable.ema30] = snapshot.ema30
                    this[DailyStockFactorTable.emaBull] = snapshot.emaBull
                    this[DailyStockFactorTable.atr14] = snapshot.atr14
                    this[DailyStockFactorTable.signal] = snapshot.signal
                    this[DailyStockFactorTable.momentum20] = snapshot.momentum20
                    this[DailyStockFactorTable.volRatio520] = snapshot.volRatio520
                    this[DailyStockFactorTable.amomCombined] = snapshot.amomCombined
                    this[DailyStockFactorTable.rankScore] = snapshot.rankScore
                }
            }
            logThroughput(
                tag = "FACTOR_UPSERT",
                rows = batch.size,
                elapsedMs = System.currentTimeMillis() - batchStart,
                batchIndex = batchIndex,
                totalBatches = batches.size,
                tradeDate = tradeDate,
            )
        }
        logThroughput(
            tag = "FACTOR_UPSERT_TOTAL",
            rows = snapshots.size,
            elapsedMs = System.currentTimeMillis() - totalStart,
            batchIndex = 0,
            totalBatches = 1,
            tradeDate = tradeDate,
        )
    }

    private fun logThroughput(
        tag: String,
        rows: Int,
        elapsedMs: Long,
        batchIndex: Int,
        totalBatches: Int,
        tradeDate: LocalDate,
    ) {
        val rowsPerSecond = if (elapsedMs > 0) rows * 1000.0 / elapsedMs else rows.toDouble()
        dailyStockFactorLogger.info(
            "[DB_THROUGHPUT][$tag] tradeDate=$tradeDate, rows=$rows, elapsedMs=$elapsedMs, " +
                "rowsPerSecond=${"%.2f".format(rowsPerSecond)}, batch=${batchIndex + 1}/$totalBatches"
        )
    }

    /**
     * 查询指定日期的所有股票因子
     * 用于盘中计算初始化时加载 T-1 日盘后数据
     */
    fun findByDate(tradeDate: LocalDate, tsCodes: List<String> = emptyList()): List<StockFactorSnapshot> {
        return stockDb.transaction(DailyStockFactorTable) {
            DailyStockFactorTable
                .selectAll()
                .where {
                    val tsCodeFilter = if (tsCodes.isNotEmpty()) {
                        DailyStockFactorTable.tsCode inList tsCodes
                    } else {
                        Op.TRUE
                    }
                    (DailyStockFactorTable.tradeDate eq tradeDate) and tsCodeFilter
                }
                .map { row ->
                    StockFactorSnapshot(
                        tradeDate = row[DailyStockFactorTable.tradeDate],
                        tsCode = row[DailyStockFactorTable.tsCode],
                        signalBasis = row[DailyStockFactorTable.signalBasis],
                        executionBasis = row[DailyStockFactorTable.executionBasis],
                        sufficientHistory = row[DailyStockFactorTable.sufficientHistory],
                        requiredHistory = row[DailyStockFactorTable.requiredHistory],
                        open = row[DailyStockFactorTable.open],
                        high = row[DailyStockFactorTable.high],
                        low = row[DailyStockFactorTable.low],
                        close = row[DailyStockFactorTable.close],
                        volume = row[DailyStockFactorTable.volume],
                        executionOpen = row[DailyStockFactorTable.executionOpen],
                        executionClose = row[DailyStockFactorTable.executionClose],
                        hfqFactor = row[DailyStockFactorTable.hfqFactor],
                        ema10 = row[DailyStockFactorTable.ema10],
                        ema30 = row[DailyStockFactorTable.ema30],
                        emaBull = row[DailyStockFactorTable.emaBull],
                        atr14 = row[DailyStockFactorTable.atr14],
                        signal = row[DailyStockFactorTable.signal],
                        momentum20 = row[DailyStockFactorTable.momentum20],
                        volRatio520 = row[DailyStockFactorTable.volRatio520],
                        amomCombined = row[DailyStockFactorTable.amomCombined],
                        rankScore = row[DailyStockFactorTable.rankScore]
                    )
                }
        }
    }

    fun findRankScores(tradeDate: LocalDate): Map<String, Double> {
        return stockDb.transaction(DailyStockFactorTable, log = false) {
            DailyStockFactorTable
                .select(DailyStockFactorTable.tsCode, DailyStockFactorTable.rankScore)
                .where { DailyStockFactorTable.tradeDate eq tradeDate }
                .associate { row ->
                    row[DailyStockFactorTable.tsCode] to row[DailyStockFactorTable.rankScore]
                }
        }
    }

    /**
     * 查询指定日期的流动性最好的前N只股票代码
     * 用于初始化情绪样本股
     */
    fun findTopLiquidStocks(tradeDate: LocalDate, limit: Int): List<String> {
        return stockDb.transaction(DailyStockFactorTable) {
            DailyStockFactorTable
                .selectAll()
                .where { DailyStockFactorTable.tradeDate eq tradeDate }
                .orderBy(DailyStockFactorTable.volume, SortOrder.DESC)
                .limit(limit)
                .map { it[DailyStockFactorTable.tsCode] }
        }
    }

    fun findLatestBefore(
        tradeDateExclusive: LocalDate,
        tsCodes: List<String> = emptyList()
    ): Pair<LocalDate, List<StockFactorSnapshot>>? {
        return stockDb.transaction(DailyStockFactorTable) {
            val latestDate = DailyStockFactorTable
                .selectAll()
                .where { DailyStockFactorTable.tradeDate lessEq tradeDateExclusive }
                .orderBy(DailyStockFactorTable.tradeDate, SortOrder.DESC)
                .limit(1)
                .map { row -> row[DailyStockFactorTable.tradeDate] }
                .firstOrNull()
                ?: return@transaction null

            latestDate to DailyStockFactorTable
                .selectAll()
                .where {
                    val tsCodeFilter = if (tsCodes.isNotEmpty()) {
                        DailyStockFactorTable.tsCode inList tsCodes
                    } else {
                        Op.TRUE
                    }
                    (DailyStockFactorTable.tradeDate eq latestDate) and tsCodeFilter
                }
                .map { row ->
                    StockFactorSnapshot(
                        tradeDate = row[DailyStockFactorTable.tradeDate],
                        tsCode = row[DailyStockFactorTable.tsCode],
                        signalBasis = row[DailyStockFactorTable.signalBasis],
                        executionBasis = row[DailyStockFactorTable.executionBasis],
                        sufficientHistory = row[DailyStockFactorTable.sufficientHistory],
                        requiredHistory = row[DailyStockFactorTable.requiredHistory],
                        open = row[DailyStockFactorTable.open],
                        high = row[DailyStockFactorTable.high],
                        low = row[DailyStockFactorTable.low],
                        close = row[DailyStockFactorTable.close],
                        volume = row[DailyStockFactorTable.volume],
                        executionOpen = row[DailyStockFactorTable.executionOpen],
                        executionClose = row[DailyStockFactorTable.executionClose],
                        hfqFactor = row[DailyStockFactorTable.hfqFactor],
                        ema10 = row[DailyStockFactorTable.ema10],
                        ema30 = row[DailyStockFactorTable.ema30],
                        emaBull = row[DailyStockFactorTable.emaBull],
                        atr14 = row[DailyStockFactorTable.atr14],
                        signal = row[DailyStockFactorTable.signal],
                        momentum20 = row[DailyStockFactorTable.momentum20],
                        volRatio520 = row[DailyStockFactorTable.volRatio520],
                        amomCombined = row[DailyStockFactorTable.amomCombined],
                        rankScore = row[DailyStockFactorTable.rankScore]
                    )
                }
        }
    }
}
