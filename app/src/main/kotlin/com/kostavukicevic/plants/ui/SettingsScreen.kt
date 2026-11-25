// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.provider.DocumentsContract
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.data.ThemeChoice
import com.kostavukicevic.plants.data.TransitionChoice
import com.kostavukicevic.plants.ui.theme.StateBlock
import com.kostavukicevic.plants.ui.theme.Type
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.OpenMeteo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val g = rememberGraph()
    val c = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val developer by g.settings.developerMode.collectAsState(false)
    val weighing by g.settings.weighing.collectAsState(false)
    val theme by g.settings.theme.collectAsState(ThemeChoice.SYSTEM)
    val transition by g.settings.transition.collectAsState(TransitionChoice.SHARED)
    val time by g.settings.reminderTime.collectAsState(null)
    var message by remember { mutableStateOf<String?>(null) }
    var fontLicence by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { target ->
        target ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val out = withContext(Dispatchers.IO) { c.contentResolver.openOutputStream(target) } ?: throw java.io.IOException("unwritable file")
                g.garden.exportZip(g.versionName, out) // closes out, also when it fails
                res.getString(R.string.export_done)
            } catch (e: java.io.IOException) {
                // A half-written zip would look like a good export; remove it where the provider allows.
                withContext(Dispatchers.IO) {
                    try {
                        DocumentsContract.deleteDocument(c.contentResolver, target)
                    } catch (_: java.io.FileNotFoundException) {
                        // Best effort: the message below still says the export failed.
                    } catch (_: RuntimeException) {
                        // Providers without delete throw UnsupportedOperationException, others SecurityException; same as above.
                    }
                }
                e.message
            }
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { target ->
        target ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val text = g.garden.calibrationCsv()
                withContext(Dispatchers.IO) { c.contentResolver.openOutputStream(target)?.use { it.write(text.toByteArray()) } }
                res.getString(R.string.export_done)
            } catch (e: java.io.IOException) {
                e.message
            }
        }
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        source ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val input = withContext(Dispatchers.IO) { c.contentResolver.openInputStream(source) } ?: throw java.io.IOException("unreadable file")
                val before = g.garden.plants.first()
                val missing = input.use { g.garden.import(it) }
                g.scheduler.imported(before); g.changed()
                if (missing == 0) res.getString(R.string.import_done) else res.getQuantityString(R.plurals.import_done_missing_photos, missing, missing)
            } catch (e: java.io.IOException) {
                res.getString(R.string.import_failed, e.message)
            } catch (e: IllegalArgumentException) {
                res.getString(R.string.import_failed, e.message)
            }
        }
    }

    Page(stringResource(R.string.settings), onBack = onBack) {
        Text(stringResource(R.string.settings_reminders), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_reminder_time), Modifier.weight(1f))
            val shown = time?.let { "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" } ?: ""
            val change = stringResource(R.string.settings_reminder_time_change, shown)
            TextButton(onClick = { pickTime = true }, enabled = time != null, modifier = Modifier.semantics { contentDescription = change }) {
                Text(shown, style = Type.label)
            }
        }
        Text(stringResource(R.string.settings_reminder_time_hint), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Choice(
            stringResource(R.string.settings_theme),
            listOf(
                ThemeChoice.SYSTEM to stringResource(R.string.settings_theme_system),
                ThemeChoice.LIGHT to stringResource(R.string.settings_theme_light),
                ThemeChoice.DARK to stringResource(R.string.settings_theme_dark),
            ),
            theme,
        ) { t -> scope.launch { g.settings.setTheme(t) } }
        Text(stringResource(R.string.settings_units))

        HorizontalDivider()
        Text(stringResource(R.string.settings_data), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { exportZip.launch("overgrown-export.zip") }) { Text(stringResource(R.string.export_json)) }
        TextButton(onClick = { confirmImport = true }) { Text(stringResource(R.string.import_json)) }
        message?.let { Text(it) }

        HorizontalDivider()
        SwitchRow(stringResource(R.string.settings_weighing), weighing) { on -> scope.launch { g.settings.setWeighing(on) } }
        Text(stringResource(R.string.settings_weighing_hint), style = MaterialTheme.typography.bodySmall)
        SwitchRow(stringResource(R.string.developer_mode), developer) { on -> scope.launch { g.settings.setDeveloperMode(on) } }
        if (developer) {
            TextButton(onClick = { exportCsv.launch("overgrown-calibration.csv") }) { Text(stringResource(R.string.export_calibration)) }
            // Only until the owner picks one of the three (D44).
            Choice(
                stringResource(R.string.settings_transitions),
                listOf(
                    TransitionChoice.SHARED to stringResource(R.string.settings_transitions_shared),
                    TransitionChoice.SLIDE to stringResource(R.string.settings_transitions_slide),
                    TransitionChoice.FADE to stringResource(R.string.settings_transitions_fade),
                ),
                transition,
            ) { t -> scope.launch { g.settings.setTransition(t) } }
        }

        HorizontalDivider()
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.about_version, g.versionName))
        Text(stringResource(R.string.about_built))
        Text(stringResource(R.string.about_licence))
        Text(stringResource(R.string.about_weather, OpenMeteo.CREDIT, OpenMeteo.LICENCE))
        TextButton(onClick = { uri.openUri(OpenMeteo.CREDIT_URL) }) { Text(OpenMeteo.CREDIT_URL) }
        Text(stringResource(R.string.about_weather, MetNorway.CREDIT, MetNorway.LICENCE))
        TextButton(onClick = { uri.openUri(MetNorway.LICENCE_URL) }) { Text(MetNorway.LICENCE_URL) }
        Text(stringResource(R.string.about_data))
        Text(stringResource(R.string.about_privacy))
        TextButton(onClick = { uri.openUri(PRIVACY_URL) }) { Text(stringResource(R.string.about_privacy_link)) }
        Text(stringResource(R.string.about_care_notice), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { uri.openUri(SOURCE_URL) }) { Text(stringResource(R.string.about_source)) }
        Text(stringResource(R.string.about_font))
        TextButton(onClick = { fontLicence = true }) { Text(stringResource(R.string.about_font_licence)) }
    }

    if (confirmImport) {
        // Brick, as every destructive confirmation; Cancel, back and a tap outside all leave the plants as they are.
        val block = StateBlock.Brick
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            containerColor = block.fill,
            titleContentColor = block.text,
            textContentColor = block.text,
            title = { Text(stringResource(R.string.import_confirm_title), style = Type.cardTitle) },
            text = { Text(stringResource(R.string.import_confirm_text), style = Type.body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    // A zip export, or a plain JSON one from an older build. Some file managers label zips with other types.
                    importJson.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/plain", "application/octet-stream"))
                }) { Text(stringResource(R.string.import_confirm), style = Type.label, color = block.text) }
            },
            dismissButton = { OnBlockButton(stringResource(R.string.cancel), onClick = { confirmImport = false }) },
        )
    }

    if (pickTime) time?.let { t ->
        val state = rememberTimePickerState(t.hour, t.minute, is24Hour = DateFormat.is24HourFormat(c))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    pickTime = false
                    scope.launch {
                        g.settings.setReminderTime(LocalTime(state.hour, state.minute))
                        g.scheduler.reschedule()
                    }
                }) { Text(stringResource(R.string.settings_time_set)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state) },
        )
    }

    if (fontLicence) {
        val text = remember { res.openRawResource(R.raw.ofl_bricolage_grotesque).bufferedReader().use { it.readText() } }
        AlertDialog(
            onDismissRequest = { fontLicence = false },
            confirmButton = { TextButton(onClick = { fontLicence = false }) { Text(stringResource(R.string.close)) } },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        )
    }
}

private const val SOURCE_URL = "https://github.com/stcksmsh/overgrown"
private const val PRIVACY_URL = "https://github.com/stcksmsh/overgrown/blob/main/docs/PRIVACY.md"
