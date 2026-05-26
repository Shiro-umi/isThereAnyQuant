@file:OptIn(ExperimentalWasmDsl::class)
@file:Suppress("DEPRECATION", "unused")

import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.androidApplication)
}

group = "org.shiroumi"

kotlin {
    androidTarget()

    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets.all {
        languageSettings.optIn("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("build/generated/skillpresets")
            dependencies {
                implementation(project(":shared"))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.logging)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.material3AdaptiveNavigationSuite)
                implementation(libs.compose.material3.adaptive)
                implementation(libs.compose.material3.adaptive.layout)
                implementation(libs.compose.material3.adaptive.navigation)
                implementation(libs.compose.material3.adaptive.navigation3)
                implementation(compose.materialIconsExtended)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.jetbrains.navigation3.ui)
                implementation(libs.kamel.core)
                implementation(libs.media.kamel.kamel.image)
                implementation(libs.media.kamel.kamel.image.default)
                implementation(libs.media.kamel.kamel.decoder.svg.std)
                implementation(libs.media.kamel.kamel.decoder.image.bitmap)
                implementation(libs.squareup.okio)
                implementation(libs.squareup.okio.fakefilesystem)
                implementation(libs.multiplatform.markdown.renderer.m3)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kamel.core)
            implementation(libs.media.kamel.kamel.image)
            implementation(libs.media.kamel.kamel.image.default)
            implementation(libs.media.kamel.kamel.decoder.svg.std)
            implementation(libs.media.kamel.kamel.decoder.image.bitmap)
            implementation(libs.squareup.okio)
            implementation(libs.squareup.okio.fakefilesystem)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.kamel.core)
            implementation(libs.media.kamel.kamel.image)
            implementation(libs.media.kamel.kamel.image.default)
            implementation(libs.media.kamel.kamel.decoder.svg.std)
            implementation(libs.media.kamel.kamel.decoder.image.bitmap)
            implementation(libs.squareup.okio)
            implementation(libs.squareup.okio.fakefilesystem)
            implementation(libs.androidx.activity.compose)
        }
    }
}

// ========== Android Version Management ==========
//
// `version.properties` 是 Android 发版的版本号唯一源头：
//   - versionName 三段式（如 0.0.1），手动维护
//   - versionCode 仅在执行 release 构建任务时（assembleRelease / bundleRelease /
//     installRelease / packageRelease）自增 1 并写回文件，由 git 提交追踪发版历史
//   - debug 构建只读取当前值，不递增
//
// 自增发生在配置阶段（基于 gradle.startParameter.taskNames 识别），保证本次构建
// 打包出的 APK/AAB 就用新 versionCode，而不是"打完包再写回"。
//
val androidVersionPropsFile: File = project.file("version.properties")

fun loadAndroidVersionProps(): Properties = Properties().apply {
    if (androidVersionPropsFile.isFile) {
        androidVersionPropsFile.inputStream().use { load(it) }
    }
}

val releaseAssembleTaskNames = setOf(
    "assembleRelease",
    "bundleRelease",
    "installRelease",
    "packageRelease"
)

fun isReleaseAssembleRequested(): Boolean {
    val requested = gradle.startParameter.taskNames
    if (requested.isEmpty()) return false
    return requested.any { raw ->
        // 命令行任务名可能带项目路径前缀，如 :compose-app:assembleRelease
        val simple = raw.substringAfterLast(':')
        simple in releaseAssembleTaskNames
    }
}

data class AndroidVersion(val name: String, val code: Int)

val resolvedAndroidVersion: AndroidVersion = run {
    val props = loadAndroidVersionProps()
    val currentName = props.getProperty("versionName", "0.0.1")
    val currentCode = props.getProperty("versionCode", "1").toInt()

    if (isReleaseAssembleRequested()) {
        val bumped = currentCode + 1
        props.setProperty("versionName", currentName)
        props.setProperty("versionCode", bumped.toString())
        androidVersionPropsFile.outputStream().use { props.store(it, null) }
        logger.lifecycle("[versioning] Android versionCode bumped: $currentCode -> $bumped (versionName=$currentName)")
        AndroidVersion(currentName, bumped)
    } else {
        AndroidVersion(currentName, currentCode)
    }
}

val androidSigningPropsFile: File = project.file("keystore.properties")
val androidSigningProps: Properties = Properties().apply {
    if (androidSigningPropsFile.isFile) {
        androidSigningPropsFile.inputStream().use { load(it) }
    }
}

fun signingValue(propertyName: String, envName: String): String? =
    androidSigningProps.getProperty(propertyName)
        ?: providers.environmentVariable(envName).orNull

