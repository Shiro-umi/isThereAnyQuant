import java.util.Properties
import kotlin.apply
import kotlin.collections.component1
import kotlin.collections.component2

plugins {
    kotlin("jvm")
}

group = "org.shiroumi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":model"))
    api(libs.ktorm.core)
    api(libs.ktorm.mysql)
    api(libs.jdbc)
    api(libs.kotlin.datetime)
    implementation(kotlin("reflect"))
    implementation("mysql:mysql-connector-java:8.0.33")
    implementation("com.zaxxer:HikariCP:5.1.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

sourceSets.main {
    println(layout.buildDirectory.dir("generated/sources/kotlin/main").get().asFile.absolutePath)
    kotlin.srcDir(layout.buildDirectory.dir("generated/sources/kotlin/main").get().asFile.absolutePath)
}

tasks.named("compileKotlin") {
    dependsOn("generateLocalProperties")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

tasks.register("generateLocalProperties") {
    group = "build"
    description = "Generate Kotlin constants from local.properties"

    val generatedDir = layout.buildDirectory.dir("generated/sources/kotlin/main")
    outputs.dir(generatedDir)

    doLast {
        val packageName = "org.shiroumi.configs"
        val className = "BuildConfigs"
        val file = generatedDir.get().asFile.resolve("${packageName.replace('.', '/')}/$className.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package $packageName

            object $className {
                ${
                localProperties.entries.joinToString("\n    ") { (key, value) ->
                    "const val ${key.toString().uppercase().replace('.', '_')} = \"${value}\""
                }
            }
            }
        """.trimIndent()
        )
    }
}