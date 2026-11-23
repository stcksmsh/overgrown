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

// Runs every model against simulated plants on real historical weather and writes the metric
// report. The committed copy in docs/reports is the evidence for gate G1.
tasks.register<JavaExec>("simReport") {
    group = "verification"
    description = "Writes the engine-versus-baseline simulation report."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.kostavukicevic.plants.sim.ReportKt")
    args(rootProject.layout.projectDirectory.file("docs/reports/sim-report.md").asFile.path)
}
