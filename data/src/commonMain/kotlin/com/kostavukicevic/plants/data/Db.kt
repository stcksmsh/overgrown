// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// Enumerations are stored by name and dates as ISO strings: readable in an export, stable across versions.

@Serializable
@Entity(tableName = "place")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val outdoor: Boolean,
    /** As entered; rounded to 2 decimals before any request. */
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double? = null,
    val rainExposure: String = "OPEN",
    val windExposure: String = "NORMAL",
    val lightClass: String? = null,
    val lux: Double? = null,
    val dryAir: Boolean = false,
)

@Serializable
@Entity(tableName = "plant", indices = [Index("placeId")])
data class PlantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val placeId: Long,
    /** A species slug from the care database, or null when an archetype is used. */
    val speciesSlug: String? = null,
    val archetypeId: String? = null,
    val canopyWidthCm: Double,
    val potVolumeL: Double,
    val potHeightCm: Double? = null,
    val potShape: String = "STANDARD",
    val potMaterial: String = "PLASTIC",
    val drainageHoles: Boolean = true,
    val inNurseryPot: Boolean = false,
    val rootBound: Boolean = false,
    val acquiredOn: String,
    val lastRepotOn: String? = null,
)

@Serializable
@Entity(tableName = "event", indices = [Index("plantId", "date")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val plantId: Long,
    val date: String,
    /** WATERED, CHECK_IN, MOVED, REPOTTED, SNOOZED */
    val type: String,
    val ranOutOfBottom: Boolean? = null,
    val moisture: String? = null,
    val snoozedUntil: String? = null,
)

@Serializable
@Entity(tableName = "weather_day", primaryKeys = ["placeId", "date"])
data class WeatherDayEntity(
    val placeId: Long,
    val date: String,
    val tMinC: Double,
    val tMaxC: Double,
    val precipitationMm: Double,
    val meanWindMs: Double? = null,
    val meanRelativeHumidityPct: Double? = null,
    val et0Mm: Double? = null,
    val provider: String,
    val fetchedAtEpochMs: Long,
)

@Serializable
@Entity(tableName = "climatology", primaryKeys = ["placeId", "month"])
data class ClimatologyEntity(
    val placeId: Long,
    val month: Int,
    val tMinC: Double,
    val tMaxC: Double,
    val precipitationMm: Double,
    val meanWindMs: Double? = null,
    val meanRelativeHumidityPct: Double? = null,
    val et0Mm: Double? = null,
)

/** Weather client cache and back-off state, keyed by provider and location. */
@Entity(tableName = "response_cache")
data class ResponseEntity(
    @PrimaryKey val key: String,
    val body: String,
    val fetchedAtEpochMs: Long,
    val expiresEpochMs: Long? = null,
    val lastModified: String? = null,
    val notBeforeEpochMs: Long? = null,
    val consecutiveFailures: Int = 0,
)

/** Calibration mode: pot mass with a timestamp. This is how the engine gets validated. */
@Serializable
@Entity(tableName = "calibration", indices = [Index("plantId")])
data class CalibrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val plantId: Long,
    val atEpochMs: Long,
    val grams: Double,
    val note: String = "",
)

@Dao
interface GardenDao {
    @Query("SELECT * FROM place ORDER BY name") fun places(): Flow<List<PlaceEntity>>
    @Query("SELECT * FROM place ORDER BY id") suspend fun allPlaces(): List<PlaceEntity>
    @Query("SELECT * FROM place WHERE id = :id") suspend fun place(id: Long): PlaceEntity?
    @Upsert suspend fun upsertPlace(p: PlaceEntity): Long
    @Query("DELETE FROM place WHERE id = :id") suspend fun deletePlace(id: Long)

    @Query("SELECT * FROM plant ORDER BY name") fun plants(): Flow<List<PlantEntity>>
    @Query("SELECT * FROM plant ORDER BY id") suspend fun allPlants(): List<PlantEntity>
    @Query("SELECT * FROM plant WHERE id = :id") suspend fun plant(id: Long): PlantEntity?
    @Upsert suspend fun upsertPlant(p: PlantEntity): Long
    @Query("DELETE FROM plant WHERE id = :id") suspend fun deletePlant(id: Long)

    @Insert suspend fun insertEvent(e: EventEntity): Long
    @Query("SELECT * FROM event WHERE plantId = :plantId ORDER BY date, id") suspend fun events(plantId: Long): List<EventEntity>
    @Query("SELECT * FROM event WHERE plantId = :plantId ORDER BY date DESC, id DESC") fun eventsFlow(plantId: Long): Flow<List<EventEntity>>
    @Query("SELECT * FROM event ORDER BY id") suspend fun allEvents(): List<EventEntity>
    @Query("DELETE FROM event WHERE id = :id") suspend fun deleteEvent(id: Long)

