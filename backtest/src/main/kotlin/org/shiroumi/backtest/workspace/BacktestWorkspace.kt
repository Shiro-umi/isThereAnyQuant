package org.shiroumi.backtest.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 本地文件模式回测工作区。
 *
 * 对齐 docs/architecture/backtest-engine-design.md §11.2、§11.4.1：
 *  - 根目录 `.backtest/bt-{timestamp}/`
 *  - 子目录 `decisions/` 存放策略决策 JSON，`output/` 存放回测产物
 *
 * 工作区由 CLI 与 BacktestRunExecutor 共同持有；CLI 负责创建/打开目录，
 * Executor 负责把决策导入、结果写出。
 *
 * @param rootDir 工作区根目录（绝对路径），形如 `.../.backtest/bt-20260525-143052`。
 */
class BacktestWorkspace private constructor(val rootDir: Path) {

    /** 决策文件目录：`{rootDir}/decisions/`。 */
    val decisionsDir: Path = rootDir.resolve(DECISIONS_DIR_NAME)

    /** 回测产物目录：`{rootDir}/output/`。 */
    val outputDir: Path = rootDir.resolve(OUTPUT_DIR_NAME)

    /** 运行标识，使用目录名（如 `bt-20260525-143052`）。 */
    val runId: String = rootDir.fileName.toString()

    init {
        Files.createDirectories(decisionsDir)
        Files.createDirectories(outputDir)
    }

    /** 决策文件路径：`{decisionsDir}/{date}.json`。 */
    fun decisionFile(isoDate: String): Path = decisionsDir.resolve("$isoDate.json")

    /** 输出文件路径：`{outputDir}/{name}`。 */
    fun outputFile(name: String): Path = outputDir.resolve(name)

    companion object {
        private const val DECISIONS_DIR_NAME = "decisions"
        private const val OUTPUT_DIR_NAME = "output"
        private const val DEFAULT_WORKSPACE_ROOT = ".backtest"
        private const val RUN_PREFIX = "bt-"
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /**
         * 在 `{baseDir}` 下创建新的 `bt-{timestamp}` 工作区。
         *
         * @param baseDir 工作区根目录，默认 `.backtest/`（相对当前工作路径）。
         * @param now 时间戳，用于单元测试覆盖。
         */
        fun createForRun(
            baseDir: Path = Paths.get(DEFAULT_WORKSPACE_ROOT),
            now: LocalDateTime = LocalDateTime.now(),
        ): BacktestWorkspace {
            val runId = "$RUN_PREFIX${now.format(TIMESTAMP_FORMATTER)}"
            val rootDir = baseDir.resolve(runId).toAbsolutePath().normalize()
            return BacktestWorkspace(rootDir)
        }

        /**
         * 打开已存在的工作区。若目录不存在会被自动创建（保持幂等）。
         */
        fun open(path: Path): BacktestWorkspace =
            BacktestWorkspace(path.toAbsolutePath().normalize())
    }
}
