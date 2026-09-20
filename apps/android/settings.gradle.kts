pluginManagement {
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
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MeshX"
include(":app")
include(":capacitor-android")
project(":capacitor-android").projectDir = file("node_modules/@capacitor/android/capacitor")
include(":capacitor-app")
project(":capacitor-app").projectDir = file("node_modules/@capacitor/app/android")
include(":capacitor-local-notifications")
project(":capacitor-local-notifications").projectDir =
    file("node_modules/@capacitor/local-notifications/android")
