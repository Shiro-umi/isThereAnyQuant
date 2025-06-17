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

sourceSets.main {
    java.srcDirs("build/generated/ksp")
}

dependencies {
    implementation(project(":global"))
    implementation(project(":database"))
    implementation(project(":ksp"))
    implementation(project(":model"))
    ksp(project(":ksp"))
    implementation(libs.bundles.ktor.client)
    api(libs.kotlin.serialization.json)
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.ktorm.core)
    implementation(libs.ktorm.mysql)
    @Suppress("VulnerableLibrariesLocal", "RedundantSuppression")
    api(libs.jdbc)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}