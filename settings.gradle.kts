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
include("trading")
include("trading:account")
include("trading:backtesting")
include("global")
include("trading:protocol")
include("trading:schedule")
include("trading:context")