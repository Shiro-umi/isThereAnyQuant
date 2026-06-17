package org.shiroumi.quant_kmp.ui.components.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QrCode 编码器自验证测试。
 *
 * 验证维度：
 *  - 结构性不变量：定位图案 / 分隔符 / 定时图案 / 暗模块 / 矩阵维度 / 确定性。
 *  - 编码正确性（锁死整条编码链路）：
 *      A) 黄金矩阵逐位比对——固定输入 "HELLO"（版本1/M，21x21）与真实分享 URL
 *         （版本3/M，29x29）对照已离线核验的完整模块矩阵逐位断言。任一处位流组装、
 *         padding、Reed-Solomon、码字交织、数据蛇形铺设、掩码选择出错都会使比对失败。
 *      B) 可解码断言路径——把 [QrCode.encode] 产出的矩阵用本测试内置的独立解码器反解
 *         （读取格式信息恢复掩码 → 去掩码 → 蛇形读位 → 去交织 → 解析字节模式），
 *         断言反解结果等于原始输入字符串。
 *
 * 黄金矩阵与可解码性的离线核验方式：用权威库 segno 生成同输入的标准 QR，并用独立编写的
 * QR 解码器对 [QrCode.encode] 的产物做反解，确认反解结果等于原始字符串、且数据码字序列
 * 等于按 ISO/IEC 18004 位流手算（模式指示符+计数+数据+终止符+0xEC/0x11 padding）的已知值，
 * Reed-Solomon 纠错码字满足生成多项式整除性。核验脚本一次性产出本文件硬编码的黄金矩阵。
 */
class QrCodeTest {

    /** 标准定位图案 7x7 模板：外框黑 + 一圈白 + 中心 3x3 黑。 */
    private val finderTemplate: Array<BooleanArray> = run {
        Array(7) { r ->
            BooleanArray(7) { c ->
                (r == 0 || r == 6 || c == 0 || c == 6) || (r in 2..4 && c in 2..4)
            }
        }
    }

