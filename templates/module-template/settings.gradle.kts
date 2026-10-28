pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io")   // the Dynamic Island module SDK
        mavenLocal()                   // only for testing an unreleased SDK
    }
}
rootProject.name = "island-module"
include(":module")
