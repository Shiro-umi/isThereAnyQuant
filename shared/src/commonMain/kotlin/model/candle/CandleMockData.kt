@file:OptIn(ExperimentalUuidApi::class)

package model.candle

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import model.Candle
import model.toCandleChartData
import model.candle.Exchange
import model.candle.CandleChartData
import model.candle.RealTimeQuote
import model.candle.StockFilterCriteria
import model.candle.StockInfo
import model.candle.StockListResponse
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi

/**
 * K线Mock数据生成器
 * 提供丰富的模拟股票数据和K线序列
 */
object CandleMockData {

    private val random = Random.Default

    /**
     * 预定义的股票池
     * 包含不同行业、不同价格区间的50只股票
     */
    val stockDefinitions: List<StockDefinition> = listOf(
        // 科技板块 - 高价股
        StockDefinition("600519", "贵州茅台", Exchange.SH, "食品饮料", "白酒", 1500f, 2500f),
        StockDefinition("300750", "宁德时代", Exchange.SZ, "电力设备", "电池", 180f, 280f),
        StockDefinition("000858", "五粮液", Exchange.SZ, "食品饮料", "白酒", 120f, 180f),
        StockDefinition("002594", "比亚迪", Exchange.SZ, "汽车", "新能源汽车", 220f, 320f),
        StockDefinition("601012", "隆基绿能", Exchange.SH, "电力设备", "光伏", 18f, 35f),

        // 科技板块 - 中价股
        StockDefinition("300059", "东方财富", Exchange.SZ, "非银金融", "互联网金融", 12f, 22f),
        StockDefinition("002230", "科大讯飞", Exchange.SZ, "计算机", "人工智能", 40f, 70f),
        StockDefinition("000063", "中兴通讯", Exchange.SZ, "通信", "5G设备", 25f, 40f),
        StockDefinition("600276", "恒瑞医药", Exchange.SH, "医药生物", "创新药", 35f, 55f),
        StockDefinition("300760", "迈瑞医疗", Exchange.SZ, "医药生物", "医疗器械", 280f, 380f),

        // 金融板块
        StockDefinition("601398", "工商银行", Exchange.SH, "银行", "国有大行", 4f, 6f),
        StockDefinition("601288", "农业银行", Exchange.SH, "银行", "国有大行", 3f, 5f),
        StockDefinition("601318", "中国平安", Exchange.SH, "非银金融", "保险", 40f, 60f),
        StockDefinition("600036", "招商银行", Exchange.SH, "银行", "股份制银行", 28f, 42f),
        StockDefinition("601688", "华泰证券", Exchange.SH, "非银金融", "券商", 12f, 18f),

        // 新能源
        StockDefinition("300124", "汇川技术", Exchange.SZ, "电力设备", "工控", 55f, 85f),
        StockDefinition("002460", "赣锋锂业", Exchange.SZ, "有色金属", "锂电池", 35f, 60f),
        StockDefinition("603259", "药明康德", Exchange.SH, "医药生物", "CXO", 60f, 100f),
        StockDefinition("300014", "亿纬锂能", Exchange.SZ, "电力设备", "锂电池", 40f, 70f),
        StockDefinition("002709", "天赐材料", Exchange.SZ, "电力设备", "电解液", 20f, 40f),

        // 消费板块
        StockDefinition("000333", "美的集团", Exchange.SZ, "家用电器", "白电", 50f, 75f),
        StockDefinition("000651", "格力电器", Exchange.SZ, "家用电器", "白电", 30f, 45f),
        StockDefinition("603288", "海天味业", Exchange.SH, "食品饮料", "调味品", 35f, 55f),
        StockDefinition("600887", "伊利股份", Exchange.SH, "食品饮料", "乳制品", 25f, 38f),
        StockDefinition("002027", "分众传媒", Exchange.SZ, "传媒", "广告", 6f, 10f),

        // 医药板块
        StockDefinition("300003", "乐普医疗", Exchange.SZ, "医药生物", "医疗器械", 12f, 20f),
        StockDefinition("600196", "复星医药", Exchange.SH, "医药生物", "综合医药", 22f, 35f),
        StockDefinition("000538", "云南白药", Exchange.SZ, "医药生物", "中药", 50f, 75f),
        StockDefinition("600332", "白云山", Exchange.SH, "医药生物", "中药", 25f, 38f),
        StockDefinition("300142", "沃森生物", Exchange.SZ, "医药生物", "疫苗", 12f, 25f),

        // 工业制造
        StockDefinition("601766", "中国中车", Exchange.SH, "机械设备", "轨道交通", 5f, 9f),
        StockDefinition("600031", "三一重工", Exchange.SH, "机械设备", "工程机械", 14f, 22f),
        StockDefinition("000425", "徐工机械", Exchange.SZ, "机械设备", "工程机械", 6f, 10f),
        StockDefinition("601100", "恒立液压", Exchange.SH, "机械设备", "液压件", 45f, 70f),
        StockDefinition("603338", "浙江鼎力", Exchange.SH, "机械设备", "高空作业", 45f, 70f),

        // 材料化工
        StockDefinition("601899", "紫金矿业", Exchange.SH, "有色金属", "黄金铜", 12f, 20f),
        StockDefinition("002460", "赣锋锂业", Exchange.SZ, "有色金属", "锂", 35f, 60f),
        StockDefinition("600309", "万华化学", Exchange.SH, "化工", "聚氨酯", 70f, 110f),
        StockDefinition("002001", "新和成", Exchange.SZ, "化工", "维生素", 18f, 30f),
        StockDefinition("601678", "滨化股份", Exchange.SH, "化工", "氯碱", 4f, 8f),

        // 通信电子
        StockDefinition("600050", "中国联通", Exchange.SH, "通信", "运营商", 4f, 7f),
        StockDefinition("000725", "京东方A", Exchange.SZ, "电子", "面板", 3f, 6f),
        StockDefinition("603501", "韦尔股份", Exchange.SH, "电子", "半导体", 80f, 140f),
        StockDefinition("002371", "北方华创", Exchange.SZ, "电子", "半导体设备", 220f, 380f),
        StockDefinition("688981", "中芯国际", Exchange.SH, "电子", "晶圆代工", 45f, 75f),

        // 房地产基建
        StockDefinition("000002", "万科A", Exchange.SZ, "房地产", "住宅开发", 7f, 15f),
        StockDefinition("600048", "保利发展", Exchange.SH, "房地产", "住宅开发", 8f, 16f),
        StockDefinition("601668", "中国建筑", Exchange.SH, "建筑装饰", "基建", 5f, 9f),
        StockDefinition("601390", "中国中铁", Exchange.SH, "建筑装饰", "基建", 5f, 9f),
        StockDefinition("601186", "中国铁建", Exchange.SH, "建筑装饰", "基建", 7f, 13f),

        // 交通运输
        StockDefinition("601111", "中国国航", Exchange.SH, "交通运输", "航空", 6f, 12f),
        StockDefinition("600029", "南方航空", Exchange.SH, "交通运输", "航空", 5f, 10f),
        StockDefinition("601919", "中远海控", Exchange.SH, "交通运输", "航运", 8f, 18f),
        StockDefinition("600018", "上港集团", Exchange.SH, "交通运输", "港口", 5f, 9f),
        StockDefinition("601006", "大秦铁路", Exchange.SH, "交通运输", "铁路", 6f, 10f)
    )

