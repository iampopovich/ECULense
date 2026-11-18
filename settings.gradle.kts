pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mik3y") }
        }
    }
}

rootProject.name = "ECULense"
include(":app")
include(":core:model")
include(":core:ui")
include(":core:navigation")
include(":core:obd")
include(":core:bluetooth")
include(":core:usb")
include(":core:service")
include(":core:database")
include(":core:data")
include(":core:demo")
include(":core:carscanner")
include(":core:analytics")
include(":core:ai")
include(":core:aitools")
include(":core:datapacks")
include(":core:vehicledb")
include(":core:dtcdb")
include(":core:obdb")
include(":core:background")
include(":core:ailocal")
include(":core:backup")
include(":feature:dashboard")
include(":feature:connect")
include(":feature:pitstop")
include(":feature:settings")
include(":feature:dtc")
include(":feature:sessions")
include(":feature:car")
include(":feature:stats")
include(":feature:analysis")
include(":feature:aisettings")
include(":feature:aichat")
include(":feature:datapacks")
include(":feature:maintenance")

// Spike 3.6 (on-device LLM, LiteRT via Koog): only with -Pecu.spike.ondevice=true — never part of the default build or the APK.
if (providers.gradleProperty("ecu.spike.ondevice").orNull == "true") include(":spike:ondevice")
