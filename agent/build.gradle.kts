plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    implementation(project(":shared"))

    // ACP SDK - 使用Maven Central上的最新版本
    implementation("com.agentclientprotocol:acp:0.16.0")

    // Kotlin协程
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.datetime)

    // kotlinx-io (ACP SDK依赖)
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.7.0")

    // 日志
    implementation(libs.logback)
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")

    // 测试依赖
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.shiroumi.agent.AgentLauncherKt")
}

// 打印 classpath 任务（用于外部脚本）
tasks.register("printClasspath") {
    doLast {
        println(sourceSets["main"].runtimeClasspath.asPath)
    }
}

// 一键运行交互式 Agent 测试
tasks.register<Exec>("agent") {
    group = "application"
    description = "Run interactive agent test launcher (one-click)"

    dependsOn("classes")

    val classpath = sourceSets["main"].runtimeClasspath.asPath

    // 直接使用 java 命令启动新进程，避免 Gradle 守护进程干扰 stdin
    commandLine("java", "-cp", classpath, "org.shiroumi.agent.InteractiveTestKt")

    // 继承当前终端的 IO
    standardInput = System.`in`
    standardOutput = System.out
    errorOutput = System.err

    // 设置工作目录为项目根目录
    workingDir = project.rootDir
}
