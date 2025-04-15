plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.0"
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
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