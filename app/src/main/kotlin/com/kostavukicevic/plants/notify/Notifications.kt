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
import com.kostavukicevic.plants.data.isPrompt
import com.kostavukicevic.plants.engine.Action
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

    private fun actionIntent(c: Context, action: String, plantId: Long) = PendingIntent.getBroadcast(
        c, (plantId * 10 + action.hashCode() % 10).toInt(),
        Intent(c, ActionReceiver::class.java).setAction(action).putExtra(ActionReceiver.EXTRA_PLANT, plantId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun promptId(plantId: Long) = (plantId * 100).toInt()

    fun postPrompt(c: Context, plantId: Long, plantName: String, action: Action) {
        if (!allowed(c)) return
        val title = when (action) {
            is Action.WaterNow -> c.getString(R.string.notif_water_title, plantName)
            is Action.CheckSoil -> c.getString(R.string.notif_check_title, plantName)
            else -> c.getString(R.string.notif_repot_title, plantName)
        }
        val n = NotificationCompat.Builder(c, CHANNEL_PROMPTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(Texts.action(c, action))
            .setContentIntent(openApp(c, plantId))
            .setAutoCancel(true)
            .addAction(0, c.getString(R.string.notif_action_watered), actionIntent(c, ActionReceiver.ACTION_WATERED, plantId))
            .addAction(0, c.getString(R.string.notif_action_snooze), actionIntent(c, ActionReceiver.ACTION_SNOOZE, plantId))
            .build()
        post(c, promptId(plantId), n)
    }

    /** True if the alert was shown. */
    fun postAlert(c: Context, id: Int, plantId: Long, plantName: String, text: String): Boolean {
        if (!allowed(c)) return false
        val n = NotificationCompat.Builder(c, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(c.getString(R.string.notif_alert_title, plantName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(c, plantId))
            .setAutoCancel(true)
            .build()
        return post(c, id, n)
    }

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

/** "Watered" and "Snooze" from a notification. A notification cannot ask whether water ran out; see DECISIONS. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val plantId = intent.getLongExtra(EXTRA_PLANT, -1).takeIf { it > 0 } ?: return
        val pending = goAsync()
        val g = context.graph
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_WATERED -> {
                        // The dose includes extra to run through a drained pot; an undrained pot cannot run out.
                        val drained = g.garden.plant(plantId)?.drainageHoles ?: false
                        g.garden.watered(plantId, ranOutOfBottom = drained)
                    }
                    ACTION_SNOOZE -> g.garden.snooze(plantId)
                }
                NotificationManagerCompat.from(context).cancel(Notifier.promptId(plantId))
                g.scheduler.reschedule()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WATERED = "com.kostavukicevic.plants.WATERED"
        const val ACTION_SNOOZE = "com.kostavukicevic.plants.SNOOZE"
        const val EXTRA_PLANT = "plant"
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
                if (status.today.isPrompt()) Notifier.postPrompt(c, plantId, status.plant.name, status.today)
            }
            KIND_ALERT -> {
                val key = inputData.getString(KEY) ?: return Result.success()
                // Remembered only once actually shown, so a refused permission does not lose the alert.
                if (!g.scheduler.alertPosted(key) &&
                    Notifier.postAlert(c, key.hashCode(), inputData.getLong(PLANT, -1), inputData.getString(NAME) ?: "", inputData.getString(TEXT) ?: "")
                ) g.scheduler.markAlertPosted(key)
            }
            KIND_STALE -> Notifier.postStale(c)
        }
        return Result.success()
    }

    companion object {
        const val KIND = "kind"; const val PLANT = "plant"; const val KEY = "key"; const val NAME = "name"; const val TEXT = "text"
        const val KIND_PROMPT = "prompt"; const val KIND_ALERT = "alert"; const val KIND_STALE = "stale"
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
        for (s in statuses) {
            s.nextPrompt?.let { (date, action) ->
                val at = date.atTime(promptTime(date, action)).toInstant(zone)
                enqueue("prompt-${s.plant.id}", at - now, workDataOf(NotifyWorker.KIND to NotifyWorker.KIND_PROMPT, NotifyWorker.PLANT to s.plant.id))
            }
            for (alert in s.alerts) {
                val key = "${s.plant.id}-${alert.type}-${alert.date}"
                if (prefs.contains(key)) continue
                // The evening before, so there is time to move or wrap the pot.
                val at = alert.date.minus(DatePeriod(days = 1)).atTime(LocalTime(18, 0)).toInstant(zone)
                enqueue(
                    "alert-$key", at - now,
                    workDataOf(
                        NotifyWorker.KIND to NotifyWorker.KIND_ALERT, NotifyWorker.KEY to key, NotifyWorker.PLANT to s.plant.id,
                        NotifyWorker.NAME to s.plant.name, NotifyWorker.TEXT to Texts.alert(context, alert),
                    ),
                )
            }
        }
        // If the forecast gets old, ask the person to open the app (where MET Norway may be used).
        val lastFetch = statuses.map { it.place.id }.distinct().mapNotNull { graph.garden.lastFetchOnce(it) }.maxOrNull()
        if (statuses.isNotEmpty()) {
            val staleAt = (lastFetch?.let { Instant.fromEpochMilliseconds(it) } ?: now) + STALE_AFTER
            enqueue("stale", staleAt - now, workDataOf(NotifyWorker.KIND to NotifyWorker.KIND_STALE))
        }
    }

    /** Alerts are posted once per plant, type and day. */
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
        private val WINTER_MONTHS = setOf(Month.NOVEMBER, Month.DECEMBER, Month.JANUARY, Month.FEBRUARY, Month.MARCH)
    }
}