val releaseStoreFilePath = signingValue("storeFile", "ANDROID_KEYSTORE_PATH")
val releaseStorePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")
val hasReleaseSigningConfig = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "org.shiroumi.quant_kmp"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.shiroumi.quant_kmp"
        minSdk = 24
        targetSdk = 37
        versionCode = resolvedAndroidVersion.code
        versionName = resolvedAndroidVersion.name
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "[signing] Android release signing is not configured; " +
                        "set compose-app/keystore.properties or ANDROID_KEYSTORE_* env vars."
                )
            }
        }
    }

    // 根据 quant.mode 控制 Android release 构建：只有 release 部署时才编译 release APK
    variantFilter {
        if (name.contains("release", ignoreCase = true)) {
            val mode = providers.gradleProperty("quant.mode")
                .orElse(providers.environmentVariable("QUANT_MODE"))
                .orElse(
                    if (gradle.startParameter.taskNames.any { it.lowercase().contains("release") }) "release" else "debug"
                )
                .get()
                .lowercase()
            val isReleaseMode = mode == "release" || mode == "prod" || mode == "production"
            if (!isReleaseMode) {
                ignore = true
            }
        }
    }
}

// ========== Cross-platform SVG Assets Sync Task ==========

val syncCrossPlatformSvgAssets = tasks.register("syncCrossPlatformSvgAssets") {
    group = "build"
    description = "Sync shared SVG/vector app icon sources into Compose, Web, and Android resource directories."

    val sourceDir = rootProject.file("shared/src/commonMain/resources/app-icons")
    val composeDrawableDir = project.file("src/commonMain/composeResources/drawable")
    val webResourcesDir = project.file("src/wasmJsMain/resources")
    val androidDrawableDir = project.file("src/androidMain/res/drawable")

    val brandMarkSource = sourceDir.resolve("brand_mark.svg")
    val webIconSource = sourceDir.resolve("web/icon.svg")
    val androidVectorSources = listOf(
        "ic_launcher_background.xml",
        "ic_launcher_foreground.xml",
        "ic_launcher_monochrome.xml",
        "ic_splash_logo.xml"
    ).map { fileName -> sourceDir.resolve("android/$fileName") }

    inputs.file(brandMarkSource).withPropertyName("brandMarkSvg")
    inputs.file(webIconSource).withPropertyName("webIconSvg")
    inputs.files(androidVectorSources).withPropertyName("androidVectorSources")
    outputs.file(composeDrawableDir.resolve("brand_mark.svg")).withPropertyName("composeBrandMark")
    outputs.file(webResourcesDir.resolve("brand-mark.svg")).withPropertyName("webBrandMark")
    outputs.file(webResourcesDir.resolve("icon.svg")).withPropertyName("webIcon")
    outputs.files(androidVectorSources.map { androidDrawableDir.resolve(it.name) }).withPropertyName("androidVectors")

    doLast {
        require(sourceDir.isDirectory) {
            "Missing cross-platform SVG source directory: ${sourceDir.relativeTo(rootDir)}"
        }

        fun copyRequired(source: File, target: File) {
            require(source.isFile) {
                "Missing cross-platform SVG source file: ${source.relativeTo(rootDir)}"
            }
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
        }

        copyRequired(brandMarkSource, composeDrawableDir.resolve("brand_mark.svg"))
        copyRequired(brandMarkSource, webResourcesDir.resolve("brand-mark.svg"))
        copyRequired(webIconSource, webResourcesDir.resolve("icon.svg"))

        androidVectorSources.forEach { source ->
            val content = source.readText().trimStart()
            require(content.startsWith("""<?xml""") && content.contains("<vector")) {
                "Android app icon source must be vector XML: ${source.relativeTo(rootDir)}"
            }
            copyRequired(source, androidDrawableDir.resolve(source.name))
        }
    }
}

tasks.matching {
    it.name != syncCrossPlatformSvgAssets.name && (
        it.name.contains("resource", ignoreCase = true) ||
            it.name.contains("SourceSetPaths", ignoreCase = true) ||
            it.name.startsWith("compileKotlin") ||
            it.name.startsWith("compile")
        )
}.configureEach {
    dependsOn(syncCrossPlatformSvgAssets)
}

// ========== Cross-platform SVG Assets Sync Task End ==========

// ========== Skill Presets Code Generation Task ==========

data class SkillMetadata(
    val name: String,
    val nameZh: String,
    val description: String,
    val triggerPrompt: String
)

val skillIconMapping = mapOf(
    "trend-analysis" to "Icons.AutoMirrored.Outlined.TrendingUp",
    "support-resistance" to "Icons.AutoMirrored.Outlined.TrendingUp",
    "technical-indicators" to "Icons.Outlined.SsidChart",
    "volume-price-analysis" to "Icons.Outlined.BarChart",
    "price-action-analysis" to "Icons.Outlined.CandlestickChart",
    "pattern-recognition" to "Icons.Outlined.ShapeLine",
    "institutional-analysis" to "Icons.Outlined.CorporateFare",
    "risk-assessment" to "Icons.Outlined.Shield",
    "buy-point-analysis" to "Icons.Outlined.PlayCircle",
    "entry-exit-analysis" to "Icons.AutoMirrored.Outlined.TrendingUp"
)

