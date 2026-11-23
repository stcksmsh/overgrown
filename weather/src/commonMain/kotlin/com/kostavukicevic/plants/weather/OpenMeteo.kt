// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.weather

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Open-Meteo (https://open-meteo.com/en/terms): free for non-commercial apps without subscriptions or
 * advertising; data CC BY 4.0. No API key. Supplies daily FAO-56 ET0, which the engine prefers
 * to Hargreaves. Background use is one refresh a day per install, timed by [RefreshSchedule].
 */
class OpenMeteo(
    private val http: HttpTransport,
    private val store: ResponseStore,
    private val now: () -> Instant,
    /** Never ask again for the same place sooner than this, foreground or not. */
    private val minInterval: Duration = 1.hours,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun forecastUrl(at: Coordinates) =
        "https://api.open-meteo.com/v1/forecast?latitude=${Coordinates.fmt(at.latitude)}&longitude=${Coordinates.fmt(at.longitude)}" +
            "&daily=$DAILY&timezone=auto&wind_speed_unit=ms&past_days=7&forecast_days=10"

    fun archiveUrl(at: Coordinates, start: LocalDate, end: LocalDate) =
        "https://archive-api.open-meteo.com/v1/archive?latitude=${Coordinates.fmt(at.latitude)}&longitude=${Coordinates.fmt(at.longitude)}" +
            "&start_date=$start&end_date=$end&daily=$DAILY&timezone=auto&wind_speed_unit=ms"

    suspend fun forecast(at: Coordinates): FetchResult {
        val key = "open-meteo:forecast:$at"
        val cached = store.load(key)
        val t = now()
        cached?.notBefore?.let { if (t < it) return FetchResult.RateLimited(ProviderId.OPEN_METEO, it, local = true) }
        if (cached != null && cached.body.isNotEmpty() && t - cached.fetchedAt < minInterval) {
            return FetchResult.Cached(ProviderId.OPEN_METEO, parseDaily(cached.body), cached.fetchedAt)
        }
        val response = http.call(forecastUrl(at), emptyMap()).getOrElse {
            store.save(key, failed(cached, t))
            return FetchResult.Failed(ProviderId.OPEN_METEO, it.message ?: "network error")
        }
        return when (response.status) {
            200 -> {
                val days = try {
                    parseDaily(response.body)
                } catch (e: IllegalArgumentException) {
                    store.save(key, failed(cached, t))
                    return FetchResult.Failed(ProviderId.OPEN_METEO, "unreadable response: ${e.message}")
                }
                store.save(key, CachedResponse(body = response.body, fetchedAt = t))
                FetchResult.Fresh(ProviderId.OPEN_METEO, days, t)
            }
            429 -> {
                val until = t + retryAfter(response)
                store.save(key, failed(cached, t).copy(notBefore = until))
                FetchResult.RateLimited(ProviderId.OPEN_METEO, until)
            }
            else -> {
                store.save(key, failed(cached, t))
                FetchResult.Failed(ProviderId.OPEN_METEO, "HTTP ${response.status}")
            }
        }
    }

    /** Daily history for climatology. Called once per place, in the foreground. */
    suspend fun archive(at: Coordinates, start: LocalDate, end: LocalDate): Result<List<DailyWeather>> =
        http.call(archiveUrl(at, start, end), emptyMap()).mapCatching { response ->
            check(response.status == 200) { "HTTP ${response.status}" }
            parseDaily(response.body)
        }

    /** Place search for the city field. Foreground only, on the person's request. */
    suspend fun searchPlaces(name: String): Result<List<PlaceMatch>> {
        val q = name.trim().takeIf { it.length >= 2 } ?: return Result.success(emptyList())
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${urlEncode(q)}&count=8&language=en&format=json"
        return http.call(url, emptyMap()).mapCatching { response ->
            check(response.status == 200) { "HTTP ${response.status}" }
            parsePlaces(response.body)
        }
    }

    /** Keeps the last good body, counts the failure, and waits before asking again: 10 minutes, doubling, at most 6 hours. */
    private fun failed(cached: CachedResponse?, t: Instant): CachedResponse {
        val failures = (cached?.consecutiveFailures ?: 0) + 1
        val wait = (10.minutes * (1 shl (failures - 1).coerceIn(0, 6))).coerceAtMost(6.hours)
        return (cached ?: CachedResponse(body = "", fetchedAt = Instant.DISTANT_PAST)).copy(consecutiveFailures = failures, notBefore = t + wait)
    }

    private fun retryAfter(response: HttpResponse): Duration =
        response.headers["retry-after"]?.toLongOrNull()?.seconds ?: 1.hours

    fun parseDaily(body: String): List<DailyWeather> {
        val daily = json.parseToJsonElement(body).jsonObject["daily"]?.jsonObject ?: return emptyList()
        fun col(name: String): JsonArray? = daily[name]?.jsonArray
        val time = col("time") ?: return emptyList()
        val tMax = col("temperature_2m_max"); val tMin = col("temperature_2m_min"); val rain = col("precipitation_sum")
        val wind = col("wind_speed_10m_mean"); val rh = col("relative_humidity_2m_mean"); val et0 = col("et0_fao_evapotranspiration")
        fun JsonArray?.at(i: Int) = this?.getOrNull(i)?.jsonPrimitive?.doubleOrNull
        return time.indices.mapNotNull { i ->
            val lo = tMin.at(i) ?: return@mapNotNull null
            val hi = tMax.at(i) ?: return@mapNotNull null
            DailyWeather(
                date = LocalDate.parse(time[i].jsonPrimitive.content),
                tMinC = lo, tMaxC = hi,
                precipitationMm = rain.at(i) ?: 0.0,
                meanWindMs = wind.at(i), meanRelativeHumidityPct = rh.at(i), et0Mm = et0.at(i),
            )
        }
    }

    private fun parsePlaces(body: String): List<PlaceMatch> {
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            PlaceMatch(
                name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                region = listOfNotNull(o["admin1"]?.jsonPrimitive?.content, o["country"]?.jsonPrimitive?.content).joinToString(", "),
                latitude = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                longitude = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                elevationM = o["elevation"]?.jsonPrimitive?.doubleOrNull,
            )
        }
    }

    companion object {
        const val DAILY = "temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_mean,relative_humidity_2m_mean,et0_fao_evapotranspiration"
        const val CREDIT = "Weather data by Open-Meteo.com"
        const val CREDIT_URL = "https://open-meteo.com/"
        const val LICENCE = "CC BY 4.0"
        const val LICENCE_URL = "https://creativecommons.org/licenses/by/4.0/"
    }
}

data class PlaceMatch(val name: String, val region: String, val latitude: Double, val longitude: Double, val elevationM: Double?)

/** The transport call, with failures as values. Cancellation is never swallowed. */
internal suspend fun HttpTransport.call(url: String, headers: Map<String, String>): Result<HttpResponse> = try {
    Result.success(get(url, headers))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

internal fun urlEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = (b.toInt() and 0xff).toChar()
        if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in "-_.~") append(c)
        else append('%').append((b.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
    }
}
