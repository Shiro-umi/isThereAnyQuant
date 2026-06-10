plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":strategy-server:contract"))
    implementation(project(":strategy-server:core"))
    implementation(project(":strategy-server:client"))
    implementation(project(":database"))
    implementation(project(":shared"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.datetime)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}

application {
    applicationName = "strategy-service"
    mainClass.set("org.shiroumi.strategy.service.StrategyServiceMainKt")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

tasks.register<JavaExec>("rebuildStrategyRange") {
    group = "application"
    description = "Rebuild full post-market strategy chain (factors/sentiment/selection/audit/holdings) for a trade-date range"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.shiroumi.strategy.service.RebuildStrategyRangeKt")
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter {
                it.startsWith("quant.profitPrediction.") || it.startsWith("quant.strategy.rebuild.") ||
                    it == "quant.projectRoot" || it == "quant.project.root"
            }
            .associateWith { System.getProperty(it) }
    )
    jvmArgs("-Xmx6g")
}

tasks.register<JavaExec>("backfillProfitPredictionSelections") {
    group = "application"
    description = "Backfill daily profit prediction selections from the model into daily_profit_prediction_selection"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.shiroumi.strategy.service.BackfillProfitPredictionSelectionsKt")
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("quant.profitPrediction.") || it == "quant.projectRoot" || it == "quant.project.root" }
            .associateWith { System.getProperty(it) }
    )
    jvmArgs("-Xmx4g")
}
