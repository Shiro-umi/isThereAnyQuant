package protocol_handle.trading.server.do_at_9_25

import protocol.model.Protocol
import trading.client.buy


/**
 * Actual protocol-action
 * @param protocol trading.client.buy
 */
fun action(protocol: trading.server.do_at_9_25): Protocol? {
    println("action received protocol: $protocol")
    return protocol
}