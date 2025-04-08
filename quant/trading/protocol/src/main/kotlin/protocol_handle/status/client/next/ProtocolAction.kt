package protocol_handle.status.client.next

import org.shiroumi.trading.schedule.threadLocalSchedular
import protocol.model.Protocol

/**
 * Actual protocol-action
 * @param protocol status.client.next
 */
suspend fun action(protocol: status.client.next): Protocol? {
    threadLocalSchedular.get().nextStep()
    return null
}