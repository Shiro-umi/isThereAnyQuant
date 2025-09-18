plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

buildscript {
    dependencies {
        classpath(kotlin("gradle-plugin", version = "2.2.0-Beta2"))
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Jar> {
    // Otherwise you'll get a "No main manifest attribute" error
    manifest {
        attributes["Main-Class"] = "org.shiroumi.server.MainKt"
    }

    // To avoid the duplicate handling strategy error
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // To add all the dependencies
    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}