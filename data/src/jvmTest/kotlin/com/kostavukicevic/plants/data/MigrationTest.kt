// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest {

    /** Builds a version 1 database exactly as the exported version 1 schema describes it. */
    private fun versionOneDatabase(path: String) {
        // Gradle runs the tests in the module directory.
        val schema = Json.parseToJsonElement(File("schemas/com.kostavukicevic.plants.data.GardenDb/1.json").readText())
            .jsonObject.getValue("database").jsonObject
        assertEquals(1, schema.getValue("version").jsonPrimitive.content.toInt())
        val conn = BundledSQLiteDriver().open(path)
        try {
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                conn.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in entity["indices"]?.jsonArray.orEmpty()) {
                    conn.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            for (q in schema.getValue("setupQueries").jsonArray) conn.execSQL(q.jsonPrimitive.content)
            conn.execSQL("INSERT INTO place (id, name, outdoor, latitude, longitude, rainExposure, windExposure, dryAir) VALUES (1, 'Balcony', 1, 44.8, 20.5, 'OPEN', 'NORMAL', 0)")
            conn.execSQL(
                "INSERT INTO plant (id, name, placeId, canopyWidthCm, potVolumeL, potShape, potMaterial, drainageHoles, inNurseryPot, rootBound, acquiredOn) " +
                    "VALUES (7, 'Boris', 1, 35.0, 3.0, 'STANDARD', 'PLASTIC', 1, 0, 0, '2026-10-01')",
            )
            conn.execSQL("INSERT INTO event (id, plantId, date, type, ranOutOfBottom) VALUES (3, 7, '2026-11-20', 'WATERED', 1)")
            conn.execSQL("PRAGMA user_version = 1")
        } finally {
            conn.close()
        }
    }

    @Test
    fun versionOneMigratesWithPlantsAndEventsIntact() = runTest {
        val path = Files.createTempDirectory("migration-test").resolve(GardenDb.FILE_NAME).toString()
        versionOneDatabase(path)

        val db = Room.databaseBuilder<GardenDb>(path)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
        try {
            val dao = db.dao()
            assertEquals(listOf(7L to "Boris"), dao.allPlants().map { it.id to it.name })
            assertEquals(listOf(EventEntity(id = 3, plantId = 7, date = "2026-11-20", type = "WATERED", ranOutOfBottom = true)), dao.allEvents())
            assertTrue(dao.allPhotos().isEmpty())
            val id = dao.insertPhoto(PhotoEntity(plantId = 7, date = "2026-11-21", file = "a.jpg"))
            assertEquals("a.jpg", dao.photo(id)?.file)
        } finally {
            db.close()
        }
    }
}
