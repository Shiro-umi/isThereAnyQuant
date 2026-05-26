plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(projects.shared)
    implementation(projects.network)

    // Logging
    implementation(libs.logback)

    // Clikt
    implementation("com.github.ajalt.clikt:clikt:4.3.0")

    // Kotlin Serialization
    implementation(libs.kotlin.serialization.json)

    // Coroutines
    implementation(libs.kotlin.coroutines.core)
}

application {
    mainClass.set("org.shiroumi.tools.intraday.GetIntradayCandlesKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}
