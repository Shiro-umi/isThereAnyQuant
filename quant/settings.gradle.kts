pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "quant"

include("app")
include("network")
include("model")
include("database")
include("ksp")