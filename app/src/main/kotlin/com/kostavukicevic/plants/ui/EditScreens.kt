// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.weather.PlaceMatch
import kotlinx.coroutines.launch

/** A species or profile choice: (speciesSlug, archetypeId) and its label. */
private data class CareChoice(val slug: String?, val archetype: String?, val label: String)

@Composable
fun EditPlantScreen(plantId: Long?, onDone: () -> Unit, onNewPlace: () -> Unit) {
    val g = rememberGraph()
    val scope = rememberCoroutineScope()
    val places by g.garden.places.collectAsState(emptyList())

    var loaded by remember { mutableStateOf<PlantEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var care by remember { mutableStateOf<CareChoice?>(null) }
    var placeId by remember { mutableStateOf<Long?>(null) }
    var canopy by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var shape by remember { mutableStateOf("STANDARD") }
    var material by remember { mutableStateOf("PLASTIC") }
    var drainage by remember { mutableStateOf(true) }
    var nursery by remember { mutableStateOf(false) }
    var rootBound by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }

    val db = g.careDb
    val generic = CareChoice(null, null, stringResource(R.string.field_species_generic))
    val choices = remember(db) {
        listOf(generic) +
            (db?.species?.map { (slug, s) -> CareChoice(slug, s.archetype, s.taxon.acceptedName + (s.commonNames["en"]?.firstOrNull()?.let { " (${it.name})" } ?: "")) } ?: emptyList()) +
            (db?.archetypes?.map { (id, a) -> CareChoice(null, id, a.name) } ?: emptyList())
    }

    LaunchedEffect(plantId) {
        val p = plantId?.let { g.garden.plant(it) } ?: return@LaunchedEffect
        loaded = p
        name = p.name; placeId = p.placeId; canopy = p.canopyWidthCm.toString(); volume = p.potVolumeL.toString()
        height = p.potHeightCm?.toString() ?: ""; shape = p.potShape; material = p.potMaterial
        drainage = p.drainageHoles; nursery = p.inNurseryPot; rootBound = p.rootBound
        care = choices.firstOrNull { it.slug == p.speciesSlug && (p.speciesSlug != null || it.archetype == p.archetypeId) } ?: generic
    }
    LaunchedEffect(places) { if (placeId == null) placeId = places.firstOrNull()?.id }

    Page(stringResource(R.string.edit_plant_title), onBack = onDone) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, isError = showErrors && name.isBlank(), modifier = Modifier.fillMaxWidth())

        Text(stringResource(R.string.field_species), style = MaterialTheme.typography.labelLarge)
        for (ch in choices) {
            val selected = (care ?: generic) == ch
            Text(
                (if (selected) "● " else "○ ") + ch.label,
                modifier = Modifier.fillMaxWidth().clickable { care = ch }.padding(vertical = 4.dp),
            )
        }

        Text(stringResource(R.string.field_place), style = MaterialTheme.typography.labelLarge)
        if (places.isEmpty()) Text(stringResource(R.string.need_place), color = MaterialTheme.colorScheme.error)
        for (p in places) {
            Text((if (placeId == p.id) "● " else "○ ") + p.name, Modifier.fillMaxWidth().clickable { placeId = p.id }.padding(vertical = 4.dp))
        }
        TextButton(onClick = onNewPlace) { Text(stringResource(R.string.field_place_new)) }

        Text(stringResource(R.string.size_hint), style = MaterialTheme.typography.bodySmall)
        NumberField(stringResource(R.string.field_canopy), canopy, showErrors) { canopy = it }
        NumberField(stringResource(R.string.field_pot_volume), volume, showErrors) { volume = it }
        OutlinedTextField(height, { height = it }, label = { Text(stringResource(R.string.field_pot_height)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Choice(stringResource(R.string.field_pot_shape), listOf("SHALLOW" to stringResource(R.string.shape_shallow), "STANDARD" to stringResource(R.string.shape_standard), "TALL" to stringResource(R.string.shape_tall)), shape) { shape = it }
        Choice(stringResource(R.string.field_pot_material), listOf("PLASTIC" to stringResource(R.string.material_plastic), "GLAZED_CERAMIC" to stringResource(R.string.material_glazed), "UNGLAZED_TERRACOTTA" to stringResource(R.string.material_terracotta)), material) { material = it }
        SwitchRow(stringResource(R.string.field_drainage), drainage) { drainage = it }
        SwitchRow(stringResource(R.string.field_nursery_pot), nursery) { nursery = it }
        SwitchRow(stringResource(R.string.field_root_bound), rootBound) { rootBound = it }

        Button(onClick = {
            val c = positive(canopy); val v = positive(volume); val pl = placeId
            if (name.isBlank() || c == null || v == null || pl == null) { showErrors = true; return@Button }
            val chosen = care ?: generic
            val entity = (loaded ?: PlantEntity(name = "", placeId = pl, canopyWidthCm = c, potVolumeL = v, acquiredOn = g.garden.today().toString())).copy(
                name = name.trim(), placeId = pl, speciesSlug = chosen.slug, archetypeId = chosen.archetype,
                canopyWidthCm = c, potVolumeL = v, potHeightCm = positive(height), potShape = shape, potMaterial = material,
                drainageHoles = drainage, inNurseryPot = nursery, rootBound = rootBound,
            )
            scope.launch { g.garden.savePlant(entity); g.scheduler.reschedule(); g.changed(); onDone() }
        }) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun NumberField(label: String, value: String, showErrors: Boolean, onChange: (String) -> Unit) {
    val bad = showErrors && positive(value) == null
    OutlinedTextField(value, onChange, label = { Text(label) }, isError = bad,
        supportingText = { if (bad) Text(stringResource(R.string.invalid_number)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

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
                    lightClass = if (outdoor) loaded?.lightClass else light, dryAir = if (outdoor) loaded?.dryAir ?: false else dryAir,
                )
            scope.launch {
                g.garden.savePlace(entity)
                if (outdoor) g.garden.refreshWeather(foreground = true)
                g.scheduler.reschedule(); g.changed(); onDone()
            }
        }) { Text(stringResource(R.string.save)) }
    }
}
