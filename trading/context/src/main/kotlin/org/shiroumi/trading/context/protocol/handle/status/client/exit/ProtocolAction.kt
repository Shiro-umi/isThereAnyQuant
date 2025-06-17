package org.shiroumi.trading.context.protocol.handle.status.client.exit

import kotlinx.coroutines.runBlocking
import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol
import status.client.exit

/**
 * Actual protocol-action
 * @param protocol status.client.exit
 */
fun action(context: Context, protocol: exit): Protocol? = runBlocking {
//    withContext(Dispatchers.socket_main) {
//        threadLocalJob.get().cancel()
//    }
    println("received: status.client.exit")
    return@runBlocking status.server.exit()
}