    /** A later forecast for a day replaces an earlier one; that day's last forecast is its pseudo-observation. */
    @Upsert suspend fun upsertWeather(days: List<WeatherDayEntity>)
    @Query("SELECT * FROM weather_day WHERE placeId = :placeId ORDER BY date") suspend fun weather(placeId: Long): List<WeatherDayEntity>
    @Query("SELECT * FROM weather_day ORDER BY placeId, date") suspend fun allWeather(): List<WeatherDayEntity>
    @Query("SELECT MAX(fetchedAtEpochMs) FROM weather_day WHERE placeId = :placeId") fun lastFetchFlow(placeId: Long): Flow<Long?>
    @Query("SELECT provider FROM weather_day ORDER BY fetchedAtEpochMs DESC LIMIT 1") suspend fun lastProvider(): String?

    @Upsert suspend fun upsertClimatology(months: List<ClimatologyEntity>)
    @Query("SELECT * FROM climatology WHERE placeId = :placeId") suspend fun climatology(placeId: Long): List<ClimatologyEntity>

    @Query("SELECT * FROM response_cache WHERE `key` = :key") suspend fun response(key: String): ResponseEntity?
    @Upsert suspend fun upsertResponse(r: ResponseEntity)

    @Insert suspend fun insertCalibration(c: CalibrationEntity): Long
    @Query("SELECT * FROM calibration WHERE plantId = :plantId ORDER BY atEpochMs") fun calibrationFlow(plantId: Long): Flow<List<CalibrationEntity>>
    @Query("SELECT * FROM calibration ORDER BY plantId, atEpochMs") suspend fun allCalibration(): List<CalibrationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun importPlaces(p: List<PlaceEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun importPlants(p: List<PlantEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun importEvents(e: List<EventEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun importCalibration(c: List<CalibrationEntity>)
    @Query("DELETE FROM place") suspend fun clearPlaces()
    @Query("DELETE FROM plant") suspend fun clearPlants()
    @Query("DELETE FROM event") suspend fun clearEvents()
    @Query("DELETE FROM weather_day") suspend fun clearWeather()
    @Query("DELETE FROM calibration") suspend fun clearCalibration()
    @Query("DELETE FROM climatology") suspend fun clearClimatology()
    @Query("DELETE FROM event WHERE plantId = :plantId") suspend fun deleteEventsOf(plantId: Long)
    @Query("DELETE FROM calibration WHERE plantId = :plantId") suspend fun deleteCalibrationOf(plantId: Long)
    @Query("SELECT id FROM plant WHERE placeId = :placeId") suspend fun plantIdsAt(placeId: Long): List<Long>
    @Query("DELETE FROM weather_day WHERE placeId = :placeId") suspend fun deleteWeatherAt(placeId: Long)
    @Query("DELETE FROM climatology WHERE placeId = :placeId") suspend fun deleteClimatologyAt(placeId: Long)

    /** A plant and everything recorded about it. */
    @Transaction
    suspend fun deletePlantFully(plantId: Long) {
        deleteEventsOf(plantId); deleteCalibrationOf(plantId); deletePlant(plantId)
    }

    /** A place, its plants and its weather. */
    @Transaction
    suspend fun deletePlaceFully(placeId: Long) {
        for (id in plantIdsAt(placeId)) deletePlantFully(id)
        deleteWeatherAt(placeId); deleteClimatologyAt(placeId); deletePlace(placeId)
    }

    /** Import: everything replaced, or nothing. */
    @Transaction
    suspend fun replaceAll(
        places: List<PlaceEntity>, plants: List<PlantEntity>, events: List<EventEntity>,
        weather: List<WeatherDayEntity>, calibration: List<CalibrationEntity>,
    ) {
        // Climatology is keyed by place id and not exported: an imported place 1 may be another city.
        clearCalibration(); clearEvents(); clearWeather(); clearClimatology(); clearPlants(); clearPlaces()
        importPlaces(places); importPlants(plants); importEvents(events); upsertWeather(weather); importCalibration(calibration)
    }
}

@Database(
    entities = [
        PlaceEntity::class, PlantEntity::class, EventEntity::class, WeatherDayEntity::class,
        ClimatologyEntity::class, ResponseEntity::class, CalibrationEntity::class,
    ],
    version = 1,
)
@ConstructedBy(GardenDbConstructor::class)
abstract class GardenDb : RoomDatabase() {
    abstract fun dao(): GardenDao

    companion object {
        const val FILE_NAME = "garden.db"
    }
}

/** Room generates the actual for each target. */
expect object GardenDbConstructor : RoomDatabaseConstructor<GardenDb> {
    override fun initialize(): GardenDb
}
