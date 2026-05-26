package org.shiroumi.server.dataprovider.port

import kotlinx.datetime.LocalDate
import org.shiroumi.database.stock.LimitListDRecord

interface RemoteLimitListDFetcher {
    suspend fun fetch(tradeDate: LocalDate): List<LimitListDRecord>
}
