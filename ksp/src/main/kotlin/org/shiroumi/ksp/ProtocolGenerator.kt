package org.shiroumi.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ProtocolClassProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ProtocolGenerator(environment)
    }
}

class ProtocolGenerator(
    private val env: SymbolProcessorEnvironment
) : SymbolProcessor {

    @Serializable
    data class Protocol(
        val name: String,
        val functions: List<Function>
    )

    @Serializable
    data class Function(
        val name: String,
        val parameters: List<Parameter>,
        val returnType: String
    )

    @Serializable
    data class Parameter(
        val name: String,
        val type: String
    )

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation("org.shiroumi.ksp.GenerateProtocol")
        val protocols = symbols.filterIsInstance<KSClassDeclaration>().map { classDec ->
            Protocol(
                name = classDec.simpleName.asString(),
                functions = classDec.getAllFunctions().filter { !it.isConstructor() }.map { funcDec ->
                    Function(
                        name = funcDec.simpleName.asString(),
                        parameters = funcDec.parameters.map { param ->
                            Parameter(
                                name = param.name?.asString() ?: "",
                                type = param.type.resolve().declaration.qualifiedName?.asString() ?: ""
                            )
                        },
                        returnType = funcDec.returnType?.resolve()?.declaration?.qualifiedName?.asString() ?: ""
                    )
                }.toList()
            )
        }.toList()

        if (protocols.isNotEmpty()) {
            val json = Json { prettyPrint = true }
            val protocolFile = File("script_protocol.json")
            protocolFile.writeText(json.encodeToString(protocols))
            env.logger.info("Generated protocol to ${protocolFile.absolutePath}")
        }

        return emptyList()
    }

    private fun KSFunctionDeclaration.isConstructor(): Boolean {
        return simpleName.asString() == "<init>"
    }
}
