pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "DynamicIsland"
include(":app")
include(":module-sdk")
include(":modules:automation")
include(":modules:calendar")
include(":modules:voice")
include(":modules:ai")
include(":modules:nano")
include(":modules:updater")
include(":modules:speech")
include(":modules:screen")
include(":modules:weather")
include(":modules:scanner")
