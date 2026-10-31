pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StarlaneShare"

include(":core")
// `-PcoreOnly` lets the pure-JVM transfer engine be built/tested on machines without an Android SDK.
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
