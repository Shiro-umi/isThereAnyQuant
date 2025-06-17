package org.shiroumi.trading.context.protocol.handle.status.client.standby

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol

/**
 * Actual protocol-action
 * @param protocol status.client.standby
 */
fun action(context: Context, protocol: status.client.standby): Protocol? = runBlocking {
    println("cmd: status.client.standby received")

    val cmds = protocol.params ?: throw Exception("No tasks provided!")
    val serverCmds: List<String> = cmds.filter { "client" !in it }.also {
        if (it.size < cmds.size) println("scheduled tasks contains client's cmd, ignored.")
    }
    val actions: List<suspend () -> Unit> = serverCmds.map { cmd ->
        suspend {
            val cls = Class.forName("org.shiroumi.trading.context.protocol.handle.$cmd.ProtocolActionKt")
            val mtd = cls.methods.filter { it.name == "action" }[0]
            mtd.isAccessible = true
            val sendingProtocol = Class.forName(cmd).constructors.first().newInstance() as Protocol
            val resProtocol = mtd.invoke(null, context, sendingProtocol)
            (resProtocol as? Protocol)?.let {
                context.socketManager.sendProtocol(resProtocol)
            }
            Unit
        }
    }
    context.iterator.submitTasks(actions)
    null
}


