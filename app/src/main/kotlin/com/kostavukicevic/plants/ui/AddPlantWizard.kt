// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.Radius
import com.kostavukicevic.plants.ui.theme.Spacing
import com.kostavukicevic.plants.ui.theme.StateBlock
import com.kostavukicevic.plants.ui.theme.Type
import com.kostavukicevic.plants.weather.PlaceMatch
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.roundToLong

// ---- Pot geometry (docs/DECISIONS.md D38) -----------------------------------------------
// The pot is a frustum: top width W, base width POT_BASE_TO_TOP * W, height (ratio for the shape) * W.
// Geometry, not care data, so these live here rather than in the engine's Coefficients.

/** Base diameter over top diameter of a usual tapered pot. */
internal const val POT_BASE_TO_TOP = 0.75

/** Height over top width: a bowl or pan. */
internal const val SHALLOW_HEIGHT_TO_WIDTH = 0.5

/** Height over top width: a usual nursery or flower pot, slightly lower than it is wide. */
internal const val STANDARD_HEIGHT_TO_WIDTH = 0.9

/** Height over top width: a long-tom or orchid-style tall pot. */
internal const val TALL_HEIGHT_TO_WIDTH = 1.5

/** Largest width the wizard takes, for the pot and for the plant. */
internal const val MAX_WIDTH_CM = 300.0

internal fun heightToWidth(shape: String): Double = when (shape) {
    "SHALLOW" -> SHALLOW_HEIGHT_TO_WIDTH
    "TALL" -> TALL_HEIGHT_TO_WIDTH
    else -> STANDARD_HEIGHT_TO_WIDTH
}

private const val FRUSTUM_FACTOR = 1 + POT_BASE_TO_TOP + POT_BASE_TO_TOP * POT_BASE_TO_TOP

/** Litres held by a pot [widthCm] across the top, of [shape]. */
internal fun potVolumeL(widthCm: Double, shape: String): Double =
    PI * heightToWidth(shape) * widthCm * widthCm * widthCm * FRUSTUM_FACTOR / 12 / 1000

/** The top width that gives [volumeL] for [shape]: the inverse of [potVolumeL]. */
internal fun potWidthCm(volumeL: Double, shape: String): Double =
    cbrt(volumeL * 1000 * 12 / (PI * heightToWidth(shape) * FRUSTUM_FACTOR))

/** A width the wizard accepts: above 0 and at most [MAX_WIDTH_CM]. */
internal fun width(s: String): Double? = positive(s)?.takeIf { it <= MAX_WIDTH_CM }

/** One decimal, without a trailing ".0", in the decimal style [positive] reads back. */
internal fun oneDecimal(x: Double): String {
    val tenths = (x * 10).roundToLong()
    return if (tenths % 10 == 0L) (tenths / 10).toString() else String.format(Locale.ROOT, "%.1f", tenths / 10.0)
}

// ---- Answers ------------------------------------------------------------------------------

/** The add-plant picker's looks. Herb has no archetype yet, so picking it means the general rules. */
enum class Look(val glyph: GlyphKind, val archetype: String?, @StringRes val label: Int) {
    Conifer(GlyphKind.Conifer, "hardy-potted-conifer", R.string.look_conifer),
    Succulent(GlyphKind.Succulent, "indoor-succulent", R.string.look_succulent),
    Leafy(GlyphKind.Leafy, "indoor-tropical-foliage", R.string.look_leafy),
    Fern(GlyphKind.Fern, "indoor-moisture-loving", R.string.look_fern),
    Orchid(GlyphKind.Orchid, "indoor-epiphytic-orchid", R.string.look_orchid),
    Herb(GlyphKind.Herb, null, R.string.look_herb),
}

enum class Answer(@StringRes val label: Int) { YES(R.string.answer_yes), LITTLE(R.string.answer_little), NO(R.string.answer_no), UNSURE(R.string.answer_unsure) }

/** "Not sure" stores partly and normal (DESIGN.md section 9, item 6). */
internal fun rainOf(a: Answer) = when (a) { Answer.YES -> "OPEN"; Answer.LITTLE, Answer.UNSURE -> "PARTIAL"; Answer.NO -> "COVERED" }
internal fun windOf(a: Answer) = when (a) { Answer.YES -> "EXPOSED"; Answer.NO -> "SHELTERED"; Answer.LITTLE, Answer.UNSURE -> "NORMAL" }
internal fun lightOf(a: Answer) = when (a) { Answer.YES -> "DIRECT"; Answer.LITTLE -> "HIGH"; Answer.NO, Answer.UNSURE -> "MEDIUM" }

