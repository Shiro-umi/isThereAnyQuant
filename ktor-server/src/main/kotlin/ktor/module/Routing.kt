package ktor.module

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import ktor.module.llm.agent.*
import logger
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import org.jetbrains.kotlinx.kandy.dsl.plot
import org.jetbrains.kotlinx.kandy.letsplot.export.toPNG
import org.jetbrains.kotlinx.kandy.letsplot.feature.layout
import org.jetbrains.kotlinx.kandy.letsplot.layers.line
import org.jetbrains.kotlinx.kandy.util.color.Color
import org.jetbrains.kotlinx.kandy.util.context.invoke
import org.jetbrains.kotlinx.statistics.kandy.layers.candlestick
import org.shiroumi.database.functioncalling.fetchDoneTask
import org.shiroumi.database.functioncalling.getJoinedCandles
import org.shiroumi.server.TaskManager
import org.shiroumi.server.model.TaskModel
import org.shiroumi.server.today
import java.nio.channels.ClosedChannelException

fun Application.ktorRouting() {
    routing {

        // 这样更简洁，功能完全相同
        singlePageApplication {
            preCompressed {
            }
            useResources = true
            applicationRoute = "/"
            filesPath = "static"
        }

        get("/tasks/submit") {
            val tsCode = call.queryParameters["ts_code"]
            if (tsCode.isNullOrBlank()) return@get call.respond(
                HttpStatusCode.BadRequest,
                "task code can't be null or blank"
            )
            val logger by logger("submit task, code: $tsCode")
            val results = mutableListOf<String>()
            val task = TaskManager.submitTask(
                tsCode, listOf(
                    {
                        val res = OverviewAgent().chat(tsCode = tsCode).choices[0]
                        val content = res.message.content
                        val reasoningContent = res.message.reasoningContent
                        logger.notify("$reasoningContent")
                        logger.accept(content)
                        results.add(content)
                        content
                    },
                    {
                        val res = HighProbAreaAgent().chat(tsCode = tsCode, results.last()).choices[0]
                        val content = res.message.content
                        val reasoningContent = res.message.reasoningContent
                        logger.notify("$reasoningContent")
                        logger.accept(content)
                        results.add(content)
                        content
                    },
                    {
                        val res = CandleSignalAgent().chat(tsCode = tsCode, results.last()).choices[0]
                        val content = res.message.content
                        val reasoningContent = res.message.reasoningContent
                        logger.notify("$reasoningContent")
                        logger.accept(content)
                        results.add(content)
                        content
                    },
                    {
                        val res = PlanningAgent().chat(tsCode = tsCode, results.joinToString("\n")).choices[0]
                        val content = res.message.content
                        val reasoningContent = res.message.reasoningContent
                        logger.notify("$reasoningContent")
                        logger.accept(content)
                        results.add(content)
                        content
                    },
                    {
                        val res = SummariseAgent().chat(tsCode = tsCode, results.joinToString("\n")).choices[0]
                        val content = res.message.content
                        val reasoningContent = res.message.reasoningContent
                        logger.notify("$reasoningContent")
                        logger.accept(content)
                        results.add(content)
                        content
                    },
                )
            )
            call.respond(
                HttpStatusCode.OK,
                "task submit. $task"
            )
        }

        get("/tasks/status") {
            call.respondText(Json.encodeToString(TaskManager.getTasksList()))
        }

        get("/strategy") {
            val uuid = call.queryParameters["uuid"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                "task uuid can't be null or blank"
            )
            call.respond(
                HttpStatusCode.OK,
                fetchDoneTask(uuid)
            )
        }

        get("/candleImg") {
            val tsCode = call.queryParameters["ts_code"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                "task uuid can't be null or blank"
            )
            val tradeDate = call.queryParameters["date"] ?: today
            val bgColor = call.queryParameters["bg_color"]?.substring(2) ?: "ffffff"
            val primary = call.queryParameters["primary"]?.substring(2) ?: "ffffff"
            val secondary = call.queryParameters["secondary"]?.substring(2) ?: "000000"
            val candles = getJoinedCandles(tsCode, 60, endDate = tradeDate).res
            val df = dataFrameOf(
                "date" to candles.map { it.date },
                "open" to candles.map { it.open },
                "high" to candles.map { it.high },
                "low" to candles.map { it.low },
                "close" to candles.map { it.close },
                "ema20" to candles.map { it.ema20 }
            )
            val png = df.plot {
                layout.style {
                    plotCanvas.background {
                        fillColor = Color.hex("#$bgColor")
                        borderLineColor = Color.hex("#$bgColor")
                    }
                    blankAxes()
                    global {
                        this@global.line {
                            this@line.blank = true
                        }
                    }
                }
                layout.size = 1400 to 700
                candlestick("date", "open", "high", "low", "close") {
                    increase {
                        fillColor = Color.hex("#$primary")
                        alpha = 0.0
                        borderLine.color = Color.hex("#$primary")
                        borderLine.width = 1.0
                    }

                    decrease {
                        fillColor = Color.hex("#$secondary")
                        borderLine.color = Color.hex("#$secondary")
                    }
                }
                line {
                    x(this@plot["date"])
                    y(this@plot["ema20"])
                    color = Color.WHITE
                }
            }.toPNG()
            call.respondBytes(png, ContentType.Image.PNG)
        }

        // 单一 WebSocket 端点，用于广播所有任务的实时状态
        webSocket("/tasks") {
            println("Client connected for live task updates.")
            try {
                // 只要 WebSocket 连接处于活动状态，就持续发送更新
                while (isActive) {
                    val allTasks: List<TaskModel> = TaskManager.getTasksList()
                    val tasksJson = Json.encodeToString(allTasks)
                    send(Frame.Text(tasksJson))
                    delay(1000)
                }
            } catch (e: ClosedChannelException) {
                println("Client disconnected from task updates: ${closeReason.await()}")
            } catch (e: Exception) {
                println("Error in /tasks/updates websocket session: ${e.message}")
                e.printStackTrace()
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "服务器内部发生错误"))
            } finally {
                println("Ending task updates session.")
            }
        }
    }
}