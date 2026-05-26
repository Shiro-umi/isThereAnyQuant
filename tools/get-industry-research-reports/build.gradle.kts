plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(projects.shared)
    implementation(projects.network)

    implementation(libs.logback)
    implementation("com.github.ajalt.clikt:clikt:4.3.0")
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.coroutines.core)
}

application {
    mainClass.set("org.shiroumi.tools.industryresearchreports.GetIndustryResearchReportsKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}
