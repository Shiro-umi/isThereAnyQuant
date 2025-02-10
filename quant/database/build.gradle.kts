plugins {
    kotlin("jvm")
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":model"))
    api(libs.ktorm.core)
    api(libs.ktorm.mysql)
    api(libs.jdbc)
    api(libs.kotlin.datetime)
    implementation(kotlin("reflect"))
    implementation("mysql:mysql-connector-java:8.0.33")
    implementation("com.zaxxer:HikariCP:5.1.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}