plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.0"
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
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