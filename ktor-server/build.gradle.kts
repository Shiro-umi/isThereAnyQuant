@file:Suppress("VulnerableLibrariesLocal")

import org.gradle.kotlin.dsl.implementation


plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    java
    application
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
    implementation(project(":database"))
    api(project(":global"))
    implementation(project(":trading"))
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.datetime)
    implementation(libs.retrofit)
    implementation(libs.bundles.ktor.server)
//    implementation(libs.jetbrains.koog)
    // import Kotlin API client BOM
    implementation(platform("com.aallam.openai:openai-client-bom:4.0.1"))

    // define dependencies without versions
    implementation("com.aallam.openai:openai-client")
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

application {
    mainClass.set("org.shiroumi.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
