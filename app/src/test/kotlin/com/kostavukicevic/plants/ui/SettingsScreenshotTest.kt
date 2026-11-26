// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden images of Settings, top and bottom, and the import confirmation, light and dark, at 1.0x and 1.3x. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class SettingsScreenshotTest(private val dark: Boolean, private val fontScale: Float) {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private val suffix = "${if (dark) "dark" else "light"}_${fontScale}x"

    // Any changed pixel fails, as in ThemeScreenshotTest. Built per instance, inside the Robolectric sandbox.
    private val exact = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f, imageComparator = SimpleImageComparator(maxDistance = 0f)),
    )

    @Before
    fun setUp() {
        useTestDatabase()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OvergrownTheme(dark = dark) { SettingsScreen(onBack = {}, onRemindersHelp = {}) }
            }
        }
        // The reminder time comes from DataStore a frame after the rest.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("08:00")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun s(id: Int) = compose.activity.getString(id)

    // Screen capture, not onRoot(): the dialog lives in its own window.
    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/settings_${name}_$suffix.png", roborazziOptions = exact)
    }

    @Test
    fun top() = capture("top")

    @Test
    fun bottom() {
        compose.onNodeWithText(s(R.string.about_font_licence)).performScrollTo()
        capture("bottom")
    }

    @Test
    fun middle() {
        compose.onNodeWithText(s(R.string.developer_mode)).performScrollTo()
        capture("middle")
    }

    @Test
    fun importConfirmation() {
        compose.onNodeWithText(s(R.string.import_json)).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(s(R.string.import_confirm_title))).fetchSemanticsNodes().isNotEmpty() }
        capture("import")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} fontScale={1}")
        fun params() = listOf(arrayOf<Any>(false, 1.0f), arrayOf<Any>(false, 1.3f), arrayOf<Any>(true, 1.0f), arrayOf<Any>(true, 1.3f))
    }
}
