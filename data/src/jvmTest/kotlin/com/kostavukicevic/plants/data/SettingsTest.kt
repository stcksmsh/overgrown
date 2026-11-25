// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {

    private val store = PreferenceDataStoreFactory.createWithPath {
        Files.createTempDirectory("settings-test").resolve("s.preferences_pb").toOkioPath()
    }
    private val settings = Settings(store)

    @Test
    fun aFreshInstallRemindsAtEightAndFollowsTheSystemTheme() = runTest {
        assertEquals(LocalTime(8, 0), settings.reminderTime.first())
        assertEquals(ThemeChoice.SYSTEM, settings.theme.first())
        assertFalse(settings.weighing.first())
    }

    @Test
    fun choicesAreStored() = runTest {
        settings.setReminderTime(LocalTime(7, 30))
        settings.setTheme(ThemeChoice.DARK)
        settings.setWeighing(true)
        assertEquals(LocalTime(7, 30), settings.reminderTime.first())
        assertEquals(ThemeChoice.DARK, settings.theme.first())
        assertTrue(settings.weighing.first())
    }

    @Test
    fun weighingFollowsDeveloperModeUntilSet() = runTest {
        settings.setDeveloperMode(true)
        assertTrue(settings.weighing.first())
        settings.setWeighing(false)
        assertFalse(settings.weighing.first())
    }

    @Test
    fun unreadableValuesReadAsDefaults() = runTest {
        store.edit {
            it[intPreferencesKey("reminder_minute")] = 24 * 60
            it[stringPreferencesKey("theme")] = "PURPLE"
        }
        assertEquals(LocalTime(8, 0), settings.reminderTime.first())
        assertEquals(ThemeChoice.SYSTEM, settings.theme.first())
    }

    /** A phone that chose a transition under D44 keeps the key; the other settings read past it (D45). */
    @Test
    fun aTransitionStoredBeforeD45IsIgnored() = runTest {
        store.edit { it[stringPreferencesKey("transition")] = "FADE" }
        settings.setTheme(ThemeChoice.DARK)
        assertEquals(LocalTime(8, 0), settings.reminderTime.first())
        assertEquals(ThemeChoice.DARK, settings.theme.first())
        assertFalse(settings.weighing.first())
    }
}
