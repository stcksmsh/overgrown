// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.caredb.Context as CareContext
import com.kostavukicevic.plants.data.CareSource
import com.kostavukicevic.plants.data.EventEntity
import com.kostavukicevic.plants.data.PlantStatus
import com.kostavukicevic.plants.engine.Coefficients
import com.kostavukicevic.plants.engine.Moisture
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt
import kotlin.time.Instant

@Composable
fun PlantScreen(plantId: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val g = rememberGraph()
    val c = LocalContext.current
    val tick = changeTick()
    val status by produceState<PlantStatus?>(null, tick) { value = g.garden.status(plantId) }
    val events by g.garden.events(plantId).collectAsState(emptyList())
    val developer by g.settings.developerMode.collectAsState(false)
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var askRanOut by remember { mutableStateOf(false) }

    fun act(block: suspend () -> Unit) = scope.launch { block(); g.scheduler.reschedule(); g.changed() }

    val s = status
    Page(
        title = s?.plant?.name ?: "",
        onBack = onBack,
        actions = { TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) } },
    ) {
        if (s == null) return@Page
        Text(Texts.action(c, s.today), style = MaterialTheme.typography.titleLarge)
        Texts.reason(c, s)?.let { Text(it) }
        for (r in s.reminders) Text(Texts.reminder(c, r))
        for (a in s.alerts) Text(Texts.alert(c, a), color = MaterialTheme.colorScheme.error)
        s.snoozedUntil?.let { Text(stringResource(R.string.snoozed_until, Texts.date(it))) }
        Text(
            s.nextPrompt?.let { (d, a) -> stringResource(R.string.next_prompt, Texts.action(c, a), Texts.date(d)) }
                ?: stringResource(R.string.next_prompt_none),
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text(stringResource(R.string.plant_state, (s.state.depletion * 100).roundToInt()))
        val sd = s.state.belief.sd
        Text(
            stringResource(
                when {
                    sd < Coefficients.CONFIDENT_SD -> R.string.plant_confidence_high
                    sd < 0.35 -> R.string.plant_confidence_medium
                    else -> R.string.plant_confidence_low
                },
            ),
        )
        Text(stringResource(R.string.plant_learned, (s.state.belief.k * 100).roundToInt()), style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { askRanOut = true }) { Text(stringResource(R.string.plant_watered)) }
            OutlinedButton(onClick = { act { g.garden.snooze(plantId) } }) { Text(stringResource(R.string.plant_snooze)) }
        }
        Text(stringResource(R.string.plant_check_in), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in Moisture.entries) OutlinedButton(onClick = { act { g.garden.checkIn(plantId, m.name) } }) { Text(Texts.moisture(c, m)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { act { g.garden.moved(plantId) } }) { Text(stringResource(R.string.plant_moved)) }
            OutlinedButton(onClick = { act { g.garden.repotted(plantId) } }) { Text(stringResource(R.string.plant_repotted)) }
            OutlinedButton(onClick = { act { g.garden.fed(plantId) } }) { Text(stringResource(R.string.plant_fed)) }
        }

        HorizontalDivider()
        CareInfo(s, onReport = { uri.openUri(reportUrl(s)) })

        if (developer) {
            HorizontalDivider()
            Calibration(plantId)
        }

        HorizontalDivider()
        Text(stringResource(R.string.plant_history), style = MaterialTheme.typography.titleMedium)
        for (e in events.take(50)) Text(historyLine(c, e), style = MaterialTheme.typography.bodySmall)

        TextButton(onClick = { act { g.garden.deletePlant(plantId) }; onBack() }) {
            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
        }
    }

    if (askRanOut) {
        AlertDialog(
            onDismissRequest = { askRanOut = false },
            title = { Text(stringResource(R.string.plant_watered_question)) },
            confirmButton = { TextButton(onClick = { askRanOut = false; act { g.garden.watered(plantId, ranOutOfBottom = true) } }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { askRanOut = false; act { g.garden.watered(plantId, ranOutOfBottom = false) } }) { Text(stringResource(R.string.no)) } },
        )
    }
}

@Composable
private fun CareInfo(s: PlantStatus, onReport: () -> Unit) {
    val g = rememberGraph()
    val species = s.plant.speciesSlug?.let { g.careDb?.species(it) }
    Text(
        when (s.careSource) {
            CareSource.SPECIES -> stringResource(R.string.plant_care_source_species, species?.taxon?.acceptedName ?: s.plant.speciesSlug ?: "")
            CareSource.ARCHETYPE -> stringResource(R.string.plant_care_source_archetype, g.careDb?.archetypes?.get(s.plant.archetypeId)?.name ?: "")
            CareSource.GENERIC -> stringResource(R.string.plant_care_source_generic)
        },
        style = MaterialTheme.typography.bodySmall,
    )
    // In-ground hardiness is shown only as such: a pot's roots freeze far sooner.
    species?.groupsFor(CareContext.IN_GROUND)?.temperature?.minTempC?.let {
        Text(stringResource(R.string.cold_in_ground, it.toString()), style = MaterialTheme.typography.bodySmall)
    }
    if (s.careSource != CareSource.GENERIC) TextButton(onClick = onReport) { Text(stringResource(R.string.plant_report_wrong_data)) }
}

@Composable
private fun Calibration(plantId: Long) {
    val g = rememberGraph()
    val scope = rememberCoroutineScope()
    val entries by g.garden.calibration(plantId).collectAsState(emptyList())
    var grams by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Text(stringResource(R.string.calibration_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.calibration_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(grams, { grams = it; error = false }, label = { Text(stringResource(R.string.calibration_grams)) }, isError = error, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.calibration_note)) }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val v = positive(grams)
        if (v == null) error = true else scope.launch { g.garden.logMass(plantId, v, note.trim()); grams = ""; note = "" }
    }) { Text(stringResource(R.string.calibration_log)) }
    if (error) Text(stringResource(R.string.calibration_invalid), color = MaterialTheme.colorScheme.error)
    for (e in entries.asReversed().take(30)) {
        Text(stringResource(R.string.calibration_entry, Texts.dateTime(Instant.fromEpochMilliseconds(e.atEpochMs)), e.grams.toString(), e.note), style = MaterialTheme.typography.bodySmall)
    }
}

private fun historyLine(c: android.content.Context, e: EventEntity): String {
    val d = Texts.date(LocalDate.parse(e.date))
    return when (e.type) {
        "WATERED" -> c.getString(if (e.ranOutOfBottom == true) R.string.history_watered_ran_out else R.string.history_watered, d)
        "CHECK_IN" -> c.getString(R.string.history_check_in, d, e.moisture?.let { Texts.moisture(c, Moisture.valueOf(it)) } ?: "")
        "MOVED" -> c.getString(R.string.history_moved, d)
        "REPOTTED" -> c.getString(R.string.history_repotted, d)
        "FED" -> c.getString(R.string.history_fed, d)
        else -> c.getString(R.string.history_snoozed, d)
    }
}

/** A prefilled issue in the data repository. */
private fun reportUrl(s: PlantStatus): String {
    val what = s.plant.speciesSlug ?: s.plant.archetypeId ?: "unknown"
    val title = Uri.encode("Wrong data: $what")
    val body = Uri.encode("Entry: $what\n\nWhat is wrong:\n\nSource that shows it (a link and a short quote):\n")
    return "https://github.com/stcksmsh/overgrown-data/issues/new?title=$title&body=$body"
}
