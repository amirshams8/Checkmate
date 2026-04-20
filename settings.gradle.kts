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
rootProject.name = "Checkmate"
include(":app")
include(":modules:core")
include(":modules:automation")
include(":modules:callhandler")
include(":modules:workmode")
include(":modules:psyche")
include(":modules:planner")
