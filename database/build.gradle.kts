plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.ksp)
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":ksp"))
    implementation(project(":shared"))
    implementation(project(":network"))

    implementation(libs.jetbrains.exposed.core)
    implementation(libs.jetbrains.exposed.dao)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.h2)
    ksp(project(":ksp"))
    ksp(project(":shared"))
    api(libs.jdbc)
    api(libs.kotlin.datetime)
    api(libs.retrofit)
    implementation(libs.kotlin.coroutines.core)
    implementation(kotlin("reflect"))
    implementation("mysql:mysql-connector-java:8.0.33")
    implementation("com.zaxxer:HikariCP:5.1.0")
}

kotlin {
    jvmToolchain(17)
}