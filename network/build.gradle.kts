plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(project(":shared"))
    api(libs.kotlin.coroutines.core)
    api(libs.kotlin.serialization.json)
    // Ktor client with CIO engine
    api(libs.bundles.ktor.client)
    api(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Guava
    implementation(libs.guava)
}

kotlin {
    jvmToolchain(17)
}