    /**
     * 生成所有股票的Mock数据
     */
    fun generateAllStocks(): List<StockInfo> {
        return stockDefinitions.map { generateStockInfo(it) }
    }

    /**
     * 生成单只股票信息
     */
    fun generateStockInfo(definition: StockDefinition): StockInfo {
        val currentPrice = randomFloat(definition.minPrice, definition.maxPrice)
        val prevClose = currentPrice * (1 + randomFloat(-0.05f, 0.05f))
        val changePercent = ((currentPrice - prevClose) / prevClose) * 100
        val changeAmount = currentPrice - prevClose

        return StockInfo(
            code = definition.code,
            name = definition.name,
            exchange = definition.exchange,
            industry = definition.industry,
            sector = definition.sector,
            latestPrice = currentPrice,
            changePercent = changePercent,
            changeAmount = changeAmount,
            volume = randomFloat(1000000f, 50000000f),
            turnover = randomFloat(100000000f, 5000000000f),
            marketCap = randomFloat(5000000000f, 2000000000000f),
            peRatio = randomFloat(10f, 50f),
            pbRatio = randomFloat(1f, 8f),
            dayHigh = currentPrice * (1 + randomFloat(0f, 0.03f)),
            dayLow = currentPrice * (1 - randomFloat(0f, 0.03f)),
            openPrice = prevClose * (1 + randomFloat(-0.02f, 0.02f)),
            prevClose = prevClose,
            updateTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        )
    }

