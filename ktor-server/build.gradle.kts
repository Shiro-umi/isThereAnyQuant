@file:Suppress("VulnerableLibrariesLocal")

import com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer
import com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer.MergeStrategy
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream


plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.ksp)
    java
    application
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/kds/kotlin-ds-maven")
}

dependencies {
    implementation(project(":network"))
    implementation(project(":database"))
    implementation(project(":backtest"))
    implementation(project(":shared"))
    implementation(project(":strategy-server:client"))
    implementation(project(":strategy-server:core"))
    api(project(":shared"))
    implementation(project(":agent"))
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.datetime)
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.client.cio)
    implementation("io.ktor:ktor-server-auto-head-response:3.3.0")
    implementation("io.ktor:ktor-server-host-common:3.3.0")
    implementation("io.ktor:ktor-server-status-pages:3.3.0")
    implementation("io.ktor:ktor-server-compression:3.3.0")
    implementation("io.ktor:ktor-server-caching-headers:3.3.0")
    implementation("io.ktor:ktor-server-conditional-headers:3.3.0")
    implementation("io.ktor:ktor-server-partial-content:3.3.0")
    implementation("io.ktor:ktor-server-default-headers:3.3.0")
    implementation("io.ktor:ktor-server-auth:3.3.0")
    implementation("io.ktor:ktor-server-auth-jwt:3.3.0")
    implementation("io.ktor:ktor-server-cors:3.3.0")
    implementation("io.ktor:ktor-server-forwarded-header:3.3.0")
    ksp(project(":ksp"))
    implementation(project(":ksp"))
    implementation(libs.selenium.java)
    implementation(libs.selenium.devtools.v139)
    implementation(libs.logback)
    // TODO: 使用 module:agent 替换 - koog 依赖已移除
    // implementation(libs.jetbrains.koog)
    implementation(libs.kaml)
    implementation("org.jetbrains.kotlinx:kandy-lets-plot:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlin-statistics-jvm:0.4.0")
    
    // BCrypt 密码加密
    implementation("at.favre.lib:bcrypt:0.10.2")
    
    // JWT 支持
    implementation("com.auth0:java-jwt:4.4.0")

    // 测试依赖
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation(libs.ktor.client.content.negotiation)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
    sourceSets.main.get().kotlin.srcDir("build/generated/ksp/main/kotlin")
}

tasks.test {
    useJUnitPlatform()
}


application {
    mainClass.set("org.shiroumi.server.MainKt")
}

// 与 shared 模块保持一致：
// - 本地开发 / IDE run / 普通 compile 默认走 debug
// - 只有明确的 release 任务才默认走 release
//
// 这样本地 `ktorServer` 和前端生成环境不会再一边 debug 一边 release。
val defaultQuantMode = if (
    gradle.startParameter.taskNames.any { taskName ->
        taskName.lowercase().contains("release")
    }
) {
    "release"
} else {
    "debug"
}
val quantMode = providers.gradleProperty("quant.mode")
    .orElse(providers.environmentVariable("QUANT_MODE"))
    .orElse(defaultQuantMode)
    .map { mode ->
        when (mode.lowercase()) {
            "dev", "debug" -> "debug"
            "debug-wan", "wan" -> "debug-wan"
            "prod", "production", "release" -> "release"
            else -> error("Unsupported quant.mode=$mode, expected debug, debug-wan or release")
        }
    }
val webAppResourcesDir = layout.buildDirectory.dir("generated/resources/webapp")
val webAppStaticDir = webAppResourcesDir.map { it.dir("static") }
fun webDistributionTask(development: Boolean): String = when {
    development -> ":compose-app:wasmJsBrowserDevelopmentExecutableDistribution"
    else -> ":compose-app:wasmJsBrowserDistribution"
}

fun webDistributionDir(development: Boolean) = project(":compose-app").layout.buildDirectory.dir(
    when {
        development -> "dist/wasmJs/developmentExecutable"
        else -> "dist/wasmJs/productionExecutable"
    }
)

