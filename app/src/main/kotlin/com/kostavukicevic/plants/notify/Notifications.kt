// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.MainActivity
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.data.AlertEvent
import com.kostavukicevic.plants.data.PlantStatus
import com.kostavukicevic.plants.data.Reminder
import com.kostavukicevic.plants.data.isPrompt
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.graph
import com.kostavukicevic.plants.weather.RefreshSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaDuration

object Notifier {
    const val CHANNEL_PROMPTS = "prompts"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_STATUS = "status"

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PROMPTS, c.getString(R.string.channel_prompts), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_ALERTS, c.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_STATUS, c.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun allowed(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(c: Context, plantId: Long?) = PendingIntent.getActivity(
        c, plantId?.toInt() ?: 0,
        Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_PLANT, plantId ?: -1L).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The data URI names the plant or event and the answer, so no two actions share a PendingIntent even where
     * request codes collide: Android tells PendingIntents apart by action and data, not by extras.
     */
    private fun actionIntent(c: Context, requestCode: Int, action: String, data: String, extras: Intent.() -> Unit) = PendingIntent.getBroadcast(
        c, requestCode,
        Intent(c, ActionReceiver::class.java).setAction(action).setData(Uri.parse("overgrown://$data")).apply(extras),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun plantAction(c: Context, action: String, plantId: Long, index: Int, moisture: Moisture? = null) =
        actionIntent(c, (plantId * 8 + index).toInt(), action, "plant/$plantId/${moisture ?: "action"}") {
            putExtra(ActionReceiver.EXTRA_PLANT, plantId)
            if (moisture != null) putExtra(ActionReceiver.EXTRA_MOISTURE, moisture.name)
        }

    private fun eventAction(c: Context, action: String, key: String) =
        actionIntent(c, 31 * eventId(key) + action.hashCode(), action, "event/$key") { putExtra(ActionReceiver.EXTRA_EVENT, key) }

    /** The soil answers a notification offers, as on Today. "Dry" covers Dry and Bone dry (D31.4). */
    private val answers = listOf(R.string.answer_wet to Moisture.TOO_WET, R.string.answer_ok to Moisture.OK, R.string.answer_dry to Moisture.DRY_OR_BONE_DRY)

    fun promptId(plantId: Long) = (plantId * 100).toInt()

    /**
     * One notification per plant: a watering prompt, the feeding and repotting reminders due today,
     * or both together. True if it was shown.
     */
    fun postPrompt(c: Context, plantId: Long, plantName: String, action: Action, reminders: List<Reminder>): Boolean {
        if (!allowed(c)) return false
        val prompt = action.isPrompt()
        if (!prompt && reminders.isEmpty()) return false
        val title = when (action) {
            is Action.WaterNow -> c.getString(R.string.notif_water_title, plantName)
            is Action.CheckSoil -> c.getString(R.string.notif_check_title, plantName)
            Action.RepotOrWaterDaily -> c.getString(R.string.notif_repot_title, plantName)
            else -> c.getString(R.string.notif_reminder_title, plantName)
        }
        val text = (listOfNotNull(if (prompt) Texts.action(c, action) else null) + reminders.map { Texts.reminder(c, it) }).joinToString(" ")
        val b = NotificationCompat.Builder(c, CHANNEL_PROMPTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(c, plantId))
            .setAutoCancel(true)
        // A soil check is answered with Wet, OK or Dry: Android shows at most three actions, so it has no Snooze.
        // Watered and Snooze answer a watering prompt; a reminder alone opens the plant.
        if (action is Action.CheckSoil) {
            for ((i, answer) in answers.withIndex()) {
                b.addAction(0, c.getString(answer.first), plantAction(c, ActionReceiver.ACTION_ANSWER, plantId, 2 + i, answer.second))
            }
        } else if (prompt) {
            b.addAction(0, c.getString(R.string.notif_action_watered), plantAction(c, ActionReceiver.ACTION_WATERED, plantId, 0))
                .addAction(0, c.getString(R.string.notif_action_snooze), plantAction(c, ActionReceiver.ACTION_SNOOZE, plantId, 1))
        }
        return post(c, promptId(plantId), b.build())
    }

    fun eventId(key: String) = key.hashCode()

    /** One notification per weather event, naming all its plants: "Frost Mon-Wed: Boris". True if it was shown. */
    fun postAlert(c: Context, e: AlertEvent, today: LocalDate): Boolean {
        if (!allowed(c)) return false
        val text = Texts.eventAdvice(c, e)
        val n = NotificationCompat.Builder(c, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Texts.eventTitle(c, e, today))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(c, null))
            .setAutoCancel(true)
            .addAction(0, c.getString(R.string.alert_done), eventAction(c, ActionReceiver.ACTION_DONE, e.key))
            .addAction(0, c.getString(R.string.alert_dismiss), eventAction(c, ActionReceiver.ACTION_DISMISS, e.key))
            .build()
        return post(c, eventId(e.key), n)
    }

    /** Takes down the event's notification, after Done or Dismiss on Today. */
    fun cancelAlert(c: Context, e: AlertEvent) = NotificationManagerCompat.from(c).cancel(eventId(e.key))

    fun postStale(c: Context) {
        if (!allowed(c)) return
        val n = NotificationCompat.Builder(c, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(c.getString(R.string.notif_stale_title))
            .setContentText(c.getString(R.string.notif_stale_text))
            .setContentIntent(openApp(c, null))
            .setAutoCancel(true)
            .build()
        post(c, STALE_ID, n)
    }

    const val STALE_ID = 1

    /** The person can revoke the permission at any moment; a missed reminder is better than a crash. */
    private fun post(c: Context, id: Int, n: android.app.Notification): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            NotificationManagerCompat.from(c).notify(id, n)
            true
        } catch (e: SecurityException) {
            android.util.Log.w("Overgrown", "notification permission revoked", e)
            false
        }
    }
}

