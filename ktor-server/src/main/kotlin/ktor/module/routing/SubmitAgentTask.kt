package ktor.module.routing

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ktor.module.llm.agent.*
import ktor.module.llm.agent.abs.AbsCandleAgent
import model.Quant
import org.shiroumi.database.functioncalling.upsertStrategy
import org.shiroumi.server.scheduler.QuantScheduler
import org.shiroumi.server.today
import kotlin.reflect.KClass

fun Route.submitAgentTask(route: String) = get(route) {
    val tsCode = call.queryParameters["ts_code"]
    val tradeDate = call.queryParameters["trade_date"] ?: today
    if (tsCode.isNullOrBlank()) return@get call.respond(
        HttpStatusCode.BadRequest,
        "task code can't be null or blank"
    )

    val workflow = Workflow.flow(
        Workflow.WorkflowItem(OverviewAgent()),
        Workflow.WorkflowItem(HighProbAreaAgent(), listOf(OverviewAgent::class)),
        Workflow.WorkflowItem(CandleSignalAgent(), listOf(HighProbAreaAgent::class)),
        Workflow.WorkflowItem(
            agent = PlanningAgent(),
            dependsOn = listOf(OverviewAgent::class, HighProbAreaAgent::class, CandleSignalAgent::class)
        ),
        Workflow.WorkflowItem(
            agent = SummariseAgent(),
            dependsOn = listOf(
                OverviewAgent::class,
                HighProbAreaAgent::class,
                CandleSignalAgent::class,
                PlanningAgent::class
            )
        ),
    )
    val task = QuantScheduler.submit(tsCode, workflow.works.map { work ->
        { quant -> work(quant, tsCode) }
    })
    call.respond(
        HttpStatusCode.OK,
        "task submit. $task"
    )
}

class Workflow private constructor(
    vararg items: WorkflowItem
) {
    private val results = mutableMapOf<KClass<*>, String>()

    private var _works: List<suspend (quant: Quant, msg: String) -> Unit> = listOf()
    val works: List<suspend (quant: Quant, msg: String) -> Unit>
        get() = _works

    init {
        _works = items.mapIndexed { i, item ->
            val work: suspend (quant: Quant, msg: String) -> Unit = { quant: Quant, msg: String ->
                val deps = item.dependsOn.map { cls -> results[cls] ?: "" }
                val res = item.agent.chat(msg, deps).choices[0].message.content
                results[item.agent::class] = res
                if (i == items.lastIndex) upsertStrategy(
                    tsCode = quant.code,
                    name = quant.name,
                    targetDate = quant.targetDate,
                    startTime = quant.triggerTime,
                    res = res
                )
            }
            work
        }
    }


    data class WorkflowItem(
        val agent: AbsCandleAgent,
        val dependsOn: List<KClass<*>> = listOf()
    )

    companion object {
        fun flow(vararg items: WorkflowItem) = Workflow(*items)
    }
}