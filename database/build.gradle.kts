plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":ksp"))
    implementation(project(":shared"))
    implementation(project(":network"))
    implementation(project(":strategy-server:core"))
    implementation(libs.kotlin.serialization.json)
    implementation(libs.h2)
    ksp(project(":ksp"))
    ksp(project(":shared"))
    api(libs.kotlin.datetime)
    implementation(libs.kotlin.coroutines.core)
    implementation(kotlin("reflect"))
    implementation(libs.jdbc)
    implementation(libs.hikari)

    implementation(platform(libs.jetbrains.exposed.bom))
    implementation(libs.jetbrains.exposed.core)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.jetbrains.exposed.kotlin.datetime)
    
    // Test dependencies
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // YAML 解析（与生产 ConfigManager 同款解析器），用于连接池配置反序列化单测
    testImplementation(libs.kaml)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
