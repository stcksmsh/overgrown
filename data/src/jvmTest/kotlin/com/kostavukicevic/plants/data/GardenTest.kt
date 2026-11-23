// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class GardenTest {

    private val dir = Files.createTempDirectory("garden-test")
    private val dbs = ArrayList<GardenDb>()
    private var clock = Instant.parse("2026-11-23T08:00:00Z")
    private val today = LocalDate(2026, 11, 23)

    @AfterTest fun close() { dbs.forEach { it.close() } }

    private val noNetwork = HttpTransport { _, _ -> HttpResponse(503, emptyMap(), "") }

    private fun garden(name: String, careDb: CareDb? = null, http: HttpTransport = noNetwork): Garden {
        val db = Room.inMemoryDatabaseBuilder<GardenDb>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build().also { dbs += it }
        val settings = Settings(PreferenceDataStoreFactory.createWithPath { dir.resolve("$name.preferences_pb").toOkioPath() })
        val om = OpenMeteo(http, InMemoryResponseStore(), { clock })
        val met = MetNorway(http, InMemoryResponseStore(), { clock }, "test", TimeZone.UTC)
        return Garden(db, settings, WeatherService(om, met, settings), om, { careDb }, { clock }, { TimeZone.UTC })
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

        g.deletePlace(placeId)
        assertNull(g.plant(plantId))
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
}
