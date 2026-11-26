// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.kostavukicevic.plants.App
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
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

/**
 * Onboarding for a new user: the notification permission is asked for right after the first plant is saved and
 * not before, Today says when reminders are off, and Settings leads to the battery-saver guidance.
 * Robolectric runs Android 16 here, so the runtime permission applies.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private lateinit var g: Graph
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        g = useTestDatabase()
    }

    private fun s(id: Int) = compose.activity.getString(id)
    private fun waitFor(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }
    private fun startedActivity(): Intent? = shadowOf(compose.activity).nextStartedActivity
    private fun requested(): String? = shadowOf(compose.activity).lastRequestedPermission?.requestedPermissions?.single()

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    private fun systemSwitch(on: Boolean) =
        shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setNotificationsEnabled(on)

    private fun showToday() = compose.setContent { OvergrownTheme { TodayScreen(onPlant = {}, onAdd = {}, onPlaces = {}, onSettings = {}) } }

    private fun press(id: Int) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(s(id)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(s(id))[0].performClick()
    }

    private fun field(label: Int) = compose.onNode(hasSetTextAction() and hasText(s(label))).performScrollTo()
    private fun tap(id: Int) = compose.onNodeWithText(s(id)).performScrollTo().performClick()

    /** The add-plant wizard's four steps for one indoor plant in a new place, then Save. */
    private fun addPlantThroughTheWizard() {
        field(R.string.wizard_name).performTextInput("Mystery fern")
        tap(R.string.look_fern)
        press(R.string.wizard_next_where)
        tap(R.string.place_indoor)
        field(R.string.wizard_place_name).performTextInput("Kitchen")
        tap(R.string.wizard_advanced)
        field(R.string.field_latitude).performTextInput("44.8")
        field(R.string.field_longitude).performTextInput("20.5")
        press(R.string.wizard_next_pot)
        field(R.string.wizard_pot_width).performTextInput("20")
        field(R.string.wizard_plant_width).performTextInput("30")
        press(R.string.wizard_next_exposure)
        tap(R.string.answer_little)
        press(R.string.save)
    }

    @Test
    fun theDecisionAsksOnlyAfterANewPlantOnAndroid13AndLater() {
        assertTrue(shouldAskForNotifications(33, granted = false, plantJustAdded = true))
        assertFalse("asked before any plant was saved", shouldAskForNotifications(33, granted = false, plantJustAdded = false))
        assertFalse("asked although granted", shouldAskForNotifications(33, granted = true, plantJustAdded = true))
        assertFalse("asked where there is no runtime permission", shouldAskForNotifications(32, granted = false, plantJustAdded = true))
    }

    @Test
    fun nothingIsAskedAtLaunchAndTheFirstSavedPlantAsksWithAReason() {
        var adding by mutableStateOf(false)
        compose.setContent {
            OvergrownTheme {
                if (adding) AddPlantWizard(null, onDone = { adding = false }, careDb = WizardTest.FIXTURE_DB)
                else TodayScreen(onPlant = {}, onAdd = { adding = true }, onPlaces = {}, onSettings = {})
            }
        }
        waitFor(s(R.string.today_add_first))
        compose.onNodeWithText(s(R.string.notifications_reason)).assertDoesNotExist()
        assertNull("the permission was asked for at launch", requested())

        compose.onNodeWithText(s(R.string.today_add_first)).performClick()
        addPlantThroughTheWizard()
        waitFor(s(R.string.notifications_reason))
        // Asked unprompted, as a dialog, not left as text on Today for the person to find.
        compose.onNode(isDialog()).assertExists()
        // The line waits until the question is answered.
        compose.onNodeWithText(s(R.string.notifications_off)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.notifications_allow)).performClick()
        compose.waitForIdle()
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, requested())
        gone(s(R.string.notifications_reason))
    }

    @Test
    fun theAppAtLaunchWithPlantsAsksNothing() {
        plant(g, "Fern", place(g, "Hall"))
        compose.setContent { OvergrownTheme { App(null) } }
        waitFor(s(R.string.notifications_off))
        compose.onNodeWithText(s(R.string.notifications_reason)).assertDoesNotExist()
        assertNull(requested())
    }

    @Test
    fun notNowLeavesTheRemindersOffLine() {
        plant(g, "Fern", place(g, "Hall"))
        Onboarding.plantAdded(app)
        showToday()
        waitFor(s(R.string.notifications_reason))
        compose.onNodeWithText(s(R.string.notifications_not_now)).performClick()
        waitFor(s(R.string.notifications_off))
        assertNull(requested())
    }

    @Test
    fun aGrantedPermissionDropsThePendingAsk() {
        grant()
        plant(g, "Fern", place(g, "Hall"))
        Onboarding.plantAdded(app)
        assertFalse(Onboarding.askNow(app))
        // Revoked later: the old save does not ask at a later launch.
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(Onboarding.askNow(app))
    }

    @Test
    fun notificationsOffShowsTheLineWithItsSettingsButton() {
        grant()
        systemSwitch(false)
        plant(g, "Fern", place(g, "Hall"))
        showToday()
        waitFor(s(R.string.notifications_off))
        compose.onNodeWithText(s(R.string.notifications_open_settings)).performScrollTo().performClick()
        val opened = startedActivity()!!
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(app.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun notificationsOnShowNoLine() {
        grant()
        systemSwitch(true)
        plant(g, "Fern", place(g, "Hall"))
        showToday()
        waitFor("Fern")
        compose.onNodeWithText(s(R.string.notifications_off)).assertDoesNotExist()
    }

    @Test
    fun theMissingPermissionAloneShowsTheLine() {
        systemSwitch(true)
        plant(g, "Fern", place(g, "Hall"))
        showToday()
        waitFor(s(R.string.notifications_off))
    }

    @Test
    fun turningRemindersOnInTheSystemSettingsTakesTheLineAwayOnReturn() {
        grant()
        systemSwitch(false)
        plant(g, "Fern", place(g, "Hall"))
        showToday()
        waitFor(s(R.string.notifications_off))
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        systemSwitch(true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        gone(s(R.string.notifications_off))
    }

    @Test
    fun aDismissedLineStaysAwayUntilAPlantIsAdded() {
        grant()
        systemSwitch(false)
        plant(g, "Fern", place(g, "Hall"))
        var key by mutableStateOf(0)
        compose.setContent {
            OvergrownTheme {
                // A new key composes Today afresh, as on a later visit.
                androidx.compose.runtime.key(key) { TodayScreen(onPlant = {}, onAdd = {}, onPlaces = {}, onSettings = {}) }
            }
        }
        waitFor(s(R.string.notifications_off))
        compose.onNodeWithText(s(R.string.notifications_off_hide)).performScrollTo().performClick()
        gone(s(R.string.notifications_off))
        key++
        waitFor("Fern")
        compose.onNodeWithText(s(R.string.notifications_off)).assertDoesNotExist()

        Onboarding.plantAdded(app)
        key++
        waitFor(s(R.string.notifications_off))
    }

    @Test
    fun settingsLeadsToTheBatteryGuidance() {
        var opened = false
        compose.setContent { OvergrownTheme { SettingsScreen(onBack = {}, onRemindersHelp = { opened = true }) } }
        compose.onNodeWithText(s(R.string.notifications_help)).performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun theBatteryGuidanceOpensTheBatterySettingsAndDontKillMyApp() {
        compose.setContent { OvergrownTheme { RemindersHelpScreen(onBack = {}) } }
        compose.onNodeWithText(s(R.string.notifications_help_setting)).assertExists()
        compose.onNodeWithText(s(R.string.notifications_help_battery)).performScrollTo().performClick()
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, startedActivity()?.action)
        compose.onNodeWithText(s(R.string.notifications_help_vendor_link)).performScrollTo().performClick()
        val link = startedActivity()!!
        assertEquals(Intent.ACTION_VIEW, link.action)
        assertEquals("https://dontkillmyapp.com/", link.dataString)
    }

    /** Asking to be exempted from battery optimisation is a Play policy risk: the merged manifest never requests it. */
    @Test
    fun theMergedManifestDoesNotAskToIgnoreBatteryOptimisations() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        // The list is read from the merged manifest: the permission the app does use is there.
        assertTrue(requested.toString(), Manifest.permission.POST_NOTIFICATIONS in requested)
        assertFalse(requested.toString(), Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in requested)
    }
}
