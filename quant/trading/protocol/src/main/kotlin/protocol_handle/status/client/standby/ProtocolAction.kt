package protocol_handle.status.client.standby

import protocol.model.Protocol
import org.shiroumi.trading.schedule.threadLocalSchedular

/**
 * Actual protocol-action
 * @param protocol status.client.standby
 */
fun action(protocol: status.client.standby): Protocol? {
    println("cmd: status.client.standby received")
    // todo register todo-protocol-list to schedular
    val schedular = threadLocalSchedular.get()
    val cmds = protocol.params ?: throw Exception("No tasks provided!")
    val serverCmds: List<String> = cmds.filter { "client" !in it }.also {
        if (it.size < cmds.size) println("scheduled tasks contains client's cmd, ignored.")
    }
//        .map { cmd ->
//        Class.forName("$cmd.ProtocolActionKt")
////        ""
//    }
////    schedular.registerTasks()
    return null
}


