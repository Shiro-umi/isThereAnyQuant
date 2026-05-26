plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

// 边界约束（对齐 docs/architecture/backtest-engine-design.md §1.4 / §3.1）：
//  - 允许依赖 :shared          —— 仅复用领域基础模型（Candle / PriceBasis 等）
//  - 允许依赖 :database        —— 仅用于读取行情、日历、停牌等市场数据
//  - 允许依赖 :strategy-server:contract —— 仅适配策略输出契约
//  - 严禁依赖 :strategy-server:core / :strategy-server:runtime / :strategy-server:service
//    原因：策略层不允许感知账户私有域；如果反向耦合，将破坏「策略零账户感知」边界。
dependencies {
    implementation(project(":shared"))
    implementation(project(":database"))
    implementation(project(":strategy-server:contract"))

    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.datetime)

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
