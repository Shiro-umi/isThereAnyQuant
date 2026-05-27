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
    description = "Run the sentiment-factor resonance research pipeline."
    mainClass.set("org.shiroumi.strategy.research.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}
