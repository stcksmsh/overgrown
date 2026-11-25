// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.data.ThemeChoice
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The Settings screen: import asks first, weighing without developer mode, theme and reminder time. */
@RunWith(RobolectricTestRunner::class)
class SettingsTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private lateinit var g: Graph

    @Before
    fun setUp() {
        g = useTestDatabase()
        compose.setContent { OvergrownTheme { SettingsScreen(onBack = {}) } }
    }

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(text: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(text: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    private fun startedActivity(): Intent? = shadowOf(compose.activity).nextStartedActivity

    @Test
    fun importAsksFirstAndCancelOpensNoPicker() {
        compose.onNodeWithText(s(R.string.import_json)).performScrollTo().performClick()
        waitFor(s(R.string.import_confirm_title))
        assertNull("the picker opened before the confirmation", startedActivity())
        compose.onNodeWithText(s(R.string.cancel)).performClick()
        gone(s(R.string.import_confirm_title))
        compose.waitForIdle()
        assertNull(startedActivity())
    }

    @Test
    fun confirmingImportOpensThePicker() {
        compose.onNodeWithText(s(R.string.import_json)).performScrollTo().performClick()
        waitFor(s(R.string.import_confirm_title))
        compose.onNodeWithText(s(R.string.import_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, startedActivity()?.action)
    }

    @Test
    fun weighingIsOnTheScreenWithDeveloperModeOff() {
        assertFalse(runBlocking { g.settings.developerMode.first() })
        compose.onNode(isToggleable() and hasContentDescription(s(R.string.settings_weighing))).performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { g.settings.weighing.first() } }
        assertFalse(runBlocking { g.settings.developerMode.first() })
    }

    @Test
    fun aThemeTapIsStored() {
        compose.onNodeWithText(s(R.string.settings_theme_dark)).performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { g.settings.theme.first() } == ThemeChoice.DARK }
    }

    @Test
    fun aCancelledTimePickerChangesNothing() {
        val change = s(R.string.settings_reminder_time_change, "08:00")
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(change)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(change).performClick()
        waitFor(s(R.string.settings_time_set))
        compose.onNodeWithText(s(R.string.cancel)).performClick()
        gone(s(R.string.settings_time_set))
        assertEquals(LocalTime(8, 0), runBlocking { g.settings.reminderTime.first() })
    }
}
