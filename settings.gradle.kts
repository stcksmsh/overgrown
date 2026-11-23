// SPDX-License-Identifier: GPL-3.0-only

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

rootProject.name = "overgrown"

// The care database's own tools compile its YAML into the asset the app ships (brief section 3).
includeBuild("overgrown-data/tools")

include(":engine", ":engine-sim", ":weather", ":caredb", ":data", ":app")
