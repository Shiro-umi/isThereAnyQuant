package ktor.module.routing

import org.shiroumi.database.stock.StockMinute15mRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L0 历史取数纯逻辑测试。
 *
 * 覆盖 as_of 截断语义（防未来函数）：
 * - as_of 缺失/非法 → parseAsOf 返回 null（路由据此返回 400）
 * - 分钟线上界 = 信号日 T 收盘 15:00:00
 * - 聚合后的分钟 K 线时间戳不超过信号日收盘
 * - 涨跌停 end_date 收敛到 as_of，start_date 不晚于 as_of
 */
class AsofCliTest {

    @Test
    fun `as_of 缺失或非法时 parseAsOf 返回 null`() {
        assertNull(AsofCli.parseAsOf(null))
        assertNull(AsofCli.parseAsOf(""))
        assertNull(AsofCli.parseAsOf("   "))
        assertNull(AsofCli.parseAsOf("2024-01-02"))   // 带分隔符非法
        assertNull(AsofCli.parseAsOf("2024010"))      // 长度不足
        assertNull(AsofCli.parseAsOf("2024010x"))     // 非数字
        assertNull(AsofCli.parseAsOf("20241301"))     // 月份越界
    }

    @Test
    fun `合法 as_of 解析为对应日期`() {
        val d = AsofCli.parseAsOf("20240102")
        assertEquals(kotlinx.datetime.LocalDate(2024, 1, 2), d)
    }

    @Test
    fun `分钟线上界是信号日 T 收盘 15点00分00秒`() {
        val asOf = AsofCli.parseAsOf("20240102")!!
        assertEquals("2024-01-02 15:00:00", AsofCli.intradayUpperBound(asOf))
    }

    @Test
    fun `15min 直接返回真实行且时间戳全部不晚于信号日收盘`() {
        val asOf = AsofCli.parseAsOf("20240102")!!
        val upper = AsofCli.intradayUpperBound(asOf)
        // 模拟历史表（信号日收盘截断后）返回的当日 15min 行：09:45 ~ 15:00。
        val rows = listOf(
            row("2024-01-02 09:45:00", open = 10.0f, high = 10.5f, low = 9.9f, close = 10.2f, vol = 100f, amount = 1000f),
            row("2024-01-02 10:00:00", open = 10.2f, high = 10.6f, low = 10.1f, close = 10.4f, vol = 120f, amount = 1200f),
            row("2024-01-02 15:00:00", open = 10.4f, high = 10.8f, low = 10.3f, close = 10.7f, vol = 200f, amount = 2000f),
        )
        val bars = AsofCli.aggregateMinuteBars(rows, "15min")
        assertEquals(3, bars.size)
        assertTrue(bars.all { it.tradeTime <= upper }, "所有 15min 时间戳必须 <= 信号日收盘")
        assertEquals("2024-01-02 15:00:00", bars.last().tradeTime)
    }

    @Test
    fun `60min 由 4 根 15min 等比聚合且不超过信号日收盘`() {
        val asOf = AsofCli.parseAsOf("20240102")!!
        val upper = AsofCli.intradayUpperBound(asOf)
        // 8 根 15min → 2 根 60min。
        val rows = (1..8).map { i ->
            val tt = "2024-01-02 " + listOf(
                "09:45:00", "10:00:00", "10:15:00", "10:30:00",
                "10:45:00", "11:00:00", "11:15:00", "11:30:00",
            )[i - 1]
            row(tt, open = i.toFloat(), high = (i + 1).toFloat(), low = (i - 1).toFloat(), close = (i + 0.5f), vol = 10f, amount = 100f)
        }
        val bars = AsofCli.aggregateMinuteBars(rows, "60min")
        assertEquals(2, bars.size)
        // 第一桶：开=第1根开、高=第1..4根最高、低=第1..4根最低、收=第4根收、量额累加。
        val first = bars.first()
        assertEquals(1.0f, first.open)
        assertEquals(5.0f, first.high)   // 第4根 high = 5
        assertEquals(0.0f, first.low)    // 第1根 low = 0
        assertEquals(4.5f, first.close)  // 第4根 close = 4.5
        assertEquals(40f, first.vol)     // 10 * 4
        assertEquals(400f, first.amount) // 100 * 4
        assertTrue(bars.all { it.tradeTime <= upper })
    }

    @Test
    fun `aggregateFactor 与 isSupportedPeriod 口径正确`() {
        assertEquals(4, AsofCli.aggregateFactor("60min"))
        assertEquals(2, AsofCli.aggregateFactor("30min"))
        assertEquals(1, AsofCli.aggregateFactor("15min"))
        assertEquals(1, AsofCli.aggregateFactor("5min"))
        assertTrue(AsofCli.isSupportedPeriod("60MIN"))
        assertFalse(AsofCli.isSupportedPeriod("1day"))
    }

    @Test
    fun `涨跌停 end_date 收敛到 as_of`() {
        val asOf = AsofCli.parseAsOf("20240102")!!
        assertEquals("20240102", AsofCli.toTushareDate(asOf))
    }

    @Test
    fun `涨跌停 start_date 越界时收敛到 as_of`() {
        val asOf = AsofCli.parseAsOf("20240102")!!
        // 调用方传入的 start_date 晚于 as_of → 收敛到 as_of。
        assertEquals("20240102", AsofCli.resolveAsOfStartDate("20240301", asOf))
        // 合法 start_date 原样保留。
        assertEquals("20231101", AsofCli.resolveAsOfStartDate("20231101", asOf))
        // 缺省回看 60 个自然日，且不晚于 as_of。
        val def = AsofCli.resolveAsOfStartDate(null, asOf)
        assertTrue(def <= "20240102")
        assertEquals("20231103", def)
    }

    @Test
    fun `tradeDateNotAfter 应用层双保险过滤越界记录`() {
        assertTrue(AsofCli.tradeDateNotAfter("20240102", "20240102"))
        assertTrue(AsofCli.tradeDateNotAfter("20231231", "20240102"))
        assertFalse(AsofCli.tradeDateNotAfter("20240103", "20240102"))
        assertFalse(AsofCli.tradeDateNotAfter("", "20240102"))
    }

    @Test
    fun `normalizeStockCode 自动补全交易所后缀`() {
        assertEquals("600519.SH", AsofCli.normalizeStockCode("600519"))
        assertEquals("000001.SZ", AsofCli.normalizeStockCode("000001"))
        assertEquals("300750.SZ", AsofCli.normalizeStockCode("300750"))
        assertEquals("000001.SZ", AsofCli.normalizeStockCode("000001.SZ"))
    }

    private fun row(
        tradeTime: String,
        open: Float,
        high: Float,
        low: Float,
        close: Float,
        vol: Float,
        amount: Float,
    ): StockMinute15mRepository.Minute15mRow {
        val date = kotlinx.datetime.LocalDate.parse(tradeTime.substring(0, 10))
        return StockMinute15mRepository.Minute15mRow(
            tsCode = "000001.SZ",
            tradeDate = date,
            tradeTime = tradeTime,
            open = open,
            high = high,
            low = low,
            close = close,
            vol = vol,
            amount = amount,
        )
    }
}
