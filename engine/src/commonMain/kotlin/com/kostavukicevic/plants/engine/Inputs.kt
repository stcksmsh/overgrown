// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.engine

import kotlinx.datetime.LocalDate

enum class WaterStrategy { DRY_OUT_FULLY, DRY_TOP_PART, EVENLY_MOIST, WET_FEET, EPIPHYTE_SOAK }

/** Which correction the learning loop applies at once; the other needs two consistent answers. */
enum class DroughtAsymmetry {
    /** Act on "too wet" at once; wait for two answers before giving more water. */
    NONE,

    /** Drought kills faster (ferns): act on "dry" at once; wait for two answers before giving less. */
    ALWAYS,

    /** Like [ALWAYS], but only during a heat alert (conifers in a heat wave). */
    IN_HEAT,
}

enum class LightClass { LOW, MEDIUM, HIGH, DIRECT }
enum class RainExposure { OPEN, PARTIAL, COVERED }
enum class WindExposure { SHELTERED, NORMAL, EXPOSED }
enum class PotMaterial { PLASTIC, GLAZED_CERAMIC, UNGLAZED_TERRACOTTA }
enum class PotShape { SHALLOW, STANDARD, TALL }

data class Pot(
    val substrateVolumeL: Double,
    /** Measured height; when null, [shape] gives a typical height. */
    val heightCm: Double? = null,
    val shape: PotShape = PotShape.STANDARD,
    val material: PotMaterial = PotMaterial.PLASTIC,
    val drainageHoles: Boolean = true,
    val inNurseryPot: Boolean = false,
    val rootBound: Boolean = false,
) {
    init {
        require(substrateVolumeL > 0) { "substrate volume must be positive" }
        require(heightCm == null || heightCm > 0) { "pot height must be positive" }
    }
}

sealed interface Place {
    /** Rounded to 2 decimals before it leaves the device. */
    val latitudeDeg: Double

    data class Outdoor(
        override val latitudeDeg: Double,
        val rainExposure: RainExposure,
        val windExposure: WindExposure = WindExposure.NORMAL,
    ) : Place

    data class Indoor(
        override val latitudeDeg: Double,
        val lightClass: LightClass,
        /** Optional raw reading from the light check. Used only in place of the class's typical value. */
        val lux: Double? = null,
        /** Heating season with dry air. */
        val dryAir: Boolean = false,
        /** Room temperature if known; otherwise the temperature term is neutral. */
        val roomTempC: Double? = null,
    ) : Place
}

/** The species or archetype's engine-relevant care data, already mapped from the care database. */
data class CareProfile(
    val waterStrategy: WaterStrategy,
    /** Overrides the strategy's dry fraction, e.g. for conifers in pots. */
    val dryFraction: Double? = null,
    val waterUseFactor: Double = 1.0,
    val droughtAsymmetry: DroughtAsymmetry = DroughtAsymmetry.NONE,
    val waterloggingSensitive: Boolean = false,
    /** Outdoor evergreen container: the winter rule set runs on top of the water balance. */
    val winterRules: Boolean = false,
    /**
     * Root-freeze alerts apply (potted woody plants left outside). Null means the care data does not
     * know; an outdoor pot then gets the alerts, since a spurious alert costs a tap and a missed one
     * can kill the plant.
     */
    val rootFreezeAlerts: Boolean? = false,
    /** Needs shelter from wind: wind alerts come earlier. */
    val windSensitive: Boolean = false,
)

data class Plant(
    val profile: CareProfile,
    val canopyWidthCm: Double,
    val pot: Pot,
    val place: Place,
) {
    init {
        require(canopyWidthCm > 0) { "canopy width must be positive" }
    }
}

enum class WeatherQuality { FORECAST, STORED_FORECAST, CLIMATOLOGY_FILL }

data class WeatherDay(
    val date: LocalDate,
    val tMinC: Double,
    val tMaxC: Double,
    val precipitationMm: Double,
    val meanWindMs: Double? = null,
    val meanRelativeHumidityPct: Double? = null,
    /** Reference evapotranspiration from the provider, when it supplies one. */
    val et0Mm: Double? = null,
    val quality: WeatherQuality = WeatherQuality.FORECAST,
) {
    val tMeanC: Double get() = (tMinC + tMaxC) / 2
}

/**
 * A soil-moisture answer. [DRY_OR_BONE_DRY] is the quick three-way question's "Dry" (Wet / OK /
 * Dry), which does not tell Dry from Bone dry (docs/DECISIONS.md D31.4).
 */
enum class Moisture { TOO_WET, OK, DRY, BONE_DRY, DRY_OR_BONE_DRY }

sealed interface Event {
    val date: LocalDate

    /** [moisture] is the soil before watering, if the person answered. Without it, K never changes. */
    data class Watered(override val date: LocalDate, val ranOutOfBottom: Boolean, val moisture: Moisture? = null) : Event
    data class CheckIn(override val date: LocalDate, val moisture: Moisture) : Event
    data class Moved(override val date: LocalDate) : Event
    data class Repotted(override val date: LocalDate) : Event
}
