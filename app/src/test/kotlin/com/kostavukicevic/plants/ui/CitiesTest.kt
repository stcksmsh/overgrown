// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The nearest-city lookup over the shipped Natural Earth list. */
class CitiesTest {

    // Gradle runs unit tests from the module directory, as the screenshot tests' paths assume.
    private val cities = parseCities(File("src/main/assets/cities.tsv").readText())

    @Test
    fun theListIsTheWholeNaturalEarthSet() {
        assertTrue(cities.size > 7000)
    }

    @Test
    fun belgradeCoordinatesGiveBelgrade() {
        val n = nearestCity(cities, 44.82, 20.46)!!
        assertEquals(City("Belgrade", "Serbia", 44.82, 20.47), n.city)
        assertEquals(0.79, n.km, 0.01)
        assertEquals("Belgrade, Serbia", cityLabel(n))
    }

    @Test
    fun midAtlanticGivesTheNearestEntryWithItsDistance() {
        val n = nearestCity(cities, 30.0, -40.0)!!
        assertEquals("Horta", n.city.name)
        assertEquals(1408.4, n.km, 0.5)
        assertEquals("Horta, Portugal (1408 km)", cityLabel(n))
    }

    @Test
    fun haversineMatchesAKnownDistance() {
        // One degree of latitude on the 6371 km sphere.
        assertEquals(111.19, haversineKm(0.0, 0.0, 1.0, 0.0), 0.01)
    }

    @Test
    fun noCitiesGiveNoMatch() {
        assertNull(nearestCity(emptyList(), 1.0, 2.0))
    }

    @Test
    fun coordinatesRoundToTwoDecimals() {
        assertEquals(44.81, roundCoordinate(44.81234), 0.0)
        assertEquals(20.47, roundCoordinate(20.46789), 0.0)
        assertEquals(-33.87, roundCoordinate(-33.8688), 0.0)
    }
}
