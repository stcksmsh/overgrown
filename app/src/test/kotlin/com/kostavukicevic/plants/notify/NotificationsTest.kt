// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.PromptReason
import com.kostavukicevic.plants.ui.heatWave
import com.kostavukicevic.plants.ui.place
import com.kostavukicevic.plants.ui.plant
import com.kostavukicevic.plants.ui.useTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** What the notifications offer and what their actions store, through the real ActionReceiver. */
@RunWith(RobolectricTestRunner::class)
class NotificationsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private lateinit var g: Graph

    @Before
    fun setUp() {
        g = useTestDatabase()
        Notifier.createChannels(app)
    }

    private fun allow(granted: Boolean = true) =
        if (granted) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        else shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    /** Taps [action] as the system would: its saved intent goes to the manifest's receiver. */
    private fun tap(action: Notification.Action) {
        app.sendBroadcast(shadowOf(action.actionIntent).savedIntent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun answers(plantId: Long) = runBlocking { g.garden.events(plantId).first() }.filter { it.type == "CHECK_IN" }.sortedBy { it.id }.map { it.moisture }

    private fun soilCheck(plantId: Long, name: String): Notification {
        assertTrue(Notifier.postPrompt(app, plantId, name, Action.CheckSoil(PromptReason.UNCERTAIN), emptyList()))
        return nm.getNotification(Notifier.promptId(plantId))
    }

    @Test
    fun soilCheckOffersWetOkDryAndEachStoresItsAnswer() {
        allow()
        val hall = place(g, "Hall")
        val fern = plant(g, "Fern", hall)
        val ivy = plant(g, "Ivy", hall)
        val labels = listOf(R.string.answer_wet, R.string.answer_ok, R.string.answer_dry).map { app.getString(it) }
        val fernActions = soilCheck(fern, "Fern").actions.toList()
        val ivyActions = soilCheck(ivy, "Ivy").actions.toList()
        assertEquals(labels, fernActions.map { it.title.toString() })
        assertEquals(labels, ivyActions.map { it.title.toString() })

        // Interleaved across two plants: a PendingIntent shared between them would store the wrong plant or answer.
        tap(fernActions[0])
        waitUntil("Wet on Fern") { answers(fern).size == 1 }
        tap(ivyActions[1])
        waitUntil("OK on Ivy") { answers(ivy).size == 1 }
        tap(fernActions[2])
        waitUntil("Dry on Fern") { answers(fern).size == 2 }
        assertEquals(listOf("TOO_WET", "DRY_OR_BONE_DRY"), answers(fern))
        assertEquals(listOf("OK"), answers(ivy))
        waitUntil("the notification is taken down") { nm.getNotification(Notifier.promptId(fern)) == null }
    }

    @Test
    fun aWateringPromptStillOffersWateredAndSnooze() {
        allow()
        val pothos = plant(g, "Pothos", place(g, "Hall"))
        assertTrue(Notifier.postPrompt(app, pothos, "Pothos", Action.WaterNow(700, PromptReason.DRY_BY_MODEL), emptyList()))
        val n = nm.getNotification(Notifier.promptId(pothos))
        assertEquals(app.getString(R.string.notif_water_title, "Pothos"), n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf(R.string.notif_action_watered, R.string.notif_action_snooze).map { app.getString(it) }, n.actions.map { it.title.toString() })
    }

    @Test
    fun anAnswerForADeletedPlantIsIgnored() {
        allow()
        val hall = place(g, "Hall")
        val fern = plant(g, "Fern", hall)
        val ok = soilCheck(fern, "Fern").actions[1]
        runBlocking { g.garden.deletePlant(fern) }
        tap(ok)
        waitUntil("the notification is taken down") { nm.getNotification(Notifier.promptId(fern)) == null }
        assertTrue(answers(fern).isEmpty())
    }

    @Test
    fun twoPlantsInOneEventPostOneNotificationAndDoneStoresDone() {
        allow()
        heatWave(g, "Boris", "Fern")
        val e = runBlocking { g.garden.alertEvents() }.single()
        assertTrue(runBlocking { g.scheduler.postEvent(e.key) })
        // Once per event: the same event does not post again.
        assertFalse(runBlocking { g.scheduler.postEvent(e.key) })
        assertEquals(1, nm.allNotifications.size)
        val n = nm.getNotification(Notifier.eventId(e.key))
        val title = n.extras.getString(Notification.EXTRA_TITLE)!!
        assertEquals(Texts.eventTitle(app, e, g.garden.today()), title)
        assertTrue(title, title.startsWith("Heat ") && title.endsWith(": Boris and Fern"))
        assertEquals(Texts.eventAdvice(app, e), n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf(R.string.alert_done, R.string.alert_dismiss).map { app.getString(it) }, n.actions.map { it.title.toString() })

        tap(n.actions[0])
        waitUntil("Done is stored") { runBlocking { g.garden.alertEvents() }.isEmpty() }
        assertNull(runBlocking { g.settings.alertAcks() }.single().dismissedOn)
        waitUntil("the notification is taken down") { nm.allNotifications.isEmpty() }
    }

    @Test
    fun dismissFromTheNotificationStoresDismiss() {
        allow()
        heatWave(g, "Boris")
        val e = runBlocking { g.garden.alertEvents() }.single()
        assertTrue(runBlocking { g.scheduler.postEvent(e.key) })
        tap(nm.getNotification(Notifier.eventId(e.key)).actions[1])
        waitUntil("Dismiss is stored") { runBlocking { g.garden.alertEvents() }.isEmpty() }
        assertEquals(g.garden.today().toString(), runBlocking { g.settings.alertAcks() }.single().dismissedOn)
    }

    @Test
    fun anEventThatGetsANewPlantAfterDonePostsAgain() {
        allow()
        val ids = heatWave(g, "Boris")
        val first = runBlocking { g.garden.alertEvents() }.single()
        assertTrue(runBlocking { g.scheduler.postEvent(first.key) })
        runBlocking { g.garden.done(first) }
        // Fern joins Boris's place, so the same heat now reaches a plant that was not in it at Done time.
        plant(g, "Fern", runBlocking { g.garden.plant(ids.single())!!.placeId })
        val back = runBlocking { g.garden.alertEvents() }.single()
        assertTrue(back.key != first.key)
        assertTrue(runBlocking { g.scheduler.postEvent(back.key) })
    }

    @Test
    fun withoutPermissionNothingIsPostedOrMarkedPosted() {
        allow(granted = false)
        heatWave(g, "Boris")
        val e = runBlocking { g.garden.alertEvents() }.single()
        assertFalse(runBlocking { g.scheduler.postEvent(e.key) })
        assertFalse(g.scheduler.alertPosted(e.key))
        assertTrue(nm.allNotifications.isEmpty())
    }
}
