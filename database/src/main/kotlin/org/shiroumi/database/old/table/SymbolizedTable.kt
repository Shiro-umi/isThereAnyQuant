package org.shiroumi.database.old.table

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable

object SymbolizedTable : UUIDTable("symbolized") {
    val candles = text("candles")
    val symbols = text("symbols")
}

//class Symbolized(id: EntityID<UUID>) : UUIDEntity(id) {
//
//    companion object : UUIDEntityClass<Symbolized>(SymbolizedTable)
//
//    var source by SymbolizedTable.candles
//    var symbols by SymbolizedTable.symbols
//}