// 开发模式：快速构建，不压缩
val copyWebAppDev by tasks.registering(Copy::class) {
    dependsOn(webDistributionTask(development = true), ":cli:installDist")
    from(webDistributionDir(development = true))
    into(webAppStaticDir)
    doFirst {
        delete(webAppStaticDir)
    }
    doLast {
        injectCacheVersion(webAppStaticDir.get().asFile, "dev")
    }
}

// 生产模式：完整构建，仅 CI/发布时使用
val copyWebApp by tasks.registering(Copy::class) {
    dependsOn(webDistributionTask(development = false), ":cli:installDist")
    from(webDistributionDir(development = false))
    into(webAppStaticDir)
    doFirst {
        delete(webAppStaticDir)
    }
    doLast {
        injectCacheVersion(webAppStaticDir.get().asFile, "release")
    }
}

// 自动替换 index.html 中的缓存版本号
fun injectCacheVersion(staticDir: File, mode: String) {
    val codeVersion = "$mode-${computeWebStaticContentHash(staticDir, File::isWebCodeAsset)}"
    val shellVersion = "$mode-${computeWebStaticContentHash(staticDir, File::isWebShellAsset)}"

    // 1) index.html: JS/WASM 代码版本与 HTML/SW 壳版本分离。
    val indexHtml = File(staticDir, "index.html")
    if (indexHtml.exists()) {
        val content = indexHtml.readText()
            .replace(Regex("""\?v=[^"']+"""), "?v=$shellVersion")
            .replace("__CODE_VERSION__", codeVersion)
            .replace("__CACHE_VERSION__", shellVersion)
        indexHtml.writeText(content)
    }

    // 2) sw.js: 只跟随 HTML/CSS/icon/manifest 等壳资源变化。
    val swJs = File(staticDir, "sw.js")
    if (swJs.exists()) {
        val content = swJs.readText()
            .replace("__CODE_VERSION__", codeVersion)
            .replace("__CACHE_VERSION__", shellVersion)
        swJs.writeText(content)
    }

    generateAssetManifest(staticDir)

    println("📝 Cache versions injected: code=$codeVersion shell=$shellVersion")
}

fun computeWebStaticContentHash(staticDir: File, include: (File) -> Boolean): String {
    val digest = MessageDigest.getInstance("SHA-256")
    staticDir.walkTopDown()
        .filter { it.isFile }
        .filter(include)
        .sortedBy { it.relativeTo(staticDir).invariantSeparatorsPath }
        .forEach { file ->
            val relativePath = file.relativeTo(staticDir).invariantSeparatorsPath
            digest.update(relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.update(0.toByte())
        }

    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }.take(12)
}

fun File.isWebCodeAsset(): Boolean {
    if (name.endsWith(".map") || name.endsWith(".LICENSE.txt")) return false
    return name == "compose-app.js" || extension.lowercase() == "wasm"
}

fun File.isWebShellAsset(): Boolean {
    if (name == "asset-manifest.json") return false
    if (name.endsWith(".map") || name.endsWith(".LICENSE.txt")) return false
    if (isWebCodeAsset()) return false

    return when (extension.lowercase()) {
        "html", "js", "css", "json", "svg", "png", "webp", "ico", "ttf", "otf", "woff", "woff2" -> true
        else -> false
    }
}