    /**
     * 生成单只股票的K线数据（使用新的Candle模型）
     * @param code 股票代码
     * @param days 天数
     * @param basePrice 基础价格
     * @param volatility 波动率
     * @return 生成的Candle列表
     */
    fun generateCandles(
        code: String,
        days: Int = 30,
        basePrice: Float = 100f,
        volatility: Float = 0.025f
    ): List<Candle> {
        val today = kotlin.time.Clock.System.todayIn(TimeZone.currentSystemDefault())
        val candles = mutableListOf<Candle>()
        var currentPrice = basePrice

        for (i in days downTo 1) {
            val date = today.minus(DatePeriod(days = i))

            // 生成日内波动
            val dailyReturn = randomGaussian(0f, volatility)
            val open = currentPrice
            val close = currentPrice * (1 + dailyReturn)
            val high = max(open, close) * (1 + randomFloat(0f, volatility * 0.5f))
            val low = min(open, close) * (1 - randomFloat(0f, volatility * 0.5f))
            val volume = randomFloat(1000000f, 50000000f)
            val turnover = volume * (open + close) / 2

            candles.add(
                Candle(
                    tsCode = code,
                    date = date,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    adj = close,
                    volume = volume,
                    turnoverReal = turnover,
                    pe = randomFloat(10f, 50f),
                    peTtm = randomFloat(10f, 50f),
                    pb = randomFloat(1f, 8f),
                    ps = randomFloat(1f, 10f),
                    psTtm = randomFloat(1f, 10f),
                    mvTotal = randomFloat(5000000000f, 2000000000000f),
                    mvCirc = randomFloat(1000000000f, 1000000000000f)
                )
            )

            currentPrice = close
        }

        return candles
    }

    /**
     * 生成单只股票的K线数据（兼容旧版API，返回 Candle 列表）
     * @param code 股票代码
     * @param days 天数
     * @param basePrice 基础价格
     * @param volatility 波动率
     */
    fun generateKLineData(
        code: String,
        days: Int = 30,
        basePrice: Float = 100f,
        volatility: Float = 0.025f
    ): List<Candle> {
        return generateCandles(code, days, basePrice, volatility)
    }

    /**
     * 生成K线图表数据（与UI组件兼容）
     */
    fun generateCandleChartData(code: String, days: Int = 60): CandleChartData {
        val stockDef = stockDefinitions.find { it.code == code } ?: stockDefinitions.random()
        val candles = generateCandles(code, days, (stockDef.minPrice + stockDef.maxPrice) / 2)
        return candles.toCandleChartData(code, stockDef.name)
    }

    /**
     * 生成实时行情推送数据
     */
    fun generateRealTimeQuote(code: String? = null): RealTimeQuote {
        val stockDef = code?.let { stockDefinitions.find { s -> s.code == code } } ?: stockDefinitions.random()
        val currentPrice = randomFloat(stockDef.minPrice, stockDef.maxPrice)
        val changePercent = randomFloat(-5f, 5f)

        return RealTimeQuote(
            code = stockDef.code,
            name = stockDef.name,
            price = currentPrice,
            changePercent = changePercent,
            changeAmount = currentPrice * changePercent / 100,
            volume = randomFloat(1000000f, 50000000f),
            turnover = randomFloat(100000000f, 5000000000f),
            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            bidPrice = currentPrice * 0.999f,
            askPrice = currentPrice * 1.001f,
            bidVolume = randomFloat(10000f, 100000f),
            askVolume = randomFloat(10000f, 100000f)
        )
    }

