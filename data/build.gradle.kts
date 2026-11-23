// SPDX-License-Identifier: GPL-3.0-only
// On-device storage (Room with the bundled SQLite driver, multiplatform DAOs), settings
// (DataStore), JSON export and import, and the planner that replays the engine.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.licensee)
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "com.kostavukicevic.plants.data"
        compileSdk = 37
        minSdk = 26
    }
    // JVM target so storage tests run without a device.
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
            api(project(":weather"))
            api(project(":caredb"))
            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.datastore.preferences)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}
