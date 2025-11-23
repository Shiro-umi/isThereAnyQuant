package org.shiroumi.database.table

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.dao.UUIDEntity
import org.jetbrains.exposed.v1.dao.UUIDEntityClass
import java.util.*

object SymbolizedTable : UUIDTable("symbolized") {
    val candles = text("candles")
    val symbols = text("symbols")
}

class Symbolized(id: EntityID<UUID>) : UUIDEntity(id) {

    companion object : UUIDEntityClass<Symbolized>(SymbolizedTable)

    var source by SymbolizedTable.candles
    var symbols by SymbolizedTable.symbols
}
