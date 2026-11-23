// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.engine

import com.kostavukicevic.plants.engine.Coefficients as C
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow

/** The pot as a bucket of plant-available water, and the daily draw on it. */
object WaterBalance {

    fun potHeightCm(pot: Pot): Double = pot.heightCm ?: when (pot.shape) {
        PotShape.SHALLOW -> C.SHALLOW_POT_HEIGHT_CM
        PotShape.STANDARD -> C.STANDARD_POT_HEIGHT_CM
        PotShape.TALL -> C.TALL_POT_HEIGHT_CM
    }

    fun availableWaterFraction(pot: Pot): Double {
        val h = potHeightCm(pot).coerceIn(C.POT_HEIGHT_EFFECT_MIN_CM, C.POT_REFERENCE_HEIGHT_CM)
        return (C.AVAILABLE_WATER_FRACTION + C.AWF_PER_CM_BELOW_REFERENCE * (C.POT_REFERENCE_HEIGHT_CM - h))
            .coerceIn(C.AVAILABLE_WATER_FRACTION_MIN, C.AVAILABLE_WATER_FRACTION_MAX)
    }

    fun capacityMl(pot: Pot): Double = pot.substrateVolumeL * 1000 * availableWaterFraction(pot)

    /** Top area of the pot, treating it as a cylinder of its substrate volume and height. */
    fun potTopAreaM2(pot: Pot): Double = pot.substrateVolumeL * 1000 / potHeightCm(pot) / 10_000

    fun canopyAreaM2(canopyWidthCm: Double): Double {
        val r = canopyWidthCm / 100 / 2
        return PI * r * r
    }

    fun materialFactor(material: PotMaterial): Double = when (material) {
        PotMaterial.PLASTIC -> C.MATERIAL_FACTOR_PLASTIC
        PotMaterial.GLAZED_CERAMIC -> C.MATERIAL_FACTOR_GLAZED
        PotMaterial.UNGLAZED_TERRACOTTA -> C.MATERIAL_FACTOR_TERRACOTTA
    }

    fun dryFraction(profile: CareProfile): Double = profile.dryFraction ?: when (profile.waterStrategy) {
        WaterStrategy.DRY_OUT_FULLY -> C.DRY_FRACTION_DRY_OUT_FULLY
        WaterStrategy.DRY_TOP_PART -> C.DRY_FRACTION_DRY_TOP_PART
        WaterStrategy.EVENLY_MOIST -> C.DRY_FRACTION_EVENLY_MOIST
        WaterStrategy.WET_FEET -> C.DRY_FRACTION_WET_FEET
        WaterStrategy.EPIPHYTE_SOAK -> C.DRY_FRACTION_EPIPHYTE_SOAK
    }

    /** ET0 for the day: the provider's value if it gave one, otherwise Hargreaves-Samani. */
    fun et0Mm(day: WeatherDay, latitudeDeg: Double): Double =
        day.et0Mm ?: Solar.hargreavesEt0(day.tMinC, day.tMaxC, latitudeDeg, day.date.dayOfYear)

    /**
     * Outdoor use, ml/day: ET0 x coefficient x canopy area x 1000 x material x K.
     * Canopy area, not pot area: coefficients on pot-top area are several times larger.
     */
    fun outdoorUseMl(
        et0Mm: Double,
        canopyAreaM2: Double,
        material: PotMaterial,
        k: Double,
        coefficient: Double = C.OUTDOOR_CANOPY_COEFFICIENT,
    ): Double = et0Mm * coefficient * canopyAreaM2 * 1000 * materialFactor(material) * k

    fun typicalLux(lightClass: LightClass): Double = when (lightClass) {
        LightClass.LOW -> C.LIGHT_LOW_TYPICAL_LUX
        LightClass.MEDIUM -> C.LIGHT_MEDIUM_TYPICAL_LUX
        LightClass.HIGH -> C.LIGHT_HIGH_TYPICAL_LUX
        LightClass.DIRECT -> C.LIGHT_DIRECT_TYPICAL_LUX
    }

    fun lightClassOf(lux: Double): LightClass = when {
        lux >= C.LIGHT_DIRECT_MIN_LUX -> LightClass.DIRECT
        lux >= C.LIGHT_HIGH_MIN_LUX -> LightClass.HIGH
        lux >= C.LIGHT_MEDIUM_MIN_LUX -> LightClass.MEDIUM
        else -> LightClass.LOW
    }

