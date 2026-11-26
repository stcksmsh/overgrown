// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.caredb

import com.kostavukicevic.plants.engine.Alert
import com.kostavukicevic.plants.engine.AlertType
import com.kostavukicevic.plants.engine.Alerts
import com.kostavukicevic.plants.engine.CareProfile
import com.kostavukicevic.plants.engine.DroughtAsymmetry
import com.kostavukicevic.plants.engine.LightClass
import com.kostavukicevic.plants.engine.Place
import com.kostavukicevic.plants.engine.Plant
import com.kostavukicevic.plants.engine.Pot
import com.kostavukicevic.plants.engine.RainExposure
import com.kostavukicevic.plants.engine.WaterStrategy
import com.kostavukicevic.plants.engine.WeatherDay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** Shaped like the compiled ocimum-basilicum entry: tender, 10 degC minimum, engine flags explicitly unknown. */
    private val basil = CareDb.parse(
        """
        {"schema_version":1,"data_version":1,"source_commit":"x","archetypes":{
          "tender":{"name":"Tender","axes":{},"defaults":{"water":{"water_strategy":"evenly_moist"}}}
        },"species":{
          "ocimum-basilicum":{"archetype":"outdoor-leafy-herb","taxon":{"accepted_name":"Ocimum basilicum","rank":"species"},"toxicity":{},
            "water":{"water_strategy":"evenly_moist"},
            "temperature":{"frost_class":"tender","min_temp_c":10.0},
            "engine":{"winter_rules":null,"root_freeze_alerts":null}},
          "known-off":{"archetype":"a","taxon":{"accepted_name":"Known off","rank":"species"},"toxicity":{},
            "engine":{"winter_rules":false,"root_freeze_alerts":false}}
        }}
        """.trimIndent(),
    )

    private val nov = LocalDate(2026, Month.NOVEMBER, 25)
    private val belowMinimum = WeatherDay(nov, 9.0, 14.0, 0.0)
    private val belowZero = WeatherDay(LocalDate(2026, Month.NOVEMBER, 26), -1.0, 4.0, 0.0)
    private val rootFreeze = WeatherDay(LocalDate(2026, Month.NOVEMBER, 27), -4.0, 1.0, 0.0)
    private val forecast = listOf(belowMinimum, belowZero, rootFreeze)

    private fun potOutside(profile: CareProfile) = Plant(profile, 25.0, Pot(5.0), Place.Outdoor(44.82, RainExposure.OPEN))
    private fun potInside(profile: CareProfile) = Plant(profile, 25.0, Pot(5.0), Place.Indoor(44.82, LightClass.HIGH))

    @Test
    fun unknownRootFreezeAlertsAreOnForAnOutdoorPot() {
        val species = basil.species("ocimum-basilicum")!!.groupsFor(Context.OUTDOOR_CONTAINER).toProfile()
        assertEquals(listOf(Alert(AlertType.ROOT_FREEZE_CAUTION, rootFreeze.date)), Alerts.forecastAlerts(potOutside(species), forecast))
        // An archetype without an engine block is unknown in the same way.
        val archetype = basil.archetypes.getValue("tender").defaults.toProfile()
        assertEquals(listOf(Alert(AlertType.ROOT_FREEZE_CAUTION, rootFreeze.date)), Alerts.forecastAlerts(potOutside(archetype), forecast))
    }

    @Test
    fun knownFalseRootFreezeAlertsStayOffOutdoors() {
        val p = basil.species("known-off")!!.groupsFor(Context.OUTDOOR_CONTAINER).toProfile()
        assertEquals(false, p.rootFreezeAlerts)
        assertTrue(Alerts.forecastAlerts(potOutside(p), forecast).isEmpty())
    }

    @Test
    fun unknownRootFreezeAlertsIndoorsChangeNothing() {
        val unknown = basil.species("ocimum-basilicum")!!.groupsFor(Context.INDOOR).toProfile()
        val known = basil.species("known-off")!!.groupsFor(Context.INDOOR).toProfile()
        assertTrue(Alerts.forecastAlerts(potInside(unknown), forecast).isEmpty())
        assertEquals(Alerts.forecastAlerts(potInside(known), forecast), Alerts.forecastAlerts(potInside(unknown), forecast))
    }

    /**
     * The engine reads neither min_temp_c nor frost_class: a tender pot gets no alert at its 10 degC
     * minimum or just below 0 degC, only at the root-freeze thresholds.
     */
    @Test
    fun aTenderPotGetsNoAlertAtItsMinimumTemperature() {
        val p = basil.species("ocimum-basilicum")!!.groupsFor(Context.OUTDOOR_CONTAINER).toProfile()
        assertTrue(Alerts.forecastAlerts(potOutside(p), listOf(belowMinimum, belowZero)).isEmpty())
    }

    /** Winter rules change the watering action only, so an unknown value removes no cold or frost alert. */
    @Test
    fun winterRulesDoNotChangeAlerts() {
        val p = basil.species("ocimum-basilicum")!!.groupsFor(Context.OUTDOOR_CONTAINER).toProfile()
        assertEquals(false, p.winterRules)
        assertEquals(
            Alerts.forecastAlerts(potOutside(p.copy(winterRules = true)), forecast),
            Alerts.forecastAlerts(potOutside(p), forecast),
        )
    }
}
