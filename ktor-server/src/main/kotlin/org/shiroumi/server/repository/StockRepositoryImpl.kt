package org.shiroumi.server.repository

import kotlinx.datetime.LocalDate
import model.candle.Exchange
import model.candle.CandlePeriod
import model.dataprovider.CandleKey
import org.shiroumi.server.data.bootstrap.DataLayerBootstrap
import org.shiroumi.server.data.snapshot.CandleSnapshotState
import org.shiroumi.server.runtime.market.resolveEffectiveTradeDate
import org.shiroumi.server.runtime.stock.StockCatalogEntry
import org.shiroumi.server.runtime.stock.StockCatalogSnapshotService
import org.shiroumi.database.stock.StockReader
import org.shiroumi.database.strategy.daily.repository.DailyStockFactorRepository
import org.shiroumi.server.dto.MatchType
import org.shiroumi.server.dto.PaginationInfo
import org.shiroumi.server.dto.SortField
import org.shiroumi.server.dto.SortOrder
import org.shiroumi.server.dto.StockInfo
import org.shiroumi.server.dto.StockListRequest
import org.shiroumi.server.dto.StockListResponse
import org.shiroumi.server.dto.StockSuggestion
import java.time.Clock
import java.time.ZoneId

/**
 * 股票数据仓库实现类
 */
