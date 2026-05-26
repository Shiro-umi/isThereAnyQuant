plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":strategy-server:contract"))
    implementation(project(":strategy-server:core"))
    testImplementation(project(":strategy-server:client"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.serialization.json)
    testImplementation(libs.kotlin.datetime)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
