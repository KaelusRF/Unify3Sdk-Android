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
        maven {
            url = uri(File(settingsDir, "../../../build/release/0.1.0/repo"))
        }
    }
}

rootProject.name = "Unify3SdkExample"
include(":app")
