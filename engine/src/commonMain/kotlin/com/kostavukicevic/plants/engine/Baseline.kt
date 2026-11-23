// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.engine

import com.kostavukicevic.plants.engine.Coefficients as C
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlin.math.max
import kotlin.math.roundToInt

data class BaselineState(
    /** Last watering, or last rain heavy enough to count as one. */
    val anchor: LocalDate? = null,
    /** Last "still damp" answer. */
    val skippedOn: LocalDate? = null,
    val belief: KBelief = KBelief(),
    val lastTMaxC: Double? = null,
)

/**
 * The naive comparison model: a reminder interval that slides between a summer and a winter
 * value by month, nudged by pot size and light, with rain and frost holds. It uses the same
 * inputs and the same learning rule as [Engine], so a comparison measures the model, not the
 * learning. K divides the interval.
 *
 * Rules and constants from ahaverty/plants (MIT licence), https://github.com/ahaverty/plants.
 *
 * @param summerIntervalDays the plant's summer interval, the value a person would enter.
 */
class Baseline(private val summerIntervalDays: (Plant) -> Double) : WateringModel<BaselineState> {

    override val name = "baseline"

    override fun initial(plant: Plant) = BaselineState()

    override fun k(state: BaselineState) = state.belief.k

    fun intervalDays(plant: Plant, state: BaselineState, date: LocalDate): Double {
        val summer = summerIntervalDays(plant)
        val winter = summer * C.BASELINE_WINTER_MULTIPLE
        val w = C.BASELINE_SEASON_WEIGHT[date.month.ordinal]
        val potMultiplier = when {
            plant.pot.substrateVolumeL <= C.BASELINE_SMALL_POT_MAX_L -> C.BASELINE_POT_SMALL
            plant.pot.substrateVolumeL >= C.BASELINE_LARGE_POT_MIN_L -> C.BASELINE_POT_LARGE
            else -> C.BASELINE_POT_MEDIUM
        }
        val lightMultiplier = when (val place = plant.place) {
            is Place.Outdoor -> 1.0
            is Place.Indoor -> when (place.lightClass) {
                LightClass.LOW -> C.BASELINE_LIGHT_LOW
                LightClass.MEDIUM -> C.BASELINE_LIGHT_MEDIUM
                LightClass.HIGH, LightClass.DIRECT -> C.BASELINE_LIGHT_BRIGHT
            }
        }
        return max(1.0, (winter + (summer - winter) * w) * potMultiplier * lightMultiplier / state.belief.k)
    }

    private fun nextDue(plant: Plant, state: BaselineState, today: LocalDate): LocalDate {
        val anchor = state.anchor ?: return today
        val interval = intervalDays(plant, state, today)
        val skipped = state.skippedOn
        return if (skipped != null && skipped > anchor) {
            skipped.plus(DatePeriod(days = max(C.BASELINE_SKIP_MIN_DAYS, (interval * C.BASELINE_SKIP_FRACTION).roundToInt())))
        } else {
            anchor.plus(DatePeriod(days = max(1, interval.roundToInt())))
        }
    }

    override fun predictedDepletion(plant: Plant, state: BaselineState, today: LocalDate): Double {
        val dryFraction = WaterBalance.dryFraction(plant.profile)
        val anchor = state.anchor ?: return dryFraction
        return (dryFraction * anchor.daysUntil(today) / intervalDays(plant, state, today)).coerceIn(0.0, 1.0)
    }

    /** The interval implies an even draw-down: the dry fraction of the bucket over the interval. */
    override fun predictedUseMl(plant: Plant, state: BaselineState, day: WeatherDay) =
        WaterBalance.dryFraction(plant.profile) * WaterBalance.capacityMl(plant.pot) / intervalDays(plant, state, day.date)

    private fun opensToSky(plant: Plant) =
        (plant.place as? Place.Outdoor)?.rainExposure == RainExposure.OPEN

    override fun advance(plant: Plant, state: BaselineState, day: WeatherDay): BaselineState {
        val rained = opensToSky(plant) && day.precipitationMm >= C.BASELINE_RAIN_MM
        val belief = Learning.addVariance(state.belief, C.PROCESS_VARIANCE_PER_DAY)
        return state.copy(anchor = if (rained) day.date else state.anchor, belief = belief, lastTMaxC = day.tMaxC)
    }

    override fun apply(plant: Plant, state: BaselineState, event: Event): BaselineState = when (event) {
        is Event.CheckIn -> {
            val learned = learn(plant, state, event.moisture, event.date)
            val damp = event.moisture == Moisture.TOO_WET || event.moisture == Moisture.OK
            if (damp) learned.copy(skippedOn = event.date) else learned
        }
        is Event.Watered -> {
            val learned = event.moisture?.let { learn(plant, state, it, event.date) } ?: state
            learned.copy(anchor = event.date, skippedOn = null)
        }
        is Event.Moved, is Event.Repotted -> state.copy(belief = Learning.addVariance(state.belief, C.VARIANCE_BUMP_CHANGE))
    }

    private fun learn(plant: Plant, state: BaselineState, moisture: Moisture, date: LocalDate): BaselineState {
        val drySideFast = when (plant.profile.droughtAsymmetry) {
            DroughtAsymmetry.NONE -> false
            DroughtAsymmetry.ALWAYS -> true
            DroughtAsymmetry.IN_HEAT -> (state.lastTMaxC ?: Double.NEGATIVE_INFINITY) >= C.HEAT_ALERT_MAX_C
        }
        val fast = moisture == Moisture.TOO_WET && !plant.pot.drainageHoles
        val predicted = predictedDepletion(plant, state, date)
        return state.copy(belief = Learning.update(state.belief, predicted, moisture, drySideFast, fast))
    }

    override fun recommend(plant: Plant, state: BaselineState, upcoming: List<WeatherDay>): Recommendation {
        require(upcoming.isNotEmpty()) { "upcoming must start with today" }
        val today = upcoming.first()
        if (today.date < nextDue(plant, state, today.date)) return Recommendation(Action.None)
        if (plant.place is Place.Outdoor) {
            val tomorrow = upcoming.getOrNull(1)
            if ((tomorrow ?: today).tMinC <= C.BASELINE_FROST_C) return Recommendation(Action.Hold(HoldReason.FROST))
            if (opensToSky(plant) && tomorrow != null && tomorrow.precipitationMm >= C.BASELINE_RAIN_MM) {
                return Recommendation(Action.Hold(HoldReason.RAIN_EXPECTED))
            }
        }
        val dose = WaterBalance.doseMl(WaterBalance.dryFraction(plant.profile), plant.pot)
        return Recommendation(Action.WaterNow(dose.roundToInt(), PromptReason.DRY_BY_MODEL))
    }

    companion object {
        /**
         * A fair summer interval for the baseline: the days the engine's bucket takes, with K = 1,
         * to reach the plant's dry fraction on a typical midsummer day. The baseline thus starts
         * from the same knowledge as the engine and differs only in how it tracks weather.
         */
        fun summerIntervalFromEngine(plant: Plant, midsummerDay: WeatherDay): Double {
            val use = WaterBalance.dailyUseMl(plant, midsummerDay, 1.0)
            return max(1.0, WaterBalance.dryFraction(plant.profile) * WaterBalance.capacityMl(plant.pot) / use)
        }
    }
}
