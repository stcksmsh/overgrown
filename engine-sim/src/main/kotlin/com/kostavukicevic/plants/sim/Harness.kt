// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.sim

import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.Event
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.engine.Plant
import com.kostavukicevic.plants.engine.WaterBalance
import com.kostavukicevic.plants.engine.WateringModel
import com.kostavukicevic.plants.engine.WeatherDay
import com.kostavukicevic.plants.engine.WeatherQuality
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

fun loadWeather(site: String): List<ObsDay> {
    val text = checkNotNull(ObsDay::class.java.getResource("/weather/$site.csv")) { "no weather for $site" }.readText()
    return text.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
        val c = line.split(',')
        ObsDay(LocalDate.parse(c[0]), c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toDouble(), c[5].toDouble(), c[6].toDouble())
    }.toList()
}

/** What the model is told about a day. Without the provider's ET0 the engine falls back to Hargreaves. */
fun ObsDay.toWeatherDay(withEt0: Boolean) = WeatherDay(
    date = date, tMinC = tMinC, tMaxC = tMaxC, precipitationMm = precipitationMm,
    meanWindMs = windMs, meanRelativeHumidityPct = rhPct,
    et0Mm = if (withEt0) et0Mm else null, quality = WeatherQuality.FORECAST,
)

/** Per-plant outcome over the whole run. */
data class PlantResult(
    val years: Double,
    /** Days the true soil was drier than the plant's stress threshold. */
    val stressDays: Int,
    /** Days the true soil stayed near saturation after a watering given while it was still wet. */
    val overwetDays: Int,
    /** Waterings done while the true soil was still wet (depletion under 0.3). */
    val wateringsWhileWet: Int,
    val prompts: Int,
    val promptsAnsweredStillWet: Int,
    /** Soil-moisture answers given: check-ins plus answers given when watering. */
    val answers: Int,
    /** Absolute error, days, in the predicted dry-out date, one per refill. */
    val dryOutErrorsDays: List<Double>,
    /** Same, relative to the true cycle length. */
    val dryOutErrorsRelative: List<Double>,
    /** Answers given before the dry-out prediction stayed within a day or 20 % for three refills in a row; null if never. */
    val answersUntilAccurate: Int?,
    /** Mean absolute relative error of predicted daily water use, on days the plant used at least 20 ml. */
    val useError: Double,
    val repotPrompts: Int,
)

class Harness(private val weather: List<ObsDay>, private val withEt0: Boolean, private val forecastDays: Int = 7) {

    companion object {
        /** Days after a refill within which both dry-out days must fall for the refill to get a prediction; the report quotes it. */
        const val HORIZON_DAYS = 90
    }

