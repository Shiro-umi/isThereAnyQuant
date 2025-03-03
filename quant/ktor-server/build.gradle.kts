@file:Suppress("VulnerableLibrariesLocal")

plugins {
    kotlin("jvm")
    alias(libs.plugins.ktor)
    kotlin("plugin.serialization") version "2.1.0"
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    implementation(project(":network"))
    implementation(project(":model"))
    implementation(project(":database"))
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    api(libs.kotlin.coroutines.core)
    api(libs.kotlin.serialization.json)
    api(libs.kotlin.datetime)
    api(libs.retrofit)
    implementation(libs.bundles.ktor.server)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}
