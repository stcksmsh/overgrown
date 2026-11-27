// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.engine.Coefficients
import com.kostavukicevic.plants.engine.Engine
import com.kostavukicevic.plants.engine.LightClass
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.engine.PotMaterial
import com.kostavukicevic.plants.engine.PotShape
import com.kostavukicevic.plants.engine.RainExposure
import com.kostavukicevic.plants.engine.WeatherQuality
import com.kostavukicevic.plants.engine.WindExposure
import com.kostavukicevic.plants.weather.CachedResponse
import com.kostavukicevic.plants.weather.Climatology
import com.kostavukicevic.plants.weather.Coordinates
import com.kostavukicevic.plants.weather.DailyWeather
import com.kostavukicevic.plants.weather.FallbackState
import com.kostavukicevic.plants.weather.FetchResult
import com.kostavukicevic.plants.weather.OpenMeteo
import com.kostavukicevic.plants.weather.ResponseStore
import com.kostavukicevic.plants.weather.Series
import com.kostavukicevic.plants.weather.WeatherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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

class EventDateFuture(date: LocalDate) : IllegalArgumentException("date is in the future: $date")
class EventDateBeforeAcquired(date: LocalDate, val acquiredOn: LocalDate) :
    IllegalArgumentException("date $date is before the plant was acquired on $acquiredOn")

