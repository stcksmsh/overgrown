// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.weather

import com.kostavukicevic.plants.engine.WeatherDay
import com.kostavukicevic.plants.engine.WeatherQuality
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Picks the provider. Open-Meteo by default; MET Norway after sustained Open-Meteo failures or
 * rate limits, and then only in the foreground. In the background, a failing Open-Meteo means no
 * fetch at all: the app recomputes from what it has.
 */
class WeatherService(
    private val openMeteo: OpenMeteo,
    private val metNorway: MetNorway,
    private val state: FallbackState,
) {
    /** Open-Meteo failures in a row before falling back. PRIOR. */
    private val sustained = 3

    suspend fun fetch(at: Coordinates, foreground: Boolean): FetchResult {
        if (state.useFallback() && foreground) {
            // Probe Open-Meteo first; it is the default and the only one usable in the background.
            val probe = openMeteo.forecast(at)
            if (probe is FetchResult.Fresh || probe is FetchResult.Cached) {
                state.setFallback(false); state.setFailures(0)
                return probe
            }
            return metNorway.forecast(at, foreground = true)
        }
        val result = openMeteo.forecast(at)
        when (result) {
            is FetchResult.Fresh, is FetchResult.Cached -> { state.setFailures(0); state.setFallback(false) }
            is FetchResult.RateLimited, is FetchResult.Failed -> {
                val failures = state.failures() + 1
                state.setFailures(failures)
                if (failures >= sustained || result is FetchResult.RateLimited) state.setFallback(true)
                if (foreground) return metNorway.forecast(at, foreground = true).takeIf { it is FetchResult.Fresh || it is FetchResult.Cached } ?: result
            }
            is FetchResult.NotAllowed -> Unit
        }
        return result
    }
}

interface FallbackState {
    suspend fun useFallback(): Boolean
    suspend fun setFallback(on: Boolean)
    suspend fun failures(): Int
    suspend fun setFailures(n: Int)
}

class InMemoryFallbackState : FallbackState {
    private var fallback = false
    private var count = 0
    override suspend fun useFallback() = fallback
    override suspend fun setFallback(on: Boolean) { fallback = on }
    override suspend fun failures() = count
    override suspend fun setFailures(n: Int) { count = n }
}

/** When this install's single daily background refresh runs: a fixed minute of the day, unique to the install. */
object RefreshSchedule {
    /** From a random number drawn once at install and kept. */
    fun minuteOfDay(installSeed: Long): Int = (installSeed.mod(24L * 60)).toInt()

    fun nextRun(now: Instant, timeZone: TimeZone, minuteOfDay: Int): Instant {
        val today = now.toLocalDateTime(timeZone).date
        val at = LocalTime(minuteOfDay / 60, minuteOfDay % 60)
        val candidate = today.atTime(at).toInstant(timeZone)
        return if (candidate > now) candidate else today.plus(DatePeriod(days = 1)).atTime(at).toInstant(timeZone)
    }

    fun delayUntilNext(now: Instant, timeZone: TimeZone, minuteOfDay: Int): Duration = nextRun(now, timeZone, minuteOfDay) - now
}

/** Monthly means for one place, from the archive, used to fill days with no data. */
data class Climatology(val months: Map<Month, DailyWeather>) {
    fun dayFor(date: LocalDate): WeatherDay? =
        months[date.month]?.copy(date = date)?.toWeatherDay(WeatherQuality.CLIMATOLOGY_FILL)

    companion object {
        fun from(history: List<DailyWeather>): Climatology = Climatology(
            history.groupBy { it.date.month }.mapValues { (_, days) ->
                DailyWeather(
                    date = days.first().date,
                    tMinC = days.map { it.tMinC }.average(),
                    tMaxC = days.map { it.tMaxC }.average(),
                    precipitationMm = days.map { it.precipitationMm }.average(),
                    meanWindMs = days.mapNotNull { it.meanWindMs }.takeIf { it.isNotEmpty() }?.average(),
                    meanRelativeHumidityPct = days.mapNotNull { it.meanRelativeHumidityPct }.takeIf { it.isNotEmpty() }?.average(),
                    et0Mm = days.mapNotNull { it.et0Mm }.takeIf { it.isNotEmpty() }?.average(),
                )
            },
        )
    }
}

/**
 * Turns what is stored into the engine's daily series. Each stored day is that day's
 * pseudo-observation: a stored forecast for past days, a live forecast from today on. A missing
 * day is filled from climatology and flagged, so the engine widens its uncertainty.
 */
object Series {
    fun build(stored: Map<LocalDate, DailyWeather>, from: LocalDate, to: LocalDate, today: LocalDate, climatology: Climatology?): List<WeatherDay> {
        val out = ArrayList<WeatherDay>()
        var d = from
        while (d <= to) {
            val day = stored[d]?.toWeatherDay(if (d < today) WeatherQuality.STORED_FORECAST else WeatherQuality.FORECAST)
                ?: climatology?.dayFor(d)
            if (day != null) out += day
            d = d.plus(DatePeriod(days = 1))
        }
        return out
    }
}