fun generateAssetManifest(staticDir: File) {
    val tracked = mutableListOf<String>()
    val extensions = setOf("wasm", "js", "ttf", "otf", "woff", "woff2")

    fun scan(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scan(file)
            } else if (file.extension.lowercase() in extensions && file.length() > 50_000) {
                val relativePath = file.relativeTo(staticDir).path
                val raw = file.length()
                val gzipped = ByteArrayOutputStream().use { baos ->
                    GZIPOutputStream(baos).use { gz -> file.inputStream().use { it.copyTo(gz) } }
                    baos.size().toLong()
                }
                tracked.add("""  "${relativePath.replace("\\", "/")}": {"raw": $raw, "gz": $gzipped}""")
            }
        }
    }
    scan(staticDir)

    val json = "{\n${tracked.joinToString(",\n")}\n}"
    File(staticDir, "asset-manifest.json").writeText(json)
    println("📦 Asset manifest generated: ${tracked.size} files tracked")
}


// 2. 将 KMP Compose 的构建产物包含到 Ktor 的资源中
sourceSets.main {
    resources {
        // copyWebApp/copyWebAppDev 会把前端产物复制到 static 目录下。
        srcDir(webAppResourcesDir)
    }
}

// 3. 建立任务依赖关系
// 确保在处理 Ktor 资源之前，KMP Compose 的 Web 应用已经被构建好
tasks.named("processResources") {
    inputs.property("quant.mode", quantMode)
    dependsOn(if (quantMode.get() == "release") copyWebApp else copyWebAppDev)
}

// ==================== 部署相关配置 ====================

// 部署目录配置。deploy.sh 全量部署会先构建到 staging 目录，成功后再替换正式 deploy 目录。
fun rootRelativeFile(path: String): File {
    val candidate = File(path)
    return if (candidate.isAbsolute) candidate else rootProject.file(path)
}

val modeFamily = quantMode.map { if (it == "release") "release" else "debug" }
val deployDir = layout.dir(
    providers.gradleProperty("quant.deploy.dir").map(::rootRelativeFile)
).orElse(layout.buildDirectory.dir(modeFamily.map { "deploy.$it" })).get()
val distDir = layout.dir(
    providers.gradleProperty("quant.dist.dir").map(::rootRelativeFile)
).orElse(layout.buildDirectory.dir(modeFamily.map { "distributions.$it" })).get()

// 应用配置
val appName = "quant-server"
val appVersion = version.toString()

// Shadow Jar 配置
tasks {
    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveBaseName.set(appName)
        archiveVersion.set(appVersion)
        archiveClassifier.set("")
        
        // 启用zip64支持（解决条目数过多问题）
        isZip64 = true
        
        // 合并SPI服务文件（重要：确保JDBC驱动等能被正确加载）
        mergeServiceFiles()
        
        // 使用变换器合并Properties文件
        transform(PropertiesFileTransformer::class.java) {
            paths = listOf("META-INF/properties")
            mergeStrategy.set(MergeStrategy.Append)
        }

        // 排除一些不必要的文件
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("META-INF/INDEX.LIST")
        exclude("META-INF/DUMMY.SF")
        exclude("META-INF/LICENSE*")
        exclude("META-INF/NOTICE*")
        exclude("META-INF/MANIFEST.MF")
        exclude("**/module-info.class")
        
        // 清单属性
        manifest {
            attributes(
                "Main-Class" to "org.shiroumi.server.MainKt",
                "Implementation-Title" to appName,
                "Implementation-Version" to appVersion,
                "Build-Time" to LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
        }
    }
}

// 创建部署目录结构
tasks.register("createDeployStructure") {
    group = "deployment"
    description = "Creates the deployment directory structure"
    
    doLast {
        mkdir(deployDir.asFile)
        mkdir(deployDir.dir("bin").asFile)
        mkdir(deployDir.dir("lib").asFile)
        mkdir(deployDir.dir("config").asFile)
        mkdir(deployDir.dir("logs").asFile)
        mkdir(deployDir.dir("data").asFile)
    }
}

// 复制启动脚本
tasks.register<Copy>("copyStartScripts") {
    group = "deployment"
    description = "Copies start scripts to deploy directory"
    dependsOn("createDeployStructure")
    
    from("src/main/scripts") {
        include("*.sh")
        include("*.bat")
        filePermissions {
            unix(0b111101101) // rwxr-xr-x (755)
        }
    }
    into(deployDir.dir("bin"))
}

