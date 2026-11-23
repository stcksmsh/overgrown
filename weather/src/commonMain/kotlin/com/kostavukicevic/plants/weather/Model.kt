// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.weather

import com.kostavukicevic.plants.engine.WeatherDay
import com.kostavukicevic.plants.engine.WeatherQuality
import kotlinx.datetime.LocalDate
import kotlin.math.round
import kotlin.time.Instant

/** The one network call this module needs. The app implements it; tests fake it. */
fun interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** Header names are lower case. */
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String)

/**
 * A location as it may leave the device: rounded to 2 decimals (about 1 km). MET Norway allows at
 * most 4; the brief uses 2 for every provider.
 */
class Coordinates private constructor(val latitude: Double, val longitude: Double) {
    override fun equals(other: Any?) = other is Coordinates && other.latitude == latitude && other.longitude == longitude
    override fun hashCode() = 31 * latitude.hashCode() + longitude.hashCode()
    override fun toString() = "${fmt(latitude)},${fmt(longitude)}"

    companion object {
        fun of(latitude: Double, longitude: Double): Coordinates {
            require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "coordinates out of range" }
            return Coordinates(round(latitude * 100) / 100, round(longitude * 100) / 100)
        }

        /** Two decimals, no exponent, no trailing noise: what goes into a URL. */
        fun fmt(x: Double): String {
            val cents = round(x * 100).toLong()
            val sign = if (cents < 0) "-" else ""
            val a = kotlin.math.abs(cents)
            return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
        }
    }
}

enum class ProviderId { OPEN_METEO, MET_NORWAY }

/** One day as a provider reports it, before it becomes an engine [WeatherDay]. */
data class DailyWeather(
    val date: LocalDate,
    val tMinC: Double,
    val tMaxC: Double,
    val precipitationMm: Double,
    val meanWindMs: Double?,
    val meanRelativeHumidityPct: Double?,
    val et0Mm: Double?,
) {
    fun toWeatherDay(quality: WeatherQuality) =
        WeatherDay(date, tMinC, tMaxC, precipitationMm, meanWindMs, meanRelativeHumidityPct, et0Mm, quality)
}

sealed interface FetchResult {
    data class Fresh(val provider: ProviderId, val days: List<DailyWeather>, val fetchedAt: Instant) : FetchResult

    /** Nothing new: served from cache without a request, or the server answered 304. */
    data class Cached(val provider: ProviderId, val days: List<DailyWeather>, val fetchedAt: Instant) : FetchResult

    data class RateLimited(val provider: ProviderId, val retryAfter: Instant) : FetchResult
    data class Failed(val provider: ProviderId, val reason: String) : FetchResult

    /** The provider may not be asked now (MET Norway outside the foreground). No request was made. */
    data class NotAllowed(val provider: ProviderId, val reason: String) : FetchResult
}

/** What a provider remembers between requests for one location. Persisted by the app. */
data class CachedResponse(
    val body: String,
    val fetchedAt: Instant,
    val expires: Instant? = null,
    val lastModified: String? = null,
    /** No request before this time (back-off after 429 or errors). */
    val notBefore: Instant? = null,
    val consecutiveFailures: Int = 0,
)

interface ResponseStore {
    suspend fun load(key: String): CachedResponse?
    suspend fun save(key: String, value: CachedResponse)
}

class InMemoryResponseStore : ResponseStore {
    private val map = HashMap<String, CachedResponse>()
    override suspend fun load(key: String) = map[key]
    override suspend fun save(key: String, value: CachedResponse) { map[key] = value }
}
