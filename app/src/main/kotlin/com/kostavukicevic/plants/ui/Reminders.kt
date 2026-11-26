// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.notify.Notifier
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.Radius
import com.kostavukicevic.plants.ui.theme.Spacing
import com.kostavukicevic.plants.ui.theme.Type

/**
 * Whether to ask for the notification permission now. Only right after a new plant is saved, the moment reminders
 * start to matter, never at launch; and only on Android 13 and later, where the permission is asked at run time.
 */
internal fun shouldAskForNotifications(sdk: Int, granted: Boolean, plantJustAdded: Boolean) = plantJustAdded && sdk >= 33 && !granted

/** Reminders cannot reach the person: the permission is missing, or notifications are off in the system's settings. */
internal fun remindersOff(c: Context) = !Notifier.allowed(c) || !NotificationManagerCompat.from(c).areNotificationsEnabled()

/**
 * Two flags kept across restarts: a plant was just added and the permission may be asked for, and the reminders-off
 * line was dismissed. Adding a plant sets the first and clears the second, so a dismissed line comes back then.
 */
internal object Onboarding {
    private const val ASK = "ask_notifications"
    private const val DISMISSED = "reminders_off_dismissed"

    private fun prefs(c: Context) = c.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun plantAdded(c: Context) = prefs(c).edit().putBoolean(ASK, true).putBoolean(DISMISSED, false).apply()

    /** Whether to ask now. A pending ask that does not apply, the permission being granted, is dropped. */
    fun askNow(c: Context): Boolean {
        val ask = shouldAskForNotifications(Build.VERSION.SDK_INT, Notifier.allowed(c), prefs(c).getBoolean(ASK, false))
        if (!ask) asked(c)
        return ask
    }

    fun asked(c: Context) = prefs(c).edit().putBoolean(ASK, false).apply()

    fun dismissed(c: Context) = prefs(c).getBoolean(DISMISSED, false)

    fun dismiss(c: Context) = prefs(c).edit().putBoolean(DISMISSED, true).apply()
}

/** The app's own notification settings, where reminders can be turned back on. */
internal fun notificationSettingsIntent(c: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)

/** The system's battery-optimisation list. Only opens it: asking to be exempted directly is a Play policy risk. */
internal fun batterySettingsIntent() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

private fun appDetailsIntent(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.packageName, null))

/** Some vendors' builds lack a settings page; the app's details page is on every phone and leads to the same switches. */
private fun open(c: Context, intent: Intent) {
    try {
        c.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        c.startActivity(appDetailsIntent(c))
    }
}

/** A short line on Today while reminders are off, with a way to turn them on and a way to put the line away. */
@Composable
internal fun RemindersOffLine(onDismiss: () -> Unit) {
    val c = LocalContext.current
    val muted = Overgrown.colors.muted
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.notifications_off), style = Type.secondary, color = Overgrown.colors.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 4)) {
            TextButton(onClick = { open(c, notificationSettingsIntent(c)) }, Modifier.heightIn(min = Spacing.minTouchTarget), contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.notifications_open_settings), style = Type.label, color = Overgrown.colors.ink)
            }
            TextButton(onClick = onDismiss, Modifier.heightIn(min = Spacing.minTouchTarget), contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.notifications_off_hide), style = Type.label, color = muted)
            }
        }
    }
}

/** "Reminders not arriving?": the battery setting to check, a button to it, and dontkillmyapp.com for each vendor. */
@Composable
fun RemindersHelpScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    val uri = LocalUriHandler.current
    val ink = Overgrown.colors.ink
    Page(stringResource(R.string.notifications_help), onBack = onBack) {
        Text(stringResource(R.string.notifications_help_text), style = Type.body, color = ink)
        Text(stringResource(R.string.notifications_help_setting), style = Type.body, color = ink)
        Button(
            onClick = { open(c, batterySettingsIntent()) },
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.buttonHeight),
            shape = RoundedCornerShape(Radius.button),
            colors = ButtonDefaults.buttonColors(containerColor = ink, contentColor = Overgrown.colors.onInk),
        ) { Text(stringResource(R.string.notifications_help_battery), style = Type.label) }
        Text(stringResource(R.string.notifications_help_vendor), style = Type.body, color = ink)
        TextButton(onClick = { uri.openUri(DONT_KILL_MY_APP_URL) }, Modifier.heightIn(min = Spacing.minTouchTarget)) {
            Text(stringResource(R.string.notifications_help_vendor_link), style = Type.label, color = ink)
        }
    }
}

internal const val DONT_KILL_MY_APP_URL = "https://dontkillmyapp.com/"
