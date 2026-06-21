@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "quant"

include("ktor-server")
include("network")
include("database")
include("ksp")
include("compose-app")
include("shared")
include("agent")
include("agent-entry")
include("cli")
include("strategy-server:contract")
include("strategy-server:core")
include("strategy-server:client")
include("strategy-server:service")
include("strategy-server:testing")
include("strategy-server:research")
include("strategy-server:breakdown")
include("backtest")
include("tools:get-candles")
include("tools:market-emotion")
include("tools:get-intraday-candles")
include("tools:get-research-reports")
include("tools:get-industry-research-reports")
include("tools:get-limit-list")
include("tools:get-candles-asof")
include("tools:get-intraday-candles-asof")
include("tools:get-limit-list-asof")
