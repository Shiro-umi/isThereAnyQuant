package org.shiroumi.trading.context.protocol.protocol_handle.status.client.next

import org.shiroumi.trading.context.Context
import org.shiroumi.trading.context.protocol.model.Protocol

/**
 * Actual protocol-action
 * @param protocol status.client.next
 */
suspend fun action(context: Context, protocol: status.client.next): Protocol? {
    context.stepIterator.nextStep()
    return null
}