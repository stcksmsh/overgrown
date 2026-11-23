// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.engine.LightClass
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.engine.PotMaterial
import com.kostavukicevic.plants.engine.PotShape
import com.kostavukicevic.plants.engine.RainExposure
import com.kostavukicevic.plants.engine.WindExposure
import com.kostavukicevic.plants.weather.CachedResponse
import com.kostavukicevic.plants.weather.Climatology
import com.kostavukicevic.plants.weather.Coordinates
import com.kostavukicevic.plants.weather.DailyWeather
import com.kostavukicevic.plants.weather.FallbackState
import com.kostavukicevic.plants.weather.FetchResult
import com.kostavukicevic.plants.weather.OpenMeteo
import com.kostavukicevic.plants.weather.ResponseStore
import com.kostavukicevic.plants.weather.WeatherService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** The app's single entry to stored data, weather and the engine. */
class Garden(
    private val db: GardenDb,
    private val settings: Settings,
    private val weather: WeatherService,
    private val openMeteo: OpenMeteo,
    private val careDb: () -> CareDb?,
    private val now: () -> Instant,
    private val timeZone: () -> TimeZone,
) {
    private val dao = db.dao()

    fun today(): LocalDate = now().toLocalDateTime(timeZone()).date

    val places: Flow<List<PlaceEntity>> get() = dao.places()
    val plants: Flow<List<PlantEntity>> get() = dao.plants()
    fun events(plantId: Long) = dao.eventsFlow(plantId)
    fun calibration(plantId: Long) = dao.calibrationFlow(plantId)
    fun lastFetch(placeId: Long) = dao.lastFetchFlow(placeId)
    suspend fun lastFetchOnce(placeId: Long): Long? = dao.lastFetchFlow(placeId).first()
    suspend fun lastProvider(): String? = dao.lastProvider()

    suspend fun place(id: Long) = dao.place(id)
    suspend fun plant(id: Long) = dao.plant(id)
    suspend fun savePlace(p: PlaceEntity) = dao.upsertPlace(p).let { if (p.id != 0L) p.id else it }
    suspend fun savePlant(p: PlantEntity) = dao.upsertPlant(p).let { if (p.id != 0L) p.id else it }
    suspend fun deletePlant(id: Long) = dao.deletePlantFully(id)
    suspend fun deletePlace(id: Long) = dao.deletePlaceFully(id)
    suspend fun deleteEvent(id: Long) = dao.deleteEvent(id)

    suspend fun watered(plantId: Long, ranOutOfBottom: Boolean, moisture: String? = null) =
        dao.insertEvent(EventEntity(plantId = plantId, date = today().toString(), type = "WATERED", ranOutOfBottom = ranOutOfBottom, moisture = moisture))

    suspend fun checkIn(plantId: Long, moisture: String) =
        dao.insertEvent(EventEntity(plantId = plantId, date = today().toString(), type = "CHECK_IN", moisture = moisture))

    suspend fun snooze(plantId: Long, days: Int = 1) =
        dao.insertEvent(EventEntity(plantId = plantId, date = today().toString(), type = "SNOOZED", snoozedUntil = today().plus(DatePeriod(days = days)).toString()))

    suspend fun moved(plantId: Long) = dao.insertEvent(EventEntity(plantId = plantId, date = today().toString(), type = "MOVED"))

    suspend fun repotted(plantId: Long) {
        dao.insertEvent(EventEntity(plantId = plantId, date = today().toString(), type = "REPOTTED"))
        dao.plant(plantId)?.let { dao.upsertPlant(it.copy(lastRepotOn = today().toString())) }
    }

    suspend fun logMass(plantId: Long, grams: Double, note: String = "") {
        require(grams > 0 && grams.isFinite()) { "mass must be a positive number of grams" }
        dao.insertCalibration(CalibrationEntity(plantId = plantId, atEpochMs = now().toEpochMilliseconds(), grams = grams, note = note))
    }

    private val refreshing = Mutex()

    /**
     * Fetches weather for every outdoor place. In the background only Open-Meteo is asked.
     * One refresh at a time, so two quick triggers never ask a provider twice.
     */
    suspend fun refreshWeather(foreground: Boolean): Map<Long, FetchResult> = refreshing.withLock { refreshAll(foreground) }

    private suspend fun refreshAll(foreground: Boolean): Map<Long, FetchResult> {
        val out = LinkedHashMap<Long, FetchResult>()
        for (place in dao.allPlaces()) {
            val at = Coordinates.of(place.latitude, place.longitude)
            val result = weather.fetch(at, foreground)
            when (result) {
                is FetchResult.Fresh -> store(place.id, result.provider.name, result.days, result.fetchedAt)
                else -> Unit
            }
            if (foreground && dao.climatology(place.id).isEmpty()) fetchClimatology(place.id, at)
            out[place.id] = result
        }
        return out
    }

    private suspend fun store(placeId: Long, provider: String, days: List<DailyWeather>, fetchedAt: Instant) {
        dao.upsertWeather(days.map {
            WeatherDayEntity(placeId, it.date.toString(), it.tMinC, it.tMaxC, it.precipitationMm, it.meanWindMs, it.meanRelativeHumidityPct, it.et0Mm, provider, fetchedAt.toEpochMilliseconds())
        })
    }

    /** Five years of history, once per place, to fill gaps with monthly means. */
    private suspend fun fetchClimatology(placeId: Long, at: Coordinates) {
        val end = today().minus(DatePeriod(days = 7))
        val start = end.minus(DatePeriod(years = 5))
        val history = openMeteo.archive(at, start, end).getOrNull() ?: return
        val clim = Climatology.from(history)
        dao.upsertClimatology(clim.months.map { (m, d) ->
            ClimatologyEntity(placeId, m.ordinal + 1, d.tMinC, d.tMaxC, d.precipitationMm, d.meanWindMs, d.meanRelativeHumidityPct, d.et0Mm)
        })
    }

    suspend fun status(plantId: Long): PlantStatus? {
        val plant = dao.plant(plantId) ?: return null
        val place = dao.place(plant.placeId) ?: return null
        return Planner.status(plant, place, dao.events(plantId), dao.weather(place.id), dao.climatology(place.id), careDb(), today())
    }

    suspend fun statuses(): List<PlantStatus> = dao.allPlants().mapNotNull { status(it.id) }

    // ---- Export, import, calibration CSV ------------------------------------------------

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun exportJson(appVersion: String): String = json.encodeToString(
        Export.serializer(),
        Export(
            format = Export.FORMAT, version = Export.VERSION, appVersion = appVersion, exportedAt = now().toString(),
            places = dao.allPlaces(), plants = dao.allPlants(), events = dao.allEvents(),
            weather = dao.allWeather(), calibration = dao.allCalibration(),
        ),
    )

    /** Replaces everything with the file's contents, in one transaction. */
    suspend fun importJson(text: String) {
        val e = json.decodeFromString(Export.serializer(), text)
        require(e.format == Export.FORMAT) { "not an Overgrown export" }
        require(e.version <= Export.VERSION) { "export version ${e.version} is newer than this app" }
        validate(e) // before anything is replaced: a bad file changes nothing
        dao.replaceAll(e.places, e.plants, e.events, e.weather, e.calibration)
    }

    /** Every value the planner will parse, checked now; throws IllegalArgumentException naming the first bad one. */
    private fun validate(e: Export) {
        fun date(s: String?, what: String) = require(s != null && runCatching { LocalDate.parse(s) }.isSuccess) { "bad date in $what: $s" }
        val placeIds = e.places.map { it.id }.toSet()
        for (p in e.places) {
            require(p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0) { "bad coordinates for place ${p.id}" }
            require(runCatching { RainExposure.valueOf(p.rainExposure); WindExposure.valueOf(p.windExposure) }.isSuccess) { "bad exposure for place ${p.id}" }
            p.lightClass?.let { require(runCatching { LightClass.valueOf(it) }.isSuccess) { "bad light class for place ${p.id}" } }
        }
        val plantIds = e.plants.map { it.id }.toSet()
        for (p in e.plants) {
            require(p.placeId in placeIds) { "plant ${p.id} refers to a missing place" }
            require(p.canopyWidthCm > 0 && p.potVolumeL > 0) { "plant ${p.id} has a non-positive size" }
            require(runCatching { PotShape.valueOf(p.potShape); PotMaterial.valueOf(p.potMaterial) }.isSuccess) { "bad pot for plant ${p.id}" }
            date(p.acquiredOn, "plant ${p.id}")
            p.lastRepotOn?.let { date(it, "plant ${p.id}") }
        }
        for (ev in e.events) {
            require(ev.plantId in plantIds) { "event ${ev.id} refers to a missing plant" }
            date(ev.date, "event ${ev.id}")
            require(ev.type in setOf("WATERED", "CHECK_IN", "MOVED", "REPOTTED", "SNOOZED")) { "unknown event type ${ev.type}" }
            if (ev.type == "CHECK_IN") require(ev.moisture != null) { "check-in ${ev.id} has no answer" }
            ev.moisture?.let { require(runCatching { Moisture.valueOf(it) }.isSuccess) { "bad answer in event ${ev.id}" } }
            ev.snoozedUntil?.let { date(it, "event ${ev.id}") }
        }
        for (w in e.weather) {
            require(w.placeId in placeIds) { "weather refers to a missing place" }
            date(w.date, "weather")
        }
        for (c in e.calibration) require(c.plantId in plantIds && c.grams > 0) { "bad calibration reading ${c.id}" }
    }

    suspend fun calibrationCsv(): String = buildString {
        appendLine("plant_id,plant_name,timestamp_utc,grams,note")
        val names = dao.allPlants().associate { it.id to it.name }
        for (c in dao.allCalibration()) {
            appendLine(listOf(c.plantId.toString(), csv(names[c.plantId] ?: ""), Instant.fromEpochMilliseconds(c.atEpochMs).toString(), c.grams.toString(), csv(c.note)).joinToString(","))
        }
    }

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

@Serializable
data class Export(
    val format: String,
    val version: Int,
    val appVersion: String,
    val exportedAt: String,
    val places: List<PlaceEntity>,
    val plants: List<PlantEntity>,
    val events: List<EventEntity>,
    val weather: List<WeatherDayEntity>,
    val calibration: List<CalibrationEntity>,
) {
    companion object {
        const val FORMAT = "overgrown-export"
        const val VERSION = 1
    }
}

/** Weather client cache in the database. */
class DbResponseStore(private val dao: GardenDao) : ResponseStore {
    override suspend fun load(key: String) = dao.response(key)?.let {
        CachedResponse(
            body = it.body,
            fetchedAt = Instant.fromEpochMilliseconds(it.fetchedAtEpochMs),
            expires = it.expiresEpochMs?.let(Instant::fromEpochMilliseconds),
            lastModified = it.lastModified,
            notBefore = it.notBeforeEpochMs?.let(Instant::fromEpochMilliseconds),
            consecutiveFailures = it.consecutiveFailures,
        )
    }

    override suspend fun save(key: String, value: CachedResponse) = dao.upsertResponse(
        ResponseEntity(
            key = key, body = value.body,
            fetchedAtEpochMs = value.fetchedAt.toEpochMilliseconds().coerceAtLeast(0),
            expiresEpochMs = value.expires?.toEpochMilliseconds(),
            lastModified = value.lastModified,
            notBeforeEpochMs = value.notBefore?.toEpochMilliseconds(),
            consecutiveFailures = value.consecutiveFailures,
        ),
    )
}

/** Settings in DataStore. */
class Settings(private val store: DataStore<Preferences>) : FallbackState {
    private val fallbackKey = booleanPreferencesKey("weather_fallback")
    private val failuresKey = intPreferencesKey("weather_failures")
    private val installSeedKey = longPreferencesKey("install_seed")
    private val developerKey = booleanPreferencesKey("developer_mode")

    override suspend fun useFallback() = store.data.first()[fallbackKey] ?: false
    override suspend fun setFallback(on: Boolean) { store.edit { it[fallbackKey] = on } }
    override suspend fun failures() = store.data.first()[failuresKey] ?: 0
    override suspend fun setFailures(n: Int) { store.edit { it[failuresKey] = n } }

    /** Drawn once per install, so each install refreshes at its own time of day. */
    suspend fun installSeed(draw: () -> Long): Long {
        store.data.first()[installSeedKey]?.let { return it }
        val seed = draw()
        store.edit { it[installSeedKey] = seed }
        return seed
    }

    val developerMode: Flow<Boolean> get() = store.data.map { it[developerKey] ?: false }
    suspend fun setDeveloperMode(on: Boolean) { store.edit { it[developerKey] = on } }
}