/** One day of the plant screen's strip; [waterLeft] is 0..1 at the start of the day. */
data class OutlookDay(val date: LocalDate, val waterLeft: Double, val frost: Boolean, val rain: Boolean, val heat: Boolean)

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
    /** Every provider whose stored days these places use: fallback is per place, and a place falls back mid-range. */
    suspend fun providers(placeIds: List<Long>): List<String> = dao.providers(placeIds)

    suspend fun place(id: Long) = dao.place(id)
    suspend fun plant(id: Long) = dao.plant(id)
    suspend fun savePlace(p: PlaceEntity) = dao.upsertPlace(p).let { if (p.id != 0L) p.id else it }
    /** Records the day a plant is added and the day its pot really changes, for the planner's repot advice. */
    suspend fun savePlant(p: PlantEntity): Long {
        val stored = if (p.id != 0L) dao.plant(p.id) else null
        val day = today().toString()
        val saved = when {
            stored == null -> p.copy(addedOn = day)
            Planner.pot(stored) != Planner.pot(p) -> p.copy(potChangedOn = day)
            else -> p
        }
        return dao.upsertPlant(saved).let { if (p.id != 0L) p.id else it }
    }
    suspend fun deletePlant(id: Long) {
        val photos = dao.photosOf(id)
        dao.deletePlantFully(id)
        deleteFiles(photos.map { it.file })
    }

    /** Deletes an empty place; false, and nothing changed, when a plant is there. Checked in the delete's own transaction, so a plant added meanwhile is not lost. */
    suspend fun deletePlace(id: Long): Boolean = db.useWriterConnection { tx ->
        tx.immediateTransaction {
            if (dao.plantIdsAt(id).isNotEmpty()) false else { dao.deletePlaceFully(id); true }
        }
    }
    suspend fun deleteEvent(id: Long) {
        val stored = dao.event(id)
        dao.deleteEvent(id)
        if (stored?.type == "REPOTTED") syncLastRepot(stored.plantId)
    }

    /**
     * Changes a logged event's date, answer and drained flag; its plant, type and snooze end stay.
     * Refuses a future date, a date before the plant was acquired (the planner starts there and would
     * drop the entry), a check-in without an answer and an answer the planner could not parse, as
     * import does. Throws [EventDateFuture], [EventDateBeforeAcquired] or IllegalArgumentException.
     */
    suspend fun updateEvent(edited: EventEntity) {
        val stored = requireNotNull(dao.event(edited.id)) { "no event ${edited.id}" }
        val plant = requireNotNull(dao.plant(stored.plantId)) { "no plant ${stored.plantId}" }
        // Both parse calls throw IllegalArgumentException on a bad value.
        val date = LocalDate.parse(edited.date)
        if (date > today()) throw EventDateFuture(date)
        val acquiredOn = LocalDate.parse(plant.acquiredOn)
        if (date < acquiredOn) throw EventDateBeforeAcquired(date, acquiredOn)
        if (stored.type == "CHECK_IN") require(edited.moisture != null) { "a check-in needs an answer" }
        edited.moisture?.let(Moisture::valueOf)
        dao.updateEvent(stored.copy(date = edited.date, moisture = edited.moisture, ranOutOfBottom = edited.ranOutOfBottom))
        if (stored.type == "REPOTTED") syncLastRepot(stored.plantId)
    }

    /** Keeps the plant's last repot on its latest repot entry, or none, so the reminder follows the history. */
    private suspend fun syncLastRepot(plantId: Long) {
        val plant = dao.plant(plantId) ?: return
        val last = dao.events(plantId).lastOrNull { it.type == "REPOTTED" }?.date
        dao.upsertPlant(plant.copy(lastRepotOn = last))
    }

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

    // NonCancellable: a rollback or cleanup that stops halfway leaves orphan files or rows without files.
    private suspend fun deleteFiles(names: Collection<String>) = withContext(NonCancellable + Dispatchers.IO) {
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

    /**
     * The next [Planner.FORECAST_DAYS] days from today if nothing more is logged: water left at the
     * start of each day, and frost, rain and heat marks from a fetched forecast only, so none
     * indoors or on days filled from climatology.
     */
    suspend fun outlook(status: PlantStatus): List<OutlookDay> {
        val today = today()
        val end = today.plus(DatePeriod(days = Planner.FORECAST_DAYS - 1))
        val place = status.place
        val days = if (place.outdoor) {
            val stored = dao.weather(place.id).associate { LocalDate.parse(it.date) to Planner.daily(it) }
            Series.build(stored, from = today, to = end, today = today, climatology = Planner.climatology(dao.climatology(place.id)))
        } else {
            Planner.indoorDays(today, end)
        }
        var state = status.state
        return days.map { day ->
            val forecast = place.outdoor && day.quality == WeatherQuality.FORECAST
            OutlookDay(
                date = day.date,
                waterLeft = 1 - state.depletion,
                frost = forecast && day.tMinC <= Coefficients.FROST_HOLD_MIN_C,
                rain = forecast && day.precipitationMm >= Coefficients.RAIN_MIN_MM,
                heat = forecast && day.tMaxC >= Coefficients.HEAT_ALERT_MAX_C,
            ).also { state = Engine.advance(status.engine, state, day) }
        }
    }

    /** Today's alert events, one per weather event, without those the person marked Done or dismissed today. */
    suspend fun alertEvents(): List<AlertEvent> = alertEvents(statuses())

    /** [alertEvents] for statuses the caller already has. */
    suspend fun alertEvents(statuses: List<PlantStatus>): List<AlertEvent> {
        val day = today()
        var visible = emptyList<AlertEvent>()
        settings.updateAlertAcks { acks -> applyAcks(alertEvents(statuses, day), acks, day).also { visible = it.first }.second }
        return visible
    }

    /** "I protected the plants": hidden for the event's whole range unless it gets worse or reaches another plant. */
    suspend fun done(event: AlertEvent) = settings.updateAlertAcks { acked(it, event, dismissedOn = null) }

    /** "Not now": hidden for today. */
    suspend fun dismiss(event: AlertEvent) = settings.updateAlertAcks { acked(it, event, dismissedOn = today()) }

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
    suspend fun exportZip(appVersion: String, out: OutputStream) = out.use {
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
        if (!zip) return importJson(withContext(Dispatchers.IO) { readBounded(source, MAX_JSON_BYTES).decodeToString() })

        val staging = File(photoDir.path + ".import")
        try {
            val text = withContext(Dispatchers.IO) {
                staging.deleteRecursively()
                staging.mkdirs()
                var text: String? = null
                var entries = 0
                var total = 0L
                ZipInputStream(source).use { z ->
                    while (true) {
                        val entry = z.nextEntry ?: break
                        val name = entry.name
                        require(++entries <= MAX_ZIP_ENTRIES) { "too many entries in the file" }
                        require(safeEntry(name)) { "unsafe path in the file: $name" }
                        when {
                            name == Export.ZIP_JSON -> text = readBounded(z, MAX_JSON_BYTES).also { total += it.size }.decodeToString()
                            entry.isDirectory -> Unit
                            name.startsWith(Export.ZIP_PHOTOS) -> {
                                val file = name.removePrefix(Export.ZIP_PHOTOS)
                                require(SAFE_NAME.matches(file)) { "unexpected entry in the file: $name" }
                                total += File(staging, file).outputStream().use { copyBounded(z, it, MAX_PHOTO_BYTES) }
                            }
                        }
                        require(total <= MAX_ZIP_TOTAL_BYTES) { "the file is too large" }
                    }
                }
                text ?: throw IllegalArgumentException("no ${Export.ZIP_JSON} in the file")
            }
            return replace(parse(text), staging)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { staging.deleteRecursively() }
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
        // Files this import created, removed again if anything fails before the database accepts it.
        val copied = mutableListOf<String>()
        try {
            withContext(Dispatchers.IO) {
                photoDir.mkdirs()
                // Checked up front so a full disk refuses the import instead of failing halfway through the copies.
                val needed = photos.sumOf { File(staging, it.file).length() }
                if (needed + FREE_SPACE_MARGIN_BYTES > photoDir.usableSpace) throw java.io.IOException("not enough free space for the photos")
                for (p in photos) {
                    // Names are random, so a live file with this name is this same photo: never overwrite it.
                    if (p.file in old && File(photoDir, p.file).isFile) continue
                    copied += p.file
                    File(staging, p.file).copyTo(File(photoDir, p.file), overwrite = true)
                }
            }
            dao.replaceAll(parsed.places, parsed.plants, parsed.events, parsed.weather, parsed.calibration, photos)
        } catch (t: Throwable) {
            deleteFiles(copied)
            throw t
        }
        deleteFiles(old - photos.map { it.file }.toSet())
        return missing.size
    }

    /** Reads at most [limit] bytes; a larger stream refuses the file instead of filling memory. */
    private fun readBounded(input: InputStream, limit: Long): ByteArray =
        java.io.ByteArrayOutputStream().also { copyBounded(input, it, limit) }.toByteArray()

    private fun copyBounded(input: InputStream, out: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return copied
            copied += n
            require(copied <= limit) { "an entry in the file is too large" }
            out.write(buffer, 0, n)
        }
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
            p.addedOn?.let { date(it, "plant ${p.id}") }
            p.potChangedOn?.let { date(it, "plant ${p.id}") }
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

/*
 * Import limits, far above any real garden (stored photos are at most about 4 MB, garden.json a few MB after
 * years of use) but low enough that a damaged or hostile file is refused before it fills memory or storage.
 * Sizes count the bytes actually inflated, not what the zip claims.
 */
internal const val MAX_JSON_BYTES = 32L * 1024 * 1024
internal const val MAX_PHOTO_BYTES = 32L * 1024 * 1024
internal const val MAX_ZIP_TOTAL_BYTES = 1L * 1024 * 1024 * 1024

/** Left free after an import's photo copies, so the database and the rest of the phone can still write. */
private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024
internal const val MAX_ZIP_ENTRIES = 20_000

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
    private val alertAcksKey = stringPreferencesKey("alert_acks")
    private val reminderMinuteKey = intPreferencesKey("reminder_minute")
    private val themeKey = stringPreferencesKey("theme")
    private val weighingKey = booleanPreferencesKey("weighing")
    private val borisVoiceKey = booleanPreferencesKey("boris_voice")
    private val acksSerializer = ListSerializer(AlertAck.serializer())

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

    /** The one daily time for watering prompts and reminders. Stored as minutes after midnight; a bad value reads as the default. */
    val reminderTime: Flow<LocalTime> get() = store.data.map { p ->
        p[reminderMinuteKey]?.takeIf { it in 0 until MINUTES_PER_DAY }?.let { LocalTime(it / 60, it % 60) } ?: DEFAULT_REMINDER_TIME
    }
    suspend fun setReminderTime(t: LocalTime) { store.edit { it[reminderMinuteKey] = t.hour * 60 + t.minute } }

    val theme: Flow<ThemeChoice> get() = store.data.map { p -> ThemeChoice.entries.find { it.name == p[themeKey] } ?: ThemeChoice.SYSTEM }
    suspend fun setTheme(t: ThemeChoice) { store.edit { it[themeKey] = t.name } }

    /** Pot weighing on the plant screen. Until it is set, it follows developer mode, which showed weighing before. */
    val weighing: Flow<Boolean> get() = store.data.map { it[weighingKey] ?: it[developerKey] ?: false }
    suspend fun setWeighing(on: Boolean) { store.edit { it[weighingKey] = on } }

    /** Boris's voice for the lines that have one; off is plain everywhere. On until the person turns it off. */
    val borisVoice: Flow<Boolean> get() = store.data.map { it[borisVoiceKey] ?: true }
    suspend fun setBorisVoice(on: Boolean) { store.edit { it[borisVoiceKey] = on } }

    /** Done and Dismiss on alert events, as JSON. An unreadable value is treated as none, so alerts show again. */
    suspend fun alertAcks(): List<AlertAck> = decodeAcks(store.data.first()[alertAcksKey])

    /** Read, change and write in one DataStore transaction; writes only when something changed. */
    suspend fun updateAlertAcks(change: (List<AlertAck>) -> List<AlertAck>) {
        store.edit { prefs ->
            val old = decodeAcks(prefs[alertAcksKey])
            val new = change(old)
            if (new == old) return@edit
            if (new.isEmpty()) prefs.remove(alertAcksKey) else prefs[alertAcksKey] = Json.encodeToString(acksSerializer, new)
        }
    }

    private fun decodeAcks(text: String?): List<AlertAck> =
        text?.let { runCatching { Json.decodeFromString(acksSerializer, it) }.getOrNull() } ?: emptyList()

    companion object {
        /** The owner's default (docs/DECISIONS.md D39). */
        val DEFAULT_REMINDER_TIME = LocalTime(8, 0)
        private const val MINUTES_PER_DAY = 24 * 60
    }
}

/** The app theme: the system's light or dark, or one of them always. */
enum class ThemeChoice { SYSTEM, LIGHT, DARK }
