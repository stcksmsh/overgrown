// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.sim

import com.kostavukicevic.plants.engine.Baseline
import com.kostavukicevic.plants.engine.CareProfile
import com.kostavukicevic.plants.engine.Coefficients
import com.kostavukicevic.plants.engine.DroughtAsymmetry
import com.kostavukicevic.plants.engine.Engine
import com.kostavukicevic.plants.engine.LightClass
import com.kostavukicevic.plants.engine.Place
import com.kostavukicevic.plants.engine.Plant
import com.kostavukicevic.plants.engine.Pot
import com.kostavukicevic.plants.engine.PotMaterial
import com.kostavukicevic.plants.engine.PotShape
import com.kostavukicevic.plants.engine.RainExposure
import com.kostavukicevic.plants.engine.WaterBalance
import com.kostavukicevic.plants.engine.WaterStrategy
import com.kostavukicevic.plants.engine.WeatherDay
import com.kostavukicevic.plants.engine.WindExposure
import kotlinx.datetime.Month
import java.io.File
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.random.Random

val SITES = mapOf("belgrade" to 44.82, "seville" to 37.39, "edinburgh" to 55.95)

private val outdoorProfiles = listOf(
    "hardy potted conifer" to CareProfile(
        WaterStrategy.DRY_TOP_PART, dryFraction = Coefficients.DRY_FRACTION_CONIFER_IN_POT,
        droughtAsymmetry = DroughtAsymmetry.IN_HEAT, waterloggingSensitive = true, winterRules = true, rootFreezeAlerts = true,
    ),
    "hardy shrub, dry top part" to CareProfile(WaterStrategy.DRY_TOP_PART, rootFreezeAlerts = true),
    "evenly moist perennial" to CareProfile(WaterStrategy.EVENLY_MOIST),
)

private val indoorProfiles = listOf(
    "tropical aroid" to CareProfile(WaterStrategy.DRY_TOP_PART),
    "fern" to CareProfile(WaterStrategy.EVENLY_MOIST, droughtAsymmetry = DroughtAsymmetry.ALWAYS),
    "desert succulent" to CareProfile(WaterStrategy.DRY_OUT_FULLY),
)

private fun <T> Random.pick(xs: List<T>) = xs[nextInt(xs.size)]

private fun drawPot(rng: Random, minL: Double, maxL: Double): Pot {
    val material = rng.pick(listOf(PotMaterial.PLASTIC, PotMaterial.PLASTIC, PotMaterial.GLAZED_CERAMIC, PotMaterial.UNGLAZED_TERRACOTTA))
    return Pot(
        substrateVolumeL = rng.uniform(minL, maxL),
        shape = rng.pick(PotShape.entries),
        material = material,
        drainageHoles = rng.nextDouble() < 0.9,
    )
}

/** Canopy a little wider than the pot, as nursery plants are. */
private fun drawCanopy(rng: Random, pot: Pot): Double {
    val diameterCm = 2 * sqrt(WaterBalance.potTopAreaM2(pot) * 10_000 / PI)
    return diameterCm * rng.uniform(0.8, 1.6)
}

fun outdoorPopulation(latitude: Double, n: Int, rng: Random): List<Pair<String, Plant>> = List(n) {
    val (label, profile) = rng.pick(outdoorProfiles)
    val pot = drawPot(rng, 3.0, 20.0)
    label to Plant(profile, drawCanopy(rng, pot), pot, Place.Outdoor(latitude, rng.pick(RainExposure.entries), rng.pick(WindExposure.entries)))
}

fun indoorPopulation(latitude: Double, n: Int, rng: Random): List<Pair<String, Plant>> = List(n) {
    val (label, profile) = rng.pick(indoorProfiles)
    val pot = drawPot(rng, 1.0, 6.0)
    label to Plant(profile, drawCanopy(rng, pot), pot, Place.Indoor(latitude, rng.pick(LightClass.entries), dryAir = rng.nextBoolean()))
}

