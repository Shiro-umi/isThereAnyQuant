plugins {
    kotlin("jvm") version "2.1.0"
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
    api(libs.kotlin.coroutines.core)
    api(libs.kotlin.serialization.json)
    api(libs.retrofit)
    api(libs.okhttp)
    api(libs.retrofit.kotlin.serialization.converter)
    implementation(libs.guava)
}

kotlin {
    jvmToolchain(17)
}