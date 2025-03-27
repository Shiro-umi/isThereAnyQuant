package protocol.model

import kotlinx.serialization.Serializable
import org.shiroumi.ksp.BaseProtocol

/**
 * Base socket protocol data
 */
@Serializable
@BaseProtocol
abstract class Protocol {
    abstract val cmd: String
    abstract val description: String
}