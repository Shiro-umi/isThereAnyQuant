plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
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
    ksp(project(":ksp"))
    api(libs.ktorm.core)
    api(libs.ktorm.mysql)
    api(libs.jdbc)
    api(libs.kotlin.datetime)
    implementation(kotlin("reflect"))
    implementation("mysql:mysql-connector-java:8.0.33")
    implementation("com.zaxxer:HikariCP:5.1.0")
}

kotlin {
    jvmToolchain(17)
}