pluginManagement {
    repositories {
        maven("https://codeberg.org/api/packages/cefrium/maven") {
            content { includeGroup("com.cefrium") }
        }
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
        maven("https://codeberg.org/api/packages/cefrium/maven") {
            content { includeGroup("com.cefrium") }
        }
    }
}

rootProject.name = "HB Browser Chromium Prototype"
include(":app")
