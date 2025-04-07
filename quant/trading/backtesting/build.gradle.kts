plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.0"
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

sourceSets.main {
    java.srcDirs("build/generated/ksp")
}

dependencies {
    implementation(libs.kotlin.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}