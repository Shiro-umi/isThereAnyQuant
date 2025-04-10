package protocol_handle.status.client.standby

import kotlinx.coroutines.flow.asFlow
import org.shiroumi.trading.schedule.SingleStepTask
import org.shiroumi.trading.schedule.threadLocalSchedular
import protocol.model.Protocol
import threadLocalSendingChannel
import kotlin.coroutines.coroutineContext

/**
 * Actual protocol-action
 * @param protocol status.client.standby
 */
suspend fun action(protocol: status.client.standby): Protocol? {
    println("cmd: status.client.standby received")
    val schedular = threadLocalSchedular.get()
    val cmds = protocol.params ?: throw Exception("No tasks provided!")
    val serverCmds: List<String> = cmds.filter { "client" !in it }.also {
        if (it.size < cmds.size) println("scheduled tasks contains client's cmd, ignored.")
    }
//        .toMutableList().apply { add("status.server.exit") }
    val actions = serverCmds.map { cmd ->
        SingleStepTask(sendingChannel = threadLocalSendingChannel.get()) { channel ->
            println("action execute: $cmd")
            val cls = Class.forName("protocol_handle.$cmd.ProtocolActionKt")
            val mtd = cls.methods.filter { it.name == "action" }[0]
            mtd.isAccessible = true
            val sendingProtocol = Class.forName(cmd).constructors.first().newInstance() as Protocol
            mtd.invoke(null, sendingProtocol)
            println("arguments: ${mtd.parameters.toList()}")
            channel.send(sendingProtocol)
            println("threadLocalSendFlow emitted $protocol")
        }
    }.asFlow()

    schedular.registerTasks(actions)
    return null
}


