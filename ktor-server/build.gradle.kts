@file:Suppress("VulnerableLibrariesLocal")

import com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer
import com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer.MergeStrategy
import com.github.jengelman.gradle.plugins.shadow.transformers.ResourceTransformer.Companion.transform
import com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer
import org.gradle.internal.impldep.org.apache.ivy.plugins.namespace.NameSpaceHelper.transform
import org.gradle.kotlin.dsl.implementation


plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.ksp)
    java
    application
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/kds/kotlin-ds-maven")
}

//application {
//    mainClass.set("io.ktor.server.netty.EngineMain")
//    val isDevelopment: Boolean = project.ext.has("development")
////    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
//    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=true")
//}

dependencies {
    implementation(project(":network"))
    implementation(project(":database"))
    implementation(project(":shared"))
    api(project(":shared"))
//    implementation(project(":trading"))
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.datetime)
    implementation(libs.retrofit)
    implementation(libs.bundles.ktor.server)
    implementation("io.ktor:ktor-server-auto-head-response:3.3.0")
    implementation("io.ktor:ktor-server-host-common:3.3.0")
    implementation("io.ktor:ktor-server-status-pages:3.3.0")
    implementation("io.ktor:ktor-server-compression:3.3.0")
    implementation("io.ktor:ktor-server-caching-headers:3.3.0")
    implementation("io.ktor:ktor-server-conditional-headers:3.3.0")
    implementation("io.ktor:ktor-server-partial-content:3.3.0")
    implementation("io.ktor:ktor-server-default-headers:3.3.0")
    ksp(project(":ksp"))
    implementation(project(":ksp"))
    implementation(libs.selenium.java)
    implementation(libs.selenium.devtools.v139)
    implementation(libs.logback)
    implementation("org.jetbrains.kotlinx:kandy-lets-plot:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlin-statistics-jvm:0.4.0")
}

kotlin {
    jvmToolchain(17)
    sourceSets.main.get().kotlin.srcDir("build/generated/ksp/main/kotlin")
}

application {
    mainClass.set("org.shiroumi.server.MainKt")
}

val copyWebApp by tasks.registering(Copy::class) {
    // 设置依赖：这个任务依赖于 compose-app 模块的 jsBrowserDistribution 任务
    // 这样，在复制之前，Gradle 会确保前端应用已经被构建好了
    dependsOn(":compose-app:jsBrowserDistribution")

    // from: 从哪里复制
    from(project(":compose-app").layout.buildDirectory.dir("dist/js/productionExecutable"))
    // into: 复制到哪里
    // 这是 ktor-server 模块的 resources 目录下的一个叫 static 的子目录
    into(layout.projectDirectory.dir("src/main/resources/static"))
}


// 2. 将 KMP Compose 的构建产物包含到 Ktor 的资源中
sourceSets.main {
    resources {
        // 将 compose-app 模块的 JS 发布目录指定为资源目录
        // 当 Ktor 打包时，会把这里面的所有文件都复制到 JAR 的资源根目录下
        srcDir(project(":compose-app").layout.buildDirectory.dir("dist/js/productionExecutable"))
    }
}

// 3. 建立任务依赖关系
// 确保在处理 Ktor 资源之前，KMP Compose 的 Web 应用已经被构建好
tasks.named("processResources") {
//    dependsOn(project(":compose-app").tasks.named("jsBrowserProductionWebpack"))
    dependsOn("copyWebApp")
}

// (可选) 创建一个统一的发布任务
tasks.register("packageRelease") {
    group = "distribution"
    description = "Builds the KMP Compose app and packages the Ktor server into a fat JAR."
    // 这个方法会自动处理 META-INF/services/ 下文件的合并
    // shadowJar 任务会自动打包所有内容，我们只需要依赖它即可
    dependsOn(tasks.named("shadowJar"))
}

tasks {
    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        mergeServiceFiles()
        // 使用变换器合并Properties文件
        transform(PropertiesFileTransformer::class.java) {
            paths = listOf("META-INF/properties")
//            mergeStrategy = "append"
            mergeStrategy.set(MergeStrategy.Append)
        }

        // 使用服务文件变换器
        transform(ServiceFileTransformer::class.java)

        // 排除一些不必要的文件
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }
}