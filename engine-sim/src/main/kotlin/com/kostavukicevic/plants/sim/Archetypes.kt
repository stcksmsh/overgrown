// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.sim

import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.caredb.Context
import com.kostavukicevic.plants.caredb.toProfile
import com.kostavukicevic.plants.engine.CareProfile
import com.kostavukicevic.plants.engine.Coefficients
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

data class RunSummary(
    val stressDaysPerYear: Double,
    val dryOutErrorDays: Double,
    val dryOutErrorRelative: Double,
    val plantsWithoutPrediction: Int,
) {
    companion object {
        fun of(results: List<PlantResult>) = RunSummary(
            stressDaysPerYear = results.map { it.stressDays / it.years }.average(),
            dryOutErrorDays = results.perPlantMedian { it.dryOutErrorsDays },
            dryOutErrorRelative = results.perPlantMedian { it.dryOutErrorsRelative },
            plantsWithoutPrediction = results.plantsWithoutPrediction(),
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
    /** At least one run had no refill with a dry-out prediction, so only the stress criterion can be judged. */
    val dryOutUnknown get() = speciesRun.dryOutErrorDays.isNaN() || archetypeRun.dryOutErrorDays.isNaN()
    val dryOutOk get() = archetypeRun.dryOutErrorDays <= speciesRun.dryOutErrorDays * (1 + DRY_OUT_ERROR_TOLERANCE)
    val failure: String? get() = when {
        !stressOk && !dryOutUnknown && !dryOutOk -> "stress and dry-out"
        !stressOk -> "stress"
        !dryOutUnknown && !dryOutOk -> "dry-out"
        else -> null
    }
    /** Stress is within the allowance but dry-out cannot be judged: neither a pass nor a fail. */
    val noData get() = failure == null && dryOutUnknown
    val pass get() = failure == null && !dryOutUnknown
    val result get() = when {
        failure != null -> "fail: $failure"
        noData -> "no data"
        else -> "pass"
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
    val noData = results.filter { it.noData }
    if (noData.isNotEmpty()) {
        appendLine("No data (stress within the allowance, but at least one run had no refill with a dry-out prediction): ${noData.joinToString { it.species }}.")
    }
    appendLine()
    val failing = results.filter { it.failure != null }
    appendLine(
        if (failing.isEmpty()) "No species needs its own entry on this evidence."
        else "Needing their own entry: ${failing.joinToString { "${it.species} (${it.failure})" }}.",
    )
    for (r in failing.filter { it.failure == "stress" && it.differingFields == listOf("winter rules") }) {
        val own = db.species.getValue(r.species).groupsFor(Context.OUTDOOR_CONTAINER).toProfile()
        val cold = own.minTempC?.takeIf { it > Coefficients.WINTER_MODE_MEAN_C }
        appendLine()
        appendLine(
            "**What the ${r.species} failure means.** The only watering field that differs is winter rules, which act only in winter mode " +
                "(the mean of the last ${Coefficients.WINTER_MODE_WINDOW_DAYS} days below ${f1(Coefficients.WINTER_MODE_MEAN_C)} degC), so the two runs can diverge only from a winter-mode day on. " +
                "The simulated true plant takes no harm from cold or from wet soil in winter, so the stress metric counts the cost of winter rules (fewer prompts, drier soil) and never their benefit. " +
                (
                    if (cold != null) "The simulated plant also stays outdoors through every winter: the extra stress days begin in weeks whose " +
                        "${Coefficients.WINTER_MODE_WINDOW_DAYS}-day mean is below ${f1(Coefficients.WINTER_MODE_MEAN_C)} degC, under its own minimum temperature of ${f1(cold)} degC" +
                        (own.frostClass?.let { " (frost class ${it.name.lowercase()})" } ?: "") + ". "
                    else ""
                ) +
                "The verdict that it needs its own entry agrees with its care data, which leaves winter rules unset where the archetype sets them, but it rests mostly on this limit of the simulation.",
        )
    }
    val toSplit = byArchetype.filter { (_, members) -> members.size >= 2 && members.all { it.failure != null } && members.map { it.failure }.toSet().size == 1 }
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
    appendLine("Metrics are the simulation report's. **Stress days**: per plant per year, days the true soil got drier than the true plant's stress threshold (its dry fraction + 0.3, at most 0.98). **Dry-out error**: median over plants of each plant's median error over its refills with a dry-out prediction, so every plant counts once however many refills it had: the error, in days, between the day the engine predicts the plant reaches the dry fraction it was told and the day the true plant reaches its true dry fraction; relative to the true cycle in brackets. Plants with no refill with a dry-out prediction have no error and are left out of the median; when a run has any, their number across its scenarios follows the error.")
    appendLine()
    appendLine("A species passes when the archetype-only run adds at most ${f1(EXTRA_STRESS_DAYS_ALLOWANCE)} stress day per plant per year (PRIOR, not measured: under 0.3 % of days, a single short dry spell a year that any hand-watered plant sees anyway, so not worth a species entry of its own) and its dry-out error in days is at most ${pct(DRY_OUT_ERROR_TOLERANCE)} above the species run's. Errors are whole days, so each plant's median is a whole or half day and the median over plants a multiple of a quarter day; below a median of 1.25 days, ${pct(DRY_OUT_ERROR_TOLERANCE)} above it allows no increase at all, so in practice the rule means \"no worse median\", which is stricter than ${pct(DRY_OUT_ERROR_TOLERANCE)}. When at least one run has no refill with a dry-out prediction, only the stress criterion is judged: a stress failure is still a failure, otherwise the species is reported as no data, not as a pass or fail.")
    appendLine()
    appendLine("## Results")
    appendLine()
    appendLine("| Species | Archetype | Watering fields that differ | Stress days, species | Stress days, archetype | Extra | Dry-out error, species | Dry-out error, archetype | Result |")
    appendLine("|---|---|---|---:|---:|---:|---:|---:|---|")
    for (r in results) {
        val diff = if (r.differingFields.isEmpty()) "none (identical by construction)" else r.differingFields.joinToString()
        appendLine(
            "| ${r.species} | ${r.archetype} | $diff | ${f1(r.speciesRun.stressDaysPerYear)} | ${f1(r.archetypeRun.stressDaysPerYear)} | ${f1(r.extraStressDays)} | " +
                "${dryOut(r.speciesRun)} | ${dryOut(r.archetypeRun)} | " +
                "${r.result} |",
        )
    }
    appendLine()
    appendLine("Scenarios per species:")
    appendLine()
    for (r in results) appendLine("- ${r.species}: ${r.scenarios.joinToString("; ")}")
}

private fun dryOut(run: RunSummary) = "${f1(run.dryOutErrorDays)} d (${pct(run.dryOutErrorRelative)})" +
    if (run.plantsWithoutPrediction > 0) ", ${run.plantsWithoutPrediction} with no dry-out prediction" else ""

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: <caredb.json> <report.md>" }
    val db = CareDb.parse(File(args[0]).readText())
    val out = File(args[1])
    out.parentFile?.mkdirs()
    out.writeText(archetypeReport(db, runArchetypeTest(db)))
    println("wrote ${out.absolutePath}")
}
