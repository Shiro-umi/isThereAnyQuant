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
    implementation(project(":model"))
    implementation(project(":ksp"))
    implementation(project(":global"))
    implementation(project(":network"))
    ksp(project(":ksp"))
    api(libs.ktorm.core)
    api(libs.ktorm.mysql)
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