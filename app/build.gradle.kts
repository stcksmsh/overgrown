// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.licensee)
}

android {
    namespace = "com.kostavukicevic.plants"
    // Current AndroidX releases require compiling against 37.1. targetSdk stays 36.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.kostavukicevic.plants"
        minSdk = 26
        targetSdk = 36
        // Static literals, parsed by F-Droid. Change only these two lines to bump.
        versionCode = 1
        versionName = "0.1.0"
    }

    // One dimension. `foss` is the default and the only build distributed outside Play.
    // Anything store-specific goes in src/play, never in main or foss.
    flavorDimensions += "store"
    productFlavors {
        create("foss") {
            dimension = "store"
            isDefault = true
        }
        create("play") {
            dimension = "store"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Reproducible builds: no Google-encrypted dependency blob in the APK or bundle.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":weather"))
    implementation(project(":caredb"))
    implementation(project(":data"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
}

// ---- Care database asset ---------------------------------------------------------------
// The YAML in the pinned overgrown-data submodule is the source form; this task compiles it,
// with the data repository's own tool, into assets/caredb.json, the object form.

val careTool: Configuration by configurations.creating

dependencies {
    careTool("overgrown.data:overgrown-data-tools")
}

abstract class CompileCareAsset @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dataFiles: ConfigurableFileCollection

    @get:Internal
    abstract val dataRoot: DirectoryProperty

    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    /** The submodule commit the asset was built from. Pinned, so builds stay reproducible. */
    @get:Input
    abstract val sourceCommit: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val out = outputDir.get().file("caredb.json").asFile
        exec.javaexec {
            classpath = toolClasspath
            mainClass.set("overgrown.data.MainKt")
            args("--root", dataRoot.get().asFile.path, "compile", "--out", out.path, "--commit", sourceCommit.get())
        }
    }
}

val dataDir = rootProject.layout.projectDirectory.dir("overgrown-data")

val compileCareAsset = tasks.register<CompileCareAsset>("compileCareAsset") {
    dataRoot.set(dataDir)
    dataFiles.from(fileTree(dataDir) {
        include("species/**", "archetypes/**", "schema/**", "provenance/snapshots/**", "redirects.yaml", "DATA_VERSION")
    })
    toolClasspath.from(careTool)
    sourceCommit.set(
        providers.exec {
            workingDir = dataDir.asFile
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.map { it.trim() },
    )
    outputDir.set(layout.buildDirectory.dir("generated/caredb"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(compileCareAsset, CompileCareAsset::outputDir)
    }
}