tasks.register<Copy>("copyStrategyServiceDistribution") {
    group = "deployment"
    description = "Copies the independent strategy-service distribution to deploy directory"
    dependsOn(":strategy-server:service:installDist", "createDeployStructure")

    from(project(":strategy-server:service").layout.buildDirectory.dir("install/strategy-service"))
    into(deployDir.dir("strategy-service"))
}

// 复制配置文件
tasks.register<Copy>("copyConfigFiles") {
    group = "deployment"
    description = "Copies configuration files to deploy directory"
    dependsOn("createDeployStructure")
    
    from("src/main/resources") {
        include("*.yaml")
        include("*.yml")
        include("*.properties")
        include("*.xml")
        exclude("static/**")
    }
    from("${rootProject.projectDir}/config.yaml") {
        into(".")
    }
    into(deployDir.dir("config"))
}

// 复制JAR文件
tasks.register<Copy>("copyJar") {
    group = "deployment"
    description = "Copies the fat JAR to deploy directory"
    dependsOn("shadowJar", "createDeployStructure")

    from(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().outputs.files)
    into(deployDir.dir("lib"))
    rename { "${appName}.jar" }
}

// 复制 APK 文件到部署包
tasks.register<Copy>("copyApk") {
    group = "deployment"
    description = "Copies Android APK to deploy directory for download route"
    dependsOn("createDeployStructure")
    mustRunAfter(":compose-app:assembleRelease")

    val apkOutputDir = project(":compose-app").layout.buildDirectory.dir("outputs/apk")
    from(apkOutputDir) {
        include("**/*.apk")
    }
    into(deployDir.dir("data/apk"))
}

// 创建完整的部署包（tar.gz）
tasks.register<Tar>("packageDeploy") {
    group = "deployment"
    description = "Creates a complete deployment package"
    dependsOn(
        "shadowJar",
        "copyStartScripts",
        "copyConfigFiles",
        "copyJar",
        "copyStrategyServiceDistribution"
    )
    if (quantMode.get() == "release") {
        dependsOn("copyApk")
    }
    
    archiveBaseName.set(quantMode.map { "${appName}-${appVersion}-$it" })
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP
    destinationDirectory.set(distDir)
    
    from(deployDir) {
        into(quantMode.map { "${appName}-${appVersion}-$it" })
    }
}

// 创建zip格式的部署包
tasks.register<Zip>("packageDeployZip") {
    group = "deployment"
    description = "Creates a complete deployment package (ZIP format)"
    dependsOn(
        "shadowJar",
        "copyStartScripts",
        "copyConfigFiles",
        "copyJar",
        "copyStrategyServiceDistribution"
    )
    if (quantMode.get() == "release") {
        dependsOn("copyApk")
    }
    
    archiveBaseName.set(quantMode.map { "${appName}-${appVersion}-$it" })
    archiveExtension.set("zip")
    destinationDirectory.set(distDir)
    
    from(deployDir) {
        into(quantMode.map { "${appName}-${appVersion}-$it" })
    }
}

// 完整的部署准备任务
tasks.register("prepareDeploy") {
    group = "deployment"
    description = "Prepares all files needed for deployment"
    dependsOn(
        "shadowJar",
        "copyStartScripts",
        "copyConfigFiles",
        "copyJar",
        "copyStrategyServiceDistribution"
    )
    
    doLast {
        println("✅ Deployment files prepared in: ${deployDir.asFile.absolutePath}")
        println("📦 To create distribution packages, run:")
        println("   ./gradlew :ktor-server:packageDeploy      (tar.gz)")
        println("   ./gradlew :ktor-server:packageDeployZip   (zip)")
    }
}