private fun rainAnswer(rain: String) = when (rain) { "OPEN" -> Answer.YES; "COVERED" -> Answer.NO; else -> Answer.LITTLE }
private fun windAnswer(wind: String) = when (wind) { "EXPOSED" -> Answer.YES; "SHELTERED" -> Answer.NO; else -> Answer.UNSURE }
private fun sunAnswer(light: String?) = when (light) { "DIRECT" -> Answer.YES; "HIGH" -> Answer.LITTLE; null -> null; else -> Answer.NO }

/** A name search hit: a species, or an archetype by its own name. [label] is the name that matched. */
internal data class CareMatch(val slug: String?, val archetype: String, val label: String, val detail: String?)

private const val MAX_MATCHES = 8

/** Species by accepted name and common names, and archetypes by name; case-insensitive substring match. */
internal fun searchCare(db: CareDb?, query: String): List<CareMatch> {
    val q = query.trim()
    if (db == null || q.length < 2) return emptyList()
    val species = db.species.mapNotNull { (slug, s) ->
        val names = s.commonNames.values.flatten().map { it.name } + s.taxon.acceptedName
        names.firstOrNull { it.contains(q, ignoreCase = true) }
            ?.let { CareMatch(slug, s.archetype, it, s.taxon.acceptedName.takeIf { a -> a != it }) }
    }
    val archetypes = db.archetypes.filter { it.value.name.contains(q, ignoreCase = true) }.map { (id, a) -> CareMatch(null, id, a.name, null) }
    return (species + archetypes).take(MAX_MATCHES)
}

// ---- State --------------------------------------------------------------------------------

/** Every answer of the wizard. Saved with the instance state, so rotation and process death keep the step and answers. */
internal class WizardState(acquiredOn: String) {
    var step by mutableStateOf(1)
    var loaded by mutableStateOf(false)
    var showErrors by mutableStateOf(false)

    var name by mutableStateOf("")
    var speciesSlug by mutableStateOf<String?>(null)
    var speciesLabel by mutableStateOf<String?>(null)
    var archetype by mutableStateOf<String?>(null)
    var look by mutableStateOf<Look?>(null)

    var outdoor by mutableStateOf<Boolean?>(null)
    var placeId by mutableStateOf<Long?>(null)
    var newPlace by mutableStateOf(false)
    var placeName by mutableStateOf("")
    var cityQuery by mutableStateOf("")
    var cityPicked by mutableStateOf<String?>(null)
    var lat by mutableStateOf("")
    var lon by mutableStateOf("")
    var placeAdvanced by mutableStateOf(false)

    var potWidth by mutableStateOf("")
    var shape by mutableStateOf("STANDARD")
    var material by mutableStateOf("PLASTIC")
    var drainage by mutableStateOf(true)
    var nursery by mutableStateOf(false)
    var canopy by mutableStateOf("")
    var volumeOverride by mutableStateOf("")
    var potAdvanced by mutableStateOf(false)
    var acquiredOn by mutableStateOf(acquiredOn)

    var rain by mutableStateOf<Answer?>(null)
    var wind by mutableStateOf<Answer?>(null)
    var sun by mutableStateOf<Answer?>(null)
    var light by mutableStateOf<String?>(null)

    /** The plant goes to a place this wizard creates: chosen, or no place of the kind exists yet. */
    fun creating(places: List<PlaceEntity>) = newPlace || places.none { it.outdoor == outdoor }

    fun pickSpecies(m: CareMatch) {
        speciesSlug = m.slug; speciesLabel = m.label; archetype = m.archetype; look = null; name = m.label
    }

    fun clearSpecies() {
        speciesSlug = null; speciesLabel = null; archetype = null
    }

    fun pickLook(l: Look) {
        look = l; archetype = l.archetype; speciesSlug = null; speciesLabel = null
    }

    fun pickKind(out: Boolean) {
        if (outdoor != out) { placeId = null; newPlace = false; rain = null; wind = null; sun = null; light = null }
        outdoor = out
    }

    fun pickPlace(p: PlaceEntity) {
        placeId = p.id; newPlace = false
        rain = rainAnswer(p.rainExposure); wind = windAnswer(p.windExposure); light = p.lightClass; sun = sunAnswer(p.lightClass)
    }

    fun pickNewPlace() {
        newPlace = true; placeId = null; rain = null; wind = null; sun = null; light = null
    }

