// SPDX-License-Identifier: GPL-3.0-only
// Owns every network rule for weather (brief section 7). The HTTP call itself is an interface
// the app implements, so this module needs no HTTP library and stays multiplatform.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.licensee)
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
