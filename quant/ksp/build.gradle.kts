plugins {
    kotlin("jvm")
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.1.0-1.0.29")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}