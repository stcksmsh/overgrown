// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The Settings screen: import asks first, weighing without developer mode, theme, voice, reminder time and About. */
@RunWith(RobolectricTestRunner::class)
class SettingsTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private lateinit var g: Graph

    @Before
    fun setUp() {
        g = useTestDatabase()
        compose.setContent { OvergrownTheme { SettingsScreen(onBack = {}, onRemindersHelp = {}) } }
    }

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun waitFor(text: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(text: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    /**
     * Reads a stored value off the main thread while [compose] keeps the main looper running; fails after 5 s, so
     * a read that waits for a value doubles as a bounded wait for it. A runBlocking read on the main thread can
     * deadlock: the screen's own collector may hold DataStore's first read and need the main thread to finish it.
     */
    private fun <T> stored(read: suspend () -> T): T {
        val value = CoroutineScope(Dispatchers.IO).async { read() }
        try {
            compose.waitUntil(5_000) { value.isCompleted }
        } finally {
            value.cancel()
        }
        return runBlocking { value.await() }
    }

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
        assertFalse(stored { g.settings.developerMode.first() })
        compose.onNode(isToggleable() and hasContentDescription(s(R.string.settings_weighing))).performScrollTo().performClick()
        stored { g.settings.weighing.first { it } }
        assertFalse(stored { g.settings.developerMode.first() })
    }

    @Test
    fun aThemeTapIsStored() {
        compose.onNodeWithText(s(R.string.settings_theme_dark)).performScrollTo().performClick()
        stored { g.settings.theme.first { it == ThemeChoice.DARK } }
    }

    @Test
    fun aCancelledTimePickerChangesNothing() {
        val change = s(R.string.settings_reminder_time_change, "08:00")
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(change)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(change).performClick()
        waitFor(s(R.string.settings_time_set))
        compose.onNodeWithText(s(R.string.cancel)).performClick()
        gone(s(R.string.settings_time_set))
        assertEquals(LocalTime(8, 0), stored { g.settings.reminderTime.first() })
    }

    @Test
    fun theVoiceIsBorisUntilPlainIsChosenAndThePlainChoiceIsStored() {
        assertTrue(stored { g.settings.borisVoice.first() })
        compose.onNodeWithText(s(R.string.settings_voice)).performScrollTo().assertExists()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(s(R.string.settings_voice_mascot)) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(s(R.string.settings_voice_plain)).performScrollTo().performClick()
        stored { g.settings.borisVoice.first { !it } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(s(R.string.settings_voice_plain)) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aboutIsBuiltForBoris() {
        compose.onNodeWithText("Built for Boris, a false cypress on a balcony in Belgrade.").performScrollTo().assertExists()
    }

    /** The general care notice: general information, check your own plant, no guarantee. */
    @Test
    fun aboutShowsTheCareNotice() {
        compose.onNodeWithText(s(R.string.about_care_notice)).performScrollTo().assertExists()
        compose.onNodeWithText("Check your own plant: there is no guarantee that this advice suits it.").performScrollTo().assertExists()
    }
}
