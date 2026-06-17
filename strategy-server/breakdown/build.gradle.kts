plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

// 边界约束（与 :backtest / :strategy-server:research 同源「研究层零账户感知」）：
//  - 本模块只做【回归通道几何破位检测】的纯计算，输入连续有效收盘序列，输出破位事件日集合。
//  - 仅依赖 kotlin.datetime（LocalDate 表征交易日）。不依赖 :shared / :database / contract。
//  - 严禁依赖 :strategy-server:core / :runtime / :service / :client。
//    原因：检测器是纯几何（只用 t 及之前窗口的 OHLC），无账户感知、无策略私有域耦合。
dependencies {
    implementation(libs.kotlin.datetime)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

// 破位检测器数值对齐校验 harness（硬闸门）：逐根跑同源 OHLC 与 Python 真值做命中布尔 + lvl diff。
tasks.register<JavaExec>("runAlignment") {
    group = "verification"
    description = "运行 BreakdownAlignmentHarness 对齐 Python 真值（breakdown_truth.csv）"
    mainClass.set("org.shiroumi.strategy.breakdown.BreakdownAlignmentHarness")
    classpath = sourceSets["main"].runtimeClasspath
}

// 破位加分窗口逻辑对齐校验 harness：验证 searchsorted+[pos-2,pos] 窗口口径与 Python recently_broke 一致。
tasks.register<JavaExec>("runRerankAlignment") {
    group = "verification"
    description = "运行 BreakdownRerankAlignmentHarness 对齐 Python recently_broke（breakdown_rerank_ref.csv）"
    mainClass.set("org.shiroumi.strategy.breakdown.BreakdownRerankAlignmentHarness")
    classpath = sourceSets["main"].runtimeClasspath
}
