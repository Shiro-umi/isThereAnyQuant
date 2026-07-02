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
    implementation(project(":strategy-server:breakdown"))
    implementation(project(":database"))
    implementation(project(":shared"))
    // 盘后选股后自动回填 agent 买点（target_date 的 selected Top-N 并发跑 agent → limit_price）。
    implementation(project(":agent-entry"))
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
                    it.startsWith("quant.strategy.holding.") || it.startsWith("quant.strategy.entryBackfill.") ||
                    it == "quant.selection.engine" || it.startsWith("quant.ema20Selection.") ||
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

tasks.register<JavaExec>("importFusionScores") {
    group = "application"
    description = "Import fusion model walk-forward OOS scores (Top3/day CSV) into daily_profit_prediction_selection for backtest"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.shiroumi.strategy.service.ImportFusionScoresToSelectionKt")
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("quant.fusion.") || it == "quant.projectRoot" || it == "quant.project.root" }
            .associateWith { System.getProperty(it) }
    )
    jvmArgs("-Xmx2g")
}

tasks.register<JavaExec>("importAgentEntryPrices") {
    group = "application"
    description = "Import agent volume-price entry limits ({date}.json) into daily_profit_prediction_selection.limit_price"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.shiroumi.strategy.service.ImportAgentEntryPricesKt")
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("quant.agentEntry.") || it == "quant.projectRoot" || it == "quant.project.root" }
            .associateWith { System.getProperty(it) }
    )
    jvmArgs("-Xmx2g")
}

tasks.register<JavaExec>("replayHoldingsFromSelection") {
    group = "application"
    description = "Replay holding state machine over a date range from existing selections (no re-selection); applies agent LIMIT entry"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.shiroumi.strategy.service.ReplayHoldingsFromSelectionKt")
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter {
                it.startsWith("quant.replay.") || it.startsWith("quant.strategy.holding.") ||
                    it == "quant.projectRoot" || it == "quant.project.root"
            }
            .associateWith { System.getProperty(it) }
    )
    jvmArgs("-Xmx4g")
}

tasks.register<JavaExec>("exportStNames") {
    group = "application"
    description = "Export ST stock names (±5% limit, non-tradable) to CSV for research dataset ST-exclusion"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.shiroumi.strategy.service.ExportStNamesKt")
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("quant.st.") || it == "quant.projectRoot" || it == "quant.project.root" }
            .associateWith { System.getProperty(it) }
    )
    jvmArgs("-Xmx1g")
}
