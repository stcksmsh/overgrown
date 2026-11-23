// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.sim

import com.kostavukicevic.plants.engine.Place
import com.kostavukicevic.plants.engine.Plant
import com.kostavukicevic.plants.engine.PotMaterial
import com.kostavukicevic.plants.engine.RainExposure
import com.kostavukicevic.plants.engine.Solar
import com.kostavukicevic.plants.engine.WaterBalance
import kotlinx.datetime.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** One day of observed weather from the archive. */
data class ObsDay(
    val date: LocalDate,
    val tMaxC: Double,
    val tMinC: Double,
    val precipitationMm: Double,
    val windMs: Double,
    val rhPct: Double,
    /** FAO Penman-Monteith ET0 from real radiation, wind and humidity. */
    val et0Mm: Double,
)

/**
 * The simulated plant's real behaviour. Deliberately richer than the engine, so that a good
 * result is not the engine recognising its own equations:
 * - hourly demand shaped by sun angle and vapour-pressure deficit, scaled by Penman-Monteith ET0
 *   (wind, humidity, radiation) instead of the engine's Hargreaves or provider value alone;
 * - a plant coefficient, bucket size, pot-wall loss, rain capture and wind sensitivity drawn
 *   at random from wide ranges;
 * - uptake that falls as the soil dries (stomata close), which the engine does not model;
 * - rain at a random hour, with the first millimetre intercepted by the canopy.
 */
data class TruthPlant(
    val plant: Plant,
    val availableWaterFraction: Double,
    val coefficient: Double,
    val materialFactor: Double,
    val rainCapture: Double,
    val windSensitivity: Double,
    /** Indoor only. */
    val trueLux: Double,
    val lightExponent: Double,
    val indoorUseMultiplier: Double,
) {
    val capacityMl: Double get() = plant.pot.substrateVolumeL * 1000 * availableWaterFraction

    companion object {
        fun draw(plant: Plant, rng: Random): TruthPlant {
            val terracotta = plant.pot.material == PotMaterial.UNGLAZED_TERRACOTTA
            val place = plant.place
            val capture = when ((place as? Place.Outdoor)?.rainExposure) {
                RainExposure.OPEN -> rng.uniform(0.6, 1.0)
                RainExposure.PARTIAL -> rng.uniform(0.15, 0.8)
                RainExposure.COVERED -> rng.uniform(0.0, 0.1)
                null -> 0.0
            }
            val lux = if (place is Place.Indoor) (place.lux ?: WaterBalance.typicalLux(place.lightClass)) * rng.uniform(0.5, 2.0) else 0.0
            return TruthPlant(
                plant = plant,
                availableWaterFraction = rng.uniform(0.25, 0.35),
                coefficient = rng.uniform(0.35, 1.2),
                materialFactor = if (terracotta) rng.uniform(1.05, 1.45) else rng.uniform(0.95, 1.05),
                rainCapture = capture,
                windSensitivity = rng.uniform(0.0, 0.3),
                trueLux = lux,
                lightExponent = rng.uniform(0.75, 0.95),
                indoorUseMultiplier = exp(rng.gaussian() * 0.4),
            )
        }
    }
}

fun Random.uniform(lo: Double, hi: Double) = lo + nextDouble() * (hi - lo)

/** Standard normal, Box-Muller. */
fun Random.gaussian(): Double {
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
}

object Truth {

    /** Uptake falls linearly from full at 0.6 depletion to 15 % at 1.0. */
    private fun stress(d: Double) = if (d < 0.6) 1.0 else max(0.15, 1 - (d - 0.6) / 0.4 * 0.85)

    private fun saturationVapourPressure(t: Double) = 0.6108 * exp(17.27 * t / (t + 237.3))

    /** Deterministic per date, so the truth can be run forward to find a future dry-out day. */
    private fun rainHour(date: LocalDate) = ((date.toEpochDays().toLong() * 2654435761L) ushr 7).mod(24L).toInt()

    data class DayResult(val endDepletion: Double, val maxDepletion: Double, val useMl: Double)

    /** One day, hour by hour, from [startDepletion]. */
    fun day(tp: TruthPlant, obs: ObsDay, startDepletion: Double): DayResult {
        val plant = tp.plant
        val lat = plant.place.latitudeDeg
        val doy = obs.date.dayOfYear
        val dayLength = Solar.dayLengthHours(lat, doy)
        val sunrise = 12 - dayLength / 2

        // Hourly weights: sun angle and vapour-pressure deficit.
        val ea = obs.rhPct / 100 * saturationVapourPressure((obs.tMaxC + obs.tMinC) / 2)
        val sun = DoubleArray(24) { h ->
            val x = (h + 0.5 - sunrise) / dayLength
            if (x in 0.0..1.0) sin(PI * x) else 0.0
        }
        val vpd = DoubleArray(24) { h ->
            val t = obs.tMinC + (obs.tMaxC - obs.tMinC) * (1 + cos(PI * ((h + 0.5) - 15) / 12)) / 2
            max(0.0, saturationVapourPressure(t) - ea)
        }
        val sunSum = sun.sum().takeIf { it > 0 } ?: 1.0
        val vpdSum = vpd.sum().takeIf { it > 0 } ?: 1.0
        val weights = DoubleArray(24) { 0.7 * sun[it] / sunSum + 0.3 * vpd[it] / vpdSum }

        val canopy = WaterBalance.canopyAreaM2(plant.canopyWidthCm)
        val dailyDemand = when (val place = plant.place) {
            is Place.Outdoor -> obs.et0Mm * tp.coefficient * canopy * 1000 * tp.materialFactor *
                (1 + tp.windSensitivity * (obs.windMs - 2.0) / 2.0).coerceIn(0.7, 1.5)
            is Place.Indoor -> {
                val dli = WaterBalance.dailyLightIntegral(tp.trueLux, dayLength)
                val ref = WaterBalance.dailyLightIntegral(2150.0, 12.0)
                73.0 * (canopy / 0.07) * max(0.1, (dli / ref).pow(tp.lightExponent)) *
                    tp.materialFactor * tp.indoorUseMultiplier * (if (place.dryAir) 1.15 else 1.0)
            }
        }

        val rainMl = if (plant.place is Place.Outdoor) {
            max(0.0, obs.precipitationMm - 1.0) * WaterBalance.potTopAreaM2(plant.pot) * 1000 * tp.rainCapture
        } else 0.0
        val rainAt = rainHour(obs.date)

        var d = startDepletion
        var maxD = d
        var used = 0.0
        for (h in 0 until 24) {
            val uptake = dailyDemand * weights[h] * stress(d) * tp.plant.profile.waterUseFactor
            used += uptake
            d = (d + uptake / tp.capacityMl).coerceIn(0.0, 1.0)
            maxD = max(maxD, d)
            if (h == rainAt && rainMl > 0) d = max(0.0, d - rainMl / tp.capacityMl)
        }
        return DayResult(d, maxD, used)
    }
}

/** How the simulated person answers and acts. Answers are noisy and biased, as people are. */
data class Person(val bias: Double, val noiseSd: Double, val compliance: Double, val answersWhenWatering: Double) {
    companion object {
        fun draw(rng: Random) = Person(
            bias = rng.gaussian() * 0.08,
            noiseSd = 0.12,
            compliance = 0.9,
            answersWhenWatering = 0.25,
        )
    }
}
