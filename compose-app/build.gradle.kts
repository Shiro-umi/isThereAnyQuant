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

    // iOS：covers 真机(arm64) 与 Apple Silicon 模拟器(simulatorArm64)。
    // 不含 iosX64(Intel 模拟器)——Kotlin 上游已废弃 Apple x86_64，Compose Multiplatform 自
    // 1.11 起从所有模块移除 iosX64 artifact（adaptive-navigation/navigation3 也不再发布该平台）。
    // 按项目「只能向上对齐、禁止降级」原则，跟随上游现状仅保留两个有效 target，覆盖 M4 Max 全部使用场景。
    // 各自输出名为 ComposeApp 的静态 framework，供 iosApp/ 下的 Xcode 壳工程链接。
    // 静态 framework 让 Compose 资源与 Kotlin 运行时直接打入 app 二进制，避免动态库嵌入步骤。
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
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
        iosMain.dependencies {
            // iOS 网络走 Ktor Darwin 引擎（基于 NSURLSession），与 Android CIO / Web Js 同走 configureCommon()。
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.darwin)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kamel.core)
            implementation(libs.media.kamel.kamel.image)
            implementation(libs.media.kamel.kamel.image.default)
            implementation(libs.media.kamel.kamel.decoder.svg.std)
            implementation(libs.media.kamel.kamel.decoder.image.bitmap)
            implementation(libs.squareup.okio)
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

// ========== iOS Version Sync Task ==========
//
// iOS 与 Android 共用同一版本号事实来源 version.properties（经 resolvedAndroidVersion
// 解析，含 release 构建自增逻辑）。本任务把当前 versionName / versionCode 写进
// iosApp/Configuration/Config.xcconfig 的 APP_VERSION / APP_BUILD 两行，使 Xcode 构建
// 时通过 $(APP_VERSION) / $(APP_BUILD) 注入 Info.plist 的 MARKETING_VERSION /
// CURRENT_PROJECT_VERSION。只替换这两行，保留 xcconfig 其余注释与配置，避免整文件覆写。
//
val syncIosVersion = tasks.register("syncIosVersion") {
    group = "build"
    description = "Sync versionName/versionCode from version.properties into iosApp Config.xcconfig."

    val xcconfigFile = rootProject.file("iosApp/Configuration/Config.xcconfig")
    val versionName = resolvedAndroidVersion.name
    val versionCode = resolvedAndroidVersion.code

    inputs.file(androidVersionPropsFile).withPropertyName("versionProperties")
    outputs.file(xcconfigFile).withPropertyName("iosXcconfig")

    doLast {
        require(xcconfigFile.isFile) {
            "Missing iOS xcconfig: ${xcconfigFile.relativeTo(rootDir)}"
        }
        val updated = xcconfigFile.readLines().joinToString("\n") { line ->
            when {
                line.trimStart().startsWith("APP_VERSION") -> "APP_VERSION = $versionName"
                line.trimStart().startsWith("APP_BUILD") -> "APP_BUILD = $versionCode"
                else -> line
            }
        } + "\n"
        xcconfigFile.writeText(updated)
        logger.lifecycle("[versioning] iOS Config.xcconfig synced: APP_VERSION=$versionName APP_BUILD=$versionCode")
    }
}

// Xcode 通过 embedAndSignAppleFrameworkForXcode 触发 Kotlin framework 构建；把版本同步
// 挂在其之前，保证每次 Xcode 构建拿到的 xcconfig 版本号与 Android 完全一致。
tasks.matching { it.name == "embedAndSignAppleFrameworkForXcode" }.configureEach {
    dependsOn(syncIosVersion)
}

// ========== iOS Version Sync Task End ==========

// ========== iOS AppIcon Rasterization Task ==========
//
// iOS AppIcon 必须是不透明位图：系统进程在安装时从编译后的 Assets.car 读取位图渲染
// 桌面/设置/App Store 图标，运行时 SVG 渲染参与不到这一步；带 alpha 通道的图标会被
// App Store 资源校验拒绝。因此把共享 SVG 源 app-icons/ios/icon.svg 栅格化为 1024
// 无 alpha PNG 写入 AppIcon.appiconset。
//
// 该任务与跨平台文本复制（syncCrossPlatformSvgAssets）刻意分离：栅格化依赖 macOS
// 专有工具 qlmanage，只能在 macOS 上执行，不纳入通用资源同步链路。产物 PNG 随
// iosApp/ 一起入仓（低频图标资源，构建必需），CI/非 macOS 环境直接复用已入仓产物。
//
val syncIosAppIcon = tasks.register<Exec>("syncIosAppIcon") {
    group = "build"
    description = "Rasterize shared iOS app icon SVG into a non-alpha 1024 PNG for AppIcon.appiconset (macOS only)."

    val iconSvg = rootProject.file("shared/src/commonMain/resources/app-icons/ios/icon.svg")
    val rasterizeScript = rootProject.file("iosApp/scripts/rasterize-appicon.py")
    val appIconPng = rootProject.file("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png")

    inputs.file(iconSvg).withPropertyName("iosIconSvg")
    inputs.file(rasterizeScript).withPropertyName("rasterizeScript")
    outputs.file(appIconPng).withPropertyName("appIconPng")

    onlyIf("仅 macOS 可执行栅格化") {
        org.gradle.internal.os.OperatingSystem.current().isMacOsX
    }
    commandLine("/usr/bin/python3", rasterizeScript.absolutePath, iconSvg.absolutePath, appIconPng.absolutePath)
}

// ========== iOS AppIcon Rasterization Task End ==========

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

// ========== WasmJS Task Ordering Workaround ==========
// Kotlin/Wasm plugin 的 production/development webpack 与对应 sync 任务共用同一输出目录，
// Gradle 在同 build 中同时触发 production 与 development 路径时报 implicit dependency 验证错误。
// 通过 mustRunAfter 建立全序：production sync -> production webpack -> development sync -> development webpack，
// 避免读写冲突。待上游插件修复后可移除。
tasks.matching { it.name == "wasmJsDevelopmentExecutableCompileSync" }.configureEach {
    mustRunAfter("wasmJsBrowserProductionWebpack")
}
tasks.matching { it.name == "wasmJsBrowserDevelopmentWebpack" }.configureEach {
    mustRunAfter("wasmJsProductionExecutableCompileSync")
}
// ========== WasmJS Task Ordering Workaround End ==========
