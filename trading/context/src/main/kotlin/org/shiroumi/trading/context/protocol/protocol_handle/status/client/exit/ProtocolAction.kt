package org.shiroumi.trading.context.protocol.protocol_handle.status.client.exit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol
import socket_main
import status.client.exit
import threadLocalJob

/**
 * Actual protocol-action
 * @param protocol status.client.exit
 */
fun action(context: Context, protocol: exit): Protocol? = runBlocking {
    withContext(Dispatchers.socket_main) {
        threadLocalJob.get().cancel()
    }
    println("received: status.client.exit")
    return@runBlocking status.server.exit()
}