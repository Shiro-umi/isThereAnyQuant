package org.shiroumi.trading.context.protocol.protocol_handle.status.client.standby

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol

/**
 * Actual protocol-action
 * @param protocol status.client.standby
 */
fun action(context: Context, protocol: status.client.standby): Protocol? {
    println("cmd: status.client.standby received")

    val cmds = protocol.params ?: throw Exception("No tasks provided!")
    val serverCmds: List<String> = cmds.filter { "client" !in it }.also {
        if (it.size < cmds.size) println("scheduled tasks contains client's cmd, ignored.")
    }
    val actions = serverCmds.map { cmd ->
        suspend {
            println("action execute: $cmd")
            val cls = Class.forName("protocol_handle.$cmd.ProtocolActionKt")
            val mtd = cls.methods.filter { it.name == "action" }[0]
            mtd.isAccessible = true
            val sendingProtocol = Class.forName(cmd).constructors.first().newInstance() as Protocol
            mtd.invoke(null, sendingProtocol)
            context.socketManager.sendProtocol(sendingProtocol)
            println("threadLocalSendFlow emitted $protocol")
        }
    }.asFlow()

    runBlocking { context.stepIterator.submitTasks(actions) }
    return null
}


