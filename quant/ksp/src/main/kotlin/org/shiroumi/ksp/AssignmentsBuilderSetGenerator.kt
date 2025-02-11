package org.shiroumi.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class KtormAssignments

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class KtormAssignmentsInclude

class AssignmentsBuilderSetProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(KtormAssignments::class.qualifiedName!!)
        val ret = mutableListOf<KSAnnotated>()
        symbols.toList().forEach {
            if (!it.validate())
                ret.add(it)
            else
                it.accept(AssignmentsVisitor(env), Unit)
        }
        return ret
    }
}

class AssignmentsBuilderSetProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return AssignmentsBuilderSetProcessor(environment)
    }
}

private class AssignmentsVisitor(val env: SymbolProcessorEnvironment) : KSVisitorVoid() {

    private val outputPackage = "org.shiroumi.generated.assignments"

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        val originalClassName = classDeclaration.simpleName.asString()
        val typeParam = classDeclaration.superTypes.toList()[0].element!!.typeArguments[0].type!!
        val file = env.codeGenerator.createNewFile(
            Dependencies(
                true,
                classDeclaration.containingFile!!
            ), outputPackage, "${originalClassName}Setter"
        )
        val content = """
package $outputPackage

import org.ktorm.dsl.AssignmentsBuilder
import ${classDeclaration.qualifiedName!!.getQualifier()}.${classDeclaration.simpleName.getShortName()}
import ${typeParam.resolve().declaration.qualifiedName!!.getQualifier()}.$typeParam

fun AssignmentsBuilder.set$typeParam(table: $originalClassName, new: $typeParam) {
        ${
            classDeclaration.getAllProperties().filter { prop ->
                prop.annotations.map { anno -> anno.shortName.asString() }.contains("KtormAssignmentsInclude")
            }.map { prop -> 
                "set(table.${prop.simpleName.getShortName()}, new.${prop.simpleName.getShortName()})"
            }.joinToString("\n\t\t")
        }
}
        """.trimIndent().toByteArray()
        file.write(content)
        file.close()
    }
}