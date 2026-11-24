// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.OpenMeteo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val g = rememberGraph()
    val c = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val developer by g.settings.developerMode.collectAsState(false)
    var message by remember { mutableStateOf<String?>(null) }

    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { target ->
        target ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val out = withContext(Dispatchers.IO) { c.contentResolver.openOutputStream(target) } ?: throw java.io.IOException("unwritable file")
                g.garden.exportZip(g.versionName, out)
                res.getString(R.string.export_done)
            } catch (e: java.io.IOException) {
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
                val missing = input.use { g.garden.import(it) }
                g.scheduler.reschedule(); g.changed()
                if (missing == 0) res.getString(R.string.import_done) else res.getQuantityString(R.plurals.import_done_missing_photos, missing, missing)
            } catch (e: java.io.IOException) {
                res.getString(R.string.import_failed, e.message)
            } catch (e: IllegalArgumentException) {
                res.getString(R.string.import_failed, e.message)
            }
        }
    }

    Page(stringResource(R.string.settings), onBack = onBack) {
        TextButton(onClick = { exportZip.launch("overgrown-export.zip") }) { Text(stringResource(R.string.export_json)) }
        // A zip export, or a plain JSON one from an older build. Some file managers label zips with other types.
        TextButton(onClick = {
            importJson.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/plain", "application/octet-stream"))
        }) { Text(stringResource(R.string.import_json)) }
        SwitchRow(stringResource(R.string.developer_mode), developer) { on -> scope.launch { g.settings.setDeveloperMode(on) } }
        if (developer) TextButton(onClick = { exportCsv.launch("overgrown-calibration.csv") }) { Text(stringResource(R.string.export_calibration)) }
        message?.let { Text(it) }

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
    }
}

private const val SOURCE_URL = "https://github.com/stcksmsh/overgrown"
private const val PRIVACY_URL = "https://github.com/stcksmsh/overgrown/blob/main/docs/PRIVACY.md"
