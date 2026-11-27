// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.voicedPlural
import com.kostavukicevic.plants.voicedResource
import com.kostavukicevic.plants.data.AlertEvent
import com.kostavukicevic.plants.data.PlantStatus
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
    // Without the events marked Done or dismissed today, which the notifications also follow. Keyed on the tick too:
    // Done from a notification changes no status, only the stored answers.
    val events by produceState<List<AlertEvent>?>(null, tick, statuses) { value = statuses?.let { g.garden.alertEvents(it) } }
    // Done and Dismiss hide the card at once, until the reload that follows them stores and shows the answer.
    var answered by remember(events) { mutableStateOf(emptySet<String>()) }
    // Keyed on the statuses too: a fetch that ends before they first load must still show.
    // Credits every provider of the places shown: fallback is per place, so one place's fetch says nothing of another's.
    val lastFetch by produceState<Pair<Long, List<String>>?>(null, tick, statuses) {
        val ids = statuses?.filter { it.place.outdoor }?.map { it.place.id }?.distinct()
        value = ids?.mapNotNull { g.garden.lastFetchOnce(it) }?.maxOrNull()?.let { it to g.garden.providers(ids) }
    }
    // Any fetch, also the one on opening the app, not only this screen's Refresh.
    val refreshing by g.fetching.collectAsState()
    val failed by g.fetchFailed.collectAsState()

    // Read again on each return, so a switch turned on in the system's settings takes the line away, and on each
    // change, which NotificationQuestion signals once it is answered.
    var off by remember(tick) { mutableStateOf(remindersOff(c)) }
    LifecycleResumeEffect(Unit) {
        off = remindersOff(c)
        onPauseOrDispose {}
    }
    // The line waits while NotificationQuestion asks.
    val asking = remember(tick) { Onboarding.askNow(c) }
    var dismissed by remember(tick) { mutableStateOf(Onboarding.dismissed(c)) }

    // Recording reloads this screen in place, as on the plant screen; in the app's scope, so leaving does not cancel it.
    fun act(block: suspend () -> Unit) = g.scope.launch { block(); g.scheduler.reschedule(); g.changed() }

    Page(
        title = stringResource(R.string.today_title),
        large = true,
        actions = {
            TextButton(onClick = onAdd) { Text(stringResource(R.string.add_plant)) }
            TopMenu(onPlaces, onSettings)
        },
    ) {
        val list = statuses ?: return@Page
        val visible = (events ?: return@Page).filterNot { it.key in answered }
        if (list.isNotEmpty() && off.isNotEmpty() && !dismissed && !asking) RemindersOffLine(off, onDismiss = { dismissed = true; Onboarding.dismiss(c) })
        TodayContent(
            list,
            g.garden.today(),
            onPlant = onPlant,
            onAdd = onAdd,
            onWatered = { s -> act { waterFromToday(g.garden, s.plant) } },
            onAnswer = { s, m -> act { g.garden.checkIn(s.plant.id, m.name) } },
            events = visible,
            onDone = { e -> answered += e.key; act { g.garden.done(e); g.scheduler.cancelEvent(e) } },
            onDismiss = { e -> answered += e.key; act { g.garden.dismiss(e); g.scheduler.cancelEvent(e) } },
        ) {
            // Indoor places use no weather, so a garden of only indoor plants shows none of this.
            if (list.any { it.place.outdoor }) WeatherStatus(lastFetch, Clock.System.now(), refreshing, failed) {
                g.scope.launch {
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
}

/**
 * Forecast age, refresh and the provider's credit, kept quiet below the cards. The credit stays next to the
 * weather it describes (Open-Meteo licence). [fetched] is the last fetch in epoch ms and the providers of the days shown.
 */
@Composable
internal fun WeatherStatus(fetched: Pair<Long, List<String>>?, now: Instant, refreshing: Boolean, failed: Boolean, onRefresh: () -> Unit) {
    val muted = Overgrown.colors.muted
    Column(Modifier.fillMaxWidth().padding(top = Spacing.grid * 2)) {
        when {
            refreshing -> Text(stringResource(R.string.forecast_loading), style = Type.secondary, color = muted)
            failed -> Text(voicedResource(R.string.forecast_failed), style = Type.secondary, color = Overgrown.colors.errorText)
            fetched == null -> Text(stringResource(R.string.forecast_never), style = Type.secondary, color = muted)
        }
        if (fetched != null) {
            val at = Instant.fromEpochMilliseconds(fetched.first)
            val age = now - at
            if (!refreshing) Text(stringResource(R.string.forecast_fetched, Texts.dateTime(at)), style = Type.secondary, color = muted)
            if (age > 3.days) {
                val days = age.inWholeDays.toInt()
                Text(voicedPlural(R.plurals.forecast_stale, days, days), style = Type.secondary, color = Overgrown.colors.errorText)
            }
            fetched.second.forEach { WeatherCredit(it) }
        }
        TextButton(enabled = !refreshing, onClick = onRefresh, modifier = Modifier.heightIn(min = Spacing.minTouchTarget), contentPadding = PaddingValues(0.dp)) {
            // Material's disabled alpha, so a Refresh that cannot be tapped looks it.
            Text(stringResource(R.string.refresh), style = Type.secondary, color = if (refreshing) muted.copy(alpha = 0.38f) else muted)
        }
    }
}

/**
 * The weather provider's credit as a link, under anything drawn from its data (Open-Meteo licence, CC BY 4.0).
 * [provider] is a provider whose data the screen shows. A button to TalkBack, as the links in Settings are.
 */
@Composable
internal fun WeatherCredit(provider: String) {
    val c = LocalContext.current
    val met = provider == "MET_NORWAY"
    Text(
        stringResource(if (met) R.string.weather_credit_met else R.string.weather_credit_open_meteo),
        // Clickable before wrapContentHeight, so the whole 48 dp row is the link, not only the line of text.
        modifier = Modifier.heightIn(min = Spacing.minTouchTarget)
            .clickable(role = Role.Button) { openLink(c, if (met) MetNorway.CREDIT_URL else OpenMeteo.CREDIT_URL) }
            .wrapContentHeight(),
        style = Type.secondary,
        color = Overgrown.colors.muted,
        textDecoration = TextDecoration.Underline,
    )
}

/**
 * The providers a screen credits, set once its lookup has finished and empty for none, so a test can tell "no credit"
 * from "not looked up yet". Not read by accessibility services.
 */
internal val WeatherProviders = SemanticsPropertyKey<List<String>>("WeatherProviders")
internal var SemanticsPropertyReceiver.weatherProviders by WeatherProviders
