plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

// 边界约束（对称 :backtest，见 backtest/build.gradle.kts）：
//  - 允许依赖 :shared          —— 复用领域基础模型（Candle / PriceBasis 等）
//  - 允许依赖 :database        —— 仅用于读取行情、日历、停牌等【事实】市场数据
//  - 允许依赖 :strategy-server:contract —— 仅适配策略输出契约
//  - 严禁依赖 :strategy-server:core / :client / :service / :runtime
//  - 不与 :backtest 互相依赖
//    原因：research 是「数据源 → 研究内容 → 结论」的研究管线，只消费事实数据；
//          任何账户/策略私有域耦合都会破坏「研究层零账户感知」边界。
//
// 职责边界（见 temp/research-pipeline-foundation-todolist.md §0）：
//  - 本模块只提供【通用管线基建】：阶段抽象、Source 段（读事实 K 线）、通用信号处理能力层。
//  - 「研究内容」（因子计算、Y 标签、状态划分、频域共振调参、出共振卡片）由 autoresearch 在
//    Study 插槽内填充，不在本模块的工程职责内。
dependencies {
    implementation(project(":shared"))
    implementation(project(":database"))
    implementation(project(":strategy-server:contract"))

    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.datetime)

    // 科学计算：Commons Math 提供 FFT；DataFrame 提供研究态宽表（均在 mavenCentral）
    implementation(libs.commons.math3)
    implementation(libs.kotlinx.dataframe)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("reflect"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

tasks.register<JavaExec>("runResearch") {
    group = "research"
    description = "Run a research topic pipeline (factor / trend / reversal) via the Main CLI entrypoint."
    mainClass.set("org.shiroumi.strategy.research.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("runCrashStockProbe") {
    group = "research"
    description = "个股截面通用下跌预警 · 最小可微闭环（logistic + 可学软门控 + soft-Fβ + walk-forward）"
    mainClass.set("org.shiroumi.strategy.research.topic.crashstock.PivotCrashStockProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    maxHeapSize = "6g"
    systemProperties(
        System.getProperties()
            .filterKeys { it is String && (it as String).startsWith("quant.cs.") }
            .mapKeys { it.key as String }
    )
}

tasks.register<JavaExec>("runCrashStockTrainingExport") {
    group = "research"
    description = "Export real pivot-crash-stock training dataset for the managed PyTorch Training stage."
    mainClass.set("org.shiroumi.strategy.research.topic.crashstock.PivotCrashTrainingExportKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    maxHeapSize = "12g"
    systemProperties(
        System.getProperties()
            .filterKeys { it is String && (it as String).startsWith("quant.cte.") }
            .mapKeys { it.key as String }
    )
}

tasks.register<Exec>("runCrashTransformerTraining") {
    group = "research"
    description = "Run managed native PyTorch training for pivot-crash-stock Transformer."
    workingDir = rootProject.projectDir.resolve("strategy-server/research/pytorch")
    val dataset = providers.gradleProperty("dataset")
    val out = providers.gradleProperty("out")
    val featureSchema = providers.gradleProperty("featureSchema")
    val normalization = providers.gradleProperty("normalization")
    doFirst {
        require(dataset.isPresent) { "Missing -Pdataset=/path/to/dataset.npz" }
        require(out.isPresent) { "Missing -Pout=/path/to/model-package" }
        require(featureSchema.isPresent) { "Missing -PfeatureSchema=/path/to/feature_schema.json" }
        require(normalization.isPresent) { "Missing -Pnormalization=/path/to/normalization.json" }
    }
    commandLine(
        buildList {
            addAll(listOf(
                "uv", "run", "quant-train-crash-transformer",
                "--dataset", dataset.orNull ?: "",
                "--out", out.orNull ?: "",
                "--feature-schema", featureSchema.orNull ?: "",
                "--normalization", normalization.orNull ?: "",
            ))
            providers.gradleProperty("seqLen").orNull?.let { addAll(listOf("--seq-len", it)) }
            providers.gradleProperty("epochs").orNull?.let { addAll(listOf("--epochs", it)) }
            providers.gradleProperty("batchSize").orNull?.let { addAll(listOf("--batch-size", it)) }
            providers.gradleProperty("dModel").orNull?.let { addAll(listOf("--d-model", it)) }
            providers.gradleProperty("heads").orNull?.let { addAll(listOf("--heads", it)) }
            providers.gradleProperty("layers").orNull?.let { addAll(listOf("--layers", it)) }
            providers.gradleProperty("lr").orNull?.let { addAll(listOf("--lr", it)) }
            providers.gradleProperty("device").orNull?.let { addAll(listOf("--device", it)) }
            providers.gradleProperty("valFraction").orNull?.let { addAll(listOf("--val-fraction", it)) }
            providers.gradleProperty("seed").orNull?.let { addAll(listOf("--seed", it)) }
            providers.gradleProperty("npRecall").orNull?.let { addAll(listOf("--np-recall", it)) }
            providers.gradleProperty("npWeight").orNull?.let { addAll(listOf("--np-weight", it)) }
            providers.gradleProperty("npMargin").orNull?.let { addAll(listOf("--np-margin", it)) }
            providers.gradleProperty("npTau").orNull?.let { addAll(listOf("--np-tau", it)) }
            providers.gradleProperty("selectMetric").orNull?.let { addAll(listOf("--select-metric", it)) }
            providers.gradleProperty("objective").orNull?.let { addAll(listOf("--objective", it)) }
            providers.gradleProperty("quantiles").orNull?.let { addAll(listOf("--quantiles", it)) }
            providers.gradleProperty("csRankLabelFrac").orNull?.let { addAll(listOf("--cs-rank-label-frac", it)) }
            providers.gradleProperty("labelDdWeight").orNull?.let { addAll(listOf("--label-dd-weight", it)) }
        }
    )
}

tasks.register<Exec>("runCrashTransformerTrainingFromApi") {
    group = "research"
    description = "Run managed native PyTorch training for pivot-crash-stock Transformer from Ktor research data APIs."
    workingDir = rootProject.projectDir.resolve("strategy-server/research/pytorch")
    val apiBase = providers.gradleProperty("apiBase")
    val out = providers.gradleProperty("out")
    doFirst {
        require(apiBase.isPresent) { "Missing -PapiBase=http://127.0.0.1:9871" }
        require(out.isPresent) { "Missing -Pout=/path/to/model-package" }
    }
    commandLine(
        buildList {
            addAll(listOf(
                "uv", "run", "quant-train-crash-transformer",
                "--api-base", apiBase.orNull ?: "",
                "--out", out.orNull ?: "",
            ))
            providers.gradleProperty("cacheOut").orNull?.let { addAll(listOf("--cache-out", it)) }
            providers.gradleProperty("dataset").orNull?.let { addAll(listOf("--dataset", it)) }
            providers.gradleProperty("start").orNull?.let { addAll(listOf("--start", it)) }
            providers.gradleProperty("end").orNull?.let { addAll(listOf("--end", it)) }
            providers.gradleProperty("fwd").orNull?.let { addAll(listOf("--fwd", it)) }
            providers.gradleProperty("label").orNull?.let { addAll(listOf("--label", it)) }
            providers.gradleProperty("dynK").orNull?.let { addAll(listOf("--dyn-k", it)) }
            providers.gradleProperty("rollW").orNull?.let { addAll(listOf("--roll-w", it)) }
            providers.gradleProperty("market").orNull?.let { if (it.toBooleanStrictOrNull() == false) add("--no-market") }
            providers.gradleProperty("moneyflow").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--moneyflow") }
            providers.gradleProperty("vpFactors").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--vp-factors") }
            providers.gradleProperty("rsiFactors").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--rsi-factors") }
            providers.gradleProperty("limitFactors").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--limit-factors") }
            providers.gradleProperty("compositeLabel").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--composite-label") }
            providers.gradleProperty("themeFactors").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--theme-factors") }
            providers.gradleProperty("trajFactors").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--traj-factors") }
            providers.gradleProperty("sealQualityFactors").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--seal-quality-factors") }
            providers.gradleProperty("externalDocFactors").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--external-doc-factors") }
            providers.gradleProperty("behavioralFactors").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--behavioral-factors") }
            providers.gradleProperty("intraday15Factors").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--intraday15-factors") }
            providers.gradleProperty("metaTimingFactors").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--meta-timing-factors") }
            providers.gradleProperty("kplActiveOnly").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--kpl-active-only") }
            providers.gradleProperty("kplActiveWindow").orNull?.let { addAll(listOf("--kpl-active-window", it)) }
            providers.gradleProperty("highRiskOnly").orNull?.let { if (it.toBooleanStrictOrNull() == true) add("--high-risk-only") }
            providers.gradleProperty("highRiskBoard").orNull?.let { addAll(listOf("--high-risk-board", it)) }
            providers.gradleProperty("highRiskMode").orNull?.let { addAll(listOf("--high-risk-mode", it)) }
            providers.gradleProperty("moeExperts").orNull?.let { addAll(listOf("--moe-experts", it)) }
            providers.gradleProperty("stateGate").orNull?.let { if (it.toBooleanStrictOrNull() == false) add("--no-state-gate") }
            providers.gradleProperty("gateMode").orNull?.let { addAll(listOf("--gate-mode", it)) }
            providers.gradleProperty("loss").orNull?.let { addAll(listOf("--loss", it)) }
            providers.gradleProperty("posWeightScale").orNull?.let { addAll(listOf("--pos-weight-scale", it)) }
            providers.gradleProperty("rankWeight").orNull?.let { addAll(listOf("--rank-weight", it)) }
            providers.gradleProperty("beta").orNull?.let { addAll(listOf("--beta", it)) }
            providers.gradleProperty("focalGamma").orNull?.let { addAll(listOf("--focal-gamma", it)) }
            providers.gradleProperty("focalAlpha").orNull?.let { addAll(listOf("--focal-alpha", it)) }
            providers.gradleProperty("maxSamples").orNull?.let { addAll(listOf("--max-samples", it)) }
            providers.gradleProperty("pageLimit").orNull?.let { addAll(listOf("--page-limit", it)) }
            providers.gradleProperty("seqLen").orNull?.let { addAll(listOf("--seq-len", it)) }
            providers.gradleProperty("epochs").orNull?.let { addAll(listOf("--epochs", it)) }
            providers.gradleProperty("batchSize").orNull?.let { addAll(listOf("--batch-size", it)) }
            providers.gradleProperty("dModel").orNull?.let { addAll(listOf("--d-model", it)) }
            providers.gradleProperty("heads").orNull?.let { addAll(listOf("--heads", it)) }
            providers.gradleProperty("layers").orNull?.let { addAll(listOf("--layers", it)) }
            providers.gradleProperty("lr").orNull?.let { addAll(listOf("--lr", it)) }
            providers.gradleProperty("weightDecay").orNull?.let { addAll(listOf("--weight-decay", it)) }
            providers.gradleProperty("dropout").orNull?.let { addAll(listOf("--dropout", it)) }
            providers.gradleProperty("purgeDays").orNull?.let { addAll(listOf("--purge-days", it)) }
            providers.gradleProperty("device").orNull?.let { addAll(listOf("--device", it)) }
            providers.gradleProperty("valFraction").orNull?.let { addAll(listOf("--val-fraction", it)) }
            providers.gradleProperty("seed").orNull?.let { addAll(listOf("--seed", it)) }
            providers.gradleProperty("npRecall").orNull?.let { addAll(listOf("--np-recall", it)) }
            providers.gradleProperty("npWeight").orNull?.let { addAll(listOf("--np-weight", it)) }
            providers.gradleProperty("npMargin").orNull?.let { addAll(listOf("--np-margin", it)) }
            providers.gradleProperty("npTau").orNull?.let { addAll(listOf("--np-tau", it)) }
            providers.gradleProperty("selectMetric").orNull?.let { addAll(listOf("--select-metric", it)) }
            providers.gradleProperty("objective").orNull?.let { addAll(listOf("--objective", it)) }
            providers.gradleProperty("quantiles").orNull?.let { addAll(listOf("--quantiles", it)) }
            providers.gradleProperty("csRankLabelFrac").orNull?.let { addAll(listOf("--cs-rank-label-frac", it)) }
            providers.gradleProperty("labelDdWeight").orNull?.let { addAll(listOf("--label-dd-weight", it)) }
        }
    )
}
