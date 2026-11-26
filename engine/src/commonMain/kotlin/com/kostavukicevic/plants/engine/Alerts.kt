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
                if (plant.profile.rootFreezeAlerts != false) {
                    when {
                        day.tMinC <= C.ROOT_FREEZE_STRONG_C + shift -> add(Alert(AlertType.ROOT_FREEZE_STRONG, day.date))
                        day.tMinC <= C.ROOT_FREEZE_CAUTION_C + shift -> add(Alert(AlertType.ROOT_FREEZE_CAUTION, day.date))
                    }
                }
                if (day.tMaxC >= C.HEAT_ALERT_MAX_C) add(Alert(AlertType.HEAT, day.date))
                if (day.precipitationMm >= C.HEAVY_RAIN_ALERT_MM) add(Alert(AlertType.HEAVY_RAIN, day.date))
                val wind = day.meanWindMs
                val windLimit = if (plant.profile.windSensitive) C.WIND_ALERT_SENSITIVE_MEAN_MS else C.WIND_ALERT_MEAN_MS
                if (wind != null && wind >= windLimit) add(Alert(AlertType.WIND, day.date))
            }
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
