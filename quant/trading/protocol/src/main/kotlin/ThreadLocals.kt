import kotlinx.coroutines.channels.Channel
import protocol.model.Protocol

/**
 * ThreadLocal for sending msg to socket positive
 */
val threadLocalSendingChannel: ThreadLocal<Channel<Protocol>> = ThreadLocal()