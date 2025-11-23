import java.util.Properties
import kotlin.apply
import kotlin.collections.component1
import kotlin.collections.component2

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    js{
        browser()
    }
    jvm()
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(layout.buildDirectory.dir("generated/sources/kotlin/main"))
            dependencies {
                implementation(libs.kotlin.serialization.json)
                implementation(libs.kotlin.coroutines.core)
                implementation(libs.kotlin.datetime)
            }
        }
    }
    sourceSets.commonMain.dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    }
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

tasks.register("generateLocalProperties") {
    group = "build"
    description = "Generate Kotlin constants from local.properties"

    val generatedDir = layout.buildDirectory.dir("generated/sources/kotlin/main")
    outputs.dir(generatedDir)

    doLast {
        val packageName = "org.shiroumi.configs"
        val className = "BuildConfigs"
        val file = generatedDir.get().asFile.resolve("${packageName.replace('.', '/')}/$className.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package $packageName

            object $className {
                ${
                localProperties.entries.joinToString("\n    ") { (key, value) ->
                    "const val ${key.toString().uppercase().replace('.', '_')} = \"${value}\""
                }
            }
            }
        """.trimIndent()
        )
    }
}


tasks.forEach {
    println(it.name)
}
tasks.named("build") {
    dependsOn("generateLocalProperties")
}

