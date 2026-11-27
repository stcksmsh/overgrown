// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden images of the "Reminders not arriving?" screen, light and dark, at 1.0x, 1.3x and 2.0x. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class RemindersHelpScreenshotTest(private val dark: Boolean, private val fontScale: Float) {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private val suffix = "${if (dark) "dark" else "light"}_${fontScale}x"

    // Any changed pixel fails, as in ThemeScreenshotTest. Built per instance, inside the Robolectric sandbox.
    private val exact = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f, imageComparator = SimpleImageComparator(maxDistance = 0f)),
    )

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun screen() {
        compose.setContent {
            FontScaled(fontScale) {
                OvergrownTheme(dark = dark) { RemindersHelpScreen(onBack = {}) }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/reminders_help_$suffix.png", roborazziOptions = exact)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} fontScale={1}")
        fun params() = listOf(1.0f, 1.3f, 2.0f).flatMap { scale -> listOf(arrayOf<Any>(false, scale), arrayOf<Any>(true, scale)) }
    }
}
