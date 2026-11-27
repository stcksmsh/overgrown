// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.sim

import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.caredb.CareGroups
import com.kostavukicevic.plants.caredb.Context
import com.kostavukicevic.plants.caredb.EngineFields
import com.kostavukicevic.plants.caredb.Water
import com.kostavukicevic.plants.caredb.toProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchetypeTest {

    private val archetype = CareGroups(water = Water(waterStrategy = "evenly_moist"))

    private fun run(species: CareGroups) =
        compare("fixture", "fixture-archetype", listOf(Context.OUTDOOR_CONTAINER), { species.toProfile() }, archetype.toProfile(), plantsPerScenario = 4)

    @Test
    fun speciesEqualToItsArchetypePassesWithNoDifference() {
        val r = run(archetype.copy())
        assertEquals(r.speciesRun, r.archetypeRun)
        assertEquals(0.0, r.extraStressDays)
        assertTrue(r.differingFields.isEmpty())
        assertTrue(r.pass)
    }

    @Test
    fun speciesThatUsesFarMoreWaterThanItsArchetypeFails() {
        val r = run(archetype.copy(engine = EngineFields(waterUseFactor = 3.0)))
        assertEquals(listOf("water use factor"), r.differingFields)
        assertFalse(r.pass, "archetype run: $r")
    }

    @Test
    fun speciesWithoutAnyRefillIsNoDataNotAFailure() {
        val r = ArchetypeResult("fixture", "fixture-archetype", emptyList(), emptyList(), RunSummary(10.0, 1.0, 0.2), noRefills(10.0))
        assertTrue(r.noData)
        assertFalse(r.pass)
        assertEquals(null, r.failure)
        assertEquals("no data", r.result)
        val report = archetypeReport(CareDb(schemaVersion = 1, dataVersion = 1, sourceCommit = "fixture"), listOf(r))
        assertTrue("| no data |" in report, report)
        assertFalse("Needing their own entry" in report, report)
    }

    /** An archetype that makes the model believe the plant uses water far more slowly never predicts a dry-out within the horizon. */
    @Test
    fun stressFailureStandsWhenOneRunHasNoDryOutPrediction() {
        val r = ArchetypeResult("fixture", "fixture-archetype", emptyList(), emptyList(), RunSummary(10.0, 1.0, 0.2), noRefills(60.0))
        assertTrue(r.dryOutUnknown)
        assertFalse(r.noData)
        assertFalse(r.pass)
        assertEquals("stress", r.failure)
        assertEquals("fail: stress", r.result)
        val report = archetypeReport(CareDb(schemaVersion = 1, dataVersion = 1, sourceCommit = "fixture"), listOf(r))
        assertTrue("Needing their own entry: fixture (stress)." in report, report)
    }

    private fun noRefills(stressDaysPerYear: Double) =
        RunSummary.of(listOf(PlantResult(10.0, (stressDaysPerYear * 10).toInt(), 0, 0, 0, 0, 0, emptyList(), emptyList(), null, 0.0, 0)))
}
