package org.shiroumi.strategy.research.pipeline

import kotlinx.datetime.LocalDate
import java.nio.file.Files
import java.nio.file.Path

/**
 * 一次研究运行的全局上下文。
 *
 * 贯穿七段管线（Source → Transform → Input → Study → Output → Compare → Conclusion）的不可变运行参数。
 * 任何 [ResearchStage] / [ResearchStudy] 都只读这里的样本区间、参数、随机种子，
 * 并通过 [workspace] 把中间态/产物落到【文件】（而非数据库表）。
 *
 * 边界：
 * - 这里只承载「研究运行参数」，不承载任何因子语义或账户语义。
 * - 数据库只作为【事实数据源】被 Source 段读取；中间态与结论一律落文件。
 *
 * @property runId         本次运行标识，用于产物命名与可追溯（默认按时间戳生成）。
 * @property startDate     样本区间起（含），事实数据读取与研究窗口的左边界。
 * @property endDate       样本区间止（含）。
 * @property params        研究参数表（窗口长度、n_fft、频带、block_size、阈值等），由 Study 解释。
 * @property workspace     研究工作区根目录，所有中间态/产物落盘于此。
 * @property randomSeed    随机种子，保证 permutation / 抽样等随机过程可复现。
 */
data class ResearchContext(
    val runId: String = "run-${System.currentTimeMillis()}",
    val startDate: LocalDate,
    val endDate: LocalDate,
    val params: Map<String, String> = emptyMap(),
    val workspace: Path,
    val randomSeed: Long = 0L,
) {
    init {
        require(startDate <= endDate) { "样本区间非法：startDate=$startDate 晚于 endDate=$endDate" }
    }

    /** 解析工作区下的子路径，必要时建目录；用于各段产物落盘。 */
    fun resolve(relative: String): Path {
        val target = workspace.resolve(relative)
        Files.createDirectories(target.parent ?: workspace)
        return target
    }

    /** 读取一个研究参数；缺失时返回 [default]。 */
    fun param(key: String, default: String): String = params[key] ?: default
}
