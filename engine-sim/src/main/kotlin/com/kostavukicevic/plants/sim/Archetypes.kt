// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.sim

import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.caredb.Context
import com.kostavukicevic.plants.caredb.toProfile
import com.kostavukicevic.plants.engine.CareProfile
import com.kostavukicevic.plants.engine.Engine
import com.kostavukicevic.plants.engine.WaterBalance
import java.io.File
import kotlin.random.Random

/**
 * PRIOR: extra stress days per plant per year the archetype-only run may add (brief section 10, G5 "small
 * allowance"). One day a year is under 0.3 % of days: a single short dry spell a year is a slip any hand-watered
 * plant sees anyway, so it is not worth a species entry of its own. Not measured; the scale data can revise it.
 */
const val EXTRA_STRESS_DAYS_ALLOWANCE = 1.0

/** Brief section 10, G5: the archetype run's dry-out error may be at most this much worse than the species run's. */
const val DRY_OUT_ERROR_TOLERANCE = 0.2

private const val PLANTS_PER_SCENARIO = 30
private const val INDOOR_SITE = "belgrade"

/** The profile fields the watering model reads. The rest (wind, frost, cold, root-freeze, waterlogging) only raise alerts. */
val WATERING_FIELDS: List<Pair<String, (CareProfile) -> Any?>> = listOf(
    "water strategy" to { it.waterStrategy },
    "dry fraction" to { WaterBalance.dryFraction(it) },
    "water use factor" to { it.waterUseFactor },
    "drought asymmetry" to { it.droughtAsymmetry },
    "winter rules" to { it.winterRules },
)

data class RunSummary(val stressDaysPerYear: Double, val dryOutErrorDays: Double, val dryOutErrorRelative: Double) {
    companion object {
        fun of(results: List<PlantResult>) = RunSummary(
            stressDaysPerYear = results.map { it.stressDays / it.years }.average(),
            dryOutErrorDays = results.flatMap { it.dryOutErrorsDays }.median(),
            dryOutErrorRelative = results.flatMap { it.dryOutErrorsRelative }.median(),
        )
    }
}

data class ArchetypeResult(
    val species: String,
    val archetype: String,
    val scenarios: List<String>,
    /** Watering fields where the species entry and the archetype defaults differ, in any context run. */
    val differingFields: List<String>,
    val speciesRun: RunSummary,
    val archetypeRun: RunSummary,
) {
    val extraStressDays get() = archetypeRun.stressDaysPerYear - speciesRun.stressDaysPerYear
    val stressOk get() = extraStressDays <= EXTRA_STRESS_DAYS_ALLOWANCE
    val dryOutOk get() = archetypeRun.dryOutErrorDays <= speciesRun.dryOutErrorDays * (1 + DRY_OUT_ERROR_TOLERANCE)
    val pass get() = stressOk && dryOutOk
    val failure: String? get() = when {
        pass -> null
        !stressOk && !dryOutOk -> "stress and dry-out"
        !stressOk -> "stress"
        else -> "dry-out"
    }
}

/**
 * Runs the engine twice over the same simulated plants, people and weather: once told the species profile, once
 * the archetype's. The true plant is the species in both runs; only what the engine is told changes. Outdoor
 * containers run on every site, indoor plants in Belgrade, as in the simulation report.
 */
