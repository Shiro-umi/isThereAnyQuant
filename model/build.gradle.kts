plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(project(":ksp"))
    ksp(project(":ksp"))
    api(libs.kotlin.serialization.json)
    api(libs.ktorm.core)
    api(libs.kotlin.datetime)
    implementation(libs.guava)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}