// SPDX-License-Identifier: GPL-3.0-only
// Rule 5: commonMain only. No java.*, no Android types. The iOS targets exist so
// CI proves the engine compiles without the JVM; they are never shipped.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.licensee)
}

kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
