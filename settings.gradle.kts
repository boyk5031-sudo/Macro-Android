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

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Macro-Android"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Modules are added to the build as they are implemented (Phase 2 → Phase 9).
include(":app")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:security")
include(":core:ui")
include(":core:platform")
include(":core:testing")
include(":automation:engine")
include(":automation:android")
include(":feature:apps")
include(":feature:apkimport")
include(":feature:macros")
include(":feature:execution")
include(":feature:scheduling")
include(":feature:settings")