    /**
     * [plant] is what the model is told. It differs from the true plant [tp] only when a test gives the model another
     * care profile (the archetype test). Stress and the true dry-out day follow the true plant's profile; the
     * predicted dry-out day follows the profile the model was given, since that is when it prompts.
     */
    fun <S> run(model: WateringModel<S>, tp: TruthPlant, person: Person, seed: Int, plant: Plant = tp.plant): PlantResult {
        val rng = Random(seed)
        val trueDryFraction = WaterBalance.dryFraction(tp.plant.profile)
        val modelDryFraction = WaterBalance.dryFraction(plant.profile)
        val stressThreshold = min(0.98, trueDryFraction + 0.3)

        var state = model.initial(plant)
        var truthD = 0.0
        var stressDays = 0
        var overwetDays = 0
        var wateringsWhileWet = 0
        var lastWetWatering = -100
        var prompts = 0
        var stillWet = 0
        var answers = 0
        var repots = 0
        val errDays = ArrayList<Double>()
        val errRel = ArrayList<Double>()
        val accurateAfter = ArrayList<Pair<Int, Boolean>>()
        var useErrSum = 0.0
        var useErrN = 0

        fun perceive(): Moisture {
            answers++
            val p = truthD + person.bias + rng.gaussian() * person.noiseSd
            return when {
                p < 0.3 -> Moisture.TOO_WET
                p < 0.7 -> Moisture.OK
                p < 0.95 -> Moisture.DRY
                else -> Moisture.BONE_DRY
            }
        }

        fun water(i: Int, doseMl: Double): Boolean {
            if (truthD < 0.3) {
                wateringsWhileWet++
                lastWetWatering = i
            }
            val deficit = truthD * tp.capacityMl
            val ranOut = plant.pot.drainageHoles && doseMl >= deficit
            truthD = max(0.0, truthD - doseMl / tp.capacityMl)
            return ranOut
        }

        fun recordDryOutPrediction(i: Int, stateAfter: S) {
            val predicted = predictCrossing(model, plant, stateAfter, i, modelDryFraction) ?: return
            val truth = truthCrossing(tp, truthD, i, trueDryFraction) ?: return
            val e = abs(predicted - truth).toDouble()
            val rel = e / max(1, truth)
            errDays += e
            errRel += rel
            accurateAfter += answers to (e <= 1.0 || rel <= 0.2)
        }

        for (i in weather.indices) {
            val obs = weather[i]
            val upcoming = (i until min(weather.size, i + forecastDays)).map { weather[it].toWeatherDay(withEt0) }
            val action = model.recommend(plant, state, upcoming).action
            val today = obs.date
            when (action) {
                is Action.WaterNow -> {
                    prompts++
                    if (rng.nextDouble() < person.compliance) {
                        val answer = if (rng.nextDouble() < person.answersWhenWatering) perceive() else null
                        if (answer == Moisture.TOO_WET || answer == Moisture.OK) stillWet++
                        val ranOut = water(i, action.doseMl.toDouble())
                        state = model.apply(plant, state, Event.Watered(today, ranOut, answer))
                        recordDryOutPrediction(i, state)
                    }
                }
                is Action.CheckSoil -> {
                    prompts++
                    if (rng.nextDouble() < person.compliance) {
                        val answer = perceive()
                        state = model.apply(plant, state, Event.CheckIn(today, answer))
                        if (answer == Moisture.TOO_WET || answer == Moisture.OK) {
                            stillWet++
                        } else {
                            // The person waters until it runs out of the bottom.
                            val ranOut = water(i, truthD * tp.capacityMl * 1.15)
                            state = model.apply(plant, state, Event.Watered(today, ranOut))
                            recordDryOutPrediction(i, state)
                        }
                    }
                }
                Action.RepotOrWaterDaily -> {
                    prompts++
                    repots++
                    val ranOut = water(i, truthD * tp.capacityMl * 1.15)
                    state = model.apply(plant, state, Event.Watered(today, ranOut))
                }
                is Action.Hold, Action.None -> Unit
            }

            val day = Truth.day(tp, obs, truthD)
            val predictedUse = model.predictedUseMl(plant, state, obs.toWeatherDay(withEt0))
            if (day.useMl >= 20) {
                useErrSum += abs(predictedUse - day.useMl) / day.useMl
                useErrN++
            }
            truthD = day.endDepletion
            if (day.maxDepletion >= stressThreshold) stressDays++
            if (truthD < 0.05 && i - lastWetWatering <= 3) overwetDays++
            state = model.advance(plant, state, obs.toWeatherDay(withEt0))
        }

        val untilAccurate = accurateAfter.indices.firstOrNull { k ->
            k + 2 < accurateAfter.size && (k..k + 2).all { accurateAfter[it].second }
        }?.let { accurateAfter[it].first }

        return PlantResult(
            years = weather.size / 365.25,
            stressDays = stressDays,
            overwetDays = overwetDays,
            wateringsWhileWet = wateringsWhileWet,
            prompts = prompts,
            promptsAnsweredStillWet = stillWet,
            answers = answers,
            dryOutErrorsDays = errDays,
            dryOutErrorsRelative = errRel,
            answersUntilAccurate = untilAccurate,
            useError = if (useErrN == 0) 0.0 else useErrSum / useErrN,
            repotPrompts = repots,
        )
    }

    /** Days from [start] until the model, left alone with the real weather, believes the plant reaches its dry fraction. */
    private fun <S> predictCrossing(model: WateringModel<S>, plant: Plant, from: S, start: Int, dryFraction: Double): Int? {
        var s = from
        for (j in start until min(weather.size, start + HORIZON_DAYS)) {
            if (model.predictedDepletion(plant, s, weather[j].date) >= dryFraction) return j - start
            s = model.advance(plant, s, weather[j].toWeatherDay(withEt0))
        }
        return null
    }

    /** Days from [start] until the true plant, left alone, reaches the dry fraction. */
    private fun truthCrossing(tp: TruthPlant, from: Double, start: Int, dryFraction: Double): Int? {
        var d = from
        for (j in start until min(weather.size, start + HORIZON_DAYS)) {
            if (d >= dryFraction) return j - start
            d = Truth.day(tp, weather[j], d).endDepletion
        }
        return null
    }
}
