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
import com.kostavukicevic.plants.data.AlertAck
import com.kostavukicevic.plants.data.AlertEvent
import com.kostavukicevic.plants.data.alreadyPosted
import com.kostavukicevic.plants.data.decodeAckList
import com.kostavukicevic.plants.data.encodeAckList
import com.kostavukicevic.plants.data.legacyPosted
import com.kostavukicevic.plants.data.PlantEntity
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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

    /** The action carries the posted event's kind, range, severity and plants, matched later as [alreadyPosted] does. */
    private fun eventAction(c: Context, action: String, posted: AlertAck): PendingIntent {
        val record = encodeAckList(listOf(posted))
        return actionIntent(c, 31 * alertId(posted) + action.hashCode(), action, "event/${Uri.encode(record)}") { putExtra(ActionReceiver.EXTRA_EVENT, record) }
    }

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

    /** Alert notifications are tagged, so their ids never meet [promptId] or [STALE_ID]. */
    const val ALERT_TAG = "alert"

    /** One posted event's notification: the same kind and first day replace it. */
    fun alertId(posted: AlertAck) = "${posted.kind}-${posted.start}".hashCode()

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
            .addAction(0, c.getString(R.string.alert_done), eventAction(c, ActionReceiver.ACTION_DONE, AlertAck.of(e)))
            .addAction(0, c.getString(R.string.alert_dismiss), eventAction(c, ActionReceiver.ACTION_DISMISS, AlertAck.of(e)))
            .build()
        return post(c, alertId(AlertAck.of(e)), n, ALERT_TAG)
    }

    /** Takes down the notification posted for [posted]. */
    fun cancelAlert(c: Context, posted: AlertAck) = NotificationManagerCompat.from(c).cancel(ALERT_TAG, alertId(posted))

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
    private fun post(c: Context, id: Int, n: android.app.Notification, tag: String? = null): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            NotificationManagerCompat.from(c).notify(tag, id, n)
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
        val posted = decodeAckList(intent.getStringExtra(EXTRA_EVENT)).singleOrNull()
        // Only the three quick answers; anything else in the extra is ignored.
        val moisture = intent.getStringExtra(EXTRA_MOISTURE)?.let { m -> QUICK_ANSWERS.find { it.name == m } }
        val valid = when (action) {
            ACTION_WATERED, ACTION_SNOOZE -> plantId != null
            ACTION_ANSWER -> plantId != null && moisture != null
            ACTION_DONE, ACTION_DISMISS -> posted != null
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
                } else if (posted != null) {
                    // Matched by overlap, so a tap on a later day still finds the event. One already gone (Done on
                    // Today) or now worse than the notification said has nothing to store: the person did not see it.
                    // A refresh can split the posted range at a milder day; the answer covers every part (D42).
                    for (e in g.garden.alertEvents().filter { alreadyPosted(listOf(posted), it) }) {
                        if (action == ACTION_DONE) g.garden.done(e) else g.garden.dismiss(e)
                    }
                    Notifier.cancelAlert(context, posted)
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
            KIND_PROMPT -> g.scheduler.runPrompt(inputData.getLong(PLANT, -1))
            KIND_REMINDER -> g.scheduler.runReminder(
                inputData.getLong(PLANT, -1), inputData.getString(DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            )
            KIND_ALERT -> decodeAckList(inputData.getString(KEY)).singleOrNull()?.let { g.scheduler.postEvent(it) }
            KIND_STALE -> Notifier.postStale(c)
        }
        return Result.success()
    }

    companion object {
        const val KIND = "kind"; const val PLANT = "plant"; const val KEY = "key"; const val DAY = "day"
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
            s.nextPrompt?.let { (date, _) -> schedulePrompt(s.plant.id, date, today) }
            scheduleReminder(s, today, includeToday = true)
        }
        forgetPrompts(keep = statuses.map { it.plant.id }.toSet())
        // Events marked Done or dismissed today are not visible, so they are not scheduled.
        for (e in graph.garden.alertEvents(statuses)) {
            if (eventPosted(e, today)) continue
            // The evening before, so there is time to move or wrap the pots.
            val at = e.start.minus(DatePeriod(days = 1)).atTime(LocalTime(18, 0)).toInstant(zone)
            val record = encodeAckList(listOf(AlertAck.of(e)))
            enqueue("alert-${e.key}", at - now, workDataOf(NotifyWorker.KIND to NotifyWorker.KIND_ALERT, NotifyWorker.KEY to record))
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
     * The prompt job: posts the day's watering prompt, with the day's reminders, once, then arms the next day's,
     * so a prompt left unanswered comes back each morning without waiting for a refresh. When posting fails
     * (notifications off) tomorrow's is armed all the same, once, so nothing loops and nothing waits for a refresh.
     * A job held up into the night does not post; it is armed again for the chosen time (D42).
     */
    suspend fun runPrompt(plantId: Long) {
        val status = graph.garden.status(plantId) ?: return
        val today = graph.garden.today()
        if (!status.today.isPrompt()) return
        if (!promptPosted(plantId, today)) {
            if (lateAtNight()) {
                val now = Clock.System.now()
                armPrompt(plantId, nextAt(today, graph.settings.reminderTime.first(), TimeZone.currentSystemDefault(), now), now)
                return
            }
            if (!Notifier.postPrompt(context, plantId, status.plant.name, status.today, status.reminders)) {
                schedulePrompt(plantId, today.plus(DatePeriod(days = 1)), today)
                return
            }
            markPromptPosted(plantId, today)
            if (status.reminders.isNotEmpty()) markAlertPosted(remindersKey(plantId, today))
        }
        schedulePrompt(plantId, today, today)
    }

    /**
     * The reminder job: posts the plant's feed or repot reminders once a day, then arms the next. The once-a-day
     * record is keyed by [scheduled], the day the job was armed for, not the day it happens to run: a job that runs
     * late, past midnight, still belongs to the day it was meant for. Held up into the night it does not post (D42).
     */
    suspend fun runReminder(plantId: Long, scheduled: LocalDate?) {
        // Recomputed now: a feed or repot logged since scheduling clears the reminder.
        val status = graph.garden.status(plantId) ?: return
        val today = graph.garden.today()
        val key = remindersKey(plantId, scheduled ?: today)
        if (lateAtNight()) {
            scheduleReminder(status, today, includeToday = true)
            return
        }
        // Reminders only, once a day. With a watering prompt due today, the prompt's own job carries them.
        if (!status.today.isPrompt() && status.reminders.isNotEmpty() && !alertPosted(key) &&
            Notifier.postPrompt(context, plantId, status.plant.name, Action.None, status.reminders)
        ) markAlertPosted(key)
        // Re-armed here, so the next reminder needs no refresh or network.
        scheduleReminder(status, today, includeToday = false)
    }

    /**
     * Arms [plantId]'s prompt for [date] at the reminder time. Once today's is shown, the next is tomorrow's. One
     * shown yesterday and still unanswered keeps its daily slot: if that has passed today (the job ran late, or a
     * reschedule replaced it), it is posted now. Any other prompt whose time has passed waits for tomorrow (D39).
     * The job is named by day so [runPrompt] can arm the next one while it runs.
     */
    suspend fun schedulePrompt(plantId: Long, date: LocalDate, today: LocalDate) {
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        val time = graph.settings.reminderTime.first()
        val shown = prefs.getString(promptKey(plantId), null)
        val at = when {
            date > today -> nextAt(date, time, zone, now)
            shown == today.toString() -> today.plus(DatePeriod(days = 1)).atTime(time).toInstant(zone)
            shown == today.minus(DatePeriod(days = 1)).toString() -> maxOf(today.atTime(time).toInstant(zone), now)
            else -> nextAt(today, time, zone, now)
        }
        armPrompt(plantId, at, now)
    }

    private fun armPrompt(plantId: Long, at: Instant, now: Instant) {
        val day = at.toLocalDateTime(TimeZone.currentSystemDefault()).date
        enqueue("prompt-$plantId-$day", at - now, workDataOf(NotifyWorker.KIND to NotifyWorker.KIND_PROMPT, NotifyWorker.PLANT to plantId))
    }

    /**
     * Whether a prompt or reminder job running now was held up into the night (Doze, a phone switched off): the
     * local time is from [NIGHT_START] to [NIGHT_END] and more than [LATE] has passed since the chosen time last
     * came round. Such a job does not post at night (D42).
     */
    suspend fun lateAtNight(): Boolean {
        val zone = TimeZone.currentSystemDefault()
        val now = Clock.System.now()
        val local = now.toLocalDateTime(zone)
        if (local.time >= NIGHT_END && local.time < NIGHT_START) return false
        val time = graph.settings.reminderTime.first()
        val todays = local.date.atTime(time).toInstant(zone)
        val last = if (todays <= now) todays else local.date.minus(DatePeriod(days = 1)).atTime(time).toInstant(zone)
        return now - last > LATE
    }

    /**
     * Drops the shown-prompt record of every plant not in [keep]: deleted ones, or those an import replaced.
     * A reused id then starts clean instead of looking already prompted today.
     */
    fun forgetPrompts(keep: Set<Long> = emptySet()) {
        val stale = prefs.all.keys.filter { it.startsWith(PROMPT_PREFIX) && it.removePrefix(PROMPT_PREFIX).toLongOrNull() !in keep }
        if (stale.isNotEmpty()) prefs.edit { stale.forEach(::remove) }
    }

    /**
     * After an import, which keeps the file's plant ids: a plant with the same id, name and acquired date as one
     * of [before] is taken as the same plant and keeps its shown-prompt record, so today's prompt is not posted
     * again; any other id starts clean (D42). Then everything is rescheduled.
     */
    suspend fun imported(before: List<PlantEntity>) {
        val same = before.associate { it.id to (it.name to it.acquiredOn) }
        forgetPrompts(keep = graph.garden.plants.first().filter { same[it.id] == (it.name to it.acquiredOn) }.map { it.id }.toSet())
        reschedule()
    }

    /**
     * Reminders are scheduled ahead, like prompts, at the reminder time: today's while they are due and not yet
     * shown (tomorrow if the time has passed), else on the next day one is due. The job is named by day
     * so the worker can queue the next one while it runs.
     */
    suspend fun scheduleReminder(s: PlantStatus, today: LocalDate, includeToday: Boolean) {
        val shownToday = prefs.contains(remindersKey(s.plant.id, today))
        val day = if (includeToday && s.reminders.isNotEmpty() && !shownToday) today else s.nextReminder ?: return
        val at = nextAt(day, graph.settings.reminderTime.first(), TimeZone.currentSystemDefault(), Clock.System.now())
        val armedFor = at.toLocalDateTime(TimeZone.currentSystemDefault()).date
        enqueue(
            "reminder-${s.plant.id}-$armedFor", at - Clock.System.now(),
            workDataOf(NotifyWorker.KIND to NotifyWorker.KIND_REMINDER, NotifyWorker.PLANT to s.plant.id, NotifyWorker.DAY to armedFor.toString()),
        )
    }

    /**
     * Posts the visible alert event that overlaps [scheduled], unless [eventPosted]. Checked again when it comes
     * due: Done or Dismiss since scheduling means nothing is posted. Remembered only once actually shown, so a
     * refused permission does not lose the alert. True if it was shown.
     */
    suspend fun postEvent(scheduled: AlertAck): Boolean {
        val today = graph.garden.today()
        val e = graph.garden.alertEvents().find { scheduled.overlaps(it) } ?: return false
        // Checked, posted and recorded as one step: two workers at once would otherwise both post it.
        synchronized(POSTED_LOCK) {
            if (eventPosted(e, today)) return false
            // A milder notification of the same event is replaced, not left beside the new one.
            val earlier = postedEvents(today).filter { it.overlaps(e) }
            for (a in earlier) Notifier.cancelAlert(context, a)
            if (!Notifier.postAlert(context, e, today)) return false
            savePosted(postedEvents(today) + AlertAck.of(e))
            return true
        }
    }

    /**
     * Whether [e] was already posted, matched by overlap ([alreadyPosted]) so it is posted once over its whole
     * range. A posted event that a refresh extended keeps its record to the new end.
     */
    fun eventPosted(e: AlertEvent, today: LocalDate): Boolean = synchronized(POSTED_LOCK) {
        val posted = postedEvents(today)
        if (!alreadyPosted(posted, e)) return false
        val extended = posted.map { if (it.overlaps(e) && LocalDate.parse(it.end) < e.end) it.copy(end = e.end.toString()) else it }
        if (extended != posted) savePosted(extended)
        return true
    }

    /** Takes down the notifications posted for [e], after Done or Dismiss on Today. */
    fun cancelEvent(e: AlertEvent) {
        val posted = synchronized(POSTED_LOCK) { postedEvents(graph.garden.today()) }
        for (a in posted.filter { it.overlaps(e) }) Notifier.cancelAlert(context, a)
    }

    /**
     * The alert events posted, as acks, without those that have ended. Keys of per-plant alerts posted before
     * events existed become records here once, so the update does not notify about them again.
     */
    private fun postedEvents(today: LocalDate): List<AlertAck> {
        val legacy = prefs.all.keys.mapNotNull { k -> legacyPosted(k)?.let { k to it } }
        val all = decodeAckList(prefs.getString(POSTED_EVENTS, null)) + legacy.map { it.second }
        val kept = all.filter { LocalDate.parse(it.end) >= today }
        if (kept.size != all.size || legacy.isNotEmpty()) prefs.edit {
            for ((k, _) in legacy) remove(k)
            putString(POSTED_EVENTS, encodeAckList(kept))
        }
        return kept
    }

    private fun savePosted(posted: List<AlertAck>) = prefs.edit { putString(POSTED_EVENTS, encodeAckList(posted)) }

    /** Reminders alone are posted once per plant and day. */
    fun alertPosted(key: String): Boolean = prefs.contains(key)

    fun markAlertPosted(key: String) = prefs.edit { putBoolean(key, true) }

    /** A watering prompt is posted once a day; the stored value is the last day it was shown. */
    fun promptPosted(plantId: Long, today: LocalDate): Boolean = prefs.getString(promptKey(plantId), null) == today.toString()

    fun markPromptPosted(plantId: Long, today: LocalDate) = prefs.edit { putString(promptKey(plantId), today.toString()) }

    /**
     * [day] at the reminder [time], or the day after when that has passed: a time moved earlier than now waits
     * for its next occurrence instead of posting at once.
     */
    private fun nextAt(day: LocalDate, time: LocalTime, zone: TimeZone, now: Instant): Instant {
        val at = day.atTime(time).toInstant(zone)
        return if (at >= now) at else day.plus(DatePeriod(days = 1)).atTime(time).toInstant(zone)
    }

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
        fun remindersKey(plantId: Long, date: LocalDate) = "reminders-$plantId-$date"
        private const val PROMPT_PREFIX = "prompt-"
        private fun promptKey(plantId: Long) = "$PROMPT_PREFIX$plantId"
        private const val POSTED_EVENTS = "events"

        /** Guards every read-modify-write of [POSTED_EVENTS]; one per process, as the preferences file is. */
        private val POSTED_LOCK = Any()
        val NIGHT_START = LocalTime(22, 0)
        val NIGHT_END = LocalTime(7, 0)
        val LATE = 3.hours
    }
}
