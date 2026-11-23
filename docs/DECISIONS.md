# Decisions

Each entry: the decision, and why. Newest last.

## D1. Default branch is `main`; gate work arrives by pull request

The first commit (G0 foundations) goes straight to `main` because an empty repository has nothing to branch from. Later work is proposed on a branch and merged by the owner.

## D2. Toolchain and library versions

Pinned in `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties` (with the distribution checksum). Chosen as the newest stable release of each on Google Maven, Maven Central and services.gradle.org when G0 was set up: Gradle 9.9.2, Android Gradle Plugin 9.5.2, Kotlin 2.4.21, JDK 21, build-tools 36.0.0 (the AGP default, pinned explicitly). Stable means no alpha, beta, RC or milestone tag. The wrapper jar's SHA-256 matches the checksum Gradle publishes for 9.9.2.

`targetSdk` is 36 as the brief requires. `compileSdk` is 37.1 because the current stable AndroidX releases (Compose foundation 1.13, core 1.19, activity 1.13) refuse to build against anything lower. Compiling against a newer API level does not change runtime behaviour; only `targetSdk` does.

Room 2.8.5, SQLite 2.7.1 and DataStore 1.3.0 match the versions the brief lists as stable Kotlin Multiplatform libraries.

## D3. `kotlinx-datetime` 0.8.0

The brief marked its stability UNVERIFIED. Checked: 0.8.0 is the newest release on Maven Central; there is no 1.0. The library is still pre-1.0, so its API may change between minor versions. It is Apache-2.0 and is the only time library allowed in `:engine`, so it is used, but the engine keeps its use to dates (`LocalDate`, day-of-year arithmetic), which is the most stable part of the API.

## D4. `:engine` targets: JVM, `iosArm64`, `iosSimulatorArm64`

JVM is what Android and the simulation harness consume. The two iOS targets exist only so CI proves the engine compiles without the JVM (rule 5). Kotlin compiles Apple-target klibs on Linux with `kotlin.native.enableKlibsCrossCompilation`, so CI does not need a macOS runner. Linking an iOS binary does need macOS and is not attempted.

## D5. `minSdk` 26

The brief does not name one. Android 8.0 (API 26) is the first with notification channels, which every alert in this app needs, and it covers nearly all phones still in use. Lower would add compatibility code for no real users.

## D6. Dependency-licence check with Licensee

`app.cash.licensee` (Apache-2.0, Maven Central) runs as part of `check` on every module that ships, with an allow-list of Apache-2.0, MIT, BSD-2-Clause and BSD-3-Clause. A dependency with any other licence, or none, fails the build. `:engine-sim` is test tooling and is not checked.

## D7. Reproducible-build check runs on every CI run, not only on tags

The brief requires it on each tagged commit. It also runs on pull requests and on `main`, so a change that breaks reproducibility is caught when it is made, not at release time. The two builds use separate checkout directories and no build cache, so a path or cache dependence shows up as a difference.

## D8. Release builds use R8

`isMinifyEnabled` and `isShrinkResources` are on for release. R8 output is deterministic for fixed inputs, and CI's double build checks that.

## D9. No contact address published yet

The brief allows "GitHub issue or the owner's address" for commercial enquiries and lists "publisher and contact" for the privacy policy. The owner's address is personal data, so it is left out until the owner chooses which address to publish.

## D10. CLA is signed by a pull-request comment

A comment naming the commit of `CLA.md` and the contributor's full name is the simplest record that needs no third-party service or account. Subject to the owner's legal review.
