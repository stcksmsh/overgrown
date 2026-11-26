// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.engine

import com.kostavukicevic.plants.engine.Coefficients as C
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.math.exp
import kotlin.math.roundToInt

data class EngineState(
    /** 0 = full, 1 = empty. */
    val depletion: Double = 0.0,
    val belief: KBelief = KBelief(),
    /** The last [Coefficients.WINTER_MODE_WINDOW_DAYS] days of weather, oldest first. */
    val recent: List<WeatherDay> = emptyList(),
    /** Days since rain last refilled the pot. */
    val daysSinceRain: Int = 0,
    /** Consecutive days filled from climatology. */
    val gapStreak: Int = 0,
    val lastSoilCheck: LocalDate? = null,
    /** The pot "Repot or water daily" was already given for; it is not repeated until the pot changes. */
    val repotAdvisedFor: Pot? = null,
    /** K when the day began, before its events: what the day's first recommendation used. Null before the first [Engine.advance]. */
    val kAtDayStart: Double? = null,
)

/**
 * The water-balance engine: a bucket of plant-available water per pot, drawn down by predicted
 * use scaled by the learned K, refilled by rain and watering, with winter and alert rules on top.
 * Deterministic: the same inputs always give the same outputs.
 */
object Engine : WateringModel<EngineState> {

    override val name = "engine"

    override fun initial(plant: Plant) = EngineState()

    override fun k(state: EngineState) = state.belief.k

    override fun predictedDepletion(plant: Plant, state: EngineState, today: LocalDate) = state.depletion

    override fun predictedUseMl(plant: Plant, state: EngineState, day: WeatherDay) =
        WaterBalance.dailyUseMl(plant, day, state.belief.k)

    override fun advance(plant: Plant, state: EngineState, day: WeatherDay): EngineState {
        val use = WaterBalance.dailyUseMl(plant, day, state.belief.k)
        val refill = WaterBalance.rainRefillMl(plant, day)
        val capacity = WaterBalance.capacityMl(plant.pot)
        val depletion = WaterBalance.stepDepletion(state.depletion, use, refill, capacity)

        var belief = Learning.addVariance(state.belief, C.PROCESS_VARIANCE_PER_DAY)
        val gap = if (day.quality == WeatherQuality.CLIMATOLOGY_FILL) state.gapStreak + 1 else 0
        if (gap == C.WEATHER_GAP_DAYS) belief = Learning.addVariance(belief, C.VARIANCE_BUMP_WEATHER_GAP)

        return state.copy(
            depletion = depletion,
            belief = belief,
            recent = (state.recent + day).takeLast(C.WINTER_MODE_WINDOW_DAYS),
            daysSinceRain = if (refill > 0) 0 else state.daysSinceRain + 1,
            gapStreak = gap,
            // Judged with the K the day started from, so the advice counts as given only if the
            // day's first recommendation gave it; a check-in that raised K today cannot mark it
            // given before it was shown.
            repotAdvisedFor = if (WaterBalance.dailyUseMl(plant, day, state.kAtDayStart ?: state.belief.k) > capacity) {
                plant.pot
            } else {
                state.repotAdvisedFor
            },
            kAtDayStart = belief.k,
        )
    }

    override fun apply(plant: Plant, state: EngineState, event: Event): EngineState = when (event) {
        is Event.CheckIn -> checkIn(plant, state, event.moisture, event.date)
        is Event.Watered -> {
            val checked = event.moisture?.let { checkIn(plant, state, it, event.date) } ?: state
            checked.copy(depletion = if (event.ranOutOfBottom) 0.0 else C.PARTIAL_WATERING_DEPLETION)
        }
        // A move changes the demand, not the pot, so it does not repeat the repot advice.
        is Event.Moved -> state.copy(belief = Learning.addVariance(state.belief, C.VARIANCE_BUMP_CHANGE))
        is Event.Repotted -> state.copy(belief = Learning.addVariance(state.belief, C.VARIANCE_BUMP_CHANGE), repotAdvisedFor = null)
    }