fun compare(
    species: String,
    archetype: String,
    contexts: List<Context>,
    speciesProfile: (Context) -> CareProfile,
    archetypeProfile: CareProfile,
    plantsPerScenario: Int = PLANTS_PER_SCENARIO,
): ArchetypeResult {
    val scenarios = contexts.flatMap { ctx ->
        if (ctx == Context.INDOOR) listOf("$INDOOR_SITE, indoor" to ctx) else SITES.keys.map { "$it, outdoor container" to ctx }
    }
    val speciesResults = ArrayList<PlantResult>()
    val archetypeResults = ArrayList<PlantResult>()
    for ((scenario, ctx) in scenarios) {
        val site = scenario.substringBefore(',')
        val lat = SITES.getValue(site)
        val rng = Random(scenario.hashCode())
        val population = if (ctx == Context.INDOOR) indoorPopulation(lat, plantsPerScenario, rng) else outdoorPopulation(lat, plantsPerScenario, rng)
        val profile = speciesProfile(ctx)
        val truths = population.mapIndexed { i, (_, p) -> TruthPlant.draw(p.copy(profile = profile), Random(scenario.hashCode() + i)) }
        val people = population.indices.map { Person.draw(Random(scenario.hashCode() - it)) }
        val h = Harness(loadWeather(site), withEt0 = true)
        for (i in truths.indices) {
            speciesResults += h.run(Engine, truths[i], people[i], seed = i)
            archetypeResults += h.run(Engine, truths[i], people[i], seed = i, plant = truths[i].plant.copy(profile = archetypeProfile))
        }
    }
    val differing = WATERING_FIELDS.filter { (_, field) -> contexts.any { field(speciesProfile(it)) != field(archetypeProfile) } }.map { it.first }
    return ArchetypeResult(species, archetype, scenarios.map { it.first }, differing, RunSummary.of(speciesResults), RunSummary.of(archetypeResults))
}

/** Contexts a shipped species is tested in: indoors for an indoor archetype or an explicit indoor block, else an outdoor container. */
fun contextsFor(db: CareDb, slug: String): List<Context> {
    val s = db.species.getValue(slug)
    val indoorArchetype = s.archetype.startsWith("indoor-")
    return buildList {
        if (!indoorArchetype) add(Context.OUTDOOR_CONTAINER)
        if (indoorArchetype || s.indoor != null) add(Context.INDOOR)
    }
}

/** Every species in the asset, resolved to profiles the way the app's Planner.profile does. */
fun runArchetypeTest(db: CareDb, plantsPerScenario: Int = PLANTS_PER_SCENARIO): List<ArchetypeResult> = db.species.keys.sorted().map { slug ->
    val s = db.species.getValue(slug)
    val archetype = checkNotNull(db.archetypes[s.archetype]) { "$slug names unknown archetype ${s.archetype}" }
    compare(slug, s.archetype, contextsFor(db, slug), { s.groupsFor(it).toProfile() }, archetype.defaults.toProfile(), plantsPerScenario)
}

