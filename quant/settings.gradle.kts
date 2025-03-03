pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "quant"

include("ktor-server")
include("network")
include("model")
include("database")
include("ksp")