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

sourceSets.main {
    java.srcDirs("build/generated/ksp")
}

dependencies {
    implementation(project(":global"))
    implementation(project(":ksp"))
    implementation(project(":trading:schedule"))
    ksp(project(":ksp"))
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}