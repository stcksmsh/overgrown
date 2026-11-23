// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.caredb

import com.kostavukicevic.plants.engine.CareProfile
import com.kostavukicevic.plants.engine.DroughtAsymmetry
import com.kostavukicevic.plants.engine.WaterStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The compiled care database (assets/caredb.json), as written by overgrown-data's compiler.
 * The engine never sees YAML or this format; it gets a [CareProfile].
 */
@Serializable
data class CareDb(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("data_version") val dataVersion: Int,
    @SerialName("source_commit") val sourceCommit: String,
    val archetypes: Map<String, Archetype> = emptyMap(),
    val species: Map<String, Species> = emptyMap(),
    val redirects: Map<String, String> = emptyMap(),
) {
    /** Follows a retired slug to its current one. */
    fun species(slug: String): Species? = species[slug] ?: redirects[slug]?.let { species[it] }

    companion object {
        const val SUPPORTED_SCHEMA = 1
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CareDb {
            val db = json.decodeFromString(serializer(), text)
            require(db.schemaVersion == SUPPORTED_SCHEMA) { "care database schema ${db.schemaVersion} is not supported" }
            return db
        }
    }
}

@Serializable
data class Archetype(val name: String, val defaults: CareGroups = CareGroups())

@Serializable
data class Species(
    val archetype: String,
    val taxon: Taxon,
    @SerialName("common_names") val commonNames: Map<String, List<CommonName>> = emptyMap(),
    @SerialName("sold_as") val soldAs: List<String> = emptyList(),
    val light: Light? = null,
    val water: Water? = null,
    val temperature: Temperature? = null,
    val season: Season? = null,
    val site: Site? = null,
    val engine: EngineFields? = null,
    /** Values that hold only for plants in the ground, in a pot outdoors, or indoors. */
    @SerialName("in_ground") val inGround: CareGroups? = null,
    @SerialName("outdoor_container") val outdoorContainer: CareGroups? = null,
    val indoor: CareGroups? = null,
    val toxicity: Toxicity,
    /** Source URLs per group, for the "where does this come from" view and wrong-data reports. */
    val sources: Map<String, List<String>> = emptyMap(),
) {
    val groups: CareGroups get() = CareGroups(light, water, temperature, season, site, engine)

    /** Care values for one growing context: the context's block over the values that hold everywhere. */
    fun groupsFor(context: Context): CareGroups {
        val block = when (context) {
            Context.IN_GROUND -> inGround
            Context.OUTDOOR_CONTAINER -> outdoorContainer
            Context.INDOOR -> indoor
        } ?: return groups
        return CareGroups(
            light = block.light ?: light,
            water = block.water ?: water,
            temperature = block.temperature ?: temperature,
            season = block.season ?: season,
            site = block.site ?: site,
            engine = block.engine ?: engine,
        )
    }
}

enum class Context { IN_GROUND, OUTDOOR_CONTAINER, INDOOR }

@Serializable
data class CareGroups(
    val light: Light? = null,
    val water: Water? = null,
    val temperature: Temperature? = null,
    val season: Season? = null,
    val site: Site? = null,
    val engine: EngineFields? = null,
)

@Serializable
data class Site(@SerialName("wind_sensitive") val windSensitive: Boolean? = null)

@Serializable
data class Taxon(
    @SerialName("accepted_name") val acceptedName: String,
    val authority: String? = null,
    val rank: String,
    @SerialName("wfo_id") val wfoId: String? = null,
    @SerialName("powo_id") val powoId: String? = null,
    @SerialName("wikidata_qid") val wikidataQid: String? = null,
    @SerialName("gbif_key") val gbifKey: Long? = null,
    val synonyms: List<String> = emptyList(),
)

@Serializable
data class CommonName(val name: String, val source: String)

@Serializable
data class Light(@SerialName("light_class") val lightClass: String? = null)

@Serializable
data class Water(
    @SerialName("water_strategy") val waterStrategy: String? = null,
    @SerialName("waterlogging_sensitive") val waterloggingSensitive: Boolean? = null,
)

@Serializable
data class Temperature(
    @SerialName("min_temp_c") val minTempC: Double? = null,
    @SerialName("frost_class") val frostClass: String? = null,
    @SerialName("root_kill_temp_c") val rootKillTempC: Double? = null,
)

@Serializable
data class Season(
    val dormancy: String? = null,
    @SerialName("feeding_months_northern_hemisphere") val feedingMonthsNorthernHemisphere: List<Int>? = null,
    @SerialName("feeding_interval_weeks") val feedingIntervalWeeks: Int? = null,
    @SerialName("repot_interval_months") val repotIntervalMonths: Int? = null,
)

@Serializable
data class EngineFields(
    @SerialName("dry_fraction") val dryFraction: Double? = null,
    @SerialName("water_use_factor") val waterUseFactor: Double? = null,
    @SerialName("drought_asymmetry") val droughtAsymmetry: String? = null,
    @SerialName("winter_rules") val winterRules: Boolean? = null,
    @SerialName("root_freeze_alerts") val rootFreezeAlerts: Boolean? = null,
)

/** Never a "safe" value: [NO_KNOWN_TOXICITY] is not the same as harmless, and [UNKNOWN] is the default. */
enum class ToxicityLevel { TOXIC, MILD_IRRITANT, NO_KNOWN_TOXICITY, UNKNOWN;
    companion object {
        fun of(s: String?) = when (s) {
            "toxic" -> TOXIC
            "mild_irritant" -> MILD_IRRITANT
            "no_known_toxicity" -> NO_KNOWN_TOXICITY
            else -> UNKNOWN
        }
    }
}

@Serializable
data class Toxicity(
    val cats: String = "unknown",
    val dogs: String = "unknown",
    val horses: String = "unknown",
    val humans: String = "unknown",
) {
    val forCats get() = ToxicityLevel.of(cats)
    val forDogs get() = ToxicityLevel.of(dogs)
    val forHorses get() = ToxicityLevel.of(horses)
    val forHumans get() = ToxicityLevel.of(humans)
}

/**
 * Maps care-data enumerations to the engine's types. Numbers stay in the engine (brief section 4):
 * a missing or unknown value here falls back to the engine's own defaults.
 */
fun CareGroups.toProfile(): CareProfile {
    val strategy = when (water?.waterStrategy) {
        "dry_out_fully" -> WaterStrategy.DRY_OUT_FULLY
        "evenly_moist" -> WaterStrategy.EVENLY_MOIST
        "wet_feet" -> WaterStrategy.WET_FEET
        "epiphyte_soak" -> WaterStrategy.EPIPHYTE_SOAK
        else -> WaterStrategy.DRY_TOP_PART
    }
    val asymmetry = when (engine?.droughtAsymmetry) {
        "always" -> DroughtAsymmetry.ALWAYS
        "in_heat" -> DroughtAsymmetry.IN_HEAT
        else -> DroughtAsymmetry.NONE
    }
    return CareProfile(
        waterStrategy = strategy,
        dryFraction = engine?.dryFraction,
        waterUseFactor = engine?.waterUseFactor ?: 1.0,
        droughtAsymmetry = asymmetry,
        waterloggingSensitive = water?.waterloggingSensitive ?: false,
        winterRules = engine?.winterRules ?: false,
        rootFreezeAlerts = engine?.rootFreezeAlerts ?: false,
        windSensitive = site?.windSensitive ?: false,
    )
}
