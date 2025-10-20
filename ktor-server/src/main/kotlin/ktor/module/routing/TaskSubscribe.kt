package ktor.module.routing

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import model.TaskList
import org.shiroumi.server.scheduler.QuantScheduler
import java.nio.channels.ClosedChannelException

fun Route.taskSubscribe(route: String) = webSocket(route) {
    println("Client connected for live task updates.")
    try {
        launch {
            QuantScheduler.quantListFlow.collect { list ->
                val tasksJson = Json.encodeToString<TaskList>(list)
                send(Frame.Text(tasksJson))
            }
        }
        while (isActive) {
            delay(1000)
        }
    } catch (e: ClosedChannelException) {
        println("Client disconnected from task updates: ${closeReason.await()}")
        close(CloseReason(CloseReason.Codes.NORMAL, "socket closed."))
    } catch (e: Exception) {
        println("Error in /tasks/updates websocket session: ${e.message}")
        e.printStackTrace()
        close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "服务器内部发生错误"))
    } finally {
        println("Ending task updates session.")
    }
}