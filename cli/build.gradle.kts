plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(projects.shared)
    implementation(projects.network)
    // 本地隔离模式回测 CLI 直接装配 backtest 引擎与 database 直连 stock_db。
    implementation(projects.backtest)
    implementation(projects.database)

    // Logging
    implementation(libs.logback)

    // Clikt
    implementation("com.github.ajalt.clikt:clikt:4.3.0")

    // Kotlin Serialization
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.datetime)

    // Coroutines
    implementation(libs.kotlin.coroutines.core)
}

application {
    mainClass.set("org.shiroumi.cli.QuantCliKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

tasks.named<Sync>("installDist") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