fun getSkillIcon(skillId: String): String = skillIconMapping[skillId] ?: "Icons.Outlined.Analytics"

fun parseYaml(content: String, dirName: String): SkillMetadata {
    val lines = content.lines()
    var name = dirName
    var nameZh = ""
    var description = ""
    var triggerPrompt = ""
    lines.forEach { line ->
        when {
            line.startsWith("name:") -> name = line.substringAfter("name:").trim()
            line.startsWith("nameZh:") -> nameZh = line.substringAfter("nameZh:").trim()
            line.startsWith("description:") -> description = line.substringAfter("description:").trim()
            line.startsWith("triggerPrompt:") -> triggerPrompt = line.substringAfter("triggerPrompt:").trim()
        }
    }
    return SkillMetadata(name, nameZh, description, triggerPrompt)
}

fun escapeKotlinString(s: String): String {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

fun generateSkillPresetsCode(skills: List<SkillMetadata>): String {
    val usedIcons = skills.map { getSkillIcon(it.name) }.toSortedSet()
    val regularIcons = usedIcons.filter { !it.contains("AutoMirrored") }
    val autoMirroredIcons = usedIcons.filter { it.contains("AutoMirrored") }
    val sb = StringBuilder()
    sb.appendLine("// Auto-generated by Gradle Task. DO NOT MODIFY.")
    sb.appendLine()
    sb.appendLine("package org.shiroumi.quant_kmp.ui.agent.sidebar")
    sb.appendLine()
    sb.appendLine("import androidx.compose.material.icons.Icons")
    autoMirroredIcons.forEach { icon ->
        val iconName = icon.substringAfterLast(".")
        sb.appendLine("import androidx.compose.material.icons.automirrored.outlined.$iconName")
    }
    if (regularIcons.isNotEmpty()) {
        sb.appendLine("import androidx.compose.material.icons.outlined.*")
    }
    sb.appendLine()
    sb.appendLine("val generatedSkillPresets: List<SkillPreset> = listOf(")
    skills.forEachIndexed { index, skill ->
        val icon = getSkillIcon(skill.name)
        val isLast = index == skills.size - 1
        val comma = if (isLast) "" else ","
        val escapedDesc = escapeKotlinString(skill.description)
        val escapedPrompt = escapeKotlinString(skill.triggerPrompt)
        sb.appendLine("    SkillPreset(")
        sb.appendLine("        skillId = \"${skill.name}\",")
        sb.appendLine("        label = \"${skill.nameZh}\",")
        sb.appendLine("        icon = $icon,")
        sb.appendLine("        description = \"${escapedDesc}\",")
        sb.appendLine("        promptTemplate = \"${escapedPrompt}\"")
        sb.appendLine("    )$comma")
    }
    sb.appendLine(")")
    return sb.toString()
}

tasks.register("generateSkillPresets") {
    val skillSourceCandidates = listOf(
        file("${rootDir}/private/agent-analysis-skills"),
        file("${rootDir}/agent/analysis-skills"),
        file("${rootDir}/.claude/skills")
    )
    val skillsDir = skillSourceCandidates.firstOrNull { it.isDirectory } ?: skillSourceCandidates.first()
    val outputDir = file("build/generated/skillpresets")
    inputs.files(
        fileTree(skillsDir) {
            include("*/metadata.yaml")
            exclude("builtin/**")
        }
    ).withPropertyName("metadataFiles")
    outputs.dir(outputDir).withPropertyName("outputDir")
    doLast {
        outputDir.mkdirs()
        val skills = mutableListOf<SkillMetadata>()
        skillsDir.listFiles()?.filter { it.isDirectory && it.name != "builtin" }?.forEach { skillDir ->
            val metadataFile = File(skillDir, "metadata.yaml")
            if (metadataFile.exists()) {
                val content = metadataFile.readText()
                val metadata = parseYaml(content, skillDir.name)
                skills.add(metadata)
            }
        }
        skills.sortBy { it.name }
        val outputFile = File(outputDir, "GeneratedSkillPresets.kt")
        val generated = generateSkillPresetsCode(skills)
        if (!outputFile.exists() || outputFile.readText() != generated) {
            outputFile.writeText(generated)
        }
        println("Generated: ${outputFile.absolutePath} (${skills.size} skills)")
    }
}

tasks.withType<KotlinCompilationTask<*> >().configureEach {
    dependsOn("generateSkillPresets")
}
// ========== Skill Presets Code Generation Task End ==========
