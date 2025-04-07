package protocol.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.shiroumi.ksp.BaseProtocol
import org.shiroumi.protocol.getSerializer

/**
 * Base socket protocol data
 */
@BaseProtocol
@Serializable(with = ProtocolSerializer::class)
abstract class Protocol {
    abstract val cmd: String
    abstract val description: String
}

object ProtocolSerializer : JsonContentPolymorphicSerializer<Protocol>(Protocol::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Protocol> {
        val cmd = (element as JsonObject)["cmd"]!!.jsonPrimitive.content
        return getSerializer(cmd)
    }
}