    fun computedVolumeL(): Double? = width(potWidth)?.let { potVolumeL(it, shape) }

    fun volumeL(): Double? = if (volumeOverride.isBlank()) computedVolumeL() else positive(volumeOverride)

    fun latitude() = lat.replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    fun longitude() = lon.replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }

    fun valid(s: Int, places: List<PlaceEntity>): Boolean = when (s) {
        1 -> name.isNotBlank()
        2 -> outdoor != null && if (creating(places)) {
            placeName.isNotBlank() && latitude() != null && longitude() != null
        } else {
            places.any { it.id == placeId && it.outdoor == outdoor }
        }
        3 -> width(potWidth) != null && width(canopy) != null && volumeL() != null && acquiredOn.isNotEmpty()
        else -> if (outdoor == true) rain != null && wind != null else light != null
    }

    companion object {
        val saver: Saver<WizardState, Any> = Saver(
            save = { s ->
                arrayListOf<Any?>(
                    s.step, s.loaded, s.showErrors, s.name, s.speciesSlug, s.speciesLabel, s.archetype, s.look?.ordinal,
                    s.outdoor, s.placeId, s.newPlace, s.placeName, s.cityQuery, s.cityPicked, s.lat, s.lon, s.placeAdvanced,
                    s.potWidth, s.shape, s.material, s.drainage, s.nursery, s.canopy, s.volumeOverride, s.potAdvanced, s.acquiredOn,
                    s.rain?.ordinal, s.wind?.ordinal, s.sun?.ordinal, s.light,
                )
            },
            restore = { saved ->
                val v = (saved as List<*>).iterator()
                fun int() = v.next() as Int
                fun bool() = v.next() as Boolean
                fun str() = v.next() as String
                fun strOrNull() = v.next() as String?
                fun answer() = (v.next() as Int?)?.let(Answer.entries::get)
                WizardState("").apply {
                    step = int(); loaded = bool(); showErrors = bool(); name = str(); speciesSlug = strOrNull(); speciesLabel = strOrNull()
                    archetype = strOrNull(); look = (v.next() as Int?)?.let(Look.entries::get)
                    outdoor = v.next() as Boolean?; placeId = v.next() as Long?; newPlace = bool(); placeName = str(); cityQuery = str()
                    cityPicked = strOrNull(); lat = str(); lon = str(); placeAdvanced = bool()
                    potWidth = str(); shape = str(); material = str(); drainage = bool(); nursery = bool(); canopy = str()
                    volumeOverride = str(); potAdvanced = bool(); acquiredOn = str()
                    rain = answer(); wind = answer(); sun = answer(); light = strOrNull()
                }
            },
        )
    }
}

private const val STEPS = 4

/** A stored volume within this share of the computed one counts as computed, so it is not shown as an override. */
private const val VOLUME_MATCH = 0.01

/**
 * Adding a plant, or editing one ([plantId]), in four steps: what plant, where, the pot, sun and rain.
 * [careDb] is the shipped care database; tests pass their own.
 */
