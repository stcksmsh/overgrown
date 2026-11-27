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
    implementation(project(":caredb"))
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

// Runs every species the app ships twice, with its own entry and with only its archetype's defaults, and
// writes the result. The committed copy in docs/reports is the evidence for the archetype part of gate G5.
tasks.register<JavaExec>("archetypeReport") {
    group = "verification"
    description = "Writes the species-versus-archetype simulation report."
    dependsOn(":app:compileCareAsset")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.kostavukicevic.plants.sim.ArchetypesKt")
    args(
        rootProject.layout.projectDirectory.file("app/build/generated/caredb/caredb.json").asFile.path,
        rootProject.layout.projectDirectory.file("docs/reports/archetype-test.md").asFile.path,
    )
}
