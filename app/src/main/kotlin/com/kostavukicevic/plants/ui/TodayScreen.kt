// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.data.AlertEvent
import com.kostavukicevic.plants.data.PlantStatus
import com.kostavukicevic.plants.notify.Notifier
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import com.kostavukicevic.plants.ui.theme.Type
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.OpenMeteo
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@Composable
fun TodayScreen(onPlant: (Long) -> Unit, onAdd: () -> Unit, onPlaces: () -> Unit, onSettings: () -> Unit) {
    val g = rememberGraph()
    val c = LocalContext.current
    val tick = changeTick()
    val statuses by produceState<List<PlantStatus>?>(null, tick) { value = g.garden.statuses() }
    // Without the events marked Done, or Not now today.
    val events by produceState(emptyList<AlertEvent>(), statuses) { value = statuses?.let { g.garden.alertEvents(it) }.orEmpty() }
    // Keyed on the statuses too: a fetch that ends before they first load must still show.
    val lastFetch by produceState<Pair<Long, String?>?>(null, tick, statuses) {
        value = statuses?.filter { it.place.outdoor }?.map { it.place.id }?.distinct()?.mapNotNull { g.garden.lastFetchOnce(it) }?.maxOrNull()?.let { it to g.garden.lastProvider() }
    }
    // Any fetch, also the one on opening the app, not only this screen's Refresh.
    val refreshing by g.fetching.collectAsState()
    val failed by g.fetchFailed.collectAsState()
    val scope = rememberCoroutineScope()

    var notifAllowed by remember { mutableStateOf(Notifier.allowed(c)) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifAllowed = it }

    // Recording reloads this screen in place, as on the plant screen.
    fun act(block: suspend () -> Unit) = scope.launch { block(); g.scheduler.reschedule(); g.changed() }

    Page(
        title = stringResource(R.string.today_title),
        large = true,
        actions = {
            TextButton(onClick = onAdd) { Text(stringResource(R.string.add_plant)) }
            TopMenu(onPlaces, onSettings)
        },
    ) {
        val list = statuses ?: return@Page
        if (list.isNotEmpty() && !notifAllowed && Build.VERSION.SDK_INT >= 33) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.cardPadding)) {
                    Text(stringResource(R.string.notifications_reason))
                    Button(onClick = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.notifications_allow)) }
                }
            }
        }
        TodayContent(
            list,
            g.garden.today(),
            onPlant = onPlant,
            onAdd = onAdd,
            onWatered = { s -> act { waterFromToday(g.garden, s.plant) } },
            onAnswer = { s, m -> act { g.garden.checkIn(s.plant.id, m.name) } },
            events = events,
            onDone = { e -> act { g.garden.done(e) } },
            onDismiss = { e -> act { g.garden.dismiss(e) } },
        ) {
            // Indoor places use no weather, so a garden of only indoor plants shows none of this.
            if (list.any { it.place.outdoor }) WeatherStatus(lastFetch, Clock.System.now(), refreshing, failed) {
                scope.launch {
                    // A thrown fetch is already marked failed; only keep it from ending the app.
                    try {
                        g.refreshWeather(foreground = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("Overgrown", "weather refresh failed", e)
                    }
                    g.scheduler.reschedule()
                    g.changed()
                }
            }
        }
    }
    LaunchedEffect(Unit) { notifAllowed = Notifier.allowed(c) }
}

/**
 * Forecast age, refresh and the provider's credit, kept quiet below the cards. The credit stays next to the
 * weather it describes (Open-Meteo licence). [fetched] is the last fetch in epoch ms and its provider.
 */
@Composable
internal fun WeatherStatus(fetched: Pair<Long, String?>?, now: Instant, refreshing: Boolean, failed: Boolean, onRefresh: () -> Unit) {
    val uri = LocalUriHandler.current
    val muted = Overgrown.colors.muted
    Column(Modifier.fillMaxWidth().padding(top = Spacing.grid * 2)) {
        when {
            refreshing -> Text(stringResource(R.string.forecast_loading), style = Type.secondary, color = muted)
            failed -> Text(stringResource(R.string.forecast_failed), style = Type.secondary, color = Overgrown.colors.errorText)
            fetched == null -> Text(stringResource(R.string.forecast_never), style = Type.secondary, color = muted)
        }
        if (fetched != null) {
            val at = Instant.fromEpochMilliseconds(fetched.first)
            val age = now - at
            if (!refreshing) Text(stringResource(R.string.forecast_fetched, Texts.dateTime(at)), style = Type.secondary, color = muted)
            if (age > 3.days) {
                val days = age.inWholeDays.toInt()
                Text(pluralStringResource(R.plurals.forecast_stale, days, days), style = Type.secondary, color = Overgrown.colors.errorText)
            }
            val met = fetched.second == "MET_NORWAY"
            Text(
                stringResource(if (met) R.string.weather_credit_met else R.string.weather_credit_open_meteo),
                modifier = Modifier.heightIn(min = Spacing.minTouchTarget).wrapContentHeight()
                    .clickable { uri.openUri(if (met) MetNorway.CREDIT_URL else OpenMeteo.CREDIT_URL) },
                style = Type.secondary,
                color = muted,
                textDecoration = TextDecoration.Underline,
            )
        }
        TextButton(enabled = !refreshing, onClick = onRefresh, modifier = Modifier.heightIn(min = Spacing.minTouchTarget), contentPadding = PaddingValues(0.dp)) {
            // Material's disabled alpha, so a Refresh that cannot be tapped looks it.
            Text(stringResource(R.string.refresh), style = Type.secondary, color = if (refreshing) muted.copy(alpha = 0.38f) else muted)
        }
    }
}
