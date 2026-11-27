// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
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
 * Flags kept across restarts: a plant was just added and the permission may be asked for, the reminders-off line was
 * dismissed, and the system no longer shows its permission dialog. Adding a plant sets the first and clears the
 * second, so a dismissed line comes back then. Written only from callbacks, never while composing.
 */
internal object Onboarding {
    private const val ASK = "ask_notifications"
    private const val DISMISSED = "reminders_off_dismissed"
    private const val BLOCKED = "notifications_blocked"

    private fun prefs(c: Context) = c.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    /** The ask is kept only where it applies, so a permission granted now and revoked later does not ask for this plant. */
    fun plantAdded(c: Context) {
        val ask = shouldAskForNotifications(Build.VERSION.SDK_INT, Notifier.allowed(c), plantJustAdded = true)
        val edit = prefs(c).edit().putBoolean(DISMISSED, false)
        if (ask) edit.putBoolean(ASK, true)
        edit.apply()
    }

    fun askNow(c: Context) = shouldAskForNotifications(Build.VERSION.SDK_INT, Notifier.allowed(c), prefs(c).getBoolean(ASK, false))

    fun asked(c: Context) = prefs(c).edit().putBoolean(ASK, false).apply()

    fun dismissed(c: Context) = prefs(c).getBoolean(DISMISSED, false)

    fun dismiss(c: Context) = prefs(c).edit().putBoolean(DISMISSED, true).apply()

    fun blocked(c: Context) = prefs(c).getBoolean(BLOCKED, false)

    /** A permission found granted ends the block, so a later revoke asks the system again. */
    fun grantSeen(c: Context) {
        if (blocked(c) && Notifier.allowed(c)) prefs(c).edit().putBoolean(BLOCKED, false).apply()
    }

    fun block(c: Context) = prefs(c).edit().putBoolean(BLOCKED, true).apply()
}

/**
 * The reason for reminders, shown over whichever screen follows the saving of a new plant, then the system's own
 * dialog, which cannot say why. Once the system has been refused twice it no longer shows that dialog, so Allow then
 * opens the app's notification settings instead.
 */
@Composable
internal fun NotificationQuestion() {
    val g = rememberGraph()
    val c = LocalContext.current
    val activity = LocalActivity.current
    val tick = changeTick()
    // The grant may have been given in the system's settings while the app was away.
    LifecycleResumeEffect(Unit) {
        Onboarding.grantSeen(c)
        onPauseOrDispose {}
    }
    var explain by remember(tick) { mutableStateOf(Onboarding.askNow(c)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The system tells that it will not ask again only by wanting no rationale after a refusal.
        if (!granted && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) {
            Onboarding.block(c)
        }
        // Today reads the permission again.
        g.changed()
    }
    if (!explain) return
    fun answer(allow: Boolean) {
        explain = false
        Onboarding.asked(c)
        when {
            allow && !Onboarding.blocked(c) -> request.launch(Manifest.permission.POST_NOTIFICATIONS)
            allow -> { open(c, notificationSettingsIntent(c)); g.changed() }
            else -> g.changed()
        }
    }
    AlertDialog(
        onDismissRequest = { answer(false) },
        text = { Text(stringResource(R.string.notifications_reason), style = Type.body) },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text(stringResource(R.string.notifications_allow), style = Type.label) } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text(stringResource(R.string.notifications_not_now), style = Type.label) } },
    )
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

/** Opens [url] in a browser. A phone without one shows a short message instead of ending the app. */
internal fun openLink(c: Context, url: String) {
    try {
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(c, R.string.link_no_app, Toast.LENGTH_SHORT).show()
    }
}

/** A short line on Today while reminders are off, with a way to turn them on and a way to put the line away. */
@Composable
internal fun RemindersOffLine(onDismiss: () -> Unit) {
    val c = LocalContext.current
    val muted = Overgrown.colors.muted
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.notifications_off), style = Type.secondary, color = Overgrown.colors.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 4)) {
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
        TextButton(onClick = { openLink(c, DONT_KILL_MY_APP_URL) }, Modifier.heightIn(min = Spacing.minTouchTarget)) {
            Text(stringResource(R.string.notifications_help_vendor_link), style = Type.label, color = ink)
        }
    }
}

internal const val DONT_KILL_MY_APP_URL = "https://dontkillmyapp.com/"