    private fun inHeat(state: EngineState) = state.recent.lastOrNull()?.let { it.tMaxC >= C.HEAT_ALERT_MAX_C } ?: false

    private fun checkIn(plant: Plant, state: EngineState, moisture: Moisture, date: LocalDate): EngineState {
        val drySideFast = when (plant.profile.droughtAsymmetry) {
            DroughtAsymmetry.NONE -> false
            DroughtAsymmetry.ALWAYS -> true
            DroughtAsymmetry.IN_HEAT -> inHeat(state)
        }
        val fast = moisture == Moisture.TOO_WET && !plant.pot.drainageHoles
        val update = Learning.update(state.belief, state.depletion, moisture, drySideFast, fast)
        return state.copy(belief = update.belief, depletion = update.depletion, lastSoilCheck = date)
    }

    private fun inWinterMode(state: EngineState): Boolean =
        state.recent.isNotEmpty() && state.recent.sumOf { it.tMeanC } / state.recent.size < C.WINTER_MODE_MEAN_C

    override fun recommend(plant: Plant, state: EngineState, upcoming: List<WeatherDay>): Recommendation {
        require(upcoming.isNotEmpty()) { "upcoming must start with today" }
        val today = upcoming.first()
        val alerts = Alerts.forecastAlerts(plant, upcoming) +
            listOfNotNull(Alerts.waterlogging(plant, state.recent, today.date))
        return Recommendation(action(plant, state, upcoming), alerts)
    }

    private fun action(plant: Plant, state: EngineState, upcoming: List<WeatherDay>): Action {
        val today = upcoming.first()
        val capacity = WaterBalance.capacityMl(plant.pot)
        if (state.repotAdvisedFor != plant.pot && WaterBalance.dailyUseMl(plant, today, state.belief.k) > capacity) {
            return Action.RepotOrWaterDaily
        }

        val outdoor = plant.place is Place.Outdoor
        val dryFraction = WaterBalance.dryFraction(plant.profile)
        val d = state.depletion
        val sd = state.belief.sd
        val confident = sd < C.CONFIDENT_SD
        val due = d >= dryFraction
        // When unsure, check the soil once the plant could be dry if it uses water faster than believed.
        val possiblyDue = d * exp(sd) >= dryFraction
        val winter = outdoor && plant.profile.winterRules && inWinterMode(state)

        val candidate: Action = when {
            winter && possiblyDue -> Action.CheckSoil(PromptReason.DRY_BY_MODEL)
            winter && state.daysSinceRain >= C.WINTER_FLOOR_DAYS &&
                (state.lastSoilCheck?.daysUntil(today.date) ?: Int.MAX_VALUE) >= C.WINTER_FLOOR_DAYS ->
                Action.CheckSoil(PromptReason.WINTER_FLOOR)
            winter -> Action.None
            due && confident -> Action.WaterNow(WaterBalance.doseMl(d, plant.pot).roundToInt(), PromptReason.DRY_BY_MODEL)
            possiblyDue && !confident -> Action.CheckSoil(PromptReason.UNCERTAIN)
            else -> Action.None
        }
        if (candidate == Action.None || !outdoor) return candidate

        val tonightMinC = (upcoming.getOrNull(1) ?: today).tMinC
        val frost = tonightMinC <= C.FROST_HOLD_MIN_C
        return when {
            winter && frost -> Action.Hold(HoldReason.FROST)
            winter && today.tMaxC <= C.WINTER_WINDOW_MIN_AIR_C -> Action.Hold(HoldReason.TOO_COLD)
            candidate is Action.WaterNow && frost -> Action.Hold(HoldReason.FROST)
            candidate is Action.WaterNow && upcoming.size > 1 &&
                WaterBalance.rainRefillMl(plant, upcoming[1]) >= C.RAIN_HOLD_SHARE * d * capacity ->
                Action.Hold(HoldReason.RAIN_EXPECTED)
            else -> candidate
        }
    }
}
