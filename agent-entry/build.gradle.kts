plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

// agent 量价买点分析的可复用内核：回测对照（cli 写文件）与生产回填（盘后 service 写 DB）共用。
// 边界：依赖 :agent（AgentBridge ACP 驱动 + SkillManager）、:backtest（决策 JSON 契约）、
// :shared（config / model.ws）、:database（selection 取数与 limit_price 回填）。
// 不依赖 :strategy-server:core/runtime/service，方向干净无环——是新模块依赖上述模块，
// 不引入 backtest→agent 等反向边。
dependencies {
    implementation(project(":shared"))
    implementation(project(":database"))
    implementation(project(":backtest"))
    implementation(project(":agent"))

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

tasks.test {
    useJUnitPlatform()
}
