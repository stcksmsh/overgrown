// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportTest {

    private fun plant(errors: List<Double>) = PlantResult(10.0, 0, 0, 0, 0, 0, 0, errors, errors.map { it / 10 }, null, 0.0, 0)

    /** A pooled median over refills would read 0: the plant with 1000 refills would outvote the other. */
    @Test
    fun everyPlantCountsOnceInTheDryOutError() {
        val plants = listOf(plant(List(1000) { 0.0 }), plant(List(10) { 5.0 }))
        assertEquals(2.5, plants.perPlantMedian { it.dryOutErrorsDays })
        assertEquals(0.25, plants.perPlantMedian { it.dryOutErrorsRelative })
        assertEquals(2.5, RunSummary.of(plants).dryOutErrorDays)
    }

    @Test
    fun plantsWithoutARefillAreLeftOutAndCounted() {
        val plants = listOf(plant(emptyList()), plant(listOf(1.0, 3.0)))
        assertEquals(2.0, plants.perPlantMedian { it.dryOutErrorsDays })
        assertEquals(1, plants.plantsWithoutRefill())
    }

    @Test
    fun scenarioWithNoRefillReportsNoData() {
        val plants = listOf(plant(emptyList()), plant(emptyList()))
        assertTrue(plants.perPlantMedian { it.dryOutErrorsDays }.isNaN())
        assertTrue(RunSummary.of(plants).dryOutErrorDays.isNaN())
        val table = report(listOf(Row("nowhere, outdoor", "engine, provider ET0", plants), Row("nowhere, outdoor", "baseline", plants))).lines().last { it.startsWith("| nowhere") }
        assertEquals(listOf("n/a", "n/a"), table.split(" | ").subList(7, 9))
        assertTrue(table.endsWith("| 2 |"), table)
    }
}
