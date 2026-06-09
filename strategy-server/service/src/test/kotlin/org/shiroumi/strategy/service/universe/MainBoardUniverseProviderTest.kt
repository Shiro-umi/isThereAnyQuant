package org.shiroumi.strategy.service.universe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * MainBoardUniverseProvider 候选池资格单元测试。
 *
 * 当前候选池 = 主板（600/601/603/605.SH，000/001/002/003.SZ）+ 创业板（300/301.SZ），
 * 排除科创板（688.SH）、北交所（8xxxxx.BJ）、ST/PT/退市/N新股。
 *
 * 资格判定单点入口为 [MainBoardUniverseProvider.isEligible]，
 * 本测试通过 [eligible] 适配器以「上市中（list_status=L、无退市日）」的状态调用它。
 */
class MainBoardUniverseProviderTest {

    /** 以「正常上市」状态调用资格判定，便于按 tsCode + name 校验市场板块与风险词过滤。 */
    private fun eligible(tsCode: String, name: String): Boolean =
        MainBoardUniverseProvider.isEligible(
            tsCode = tsCode,
            name = name,
            listStatus = "L",
            delistDate = null,
        )

    @ParameterizedTest(name = "沪市主板 {0} 应该被识别")
    @CsvSource(
        "600000.SH, true",
        "600001.SH, true",
        "601000.SH, true",
        "601318.SH, true",
        "603000.SH, true",
        "603288.SH, true",
        "605000.SH, true",
        "605117.SH, true",
        "688000.SH, false",
        "688981.SH, false",
        "689000.SH, false"
    )
    @DisplayName("沪市主板筛选测试")
    fun testShanghaiMainBoard(tsCode: String, expected: Boolean) {
        val result = eligible(tsCode, "测试股票")
        assertEquals(expected, result, "股票 $tsCode 的主板识别结果应为 $expected")
    }

    @ParameterizedTest(name = "深市主板 {0} 应该被识别")
    @CsvSource(
        "000001.SZ, true",
        "000002.SZ, true",
        "000333.SZ, true",
        "001000.SZ, true",
        "001289.SZ, true",
        "002000.SZ, true",
        "002415.SZ, true",
        "003000.SZ, true",
        "003816.SZ, true",
        "300001.SZ, true",
        "300750.SZ, true",
        "301000.SZ, true",
        "301269.SZ, true"
    )
    @DisplayName("深市主板+创业板筛选测试")
    fun testShenzhenMainBoard(tsCode: String, expected: Boolean) {
        val result = eligible(tsCode, "测试股票")
        assertEquals(expected, result, "股票 $tsCode 的主板识别结果应为 $expected")
    }

    @ParameterizedTest(name = "北交所 {0} 应该被排除")
    @ValueSource(strings = ["830000.BJ", "835000.BJ", "870000.BJ", "872000.BJ"])
    @DisplayName("北交所排除测试")
    fun testBeijingExchangeExcluded(tsCode: String) {
        val result = eligible(tsCode, "测试股票")
        assertFalse(result, "北交所股票 $tsCode 应该被排除")
    }

    @ParameterizedTest(name = "其他市场 {0} 应该被排除")
    @ValueSource(strings = ["AAPL.US", "TSLA.US", "0700.HK", "9988.HK"])
    @DisplayName("其他市场排除测试")
    fun testOtherMarketsExcluded(tsCode: String) {
        val result = eligible(tsCode, "测试股票")
        assertFalse(result, "其他市场股票 $tsCode 应该被排除")
    }

    @Test
    @DisplayName("ST 股票应该被排除在 10cm 股票池外")
    fun testStExcluded() {
        assertFalse(eligible("600000.SH", "ST测试"))
        assertFalse(eligible("000001.SZ", "*ST测试"))
        assertFalse(eligible("002001.SZ", "测试ST"))
    }

    @Test
    @DisplayName("空字符串应该返回false")
    fun testEmptyString() {
        val result = eligible("", "测试股票")
        assertFalse(result, "空字符串应该返回false")
    }

    @Test
    @DisplayName("无市场后缀的代码应该返回false")
    fun testNoMarketSuffix() {
        val result = eligible("600000", "测试股票")
        assertFalse(result, "无市场后缀的代码应该返回false")
    }

    @Test
    @DisplayName("只有市场后缀应该返回false")
    fun testOnlyMarketSuffix() {
        val result = eligible(".SH", "测试股票")
        assertFalse(result, "只有市场后缀应该返回false")
    }

    @Test
    @DisplayName("包含多个点号的代码应该正确处理")
    fun testMultipleDots() {
        val result = eligible("600.000.SH", "测试股票")
        assertFalse(result, "包含多个点号的代码应该返回false（代码部分不符合主板规则）")
    }

    @Test
    @DisplayName("完整股票列表筛选测试")
    fun testFullStockListFiltering() {
        val testStocks = listOf(
            "600000.SH",
            "601318.SH",
            "603288.SH",
            "605117.SH",
            "688981.SH",
            "000001.SZ",
            "000333.SZ",
            "002415.SZ",
            "300750.SZ",
            "301269.SZ",
            "830000.BJ"
        )

        val filtered = testStocks.filter { eligible(it, "测试股票") }

        // 主板 5 (600000/601318/603288/605117/000001/000333/002415) + 创业板 2 (300750/301269) = 9
        assertEquals(9, filtered.size, "应该保留主板+创业板共9只")
        assertTrue(filtered.contains("600000.SH"), "应该包含600000.SH")
        assertTrue(filtered.contains("601318.SH"), "应该包含601318.SH")
        assertTrue(filtered.contains("000001.SZ"), "应该包含000001.SZ")
        assertTrue(filtered.contains("300750.SZ"), "应该包含创业板300750.SZ")
        assertTrue(filtered.contains("301269.SZ"), "应该包含创业板301269.SZ")
        assertFalse(filtered.contains("688981.SH"), "不应该包含科创板")
        assertFalse(filtered.contains("830000.BJ"), "不应该包含北交所")
    }

    @Test
    @DisplayName("筛选结果应该按tsCode排序")
    fun testSorting() {
        val testStocks = listOf(
            "603000.SH",
            "000001.SZ",
            "600000.SH",
            "002000.SZ",
            "601000.SH"
        )

        val filtered = testStocks.filter { eligible(it, "测试股票") }.sorted()

        assertEquals("000001.SZ", filtered[0], "第一个应该是深市主板")
        assertEquals("002000.SZ", filtered[1])
        assertEquals("600000.SH", filtered[2])
        assertEquals("601000.SH", filtered[3])
        assertEquals("603000.SH", filtered[4])
    }

}