@Composable
fun AddPlantWizard(plantId: Long?, onDone: () -> Unit, careDb: CareDb? = rememberGraph().careDb) {
    val g = rememberGraph()
    val scope = rememberCoroutineScope()
    val places by g.garden.places.collectAsState(null)
    val plants by g.garden.plants.collectAsState(emptyList())
    val st = rememberSaveable(saver = WizardState.saver) { WizardState(g.garden.today().toString()) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(plantId) {
        if (plantId == null || st.loaded) return@LaunchedEffect
        val p = g.garden.plant(plantId) ?: return@LaunchedEffect
        val place = g.garden.place(p.placeId)
        st.name = p.name; st.speciesSlug = p.speciesSlug; st.archetype = p.archetypeId
        st.speciesLabel = p.speciesSlug?.let { careDb?.species(it) }?.let { it.commonNames["en"]?.firstOrNull()?.name ?: it.taxon.acceptedName }
        if (p.speciesSlug == null) st.look = Look.entries.firstOrNull { it.archetype != null && it.archetype == p.archetypeId }
        if (place != null) { st.outdoor = place.outdoor; st.pickPlace(place) }
        st.shape = p.potShape; st.material = p.potMaterial; st.drainage = p.drainageHoles; st.nursery = p.inNurseryPot
        st.canopy = oneDecimal(p.canopyWidthCm); st.acquiredOn = p.acquiredOn
        // The wizard stores the height it computed, which gives the width back; older plants only have a volume.
        st.potWidth = oneDecimal(p.potHeightCm?.let { it / heightToWidth(p.potShape) } ?: potWidthCm(p.potVolumeL, p.potShape))
        val computed = st.computedVolumeL()
        if (computed == null || abs(computed - p.potVolumeL) > p.potVolumeL * VOLUME_MATCH) {
            st.volumeOverride = oneDecimal(p.potVolumeL); st.potAdvanced = true
        }
        st.loaded = true
    }

    val known = places ?: emptyList()
    fun back() {
        if (st.step > 1) { st.step--; st.showErrors = false } else onDone()
    }
    BackHandler(enabled = st.step > 1) { back() }

    fun save() {
        val firstBad = (1..STEPS).firstOrNull { !st.valid(it, known) }
        if (firstBad != null) { st.step = firstBad; st.showErrors = true; return }
        saving = true
        scope.launch {
            try {
                savePlantAndPlace(g, st, plantId, known)
                g.scheduler.reschedule(); g.changed(); onDone()
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        containerColor = Overgrown.colors.ground,
        topBar = { WizardTop(st.step, ::back) },
        bottomBar = {
            WizardBottom(
                step = st.step,
                editing = plantId != null,
                enabled = !saving && (plantId == null || st.loaded) && places != null,
                onNext = { if (st.valid(st.step, known)) { st.step++; st.showErrors = false } else st.showErrors = true },
                onSave = ::save,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen, vertical = Spacing.grid * 3),
            verticalArrangement = Arrangement.spacedBy(Spacing.grid * 4),
        ) {
            when (st.step) {
                1 -> PlantStep(st, careDb)
                2 -> WhereStep(st, known)
                3 -> PotStep(st)
                else -> ExposureStep(st, known, plants, plantId)
            }
            if (st.showErrors && !st.valid(st.step, known)) ErrorText(stringResource(R.string.wizard_fix_above))
        }
    }
}

private suspend fun savePlantAndPlace(g: Graph, st: WizardState, plantId: Long?, places: List<PlaceEntity>) {
    val outdoor = st.outdoor == true
    val rain = st.rain?.let(::rainOf) ?: "PARTIAL"
    val wind = st.wind?.let(::windOf) ?: "NORMAL"
    val creating = st.creating(places)
    // save() checked every step before calling this, so none of these returns is taken.
    val volume = st.volumeL() ?: return
    val potWidth = width(st.potWidth) ?: return
    val canopy = width(st.canopy) ?: return
    val placeId = if (creating) {
        val lat = st.latitude() ?: return
        val lon = st.longitude() ?: return
        g.garden.savePlace(
            PlaceEntity(
                name = st.placeName.trim(), outdoor = outdoor, latitude = lat, longitude = lon,
                rainExposure = if (outdoor) rain else "OPEN", windExposure = if (outdoor) wind else "NORMAL",
                lightClass = if (outdoor) null else st.light,
            ),
        )
    } else {
        val p = places.first { it.id == st.placeId }
        val updated = if (p.outdoor) {
            p.copy(rainExposure = rain, windExposure = wind)
        } else {
            // A stored lux wins over the class in WaterBalance, so a changed class clears it, as EditPlaceScreen does.
            p.copy(lightClass = st.light, lux = if (st.light == p.lightClass) p.lux else null)
        }
        if (updated != p) g.garden.savePlace(updated)
        p.id
    }
    val base = plantId?.let { g.garden.plant(it) }
        ?: PlantEntity(name = "", placeId = placeId, canopyWidthCm = canopy, potVolumeL = volume, acquiredOn = st.acquiredOn)
    g.garden.savePlant(
        base.copy(
            name = st.name.trim(), placeId = placeId, speciesSlug = st.speciesSlug, archetypeId = st.archetype,
            canopyWidthCm = canopy, potVolumeL = volume, potHeightCm = potWidth * heightToWidth(st.shape),
            potShape = st.shape, potMaterial = st.material, drainageHoles = st.drainage, inNurseryPot = st.nursery,
            acquiredOn = st.acquiredOn,
        ),
    )
    // A new outdoor place has no forecast yet, as in EditPlaceScreen.
    if (creating && outdoor) g.refreshWeather(foreground = true)
}

// ---- Frame --------------------------------------------------------------------------------

@Composable
private fun WizardTop(step: Int, onBack: () -> Unit) {
    val c = Overgrown.colors
    val description = stringResource(R.string.wizard_progress_description, step, STEPS)
    Row(
        Modifier.fillMaxWidth().background(c.ground).statusBarsPadding()
            .padding(top = Spacing.grid * 2, start = Spacing.grid * 2, end = Spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 2),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
            Text(stringResource(R.string.back), style = Type.label, color = c.ink)
        }
        Row(
            Modifier.weight(1f).clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(Spacing.grid + 2.dp),
        ) {
            for (i in 1..STEPS) {
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(Radius.barNarrow)).background(if (i <= step) c.ink else c.surface))
            }
        }
        Text(stringResource(R.string.wizard_progress, step, STEPS), Modifier.clearAndSetSemantics { }, style = Type.secondary, color = c.ink)
    }
}