/** A typical midsummer day for the site: the mean of all its July days. */
fun midsummer(weather: List<ObsDay>): WeatherDay {
    val july = weather.filter { it.date.month == Month.JULY }
    return WeatherDay(
        july.first().date, july.map { it.tMinC }.average(), july.map { it.tMaxC }.average(), 0.0,
        et0Mm = july.map { it.et0Mm }.average(),
    )
}

data class Row(val scenario: String, val model: String, val results: List<PlantResult>) {
    /** Share of all prompts answered "too wet" or "ok". */
    fun stillWetShare(): Double {
        val prompts = results.sumOf { it.prompts }
        return if (prompts == 0) 0.0 else results.sumOf { it.promptsAnsweredStillWet }.toDouble() / prompts
    }
}

internal fun List<Double>.median(): Double = if (isEmpty()) Double.NaN else sorted().let { s ->
    if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/**
 * Median over plants of each plant's own median error, so a plant with thousands of refills counts once, the same as
 * one with ten. Plants without a dry-out prediction have no error and are left out; NaN when no plant has one.
 */
internal fun List<PlantResult>.perPlantMedian(errors: (PlantResult) -> List<Double>): Double =
    map(errors).filter { it.isNotEmpty() }.map { it.median() }.median()

internal fun List<PlantResult>.plantsWithoutPrediction(): Int = count { it.dryOutErrorsDays.isEmpty() }

fun runAll(plantsPerScenario: Int = 30): List<Row> {
    val rows = ArrayList<Row>()
    for ((site, lat) in SITES) {
        val weather = loadWeather(site)
        val summer = midsummer(weather)
        val baseline = Baseline { Baseline.summerIntervalFromEngine(it, summer) }
        val scenarios = buildList {
            add("$site, outdoor" to outdoorPopulation(lat, plantsPerScenario, Random(site.hashCode())))
            if (site == "belgrade") add("$site, indoor" to indoorPopulation(lat, plantsPerScenario, Random(site.hashCode() + 1)))
        }
        for ((scenario, population) in scenarios) {
            val truths = population.mapIndexed { i, (_, p) -> TruthPlant.draw(p, Random(scenario.hashCode() + i)) }
            val people = population.indices.map { Person.draw(Random(scenario.hashCode() - it)) }
            fun runModel(name: String, withEt0: Boolean, model: com.kostavukicevic.plants.engine.WateringModel<*>) {
                val h = Harness(weather, withEt0)
                @Suppress("UNCHECKED_CAST")
                val m = model as com.kostavukicevic.plants.engine.WateringModel<Any?>
                rows += Row(scenario, name, truths.indices.map { h.run(m, truths[it], people[it], seed = it) })
            }
            runModel("engine, provider ET0", true, Engine)
            if (scenario.endsWith("outdoor")) runModel("engine, Hargreaves only", false, Engine)
            runModel("baseline", true, baseline)
        }
    }
    return rows
}

fun report(rows: List<Row>): String = buildString {
    appendLine("# Simulation harness report")
    appendLine()
    appendLine("Generated by `./gradlew :engine-sim:simReport`. Deterministic: the same code and data give the same report.")
    appendLine()
    appendLine("**What this shows and does not show.** The harness runs each model against a simulated plant whose behaviour is richer than the engine (hourly demand from sun angle and vapour-pressure deficit scaled by Penman-Monteith ET0, a random plant coefficient, bucket size, pot-wall loss, rain capture and wind sensitivity, uptake that falls as soil dries, rain at a random hour) and a simulated person whose soil-moisture answers are noisy and biased. It shows robustness to plants the engine does not describe exactly. It does not show real-world accuracy: that needs the kitchen-scale measurements.")
    appendLine()
    appendLine("Weather: 2015-2024 daily history from the Open-Meteo Historical Weather API (CC BY 4.0; reanalysis from Copernicus C3S). Models see each day's weather and a perfect 7-day forecast; real forecasts are worse.")
    appendLine()
    appendLine("The baseline uses the same inputs and learning rule as the engine. Its summer interval is the engine's own dry-out time for the plant on the site's mean July day with K = 1, so both start from the same knowledge.")
    appendLine()
    appendLine("## Headline: prompts answered still wet")
    appendLine()
    appendLine("The share of prompts (water now, or check the soil) the person answered \"too wet\" or \"ok\": each is a prompt that came before the plant needed water, and the cost the person feels most. The engine asks for a soil check while it is unsure and the plant could already be dry, so more of its prompts find wet soil than the baseline's, which waits for its interval and lets the plant dry further (see stress days below).")
    appendLine()
    appendLine("| Scenario | Engine, still wet | Baseline, still wet |")
    appendLine("|---|---:|---:|")
    for ((scenario, scenarioRows) in rows.groupBy { it.scenario }) {
        val share = { model: String -> scenarioRows.first { it.model == model }.stillWetShare() }
        appendLine("| $scenario | ${pct(share("engine, provider ET0"))} | ${pct(share("baseline"))} |")
    }
    appendLine()
    appendLine("## Metrics")
    appendLine()
    appendLine("Per plant per year unless stated, averaged over plants.")
    appendLine()
    appendLine("- **Stress days**: days the true soil got drier than the plant's stress threshold (dry fraction + 0.3, at most 0.98).")
    appendLine("- **Over-wet days**: days the true soil stayed near saturation (depletion under 0.05) within 3 days of a watering given while it was still wet.")
    appendLine("- **Wet waterings**: waterings given while the true soil was still wet (depletion under 0.3).")
    appendLine("- **Prompts**, and the **share answered still wet** (\"too wet\" or \"ok\").")
    appendLine("- **Dry-out error**: median over plants of each plant's median error, over its refills with a dry-out prediction, in the predicted day the plant reaches its dry fraction; in days and relative to the true cycle. Every plant counts once however many refills it had. A refill gets a prediction only when both the model and the true plant reach the dry fraction within ${Harness.HORIZON_DAYS} days of it and before the weather record ends, and a watering prompted by the repot rule gets none, so a plant can be refilled often and still have no prediction. Such plants have no error and are left out; their number is in the last column.")
    appendLine("- **Answers to accurate**: median soil-moisture answers (check-ins, plus answers given when watering) before the dry-out prediction is within a day or 20 % for three refills in a row; with the share of plants that never got there. Zero means the starting prediction was already that good.")
    appendLine("- **Use error**: mean absolute relative error of predicted daily water use, on days the true plant used at least 20 ml. The real-world version of this metric is against the scale.")
    appendLine()
    appendLine("| Scenario | Model | Stress days | Over-wet days | Wet waterings | Prompts | Still wet | Dry-out error, days | Dry-out error, rel. | Answers to accurate | Never accurate | Use error | Repot prompts | Plants with no dry-out prediction |")
    appendLine("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for (r in rows) {
        val perYear = { f: (PlantResult) -> Number -> r.results.map { f(it).toDouble() / it.years }.average() }
        val untils = r.results.mapNotNull { it.answersUntilAccurate?.toDouble() }
        val never = r.results.count { it.answersUntilAccurate == null }.toDouble() / r.results.size
        appendLine(
            "| ${r.scenario} | ${r.model} | ${f1(perYear { it.stressDays })} | ${f1(perYear { it.overwetDays })} | " +
                "${f1(perYear { it.wateringsWhileWet })} | ${f1(perYear { it.prompts })} | ${pct(r.stillWetShare())} | " +
                "${f1(r.results.perPlantMedian { it.dryOutErrorsDays })} | ${pct(r.results.perPlantMedian { it.dryOutErrorsRelative })} | " +
                "${f1(untils.median())} | ${pct(never)} | ${pct(r.results.map { it.useError }.average())} | ${f1(perYear { it.repotPrompts })} | ${r.results.plantsWithoutPrediction()} |",
        )
    }
}

internal fun f1(x: Double) = if (x.isNaN()) "n/a" else "%.1f".format(java.util.Locale.ROOT, x)
internal fun pct(x: Double) = if (x.isNaN()) "n/a" else "%.0f %%".format(java.util.Locale.ROOT, x * 100)

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/reports/sim-report.md")
    out.parentFile?.mkdirs()
    out.writeText(report(runAll()))
    println("wrote ${out.absolutePath}")
}