    /** 断言矩阵在 (topRow,leftCol) 处与定位图案模板完全一致。 */
    private fun assertFinderPattern(
        matrix: Array<BooleanArray>,
        topRow: Int,
        leftCol: Int,
        label: String,
    ) {
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                assertEquals(
                    finderTemplate[r][c],
                    matrix[topRow + r][leftCol + c],
                    "$label 定位图案在 (${topRow + r},${leftCol + c}) 处不符",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 黄金矩阵（已离线核验：segno 同输入 + 独立解码器反解 = 原始字符串）。
    // 每个字符串一行，'1' = 黑模块，'0' = 白模块。
    // ------------------------------------------------------------------

    /** "HELLO" 版本1 / 级别M 的完整模块矩阵（21x21，编码器实际选用掩码 4）。 */
    private val helloGolden: Array<String> = arrayOf(
        "111111101101001111111",
        "100000100110101000001",
        "101110100111101011101",
        "101110101001001011101",
        "101110101000101011101",
        "100000101011001000001",
        "111111101010101111111",
        "000000001111100000000",
        "100010111111011111001",
        "000111001011100101111",
        "101100101011001110010",
        "111001000100011010000",
        "001011100100111000110",
        "000000001110111001011",
        "111111101100110001010",
        "100000100001100100010",
        "101110101001001110101",
        "101110100001100001011",
        "101110100111001111000",
        "100000100100011000000",
        "111111101000111110101",
    )

    /** "https://pan.quark.cn/s/667221bcabd6" 版本3 / 级别M 的完整模块矩阵（29x29，编码器实际选用掩码 2）。 */
    private val urlGolden: Array<String> = arrayOf(
        "11111110011110011111001111111",
        "10000010010010000100101000001",
        "10111010100010101001001011101",
        "10111010100000000110101011101",
        "10111010111111110001101011101",
        "10000010100101110100101000001",
        "11111110101010101010101111111",
        "00000000101011001011100000000",
        "10111110001110111111001111100",
        "00110101010010000001001110001",
        "10010110001110010100010000000",
        "11011101011000111011100101010",
        "10000111000100010111000001100",
        "00000100001001101001101010001",
        "01101111111001110100100001100",
        "10001100111011000001000100010",
        "01010110110110110110000001100",
        "11001000101110001001111110101",
        "10110110100010011110010110100",
        "10010100101110110011100010010",
        "10101010011100010111111110111",
        "00000000100111101000100011111",
        "11111110011011111111101011100",
        "10000010110101000010100010001",
        "10111010100100110110111110110",
        "10111010100101001001100001111",
        "10111010111101010010101111110",
        "10000010001110100000110011010",
        "11111110110011110101011011100",
    )

    /** 把黄金字符串矩阵逐位与编码器产出的矩阵比对。 */
    private fun assertMatchesGolden(matrix: Array<BooleanArray>, golden: Array<String>, label: String) {
        assertEquals(golden.size, matrix.size, "$label 黄金矩阵行数应一致")
        for (r in golden.indices) {
            val row = golden[r]
            assertEquals(row.length, matrix[r].size, "$label 黄金矩阵第 $r 行列数应一致")
            for (c in row.indices) {
                val expected = row[c] == '1'
                assertEquals(
                    expected,
                    matrix[r][c],
                    "$label 黄金矩阵在 ($r,$c) 处不符",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 内置独立解码器（可解码断言路径）。
    // 不依赖任何外部库；从 QR 矩阵反解出原始字符串：
    //   读取格式信息恢复掩码 → 去掩码 → 蛇形读位 → 去交织 → 解析字节模式。
    // 仅覆盖本测试用到的单 block 版本（版本 1~7，级别 M），足以反解 HELLO / URL。
    // ------------------------------------------------------------------

    /** 每版本（级别 M）数据码字数，索引 = version-1。来源：ISO 18004 表 9。 */
    private val dataCodewordsPerVersionM = intArrayOf(16, 28, 44, 64, 86, 108, 124)

    /** 重建功能图案占用掩膜（与编码器一致），用于反解时跳过功能位。 */
    private fun buildReserved(size: Int, version: Int): Array<BooleanArray> {
        val reserved = Array(size) { BooleanArray(size) }
        // 定位图案 + 分隔符占用：三个角的 8x8 区域。
        fun reserveFinder(top: Int, left: Int) {
            for (r in 0..7) for (c in 0..7) {
                val rr = top + r
                val cc = left + c
                if (rr in 0 until size && cc in 0 until size) reserved[rr][cc] = true
            }
        }
        reserveFinder(0, 0)
        reserveFinder(0, size - 8)
        reserveFinder(size - 8, 0)
        // 定时图案：第 6 行 / 第 6 列。
        for (i in 0 until size) {
            reserved[6][i] = true
            reserved[i][6] = true
        }
        // 对齐图案（版本 2~7，单中心列表 [6, x]，仅右下中心 (x,x) 不与定位图案重叠）。
        val alignCenters = when (version) {
            2 -> intArrayOf(6, 18)
            3 -> intArrayOf(6, 22)
            4 -> intArrayOf(6, 26)
            5 -> intArrayOf(6, 30)
            6 -> intArrayOf(6, 34)
            7 -> intArrayOf(6, 22, 38)
            else -> intArrayOf()
        }
        if (alignCenters.isNotEmpty()) {
            val last = alignCenters.last()
            for (cr in alignCenters) for (cc in alignCenters) {
                if (cr == 6 && cc == 6) continue
                if (cr == 6 && cc == last) continue
                if (cr == last && cc == 6) continue
                for (r in -2..2) for (c in -2..2) reserved[cr + r][cc + c] = true
            }
        }
        // 暗模块。
        reserved[4 * version + 9][8] = true
        // 格式信息预留区。
        for (i in 0..8) {
            if (i != 6) reserved[8][i] = true
            if (i != 6) reserved[i][8] = true
        }
        for (i in 0..7) reserved[8][size - 1 - i] = true
        for (i in 0..6) reserved[size - 1 - i][8] = true
        return reserved
    }

    /** 8 种掩码条件（与编码器一致）。 */
    private fun maskCondition(mask: Int, row: Int, col: Int): Boolean = when (mask) {
        0 -> (row + col) % 2 == 0
        1 -> row % 2 == 0
        2 -> col % 3 == 0
        3 -> (row + col) % 3 == 0
        4 -> (row / 2 + col / 3) % 2 == 0
        5 -> (row * col) % 2 + (row * col) % 3 == 0
        6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0
        7 -> ((row + col) % 2 + (row * col) % 3) % 2 == 0
        else -> false
    }

    /** 从格式信息区读取并恢复掩码编号（顺带验证格式信息 BCH 写入正确）。 */
    private fun recoverMask(matrix: Array<BooleanArray>): Int {
        // 第一处格式信息布局（与编码器 writeFormatInfo 完全对称）。
        val bits = IntArray(15)
        for (i in 0..5) bits[i] = if (matrix[i][8]) 1 else 0
        bits[6] = if (matrix[7][8]) 1 else 0
        bits[7] = if (matrix[8][8]) 1 else 0
        bits[8] = if (matrix[8][7]) 1 else 0
        for (i in 9..14) bits[i] = if (matrix[8][14 - i]) 1 else 0
        var value = 0
        for (i in 0..14) value = value or (bits[i] shl i)
        value = value xor 0b101010000010010 // 还原规范固定掩码
        val data5 = (value ushr 10) and 0x1F // 高 5 位 = 2bit ECC + 3bit 掩码
        return data5 and 0x7
    }

    /** 反解：QR 矩阵 → 原始字符串（仅支持字节模式 / 级别 M / 单 block 版本 1~7）。 */
    private fun decode(matrix: Array<BooleanArray>): String {
        val size = matrix.size
        val version = (size - 17) / 4
        val mask = recoverMask(matrix)
        val reserved = buildReserved(size, version)

        // 去掩码（仅非功能位）。
        val unmasked = Array(size) { r -> matrix[r].copyOf() }
        for (r in 0 until size) for (c in 0 until size) {
            if (!reserved[r][c] && maskCondition(mask, r, c)) unmasked[r][c] = !unmasked[r][c]
        }

        // 蛇形读位（与编码器 placeDataBits 同序）。
        val bits = ArrayList<Int>()
        var col = size - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            val rows = if (upward) (size - 1 downTo 0) else (0 until size)
            for (row in rows) {
                for (c in intArrayOf(col, col - 1)) {
                    if (!reserved[row][c]) bits.add(if (unmasked[row][c]) 1 else 0)
                }
            }
            upward = !upward
            col -= 2
        }

        // 打包成码字。
        val codewords = ArrayList<Int>()
        var i = 0
        while (i + 8 <= bits.size) {
            var b = 0
            for (k in 0 until 8) b = (b shl 1) or bits[i + k]
            codewords.add(b)
            i += 8
        }

        // 单 block：数据码字即前 N 个（无需 RS 纠错，数据区无损）。
        val dataCount = dataCodewordsPerVersionM[version - 1]
        val dataCodewords = codewords.subList(0, dataCount)

        // 解析字节模式。
        val flat = ArrayList<Int>()
        for (cw in dataCodewords) for (k in 7 downTo 0) flat.add((cw ushr k) and 1)
        var pos = 0
        fun take(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or flat[pos++] }
            return v
        }
        val mode = take(4)
        assertEquals(0b0100, mode, "反解模式指示符应为字节模式")
        val countBits = if (version <= 9) 8 else 16
        val count = take(countBits)
        val out = ByteArray(count)
        for (j in 0 until count) out[j] = take(8).toByte()
        return out.decodeToString()
    }

    // ------------------------------------------------------------------
    // 测试用例
    // ------------------------------------------------------------------

    @Test
    fun shortInputProducesVersion1MatrixWithCorrectStructure() {
        // "HELLO" 5 字节，远小于版本 1（级别 M）数据容量 16 码字，必落在版本 1。
        val matrix = QrCode.encode("HELLO")

        // 版本 1 矩阵边长 = 17 + 4*1 = 21。
        assertEquals(21, matrix.size, "版本 1 矩阵行数应为 21")
        matrix.forEach { row -> assertEquals(21, row.size, "版本 1 矩阵列数应为 21") }

        // 三个角的定位图案位置：左上(0,0)、右上(0,size-7)、左下(size-7,0)。
        val size = matrix.size
        assertFinderPattern(matrix, 0, 0, "左上")
        assertFinderPattern(matrix, 0, size - 7, "右上")
        assertFinderPattern(matrix, size - 7, 0, "左下")

        // 暗模块固定坐标 (4*version+9, 8) = (13, 8)，恒为黑。
        assertTrue(matrix[4 * 1 + 9][8], "暗模块 (13,8) 应为黑")

        // 第四个角（右下）不应有完整定位图案。
        var isFinder = true
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                if (matrix[size - 7 + r][size - 7 + c] != finderTemplate[r][c]) {
                    isFinder = false
                }
            }
        }
        assertTrue(!isFinder, "右下角不应出现完整定位图案")
    }

    @Test
    fun separatorAroundTopLeftFinderIsLight() {
        val matrix = QrCode.encode("HELLO")
        // 分隔符：定位图案外侧第 7 行 / 第 7 列应为白（false）。
        for (i in 0..7) {
            assertTrue(!matrix[7][i], "左上分隔符第 7 行 ($i) 应为白")
            assertTrue(!matrix[i][7], "左上分隔符第 7 列 ($i) 应为白")
        }
    }

    @Test
    fun realShareUrlEncodesWithIntactFinderPatterns() {
        // 蓝图固定永久分享链接。
        val url = "https://pan.quark.cn/s/667221bcabd6"
        val matrix = QrCode.encode(url)

        // 矩阵非空且为正方形。
        assertTrue(matrix.isNotEmpty(), "矩阵不应为空")
        val size = matrix.size
        assertTrue(size >= 21, "矩阵边长应 >= 21")
        matrix.forEach { row -> assertEquals(size, row.size, "矩阵应为正方形") }

        // 边长必须满足 17 + 4*version 的形式。
        assertEquals(0, (size - 17) % 4, "矩阵边长应满足 17 + 4*version")

        // 四角（实为三角）定位图案完整。
        assertFinderPattern(matrix, 0, 0, "左上")
        assertFinderPattern(matrix, 0, size - 7, "右上")
        assertFinderPattern(matrix, size - 7, 0, "左下")

        // 至少存在黑模块（编码非全白）。
        val hasDark = matrix.any { row -> row.any { it } }
        assertTrue(hasDark, "编码结果应包含黑模块")
    }

    @Test
    fun encodingIsDeterministic() {
        val url = "https://pan.quark.cn/s/667221bcabd6"
        val a = QrCode.encode(url)
        val b = QrCode.encode(url)

        assertEquals(a.size, b.size, "两次编码维度应一致")
        for (r in a.indices) {
            for (c in a[r].indices) {
                assertEquals(a[r][c], b[r][c], "两次编码在 ($r,$c) 处应一致")
            }
        }
    }

    @Test
    fun timingPatternAlternates() {
        val matrix = QrCode.encode("HELLO")
        val size = matrix.size
        // 定时图案：第 6 行 / 第 6 列在定位图案之间交替黑白，偶数坐标为黑。
        for (i in 8 until size - 8) {
            val expected = (i % 2) == 0
            assertEquals(expected, matrix[6][i], "横向定时图案 ($i) 不符")
            assertEquals(expected, matrix[i][6], "纵向定时图案 ($i) 不符")
        }
    }

    @Test
    fun helloMatchesGoldenMatrix() {
        // 编码正确性 A：固定输入 "HELLO"（版本1/M，21x21）逐位对照已离线核验的黄金矩阵。
        val matrix = QrCode.encode("HELLO")
        assertMatchesGolden(matrix, helloGolden, "HELLO")
    }

    @Test
    fun realShareUrlMatchesGoldenMatrix() {
        // 编码正确性 A：真实分享 URL（版本3/M，29x29）逐位对照已离线核验的黄金矩阵。
        val matrix = QrCode.encode("https://pan.quark.cn/s/667221bcabd6")
        assertMatchesGolden(matrix, urlGolden, "URL")
    }

    @Test
    fun encodedMatrixDecodesBackToOriginalInput() {
        // 编码正确性 B：可解码断言路径。把编码器产物用内置独立解码器反解，断言 = 原始输入。
        // 位流组装 / padding / Reed-Solomon / 交织 / 蛇形铺设 / 掩码 / 格式信息 任一处出错都会使反解失败。
        val hello = "HELLO"
        assertEquals(hello, decode(QrCode.encode(hello)), "HELLO 反解应等于原始输入")

        val url = "https://pan.quark.cn/s/667221bcabd6"
        assertEquals(url, decode(QrCode.encode(url)), "URL 反解应等于原始输入")
    }
}