    /**
     * 生成股票列表响应
     */
    fun generateStockListResponse(
        page: Int = 1,
        pageSize: Int = 20,
        filter: StockFilterCriteria? = null
    ): StockListResponse {
        var stocks = generateAllStocks()

        filter?.let { f ->
            f.exchange?.let { e -> stocks = stocks.filter { it.exchange == e } }
            f.industry?.let { i -> stocks = stocks.filter { it.industry.contains(i) } }
            f.minPrice?.let { min -> stocks = stocks.filter { it.latestPrice >= min } }
            f.maxPrice?.let { max -> stocks = stocks.filter { it.latestPrice <= max } }
            f.minChangePercent?.let { min -> stocks = stocks.filter { it.changePercent >= min } }
            f.maxChangePercent?.let { max -> stocks = stocks.filter { it.changePercent <= max } }
            f.minMarketCap?.let { min -> stocks = stocks.filter { it.marketCap >= min } }
            f.maxMarketCap?.let { max -> stocks = stocks.filter { it.marketCap <= max } }
        }

        val total = stocks.size
        val startIndex = (page - 1) * pageSize
        val endIndex = min(startIndex + pageSize, total)
        val pagedStocks = if (startIndex < total) {
            stocks.subList(startIndex, endIndex)
        } else emptyList()

        return StockListResponse(
            stocks = pagedStocks,
            pagination = PaginationInfo(
                page = page,
                pageSize = pageSize,
                total = total,
                totalPages = if (total == 0) 1 else (total + pageSize - 1) / pageSize,
                hasNext = page * pageSize < total,
                hasPrevious = page > 1
            )
        )
    }

    /**
     * 生成指定数量的随机股票信息
     */
    fun generateRandomStocks(count: Int): List<StockInfo> {
        return (1..count).map {
            val code = generateRandomStockCode()
            val name = generateRandomStockName()
            val currentPrice = randomFloat(10f, 100f)
            val prevClose = currentPrice * (1 + randomFloat(-0.05f, 0.05f))
            val changePercent = ((currentPrice - prevClose) / prevClose) * 100

            StockInfo(
                code = code,
                name = name,
                exchange = Exchange.SH,
                industry = "科技",
                sector = "软件",
                latestPrice = currentPrice,
                changePercent = changePercent,
                changeAmount = currentPrice - prevClose,
                volume = randomFloat(1000000f, 50000000f),
                turnover = randomFloat(100000000f, 5000000000f),
                marketCap = randomFloat(5000000000f, 2000000000000f),
                peRatio = randomFloat(10f, 50f),
                pbRatio = randomFloat(1f, 8f),
                dayHigh = currentPrice * (1 + randomFloat(0f, 0.03f)),
                dayLow = currentPrice * (1 - randomFloat(0f, 0.03f)),
                openPrice = prevClose * (1 + randomFloat(-0.02f, 0.02f)),
                prevClose = prevClose,
                updateTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
            )
        }
    }

    /**
     * 生成Mock K线数据（兼容旧版API）
     * @param code 股票代码
     * @param days 天数
     */
    fun generateMockCandles(code: String, days: Int): List<Candle> {
        val stockDef = stockDefinitions.find { it.code == code } ?: stockDefinitions.random()
        val basePrice = (stockDef.minPrice + stockDef.maxPrice) / 2
        return generateCandles(code, days, basePrice)
    }

    /**
     * 生成随机股票代码
     */
    fun generateRandomStockCode(): String {
        val prefixes = listOf("600", "601", "603", "605", "000", "002", "003", "300", "688")
        val prefix = prefixes.random()
        val suffix = (100..999).random().toString()
        return prefix + suffix
    }

