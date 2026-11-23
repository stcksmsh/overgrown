// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.licensee)
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
