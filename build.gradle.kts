// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

// Rule 4 and rule 5: only permissive licences in shipped dependencies.
// Every module applies Licensee with the same allow-list.
subprojects {
    plugins.withId("app.cash.licensee") {
        extensions.configure<app.cash.licensee.LicenseeExtension> {
            allow("Apache-2.0")
            allow("MIT")
            allow("BSD-2-Clause")
            allow("BSD-3-Clause")
        }
    }
}
