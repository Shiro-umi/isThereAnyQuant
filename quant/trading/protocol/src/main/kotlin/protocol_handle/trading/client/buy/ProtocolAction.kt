package protocol_handle.trading.client.buy

import protocol.model.Protocol
import trading.client.buy


/**
 * Actual protocol-action
 * @param protocol trading.client.buy
 */
fun action(protocol: buy): Protocol? {
    println("action received protocol: $protocol")
    return protocol
}