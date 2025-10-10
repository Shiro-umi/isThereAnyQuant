@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JsSourceMapEmbedMode
import java.util.Properties
import kotlin.apply
import kotlin.collections.component1
import kotlin.collections.component2

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

group = "org.shiroumi"

kotlin {
    js {
        browser()
        useEsModules()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(layout.buildDirectory.dir("generated/sources/kotlin/main"))
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.material3AdaptiveNavigationSuite)
//            implementation(compose.materialIconsExtended)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation("media.kamel:kamel-core:1.0.8")
                implementation("media.kamel:kamel-image:1.0.8")
                implementation("media.kamel:kamel-image-default:1.0.8")
                implementation("media.kamel:kamel-decoder-svg-std:1.0.8")
                implementation("media.kamel:kamel-decoder-image-bitmap:1.0.8")
                implementation("com.squareup.okio:okio:3.17.0-SNAPSHOT")
                implementation("com.squareup.okio:okio-fakefilesystem:3.17.0-SNAPSHOT")
            }
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation("media.kamel:kamel-core-js:1.0.8")
            implementation("media.kamel:kamel-image-js:1.0.8")
            implementation("media.kamel:kamel-decoder-svg-std-js:1.0.8")
            implementation("media.kamel:kamel-decoder-image-bitmap-js:1.0.8")
            implementation("com.squareup.okio:okio-js:3.17.0-SNAPSHOT")
            implementation("com.squareup.okio:okio-fakefilesystem-js:3.17.0-SNAPSHOT")
//            implementation(libs.ktorfit.lib)
        }

    }
}

tasks.named("compileKotlinJs") {
    dependsOn("generateLocalProperties")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

tasks.register("generateLocalProperties") {
    group = "build"
    description = "Generate Kotlin constants from local.properties"
    val generatedDir = layout.projectDirectory.dir("src/commonMain/kotlin")
//    val generatedDir = layout.buildDirectory.dir("generated/sources/kotlin/main")
    outputs.dir(generatedDir)
    doLast {
        val packageName = "org.shiroumi.quant_kmp"
        val className = "BuildConfigs"
        val file = generatedDir.asFile.resolve("${packageName.replace('.', '/')}/$className.kt")
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