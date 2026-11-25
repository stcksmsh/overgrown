// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui.theme

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.kostavukicevic.plants.ChosenTheme
import com.kostavukicevic.plants.data.ThemeChoice
import com.kostavukicevic.plants.graph
import com.kostavukicevic.plants.ui.RegisterHostActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The theme chosen in Settings wins over the system's light or dark, and a change shows at once. */
@RunWith(RobolectricTestRunner::class)
class ChosenThemeTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private val settings = ApplicationProvider.getApplicationContext<Application>().graph.settings
    private var ground: Color? = null

    private fun start() = compose.setContent { ChosenTheme { ground = Overgrown.colors.ground } }

    private fun waitForGround(expected: Color) = compose.waitUntil(5_000) { ground == expected }

    @Test
    @Config(qualifiers = "notnight")
    fun darkChosenUnderALightSystemIsDarkAndLightTakesOverWithoutARestart() {
        runBlocking { settings.setTheme(ThemeChoice.DARK) }
        start()
        waitForGround(DarkColors.ground)
        runBlocking { settings.setTheme(ThemeChoice.LIGHT) }
        waitForGround(LightColors.ground)
    }

    @Test
    @Config(qualifiers = "night")
    fun aFreshInstallFollowsTheSystem() {
        start()
        waitForGround(DarkColors.ground)
        runBlocking { settings.setTheme(ThemeChoice.LIGHT) }
        waitForGround(LightColors.ground)
    }
}