@Composable
private fun WizardBottom(step: Int, editing: Boolean, enabled: Boolean, onNext: () -> Unit, onSave: () -> Unit) {
    val c = Overgrown.colors
    val primary = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.onInk)
    Row(
        Modifier.fillMaxWidth().background(c.ground).navigationBarsPadding()
            .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.grid * 3, bottom = Spacing.grid * 5),
        horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap),
    ) {
        val next = when (step) {
            1 -> R.string.wizard_next_where
            2 -> R.string.wizard_next_pot
            3 -> R.string.wizard_next_exposure
            else -> null
        }
        if (editing && next != null) {
            OutlinedButton(onClick = onSave, enabled = enabled, modifier = Modifier.heightIn(min = Spacing.buttonHeight)) {
                Text(stringResource(R.string.save), style = Type.label, color = c.ink)
            }
        }
        // A new button per step, so the last step's press does not carry over onto the next one.
        key(step) {
            Button(
                onClick = if (next == null) onSave else onNext,
                enabled = enabled,
                colors = primary,
                modifier = Modifier.weight(1f).heightIn(min = Spacing.buttonHeight),
            ) { Text(stringResource(next ?: R.string.save), style = Type.label) }
        }
    }
}

// ---- Step 1: what plant -------------------------------------------------------------------

@Composable
private fun PlantStep(st: WizardState, db: CareDb?) {
    val c = Overgrown.colors
    Title(stringResource(R.string.wizard_plant_title))
    val noName = st.showErrors && st.name.isBlank()
    OutlinedTextField(
        st.name, { st.name = it },
        label = { Text(stringResource(R.string.wizard_name)) },
        isError = noName,
        supportingText = if (noName) ({ Text(stringResource(R.string.wizard_name_needed)) }) else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val picked = st.speciesLabel
    if (picked != null) {
        // The picked result stands as the choice, so editing the name above does not search again.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(StateBlock.Moss.fill)
                .padding(start = Spacing.cardPadding, top = Spacing.grid * 2, bottom = Spacing.grid * 2, end = Spacing.grid),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(glyphFor(st.archetype), StateBlock.Moss.text, null, size = 32.dp)
            Text(picked, Modifier.weight(1f).padding(horizontal = Spacing.grid * 3), style = Type.label, color = StateBlock.Moss.text)
            val change = stringResource(R.string.wizard_change_named, picked)
            TextButton(
                onClick = st::clearSpecies,
                modifier = Modifier.heightIn(min = Spacing.minTouchTarget).semantics { contentDescription = change },
            ) { Text(stringResource(R.string.wizard_change), style = Type.label, color = StateBlock.Moss.text) }
        }
    } else if (st.name.trim().length >= 2) {
        val matches = remember(db, st.name) { searchCare(db, st.name) }
        if (matches.isEmpty()) {
            Text(stringResource(R.string.wizard_no_match), style = Type.secondary, color = c.muted)
        }
        for (m in matches) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget).clip(RoundedCornerShape(Radius.button))
                    .background(c.surface).clickable(role = Role.Button) { st.pickSpecies(m) }
                    .padding(horizontal = Spacing.cardPadding, vertical = Spacing.grid * 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(glyphFor(m.archetype), c.ink, null, size = 28.dp)
                Column(Modifier.padding(start = Spacing.grid * 3)) {
                    Text(m.label, style = Type.label, color = c.ink)
                    m.detail?.let { Text(it, style = Type.secondary, color = c.muted) }
                }
            }
        }
    }
    Text(stringResource(R.string.wizard_look_title), style = Type.secondary, color = c.ink)
    TileGrid(Look.entries, columns = 2, minHeight = 122.dp) { l, m ->
        Tile(stringResource(l.label), st.look == l, { st.pickLook(l) }, m) { color -> Glyph(l.glyph, color, null, size = 40.dp) }
    }
    if (st.archetype == null) Text(stringResource(R.string.wizard_general_rules), style = Type.secondary, color = c.muted)
}

