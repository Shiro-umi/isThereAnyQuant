plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":strategy-server:contract"))
    implementation(project(":strategy-server:core"))
    implementation(project(":strategy-server:client"))
    implementation(project(":database"))
    implementation(project(":shared"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.datetime)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}

application {
    applicationName = "strategy-service"
    mainClass.set("org.shiroumi.strategy.service.StrategyServiceMainKt")
}

tasks.test {
    useJUnitPlatform()
}
