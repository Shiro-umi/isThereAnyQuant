package datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.ktorm.entity.add
import org.ktorm.entity.map
import org.shiroumi.akApi
import org.shiroumi.database.table.SymbolType
import org.shiroumi.database.table.symbolSeq
import org.shiroumi.model.database.Symbol

// update all symbol
suspend fun updateSymbol() = coroutineScope {
    launch(Dispatchers.IO) {
        symbolSeq.run symbol@{
            val symbols = map { it.code }.toSet()
            akApi.getStockSymbol().map {
                it.convert()
            }.forEach { symbol ->
                if (symbol.code in symbols) return@forEach
                add(Symbol {
                    code = symbol.code
                    name = symbol.name
                    type = SymbolType.Stock.value
                })
            }
        }
    }
}
