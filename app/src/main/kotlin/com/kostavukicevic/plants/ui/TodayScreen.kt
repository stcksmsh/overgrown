// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.data.PlantStatus
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.notify.Notifier
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.OpenMeteo
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@Composable
fun TodayScreen(onPlant: (Long) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    val g = rememberGraph()
    val c = LocalContext.current
    val tick = changeTick()
    val statuses by produceState<List<PlantStatus>?>(null, tick) { value = g.garden.statuses() }
    val places by g.garden.places.collectAsState(null)
    val lastFetch by produceState<Pair<Long, String?>?>(null, tick) {
        value = statuses?.filter { it.place.outdoor }?.map { it.place.id }?.distinct()?.mapNotNull { g.garden.lastFetchOnce(it) }?.maxOrNull()?.let { it to g.garden.lastProvider() }
    }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    var notifAllowed by remember { mutableStateOf(Notifier.allowed(c)) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifAllowed = it }

    Page(
        title = stringResource(R.string.today_title),
        actions = {
            TextButton(onClick = onAdd) { Text(stringResource(R.string.add_plant)) }
            TextButton(onClick = onSettings) { Text(stringResource(R.string.settings)) }
        },
    ) {
        if (!notifAllowed && Build.VERSION.SDK_INT >= 33) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.notifications_reason))
                    Button(onClick = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.notifications_allow)) }
                }
            }
        }

        // Forecast age and the provider's credit, next to the weather it describes (Open-Meteo licence).
        // Indoor places use no weather, so a garden of only indoor places shows none of this.
        val indoorOnly = places?.let { it.isNotEmpty() && it.none { p -> p.outdoor } } == true
        val fetched = lastFetch
        if (!indoorOnly && fetched == null) {
            Text(stringResource(R.string.forecast_never), style = MaterialTheme.typography.bodySmall)
        } else if (!indoorOnly && fetched != null) {
            val at = Instant.fromEpochMilliseconds(fetched.first)
            val age = Clock.System.now() - at
            Text(stringResource(R.string.forecast_fetched, Texts.dateTime(at)), style = MaterialTheme.typography.bodySmall)
            if (age > 3.days) Text(pluralStringResource(R.plurals.forecast_stale, age.inWholeDays.toInt(), age.inWholeDays.toInt()), color = MaterialTheme.colorScheme.error)
            val met = fetched.second == "MET_NORWAY"
            Text(
                stringResource(if (met) R.string.weather_credit_met else R.string.weather_credit_open_meteo),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { uri.openUri(if (met) MetNorway.CREDIT_URL else OpenMeteo.CREDIT_URL) },
            )
        }
        if (!indoorOnly) TextButton(
            enabled = !refreshing,
            onClick = {
                refreshing = true
                scope.launch {
                    g.garden.refreshWeather(foreground = true)
                    g.scheduler.reschedule()
                    g.changed()
                    refreshing = false
                }
            },
        ) { Text(stringResource(if (refreshing) R.string.refreshing else R.string.refresh)) }

        val list = statuses ?: return@Page
        if (list.isEmpty()) {
            Text(stringResource(R.string.today_empty))
            Button(onClick = onAdd) { Text(stringResource(R.string.add_plant)) }
            return@Page
        }
        if (list.none { it.today != Action.None || it.alerts.isNotEmpty() }) Text(stringResource(R.string.today_all_fine))
        for (s in list.sortedByDescending { it.today != Action.None }) {
            Card(Modifier.fillMaxWidth().clickable { onPlant(s.plant.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.plant.name, style = MaterialTheme.typography.titleMedium)
                    Text(Texts.action(c, s.today))
                    Texts.reason(c, s)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    for (a in s.alerts) Text(Texts.alert(c, a), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    LaunchedEffect(Unit) { notifAllowed = Notifier.allowed(c) }
}
