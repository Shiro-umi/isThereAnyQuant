package org.shiroumi.quant_kmp.ui.components.qr

/**
 * 纯 Kotlin QR Code Model 2 编码器（零外部依赖）。
 *
 * 实现范围严格对照 ISO/IEC 18004（QR Code 标准规范）：
 *  - 字节模式（Byte / 8-bit）编码，输入字符串按 UTF-8 取字节。
 *  - 纠错级别固定 M，自动从版本 1~10 中选最小可容纳版本。
 *  - Reed-Solomon 纠错码：GF(256)，本原多项式 0x11D（x^8+x^4+x^3+x^2+1）。
 *  - 数据位流：模式指示符(4bit) + 字符计数 + 数据 + 终止符 + 字节对齐 + 填充字节(0xEC,0x11 交替)。
 *  - 数据码字 / 纠错码字按 block 交织（多 block 版本正确交织，单 block 版本即顺序拼接）。
 *  - 功能图案：三处定位图案(7x7) + 分隔符 + 定时图案 + 对齐图案(版本>=2) + 暗模块。
 *  - 8 种掩码全部试算，按 ISO 18004 §8.8.2 四条 penalty 规则选最低分。
 *  - 格式信息：15bit BCH，写入两处。版本信息：18bit BCH，版本>=7 写入两处。
 *
 * 输出：[Array]<[BooleanArray]>，外层下标为行(y)，内层下标为列(x)，true 表示黑模块。
 * 矩阵边长 = 17 + 4 * version。
 */
object QrCode {

    /** 纠错级别（本编码器固定使用 M）。规范定义 L=0b01, M=0b00, Q=0b11, H=0b10 的格式位模式。 */
    private const val EC_M_BITS = 0b00 // 格式信息中 M 级别的两位编码（来源：ISO 18004 表 12）

    /**
     * 每个版本（索引 = version-1，仅维护 1~10）在 ECC 级别 M 下的容量与分块参数。
     * 字段来源：ISO 18004 表 9（纠错特性表）。
     *  - ecPerBlock：每个 block 的纠错码字数。
     *  - group1Blocks / group1DataPerBlock：第一组 block 数及其每块数据码字数。
     *  - group2Blocks / group2DataPerBlock：第二组 block 数及其每块数据码字数。
     * 总数据码字 = g1Blocks*g1Data + g2Blocks*g2Data；字节模式可容纳数据字节 = 总数据码字 - 计数字段与模式开销。
     */
    private data class VersionEc(
        val version: Int,
        val ecPerBlock: Int,
        val group1Blocks: Int,
        val group1DataPerBlock: Int,
        val group2Blocks: Int,
        val group2DataPerBlock: Int,
    ) {
        val totalDataCodewords: Int
            get() = group1Blocks * group1DataPerBlock + group2Blocks * group2DataPerBlock
        val totalBlocks: Int
            get() = group1Blocks + group2Blocks
    }

    // ECC 级别 M，版本 1~10。数值逐项核对 ISO 18004 表 9。
    private val VERSION_EC_M: List<VersionEc> = listOf(
        VersionEc(1, 10, 1, 16, 0, 0),   // 数据码字 16
        VersionEc(2, 16, 1, 28, 0, 0),   // 数据码字 28
        VersionEc(3, 26, 1, 44, 0, 0),   // 数据码字 44
        VersionEc(4, 18, 2, 32, 0, 0),   // 数据码字 64
        VersionEc(5, 24, 2, 43, 0, 0),   // 数据码字 86
        VersionEc(6, 16, 4, 27, 0, 0),   // 数据码字 108
        VersionEc(7, 18, 4, 31, 0, 0),   // 数据码字 124
        VersionEc(8, 22, 2, 38, 2, 39),  // 数据码字 154
        VersionEc(9, 22, 3, 36, 2, 37),  // 数据码字 182
        VersionEc(10, 26, 4, 43, 1, 44), // 数据码字 216
    )

