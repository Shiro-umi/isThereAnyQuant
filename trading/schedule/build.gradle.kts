plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    api(libs.jdbc)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}