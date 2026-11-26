// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.caredb

import com.kostavukicevic.plants.engine.DroughtAsymmetry
import com.kostavukicevic.plants.engine.WaterStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CareDbTest {

    /** Shaped like overgrown-data's compiler output for a reviewed conifer. */
    private val asset = """
        {
          "archetypes" : {
            "hardy-potted-conifer" : {
              "axes" : { "water_strategy" : "dry_top_part" },
              "defaults" : {
                "engine" : { "basis" : "archetype_default", "dry_fraction" : 0.5, "drought_asymmetry" : "in_heat", "root_freeze_alerts" : true, "water_use_factor" : 1.0, "winter_rules" : true },
                "water" : { "water_strategy" : "dry_top_part", "waterlogging_sensitive" : true }
              },
              "name" : "Hardy potted conifer"
            }
          },
          "data_version" : 3,
          "redirects" : { "chamaecyparis-old" : "chamaecyparis-lawsoniana" },
          "schema_version" : 1,
          "source_commit" : "abc",
          "species" : {
            "chamaecyparis-lawsoniana" : {
              "archetype" : "hardy-potted-conifer",
              "common_names" : { "en" : [ { "name" : "Lawson's Cypress", "source" : "https://www.wikidata.org/wiki/Special:EntityData/Q161360.json" } ] },
              "engine" : { "basis" : "archetype_default", "dry_fraction" : 0.5, "drought_asymmetry" : "in_heat", "root_freeze_alerts" : true, "water_use_factor" : 1.0, "winter_rules" : true },
              "sources" : { "water" : [ "https://plantsservices.sc.egov.usda.gov/api/PlantCharacteristics/15030" ] },
              "taxon" : { "accepted_name" : "Chamaecyparis lawsoniana", "gbif_key" : 2683866, "rank" : "species", "wfo_id" : "wfo-0000599456" },
              "temperature" : { "frost_class" : "hardy", "root_kill_temp_c" : null },
              "toxicity" : { "cats" : "unknown", "dogs" : "unknown", "horses" : "unknown", "humans" : "unknown" },
              "water" : { "water_strategy" : "dry_top_part", "waterlogging_sensitive" : true },
              "site" : { "wind_sensitive" : true },
              "in_ground" : { "temperature" : { "frost_class" : "very_hardy", "min_temp_c" : -24.4 }, "water" : { "water_strategy" : "evenly_moist" } }
            }
          }
        }
    """.trimIndent()

    @Test
    fun speciesMapsToTheEngineProfile() {
        val db = CareDb.parse(asset)
        val s = db.species("chamaecyparis-lawsoniana")!!
        assertEquals("Chamaecyparis lawsoniana", s.taxon.acceptedName)
        assertNull(s.temperature?.rootKillTempC)
        val p = s.groups.toProfile()
        assertEquals(WaterStrategy.DRY_TOP_PART, p.waterStrategy)
        assertEquals(0.5, p.dryFraction)
        assertEquals(DroughtAsymmetry.IN_HEAT, p.droughtAsymmetry)
        assertEquals(true, p.waterloggingSensitive)
        assertEquals(true, p.winterRules)
        assertEquals(true, p.rootFreezeAlerts)
    }

    @Test
    fun contextBlocksApplyOnlyInTheirContext() {
        val s = CareDb.parse(asset).species("chamaecyparis-lawsoniana")!!
        assertEquals(true, s.groups.toProfile().windSensitive)
        assertEquals(WaterStrategy.DRY_TOP_PART, s.groupsFor(Context.OUTDOOR_CONTAINER).toProfile().waterStrategy)
        assertEquals(WaterStrategy.EVENLY_MOIST, s.groupsFor(Context.IN_GROUND).toProfile().waterStrategy)
        assertEquals(-24.4, s.groupsFor(Context.IN_GROUND).temperature?.minTempC)
        assertNull(s.groupsFor(Context.OUTDOOR_CONTAINER).temperature?.minTempC)
    }

    @Test
    fun aContextBlockOverridesFieldByFieldAndItsExplicitNullStaysUnknown() {
        val s = CareDb.parse(
            """
            {"schema_version":1,"data_version":1,"source_commit":"x","archetypes":{},"species":{"x-y":{
              "archetype":"a","taxon":{"accepted_name":"X y","rank":"species"},"toxicity":{},
              "temperature":{"frost_class":"frost_free","min_temp_c":5.0},
              "indoor":{"temperature":{"min_temp_c":null}},
              "outdoor_container":{"temperature":{"root_kill_temp_c":-2.0}}
            }}}
            """.trimIndent(),
        ).species("x-y")!!
        val indoor = s.groupsFor(Context.INDOOR).temperature!!
        assertEquals("frost_free", indoor.frostClass)
        assertNull(indoor.minTempC)
        val pot = s.groupsFor(Context.OUTDOOR_CONTAINER).temperature!!
        assertEquals("frost_free", pot.frostClass)
        assertEquals(5.0, pot.minTempC)
        assertEquals(-2.0, pot.rootKillTempC)
        assertEquals(5.0, s.groupsFor(Context.IN_GROUND).temperature?.minTempC)
    }

    @Test
    fun archetypeAloneGivesAProfile() {
        val p = CareDb.parse(asset).archetypes.getValue("hardy-potted-conifer").defaults.toProfile()
        assertEquals(0.5, p.dryFraction)
        assertEquals(true, p.winterRules)
    }

    @Test
    fun retiredSlugsRedirect() {
        assertEquals("Chamaecyparis lawsoniana", CareDb.parse(asset).species("chamaecyparis-old")?.taxon?.acceptedName)
    }

    @Test
    fun toxicityIsNeverSafe() {
        val t = CareDb.parse(asset).species("chamaecyparis-lawsoniana")!!.toxicity
        assertEquals(ToxicityLevel.UNKNOWN, t.forCats)
        assertEquals(ToxicityLevel.UNKNOWN, Toxicity(cats = "safe").forCats)
    }

    @Test
    fun anUnsupportedSchemaIsRefused() {
        assertFailsWith<IllegalArgumentException> { CareDb.parse(asset.replace("\"schema_version\" : 1", "\"schema_version\" : 2")) }
    }

    @Test
    fun theEmptyAssetOfAnUnreviewedDatabaseParses() {
        val db = CareDb.parse("""{"schema_version":1,"data_version":1,"source_commit":"x","archetypes":{},"species":{}}""")
        assertEquals(0, db.species.size)
    }
}