fun archetypeReport(db: CareDb, results: List<ArchetypeResult>): String = buildString {
    val passed = results.count { it.pass }
    val byArchetype = results.groupBy { it.archetype }
    appendLine("# Archetype test (part of gate G5)")
    appendLine()
    appendLine("Generated by `./gradlew :engine-sim:archetypeReport` from the compiled care asset (data version ${db.dataVersion}, overgrown-data commit `${db.sourceCommit}`). Deterministic: the same code and data give the same report.")
    appendLine()
    appendLine("## Verdict")
    appendLine()
    appendLine("$passed of ${results.size} species pass.")
    if (results.size < 5) {
        appendLine("The brief's rule is at least four in five species. With fewer than five species shipped it is not yet meaningful, so this is reported as a ratio, not as a pass or fail of the gate.")
    } else {
        appendLine(if (passed * 5 >= results.size * 4) "That meets the rule of at least four in five." else "That does not meet the rule of at least four in five.")
    }
    appendLine()
    val failing = results.filter { !it.pass }
    appendLine(
        if (failing.isEmpty()) "No species needs its own entry on this evidence."
        else "Needing their own entry: ${failing.joinToString { "${it.species} (${it.failure})" }}.",
    )
    val toSplit = byArchetype.filter { (_, members) -> members.size >= 2 && members.all { !it.pass } && members.map { it.failure }.toSet().size == 1 }
    appendLine()
    appendLine(
        if (toSplit.isEmpty()) "No archetype has every member failing the same way, so none is to be split."
        else "To split, every member failing the same way: ${toSplit.keys.joinToString()}.",
    )
    val single = byArchetype.count { it.value.size == 1 }
    if (single > 0) appendLine("$single of ${byArchetype.size} archetypes tested have a single shipped member, so a split cannot be judged for them yet.")
    appendLine()
    appendLine("## Method")
    appendLine()
    appendLine("For each species the harness simulates $PLANTS_PER_SCENARIO plants per scenario (pots, canopies and places drawn as in the simulation report; true plant coefficients drawn as the harness does) on 2015-2024 weather with the provider's ET0. Each plant is run twice with the same weather, person and random seed: once with the engine told the species entry for the context, once told only the archetype's defaults. The true plant is the species in both runs; only the engine's profile differs. Profiles come from the compiled asset through the mapping the app uses (caredb `groupsFor(context).toProfile()` for a species, `defaults.toProfile()` for an archetype).")
    appendLine()
    appendLine("Contexts: an outdoor container on every site (Belgrade, Seville, Edinburgh), and indoors in Belgrade when the archetype is an indoor one or the species has an indoor block.")
    appendLine()
    appendLine("Fields exercised: the ones the watering model reads (${WATERING_FIELDS.joinToString { it.first }}). Wind sensitivity, frost class, minimum temperature, root-freeze alerts and waterlogging sensitivity only raise alerts and do not change watering, so they cannot move these metrics. A species equal to its archetype on every watering field gives two identical runs by construction.")
    appendLine()
    appendLine("Metrics are the simulation report's. **Stress days**: per plant per year, days the true soil got drier than the true plant's stress threshold (its dry fraction + 0.3, at most 0.98). **Dry-out error**: median over all refills of the error, in days, between the day the engine predicts the plant reaches the dry fraction it was told and the day the true plant reaches its true dry fraction; relative to the true cycle in brackets.")
    appendLine()
    appendLine("A species passes when the archetype-only run adds at most ${f1(EXTRA_STRESS_DAYS_ALLOWANCE)} stress day per plant per year (PRIOR, not measured: under 0.3 % of days, a single short dry spell a year that any hand-watered plant sees anyway, so not worth a species entry of its own) and its dry-out error in days is at most ${pct(DRY_OUT_ERROR_TOLERANCE)} above the species run's.")
    appendLine()
    appendLine("## Results")
    appendLine()
    appendLine("| Species | Archetype | Watering fields that differ | Stress days, species | Stress days, archetype | Extra | Dry-out error, species | Dry-out error, archetype | Result |")
    appendLine("|---|---|---|---:|---:|---:|---:|---:|---|")
    for (r in results) {
        val diff = if (r.differingFields.isEmpty()) "none (identical by construction)" else r.differingFields.joinToString()
        appendLine(
            "| ${r.species} | ${r.archetype} | $diff | ${f1(r.speciesRun.stressDaysPerYear)} | ${f1(r.archetypeRun.stressDaysPerYear)} | ${f1(r.extraStressDays)} | " +
                "${f1(r.speciesRun.dryOutErrorDays)} d (${pct(r.speciesRun.dryOutErrorRelative)}) | ${f1(r.archetypeRun.dryOutErrorDays)} d (${pct(r.archetypeRun.dryOutErrorRelative)}) | " +
                "${if (r.pass) "pass" else "fail: ${r.failure}"} |",
        )
    }
    appendLine()
    appendLine("Scenarios per species:")
    appendLine()
    for (r in results) appendLine("- ${r.species}: ${r.scenarios.joinToString("; ")}")
}

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: <caredb.json> <report.md>" }
    val db = CareDb.parse(File(args[0]).readText())
    val out = File(args[1])
    out.parentFile?.mkdirs()
    out.writeText(archetypeReport(db, runArchetypeTest(db)))
    println("wrote ${out.absolutePath}")
}
