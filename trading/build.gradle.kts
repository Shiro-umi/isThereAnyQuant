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
    implementation(project("backtesting"))
    implementation(project(":global"))
    implementation(project("schedule"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.bundles.ktor.client)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}