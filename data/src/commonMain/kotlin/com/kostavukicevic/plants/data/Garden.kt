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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
    /** App-private directory holding the photo files; excluded from Auto Backup. */
    private val photoDir: File,
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
    suspend fun deletePlant(id: Long) {
        val photos = dao.photosOf(id)
        dao.deletePlantFully(id)
        deleteFiles(photos.map { it.file })
    }

    suspend fun deletePlace(id: Long) {
        val photos = dao.plantIdsAt(id).flatMap { dao.photosOf(it) }
        dao.deletePlaceFully(id)
        deleteFiles(photos.map { it.file })
    }
    suspend fun deleteEvent(id: Long) = dao.deleteEvent(id)

    suspend fun watered(plantId: Long, ranOutOfBottom: Boolean, moisture: String? = null) =
        dao.insertEvent(EventEntity(plantId = plantId, date = today().toString(), type = "WATERED", ranOutOfBottom = ranOutOfBottom, moisture = moisture))

    suspend fun checkIn(plantId: Long, moisture: String) =
        dao.insertEvent(EventEntity(plantId = plantId, date = today().toString(), type = "CHECK_IN", moisture = moisture))

    suspend fun snooze(plantId: Long, days: Int = 1) =
        dao.insertEvent(EventEntity(plantId = plantId, date = today().toString(), type = "SNOOZED", snoozedUntil = today().plus(DatePeriod(days = days)).toString()))

    suspend fun moved(plantId: Long) = dao.insertEvent(EventEntity(plantId = plantId, date = today().toString(), type = "MOVED"))

    suspend fun fed(plantId: Long) = dao.insertEvent(EventEntity(plantId = plantId, date = today().toString(), type = "FED"))

    suspend fun repotted(plantId: Long) {
        dao.insertEvent(EventEntity(plantId = plantId, date = today().toString(), type = "REPOTTED"))
        dao.plant(plantId)?.let { dao.upsertPlant(it.copy(lastRepotOn = today().toString())) }
    }

    fun photos(plantId: Long) = dao.photosFlow(plantId)
    fun photoFile(photo: PhotoEntity) = File(photoDir, photo.file)

    /** Stores the image bytes as they are, under a new name, and records them for the plant on the given day. */
    suspend fun addPhoto(plantId: Long, bytes: ByteArray, date: LocalDate = today()): Long {
        require(bytes.isNotEmpty()) { "empty photo" }
        val name = UUID.randomUUID().toString() + extension(bytes)
        val file = File(photoDir, name)
        withContext(Dispatchers.IO) {
            photoDir.mkdirs()
            file.writeBytes(bytes)
        }
        return try {
            dao.insertPhoto(PhotoEntity(plantId = plantId, date = date.toString(), file = name))
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { file.delete() }
            throw e
        }
    }

    suspend fun deletePhoto(id: Long) {
        val photo = dao.photo(id) ?: return
        dao.deletePhoto(id)
        deleteFiles(listOf(photo.file))
    }

    private suspend fun deleteFiles(names: Collection<String>) = withContext(Dispatchers.IO) {
        for (name in names) File(photoDir, name).delete()
    }

    private fun extension(b: ByteArray) = when {
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> ".jpg"
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> ".png"
        else -> ""
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
        // Indoor advice needs no weather: an indoor-only garden makes no request.
        for (place in dao.allPlaces().filter { it.outdoor }) {
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
            weather = dao.allWeather(), calibration = dao.allCalibration(), photos = dao.allPhotos(),
        ),
    )

    /**
     * The full export: a zip with [Export.ZIP_JSON] and every photo file under [Export.ZIP_PHOTOS].
     * Closes [out]. A photo whose file is gone is still listed in the JSON; import skips it.
     */
    suspend fun exportZip(appVersion: String, out: OutputStream) {
        val text = exportJson(appVersion)
        val photos = dao.allPhotos()
        withContext(Dispatchers.IO) {
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(Export.ZIP_JSON))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
                for (p in photos) {
                    val f = File(photoDir, p.file)
                    if (!f.isFile) continue
                    zip.putNextEntry(ZipEntry(Export.ZIP_PHOTOS + p.file))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Replaces everything with a zip export or a plain JSON export, in one transaction: a bad file changes nothing.
     * Returns how many photos were left out because the file did not contain their image.
     */
    suspend fun import(input: InputStream): Int {
        val source = BufferedInputStream(input)
        val zip = withContext(Dispatchers.IO) {
            source.mark(2)
            (source.read() == 'P'.code && source.read() == 'K'.code).also { source.reset() }
        }
        if (!zip) return importJson(withContext(Dispatchers.IO) { source.readBytes().decodeToString() })

        val staging = File(photoDir.path + ".import")
        try {
            val text = withContext(Dispatchers.IO) {
                staging.deleteRecursively()
                staging.mkdirs()
                var text: String? = null
                ZipInputStream(source).use { z ->
                    while (true) {
                        val entry = z.nextEntry ?: break
                        val name = entry.name
                        require(safeEntry(name)) { "unsafe path in the file: $name" }
                        when {
                            name == Export.ZIP_JSON -> text = z.readBytes().decodeToString()
                            entry.isDirectory -> Unit
                            name.startsWith(Export.ZIP_PHOTOS) -> {
                                val file = name.removePrefix(Export.ZIP_PHOTOS)
                                require(SAFE_NAME.matches(file)) { "unexpected entry in the file: $name" }
                                File(staging, file).outputStream().use { z.copyTo(it) }
                            }
                        }
                    }
                }
                text ?: throw IllegalArgumentException("no ${Export.ZIP_JSON} in the file")
            }
            return replace(parse(text), staging)
        } finally {
            withContext(Dispatchers.IO) { staging.deleteRecursively() }
        }
    }

    /** A plain JSON export. Its photos have no image here, so none is kept. */
    suspend fun importJson(text: String): Int = replace(parse(text), null)

    private fun parse(text: String): Export {
        val e = json.decodeFromString(Export.serializer(), text)
        require(e.format == Export.FORMAT) { "not an Overgrown export" }
        require(e.version <= Export.VERSION) { "export version ${e.version} is newer than this app" }
        validate(e) // before anything is replaced: a bad file changes nothing
        return e
    }

    /**
     * New photo files are copied in first and removed again if the database refuses the import;
     * the old ones are deleted only after it succeeded. Returns how many photos had no image in [staging].
     */
    private suspend fun replace(parsed: Export, staging: File?): Int {
        val (photos, missing) = parsed.photos.partition { staging != null && File(staging, it.file).isFile }
        val old = dao.allPhotos().map { it.file }.toSet()
        val copied = withContext(Dispatchers.IO) {
            photoDir.mkdirs()
            // A name already in use is the same photo (names are random), exported from this garden.
            for (p in photos) File(staging, p.file).copyTo(File(photoDir, p.file), overwrite = true)
            photos.map { it.file }.filter { it !in old }
        }
        try {
            dao.replaceAll(parsed.places, parsed.plants, parsed.events, parsed.weather, parsed.calibration, photos)
        } catch (t: Throwable) {
            deleteFiles(copied)
            throw t
        }
        deleteFiles(old - photos.map { it.file }.toSet())
        return missing.size
    }

    /** Zip slip: an entry that could land outside the staging directory refuses the whole file. */
    private fun safeEntry(name: String) =
        !name.startsWith("/") && !name.startsWith("\\") && !(name.length > 1 && name[1] == ':') &&
            name.split('/', '\\').none { it == ".." }

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
            require(ev.type in setOf("WATERED", "CHECK_IN", "MOVED", "REPOTTED", "SNOOZED", "FED")) { "unknown event type ${ev.type}" }
            if (ev.type == "CHECK_IN") require(ev.moisture != null) { "check-in ${ev.id} has no answer" }
            ev.moisture?.let { require(runCatching { Moisture.valueOf(it) }.isSuccess) { "bad answer in event ${ev.id}" } }
            ev.snoozedUntil?.let { date(it, "event ${ev.id}") }
        }
        for (w in e.weather) {
            require(w.placeId in placeIds) { "weather refers to a missing place" }
            date(w.date, "weather")
        }
        for (c in e.calibration) require(c.plantId in plantIds && c.grams > 0) { "bad calibration reading ${c.id}" }
        for (p in e.photos) {
            require(p.plantId in plantIds) { "photo ${p.id} refers to a missing plant" }
            date(p.date, "photo ${p.id}")
            require(SAFE_NAME.matches(p.file)) { "bad file name for photo ${p.id}" }
        }
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
    /** Absent in version 1 exports. */
    val photos: List<PhotoEntity> = emptyList(),
) {
    companion object {
        const val FORMAT = "overgrown-export"
        /** 2 added photos. */
        const val VERSION = 2
        /** Zip layout: the JSON at the root, photo files in a folder beside it. */
        const val ZIP_JSON = "garden.json"
        const val ZIP_PHOTOS = "photos/"
    }
}

/** A plain file name: letters, digits, dot, dash, underscore, not starting with a dot. */
private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

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
