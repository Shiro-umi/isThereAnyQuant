package org.shiroumi.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DataClassBridge

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class BridgeSerialName(val value: String)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class BridgeIgnore()

class BridgeClassProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(DataClassBridge::class.qualifiedName!!)
        val ret = mutableListOf<KSAnnotated>()
        symbols.toList().forEach {
            if (!it.validate())
                ret.add(it)
            else
                it.accept(BridgeVisitor(env), Unit)
        }
        return ret
    }
}

class BridgeClassProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return BridgeClassProcessor(environment)
    }
}

private class BridgeVisitor(val env: SymbolProcessorEnvironment) : KSVisitorVoid() {

    private val outputPackage = "org.shiroumi.generated.dataclass"

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val originalClassName = classDeclaration.simpleName.asString()
        val file = env.codeGenerator.createNewFile(
            Dependencies(
                true,
                classDeclaration.containingFile!!
            ), outputPackage, originalClassName
        )

        val content = """
package $outputPackage
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import org.shiroumi.model.database.$originalClassName
import org.shiroumi.model.ModelTypeBridge
import kotlin.reflect.KClass

@Serializable
data class $originalClassName(
${
            classDeclaration.getDeclaredProperties().mapNotNull { prop ->
                prop.annotations.find { anno -> anno.shortName.asString() == "BridgeIgnore" }?.run { return@mapNotNull null }
                "\t${
                    prop.annotations.find { anno -> anno.shortName.asString() == "BridgeSerialName" }
                        ?.let { serialNameAnno ->
                            "@SerialName(\"${serialNameAnno.arguments.first().value}\") "
                        } ?: ""
                }var ${prop.simpleName.asString()}: ${prop.type},"
            }.joinToString("\n")
        }
) : ModelTypeBridge<$originalClassName> {
    override val targetClass: KClass<$originalClassName> = $originalClassName::class
}
""".trimIndent().toByteArray()
        file.write(content)
        file.close()
    }
}