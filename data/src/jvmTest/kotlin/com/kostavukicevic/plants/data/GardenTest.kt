// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kostavukicevic.plants.caredb.Archetype
import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.caredb.CareGroups
import com.kostavukicevic.plants.caredb.Season
import com.kostavukicevic.plants.caredb.Species
import com.kostavukicevic.plants.caredb.Taxon
import com.kostavukicevic.plants.caredb.Toxicity
import com.kostavukicevic.plants.caredb.Water
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.Place
import com.kostavukicevic.plants.engine.WaterStrategy
import com.kostavukicevic.plants.weather.HttpResponse
import com.kostavukicevic.plants.weather.HttpTransport
import com.kostavukicevic.plants.weather.InMemoryResponseStore
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.OpenMeteo
import com.kostavukicevic.plants.weather.WeatherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class GardenTest {

    private val dir = Files.createTempDirectory("garden-test")
    private val dbs = ArrayList<GardenDb>()
    private var clock = Instant.parse("2026-11-23T08:00:00Z")
    private val today = LocalDate(2026, 11, 23)

    @AfterTest fun close() { dbs.forEach { it.close() } }

    private fun photoDir(name: String): File = dir.resolve("$name-photos").toFile()

    private val noNetwork = HttpTransport { _, _ -> HttpResponse(503, emptyMap(), "") }

    private fun garden(name: String, careDb: CareDb? = null, http: HttpTransport = noNetwork): Garden {
        val db = Room.inMemoryDatabaseBuilder<GardenDb>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build().also { dbs += it }
        val settings = Settings(PreferenceDataStoreFactory.createWithPath { dir.resolve("$name.preferences_pb").toOkioPath() })
        val om = OpenMeteo(http, InMemoryResponseStore(), { clock })
        val met = MetNorway(http, InMemoryResponseStore(), { clock }, "test", TimeZone.UTC)
        return Garden(db, settings, WeatherService(om, met, settings), om, { careDb }, { clock }, { TimeZone.UTC }, photoDir(name))
    }

    private suspend fun Garden.seed(): Long {
        val placeId = savePlace(PlaceEntity(name = "Balcony", outdoor = true, latitude = 44.8167, longitude = 20.4589, rainExposure = "PARTIAL"))
        val plantId = savePlant(
            PlantEntity(
                name = "Cypress", placeId = placeId, archetypeId = "hardy-potted-conifer", canopyWidthCm = 35.0,
                potVolumeL = 3.0, potMaterial = "PLASTIC", inNurseryPot = true, acquiredOn = today.plus(DatePeriod(days = -10)).toString(),
            ),
        )
        return plantId
    }

    @Test
    fun exportAndImportRoundTrip() = runTest {
        val a = garden("a")
        val plantId = a.seed()
        a.watered(plantId, ranOutOfBottom = true)
        a.checkIn(plantId, "OK")
        a.logMass(plantId, 2310.5, note = "after watering, drained an hour")
        val exported = a.exportJson("0.1.0")

        val b = garden("b")
        b.importJson(exported)
        assertEquals(exported, b.exportJson("0.1.0"))
        assertEquals("Cypress", b.plant(plantId)?.name)
    }

    private fun indoor(light: String, dryAir: Boolean = false, lux: Double? = null) =
        PlaceEntity(id = 1, name = "Living room", outdoor = false, latitude = 44.8167, longitude = 20.4589, lightClass = light, lux = lux, dryAir = dryAir)

    private val fern = PlantEntity(
        id = 1, name = "Fern", placeId = 1, canopyWidthCm = 30.0, potVolumeL = 10.0,
        acquiredOn = today.plus(DatePeriod(days = -3)).toString(),
    )

    private fun depletion(place: PlaceEntity) =
        Planner.status(fern, place, emptyList(), emptyList(), emptyList(), null, today).state.depletion

    @Test
    fun aQuickDryLeadsToTheSameActionAsDry() {
        fun after(answer: String) = Planner.status(
            fern, indoor("MEDIUM"),
            listOf(EventEntity(id = 1, plantId = 1, date = today.plus(DatePeriod(days = -1)).toString(), type = "CHECK_IN", moisture = answer)),
            emptyList(), emptyList(), null, today,
        ).today
        val dry = after("DRY")
        assertTrue(dry is Action.WaterNow || dry is Action.CheckSoil, "after Dry: $dry")
        assertEquals(dry::class, after("DRY_OR_BONE_DRY")::class)
        assertEquals(Action.None::class, after("OK")::class)
    }

    @Test
    fun indoorUseFollowsLightAndDryAirWithoutWeather() {
        val s = Planner.status(fern, indoor("MEDIUM"), emptyList(), emptyList(), emptyList(), null, today)
        assertIs<Place.Indoor>(s.engine.place)
        // Without weather an outdoor plant would not advance at all; indoors it uses water every day.
        assertTrue(s.state.depletion > 0.0, "depletion ${s.state.depletion}")
        assertEquals(0.0, depletion(indoor("MEDIUM").copy(outdoor = true)))

        val low = depletion(indoor("LOW"))
        val high = depletion(indoor("HIGH"))
        assertTrue(high > low && high < 1.0, "low $low, high $high")
        assertTrue(depletion(indoor("MEDIUM", dryAir = true)) > depletion(indoor("MEDIUM")))
    }

    @Test
    fun anIndoorPlaceUsesTheSpeciesIndoorCare() {
        // Test fixture, not care data: only the per-context blocks differ.
        val db = CareDb(
            schemaVersion = CareDb.SUPPORTED_SCHEMA, dataVersion = 1, sourceCommit = "test",
            species = mapOf(
                "test-species" to Species(
                    archetype = "test", taxon = Taxon(acceptedName = "Testus testus", rank = "species"),
                    outdoorContainer = CareGroups(water = Water(waterStrategy = "evenly_moist")),
                    indoor = CareGroups(water = Water(waterStrategy = "dry_out_fully")),
                    toxicity = Toxicity(),
                ),
            ),
        )
        val plant = fern.copy(speciesSlug = "test-species")
        val inside = Planner.status(plant, indoor("MEDIUM"), emptyList(), emptyList(), emptyList(), db, today)
        assertEquals(CareSource.SPECIES, inside.careSource)
        assertEquals(WaterStrategy.DRY_OUT_FULLY, inside.engine.profile.waterStrategy)
        val outside = Planner.status(plant, indoor("MEDIUM").copy(outdoor = true), emptyList(), emptyList(), emptyList(), db, today)
        assertEquals(WaterStrategy.EVENLY_MOIST, outside.engine.profile.waterStrategy)
    }

    @Test
    fun exportAndImportKeepAnIndoorPlace() = runTest {
        val a = garden("indoor-a")
        val placeId = a.savePlace(indoor("HIGH", dryAir = true, lux = 6200.0).copy(id = 0))
        val b = garden("indoor-b")
        b.importJson(a.exportJson("x"))
        val p = b.place(placeId)!!
        assertEquals(false, p.outdoor)
        assertEquals("HIGH", p.lightClass)
        assertEquals(6200.0, p.lux)
        assertEquals(true, p.dryAir)
    }

    @Test
    fun indoorOnlyGardenFetchesNoWeather() = runTest {
        var requests = 0
        val counting = HttpTransport { _, _ -> requests++; HttpResponse(503, emptyMap(), "") }
        val g = garden("indoor-only", http = counting)
        g.savePlace(indoor("MEDIUM").copy(id = 0))
        assertTrue(g.refreshWeather(foreground = false).isEmpty())
        assertTrue(g.refreshWeather(foreground = true).isEmpty())
        assertEquals(0, requests)

        // The same garden with an outdoor place does ask, so the count above means something.
        g.savePlace(PlaceEntity(name = "Balcony", outdoor = true, latitude = 44.8167, longitude = 20.4589))
        g.refreshWeather(foreground = true)
        assertTrue(requests > 0)
    }

    @Test
    fun importRefusesSomethingElse() = runTest {
        val e = runCatching { garden("c").importJson("""{"format":"other","version":1,"appVersion":"x","exportedAt":"x","places":[],"plants":[],"events":[],"weather":[],"calibration":[]}""") }
        assertIs<IllegalArgumentException>(e.exceptionOrNull())
    }

    @Test
    fun theEngineReplaysStoredWeatherAndEvents() = runTest {
        val g = garden("d")
        val plantId = g.seed()
        // No weather stored: nothing to recommend, nothing invented.
        assertEquals(Action.None, g.status(plantId)!!.today)

        // A warm dry fortnight of stored days and forecast.
        val placeId = g.plant(plantId)!!.placeId
        val dao = dbs.last().dao()
        val days = (-10..9).map { offset ->
            WeatherDayEntity(placeId, today.plus(DatePeriod(days = offset)).toString(), 14.0, 28.0, 0.0, 2.0, 50.0, 4.5, "OPEN_METEO", clock.toEpochMilliseconds())
        }
        dao.upsertWeather(days)
        val dry = g.status(plantId)!!
        assertTrue(dry.state.depletion > 0.5, "depletion ${dry.state.depletion}")
        assertTrue(dry.today is Action.CheckSoil || dry.today is Action.WaterNow || dry.today == Action.RepotOrWaterDaily, "${dry.today}")

        g.watered(plantId, ranOutOfBottom = true)
        assertEquals(0.0, g.status(plantId)!!.state.depletion)
    }

    @Test
    fun eachPlaceCreditsItsOwnProviders() = runTest {
        val g = garden("credit")
        val a = g.savePlace(PlaceEntity(name = "A", outdoor = true, latitude = 44.8, longitude = 20.5))
        val b = g.savePlace(PlaceEntity(name = "B", outdoor = true, latitude = 59.9, longitude = 10.7))
        fun day(place: Long, offset: Int, provider: String, at: Long) =
            WeatherDayEntity(place, today.plus(DatePeriod(days = offset)).toString(), 4.0, 11.0, 0.0, provider = provider, fetchedAtEpochMs = at)
        val dao = dbs.last().dao()
        dao.upsertWeather(listOf(day(a, 0, "OPEN_METEO", 1_000)))
        // B fell back to MET Norway later: the newest row anywhere is not A's provider.
        dao.upsertWeather(listOf(day(b, 0, "MET_NORWAY", 2_000)))

        assertEquals(listOf("OPEN_METEO"), g.providers(listOf(a)))
        assertEquals(listOf("MET_NORWAY", "OPEN_METEO"), g.providers(listOf(a, b)))
        // A place whose range fell back part way has days from both.
        dao.upsertWeather(listOf(day(a, 1, "MET_NORWAY", 3_000)))
        assertEquals(listOf("MET_NORWAY", "OPEN_METEO"), g.providers(listOf(a)))
        assertEquals(emptyList(), g.providers(listOf(g.savePlace(PlaceEntity(name = "C", outdoor = true, latitude = 0.0, longitude = 0.0)))))
    }

    @Test
    fun snoozeHidesTodaysPrompt() = runTest {
        val g = garden("e")
        val plantId = g.seed()
        val placeId = g.plant(plantId)!!.placeId
        dbs.last().dao().upsertWeather((-10..9).map { offset ->
            WeatherDayEntity(placeId, today.plus(DatePeriod(days = offset)).toString(), 14.0, 28.0, 0.0, 2.0, 50.0, 4.5, "OPEN_METEO", 0)
        })
        g.snooze(plantId)
        val s = g.status(plantId)!!
        assertEquals(Action.None, s.today)
        assertEquals(today.plus(DatePeriod(days = 1)), s.snoozedUntil)
        assertEquals(today.plus(DatePeriod(days = 1)), s.nextPrompt?.first)

        // The next day the prompt is back: a snooze lasts one day.
        clock += kotlin.time.Duration.parse("24h")
        val next = g.status(plantId)!!
        assertNull(next.snoozedUntil)
        assertTrue(next.today != Action.None)
    }

    @Test
    fun wateringEndsASnooze() = runTest {
        val g = garden("w")
        val plantId = g.seed()
        g.snooze(plantId)
        g.watered(plantId, ranOutOfBottom = true)
        assertNull(g.status(plantId)!!.snoozedUntil)
    }

    @Test
    fun aBadImportChangesNothing() = runTest {
        val g = garden("h")
        val plantId = g.seed()
        val before = g.exportJson("x")
        val broken = before.replace("\"acquiredOn\": \"", "\"acquiredOn\": \"not-a-date")
        assertIs<IllegalArgumentException>(runCatching { g.importJson(broken) }.exceptionOrNull())
        assertEquals(before, g.exportJson("x"))
        assertEquals("Cypress", g.plant(plantId)?.name)
    }

    @Test
    fun importClearsClimatologyAndDeletesCascade() = runTest {
        val g = garden("i")
        val plantId = g.seed()
        val placeId = g.plant(plantId)!!.placeId
        val dao = dbs.last().dao()
        dao.upsertClimatology(listOf(ClimatologyEntity(placeId, 1, 0.0, 5.0, 1.0)))
        g.checkIn(plantId, "OK")
        g.importJson(g.exportJson("x"))
        assertTrue(dao.climatology(placeId).isEmpty())

        g.deletePlant(plantId)
        assertTrue(g.deletePlace(placeId))
        assertNull(g.place(placeId))
        assertTrue(dao.events(plantId).isEmpty())
    }

    @Test
    fun calibrationCsvQuotesNotes() = runTest {
        val g = garden("f")
        val plantId = g.seed()
        g.logMass(plantId, 2000.0, note = "rain, then \"windy\"")
        val lines = g.calibrationCsv().lines()
        assertEquals("plant_id,plant_name,timestamp_utc,grams,note", lines[0])
        assertEquals("$plantId,Cypress,2026-11-23T08:00:00Z,2000.0,\"rain, then \"\"windy\"\"\"", lines[1])
    }

    @Test
    fun weatherCacheRoundTrips() = runTest {
        val g = garden("g")
        val store = DbResponseStore(dbs.last().dao())
        assertNull(store.load("k"))
        val v = com.kostavukicevic.plants.weather.CachedResponse("body", clock, expires = clock, lastModified = "Mon, 23 Nov 2026 07:00:00 GMT", consecutiveFailures = 2)
        store.save("k", v)
        assertEquals(v, store.load("k"))
        assertTrue(g.statuses().isEmpty())
    }

    // ---- Feeding and repotting reminders ------------------------------------------------
    // Test fixtures, not care data.

    private val feeding = Season(feedingMonthsNorthernHemisphere = listOf(4, 5, 6, 7, 8, 9), feedingIntervalWeeks = 2)
    private val june = LocalDate(2026, 6, 15)
    private val southern = indoor("MEDIUM").copy(latitude = -33.9)

    private fun fed(on: LocalDate, id: Long = 1) = EventEntity(id = id, plantId = 1, date = on.toString(), type = "FED")

    private fun remindersOn(day: LocalDate, events: List<EventEntity> = emptyList(), season: Season? = feeding, place: PlaceEntity = indoor("MEDIUM")) =
        Planner.reminders(fern, place, events, season, day)

    @Test
    fun feedingIsDueInAFeedingMonthWithoutAFeed() {
        assertEquals(listOf(Reminder.FEED), remindersOn(june))
    }

    @Test
    fun feedingClearsAfterAFeedUntilTheIntervalPasses() {
        assertEquals(emptyList(), remindersOn(june, listOf(fed(june))))
        assertEquals(emptyList(), remindersOn(june.plus(DatePeriod(days = 13)), listOf(fed(june))))
        assertEquals(listOf(Reminder.FEED), remindersOn(june.plus(DatePeriod(days = 14)), listOf(fed(june))))
        // The latest feed counts, not the first.
        assertEquals(emptyList(), remindersOn(june, listOf(fed(LocalDate(2026, 4, 1)), fed(june, id = 2))))
    }

    @Test
    fun feedingIsNotDueOutsideTheFeedingMonths() {
        assertEquals(emptyList(), remindersOn(LocalDate(2026, 12, 15)))
        assertEquals(emptyList(), remindersOn(LocalDate(2026, 3, 31)))
        assertEquals(listOf(Reminder.FEED), remindersOn(LocalDate(2026, 4, 1)))
    }

    @Test
    fun feedingMonthsShiftSixMonthsSouthOfTheEquator() {
        // April-September in the north is October-March in the south.
        assertEquals(emptyList(), remindersOn(june, place = southern))
        assertEquals(listOf(Reminder.FEED), remindersOn(LocalDate(2026, 12, 15), place = southern))
        assertEquals(listOf(Reminder.FEED), remindersOn(LocalDate(2026, 3, 15), place = southern))
        assertEquals(emptyList(), remindersOn(LocalDate(2026, 4, 15), place = southern))
        // The equator itself counts as north.
        assertEquals(listOf(Reminder.FEED), remindersOn(june, place = southern.copy(latitude = 0.0)))
    }

    @Test
    fun noReminderWhenTheFieldsAreMissing() {
        assertEquals(emptyList(), remindersOn(june, season = null))
        assertEquals(emptyList(), remindersOn(june, season = feeding.copy(feedingMonthsNorthernHemisphere = null)))
        assertEquals(emptyList(), remindersOn(june, season = feeding.copy(feedingIntervalWeeks = null)))
        assertEquals(emptyList(), remindersOn(june, season = Season(repotIntervalMonths = null)))
    }

    @Test
    fun repottingIsDueFromTheLastRepotElseTheAcquiredDate() {
        val season = Season(repotIntervalMonths = 24)
        val plant = fern.copy(acquiredOn = "2023-01-10", lastRepotOn = "2024-06-15")
        fun on(day: LocalDate, p: PlantEntity = plant) = Planner.reminders(p, indoor("MEDIUM"), emptyList(), season, day)
        assertEquals(emptyList(), on(LocalDate(2026, 6, 14)))
        assertEquals(listOf(Reminder.REPOT), on(LocalDate(2026, 6, 15)))
        // No repot recorded: counted from the day the plant was acquired.
        val never = plant.copy(lastRepotOn = null)
        assertEquals(emptyList(), on(LocalDate(2025, 1, 9), never))
        assertEquals(listOf(Reminder.REPOT), on(LocalDate(2025, 1, 10), never))
    }

    private fun seasonDb(season: Season) = CareDb(
        schemaVersion = CareDb.SUPPORTED_SCHEMA, dataVersion = 1, sourceCommit = "test",
        species = mapOf(
            "test-species" to Species(
                archetype = "test", taxon = Taxon(acceptedName = "Testus testus", rank = "species"),
                indoor = CareGroups(season = season),
                toxicity = Toxicity(),
            ),
        ),
    )

    private suspend fun Garden.indoorPlant(acquiredOn: LocalDate): Long {
        val placeId = savePlace(indoor("MEDIUM").copy(id = 0))
        return savePlant(fern.copy(id = 0, placeId = placeId, speciesSlug = "test-species", acquiredOn = acquiredOn.toString()))
    }

    @Test
    fun repottedClearsARepotReminder() = runTest {
        val g = garden("repot", careDb = seasonDb(Season(repotIntervalMonths = 12)))
        val plantId = g.indoorPlant(today.plus(DatePeriod(years = -2)))
        assertEquals(listOf(Reminder.REPOT), g.status(plantId)!!.reminders)
        g.repotted(plantId)
        assertEquals(emptyList(), g.status(plantId)!!.reminders)
    }

    @Test
    fun aFeedIsLoggedAndClearsTheReminder() = runTest {
        // Feeding every month, so the fixed test date is always in season.
        val g = garden("feed", careDb = seasonDb(Season(feedingMonthsNorthernHemisphere = (1..12).toList(), feedingIntervalWeeks = 4)))
        val plantId = g.indoorPlant(today.plus(DatePeriod(days = -3)))
        assertEquals(listOf(Reminder.FEED), g.status(plantId)!!.reminders)
        g.fed(plantId)
        assertEquals(listOf("FED"), dbs.last().dao().events(plantId).map { it.type })
        assertEquals(emptyList(), g.status(plantId)!!.reminders)
    }

    @Test
    fun exportAndImportKeepAFeed() = runTest {
        val a = garden("fed-a")
        val plantId = a.seed()
        a.fed(plantId)
        val exported = a.exportJson("x")
        val b = garden("fed-b")
        b.importJson(exported)
        assertEquals(listOf("FED"), dbs.last().dao().events(plantId).map { it.type })
        assertEquals(exported, b.exportJson("x"))
    }

    @Test
    fun theArchetypeGivesTheSeasonWhenThereIsNoSpecies() {
        val db = CareDb(
            schemaVersion = CareDb.SUPPORTED_SCHEMA, dataVersion = 1, sourceCommit = "test",
            archetypes = mapOf("test-archetype" to Archetype(name = "Test", defaults = CareGroups(season = feeding))),
        )
        val plant = fern.copy(archetypeId = "test-archetype")
        assertEquals(feeding, Planner.season(plant, indoor("MEDIUM"), db))
        assertEquals(listOf(Reminder.FEED), Planner.status(plant, indoor("MEDIUM"), emptyList(), emptyList(), emptyList(), db, june).reminders)
    }

    @Test
    fun theNextReminderDateIsKnownAhead() {
        val season = feeding.copy(repotIntervalMonths = 24)
        val plant = fern.copy(acquiredOn = "2025-01-10")
        fun next(from: LocalDate, events: List<EventEntity> = emptyList(), place: PlaceEntity = indoor("MEDIUM")) =
            Planner.reminderDates(plant, place, events, season, from)
        // Fed on 15 June: next feed two weeks later; repot two years after acquiring.
        assertEquals(mapOf(Reminder.FEED to LocalDate(2026, 6, 29), Reminder.REPOT to LocalDate(2027, 1, 10)), next(june, listOf(fed(june))))
        // Outside the feeding months the next feed is the first day of the next feeding month.
        assertEquals(LocalDate(2027, 4, 1), next(LocalDate(2026, 10, 3))[Reminder.FEED])
        assertEquals(LocalDate(2026, 10, 1), next(june, place = southern)[Reminder.FEED])
        // Already due: the day asked about.
        assertEquals(june, next(june)[Reminder.FEED])
        // An interval that ends after the last feeding month waits for the next season.
        assertEquals(LocalDate(2027, 4, 1), next(LocalDate(2026, 9, 20), listOf(fed(LocalDate(2026, 9, 20))))[Reminder.FEED])
    }

    @Test
    fun theStatusNamesTheNextReminderAfterToday() {
        val db = CareDb(
            schemaVersion = CareDb.SUPPORTED_SCHEMA, dataVersion = 1, sourceCommit = "test",
            archetypes = mapOf("test-archetype" to Archetype(name = "Test", defaults = CareGroups(season = feeding))),
        )
        val plant = fern.copy(archetypeId = "test-archetype", acquiredOn = june.plus(DatePeriod(days = -3)).toString())
        fun status(events: List<EventEntity>) = Planner.status(plant, indoor("MEDIUM"), events, emptyList(), emptyList(), db, june)
        // Due today and, unless fed, again tomorrow.
        assertEquals(june.plus(DatePeriod(days = 1)), status(emptyList()).nextReminder)
        assertEquals(june.plus(DatePeriod(days = 14)), status(listOf(fed(june))).nextReminder)
        assertNull(Planner.status(plant, indoor("MEDIUM"), emptyList(), emptyList(), emptyList(), null, june).nextReminder)
    }

    // ---- Photos -------------------------------------------------------------------------

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5, 0xFF.toByte(), 0xD9.toByte())

    private suspend fun Garden.zip(): ByteArray = ByteArrayOutputStream().also { exportZip("x", it) }.toByteArray()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()

    private fun entries(zip: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) { val e = z.nextEntry ?: break; put(e.name, z.readBytes()) } }
    }

    @Test
    fun aPhotoIsStoredAndListedWithItsDate() = runTest {
        val g = garden("photo")
        val plantId = g.seed()
        val day = today.plus(DatePeriod(days = -4))
        val id = g.addPhoto(plantId, jpeg, day)
        val photo = dbs.last().dao().photo(id)!!
        assertEquals(day.toString(), photo.date)
        assertEquals(plantId, photo.plantId)
        assertTrue(photo.file.endsWith(".jpg"))
        assertContentEquals(jpeg, g.photoFile(photo).readBytes())
        assertEquals(listOf(id), g.photos(plantId).first().map { it.id })
    }

    @Test
    fun exportZipAndImportRoundTripAPhoto() = runTest {
        val a = garden("zip-a")
        val plantId = a.seed()
        a.watered(plantId, ranOutOfBottom = true)
        a.addPhoto(plantId, jpeg)
        val zip = a.zip()
        val inZip = entries(zip)
        assertEquals(setOf(Export.ZIP_JSON, Export.ZIP_PHOTOS + a.photos(plantId).first().single().file), inZip.keys)

        val b = garden("zip-b")
        assertEquals(0, b.import(ByteArrayInputStream(zip)))
        val photo = b.photos(plantId).first().single()
        assertContentEquals(jpeg, b.photoFile(photo).readBytes())
        assertTrue(b.photoFile(photo).startsWith(photoDir("zip-b")))
        assertEquals(a.exportJson("x"), b.exportJson("x"))
    }

    @Test
    fun importAcceptsAPlainVersionOneJson() = runTest {
        val a = garden("v1-a")
        val plantId = a.seed()
        a.watered(plantId, ranOutOfBottom = false)
        // A version 1 export has no photos field.
        val v1 = a.exportJson("0.1.0").replace("\"version\": 2", "\"version\": 1").replace(Regex(",\\s*\"photos\": \\[\\s*]"), "")
        assertTrue("photos" !in v1)
        val b = garden("v1-b")
        b.addPhoto(b.seed(), jpeg)
        val old = b.photoFile(dbs.last().dao().allPhotos().single())
        assertEquals(0, b.import(ByteArrayInputStream(v1.encodeToByteArray())))
        assertEquals("Cypress", b.plant(plantId)?.name)
        assertEquals(listOf("WATERED"), dbs.last().dao().events(plantId).map { it.type })
        // Everything was replaced, the old photo with it.
        assertTrue(dbs.last().dao().allPhotos().isEmpty())
        assertFalse(old.exists())
    }

    @Test
    fun aPhotoMissingFromTheZipIsSkippedAndCounted() = runTest {
        val a = garden("missing-a")
        val plantId = a.seed()
        a.addPhoto(plantId, jpeg)
        a.addPhoto(plantId, jpeg + byteArrayOf(1))
        val full = entries(a.zip())
        val dropped = full.keys.first { it.startsWith(Export.ZIP_PHOTOS) }
        val zip = zipOf(*full.filterKeys { it != dropped }.toList().toTypedArray())

        val b = garden("missing-b")
        assertEquals(1, b.import(ByteArrayInputStream(zip)))
        assertEquals("Cypress", b.plant(plantId)?.name)
        val kept = b.photos(plantId).first().single()
        assertTrue(kept.file != dropped.removePrefix(Export.ZIP_PHOTOS))
        assertTrue(b.photoFile(kept).isFile)
    }

    @Test
    fun aZipEntryWithDotDotIsRejectedAndChangesNothing() = runTest {
        val g = garden("slip")
        val plantId = g.seed()
        g.addPhoto(plantId, jpeg)
        val before = g.exportJson("x")
        val json = entries(g.zip()).getValue(Export.ZIP_JSON)
        for (bad in listOf("photos/../../escaped.jpg", "../garden.json", "/etc/escaped", "photos\\..\\escaped.jpg")) {
            val zip = zipOf(bad to jpeg, Export.ZIP_JSON to json)
            assertIs<IllegalArgumentException>(runCatching { g.import(ByteArrayInputStream(zip)) }.exceptionOrNull(), bad)
            assertEquals(before, g.exportJson("x"))
            assertTrue(g.photoFile(g.photos(plantId).first().single()).isFile)
        }
        assertFalse(dir.resolve("escaped.jpg").toFile().exists())
        assertFalse(File(photoDir("slip").parentFile.parentFile, "escaped.jpg").exists())
    }

    @Test
    fun aPhotoFileNameThatIsAPathIsRejected() = runTest {
        val g = garden("path-name")
        val plantId = g.seed()
        g.addPhoto(plantId, jpeg)
        val before = g.exportJson("x")
        val file = g.photos(plantId).first().single().file
        val zip = zipOf(Export.ZIP_JSON to before.replace(file, "../$file").encodeToByteArray(), Export.ZIP_PHOTOS + file to jpeg)
        assertIs<IllegalArgumentException>(runCatching { g.import(ByteArrayInputStream(zip)) }.exceptionOrNull())
        assertEquals(before, g.exportJson("x"))
    }

    @Test
    fun deletingAPhotoOrAPlantRemovesTheFiles() = runTest {
        val g = garden("delete-photos")
        val plantId = g.seed()
        val first = g.addPhoto(plantId, jpeg)
        g.addPhoto(plantId, jpeg)
        val files = g.photos(plantId).first().associate { it.id to g.photoFile(it) }
        assertTrue(files.values.all { it.isFile })

        g.deletePhoto(first)
        assertFalse(files.getValue(first).exists())
        assertEquals(1, g.photos(plantId).first().size)

        g.deletePlant(plantId)
        assertTrue(files.values.none { it.exists() })
        assertTrue(dbs.last().dao().allPhotos().isEmpty())
    }

    @Test
    fun aPlaceWithAPlantIsNotDeletedAndAnEmptyOneIs() = runTest {
        val g = garden("delete-place")
        val plantId = g.seed()
        g.addPhoto(plantId, jpeg)
        val file = g.photoFile(g.photos(plantId).first().single())
        val placeId = g.plant(plantId)!!.placeId
        assertFalse(g.deletePlace(placeId))
        assertNotNull(g.place(placeId))
        assertNotNull(g.plant(plantId))
        assertTrue(file.exists())
        g.deletePlant(plantId)
        assertFalse(file.exists())
        assertTrue(g.deletePlace(placeId))
        assertNull(g.place(placeId))
    }

    @Test
    fun anEmptyPhotoIsRefusedAndLeavesNothing() = runTest {
        val g = garden("empty-photo")
        val plantId = g.seed()
        assertIs<IllegalArgumentException>(runCatching { g.addPhoto(plantId, ByteArray(0)) }.exceptionOrNull())
        assertTrue(g.photos(plantId).first().isEmpty())
        assertTrue(photoDir("empty-photo").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aFailedPhotoCopyRollsBackAndKeepsTheLivePhoto() = runTest {
        val a = garden("copy-fail-a")
        val plantId = a.seed()
        a.addPhoto(plantId, jpeg)
        a.addPhoto(plantId, jpeg + byteArrayOf(1))
        a.addPhoto(plantId, jpeg + byteArrayOf(2))
        val zip = a.zip()
        // In id order, the order import copies them in.
        val names = a.photos(plantId).first().sortedBy { it.id }.map { it.file }

        // The destination already has a live photo under the first name, and a directory where the third must go:
        // the second is copied, the third fails, and the second must be removed again.
        val b = garden("copy-fail-b")
        val bPlant = b.seed()
        b.addPhoto(bPlant, jpeg + byteArrayOf(7))
        val live = b.photos(bPlant).first().single()
        val liveBytes = b.photoFile(live).readBytes()
        val forged = entries(zip).mapValues { (k, v) -> if (k == Export.ZIP_JSON) v.decodeToString().replace(names[0], live.file).encodeToByteArray() else v }
            .mapKeys { (k, _) -> if (k == Export.ZIP_PHOTOS + names[0]) Export.ZIP_PHOTOS + live.file else k }
        File(photoDir("copy-fail-b"), names[2]).resolve("blocker").apply { parentFile.mkdirs(); writeText("x") }
        val before = b.exportJson("x")

        assertIs<java.io.IOException>(runCatching { b.import(ByteArrayInputStream(zipOf(*forged.toList().toTypedArray()))) }.exceptionOrNull())
        assertEquals(before, b.exportJson("x"))
        assertContentEquals(liveBytes, b.photoFile(live).readBytes())
        assertEquals(setOf(live.file, names[2]), photoDir("copy-fail-b").list()!!.toSet())
    }

    @Test
    fun anOversizedZipEntryIsRefusedAndChangesNothing() = runTest {
        val g = garden("oversized")
        val plantId = g.seed()
        g.addPhoto(plantId, jpeg)
        val before = g.exportJson("x")
        val json = entries(g.zip()).getValue(Export.ZIP_JSON)
        // Zeros compress to almost nothing, as in a zip bomb; only the inflated size counts.
        val bigPhoto = zipOf(Export.ZIP_JSON to json, Export.ZIP_PHOTOS + "big.jpg" to ByteArray((MAX_PHOTO_BYTES + 1).toInt()))
        val bigJson = zipOf(Export.ZIP_JSON to ByteArray((MAX_JSON_BYTES + 1).toInt()) { ' '.code.toByte() })
        val tooMany = zipOf(*Array(MAX_ZIP_ENTRIES + 1) { "photos/d$it/" to ByteArray(0) })
        for (zip in listOf(bigPhoto, bigJson, tooMany)) {
            assertIs<IllegalArgumentException>(runCatching { g.import(ByteArrayInputStream(zip)) }.exceptionOrNull())
            assertEquals(before, g.exportJson("x"))
            assertTrue(g.photoFile(g.photos(plantId).first().single()).isFile)
        }
        assertFalse(File(photoDir("oversized").path + ".import").exists())
    }

    // ---- Repot advice: re-armed on a pot edit, never given before the plant was added (D65) ----

    private val room = PlaceEntity(name = "Room", outdoor = false, latitude = 44.8167, longitude = 20.4589, lightClass = "HIGH")

    /** A wide canopy in a half-litre pot: indoors it uses more than the pot holds, and more than 0.8 l too. */
    private val outgrown = PlantEntity(
        name = "Ficus", placeId = 0, canopyWidthCm = 150.0, potVolumeL = 0.5, acquiredOn = today.plus(DatePeriod(days = -20)).toString(),
    )

    private suspend fun Garden.addOutgrown(acquiredOn: LocalDate = today): Long =
        savePlant(outgrown.copy(placeId = savePlace(room), acquiredOn = acquiredOn.toString()))

    private fun at(day: LocalDate) { clock = Instant.parse("${day}T08:00:00Z") }

    @Test
    fun editingThePotWithoutARepottedEventReArmsTheRepotAdvice() = runTest {
        val g = garden("pot-edit")
        val added = today.plus(DatePeriod(days = -20))
        at(added)
        val id = g.addOutgrown(acquiredOn = added)
        assertEquals(Action.RepotOrWaterDaily, g.status(id)?.today)
        at(today)
        // Given once, on the day the plant was added.
        assertNotEquals(Action.RepotOrWaterDaily, g.status(id)?.today)

        g.savePlant(assertNotNull(g.plant(id)).copy(potVolumeL = 0.8))
        assertEquals(today.toString(), g.plant(id)?.potChangedOn)
        assertTrue(dbs.last().dao().events(id).isEmpty())
        assertEquals(Action.RepotOrWaterDaily, g.status(id)?.today)

        // Shown today for the new pot, so not repeated tomorrow.
        at(today.plus(DatePeriod(days = 1)))
        assertNotEquals(Action.RepotOrWaterDaily, g.status(id)?.today)
    }

    @Test
    fun aRepottedEventAndAPotEditOnTheSameDayReArmTheAdviceOnce() = runTest {
        val g = garden("pot-edit-repotted")
        val added = today.plus(DatePeriod(days = -20))
        at(added)
        val id = g.addOutgrown(acquiredOn = added)
        at(today)
        g.repotted(id)
        g.savePlant(assertNotNull(g.plant(id)).copy(potVolumeL = 0.8))
        assertEquals(Action.RepotOrWaterDaily, g.status(id)?.today)
        at(today.plus(DatePeriod(days = 1)))
        assertNotEquals(Action.RepotOrWaterDaily, g.status(id)?.today)
    }

    @Test
    fun aBackdatedAcquisitionDoesNotMarkTheRepotAdviceGivenBeforeThePlantWasAdded() = runTest {
        val g = garden("backdated")
        val id = g.addOutgrown(acquiredOn = today.plus(DatePeriod(days = -20)))
        assertEquals(today.toString(), g.plant(id)?.addedOn)
        // The replay starts 20 days back, but the person sees the advice for the first time today.
        assertEquals(Action.RepotOrWaterDaily, g.status(id)?.today)
        at(today.plus(DatePeriod(days = 1)))
        assertNotEquals(Action.RepotOrWaterDaily, g.status(id)?.today)
    }

    @Test
    fun aPlantWithoutTheTwoDatesReplaysAsBefore() {
        val place = room.copy(id = 1)
        val plant = outgrown.copy(id = 1, placeId = 1)
        assertNull(plant.addedOn)
        assertNull(plant.potChangedOn)
        val s = Planner.status(plant, place, emptyList(), emptyList(), emptyList(), null, today)
        // Marked given on the first replayed day, as before the two dates were kept.
        assertEquals(Planner.pot(plant), s.state.repotAdvisedFor)
        assertNotEquals(Action.RepotOrWaterDaily, s.today)
        // Re-arming on the first replayed day, before anything ran, changes nothing.
        val sameStart = plant.copy(addedOn = plant.acquiredOn)
        assertEquals(s, Planner.status(sameStart, place, emptyList(), emptyList(), emptyList(), null, today).copy(plant = plant))
    }

    @Test
    fun anEditThatKeepsThePotRecordsNoPotChange() = runTest {
        val g = garden("same-pot")
        val id = g.addOutgrown()
        at(today.plus(DatePeriod(days = 3)))
        g.savePlant(assertNotNull(g.plant(id)).copy(name = "Renamed", canopyWidthCm = 160.0, potVolumeL = 0.5))
        assertEquals("Renamed", g.plant(id)?.name)
        assertNull(g.plant(id)?.potChangedOn)
        assertEquals(today.toString(), g.plant(id)?.addedOn)
    }

    @Test
    fun anExportWithTheTwoDatesRoundTrips() = runTest {
        val a = garden("dates-a")
        val id = a.addOutgrown()
        at(today.plus(DatePeriod(days = 2)))
        a.savePlant(assertNotNull(a.plant(id)).copy(potVolumeL = 0.8))
        val exported = a.exportJson("x")
        val b = garden("dates-b")
        b.importJson(exported)
        assertEquals(today.toString(), b.plant(id)?.addedOn)
        assertEquals(today.plus(DatePeriod(days = 2)).toString(), b.plant(id)?.potChangedOn)
        assertEquals(exported, b.exportJson("x"))
    }

    @Test
    fun anExportWithoutTheTwoDatesImportsWithThemNull() = runTest {
        val a = garden("no-dates-a")
        val id = a.addOutgrown()
        a.savePlant(assertNotNull(a.plant(id)).copy(potVolumeL = 0.8))
        // An export from before the two dates were kept has neither field.
        val old = a.exportJson("x").replace(Regex(",\\s*\"(addedOn|potChangedOn)\": [^,\\n]*"), "")
        assertFalse("addedOn" in old || "potChangedOn" in old)
        val b = garden("no-dates-b")
        b.importJson(old)
        assertEquals("Ficus", b.plant(id)?.name)
        assertNull(b.plant(id)?.addedOn)
        assertNull(b.plant(id)?.potChangedOn)
    }

    @Test
    fun anImportWithABadAddedOrPotChangeDateIsRefused() = runTest {
        val a = garden("bad-dates")
        a.addOutgrown()
        val exported = a.exportJson("x")
        for (bad in listOf(exported.replace("\"addedOn\": \"$today\"", "\"addedOn\": \"soon\""), exported.replace("\"potChangedOn\": null", "\"potChangedOn\": \"x\""))) {
            assertNotEquals(exported, bad)
            assertIs<IllegalArgumentException>(runCatching { garden("bad-dates-${bad.hashCode()}").importJson(bad) }.exceptionOrNull())
        }
    }
}