/**
 * The actions on a notification: Watered and Snooze, the soil answers, and Done and Dismiss on an alert event.
 * A notification cannot ask whether water ran out; see DECISIONS.
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val plantId = intent.getLongExtra(EXTRA_PLANT, -1).takeIf { it > 0 }
        val eventKey = intent.getStringExtra(EXTRA_EVENT)
        // Only the three quick answers; anything else in the extra is ignored.
        val moisture = intent.getStringExtra(EXTRA_MOISTURE)?.let { m -> QUICK_ANSWERS.find { it.name == m } }
        val valid = when (action) {
            ACTION_WATERED, ACTION_SNOOZE -> plantId != null
            ACTION_ANSWER -> plantId != null && moisture != null
            ACTION_DONE, ACTION_DISMISS -> eventKey != null
            else -> false
        }
        if (!valid) return
        val pending = goAsync()
        val g = context.graph
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (plantId != null) {
                    // A plant deleted since the notification was posted takes no answer.
                    val plant = g.garden.plant(plantId)
                    if (plant != null) when (action) {
                        // The dose includes extra to run through a drained pot; an undrained pot cannot run out.
                        ACTION_WATERED -> g.garden.watered(plantId, ranOutOfBottom = plant.drainageHoles)
                        ACTION_SNOOZE -> g.garden.snooze(plantId)
                        ACTION_ANSWER -> if (moisture != null) g.garden.checkIn(plantId, moisture.name)
                    }
                    NotificationManagerCompat.from(context).cancel(Notifier.promptId(plantId))
                } else if (eventKey != null) {
                    // An event already gone (Done on Today, or changed by a refresh) has nothing to store.
                    val e = g.garden.alertEvents().find { it.key == eventKey }
                    if (e != null) if (action == ACTION_DONE) g.garden.done(e) else g.garden.dismiss(e)
                    NotificationManagerCompat.from(context).cancel(Notifier.eventId(eventKey))
                }
                g.scheduler.reschedule()
                g.changed()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WATERED = "com.kostavukicevic.plants.WATERED"
        const val ACTION_SNOOZE = "com.kostavukicevic.plants.SNOOZE"
        const val ACTION_ANSWER = "com.kostavukicevic.plants.ANSWER"
        const val ACTION_DONE = "com.kostavukicevic.plants.DONE"
        const val ACTION_DISMISS = "com.kostavukicevic.plants.DISMISS"
        const val EXTRA_PLANT = "plant"
        const val EXTRA_MOISTURE = "moisture"
        const val EXTRA_EVENT = "event"
        private val QUICK_ANSWERS = listOf(Moisture.TOO_WET, Moisture.OK, Moisture.DRY_OR_BONE_DRY)
    }
}

/** Posts one notification when it comes due, after checking it is still due. */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val g = c.graph
        when (inputData.getString(KIND)) {
            KIND_PROMPT -> {
                val plantId = inputData.getLong(PLANT, -1)
                val status = g.garden.status(plantId) ?: return Result.success()
                val key = Scheduler.remindersKey(plantId, g.garden.today())
                // A watering prompt carries the day's reminders, so the plant gets one notification.
                if (status.today.isPrompt() &&
                    Notifier.postPrompt(c, plantId, status.plant.name, status.today, status.reminders) && status.reminders.isNotEmpty()
                ) g.scheduler.markAlertPosted(key)
            }
            KIND_REMINDER -> {
                val plantId = inputData.getLong(PLANT, -1)
                // Recomputed now: a feed or repot logged since scheduling clears the reminder.
                val status = g.garden.status(plantId) ?: return Result.success()
                val key = Scheduler.remindersKey(plantId, g.garden.today())
                // Reminders only, once a day. With a watering prompt due today, the prompt's own job carries them.
                if (!status.today.isPrompt() && status.reminders.isNotEmpty() && !g.scheduler.alertPosted(key) &&
                    Notifier.postPrompt(c, plantId, status.plant.name, Action.None, status.reminders)
                ) g.scheduler.markAlertPosted(key)
                // Re-armed here, so the next reminder needs no refresh or network.
                g.scheduler.scheduleReminder(status, g.garden.today(), includeToday = false)
            }
            KIND_ALERT -> g.scheduler.postEvent(inputData.getString(KEY) ?: return Result.success())
            KIND_STALE -> Notifier.postStale(c)
        }
        return Result.success()
    }

    companion object {
        const val KIND = "kind"; const val PLANT = "plant"; const val KEY = "key"
        const val KIND_PROMPT = "prompt"; const val KIND_REMINDER = "reminder"; const val KIND_ALERT = "alert"; const val KIND_STALE = "stale"
    }
}