// 一键打包任务（包含前端构建）
tasks.register("packageDebug") {
    group = "distribution"
    description = "Builds the debug deployment package. Run with -Pquant.mode=debug."
    dependsOn("prepareDeploy", "packageDeploy", "packageDeployZip")

    doFirst {
        check(quantMode.get() == "debug") {
            "packageDebug requires -Pquant.mode=debug"
        }
    }

    doLast {
        println("✅ Debug packages created in: ${distDir.asFile.absolutePath}")
    }
}

tasks.register("packageDebugWan") {
    group = "distribution"
    description = "Builds the debug-wan deployment package. Run with -Pquant.mode=debug-wan."
    dependsOn("prepareDeploy", "packageDeploy", "packageDeployZip")

    doFirst {
        check(quantMode.get() == "debug-wan") {
            "packageDebugWan requires -Pquant.mode=debug-wan"
        }
    }

    doLast {
        println("✅ Debug WAN packages created in: ${distDir.asFile.absolutePath}")
    }
}

tasks.register("packageRelease") {
    group = "distribution"
    description = "Builds the release deployment package. Run with -Pquant.mode=release."
    dependsOn("prepareDeploy", "packageDeploy", "packageDeployZip")
    if (quantMode.get() == "release") {
        dependsOn(":compose-app:assembleRelease")
        dependsOn("copyApk")
    }

    doFirst {
        check(quantMode.get() == "release") {
            "packageRelease requires -Pquant.mode=release"
        }
    }

    doLast {
        println("✅ Release packages created in: ${distDir.asFile.absolutePath}")
    }
}

// 清理部署目录
tasks.register<Delete>("cleanDeploy") {
    group = "deployment"
    description = "Cleans the deployment directory"
    delete(deployDir)
    delete(distDir)
}

// 本地运行任务（使用部署配置）
tasks.register<JavaExec>("runDeploy") {
    group = "deployment"
    description = "Runs the application using deployment structure"
    dependsOn("prepareDeploy")

    classpath = files(deployDir.dir("lib/${appName}.jar"))
    mainClass.set("org.shiroumi.server.MainKt")

    // JVM参数
    jvmArgs = listOf(
        "-Xms512m",
        "-Xmx2g",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=200",
        "-Dconfig.file=${deployDir.dir("config").asFile.absolutePath}",
        "-Dlogback.configurationFile=${deployDir.dir("config/logback.xml").asFile.absolutePath}",
        "-Dquant.project.dir=${rootProject.projectDir.absolutePath}"
    )

    // 工作目录
    workingDir = deployDir.asFile
}

