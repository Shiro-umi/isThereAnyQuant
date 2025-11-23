package org.shiroumi.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class FunctionCall(
    val type: String = "function",
    val description: String
)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class Description(
    val value: String
)


class FunctionCallingGenerator(private val env: SymbolProcessorEnvironment) : SymbolProcessor {

    private val collected = mutableListOf<Pair<KSFunctionDeclaration, String>>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(FunctionCall::class.qualifiedName!!)
        val ret = mutableListOf<KSAnnotated>()
        try {
            symbols.toList().forEach {
                if (!it.validate()) ret.add(it)
                else it.accept(FunctionCallingVisitor { collected }, Unit)
            }
            if (collected.isEmpty()) return ret
            val packageName = "org.shiroumi.ai.function"
            val fileName = "FunctionCallingToolsDef"
            val file = env.codeGenerator.createNewFile(
                Dependencies.ALL_FILES,
                packageName = packageName,
                fileName = fileName
            )
// 使用三重引号定义包含toolJson内容的字符串
            val content = """package org.shiroumi.ai.function
                
import kotlinx.serialization.json.*
${
                collected.joinToString("\n        ") { c ->
                    val functionName = c.first.qualifiedName?.asString()
                    "import $functionName"
                }
            }

val llmTools: JsonArray = buildJsonArray {
${
                collected.joinToString("\n        ") { c ->
                    "add(${c.second})"
                }
            }
}

suspend fun functionCall(name: String, params: JsonObject): String? = when (name) {
${
                collected.joinToString("\n        ") { c ->
                    generateFunctionCall(c.first)
                }
            }
    
    else -> null
}
"""
            file.write(content.toByteArray())
            file.close()
        } catch (e: Exception) {
//            e.printStackTrace()
        }
        return ret
    }

    private fun generateFunctionCall(f: KSFunctionDeclaration): String {
        val fName = f.simpleName.getShortName()
        val param = f.parameters.map { p -> p.name?.asString() to p.type.resolve().declaration.simpleName.asString() }
        val params = param.map { p ->
            val convert = when (p.second) {
                "Int" -> "?.jsonPrimitive?.intOrNull ?: 0"
                "Long" -> "?.jsonPrimitive?.longOrNull ?: 0L"
                "Float" -> "?.jsonPrimitive?.floatOrNull ?: 0f"
                "Double" -> "?.jsonPrimitive?.doubleOrNull ?: 0.0"
                "Boolean" -> "?.jsonPrimitive?.booleanOrNull ?: false"
                else -> "!!.jsonPrimitive.content"
            }
            p.first to "val ${p.first}: ${p.second} = params[\"${p.first}\"]$convert"
        }
        val content = """
            "$fName" -> {
                ${
            params.joinToString("\n        ") { p -> p.second }
        }   
        $fName(${params.joinToString(",") { p -> "${p.first} = ${p.first}" }})
        }
        """.trimIndent()
        return content
    }
}

class FunctionCallingProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return FunctionCallingGenerator(environment)
    }
}

private class FunctionCallingVisitor(
    val collectedProvider: () -> MutableList<Pair<KSFunctionDeclaration, String>>
) : KSVisitorVoid() {

    @OptIn(KspExperimental::class)
    override fun visitFunctionDeclaration(function: KSFunctionDeclaration, data: Unit) {
        val anno = function.getAnnotationsByType(FunctionCall::class).first()
        val builder = """
buildJsonObject {
put("type", "function")
put("function", buildJsonObject {
    put("name", "${function.simpleName.asString()}")
    put("description", "${anno.description}")
    put("parameters", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            ${
            function.parameters.joinToString("\n        ") { param ->
                val name = param.name?.asString()
                val type = param.type.resolve().declaration.simpleName.asString()
                val a = param.getAnnotationsByType(Description::class).first()
                val jsonType = when (type) {
                    "String" -> "string"
                    "Boolean" -> "boolean"
                    else -> "integer"
                }
                """put("$name", buildJsonObject {
                        put("type", "$jsonType")
                        put("description", "${a.value}")
                    }) """
            }
        }
        })
        putJsonArray("required") {
            ${function.parameters.joinToString("\n") { f -> "add(\"${f.name!!.asString()}\")" }}
        }
    })
})
        }
        """.trimIndent()
        collectedProvider().add(function to builder)
    }
}