/**
 * The single daily background job: one Open-Meteo refresh (never MET Norway) at this install's own
 * time of day, then local recomputation and rescheduling.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        g.garden.refreshWeather(foreground = false)
        g.scheduler.reschedule()
        return Result.success()
    }
}

/** Plans notifications ahead from the cached forecast. No exact alarms: WorkManager delivers within minutes. */
class Scheduler(private val context: Context, private val graph: Graph) {
    private val work get() = WorkManager.getInstance(context)
    private val prefs get() = context.getSharedPreferences("posted_alerts", Context.MODE_PRIVATE)

    suspend fun ensureDailyRefresh() {
        val seed = graph.settings.installSeed { Random.nextLong() }
        val delay = RefreshSchedule.delayUntilNext(Clock.System.now(), TimeZone.currentSystemDefault(), RefreshSchedule.minuteOfDay(seed))
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay.toJavaDuration())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork("daily-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Recomputes every plant and replaces the pending notifications. */
    suspend fun reschedule() {
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        work.cancelAllWorkByTag(TAG)
        val statuses = graph.garden.statuses()
        val today = graph.garden.today()
        for (s in statuses) {
            s.nextPrompt?.let { (date, action) ->
                val at = date.atTime(promptTime(date, action)).toInstant(zone)
                enqueue("prompt-${s.plant.id}", at - now, workDataOf(NotifyWorker.KIND to NotifyWorker.KIND_PROMPT, NotifyWorker.PLANT to s.plant.id))
            }
            scheduleReminder(s, today, includeToday = true)
        }
        // Events marked Done or dismissed today are not visible, so they are not scheduled.
        for (e in graph.garden.alertEvents(statuses)) {
            if (prefs.contains(e.key)) continue
            // The evening before, so there is time to move or wrap the pots.
            val at = e.start.minus(DatePeriod(days = 1)).atTime(LocalTime(18, 0)).toInstant(zone)
            enqueue("alert-${e.key}", at - now, workDataOf(NotifyWorker.KIND to NotifyWorker.KIND_ALERT, NotifyWorker.KEY to e.key))
        }
        // If the forecast gets old, ask the person to open the app (where MET Norway may be used).
        val outdoor = statuses.filter { it.place.outdoor }
        val lastFetch = outdoor.map { it.place.id }.distinct().mapNotNull { graph.garden.lastFetchOnce(it) }.maxOrNull()
        if (outdoor.isNotEmpty()) {
            val staleAt = (lastFetch?.let { Instant.fromEpochMilliseconds(it) } ?: now) + STALE_AFTER
            enqueue("stale", staleAt - now, workDataOf(NotifyWorker.KIND to NotifyWorker.KIND_STALE))
        }
    }

    /**
     * Reminders are scheduled ahead, like prompts, at 09:00: today's while they are due and not yet
     * shown (at once if 09:00 has passed), else on the next day one is due. The job is named by day
     * so the worker can queue the next one while it runs.
     */
    fun scheduleReminder(s: PlantStatus, today: LocalDate, includeToday: Boolean) {
        val shownToday = prefs.contains(remindersKey(s.plant.id, today))
        val day = if (includeToday && s.reminders.isNotEmpty() && !shownToday) today else s.nextReminder ?: return
        val at = day.atTime(REMINDER_TIME).toInstant(TimeZone.currentSystemDefault())
        enqueue("reminder-${s.plant.id}-$day", at - Clock.System.now(), workDataOf(NotifyWorker.KIND to NotifyWorker.KIND_REMINDER, NotifyWorker.PLANT to s.plant.id))
    }

    /**
     * Posts the alert event named [key] if it is still visible and not yet posted. Checked again when it comes due:
     * Done or Dismiss since scheduling, or a forecast that changed it, means nothing is posted. Remembered only once
     * actually shown, so a refused permission does not lose the alert. True if it was shown.
     */
    suspend fun postEvent(key: String): Boolean {
        if (alertPosted(key)) return false
        val e = graph.garden.alertEvents().find { it.key == key } ?: return false
        if (!Notifier.postAlert(context, e, graph.garden.today())) return false
        markAlertPosted(key)
        return true
    }

    /**
     * Alert events are posted once per [AlertEvent.key], so one that gets more severe or reaches another plant
     * posts again; reminders alone once per plant and day.
     */
    fun alertPosted(key: String): Boolean = prefs.contains(key)

    fun markAlertPosted(key: String) = prefs.edit { putBoolean(key, true) }

    /** Winter soil checks around midday (the winter watering window); other prompts in the morning. */
    private fun promptTime(date: LocalDate, action: Action): LocalTime =
        if (action is Action.CheckSoil && date.month in WINTER_MONTHS) LocalTime(12, 0) else LocalTime(9, 0)

    private fun enqueue(name: String, delay: Duration, data: androidx.work.Data) {
        val request = OneTimeWorkRequestBuilder<NotifyWorker>()
            .setInitialDelay(delay.coerceAtLeast(1.minutes).toJavaDuration())
            .setInputData(data)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        private const val TAG = "notify"
        val STALE_AFTER = 3.days
        private val REMINDER_TIME = LocalTime(9, 0)
        fun remindersKey(plantId: Long, date: LocalDate) = "reminders-$plantId-$date"
        private val WINTER_MONTHS = setOf(Month.NOVEMBER, Month.DECEMBER, Month.JANUARY, Month.FEBRUARY, Month.MARCH)
    }
}