    /**
     * 生成随机股票名称
     */
    fun generateRandomStockName(): String {
        val prefixes = listOf("中国", "东方", "南方", "北方", "华夏", "国泰", "平安", "招商", "中信", "光大")
        val suffixes = listOf("科技", "生物", "医药", "电子", "能源", "环保", "智能", "数据", "网络", "软件")
        val endings = listOf("股份", "集团", "控股", "实业", "科技", "公司")
        return prefixes.random() + suffixes.random() + endings.random()
    }

    /**
     * 股票定义数据类
     */
    data class StockDefinition(
        val code: String,
        val name: String,
        val exchange: Exchange,
        val industry: String,
        val sector: String,
        val minPrice: Float,
        val maxPrice: Float
    )

    // ==================== 私有辅助函数 ====================

    private fun randomFloat(min: Float, max: Float): Float {
        return min + random.nextFloat() * (max - min)
    }

    private fun randomGaussian(mean: Float, stdDev: Float): Float {
        val u1 = random.nextDouble()
        val u2 = random.nextDouble()
        val z0 = sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * kotlin.math.PI * u2)
        return (mean + stdDev * z0).toFloat()
    }
}

/**
 * K线数据生成器DSL
 */
class CandleDataBuilder {
    var days: Int = 30
    var basePrice: Float = 100f
    var volatility: Float = 0.025f
    var trend: Trend = Trend.RANDOM
    var trendStrength: Float = 0.5f

    enum class Trend {
        BULLISH,    // 上涨趋势
        BEARISH,    // 下跌趋势
        RANDOM,     // 随机波动
        SIDEWAYS    // 横盘震荡
    }

    fun build(): List<Candle> {
        val today = kotlin.time.Clock.System.todayIn(TimeZone.currentSystemDefault())
        val candles = mutableListOf<Candle>()
        var currentPrice = basePrice

        val trendBias = when (trend) {
            Trend.BULLISH -> 0.001f * trendStrength
            Trend.BEARISH -> -0.001f * trendStrength
            Trend.RANDOM -> 0f
            Trend.SIDEWAYS -> 0f
        }

        for (i in days downTo 1) {
            val date = today.minus(DatePeriod(days = i))

            val dailyReturn = when (trend) {
                Trend.SIDEWAYS -> {
                    val targetPrice = basePrice
                    val deviation = (currentPrice - targetPrice) / targetPrice
                    randomGaussian(-deviation * 0.1f, volatility)
                }
                else -> randomGaussian(trendBias, volatility)
            }

            val open = currentPrice
            val close = currentPrice * (1 + dailyReturn)
            val high = max(open, close) * (1 + randomFloat(0f, volatility * 0.5f))
            val low = min(open, close) * (1 - randomFloat(0f, volatility * 0.5f))
            val volume = randomFloat(1000000f, 50000000f)
            val turnover = volume * (open + close) / 2

            candles.add(
                Candle(
                    tsCode = "TEMP",
                    date = date,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    adj = close,
                    volume = volume,
                    turnoverReal = turnover,
                    pe = 0f,
                    peTtm = 0f,
                    pb = 0f,
                    ps = 0f,
                    psTtm = 0f,
                    mvTotal = 0f,
                    mvCirc = 0f
                )
            )

            currentPrice = close
        }

        return candles
    }

    private fun randomFloat(min: Float, max: Float): Float {
        return min + kotlin.random.Random.nextFloat() * (max - min)
    }

    private fun randomGaussian(mean: Float, stdDev: Float): Float {
        val u1 = kotlin.random.Random.nextDouble()
        val u2 = kotlin.random.Random.nextDouble()
        val z0 = sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * kotlin.math.PI * u2)
        return (mean + stdDev * z0).toFloat()
    }
}

/**
 * DSL函数：构建K线数据
 */
inline fun candleData(builder: CandleDataBuilder.() -> Unit): List<Candle> {
    return CandleDataBuilder().apply(builder).build()
}
