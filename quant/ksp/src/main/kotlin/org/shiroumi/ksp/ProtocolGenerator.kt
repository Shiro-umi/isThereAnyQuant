package org.shiroumi.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.shiroumi.configs.BuildConfigs
import java.io.File

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class BaseProtocol()

class ProtocolClassProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(BaseProtocol::class.qualifiedName!!)
        val ret = mutableListOf<KSAnnotated>()
        symbols.toList().forEach {
            if (!it.validate())
                ret.add(it)
            else
                it.accept(ProtocolVisitor(env), Unit)
        }
        return ret
    }
}

class ProtocolClassProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ProtocolClassProcessor(environment)
    }
}

private class ProtocolVisitor(val env: SymbolProcessorEnvironment) : KSVisitorVoid() {

    private val detectedProtocol = mutableListOf<DetectedProtocol>()

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val protocolFile = File(BuildConfigs.SCRIPT_BASE_PROTOCOL_DIR)
        val jsonStr = buildString {
            protocolFile.readLines().forEach { line ->
                appendLine(line.replace("\n", ""))
            }
        }
        val protocolJson = Json.decodeFromString(JsonObject.serializer(), jsonStr)

        protocolJson.entries.forEach { (category, element) ->
            (element as JsonObject).entries.forEach { (caller, element) ->
                (element as JsonArray).forEach { element ->
                    element as JsonObject
                    val callbackName = element.extractJsonObject("callback")?.let {
                        return@let it.processProtocol("callback", category, caller, classDeclaration)
                    }
                    element.processProtocol("caller", category, caller, classDeclaration, callbackName)
                }
            }
        }
        generateProtocolDecoder(classDeclaration)
    }

    private fun generateProtocolDecoder(
        classDeclaration: KSClassDeclaration,
    ) {
        val packageName = "org.shiroumi.protocol"
        val fileName = "ProtocolDecoder"
        val file = env.codeGenerator.createNewFile(
            Dependencies(true, classDeclaration.containingFile!!),
            packageName = packageName,
            fileName = fileName
        )
        val content = """
package $packageName

import protocol.model.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
import kotlinx.serialization.SerializationException

private val $fileName = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    serializersModule = SerializersModule {
        polymorphic(Protocol::class) {
            ${
            detectedProtocol.joinToString("\n            ") { protocol ->
                "subclass(${protocol.cmd}::class)   // ${protocol.description}"
            }
            }
        }
    }
}

fun handleProtocol(
    protocolJson: String
) = ProtocolDecoder.decodeFromString<Protocol>(protocolJson).let { p ->
    when (p) {
        ${
            detectedProtocol
                .joinToString("\n        ") { protocol ->
                    "is ${protocol.cmd} -> protocol_handle.${protocol.cmd}.action(p)"
                }
        }
        else -> null
    }
} 

fun serializeProtocol(
    p: Protocol
) = when (p) {
    ${
            detectedProtocol
                .joinToString("\n    ") { protocol ->
                    "is ${protocol.cmd} -> $fileName.encodeToString<${protocol.cmd}>(p)"
                }
        }
    else -> Unit
}

@Suppress("USELESS_IS_CHECK")
fun getSerializer(cmd: String) = when (cmd) {
    ${
            detectedProtocol
                .joinToString("\n    ") { protocol ->
                    "\"${protocol.cmd}\" -> ${protocol.cmd}.serializer()"
                }
        }
    else -> throw SerializationException("Unknown command: ${"\$cmd"}")
}
        """.trimIndent().toByteArray()
        file.write(content)
        file.close()
    }

    private fun JsonObject.processParams(
        packageName: String,
        classDeclaration: KSClassDeclaration,
    ): String? {
        entries.firstOrNull { (k, _) -> k == "type" }?.run {
            val subtype = extractField("subtype")?.toKotlinType().toString()
            val paramType = extractField("type")?.toKotlinType(subtype) as String?
            return paramType
        }
        val paramName = entries.first().key
        val param = entries.first().value as JsonObject
        val packageName = if ("model" !in packageName) "$packageName.model" else packageName
        val fields = mutableListOf<ParamsDef>()
        param.forEach { (fieldName, value) ->
            value as JsonObject
            val type: String = value.extractField("type")!!
            val subtype: JsonObject? = value.extractJsonObject("subtype")
            val subtypeName = subtype?.processParams(packageName, classDeclaration)
            val default: String? = value.extractField("default")
            fields.add(ParamsDef(fieldName, type, subtype, subtypeName, default))
        }
        val file = env.codeGenerator.createNewFile(
            Dependencies(true, classDeclaration.containingFile!!),
            packageName = packageName,
            fileName = paramName
        )
        val content = """
@file:Suppress("RemoveRedundantQualifierName", "PropertyName", "ClassName")

package $packageName

import protocol.model.*
import kotlinx.serialization.Serializable

@Serializable
data class $paramName(
    ${
            fields.joinToString(",\n    ") { (fieldName, type, _, subtypeName, default) ->
                val defaultValue = if (type == "list") "listOf()" else default
                val kotlinType = type.toKotlinType(fullSubtype = subtypeName?.let { "$packageName.$it" })
                "val $fieldName: $kotlinType ${if (defaultValue != null) " = $defaultValue" else ""}"
            }
        }
)
            """.trimIndent().toByteArray()
        file.write(content)
        file.close()
        return paramName
    }

    private fun String.toKotlinType(
        fullSubtype: String? = "Any?",
    ) = when (this) {
        "string" -> "String"
        "int" -> "Int"
        "long" -> "Long"
        "float" -> "Float"
        "double" -> "Double"
        "boolean" -> "Boolean"
        "list" -> "List<$fullSubtype>"
        else -> Unit
    }


    private fun JsonObject.processProtocol(
        parentName: String,
        category: String,
        caller: String,
        classDeclaration: KSClassDeclaration,
        callbackName: String? = null
    ): String? {
        val packageName = "$category.${if (parentName == "callback") caller.callerInverse else caller}"
        val fileName = extractField("cmd")
        val description = extractField("description")
        val params = extractJsonObject("params")?.let {
            return@let it.processParams(packageName, classDeclaration)
        }

        val file = env.codeGenerator.createNewFile(
            Dependencies(true, classDeclaration.containingFile!!),
            packageName = packageName,
            fileName = fileName!!
        )
        val content = """
package $packageName

import protocol.model.*
import kotlinx.serialization.*

@Serializable
@Polymorphic
class $fileName : Protocol() {
    override val cmd: String = "$packageName.$fileName"
    override val description: String = "$description"
    ${if (!params.isNullOrBlank()) "var params: ${if (!params.isBaseKotlinType) "$packageName.model." else ""}$params? = null" else ""}
    ${if (!callbackName.isNullOrBlank()) "val callback: $category.${caller.callerInverse}.$callbackName = $category.${caller.callerInverse}.$callbackName()" else ""}
}  
        """.trimIndent().toByteArray()
        file.write(content)
        file.close()
        detectedProtocol.add(
            DetectedProtocol(
                cmd = "$packageName.$fileName",
                description = "$description",
            )
        )
        return if (parentName == "callback") fileName else null
    }

    fun JsonObject.extractField(key: String): String? {
        val field = get(key)
        if (field is JsonNull) return null
        val res = "${get(key)}".replace("\"", "")
        return res.ifBlank { null }
    }

    fun JsonObject.extractJsonObject(key: String): JsonObject? {
        val field = get(key)
        if (field == null || field is JsonNull) return null
        return field as JsonObject
    }

    val String.isBaseKotlinType: Boolean
        get() = contains("Int") || contains("Long") || contains("Float") ||
                contains("Double") || contains("Boolean") || contains("String") || contains("List")

    val String.callerInverse: String
        get() = when (this) {
            "server" -> "client"
            "client" -> "server"
            else -> this
        }

    data class ParamsDef(
        val name: String,
        val type: String,
        val subtype: JsonObject?,
        val subtypeName: String?,
        val default: String?,
    )

    data class DetectedProtocol(
        val cmd: String,
        val description: String
    ) {
        val isClientProtocol: Boolean
            get() = ".client." in cmd && "_cb" !in cmd
    }
}