package org.shiroumi.trading.context.protocol

import kotlinx.coroutines.channels.Channel
import org.shiroumi.trading.context.protocol.model.Protocol

/**
 * ThreadLocal for sending msg to socket positive
 */
val threadLocalSendingChannel1: ThreadLocal<Channel<Protocol>> = ThreadLocal()