class StockRepositoryImpl(
    private val stockCatalogSnapshotService: StockCatalogSnapshotService = StockCatalogSnapshotService(),
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Shanghai")),
    private val tradeDateProvider: () -> LocalDate = { resolveEffectiveTradeDate(clock) }
) : StockRepository {

    @Volatile
    private var rankScoreCache: Pair<LocalDate, Map<String, Double>>? = null

    private fun getRankScores(tradeDate: LocalDate): Map<String, Double> {
        rankScoreCache?.let { (cachedDate, scores) ->
            if (cachedDate == tradeDate) return scores
        }
        val scores = DailyStockFactorRepository.findRankScores(tradeDate)
        if (scores.isNotEmpty()) {
            rankScoreCache = tradeDate to scores
        }
        return scores
    }

    override suspend fun getStocks(request: StockListRequest): StockListResponse {
        data class StockEntry(val tsCode: String, val name: String, val exchange: Exchange)
        val catalog = stockCatalogSnapshotService.snapshot()
        var entries = catalog.entries

        // 应用搜索过滤
        request.search?.let { search ->
            if (search.isNotBlank()) {
                val lowerSearch = search.lowercase()
                entries = entries.filter {
                    it.lowerTsCode.contains(lowerSearch) ||
                        it.lowerCodeWithoutSuffix.startsWith(lowerSearch) ||
                        it.lowerName.contains(lowerSearch) ||
                        it.lowerCnSpell.contains(lowerSearch)
                }
            }
        }

        // 应用交易所过滤
        request.exchange?.let { exchange ->
            entries = entries.filter { it.exchange == exchange }
        }

        // 计算总数
        val total = entries.size

        // 分页参数：先于排序确定，RANK_SCORE 分支据此只选出当前页所需的 TopK，避免全量排序。
        val page = request.page.coerceAtLeast(1)
        val pageSize = request.pageSize.coerceIn(1, 100)
        val offset = (page - 1) * pageSize

        // 对轻量数据排序后只取当前页（CODE/NAME 可在内存排序，价格类排序需要先查数据）
        val pagedEntries = when (request.sortBy) {
            SortField.CODE -> if (request.sortOrder == SortOrder.ASC) {
                entries.sortedBy { it.tsCode }
            } else {
                entries.sortedByDescending { it.tsCode }
            }.drop(offset).take(pageSize)
            SortField.NAME -> if (request.sortOrder == SortOrder.ASC) {
                entries.sortedBy { it.name }
            } else {
                entries.sortedByDescending { it.name }
            }.drop(offset).take(pageSize)
            SortField.RANK_SCORE -> {
                val scores = getRankScores(currentTradeDate())
                // 列表页只要某一页：用有界堆做 TopK 部分选择，把比较代价从全排序的 O(n log n) 降到 O(n log k)
                // （k = offset + pageSize）。稳定性由 TopKPageSelector 内部按 catalog 原始下标补全序保证，
                // 打分相同的条目按原始顺序排列，深翻页不会出现页间重复/遗漏。
                val byScore = compareBy<StockCatalogEntry> { scores[it.tsCode] ?: 0.0 }
                val comparator = if (request.sortOrder == SortOrder.DESC) byScore.reversed() else byScore
                TopKPageSelector.select(entries, comparator, offset, pageSize)
            }
            // 价格类排序暂时按代码排序，实际价格排序需要全量行情数据支持
            else -> entries.sortedBy { it.tsCode }.drop(offset).take(pageSize)
        }

        // 只对当前页的股票批量查询历史数据（单次批量查询代替 N 次单条查询）
        val pagedStocks = if (pagedEntries.isEmpty()) {
            emptyList()
        } else {
            val tsCodes = pagedEntries.map { it.tsCode }
            // 首屏列表从 Data Layer DAY 快照读取最新收盘行情，无需直读 DB。
            // 实时价格由后续的 STOCK_LIST_UPDATE websocket 增量覆盖。
            val snapshotMap = tsCodes.mapNotNull { tsCode ->
                DataLayerBootstrap.candleFacade.readSnapshot(CandleKey(tsCode, CandlePeriod.DAY))
                    ?.takeIf { it.candles.isNotEmpty() }
                    ?.let { tsCode to StockInfoSource.Snapshot(it) }
            }.toMap()
            val missingCodes = tsCodes.filterNot(snapshotMap::containsKey)
            val fallbackMap = if (missingCodes.isNotEmpty()) {
                StockReader.getBatchLatestCandles(missingCodes, limit = 2)
                    .filterValues { it.isNotEmpty() }
                    .mapValues { (_, candles) -> StockInfoSource.Candles(candles) }
            } else {
                emptyMap()
            }
            val sourceMap = snapshotMap + fallbackMap

            pagedEntries.map { entry ->
                createStockInfo(entry.tsCode, entry.name, entry.exchange, sourceMap[entry.tsCode])
            }
        }

        val pagination = PaginationInfo.create(page, pageSize, total)

        return StockListResponse(
            stocks = pagedStocks,
            pagination = pagination
        )
    }

    override suspend fun searchStocks(query: String, limit: Int): List<StockSuggestion> {
        if (query.isBlank()) return emptyList()

        val lowerQuery = query.lowercase()
        val maxResults = limit.coerceIn(1, 20)
        val catalog = stockCatalogSnapshotService.snapshot()

        return catalog.entries.asSequence()
            .mapNotNull { entry ->
                when {
                    entry.lowerCodeWithoutSuffix.startsWith(lowerQuery) ||
                        entry.lowerTsCode.startsWith(lowerQuery) -> {
                        StockSuggestion(
                            code = entry.tsCode,
                            name = entry.name,
                            exchange = entry.exchange,
                            matchType = MatchType.CODE
                        )
                    }
                    entry.lowerName.contains(lowerQuery) -> {
                        StockSuggestion(
                            code = entry.tsCode,
                            name = entry.name,
                            exchange = entry.exchange,
                            matchType = MatchType.NAME
                        )
                    }
                    entry.lowerCnSpell.contains(lowerQuery) -> {
                        StockSuggestion(
                            code = entry.tsCode,
                            name = entry.name,
                            exchange = entry.exchange,
                            matchType = MatchType.NAME
                        )
                    }
                    else -> null
                }
            }
            .sortedWith(compareBy({ it.matchType.ordinal }, { it.code }))
            .take(maxResults)
            .toList()
    }

    override suspend fun getStockByCode(code: String): StockInfo? {
        val normalizedCode = normalizeStockCode(code)
        val catalogEntry = stockCatalogSnapshotService.findByCode(normalizedCode) ?: return null
        val snapshot = DataLayerBootstrap.candleFacade.readSnapshot(CandleKey(normalizedCode, CandlePeriod.DAY))
            ?.takeIf { it.candles.isNotEmpty() }
        val source = snapshot?.let { StockInfoSource.Snapshot(it) }
            ?: StockReader.getBatchLatestCandles(listOf(normalizedCode), limit = 2)[normalizedCode]
                ?.takeIf { it.isNotEmpty() }
                ?.let { StockInfoSource.Candles(it) }
        return createStockInfo(
            normalizedCode,
            catalogEntry.name,
            catalogEntry.exchange,
            source
        )
    }

    /**
     * 从 DAY 快照的最后一根 K 线创建 StockInfo，保留完整估值与行情字段。
     */
    private fun createStockInfo(
        tsCode: String,
        name: String,
        exchange: Exchange,
        source: StockInfoSource?
    ): StockInfo {
        val candles = when (source) {
            is StockInfoSource.Snapshot -> source.snapshot.candles
            is StockInfoSource.Candles -> source.candles
            null -> emptyList()
        }
        val latest = candles.lastOrNull()
        val prev = if (candles.size >= 2) candles[candles.size - 2] else null
        val latestPrice = latest?.close ?: 0f
        val prevClose = prev?.close ?: latest?.open ?: 0f
        val changeAmount = if (prevClose > 0f) latestPrice - prevClose else 0f
        val changePercent = if (prevClose > 0f) (changeAmount / prevClose) * 100f else 0f

        return StockInfo(
            code = tsCode,
            name = name,
            exchange = exchange,
            industry = "",
            sector = "",
            latestPrice = latestPrice,
            changePercent = changePercent,
            changeAmount = changeAmount,
            volume = latest?.volume ?: 0f,
            turnover = latest?.turnoverReal ?: 0f,
            // Tushare daily_basic.total_mv 落库单位为"万元"；传给前端统一转成"元"。
            marketCap = (latest?.mvTotal ?: 0f) * 10_000f,
            peRatio = latest?.pe,
            pbRatio = latest?.pb,
            dayHigh = latest?.high ?: 0f,
            dayLow = latest?.low ?: 0f,
            openPrice = latest?.open ?: 0f,
            prevClose = prevClose,
            updateTime = System.currentTimeMillis()
        )
    }

    private sealed interface StockInfoSource {
        data class Snapshot(val snapshot: CandleSnapshotState) : StockInfoSource
        data class Candles(val candles: List<model.Candle>) : StockInfoSource
    }

    /**
     * 解析交易所信息
     */
    private fun parseExchange(tsCode: String): Exchange {
        return when {
            tsCode.endsWith(".SH") -> Exchange.SH
            tsCode.endsWith(".SZ") -> Exchange.SZ
            tsCode.endsWith(".BJ") -> Exchange.BJ
            tsCode.endsWith(".HK") -> Exchange.HK
            else -> {
                // 根据代码规则判断
                val code = tsCode.substringBefore(".").padStart(6, '0')
                when {
                    code.startsWith("6") -> Exchange.SH
                    code.startsWith("0") || code.startsWith("3") -> Exchange.SZ
                    code.startsWith("8") || code.startsWith("4") -> Exchange.BJ
                    else -> Exchange.SZ // 默认深圳
                }
            }
        }
    }

    /**
     * 规范化股票代码
     */
    private fun normalizeStockCode(code: String): String {
        return if (code.contains(".")) {
            code
        } else {
            // 根据代码前缀添加交易所后缀
            val paddedCode = code.padStart(6, '0')
            when {
                paddedCode.startsWith("6") -> "$paddedCode.SH"
                paddedCode.startsWith("0") || paddedCode.startsWith("3") -> "$paddedCode.SZ"
                paddedCode.startsWith("8") || paddedCode.startsWith("4") -> "$paddedCode.BJ"
                else -> "$paddedCode.SZ"
            }
        }
    }

    /**
     * 从 Data Layer DAY 快照读取 K 线。
     *
     * 注意：快照缓存最大 500 根 K 线，约 2 年窗口。超出范围的日期返回空。
     */
    override suspend fun getStockCandles(
        code: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<model.Candle> {
        val normalizedCode = normalizeStockCode(code)
        val snapshot = DataLayerBootstrap.candleFacade.readSnapshot(CandleKey(normalizedCode, CandlePeriod.DAY))
        return snapshot?.candles
            ?.filter { it.date >= startDate && it.date <= endDate }
            ?: emptyList()
    }

    private fun currentTradeDate(): LocalDate =
        tradeDateProvider()
}
