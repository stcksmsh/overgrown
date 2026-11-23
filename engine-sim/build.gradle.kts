// SPDX-License-Identifier: GPL-3.0-only
// Test tooling only: simulated-year harness, metrics, property tests. Never shipped.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":engine"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotest.property)
}
