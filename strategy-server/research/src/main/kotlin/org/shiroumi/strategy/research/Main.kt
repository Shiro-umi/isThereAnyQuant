package org.shiroumi.strategy.research

import kotlinx.datetime.LocalDate
import org.shiroumi.strategy.research.pipeline.ResearchContext
import java.time.ZoneId
import kotlin.io.path.Path

private const val DEFAULT_START = "2020-01-02"
private const val DEFAULT_WORKSPACE = "research/sentiment_factor"

fun main(args: Array<String>) {
    val options = parseArgs(args)
    val ctx = ResearchContext(
        startDate = LocalDate.parse(options["start"] ?: DEFAULT_START),
        endDate = LocalDate.parse(options["end"] ?: java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()),
        workspace = Path(options["workspace"] ?: DEFAULT_WORKSPACE).toAbsolutePath().normalize(),
        params = options.filterKeys { it !in setOf("start", "end", "workspace") },
    )

    val written = SkeletonPipeline.run(ctx)
    println("run_id=${ctx.runId}")
    println("workspace=${ctx.workspace}")
    println("cards_written=${written.size}")
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val parsed = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        require(arg.startsWith("--")) { "Unsupported argument: $arg" }
        val key = arg.removePrefix("--")
        val value = if (index + 1 < args.size && !args[index + 1].startsWith("--")) {
            args[++index]
        } else {
            "true"
        }
        parsed[key] = value
        index++
    }
    return parsed
}
