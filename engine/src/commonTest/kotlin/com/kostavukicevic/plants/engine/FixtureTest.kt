// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.engine

import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureTest {

    private data class Row(val month: String, val doy: Int, val tMax: Double, val tMin: Double, val ra: Double, val dayLength: Double, val et0: Double)

    /** Belgrade 44.8 degN, 1981-2010 normals (Serbian hydrometeorological service, station 13274). */
    private val belgrade = listOf(
        Row("Jan", 15, 4.6, -1.1, 12.05, 8.98, 0.53),
        Row("Feb", 46, 7.0, -0.1, 17.57, 10.20, 0.93),
        Row("Mar", 74, 12.4, 3.7, 24.94, 11.64, 1.78),
        Row("Apr", 105, 18.0, 8.3, 33.28, 13.28, 3.01),
        Row("May", 135, 23.5, 13.0, 39.23, 14.64, 4.30),
        Row("Jun", 166, 26.2, 15.8, 41.86, 15.38, 4.92),
        Row("Jul", 196, 28.6, 17.5, 40.61, 15.06, 5.19),
        Row("Aug", 227, 28.7, 17.6, 35.65, 13.87, 4.56),
        Row("Sep", 258, 23.9, 13.5, 27.95, 12.28, 3.09),
        Row("Oct", 288, 18.4, 9.0, 19.83, 10.70, 1.80),
        Row("Nov", 319, 11.2, 4.2, 13.33, 9.30, 0.84),
        Row("Dec", 349, 5.8, 0.2, 10.62, 8.62, 0.49),
    )

    @Test
    fun hargreavesBelgradeMonthlyNormals() {
        for (r in belgrade) {
            assertEquals(r.ra, Solar.extraterrestrialRadiation(44.8, r.doy), 0.1, "Ra ${r.month}")
            assertEquals(r.dayLength, Solar.dayLengthHours(44.8, r.doy), 0.1, "day length ${r.month}")
            assertEquals(r.et0, Solar.hargreavesEt0(r.tMin, r.tMax, 44.8, r.doy), 0.1, "ET0 ${r.month}")
        }
    }

    @Test
    fun summerSmallPotBigCanopyMeansRepot() {
        // July ET0 5.19 mm on a 40 cm canopy with coefficient 1: about 0.65 l/day.
        val canopy = WaterBalance.canopyAreaM2(40.0)
        assertEquals(0.126, canopy, 0.001)
        val use = WaterBalance.outdoorUseMl(5.19, canopy, PotMaterial.PLASTIC, k = 1.0, coefficient = 1.0)
        assertEquals(652.0, use, 5.0)

        val pot = Pot(substrateVolumeL = 2.0)
        assertEquals(600.0, WaterBalance.capacityMl(pot), 1e-9)

        // The engine itself, with its coefficient and a July day whose ET0 gives the same use.
        val plant = Plant(
            CareProfile(WaterStrategy.DRY_TOP_PART),
            canopyWidthCm = 40.0,
            pot = pot,
            place = Place.Outdoor(44.8, RainExposure.COVERED),
        )
        val july = WeatherDay(LocalDate(2026, Month.JULY, 15), 17.5, 28.6, 0.0, et0Mm = 5.19 / Coefficients.OUTDOOR_CANOPY_COEFFICIENT)
        val action = Engine.recommend(plant, Engine.initial(plant), listOf(july)).action
        assertEquals(Action.RepotOrWaterDaily, action)
    }

    private fun referencePlace(lux: Double) = Place.Indoor(latitudeDeg = 0.0, lightClass = WaterBalance.lightClassOf(lux), lux = lux)

    @Test
    fun indoorReferencePlantUses73MlPerDay() {
        val use = WaterBalance.indoorUseMl(referencePlace(2150.0), 0.07, PotMaterial.PLASTIC, k = 1.0, photoperiodHours = 12.0)
        assertEquals(73.0, use, 0.01)

        // 1.5 to 2 litres of substrate: the full bucket empties in about 6 to 8 days.
        for (litres in listOf(1.5, 2.0)) {
            val days = WaterBalance.capacityMl(Pot(litres)) / use
            assertTrue(days in 6.0..8.3, "$litres l empties in $days days")
        }
    }

    @Test
    fun indoorDimLightTakesTwoToThreeTimesAsLong() {
        val bright = WaterBalance.indoorUseMl(referencePlace(2150.0), 0.07, PotMaterial.PLASTIC, 1.0, 12.0)
        val dim = WaterBalance.indoorUseMl(referencePlace(540.0), 0.07, PotMaterial.PLASTIC, 1.0, 12.0)
        val ratio = bright / dim
        // The study's ratio of midpoints is 19/7 = 2.7. Exponent 0.8 gives 3.0.
        assertTrue(ratio in 2.0..3.1, "ratio $ratio")
    }
}
