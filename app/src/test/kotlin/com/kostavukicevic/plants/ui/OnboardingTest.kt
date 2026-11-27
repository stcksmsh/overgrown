// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
import com.kostavukicevic.plants.notify.Notifier
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
import org.robolectric.shadows.ShadowToast

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

    /** One channel's switch in the system's settings, the app's own switch left as it is. */
    private fun channelSwitch(id: String, on: Boolean) {
        Notifier.createChannels(app)
        app.getSystemService(NotificationManager::class.java).getNotificationChannel(id).importance =
            if (on) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_NONE
    }

    /** Today with the question over it, as App shows them. */
    private fun showToday() = compose.setContent {
        OvergrownTheme {
            TodayScreen(onPlant = {}, onAdd = {}, onPlaces = {}, onSettings = {})
            NotificationQuestion()
        }
    }

    /**
     * The system's answer to the request it was showing: refused, and whether it would still show a rationale.
     * Delivered as the platform does, so the activity ends that request and can make the next one.
     */
    private fun refuse(rationale: Boolean) {
        val shown = shadowOf(compose.activity).nextStartedActivityForResult!!
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, rationale)
        // PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES and _RESULTS, which are hidden.
        val result = Intent()
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", intArrayOf(PackageManager.PERMISSION_DENIED))
        compose.runOnUiThread { shadowOf(compose.activity).receiveResult(shown.intent, Activity.RESULT_OK, result) }
        compose.waitForIdle()
        // What was started so far is done with.
        generateSequence { startedActivity() }.count()
    }

    /** What the wizard does on saving a new plant. */
    private fun savePlant() {
        Onboarding.plantAdded(app)
        g.changed()
    }

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

    /** The activity the platform starts for a permission request (PackageManager.ACTION_REQUEST_PERMISSIONS, hidden). */
    private val PERMISSION_SCREEN = "android.content.pm.action.REQUEST_PERMISSIONS"

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
                NotificationQuestion()
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
    fun afterOneRefusalTheNextPlantAsksTheSystemAgain() {
        plant(g, "Fern", place(g, "Hall"))
        savePlant()
        showToday()
        waitFor(s(R.string.notifications_reason))
        compose.onNodeWithText(s(R.string.notifications_allow)).performClick()
        compose.waitForIdle()
        refuse(rationale = true)
        waitFor(s(R.string.notifications_off))

        savePlant()
        waitFor(s(R.string.notifications_reason))
        compose.onNodeWithText(s(R.string.notifications_allow)).performClick()
        compose.waitForIdle()
        // The system's permission screen again, not the settings.
        val actions = generateSequence { startedActivity() }.map { it.action }.toList()
        assertEquals(listOf(PERMISSION_SCREEN), actions)
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, requested())
    }

    /** Refused twice, the system answers at once without showing its dialog: Allow leads to the settings instead. */
    @Test
    fun afterTheSystemStopsAskingAllowOpensTheNotificationSettings() {
        plant(g, "Fern", place(g, "Hall"))
        savePlant()
        showToday()
        waitFor(s(R.string.notifications_reason))
        compose.onNodeWithText(s(R.string.notifications_allow)).performClick()
        compose.waitForIdle()
        refuse(rationale = false)
        waitFor(s(R.string.notifications_off))

        savePlant()
        waitFor(s(R.string.notifications_reason))
        compose.onNodeWithText(s(R.string.notifications_allow)).performClick()
        compose.waitForIdle()
        // The settings, and no request that the system would refuse unseen.
        val started = generateSequence { startedActivity() }.toList()
        assertEquals(listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS), started.map { it.action })
        assertEquals(app.packageName, started.single().getStringExtra(Settings.EXTRA_APP_PACKAGE))
        gone(s(R.string.notifications_reason))
    }

    /** The wizard opened from Plants returns to Plants: the question shows there, not on the next visit to Today. */
    @Test
    fun aPlantSavedFromPlantsIsAskedAboutOnPlants() {
        plant(g, "Fern", place(g, "Hall"))
        compose.setContent { OvergrownTheme { App(null) } }
        waitFor(s(R.string.plants_title))
        compose.onAllNodesWithText(s(R.string.plants_title))[0].performClick()
        // Today's line is gone with Today.
        gone(s(R.string.notifications_off))
        savePlant()
        waitFor(s(R.string.notifications_reason))
        compose.onNode(isDialog()).assertExists()
    }

    @Test
    fun aLinkOnAPhoneWithNoBrowserDoesNotEndTheApp() {
        shadowOf(app).checkActivities(true)
        compose.setContent { OvergrownTheme { RemindersHelpScreen(onBack = {}) } }
        compose.onNodeWithText(s(R.string.notifications_help_vendor_link)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(s(R.string.link_no_app), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aPendingAskIsNotMadeWhilePermissionIsGranted() {
        plant(g, "Fern", place(g, "Hall"))
        Onboarding.plantAdded(app)
        assertTrue(Onboarding.askNow(app))
        grant()
        assertFalse(Onboarding.askNow(app))
    }

    /** Blocked, then granted in the system's settings and the app opened, then revoked: Allow asks the system again. */
    @Test
    fun aGrantAfterAPermanentDenialClearsTheBlock() {
        plant(g, "Fern", place(g, "Hall"))
        Onboarding.block(app)
        grant()
        showToday()
        compose.waitForIdle()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        savePlant()
        waitFor(s(R.string.notifications_reason))
        compose.onNodeWithText(s(R.string.notifications_allow)).performClick()
        compose.waitForIdle()
        val actions = generateSequence { startedActivity() }.map { it.action }.toList()
        assertEquals(listOf(PERMISSION_SCREEN), actions)
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, requested())
    }

    @Test
    fun aPlantAddedWithPermissionStoresNoAsk() {
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
        Notifier.createChannels(app)
        plant(g, "Fern", place(g, "Hall"))
        showToday()
        waitFor("Fern")
        compose.onNodeWithText(s(R.string.notifications_off)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.notifications_prompts_off)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.notifications_alerts_off)).assertDoesNotExist()
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

    /** The line follows the system's switch as it is now, not the history: a block, a grant, a revoke and a re-enable. */
    @Test
    fun aRevokeAfterAGrantShowsTheLineAndReEnablingTakesItAway() {
        Onboarding.block(app)
        grant()
        systemSwitch(true)
        Notifier.createChannels(app)
        plant(g, "Fern", place(g, "Hall"))
        showToday()
        waitFor("Fern")
        compose.onNodeWithText(s(R.string.notifications_off)).assertDoesNotExist()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        systemSwitch(false)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor(s(R.string.notifications_off))
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        systemSwitch(true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        gone(s(R.string.notifications_off))
    }

    @Test
    fun oneChannelOffNamesItAndOpensItsSettings() {
        grant()
        systemSwitch(true)
        channelSwitch(Notifier.CHANNEL_ALERTS, on = false)
        plant(g, "Fern", place(g, "Hall"))
        showToday()
        waitFor(s(R.string.notifications_alerts_off))
        compose.onNodeWithText(s(R.string.notifications_off)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.notifications_open_settings)).performScrollTo().performClick()
        val opened = startedActivity()!!
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(app.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(Notifier.CHANNEL_ALERTS, opened.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    /** Only the app's notification settings resolve: the channel's page is skipped, not the app's details shown. */
    @Test
    fun aPhoneWithoutChannelSettingsOpensTheAppNotificationSettings() {
        grant()
        systemSwitch(true)
        channelSwitch(Notifier.CHANNEL_PROMPTS, on = false)
        plant(g, "Fern", place(g, "Hall"))
        shadowOf(app).checkActivities(true)
        val settings = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "settings"; name = "Settings" } }
        shadowOf(app.packageManager).addResolveInfoForIntent(notificationSettingsIntent(app), settings)
        showToday()
        waitFor(s(R.string.notifications_prompts_off))
        compose.onNodeWithText(s(R.string.notifications_open_settings)).performScrollTo().performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, startedActivity()!!.action)
    }

    @Test
    fun bothChannelsOffShowTheGeneralLine() {
        grant()
        systemSwitch(true)
        channelSwitch(Notifier.CHANNEL_PROMPTS, on = false)
        channelSwitch(Notifier.CHANNEL_ALERTS, on = false)
        plant(g, "Fern", place(g, "Hall"))
        showToday()
        waitFor(s(R.string.notifications_off))
        compose.onNodeWithText(s(R.string.notifications_open_settings)).performScrollTo().performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, startedActivity()!!.action)
    }

    @Test
    fun aChannelTurnedBackOnTakesTheLineAwayOnReturn() {
        grant()
        systemSwitch(true)
        channelSwitch(Notifier.CHANNEL_ALERTS, on = false)
        plant(g, "Fern", place(g, "Hall"))
        showToday()
        waitFor(s(R.string.notifications_alerts_off))
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        channelSwitch(Notifier.CHANNEL_ALERTS, on = true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        gone(s(R.string.notifications_alerts_off))
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
