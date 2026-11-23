// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.weather

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * MET Norway Locationforecast, the fallback provider. The owner accepted its terms; the rules
 * this client enforces are in docs/acceptance/met-norway-2026-11-23.md:
 * - never a request unless the app is in the foreground;
 * - an identifying User-Agent;
 * - never a repeat before Expires; If-Modified-Since on every repeat; every response cached;
 * - coordinates with 2 decimals;
 * - back-off on 429; 403 is reported as a bug.
 * It has no history and no solar radiation, so days are aggregated here and ET0 is left to the engine.
 */
class MetNorway(
    private val http: HttpTransport,
    private val store: ResponseStore,
    private val now: () -> Instant,
    private val appVersion: String,
    private val timeZone: TimeZone,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val userAgent: String get() = "Overgrown/$appVersion https://github.com/stcksmsh/overgrown"

    fun url(at: Coordinates) =
        "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${Coordinates.fmt(at.latitude)}&lon=${Coordinates.fmt(at.longitude)}"

    /** @param foreground true only while the app is visible. False never sends a request. */
    suspend fun forecast(at: Coordinates, foreground: Boolean): FetchResult {
        if (!foreground) return FetchResult.NotAllowed(ProviderId.MET_NORWAY, "MET Norway is asked only while the app is open")
        val key = "met-norway:compact:$at"
        val cached = store.load(key)
        val t = now()
        cached?.notBefore?.let { if (t < it) return FetchResult.RateLimited(ProviderId.MET_NORWAY, it, local = true) }
        // Never before Expires; and without an Expires header, not more than once an hour.
        val fresh = cached?.expires ?: cached?.fetchedAt?.plus(1.hours)
        if (cached != null && cached.body.isNotEmpty() && fresh != null && t < fresh) {
            return FetchResult.Cached(ProviderId.MET_NORWAY, aggregate(cached.body), cached.fetchedAt)
        }

        val headers = buildMap {
            put("User-Agent", userAgent)
            cached?.lastModified?.let { put("If-Modified-Since", it) }
        }
        val response = http.call(url(at), headers).getOrElse {
            val failures = (cached?.consecutiveFailures ?: 0) + 1
            store.save(key, backOff(cached, t, failures).copy(notBefore = t + backOffFor(failures)))
            return FetchResult.Failed(ProviderId.MET_NORWAY, it.message ?: "network error")
        }
        val expires = response.headers["expires"]?.let(::parseHttpDate)
        return when (response.status) {
            200 -> {
                val days = try {
                    aggregate(response.body)
                } catch (e: IllegalArgumentException) {
                    val failures = (cached?.consecutiveFailures ?: 0) + 1
                    store.save(key, backOff(cached, t, failures).copy(notBefore = t + backOffFor(failures)))
                    return FetchResult.Failed(ProviderId.MET_NORWAY, "unreadable response: ${e.message}")
                }
                store.save(key, CachedResponse(response.body, t, expires, response.headers["last-modified"]))
                FetchResult.Fresh(ProviderId.MET_NORWAY, days, t)
            }
            304 -> {
                val body = cached?.body ?: return FetchResult.Failed(ProviderId.MET_NORWAY, "304 without a cached response")
                // Without a new Expires, wait an hour rather than reuse a past one.
                store.save(key, cached.copy(expires = expires ?: (t + 1.hours), consecutiveFailures = 0, notBefore = null))
                FetchResult.Cached(ProviderId.MET_NORWAY, aggregate(body), cached.fetchedAt)
            }
            429 -> {
                val failures = (cached?.consecutiveFailures ?: 0) + 1
                val wait = response.headers["retry-after"]?.toLongOrNull()?.seconds ?: backOffFor(failures)
                store.save(key, backOff(cached, t, failures).copy(notBefore = t + wait))
                FetchResult.RateLimited(ProviderId.MET_NORWAY, t + wait)
            }
            403 -> {
                store.save(key, backOff(cached, t, (cached?.consecutiveFailures ?: 0) + 1).copy(notBefore = t + 24.hours))
                FetchResult.Failed(ProviderId.MET_NORWAY, "HTTP 403: MET Norway refused the request; this is a bug to fix (User-Agent or terms)")
            }
            else -> {
                val failures = (cached?.consecutiveFailures ?: 0) + 1
                store.save(key, backOff(cached, t, failures).copy(notBefore = t + backOffFor(failures)))
                FetchResult.Failed(ProviderId.MET_NORWAY, "HTTP ${response.status}")
            }
        }
    }

    private fun backOff(cached: CachedResponse?, t: Instant, failures: Int) =
        (cached ?: CachedResponse(body = "", fetchedAt = Instant.DISTANT_PAST)).copy(consecutiveFailures = failures)

    /** 10 minutes, doubling, at most 6 hours. */
    private fun backOffFor(failures: Int): Duration {
        val d = 10.minutes * (1 shl (failures - 1).coerceIn(0, 6))
        return if (d > 6.hours) 6.hours else d
    }

    /**
     * Daily min and max from the instant temperatures, precipitation summed from the 1-hour
     * periods where given and the 6-hour periods after that, mean wind and humidity. Days are
     * local to [timeZone]. Days with fewer than 4 readings are dropped as incomplete.
     */
    fun aggregate(body: String): List<DailyWeather> {
        val series = json.parseToJsonElement(body).jsonObject["properties"]?.jsonObject?.get("timeseries")?.jsonArray ?: return emptyList()
        class Acc { val temps = ArrayList<Double>(); val winds = ArrayList<Double>(); val rhs = ArrayList<Double>(); var rain = 0.0 }
        val byDay = LinkedHashMap<LocalDate, Acc>()
        var coveredUntil = Instant.DISTANT_PAST
        for (entry in series) {
            val o = entry as? JsonObject ?: continue
            val time = Instant.parse(o["time"]?.jsonPrimitive?.content ?: continue)
            val data = o["data"]?.jsonObject ?: continue
            val details = data["instant"]?.jsonObject?.get("details")?.jsonObject ?: continue
            val acc = byDay.getOrPut(time.toLocalDateTime(timeZone).date) { Acc() }
            details["air_temperature"]?.jsonPrimitive?.doubleOrNull?.let { acc.temps += it }
            details["wind_speed"]?.jsonPrimitive?.doubleOrNull?.let { acc.winds += it }
            details["relative_humidity"]?.jsonPrimitive?.doubleOrNull?.let { acc.rhs += it }
            if (time >= coveredUntil) {
                fun precip(period: String) = data[period]?.jsonObject?.get("details")?.jsonObject?.get("precipitation_amount")?.jsonPrimitive?.doubleOrNull
                val oneHour = precip("next_1_hours")
                val sixHours = precip("next_6_hours")
                if (oneHour != null) {
                    acc.rain += oneHour; coveredUntil = time + 1.hours
                } else if (sixHours != null) {
                    acc.rain += sixHours; coveredUntil = time + 6.hours
                }
            }
        }
        return byDay.entries.sortedBy { it.key }.mapNotNull { (date, a) ->
            if (a.temps.size < 4) return@mapNotNull null
            DailyWeather(
                date = date,
                tMinC = a.temps.min(), tMaxC = a.temps.max(), precipitationMm = a.rain,
                meanWindMs = a.winds.takeIf { it.isNotEmpty() }?.average(),
                meanRelativeHumidityPct = a.rhs.takeIf { it.isNotEmpty() }?.average(),
                et0Mm = null,
            )
        }
    }

    companion object {
        const val CREDIT = "Data from MET Norway"
        const val CREDIT_URL = "https://api.met.no/"
        const val LICENCE = "NLOD 2.0 and CC BY 4.0"
        const val LICENCE_URL = "https://api.met.no/doc/License"
    }
}

/** RFC 1123 date as used in Expires and Last-Modified; null if unreadable. */
internal fun parseHttpDate(s: String): Instant? = try {
    DateTimeComponents.Formats.RFC_1123.parse(s).toInstantUsingOffset()
} catch (e: IllegalArgumentException) {
    null
}