// ---- Step 2: where ------------------------------------------------------------------------

@Composable
private fun WhereStep(st: WizardState, places: List<PlaceEntity>) {
    val g = rememberGraph()
    val scope = rememberCoroutineScope()
    val c = Overgrown.colors
    Title(stringResource(R.string.wizard_where_title))
    TileGrid(listOf(false, true), columns = 2, minHeight = 96.dp) { out, m ->
        Tile(stringResource(if (out) R.string.place_outdoor else R.string.place_indoor), st.outdoor == out, { st.pickKind(out) }, m)
    }
    if (st.showErrors && st.outdoor == null) ErrorText(stringResource(R.string.wizard_kind_needed))
    val outdoor = st.outdoor ?: return
    val ofKind = places.filter { it.outdoor == outdoor }
    if (ofKind.isNotEmpty()) {
        Text(stringResource(R.string.wizard_which_place), style = Type.secondary, color = c.ink)
        TileGrid(ofKind + null, columns = 2, minHeight = 64.dp) { p, m ->
            if (p == null) {
                Tile(stringResource(R.string.wizard_new_place), st.newPlace, st::pickNewPlace, m)
            } else {
                Tile(p.name, st.placeId == p.id && !st.newPlace, { st.pickPlace(p) }, m)
            }
        }
        if (st.showErrors && !st.creating(places) && st.placeId == null) ErrorText(stringResource(R.string.wizard_place_needed))
    }
    if (!st.creating(places)) return

    var matches by remember { mutableStateOf<List<PlaceMatch>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    OutlinedTextField(
        st.placeName, { st.placeName = it },
        label = { Text(stringResource(R.string.wizard_place_name)) },
        isError = st.showErrors && st.placeName.isBlank(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        st.cityQuery, { st.cityQuery = it },
        label = { Text(stringResource(R.string.field_city)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = {
            scope.launch {
                g.openMeteo.searchPlaces(st.cityQuery)
                    .onSuccess { matches = it; failed = false; searched = true }
                    .onFailure { failed = true; matches = emptyList() }
            }
        },
        enabled = st.cityQuery.isNotBlank(),
        modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
    ) { Text(stringResource(R.string.city_search), style = Type.label, color = c.ink) }
    if (failed) ErrorText(stringResource(R.string.wizard_city_offline))
    if (searched && !failed && matches.isEmpty()) Text(stringResource(R.string.city_none), style = Type.secondary, color = c.ink)
    for (m in matches) {
        val label = listOf(m.name, m.region).filter { it.isNotBlank() }.joinToString(", ")
        Text(
            label,
            Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget).clip(RoundedCornerShape(Radius.button))
                .background(c.surface).clickable(role = Role.Button) {
                    st.lat = m.latitude.toString(); st.lon = m.longitude.toString(); st.cityPicked = label
                    if (st.placeName.isBlank()) st.placeName = m.name
                    matches = emptyList(); searched = false
                }.padding(horizontal = Spacing.cardPadding, vertical = Spacing.grid * 3),
            style = Type.body, color = c.ink,
        )
    }
    st.cityPicked?.let { Text(stringResource(R.string.wizard_city_picked, it), style = Type.label, color = c.ink) }
    if (st.showErrors && (st.latitude() == null || st.longitude() == null)) ErrorText(stringResource(R.string.invalid_coordinates))
    AdvancedToggle(st.placeAdvanced) { st.placeAdvanced = it }
    if (st.placeAdvanced) {
        DecimalField(stringResource(R.string.field_latitude), st.lat, st.showErrors && st.latitude() == null) { st.lat = it; st.cityPicked = null }
        DecimalField(stringResource(R.string.field_longitude), st.lon, st.showErrors && st.longitude() == null) { st.lon = it; st.cityPicked = null }
    }
    Text(stringResource(if (outdoor) R.string.location_privacy else R.string.location_indoor), style = Type.secondary, color = c.muted)
}

// ---- Step 3: pot --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PotStep(st: WizardState) {
    val c = Overgrown.colors
    Title(stringResource(R.string.wizard_pot_title))
    val badWidth = st.showErrors && width(st.potWidth) == null
    val widthMessage = stringResource(R.string.wizard_width_invalid)
    DecimalField(stringResource(R.string.wizard_pot_width), st.potWidth, badWidth, widthMessage.takeIf { badWidth }) { st.potWidth = it }
    Text(stringResource(R.string.wizard_pot_shape), style = Type.secondary, color = c.ink)
    TileGrid(listOf("SHALLOW", "STANDARD", "TALL"), columns = 3, minHeight = 104.dp) { shape, m ->
        val label = when (shape) {
            "SHALLOW" -> R.string.shape_shallow
            "TALL" -> R.string.shape_tall
            else -> R.string.shape_standard
        }
        Tile(stringResource(label), st.shape == shape, { st.shape = shape }, m) { color -> PotOutline(shape, color) }
    }
    Choice(
        stringResource(R.string.field_pot_material),
        listOf(
            "PLASTIC" to stringResource(R.string.material_plastic),
            "GLAZED_CERAMIC" to stringResource(R.string.material_glazed),
            "UNGLAZED_TERRACOTTA" to stringResource(R.string.material_terracotta),
        ),
        st.material,
    ) { st.material = it }
    SwitchRow(stringResource(R.string.field_drainage), st.drainage) { st.drainage = it }
    SwitchRow(stringResource(R.string.field_nursery_pot), st.nursery) { st.nursery = it }
    val badCanopy = st.showErrors && width(st.canopy) == null
    DecimalField(stringResource(R.string.wizard_plant_width), st.canopy, badCanopy, widthMessage.takeIf { badCanopy }) { st.canopy = it }

    var picking by remember { mutableStateOf(false) }
    val date = parseDate(st.acquiredOn)
    val shown = date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: st.acquiredOn
    val dateLabel = stringResource(R.string.wizard_acquired_button, shown)
    Text(stringResource(R.string.wizard_acquired), style = Type.secondary, color = c.ink)
    OutlinedButton(
        onClick = { picking = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget).semantics { contentDescription = dateLabel },
    ) { Text(shown, style = Type.label, color = c.ink) }
    if (picking) {
        val todayMs = LocalDate.now().toEpochDay() * DAY_MS
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date?.toEpochDay()?.times(DAY_MS),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMs
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { st.acquiredOn = LocalDate.ofEpochDay(it / DAY_MS).toString() }
                    picking = false
                }) { Text(stringResource(R.string.wizard_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }

    AdvancedToggle(st.potAdvanced) { st.potAdvanced = it }
    if (st.potAdvanced) {
        st.computedVolumeL()?.let { Text(stringResource(R.string.wizard_volume_computed, oneDecimal(it)), style = Type.secondary, color = c.ink) }
        val badVolume = st.showErrors && st.volumeOverride.isNotBlank() && positive(st.volumeOverride) == null
        DecimalField(
            stringResource(R.string.field_pot_volume), st.volumeOverride, badVolume,
            stringResource(if (badVolume) R.string.invalid_number else R.string.wizard_volume_hint),
        ) { st.volumeOverride = it }
    }
}

private const val DAY_MS = 86_400_000L

/** The stored date, or null when it is not an ISO date; the raw text is then shown as it is. */
private fun parseDate(s: String): LocalDate? =
    try {
        LocalDate.parse(s)
    } catch (e: DateTimeParseException) {
        null
    }

/** The pot's side view at its real proportions, scaled to fit the tile. */
@Composable
private fun PotOutline(shape: String, color: Color) {
    Canvas(Modifier.size(width = 56.dp, height = 44.dp)) {
        val ratio = heightToWidth(shape).toFloat()
        val stroke = 3.dp.toPx()
        val w = minOf(size.width - stroke, (size.height - stroke) / ratio)
        val bottom = size.height - stroke / 2
        val top = bottom - w * ratio
        val left = (size.width - w) / 2
        val inset = w * (1 - POT_BASE_TO_TOP.toFloat()) / 2
        val path = Path().apply {
            moveTo(left, top); lineTo(left + w, top); lineTo(left + w - inset, bottom); lineTo(left + inset, bottom); close()
        }
        drawPath(path, color, style = Stroke(width = stroke))
        drawLine(color, Offset(left, top + stroke * 1.5f), Offset(left + w, top + stroke * 1.5f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

// ---- Step 4: exposure ---------------------------------------------------------------------

@Composable
private fun ExposureStep(st: WizardState, places: List<PlaceEntity>, plants: List<PlantEntity>, plantId: Long?) {
    val outdoor = st.outdoor == true
    Title(stringResource(if (outdoor) R.string.wizard_exposure_outdoor_title else R.string.wizard_exposure_indoor_title))
    if (!st.creating(places)) {
        val place = places.firstOrNull { it.id == st.placeId }
        val others = plants.filter { it.placeId == st.placeId && it.id != plantId }
        if (place != null && others.isNotEmpty()) {
            Text(
                stringResource(R.string.wizard_shared_place, place.name, others.joinToString(", ") { it.name }),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(StateBlock.Marigold.fill).padding(Spacing.cardPadding),
                style = Type.body, color = StateBlock.Marigold.text,
            )
        }
    }
    if (outdoor) {
        Question(stringResource(R.string.wizard_rain), Answer.entries, st.rain, st.showErrors) { st.rain = it }
        Question(stringResource(R.string.wizard_wind), listOf(Answer.YES, Answer.NO, Answer.UNSURE), st.wind, st.showErrors) { st.wind = it }
    } else {
        Question(stringResource(R.string.wizard_sun), Answer.entries, st.sun, st.showErrors && st.light == null) {
            st.sun = it; st.light = lightOf(it)
        }
        Choice(
            stringResource(R.string.field_light),
            listOf(
                "LOW" to stringResource(R.string.light_low),
                "MEDIUM" to stringResource(R.string.light_medium),
                "HIGH" to stringResource(R.string.light_high),
                "DIRECT" to stringResource(R.string.light_direct),
            ),
            st.light,
        ) { st.light = it }
        LightCheck(st.light ?: "") { st.light = it.name }
    }
}

@Composable
private fun Question(text: String, options: List<Answer>, selected: Answer?, showError: Boolean, onPick: (Answer) -> Unit) {
    Text(text, style = Type.label, color = Overgrown.colors.ink)
    TileGrid(options, columns = 2, minHeight = Spacing.minTouchTarget + Spacing.grid * 4) { a, m ->
        Tile(stringResource(a.label), selected == a, { onPick(a) }, m)
    }
    if (showError && selected == null) ErrorText(stringResource(R.string.wizard_answer_needed))
}

// ---- Parts --------------------------------------------------------------------------------

@Composable
private fun Title(text: String) =
    Text(text, Modifier.semantics { heading() }, style = Type.display, color = Overgrown.colors.ink)

@Composable
private fun ErrorText(text: String) = Text(text, style = Type.secondary, color = Overgrown.colors.errorText)

@Composable
private fun DecimalField(label: String, value: String, error: Boolean, supporting: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        isError = error,
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AdvancedToggle(open: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget).clip(RoundedCornerShape(Radius.button))
            .toggleable(open, role = Role.Switch, onValueChange = onChange).padding(horizontal = Spacing.grid),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.wizard_advanced), Modifier.weight(1f), style = Type.label, color = Overgrown.colors.ink)
        Text(if (open) "−" else "+", Modifier.clearAndSetSemantics { }, style = Type.cardTitle, color = Overgrown.colors.ink)
    }
}

/** [items] in rows of [columns]; tiles in a row share the tallest one's height. */
@Composable
private fun <T> TileGrid(items: List<T>, columns: Int, minHeight: Dp, tile: @Composable (T, Modifier) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        for (row in items.chunked(columns)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
                for (item in row) tile(item, Modifier.weight(1f).fillMaxHeight().heightIn(min = minHeight))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A choice tile: surface, or moss with a check mark when selected, so the state is not shown by colour alone. */
@Composable
private fun Tile(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, art: (@Composable (Color) -> Unit)? = null) {
    val fill = if (selected) StateBlock.Moss.fill else Overgrown.colors.surface
    val text = if (selected) StateBlock.Moss.text else Overgrown.colors.ink
    Column(
        modifier.clip(RoundedCornerShape(Radius.card)).background(fill)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            // Narrow side padding so "Standard" fits a third of the width at 1.3x text.
            .padding(horizontal = Spacing.grid * 2, vertical = Spacing.grid * 3),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            art?.invoke(text)
            Spacer(Modifier.weight(1f))
            if (selected) Check(text)
        }
        Text(label, style = Type.label, color = text)
    }
}

@Composable
private fun Check(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.width
        val path = Path().apply { moveTo(s * 0.18f, s * 0.52f); lineTo(s * 0.42f, s * 0.76f); lineTo(s * 0.84f, s * 0.28f) }
        drawPath(path, color, style = Stroke(width = s * 0.14f, cap = StrokeCap.Round))
    }
}