// 运行缓存测试（独立 main 函数）
tasks.register<JavaExec>("runCacheTest") {
    group = "verification"
    description = "运行 Candle 缓存策略独立测试"
    dependsOn("compileKotlin", "compileJava", "processResources")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.shiroumi.server.test.CandleCacheTestKt")
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

// 运行周/月线数据验证
tasks.register<JavaExec>("runVerifyWeeklyMonthly") {
    group = "verification"
    description = "验证周/月线数据接入"
    dependsOn("compileKotlin", "compileJava", "processResources")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.shiroumi.server.test.VerifyWeeklyMonthlyKt")
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

// 独立运行数据更新（手动追平历史）
tasks.register<JavaExec>("runDataUpdate") {
    group = "application"
    description = "手动运行数据更新和策略预处理 (UpdateDaily.kt)"
    dependsOn("compileKotlin", "compileJava", "processResources")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.shiroumi.server.UpdateDailyKt")
    workingDir = rootProject.projectDir

    // 使用与主程序相同的环境变量
    environment("QUANT_TEST_MODE", "true")

    jvmArgs = listOf(
        "-Xms512m",
        "-Xmx4g", // 更新历史数据可能会比较吃内存，适当调大
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=200",
        "-ea"
    )

    standardInput = System.`in`

    doFirst {
        println("🚀 开始手动更新历史数据和策略状态...")
        println("   工作目录: ${workingDir}")
        println("   执行类: org.shiroumi.server.UpdateDailyKt")
    }
}

tasks.register<JavaExec>("runHistoricalBackfill") {
    group = "application"
    description = "手动回补历史日线和复权数据 (BackfillHistoricalData.kt)"
    dependsOn("compileKotlin", "compileJava", "processResources")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.shiroumi.server.BackfillHistoricalDataKt")
    workingDir = rootProject.projectDir

    environment("QUANT_TEST_MODE", "true")
    systemProperties(
        System.getProperties()
            .filterKeys { key ->
                key is String && key.startsWith("quant.backfill.")
            }
            .mapKeys { it.key as String }
    )

    jvmArgs = listOf(
        "-Xms4g",
        "-Xmx32g",
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=500",
        "-ea"
    )

    standardInput = System.`in`

    doFirst {
        println("🚀 开始执行历史回补任务...")
        println("   工作目录: ${workingDir}")
        println("   执行类: org.shiroumi.server.BackfillHistoricalDataKt")
        println("   mode=${System.getProperty("quant.backfill.mode") ?: "auto"}")
        println("   from=${System.getProperty("quant.backfill.from") ?: "(auto)"}")
        println("   to=${System.getProperty("quant.backfill.to") ?: "(latest trading day)"}")
        println("   resetFlags=${System.getProperty("quant.backfill.resetFlags") ?: "(default by mode)"}")
    }
}

tasks.register<JavaExec>("probeStkMins") {
    group = "application"
    description = "Step 0 探针：探测 Tushare stk_mins 5min 真实可回溯起点 (ProbeStkMins.kt)"
    dependsOn("compileKotlin", "compileJava", "processResources")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.shiroumi.server.ProbeStkMinsKt")
    workingDir = rootProject.projectDir

    systemProperties(
        System.getProperties()
            .filterKeys { key -> key is String && key.startsWith("quant.probe.") }
            .mapKeys { it.key as String }
    )
}

tasks.register<JavaExec>("collectOpen5m") {
    group = "application"
    description = "采集每日首根5min K线到 stock_open_5m (CollectOpen5m.kt)，支持小样本快验证"
    dependsOn("compileKotlin", "compileJava", "processResources")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.shiroumi.server.CollectOpen5mKt")
    workingDir = rootProject.projectDir

    systemProperties(
        System.getProperties()
            .filterKeys { key -> key is String && key.startsWith("quant.open5m.") }
            .mapKeys { it.key as String }
    )
    jvmArgs = listOf("-Xms2g", "-Xmx8g", "-XX:+UseG1GC")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    environment("QUANT_MODE", "debug")
    jvmArgs("-Dio.ktor.development=true")
}

tasks.register<JavaExec>("runTest") {
    group = "application"
    description = "运行测试模式 (等价于 IDEA 中直接 Run，端口来自 deployment.modes)"

    // 只编译到 class 文件，不打包
    dependsOn("compileKotlin", "compileJava", "processResources")

    // 使用编译后的 class 文件 + 运行时依赖（与 IDEA Run 相同）
    classpath = sourceSets.main.get().runtimeClasspath

    mainClass.set("org.shiroumi.server.MainKt")

    // 工作目录设为项目根目录，便于找到 config.yaml
    workingDir = rootProject.projectDir

    // 测试模式：通过环境变量让 ConfigManager 知道
    environment("QUANT_TEST_MODE", "true")

    // JVM 参数（与 IDEA 默认配置对齐）
    jvmArgs = listOf(
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=200",
        "-ea",  // 启用断言（IDEA 默认）
        "-Dio.ktor.development=true"
    )

    // 标准输入保持连接（支持交互）
    standardInput = System.`in`

    doFirst {
        println("🚀 启动测试模式服务器...")
        println("   工作目录: ${workingDir}")
        println("   环境变量 QUANT_TEST_MODE=true")
        println("   预期端口: 来自 config.yaml 的 deployment.modes 测试模式")
        println("   类路径: ${classpath.files.size} 个条目")
    }
}

// 重置策略计算 flag（使下次 runDataUpdate 重新计算情绪指标）
tasks.register<JavaExec>("resetStrategyFlag") {
    group = "application"
    description = "重置 calendar 表中 strategy_updated 标记，使情绪指标重新计算"
    dependsOn("compileKotlin", "compileJava", "processResources")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.shiroumi.server.ResetStrategyFlagKt")
    workingDir = rootProject.projectDir

    environment("QUANT_TEST_MODE", "true")

    jvmArgs = listOf(
        "-Xms256m",
        "-Xmx1g",
        "-ea"
    )

    doFirst {
        println("🔄 开始重置策略计算标记...")
    }
}

tasks.register<JavaExec>("cleanupDailyStrategyData") {
    group = "application"
    description = "清理指定交易日的策略产物并恢复为可重跑状态"
    dependsOn("compileKotlin", "compileJava", "processResources")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.shiroumi.server.CleanupDailyStrategyDataKt")
    workingDir = rootProject.projectDir

    environment("QUANT_TEST_MODE", "true")
    systemProperties(
        System.getProperties()
            .filterKeys { key ->
                key is String && key.startsWith("quant.cleanup.")
            }
            .mapKeys { it.key as String }
    )

    jvmArgs = listOf(
        "-Xms256m",
        "-Xmx1g",
        "-ea"
    )

    doFirst {
        println("🧹 开始清理指定交易日策略产物...")
        println("   tradeDate=${System.getProperty("quant.cleanup.tradeDate") ?: "(today)"}")
        println("   dryRun=${System.getProperty("quant.cleanup.dryRun") ?: "false"}")
    }
}

tasks.register<JavaExec>("drainCompensationQueue") {
    group = "application"
    description = "手动执行数据补偿队列"
    dependsOn("compileKotlin", "compileJava", "processResources")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.shiroumi.server.DrainCompensationQueueKt")
    workingDir = rootProject.projectDir

    environment("QUANT_TEST_MODE", "true")
    systemProperties(
        System.getProperties()
            .filterKeys { key ->
                key is String && key.startsWith("quant.compensation.")
            }
            .mapKeys { it.key as String }
    )

    jvmArgs = listOf(
        "-Xms256m",
        "-Xmx1g",
        "-ea"
    )

    doFirst {
        println("🔁 开始执行补偿任务...")
        println("   taskType=${System.getProperty("quant.compensation.taskType") ?: "ALL"}")
        println("   tradeDate=${System.getProperty("quant.compensation.tradeDate") ?: "ALL"}")
        println("   ignoreSchedule=${System.getProperty("quant.compensation.ignoreSchedule") ?: "true"}")
    }
}

tasks.register<JavaExec>("repairPingAn") {
    group = "verification"
    description = "强制重刷平安银行(000001.SZ)最近两周的数据"
    dependsOn("compileKotlin", "compileJava", "processResources")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.shiroumi.server.tool.RepairPingAnDataKt")
    workingDir = rootProject.projectDir

    environment("QUANT_TEST_MODE", "true")

    jvmArgs = listOf(
        "-Xms256m",
        "-Xmx1g",
        "-ea"
    )

    doFirst {
        println("⚠️ 正在准备执行修复任务，将直接覆盖数据库中 000001.SZ 最近 14 天的日线字段...")
    }
}

tasks.register<JavaExec>("verifyRepairPingAn") {
    group = "verification"
    description = "验证平安银行修复后的数据状态"
    dependsOn("compileKotlin", "compileJava", "processResources")

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.shiroumi.server.tool.VerifyRepairKt")
    workingDir = rootProject.projectDir

    environment("QUANT_TEST_MODE", "true")

    jvmArgs = listOf(
        "-Xms256m",
        "-Xmx1g",
        "-ea"
    )
}
