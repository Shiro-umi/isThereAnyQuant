package org.shiroumi.trading.context.protocol.handle.status.client.next

import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol

/**
 * Actual protocol-action
 * @param protocol status.client.next
 */
suspend fun action(context: Context, protocol: status.client.next): Protocol? {
    context.iterator.nextStep()
    return null
}