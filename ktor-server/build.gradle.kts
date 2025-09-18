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

    implementation("org.commonmark:commonmark:0.26.0")
    implementation("org.xhtmlrenderer:flying-saucer-core:9.1.22")
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
    mainClass.set("org.shiroumi.server.MainKt")
}

tasks.withType<Jar> {
    // Otherwise you'll get a "No main manifest attribute" error
    manifest {
        attributes["Main-Class"] = "org.shiroumi.server.MainKt"
    }

    // To avoid the duplicate handling strategy error
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // To add all the dependencies
    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}