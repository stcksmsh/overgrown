// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.licensee)
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "com.kostavukicevic.plants.data"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
        }
    }
}
