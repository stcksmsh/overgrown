// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.engine.LightClass
import com.kostavukicevic.plants.engine.WaterBalance
import com.kostavukicevic.plants.weather.PlaceMatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EditPlaceScreen(placeId: Long?, onDone: () -> Unit) {
    val g = rememberGraph()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<PlaceEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<PlaceMatch>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var rain by remember { mutableStateOf("OPEN") }
    var wind by remember { mutableStateOf("NORMAL") }
    var outdoor by remember { mutableStateOf(true) }
    // Preselected so an indoor place is never saved without a light class.
    var light by remember { mutableStateOf("MEDIUM") }
    var dryAir by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }

    LaunchedEffect(placeId) {
        val p = placeId?.let { g.garden.place(it) } ?: return@LaunchedEffect
        loaded = p; name = p.name; lat = p.latitude.toString(); lon = p.longitude.toString(); rain = p.rainExposure; wind = p.windExposure
        outdoor = p.outdoor; light = p.lightClass ?: "MEDIUM"; dryAir = p.dryAir
    }

    Page(stringResource(R.string.edit_place_title), onBack = onDone) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, isError = showErrors && name.isBlank(), modifier = Modifier.fillMaxWidth())
        Choice(stringResource(R.string.field_place_kind), listOf(true to stringResource(R.string.place_outdoor), false to stringResource(R.string.place_indoor)), outdoor) { outdoor = it }
        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.field_city)) }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = {
            scope.launch {
                g.openMeteo.searchPlaces(query)
                    .onSuccess { matches = it; searchError = null; searched = true }
                    .onFailure { searchError = it.message ?: it.toString(); matches = emptyList() }
            }
        }) { Text(stringResource(R.string.city_search)) }
        searchError?.let { Text(stringResource(R.string.city_error, it), color = MaterialTheme.colorScheme.error) }
        if (searched && matches.isEmpty() && searchError == null) Text(stringResource(R.string.city_none))
        for (m in matches) {
            Text("${m.name}, ${m.region}", Modifier.fillMaxWidth().clickable {
                lat = m.latitude.toString(); lon = m.longitude.toString(); if (name.isBlank()) name = m.name; matches = emptyList()
            }.padding(vertical = 6.dp))
        }
        OutlinedTextField(lat, { lat = it }, label = { Text(stringResource(R.string.field_latitude)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(lon, { lon = it }, label = { Text(stringResource(R.string.field_longitude)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Text(stringResource(if (outdoor) R.string.location_privacy else R.string.location_indoor), style = MaterialTheme.typography.bodySmall)

        if (outdoor) {
            Choice(stringResource(R.string.field_rain), listOf("OPEN" to stringResource(R.string.rain_open), "PARTIAL" to stringResource(R.string.rain_partial), "COVERED" to stringResource(R.string.rain_covered)), rain) { rain = it }
            Choice(stringResource(R.string.field_wind), listOf("SHELTERED" to stringResource(R.string.wind_sheltered), "NORMAL" to stringResource(R.string.wind_normal), "EXPOSED" to stringResource(R.string.wind_exposed)), wind) { wind = it }
        } else {
            Choice(
                stringResource(R.string.field_light),
                listOf("LOW" to stringResource(R.string.light_low), "MEDIUM" to stringResource(R.string.light_medium), "HIGH" to stringResource(R.string.light_high), "DIRECT" to stringResource(R.string.light_direct)),
                light,
            ) { light = it }
            LightCheck(light) { light = it.name }
            SwitchRow(stringResource(R.string.field_dry_air), dryAir) { dryAir = it }
        }

        val la = lat.replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val lo = lon.replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        if (showErrors && (la == null || lo == null)) Text(stringResource(R.string.invalid_coordinates), color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            if (name.isBlank() || la == null || lo == null) { showErrors = true; return@Button }
            val entity = (loaded ?: PlaceEntity(name = "", outdoor = outdoor, latitude = la, longitude = lo))
                .copy(
                    name = name.trim(), outdoor = outdoor, latitude = la, longitude = lo, rainExposure = rain, windExposure = wind,
                    lightClass = if (outdoor) loaded?.lightClass else light,
                    // The chosen class is authoritative indoors; a stored lux would override it in the engine (D28).
                    lux = if (outdoor) loaded?.lux else null,
                    dryAir = if (outdoor) loaded?.dryAir ?: false else dryAir,
                )
            scope.launch {
                g.garden.savePlace(entity)
                if (outdoor) g.garden.refreshWeather(foreground = true)
                g.scheduler.reschedule(); g.changed(); onDone()
            }
        }) { Text(stringResource(R.string.save)) }
    }
}

/**
 * Suggests a light class from the ambient light sensor. Only the class is passed on, never the
 * lux reading (D28). Shows nothing on a device without a light sensor.
 */
@Composable
internal fun LightCheck(selected: String, onClass: (LightClass) -> Unit) {
    val context = LocalContext.current
    val sensors = remember { context.getSystemService(SensorManager::class.java) }
    val sensor = remember { sensors?.getDefaultSensor(Sensor.TYPE_LIGHT) }
    if (sensors == null || sensor == null) return
    var run by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LightClass?>(null) }
    var noReading by remember { mutableStateOf(false) }
    // A class picked by hand after a check makes "Selected above" untrue, so the message goes.
    LaunchedEffect(selected) { if (result != null && result?.name != selected) result = null }

    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        checking = true; noReading = false; result = null
        val readings = mutableListOf<Float>()
        // Registered without a handler, so events arrive on the main thread, like this effect.
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { readings += event.values[0] }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        // Leaving the screen cancels the effect, which unregisters here too.
        try { delay(LIGHT_CHECK_MS) } finally { sensors.unregisterListener(listener) }
        checking = false
        if (readings.isEmpty()) { noReading = true; return@LaunchedEffect }
        val sorted = readings.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
        val cls = WaterBalance.lightClassOf(median)
        result = cls
        onClass(cls)
    }

    Text(stringResource(R.string.light_check_how), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { run++ }, enabled = !checking) {
        Text(stringResource(if (checking) R.string.light_checking else R.string.light_check))
    }
    result?.let {
        val label = stringResource(
            when (it) {
                LightClass.LOW -> R.string.light_low
                LightClass.MEDIUM -> R.string.light_medium
                LightClass.HIGH -> R.string.light_high
                LightClass.DIRECT -> R.string.light_direct
            },
        )
        Text(stringResource(R.string.light_check_result, label))
    }
    if (noReading) Text(stringResource(R.string.light_check_none), color = MaterialTheme.colorScheme.error)
}

/** Readings fluctuate with hand and cloud movement; the median of about 3 s of them is used. */
private const val LIGHT_CHECK_MS = 3_000L
