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
include(":core:ai")
include(":feature:dashboard")
include(":feature:connect")
include(":feature:pitstop")
include(":feature:settings")
include(":feature:dtc")
include(":feature:sessions")
include(":feature:aisettings")
