plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
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
    implementation(project(":database"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.ktorm.core)
    implementation(libs.ktorm.mysql)
    api(libs.jdbc)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}