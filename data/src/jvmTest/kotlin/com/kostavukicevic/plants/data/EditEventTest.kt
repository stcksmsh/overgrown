// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kostavukicevic.plants.weather.HttpResponse
import com.kostavukicevic.plants.weather.HttpTransport
import com.kostavukicevic.plants.weather.InMemoryResponseStore
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.OpenMeteo
import com.kostavukicevic.plants.weather.WeatherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class EditEventTest {

    private val dir = Files.createTempDirectory("edit-event-test")
    private val db = Room.inMemoryDatabaseBuilder<GardenDb>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
    private val clock = Instant.parse("2026-11-23T08:00:00Z")
    private val garden = run {
        val http = HttpTransport { _, _ -> HttpResponse(503, emptyMap(), "") }
        val settings = Settings(PreferenceDataStoreFactory.createWithPath { dir.resolve("s.preferences_pb").toOkioPath() })
        val om = OpenMeteo(http, InMemoryResponseStore(), { clock })
        val met = MetNorway(http, InMemoryResponseStore(), { clock }, "test", TimeZone.UTC)
        Garden(db, settings, WeatherService(om, met, settings), om, { null }, { clock }, { TimeZone.UTC }, dir.resolve("photos").toFile())
    }

    @AfterTest fun close() = db.close()

    private suspend fun plant(): Long {
        val placeId = garden.savePlace(PlaceEntity(name = "Hall", outdoor = false, latitude = 44.8, longitude = 20.5))
        return garden.savePlant(PlantEntity(name = "Fern", placeId = placeId, canopyWidthCm = 20.0, potVolumeL = 2.0, acquiredOn = "2026-11-01"))
    }

    private suspend fun only(plantId: Long) = garden.events(plantId).first().single()

    @Test
    fun checkInDateAndAnswerPersist() = runTest {
        val p = plant()
        garden.checkIn(p, "OK")
        val e = only(p)

        garden.updateEvent(e.copy(date = "2026-11-20", moisture = "BONE_DRY"))

        assertEquals(e.copy(date = "2026-11-20", moisture = "BONE_DRY"), only(p))
    }

    @Test
    fun wateringDateAndDrainedFlagPersist() = runTest {
        val p = plant()
        garden.watered(p, ranOutOfBottom = false)
        val e = only(p)

        garden.updateEvent(e.copy(date = "2026-11-21", ranOutOfBottom = true))

        assertEquals(e.copy(date = "2026-11-21", ranOutOfBottom = true), only(p))
    }

    @Test
    fun plantTypeAndSnoozeEndCannotBeChanged() = runTest {
        val p = plant()
        garden.snooze(p)
        val e = only(p)

        garden.updateEvent(e.copy(date = "2026-11-22", plantId = p + 1, type = "WATERED", snoozedUntil = "2027-01-01"))

        assertEquals(e.copy(date = "2026-11-22"), only(p))
    }

    @Test
    fun futureDateIsRefused() = runTest {
        val p = plant()
        garden.checkIn(p, "OK")
        val e = only(p)

        assertFailsWith<IllegalArgumentException> { garden.updateEvent(e.copy(date = "2026-11-24")) }
        assertEquals(e, only(p))
    }

    @Test
    fun invalidAnswerIsRefused() = runTest {
        val p = plant()
        garden.checkIn(p, "OK")
        val e = only(p)

        assertFailsWith<IllegalArgumentException> { garden.updateEvent(e.copy(moisture = "SOGGY")) }
        assertFailsWith<IllegalArgumentException> { garden.updateEvent(e.copy(moisture = null)) }
        assertFailsWith<IllegalArgumentException> { garden.updateEvent(e.copy(date = "23 Nov")) }
        assertEquals(e, only(p))
    }

    @Test
    fun missingEventIsRefused() = runTest {
        val p = plant()
        assertFailsWith<IllegalArgumentException> { garden.updateEvent(EventEntity(id = 99, plantId = p, date = "2026-11-20", type = "FED")) }
    }
}