    /**
     * 对齐图案中心坐标（按版本）。来源：ISO 18004 附录 E 表 E.1。
     * 版本 1 无对齐图案；版本 2~10 给出坐标列表，所有坐标两两组合得到中心点，
     * 但与定位图案重叠的角点（左上/右上/左下）需排除。
     */
    private val ALIGNMENT_CENTERS: Map<Int, IntArray> = mapOf(
        1 to intArrayOf(),
        2 to intArrayOf(6, 18),
        3 to intArrayOf(6, 22),
        4 to intArrayOf(6, 26),
        5 to intArrayOf(6, 30),
        6 to intArrayOf(6, 34),
        7 to intArrayOf(6, 22, 38),
        8 to intArrayOf(6, 24, 42),
        9 to intArrayOf(6, 26, 46),
        10 to intArrayOf(6, 28, 50),
    )

    /**
     * 编码入口：把 URL/字符串编码为 QR 模块矩阵。
     *
     * @param content 待编码字符串（如分享链接 URL）。
     * @return 模块矩阵，matrix[y][x] == true 表示黑模块。
     * @throws IllegalArgumentException 当内容在版本 1~10 / 级别 M 下无法容纳。
     */
    fun encode(content: String): Array<BooleanArray> {
        val data = content.encodeToByteArray() // UTF-8 字节（Kotlin 标准库，无外部依赖）

        val versionEc = selectVersion(data.size)
        val version = versionEc.version
        val size = 17 + 4 * version

        // 1) 构造数据位流 → 数据码字。
        val dataCodewords = buildDataCodewords(data, versionEc)

        // 2) 计算纠错码字并按 block 交织成最终码字序列。
        val finalCodewords = interleaveWithEc(dataCodewords, versionEc)

        // 3) 铺功能图案，得到“保留位掩膜”。
        val modules = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) } // true = 功能图案占用，数据不可写
        placeFunctionPatterns(modules, reserved, version)

        // 4) 在非保留区按规范蛇形路径写入数据位。
        placeDataBits(modules, reserved, finalCodewords, size)

        // 5) 8 种掩码全试，选 penalty 最低者，并写入对应格式信息。
        return applyBestMask(modules, reserved, version, size)
    }

    // ---------------------------------------------------------------------
    // 版本选择
    // ---------------------------------------------------------------------

    /**
     * 选择能容纳 dataByteCount 字节（字节模式 / 级别 M）的最小版本。
     * 开销：模式指示符 4bit + 字符计数指示符（版本 1~9 为 8bit）+ 数据 + 终止符。
     * 字节模式字符计数位数：版本 1~9 = 8bit，10~26 = 16bit（来源：ISO 18004 表 3）。
     */
    private fun selectVersion(dataByteCount: Int): VersionEc {
        for (ve in VERSION_EC_M) {
            val countBits = if (ve.version <= 9) 8 else 16
            // 需要的位数：模式(4) + 计数 + 数据*8。终止符与填充由码字容量天然吸收。
            val requiredBits = 4 + countBits + dataByteCount * 8
            val capacityBits = ve.totalDataCodewords * 8
            if (requiredBits <= capacityBits) return ve
        }
        throw IllegalArgumentException(
            "内容过长，版本 1~10 级别 M 无法容纳：${dataByteCount} 字节",
        )
    }

    // ---------------------------------------------------------------------
    // 位流 → 数据码字
    // ---------------------------------------------------------------------

    /** 简单的 MSB-first 位缓冲，用于按规范顺序拼接位流。 */
    private class BitBuffer {
        val bits = ArrayList<Boolean>()
        fun appendBits(value: Int, length: Int) {
            // 从高位到低位逐位写入（MSB first），符合 QR 位流约定。
            for (i in length - 1 downTo 0) {
                bits.add(((value ushr i) and 1) == 1)
            }
        }
    }

    /**
     * 构造数据码字：模式指示符 + 字符计数 + 数据字节 + 终止符 + 字节对齐 + 填充字节。
     * 填充字节交替 0xEC(11101100) / 0x11(00010001)，来源：ISO 18004 §8.4.9。
     */
    private fun buildDataCodewords(data: ByteArray, ve: VersionEc): IntArray {
        val bb = BitBuffer()

        // 模式指示符：字节模式 = 0b0100（来源：ISO 18004 表 2）。
        bb.appendBits(0b0100, 4)

        // 字符计数指示符：字节模式下版本 1~9 为 8bit，10~26 为 16bit。
        val countBits = if (ve.version <= 9) 8 else 16
        bb.appendBits(data.size, countBits)

        // 数据：每字节 8bit（toInt() 带符号，需 and 0xFF 取无符号字节）。
        for (b in data) {
            bb.appendBits(b.toInt() and 0xFF, 8)
        }

        val capacityBits = ve.totalDataCodewords * 8

        // 终止符：最多 4 个 0；若剩余空间不足 4 则只填到满。
        val remaining = capacityBits - bb.bits.size
        val terminator = if (remaining < 4) remaining else 4
        repeat(terminator) { bb.bits.add(false) }

        // 字节对齐：补 0 到 8 的整数倍。
        while (bb.bits.size % 8 != 0) bb.bits.add(false)

        // 转成码字。
        val codewords = IntArray(ve.totalDataCodewords)
        var idx = 0
        var bitPos = 0
        while (bitPos + 8 <= bb.bits.size && idx < codewords.size) {
            var byte = 0
            for (k in 0 until 8) {
                byte = (byte shl 1) or (if (bb.bits[bitPos + k]) 1 else 0)
            }
            codewords[idx++] = byte
            bitPos += 8
        }

        // 填充字节：交替 0xEC / 0x11，直到填满数据码字容量。
        var padToggle = true
        while (idx < codewords.size) {
            codewords[idx++] = if (padToggle) 0xEC else 0x11
            padToggle = !padToggle
        }

        return codewords
    }

    // ---------------------------------------------------------------------
    // GF(256) 与 Reed-Solomon
    // ---------------------------------------------------------------------

    // GF(256) 指数表 / 对数表，本原多项式 0x11D（来源：ISO 18004 §8.5.1）。
    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D // 溢出时按本原多项式约简
        }
        // 把指数表延长一倍，便于乘法做 (a+b) 而不取模。
        for (i in 255 until 512) GF_EXP[i] = GF_EXP[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return GF_EXP[GF_LOG[a] + GF_LOG[b]]
    }

    /**
     * 生成 Reed-Solomon 生成多项式：(x - α^0)(x - α^1)...(x - α^(degree-1))。
     * 系数按降幂存储，首项系数恒为 1。来源：ISO 18004 §8.5.2。
     */
    private fun rsGeneratorPoly(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            // 乘以 (x - α^i)，在 GF(256) 中 -α^i == α^i。
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]                       // x * 当前项
                next[j + 1] = next[j + 1] xor gfMul(poly[j], GF_EXP[i]) // α^i * 当前项
            }
            poly = next
        }
        return poly
    }

    /**
     * 对一个 block 的数据码字计算 ecCount 个纠错码字（多项式除法取余数）。
     * 来源：ISO 18004 §8.5.2，等价于 RS 编码的多项式长除。
     */
    private fun rsEncodeBlock(dataBlock: IntArray, ecCount: Int): IntArray {
        val generator = rsGeneratorPoly(ecCount)
        // 余数寄存器初值 = 数据码字后接 ecCount 个 0。
        val remainder = IntArray(dataBlock.size + ecCount)
        for (i in dataBlock.indices) remainder[i] = dataBlock[i]

        for (i in dataBlock.indices) {
            val coef = remainder[i]
            if (coef != 0) {
                for (j in generator.indices) {
                    remainder[i + j] = remainder[i + j] xor gfMul(generator[j], coef)
                }
            }
        }
        // 余数的最后 ecCount 项即纠错码字。
        return remainder.copyOfRange(dataBlock.size, dataBlock.size + ecCount)
    }

    /**
     * 把数据码字切成 block，逐块计算纠错码字，再按规范交织：
     * 先按列输出所有 block 的数据码字，再按列输出所有 block 的纠错码字。
     * 来源：ISO 18004 §8.6（码字交织）。
     */
    private fun interleaveWithEc(dataCodewords: IntArray, ve: VersionEc): IntArray {
        // 1) 切分数据 block。
        val dataBlocks = ArrayList<IntArray>(ve.totalBlocks)
        var offset = 0
        repeat(ve.group1Blocks) {
            dataBlocks.add(dataCodewords.copyOfRange(offset, offset + ve.group1DataPerBlock))
            offset += ve.group1DataPerBlock
        }
        repeat(ve.group2Blocks) {
            dataBlocks.add(dataCodewords.copyOfRange(offset, offset + ve.group2DataPerBlock))
            offset += ve.group2DataPerBlock
        }

        // 2) 逐块计算纠错码字。
        val ecBlocks = dataBlocks.map { rsEncodeBlock(it, ve.ecPerBlock) }

        val result = ArrayList<Int>()

        // 3) 交织数据码字：按列遍历，列数取各 block 最大长度。
        val maxDataLen = dataBlocks.maxOf { it.size }
        for (col in 0 until maxDataLen) {
            for (block in dataBlocks) {
                if (col < block.size) result.add(block[col])
            }
        }

        // 4) 交织纠错码字：所有 block 的纠错码字长度一致（= ecPerBlock）。
        for (col in 0 until ve.ecPerBlock) {
            for (ec in ecBlocks) {
                result.add(ec[col])
            }
        }

        return result.toIntArray()
    }

    // ---------------------------------------------------------------------
    // 功能图案
    // ---------------------------------------------------------------------

    /** 在 (row,col) 处放置一个模块并标记为功能图案占用。 */
    private fun setFunction(
        modules: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        row: Int,
        col: Int,
        dark: Boolean,
    ) {
        modules[row][col] = dark
        reserved[row][col] = true
    }

    /**
     * 铺设全部功能图案：定位图案 + 分隔符 + 定时图案 + 对齐图案 + 暗模块 + 预留格式/版本信息区。
     */
    private fun placeFunctionPatterns(
        modules: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        version: Int,
    ) {
        val size = modules.size

        // 定位图案（Finder Pattern）7x7，置于三个角（左上、右上、左下）。
        placeFinderPattern(modules, reserved, 0, 0)
        placeFinderPattern(modules, reserved, 0, size - 7)
        placeFinderPattern(modules, reserved, size - 7, 0)

        // 分隔符（Separator）：定位图案外侧一圈白模块。
        placeSeparators(modules, reserved, size)

        // 定时图案（Timing Pattern）：第 6 行 / 第 6 列交替黑白。
        for (i in 8 until size - 8) {
            val dark = (i % 2) == 0
            if (!reserved[6][i]) setFunction(modules, reserved, 6, i, dark)
            if (!reserved[i][6]) setFunction(modules, reserved, i, 6, dark)
        }

        // 对齐图案（Alignment Pattern），版本 >= 2。
        placeAlignmentPatterns(modules, reserved, version)

        // 暗模块（Dark Module）：固定坐标 (4*version+9, 8)，恒为黑。来源：ISO 18004 §8.9。
        setFunction(modules, reserved, 4 * version + 9, 8, true)

        // 预留格式信息区（15bit，两处）——此处仅标记为保留，真正写入在掩码确定后。
        reserveFormatInfoArea(reserved, size)

        // 预留版本信息区（18bit，两处），版本 >= 7。
        if (version >= 7) reserveVersionInfoArea(reserved, size)
    }

    /** 7x7 定位图案：外框黑、内白、3x3 实心黑。 */
    private fun placeFinderPattern(
        modules: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        topRow: Int,
        leftCol: Int,
    ) {
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                // 外圈(r/c==0或6)为黑；内圈一圈白(r/c==1或5)；中心 3x3(2..4)为黑。
                val dark = (r == 0 || r == 6 || c == 0 || c == 6) ||
                    (r in 2..4 && c in 2..4)
                setFunction(modules, reserved, topRow + r, leftCol + c, dark)
            }
        }
    }

    /** 分隔符：每个定位图案朝向矩阵内侧的一圈白模块（宽 1）。 */
    private fun placeSeparators(
        modules: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        size: Int,
    ) {
        // 左上：第 7 行 0..7 与第 7 列 0..7。
        for (i in 0..7) {
            if (!reserved[7][i]) setFunction(modules, reserved, 7, i, false)
            if (!reserved[i][7]) setFunction(modules, reserved, i, 7, false)
        }
        // 右上：第 7 行 size-8..size-1 与第 7 列起的竖边。
        for (i in 0..7) {
            if (!reserved[7][size - 1 - i]) setFunction(modules, reserved, 7, size - 1 - i, false)
            if (!reserved[i][size - 8]) setFunction(modules, reserved, i, size - 8, false)
        }
        // 左下：第 size-8 行与第 size-8..size-1 列附近。
        for (i in 0..7) {
            if (!reserved[size - 8][i]) setFunction(modules, reserved, size - 8, i, false)
            if (!reserved[size - 1 - i][7]) setFunction(modules, reserved, size - 1 - i, 7, false)
        }
    }

    /**
     * 对齐图案 5x5：外框黑、内圈白、中心单点黑。
     * 规范：在所有中心坐标两两组合处放置，**仅排除**与三个定位图案重叠的三个角
     * （左上 (6,6)、右上 (6,last)、左下 (last,6)）。
     * 注意：中心落在定时图案行/列（如 (6,22)、(22,6)）的对齐图案必须正常绘制，
     * 它会覆盖该处的定时图案模块——这是规范允许的（来源：ISO 18004 §8.5、附录 E）。
     */
    private fun placeAlignmentPatterns(
        modules: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        version: Int,
    ) {
        val centers = ALIGNMENT_CENTERS[version] ?: return
        if (centers.isEmpty()) return
        val last = centers.last()
        for (cr in centers) {
            for (cc in centers) {
                // 仅排除与三个定位图案重叠的三个角点。
                if (cr == 6 && cc == 6) continue
                if (cr == 6 && cc == last) continue
                if (cr == last && cc == 6) continue
                for (r in -2..2) {
                    for (c in -2..2) {
                        val dark = (r == -2 || r == 2 || c == -2 || c == 2) || (r == 0 && c == 0)
                        setFunction(modules, reserved, cr + r, cc + c, dark)
                    }
                }
            }
        }
    }

    /** 预留格式信息 15 个模块（两处），真实位由 applyBestMask 写入。 */
    private fun reserveFormatInfoArea(reserved: Array<BooleanArray>, size: Int) {
        // 第一处：定位图案 1（左上）周边。
        for (i in 0..8) {
            if (i != 6) reserved[8][i] = true       // 第 8 行
            if (i != 6) reserved[i][8] = true       // 第 8 列
        }
        // 第二处：右上水平条 与 左下竖条。
        for (i in 0..7) reserved[8][size - 1 - i] = true // 第 8 行右侧 8 位
        for (i in 0..6) reserved[size - 1 - i][8] = true // 第 8 列下侧 7 位
    }

    /** 预留版本信息 18 个模块（两处，6x3），版本 >= 7。 */
    private fun reserveVersionInfoArea(reserved: Array<BooleanArray>, size: Int) {
        for (r in 0..5) {
            for (c in 0..2) {
                reserved[r][size - 11 + c] = true // 右上 6x3
                reserved[size - 11 + c][r] = true // 左下 3x6
            }
        }
    }

    // ---------------------------------------------------------------------
    // 数据位铺设（蛇形路径）
    // ---------------------------------------------------------------------

    /**
     * 按规范把最终码字位流写入非保留模块：
     * 自右下角起，每次取相邻两列（右列优先），从下往上、再从上往下蛇形上行；
     * 跳过第 6 列（定时图案列）；跳过所有保留模块。
     * 来源：ISO 18004 §8.7.1。
     */
    private fun placeDataBits(
        modules: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        codewords: IntArray,
        size: Int,
    ) {
        var bitIndex = 0
        val totalBits = codewords.size * 8

        var col = size - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col-- // 跳过定时图案所在的第 6 列
            val rows = if (upward) (size - 1 downTo 0) else (0 until size)
            for (row in rows) {
                // 当前两列：右列 = col，左列 = col-1。
                for (c in intArrayOf(col, col - 1)) {
                    if (!reserved[row][c]) {
                        val bit = if (bitIndex < totalBits) {
                            val cw = codewords[bitIndex / 8]
                            ((cw ushr (7 - (bitIndex % 8))) and 1) == 1
                        } else {
                            false // 余量补 0（理论上交织后正好填满，不会触发）
                        }
                        modules[row][c] = bit
                        bitIndex++
                    }
                }
            }
            upward = !upward
            col -= 2
        }
    }

    // ---------------------------------------------------------------------
    // 掩码与格式信息
    // ---------------------------------------------------------------------

    /** 8 种掩码条件函数（来源：ISO 18004 表 10）。row=i, col=j。 */
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

    /**
     * 对 8 种掩码逐一施加 + 写入格式信息 + 计算 penalty，选最低分掩码，返回成品矩阵。
     */
    private fun applyBestMask(
        baseModules: Array<BooleanArray>,
        reserved: Array<BooleanArray>,
        version: Int,
        size: Int,
    ): Array<BooleanArray> {
        var bestMatrix: Array<BooleanArray>? = null
        var bestPenalty = Int.MAX_VALUE

        for (mask in 0..7) {
            // 复制基础矩阵，仅对非保留模块按掩码条件翻转。
            val candidate = Array(size) { r -> baseModules[r].copyOf() }
            for (r in 0 until size) {
                for (c in 0 until size) {
                    if (!reserved[r][c] && maskCondition(mask, r, c)) {
                        candidate[r][c] = !candidate[r][c]
                    }
                }
            }
            // 写入对应掩码的格式信息与版本信息（这些是功能位，不受掩码翻转影响）。
            writeFormatInfo(candidate, version, mask, size)
            if (version >= 7) writeVersionInfo(candidate, version, size)

            val penalty = calculatePenalty(candidate, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMatrix = candidate
            }
        }
        return bestMatrix!!
    }

    /**
     * 计算 15bit 格式信息：5bit 数据(2bit ECC 级别 + 3bit 掩码) 经 BCH(15,5) 编码补 10bit，
     * 再与固定掩码 0b101010000010010 异或。来源：ISO 18004 §8.9 与附录 C。
     */
    private fun formatInfoBits(ecBits: Int, mask: Int): Int {
        val data = (ecBits shl 3) or mask // 5bit
        var bch = data shl 10
        // BCH 多项式 G(x) = x^10+x^8+x^5+x^4+x^2+x+1 = 0b10100110111。
        val g = 0b10100110111
        // 多项式长除求 10bit 余数。
        for (i in 4 downTo 0) {
            if ((bch ushr (i + 10)) and 1 == 1) {
                bch = bch xor (g shl i)
            }
        }
        val full = ((data shl 10) or (bch and 0x3FF))
        return full xor 0b101010000010010 // 规范固定掩码
    }

    /**
     * 把 15bit 格式信息写入两处（坐标采用 ISO 18004 §8.9 + Nayuki 参考实现的标准布局）。
     * 第一处分布在左上定位图案的第 8 行 / 第 8 列；第二处分布在右上水平条与左下竖条。
     * 约定：bit i 的取位为从 LSB(i=0) 到 MSB(i=14)。
     */
    private fun writeFormatInfo(
        modules: Array<BooleanArray>,
        version: Int,
        mask: Int,
        size: Int,
    ) {
        val bits = formatInfoBits(EC_M_BITS, mask)
        fun bitAt(i: Int): Boolean = ((bits ushr i) and 1) == 1

        // 第一处（左上）：
        // i = 0..5 沿第 8 列自上而下放在 row=0..5（跳过第 6 行定时图案）。
        for (i in 0..5) modules[i][8] = bitAt(i)
        // i = 6 -> (7,8)，i = 7 -> (8,8)，i = 8 -> (8,7)（跨过第 6 列定时图案）。
        modules[7][8] = bitAt(6)
        modules[8][8] = bitAt(7)
        modules[8][7] = bitAt(8)
        // i = 9..14 沿第 8 行自右而左放在 col=5..0。
        for (i in 9..14) modules[8][14 - i] = bitAt(i)

        // 第二处（右上 + 左下）：
        // i = 0..7 沿第 8 行自右端向左放在 col=size-1..size-8。
        for (i in 0..7) modules[8][size - 1 - i] = bitAt(i)
        // i = 8..14 沿第 8 列自下而上放在 row=size-7..size-1。
        for (i in 8..14) modules[size - 15 + i][8] = bitAt(i)
    }

    /**
     * 计算并写入 18bit 版本信息（版本 >= 7，两处 6x3）。
     * 18bit = 6bit 版本号 + BCH(18,6) 12bit 余数，G(x)=0x1F25。来源：ISO 18004 附录 D。
     */
    private fun writeVersionInfo(modules: Array<BooleanArray>, version: Int, size: Int) {
        var bch = version shl 12
        val g = 0x1F25
        for (i in 5 downTo 0) {
            if ((bch ushr (i + 12)) and 1 == 1) {
                bch = bch xor (g shl i)
            }
        }
        val bits = (version shl 12) or (bch and 0xFFF)

        for (i in 0..17) {
            val bit = ((bits ushr i) and 1) == 1
            val row = i / 3
            val col = i % 3
            // 左下 6x3（行 row、列在 size-11 区）与右上对称 3x6。
            modules[size - 11 + col][row] = bit
            modules[row][size - 11 + col] = bit
        }
    }

    // ---------------------------------------------------------------------
    // Penalty 评分（ISO 18004 §8.8.2 四条规则）
    // ---------------------------------------------------------------------

    private fun calculatePenalty(m: Array<BooleanArray>, size: Int): Int {
        var penalty = 0

        // 规则 1：每行/每列连续同色 >= 5。5 个记 3 分，之后每多 1 个 +1。
        for (r in 0 until size) {
            penalty += lineRunPenalty(IntArray(size) { c -> if (m[r][c]) 1 else 0 })
        }
        for (c in 0 until size) {
            penalty += lineRunPenalty(IntArray(size) { r -> if (m[r][c]) 1 else 0 })
        }

        // 规则 2：2x2 同色块，每个 +3。
        for (r in 0 until size - 1) {
            for (c in 0 until size - 1) {
                val v = m[r][c]
                if (m[r][c + 1] == v && m[r + 1][c] == v && m[r + 1][c + 1] == v) {
                    penalty += 3
                }
            }
        }

        // 规则 3：行/列中出现 1011101 模式且一侧有 0000，每次 +40。
        penalty += finderLikePenalty(m, size)

        // 规则 4：黑模块占比偏离 50% 的程度。
        var dark = 0
        for (r in 0 until size) for (c in 0 until size) if (m[r][c]) dark++
        val total = size * size
        val percent = dark * 100 / total
        val prev = (percent / 5) * 5
        val next = prev + 5
        val k = minOf(kotlin.math.abs(prev - 50) / 5, kotlin.math.abs(next - 50) / 5)
        penalty += k * 10

        return penalty
    }

    private fun lineRunPenalty(line: IntArray): Int {
        var penalty = 0
        var runColor = line[0]
        var runLength = 1
        for (i in 1 until line.size) {
            if (line[i] == runColor) {
                runLength++
            } else {
                if (runLength >= 5) penalty += 3 + (runLength - 5)
                runColor = line[i]
                runLength = 1
            }
        }
        if (runLength >= 5) penalty += 3 + (runLength - 5)
        return penalty
    }

    /** 规则 3：检测 1:1:3:1:1 定位类模式（dark light dark*3 light dark）+ 一侧 4 个浅模块。 */
    private fun finderLikePenalty(m: Array<BooleanArray>, size: Int): Int {
        var penalty = 0
        // 两种带空白的模式（来源：ISO 18004 §8.8.2 规则 3）。
        val patternA = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val patternB = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)

        // 水平扫描。
        for (r in 0 until size) {
            for (c in 0..size - 11) {
                if (matchesPattern(m, r, c, true, patternA) || matchesPattern(m, r, c, true, patternB)) {
                    penalty += 40
                }
            }
        }
        // 垂直扫描。
        for (c in 0 until size) {
            for (r in 0..size - 11) {
                if (matchesPattern(m, r, c, false, patternA) || matchesPattern(m, r, c, false, patternB)) {
                    penalty += 40
                }
            }
        }
        return penalty
    }

    private fun matchesPattern(
        m: Array<BooleanArray>,
        row: Int,
        col: Int,
        horizontal: Boolean,
        pattern: BooleanArray,
    ): Boolean {
        for (k in pattern.indices) {
            val v = if (horizontal) m[row][col + k] else m[row + k][col]
            if (v != pattern[k]) return false
        }
        return true
    }
}
