// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.engine.Action
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

    private fun garden(name: String, careDb: CareDb? = null): Garden {
        val db = Room.inMemoryDatabaseBuilder<GardenDb>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build().also { dbs += it }
        val settings = Settings(PreferenceDataStoreFactory.createWithPath { dir.resolve("$name.preferences_pb").toOkioPath() })
        val om = OpenMeteo(noNetwork, InMemoryResponseStore(), { clock })
        val met = MetNorway(noNetwork, InMemoryResponseStore(), { clock }, "test", TimeZone.UTC)
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
}
