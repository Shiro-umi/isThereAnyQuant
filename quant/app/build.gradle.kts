@file:Suppress("VulnerableLibrariesLocal")

plugins {
    kotlin("jvm")
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":network"))
    implementation(project(":model"))
    implementation(project(":database"))
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    api(libs.kotlin.coroutines.core)
//    api(libs.ktorm.core)
//    api(libs.ktorm.mysql)
    api(libs.kotlin.serialization.json)
    api(libs.kotlin.datetime)
    api(libs.retrofit)
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
