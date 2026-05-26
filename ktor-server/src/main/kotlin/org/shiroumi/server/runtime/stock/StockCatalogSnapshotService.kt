package org.shiroumi.server.runtime.stock

import model.candle.Exchange
import org.shiroumi.database.stock.StockReader
import utils.logger
import java.util.concurrent.atomic.AtomicReference

data class StockCatalogSnapshot(
    val entries: List<StockCatalogEntry>,
    val byTsCode: Map<String, StockCatalogEntry>,
    val updatedAt: Long
)

data class StockCatalogEntry(
    val tsCode: String,
    val name: String,
    val cnSpell: String,
    val market: String,
    val listStatus: String,
    val exchange: Exchange,
    val lowerTsCode: String,
    val lowerName: String,
    val lowerCnSpell: String,
    val lowerCodeWithoutSuffix: String
)

class StockCatalogSnapshotService {
    private val logger by logger("StockCatalogSnapshotService")
    private val snapshotRef = AtomicReference<StockCatalogSnapshot?>(null)

    fun initialize() {
        if (snapshotRef.get() != null) return
        refresh()
    }

    fun refresh() {
        val start = System.currentTimeMillis()
        val basics = StockReader.getAllStockBasics()
        val dbElapsed = System.currentTimeMillis() - start
        val entries = basics.map { record ->
            StockCatalogEntry(
                tsCode = record.tsCode,
                name = record.name,
                cnSpell = record.cnSpell,
                market = record.market,
                listStatus = record.listStatus,
                exchange = parseExchange(record.tsCode),
                lowerTsCode = record.tsCode.lowercase(),
                lowerName = record.name.lowercase(),
                lowerCnSpell = record.cnSpell.lowercase(),
                lowerCodeWithoutSuffix = record.tsCode.substringBefore(".").lowercase()
            )
        }
        val snapshot = StockCatalogSnapshot(
            entries = entries,
            byTsCode = entries.associateBy { it.tsCode },
            updatedAt = System.currentTimeMillis()
        )
        snapshotRef.set(snapshot)
        val totalElapsed = System.currentTimeMillis() - start
        logger.info("股票目录内存快照刷新完成: total=${entries.size}, 查询=${dbElapsed}ms, 总耗时=${totalElapsed}ms")
    }

    fun snapshot(): StockCatalogSnapshot {
        initialize()
        return requireNotNull(snapshotRef.get()) { "stock catalog snapshot unavailable" }
    }

    fun findByCode(tsCode: String): StockCatalogEntry? = snapshot().byTsCode[tsCode]

    private fun parseExchange(tsCode: String): Exchange {
        return when {
            tsCode.endsWith(".SH") -> Exchange.SH
            tsCode.endsWith(".SZ") -> Exchange.SZ
            tsCode.endsWith(".BJ") -> Exchange.BJ
            tsCode.endsWith(".HK") -> Exchange.HK
            else -> {
                val code = tsCode.substringBefore(".").padStart(6, '0')
                when {
                    code.startsWith("6") -> Exchange.SH
                    code.startsWith("0") || code.startsWith("3") -> Exchange.SZ
                    code.startsWith("8") || code.startsWith("4") -> Exchange.BJ
                    else -> Exchange.SZ
                }
            }
        }
    }
}
