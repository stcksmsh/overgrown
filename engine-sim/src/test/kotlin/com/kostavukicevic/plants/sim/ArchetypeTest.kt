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
        val none = RunSummary.of(emptyList())
        val r = ArchetypeResult("fixture", "fixture-archetype", emptyList(), emptyList(), none, none)
        assertTrue(r.noData)
        assertFalse(r.pass)
        assertEquals(null, r.failure)
        assertEquals("no data", r.result)
        val report = archetypeReport(CareDb(schemaVersion = 1, dataVersion = 1, sourceCommit = "fixture"), listOf(r))
        assertTrue("| no data |" in report, report)
        assertFalse("Needing their own entry" in report, report)
    }
}
