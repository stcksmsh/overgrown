// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.engine

import kotlinx.datetime.LocalDate

enum class PromptReason {
    /** The bucket has reached the plant's dry fraction. */
    DRY_BY_MODEL,

    /** The engine is not yet sure of this plant and may be near the dry fraction. */
    UNCERTAIN,

    /** Winter dry spell: a periodic check regardless of the balance. */
    WINTER_FLOOR,
}

enum class HoldReason { FROST, TOO_COLD, RAIN_EXPECTED }

sealed interface Action {
    data object None : Action
    data class WaterNow(val doseMl: Int, val reason: PromptReason) : Action
    data class CheckSoil(val reason: PromptReason) : Action

    /** Watering is due, but not today. */
    data class Hold(val reason: HoldReason) : Action

    /** Daily use exceeds what the pot holds: no schedule can keep up. */
    data object RepotOrWaterDaily : Action
}

enum class AlertType { ROOT_FREEZE_CAUTION, ROOT_FREEZE_STRONG, HEAT, HEAVY_RAIN, WIND, WATERLOGGING }

data class Alert(val type: AlertType, val date: LocalDate)

data class Recommendation(val action: Action, val alerts: List<Alert> = emptyList())

/**
 * A model that turns a plant and its weather into prompts. Implemented by [Engine] and by
 * [Baseline], so the simulation harness can drive both the same way.
 *
 * Each simulated day: [recommend] with today's and the coming days' forecast, then [apply] the
 * person's events, then [advance] with the day's weather.
 */
interface WateringModel<S> {
    val name: String
    fun initial(plant: Plant): S
    fun advance(plant: Plant, state: S, day: WeatherDay): S
    fun apply(plant: Plant, state: S, event: Event): S

    /** @param upcoming today first, then the coming days. Must not be empty. */
    fun recommend(plant: Plant, state: S, upcoming: List<WeatherDay>): Recommendation

    /** The model's current belief of depletion, 0 (full) to 1 (empty), on [today]. */
    fun predictedDepletion(plant: Plant, state: S, today: LocalDate): Double

    /** The learned correction factor. */
    fun k(state: S): Double

    /** The model's belief of water used on [day], ml. */
    fun predictedUseMl(plant: Plant, state: S, day: WeatherDay): Double
}