    /** Daily light integral, mol m-2 day-1. */
    fun dailyLightIntegral(lux: Double, photoperiodHours: Double): Double =
        lux * C.LUX_TO_PPFD_SUNLIGHT * photoperiodHours * 3600 / 1_000_000

    private val referenceDli = dailyLightIntegral(C.INDOOR_REFERENCE_LUX, C.INDOOR_REFERENCE_PHOTOPERIOD_H)

    fun indoorLightTerm(lux: Double, photoperiodHours: Double): Double =
        max(C.INDOOR_LIGHT_TERM_FLOOR, (dailyLightIntegral(lux, photoperiodHours) / referenceDli).pow(C.INDOOR_LIGHT_EXPONENT))

    private fun bounded(x: Double) = x.coerceIn(1 - C.INDOOR_TERM_BOUND, 1 + C.INDOOR_TERM_BOUND)

    fun indoorTempTerm(roomTempC: Double?): Double =
        if (roomTempC == null) 1.0 else bounded(1 + C.INDOOR_TEMP_EFFECT_PER_C * (roomTempC - C.INDOOR_TEMP_REFERENCE_C))

    fun indoorHumidityTerm(dryAir: Boolean): Double = bounded(if (dryAir) C.INDOOR_DRY_AIR_FACTOR else 1.0)

    /**
     * Indoor use, ml/day: reference use x canopy ratio x light x temperature x humidity x material x K.
     * No season factor: day length and temperature already carry the season.
     */
    fun indoorUseMl(place: Place.Indoor, canopyAreaM2: Double, material: PotMaterial, k: Double, photoperiodHours: Double): Double {
        val lux = place.lux ?: typicalLux(place.lightClass)
        return C.INDOOR_REFERENCE_USE_ML * (canopyAreaM2 / C.INDOOR_REFERENCE_CANOPY_M2) *
            indoorLightTerm(lux, photoperiodHours) * indoorTempTerm(place.roomTempC) *
            indoorHumidityTerm(place.dryAir) * materialFactor(material) * k
    }

    /** Predicted use for one plant on one day, ml, with correction factor [k]. */
    fun dailyUseMl(plant: Plant, day: WeatherDay, k: Double): Double {
        val canopy = canopyAreaM2(plant.canopyWidthCm)
        val base = when (val place = plant.place) {
            is Place.Outdoor -> outdoorUseMl(et0Mm(day, place.latitudeDeg), canopy, plant.pot.material, k)
            is Place.Indoor -> indoorUseMl(
                place, canopy, plant.pot.material, k,
                Solar.dayLengthHours(place.latitudeDeg, day.date.dayOfYear),
            )
        }
        return base * plant.profile.waterUseFactor
    }

    fun rainExposureFactor(exposure: RainExposure): Double = when (exposure) {
        RainExposure.OPEN -> C.RAIN_EXPOSURE_OPEN
        RainExposure.PARTIAL -> C.RAIN_EXPOSURE_PARTIAL
        RainExposure.COVERED -> C.RAIN_EXPOSURE_COVERED
    }

    /** Rain caught by the pot, ml, before capping at the deficit. Zero indoors and on days under the minimum. */
    fun rainRefillMl(plant: Plant, day: WeatherDay): Double {
        val place = plant.place as? Place.Outdoor ?: return 0.0
        if (day.precipitationMm < C.RAIN_MIN_MM) return 0.0
        return day.precipitationMm * potTopAreaM2(plant.pot) * 1000 * rainExposureFactor(place.rainExposure)
    }

    /** One day of the bucket: draw down by use, then refill by rain up to the deficit. */
    fun stepDepletion(depletion: Double, useMl: Double, refillMl: Double, capacityMl: Double): Double {
        val afterUse = (depletion + useMl / capacityMl).coerceIn(0.0, 1.0)
        return (afterUse - refillMl / capacityMl).coerceIn(0.0, 1.0)
    }

    /** Dose to bring the pot back to full: more if it drains so some runs through, less if it has no holes. */
    fun doseMl(depletion: Double, pot: Pot): Double {
        val deficit = depletion * capacityMl(pot)
        return if (pot.drainageHoles) deficit * (1 + C.DOSE_DRAINED_EXTRA) else deficit * (1 - C.DOSE_UNDRAINED_REDUCTION)
    }
}
