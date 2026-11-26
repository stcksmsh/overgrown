// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.data.AlertAck
import com.kostavukicevic.plants.data.Garden
import com.kostavukicevic.plants.data.GardenDb
import com.kostavukicevic.plants.data.WeatherDayEntity
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.data.isPrompt
import com.kostavukicevic.plants.engine.PromptReason
import com.kostavukicevic.plants.ui.heatWave
import com.kostavukicevic.plants.ui.place
import com.kostavukicevic.plants.ui.plant
import com.kostavukicevic.plants.ui.useTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

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
        // WorkManager outlives a test and plant ids repeat, so an earlier test's job could stand in for this one's.
        WorkManager.getInstance(app).cancelAllWork().result.get()
    }

    private fun allow(granted: Boolean = true) =
        if (granted) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        else shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    /**
     * Taps [action] as the system would: its saved intent goes to the manifest's receiver. Returns once the
     * receiver's work, which ends with a reschedule, is done: left running, that reschedule would cancel or replace
     * jobs in the next test, since WorkManager is shared across tests.
     */
    private fun tap(action: Notification.Action) {
        val before = g.changes.value
        app.sendBroadcast(shadowOf(action.actionIntent).savedIntent)
        shadowOf(Looper.getMainLooper()).idle()
        waitUntil("the receiver to finish") { g.changes.value > before }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    /** Moves the garden's clock [days] ahead, as if the person came back to the app later. */
    private fun passDays(days: Int) {
        val at = Clock.System.now() + days.days
        val now: () -> Instant = { at }
        Garden::class.java.getDeclaredField("now").apply { isAccessible = true }.set(g.garden, now)
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
        assertTrue(runBlocking { g.scheduler.postEvent(AlertAck.of(e)) })
        // Once per event: the same event does not post again.
        assertFalse(runBlocking { g.scheduler.postEvent(AlertAck.of(e)) })
        assertEquals(1, nm.allNotifications.size)
        val n = nm.getNotification(Notifier.ALERT_TAG, Notifier.alertId(AlertAck.of(e)))
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
        assertTrue(runBlocking { g.scheduler.postEvent(AlertAck.of(e)) })
        tap(nm.getNotification(Notifier.ALERT_TAG, Notifier.alertId(AlertAck.of(e))).actions[1])
        waitUntil("Dismiss is stored") { runBlocking { g.garden.alertEvents() }.isEmpty() }
        assertEquals(g.garden.today().toString(), runBlocking { g.settings.alertAcks() }.single().dismissedOn)
    }

    @Test
    fun anEventThatGetsANewPlantAfterDonePostsAgain() {
        allow()
        val ids = heatWave(g, "Boris")
        val first = runBlocking { g.garden.alertEvents() }.single()
        assertTrue(runBlocking { g.scheduler.postEvent(AlertAck.of(first)) })
        runBlocking { g.garden.done(first) }
        // Fern joins Boris's place, so the same heat now reaches a plant that was not in it at Done time.
        plant(g, "Fern", runBlocking { g.garden.plant(ids.single())!!.placeId })
        val back = runBlocking { g.garden.alertEvents() }.single()
        assertTrue(back.key != first.key)
        assertTrue(runBlocking { g.scheduler.postEvent(AlertAck.of(back)) })
    }

    @Test
    fun withoutPermissionNothingIsPostedOrMarkedPosted() {
        allow(granted = false)
        heatWave(g, "Boris")
        val e = runBlocking { g.garden.alertEvents() }.single()
        assertFalse(runBlocking { g.scheduler.postEvent(AlertAck.of(e)) })
        assertFalse(g.scheduler.eventPosted(e, g.garden.today()))
        assertTrue(nm.allNotifications.isEmpty())
    }

    @Test
    fun anEventPostedTheEveningBeforeIsNotPostedAgainOnItsLaterDays() {
        allow()
        heatWave(g, "Boris")
        val first = runBlocking { g.garden.alertEvents() }.single()
        assertTrue(runBlocking { g.scheduler.postEvent(AlertAck.of(first)) })
        for (day in 1..3) {
            passDays(day)
            val e = runBlocking { g.garden.alertEvents() }.single()
            // Its start is now today, so it is not the event that was posted by its own day-bound key.
            assertEquals(g.garden.today(), e.start)
            assertFalse("day $day", runBlocking { g.scheduler.postEvent(AlertAck.of(e)) })
            runBlocking { g.scheduler.reschedule() }
        }
        assertEquals(1, nm.allNotifications.size)
    }

    @Test
    fun doneFromYesterdaysNotificationStillStoresDone() {
        allow()
        heatWave(g, "Boris")
        val e = runBlocking { g.garden.alertEvents() }.single()
        assertTrue(runBlocking { g.scheduler.postEvent(AlertAck.of(e)) })
        val n = nm.getNotification(Notifier.ALERT_TAG, Notifier.alertId(AlertAck.of(e)))
        passDays(1)
        tap(n.actions[0])
        waitUntil("Done is stored") { runBlocking { g.garden.alertEvents() }.isEmpty() }
        waitUntil("the notification is taken down") { nm.allNotifications.isEmpty() }
    }

    @Test
    fun aPerPlantAlertPostedBeforeTheUpdateIsNotPostedAgain() {
        allow()
        val boris = heatWave(g, "Boris").single()
        val e = runBlocking { g.garden.alertEvents() }.single()
        // The key the per-plant alerts used: plant, alert type, day.
        app.getSharedPreferences("posted_alerts", 0).edit().putBoolean("$boris-HEAT-${e.start}", true).commit()
        assertFalse(runBlocking { g.scheduler.postEvent(AlertAck.of(e)) })
        assertTrue(nm.allNotifications.isEmpty())
    }

    @Test
    fun alertsArePostedUnderTheirOwnTagSoNoIdMeetsAPrompt() {
        allow()
        heatWave(g, "Boris")
        val e = runBlocking { g.garden.alertEvents() }.single()
        assertTrue(runBlocking { g.scheduler.postEvent(AlertAck.of(e)) })
        val id = Notifier.alertId(AlertAck.of(e))
        assertNull(nm.getNotification(id))
        assertTrue(nm.getNotification(Notifier.ALERT_TAG, id) != null)
    }

    /** The pending prompt job for [plantId] (named by day, today to the day after tomorrow), or null when none is. */
    private fun promptJob(plantId: Long): WorkInfo? {
        val today = runBlocking { g.garden.today() }
        val names = (0..2).map { "prompt-$plantId-${today.plus(DatePeriod(days = it))}" }
        return WorkManager.getInstance(app).getWorkInfos(WorkQuery.fromUniqueWorkNames(names)).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }.also { assertTrue("more than one prompt job", it.size <= 1) }.singleOrNull()
    }

    /**
     * When the pending prompt job for [plantId] will run, in local time, or null when none is pending. Rounded to
     * the nearest minute: the delay is cut to whole milliseconds, which can put a job at 19:59:59.999.
     */
    private fun promptJobAt(plantId: Long): LocalDateTime? =
        promptJob(plantId)?.let { Instant.fromEpochMilliseconds(it.nextScheduleTimeMillis + 30_000).toLocalDateTime(TimeZone.currentSystemDefault()) }

    private fun LocalDateTime.minuteOnly() = date.atTime(hour, minute)

    private fun nextPromptDate(plantId: Long): LocalDate = runBlocking { g.garden.status(plantId) }!!.nextPrompt!!.first

    @Test
    fun promptsComeAtTheChosenReminderTime() {
        val fern = plant(g, "Fern", place(g, "Hall"))
        // Hours away from now, so neither the one-minute minimum delay nor a time just passing moves the job.
        val time = LocalTime((Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour + 3) % 24, 30)
        runBlocking {
            g.settings.setReminderTime(time)
            g.scheduler.reschedule()
        }
        val at = promptJobAt(fern)!!
        assertEquals(time, LocalTime(at.hour, at.minute))
    }

    @Test
    fun aTimeAlreadyPastTodayWaitsForTomorrowAndATodaysPostedPromptComesBackTomorrow() {
        val fern = plant(g, "Fern", place(g, "Hall"))
        val today = runBlocking { g.garden.today() }
        assertEquals("a plant with no history is asked about today", today, nextPromptDate(fern))
        // Midnight has always passed, so today's prompt is not posted at once but at the next midnight.
        runBlocking {
            g.settings.setReminderTime(LocalTime(0, 0))
            g.scheduler.reschedule()
        }
        assertEquals(today.plus(DatePeriod(days = 1)).atTime(0, 0), promptJobAt(fern)!!.minuteOnly())

        // Shown today: not again today, but tomorrow at the chosen time if still unanswered.
        g.scheduler.markPromptPosted(fern, today)
        runBlocking { g.settings.setReminderTime(LocalTime(23, 59)) }
        runBlocking { g.scheduler.reschedule() }
        assertEquals(today.plus(DatePeriod(days = 1)).atTime(23, 59), promptJobAt(fern)!!.minuteOnly())
    }

    @Test
    fun aPromptShownYesterdayAndUnansweredIsPostedTodayEvenWhenItsTimeHasPassed() {
        val fern = plant(g, "Fern", place(g, "Hall"))
        val today = runBlocking { g.garden.today() }
        assertEquals(today, nextPromptDate(fern))
        // Yesterday's prompt was ignored and today's job was replaced by a reschedule after the chosen time.
        g.scheduler.markPromptPosted(fern, today.minus(DatePeriod(days = 1)))
        runBlocking {
            g.settings.setReminderTime(LocalTime(0, 0))
            g.scheduler.reschedule()
        }
        val wait = promptJob(fern)!!.nextScheduleTimeMillis - System.currentTimeMillis()
        assertTrue("today's prompt waits $wait ms", wait <= 2 * 60_000)
    }

    /** The local time [hours] ago, to the minute: a chosen time the job is not late for, day or night. */
    private fun hoursAgo(hours: Int): LocalTime {
        val t = (Clock.System.now() - hours.hours).toLocalDateTime(TimeZone.currentSystemDefault())
        return LocalTime(t.hour, t.minute)
    }

    @Test
    fun thePromptJobPostsOnceAndArmsTomorrows() {
        allow()
        val fern = plant(g, "Fern", place(g, "Hall"))
        val today = runBlocking { g.garden.today() }
        val time = hoursAgo(1)
        runBlocking {
            g.settings.setReminderTime(time)
            g.scheduler.runPrompt(fern)
        }
        assertTrue(g.scheduler.promptPosted(fern, today))
        assertEquals(1, nm.allNotifications.size)
        assertEquals(today.plus(DatePeriod(days = 1)).atTime(time), promptJobAt(fern)!!.minuteOnly())
        // A second run the same day (a late duplicate) posts nothing new.
        app.getSystemService(NotificationManager::class.java).cancelAll()
        runBlocking { g.scheduler.runPrompt(fern) }
        assertEquals(0, nm.allNotifications.size)
    }

    @Test
    fun anImportOrADeleteForgetsWhichPromptsWereShown() {
        val fern = plant(g, "Fern", place(g, "Hall"))
        val today = runBlocking { g.garden.today() }
        g.scheduler.markPromptPosted(fern, today)
        g.scheduler.markPromptPosted(fern + 1000, today)
        runBlocking { g.scheduler.reschedule() }
        assertTrue(g.scheduler.promptPosted(fern, today))
        assertFalse("a plant that no longer exists", g.scheduler.promptPosted(fern + 1000, today))
        g.scheduler.forgetPrompts()
        assertFalse(g.scheduler.promptPosted(fern, today))
    }

    @Test
    fun aPromptThatCannotBePostedStillArmsTomorrowsAndNotSooner() {
        allow(granted = false)
        val fern = plant(g, "Fern", place(g, "Hall"))
        val today = runBlocking { g.garden.today() }
        val time = hoursAgo(1)
        runBlocking {
            g.settings.setReminderTime(time)
            g.scheduler.runPrompt(fern)
        }
        assertTrue(nm.allNotifications.isEmpty())
        assertFalse(g.scheduler.promptPosted(fern, today))
        // Tomorrow at the chosen time: neither lost until a refresh nor retried at once.
        assertEquals(today.plus(DatePeriod(days = 1)).atTime(time), promptJobAt(fern)!!.minuteOnly())
    }

    @Test
    fun anImportKeepsTheShownPromptOfAPlantThatIsStillThere() {
        val hall = place(g, "Hall")
        val fern = plant(g, "Fern", hall)
        val ivy = plant(g, "Ivy", hall)
        val today = runBlocking { g.garden.today() }
        g.scheduler.markPromptPosted(fern, today)
        g.scheduler.markPromptPosted(ivy, today)
        runBlocking {
            val before = g.garden.plants.first()
            // The same garden, except that Ivy's id now belongs to another plant.
            g.garden.importJson(g.garden.exportJson("test").replace("\"Ivy\"", "\"Cactus\""))
            g.scheduler.imported(before)
        }
        assertTrue("the same plant was prompted today", g.scheduler.promptPosted(fern, today))
        assertFalse("another plant under a reused id", g.scheduler.promptPosted(ivy, today))
    }

    @Test
    fun twoWorkersPostingOneEventAtOncePostItOnce() {
        allow()
        heatWave(g, "Boris")
        val e = runBlocking { g.garden.alertEvents() }.single()
        val prefs = app.getSharedPreferences("posted_alerts", 0)
        repeat(20) { round ->
            prefs.edit().clear().commit()
            val start = CountDownLatch(1)
            val posted = AtomicInteger()
            val workers = (1..4).map {
                thread {
                    start.await()
                    if (runBlocking { g.scheduler.postEvent(AlertAck.of(e)) }) posted.incrementAndGet()
                }
            }
            start.countDown()
            workers.forEach { it.join(10_000) }
            assertTrue("round $round", workers.none { it.isAlive })
            assertEquals("round $round", 1, posted.get())
        }
    }

    @Test
    fun doneOnANotificationCoversBothPartsOfAnEventARefreshSplit() {
        allow()
        heatWave(g, "Boris")
        val e = runBlocking { g.garden.alertEvents() }.single()
        assertTrue(runBlocking { g.scheduler.postEvent(AlertAck.of(e)) })
        val n = nm.getNotification(Notifier.ALERT_TAG, Notifier.alertId(AlertAck.of(e)))
        // A refresh finds the middle day mild, so the posted heat wave is now two events.
        val db = Garden::class.java.getDeclaredField("db").apply { isAccessible = true }.get(g.garden) as GardenDb
        val middle = e.start.plus(DatePeriod(days = 1))
        val placeId = runBlocking { g.garden.plant(e.plants.single().id) }!!.placeId
        runBlocking {
            db.dao().upsertWeather(listOf(WeatherDayEntity(placeId, middle.toString(), 18.0, 22.0, 0.0, provider = "OPEN_METEO", fetchedAtEpochMs = Clock.System.now().toEpochMilliseconds())))
        }
        assertEquals(2, runBlocking { g.garden.alertEvents() }.size)
        tap(n.actions[0])
        waitUntil("Done is stored for both parts") { runBlocking { g.garden.alertEvents() }.isEmpty() }
    }

    /** Runs [block] in a zone where it is now between 02:00 and 03:00, whatever the clock says. */
    private fun atNight(block: () -> Unit) {
        val saved = java.util.TimeZone.getDefault()
        try {
            val utc = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val offset = ((2 - utc.hour) % 24 + 24) % 24
            val hours = if (offset > 14) offset - 24 else offset
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT" + (if (hours < 0) "-" else "+") + "%02d:00".format(kotlin.math.abs(hours))))
            assertEquals(2, Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour)
            block()
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test
    fun aJobHeldUpIntoTheNightWaitsForTheChosenTime() {
        allow()
        atNight {
            val fern = plant(g, "Fern", place(g, "Hall"))
            val today = runBlocking { g.garden.today() }
            // Yesterday's 20:00 prompt, delayed past midnight: not posted at night, armed for 20:00 today.
            runBlocking {
                g.settings.setReminderTime(LocalTime(20, 0))
                g.scheduler.runPrompt(fern)
            }
            assertTrue(nm.allNotifications.isEmpty())
            assertFalse(g.scheduler.promptPosted(fern, today))
            assertEquals(today.atTime(20, 0), promptJobAt(fern)!!.minuteOnly())
            // A chosen time at night is kept: a job an hour after it posts.
            runBlocking {
                g.settings.setReminderTime(hoursAgo(1))
                g.scheduler.runPrompt(fern)
            }
            assertEquals(1, nm.allNotifications.size)
        }
    }

    /** A plant with a repot reminder due today and no watering prompt. */
    private fun remindedPlant(): Long {
        // The test garden has no care data of its own; the shipped one holds the orchid archetype.
        val shipped: () -> com.kostavukicevic.plants.caredb.CareDb? = { g.careDb }
        Garden::class.java.getDeclaredField("careDb").apply { isAccessible = true }.set(g.garden, shipped)
        // Acquired long ago, and the orchid archetype repots every 24 months.
        val fern = runBlocking {
            g.garden.savePlant(PlantEntity(name = "Orchid", placeId = place(g, "Hall"), archetypeId = "indoor-epiphytic-orchid", canopyWidthCm = 20.0, potVolumeL = 1.0, acquiredOn = "2020-01-01"))
        }
        // Watered today, so the watering prompt does not carry the reminders.
        runBlocking { g.garden.watered(fern, ranOutOfBottom = false) }
        val s = runBlocking { g.garden.status(fern) }!!
        assertTrue(s.reminders.isNotEmpty())
        assertFalse(s.today.isPrompt())
        return fern
    }

    @Test
    fun aReminderJobRunningLateStillBelongsToTheDayItWasArmedFor() {
        allow()
        val fern = remindedPlant()
        val today = runBlocking { g.garden.today() }
        val armedFor = today.minus(DatePeriod(days = 1))
        runBlocking {
            g.settings.setReminderTime(hoursAgo(1))
            g.scheduler.runReminder(fern, armedFor)
        }
        assertEquals(1, nm.allNotifications.size)
        assertTrue(g.scheduler.alertPosted(Scheduler.remindersKey(fern, armedFor)))
        assertFalse(g.scheduler.alertPosted(Scheduler.remindersKey(fern, today)))
    }

    @Test
    fun remindersAreHeldUpAtNightLikeWateringPrompts() {
        allow()
        atNight {
            val fern = remindedPlant()
            val today = runBlocking { g.garden.today() }
            runBlocking {
                g.settings.setReminderTime(LocalTime(20, 0))
                g.scheduler.runReminder(fern, today.minus(DatePeriod(days = 1)))
            }
            assertTrue(nm.allNotifications.isEmpty())
            assertFalse(g.scheduler.alertPosted(Scheduler.remindersKey(fern, today)))
            // A chosen time an hour ago is not late, so the same job posts.
            runBlocking {
                g.settings.setReminderTime(hoursAgo(1))
                g.scheduler.runReminder(fern, today)
            }
            assertEquals(1, nm.allNotifications.size)
        }
    }
}
