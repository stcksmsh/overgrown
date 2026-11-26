// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.engine

import com.kostavukicevic.plants.engine.Coefficients as C
import kotlinx.datetime.LocalDate

/** Weather alerts for outdoor plants. Indoor plants get none. */
object Alerts {

    fun forecastAlerts(plant: Plant, upcoming: List<WeatherDay>): List<Alert> {
        val place = plant.place as? Place.Outdoor ?: return emptyList()
        val stricter = plant.pot.substrateVolumeL < C.SMALL_POT_LITRES || place.windExposure == WindExposure.EXPOSED
        val shift = if (stricter) C.ROOT_FREEZE_STRICTER_BY_C else 0.0
        return upcoming.flatMap { day ->
            buildList {
                coldAlert(plant.profile, day.tMinC, shift)?.let { add(Alert(it, day.date)) }
                if (day.tMaxC >= C.HEAT_ALERT_MAX_C) add(Alert(AlertType.HEAT, day.date))
                if (day.precipitationMm >= C.HEAVY_RAIN_ALERT_MM) add(Alert(AlertType.HEAVY_RAIN, day.date))
                val wind = day.meanWindMs
                val windLimit = if (plant.profile.windSensitive) C.WIND_ALERT_SENSITIVE_MEAN_MS else C.WIND_ALERT_MEAN_MS
                if (wind != null && wind >= windLimit) add(Alert(AlertType.WIND, day.date))
            }
        }
    }

    /**
     * At most one cold alert a day, the strongest: frost, then root freeze strong, then cold, then root freeze
     * caution. At the same level the plant's own limit wins over the pot's, because bringing a plant in or covering
     * it also protects its roots, while root-freeze advice protects only the pot.
     */
    private fun coldAlert(profile: CareProfile, tMinC: Double, shift: Double): AlertType? {
        val tender = profile.frostClass == FrostClass.TENDER || profile.frostClass == FrostClass.FROST_FREE
        val rootFreeze = profile.rootFreezeAlerts != false
        val minTemp = profile.minTempC
        return when {
            tender && tMinC <= C.FROST_ALERT_C -> AlertType.FROST
            rootFreeze && tMinC <= C.ROOT_FREEZE_STRONG_C + shift -> AlertType.ROOT_FREEZE_STRONG
            minTemp != null && tMinC < minTemp -> AlertType.COLD
            rootFreeze && tMinC <= C.ROOT_FREEZE_CAUTION_C + shift -> AlertType.ROOT_FREEZE_CAUTION
            else -> null
        }
    }

    /** Prolonged rain on an open, undrained pot of a species that hates wet feet. */
    fun waterlogging(plant: Plant, recent: List<WeatherDay>, today: LocalDate): Alert? {
        val place = plant.place as? Place.Outdoor ?: return null
        if (!plant.profile.waterloggingSensitive || plant.pot.drainageHoles || place.rainExposure != RainExposure.OPEN) return null
        val wetStreak = recent.asReversed().takeWhile { it.precipitationMm >= C.RAIN_MIN_MM }.size
        return if (wetStreak >= C.WATERLOGGING_WET_DAYS) Alert(AlertType.WATERLOGGING, today) else null
    }
}
