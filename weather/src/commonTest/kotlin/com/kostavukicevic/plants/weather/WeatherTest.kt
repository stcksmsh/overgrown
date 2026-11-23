// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.weather

import com.kostavukicevic.plants.engine.WeatherQuality
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class WeatherTest {

    private class FakeHttp(var respond: (String, Map<String, String>) -> HttpResponse) : HttpTransport {
        val calls = ArrayList<Pair<String, Map<String, String>>>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            calls += url to headers
            return respond(url, headers)
        }
    }

    private var clock = Instant.parse("2026-11-23T10:00:00Z")
    private val at = Coordinates.of(44.816_789, 20.459_123)

    private val metBody = """
        {"properties":{"timeseries":[
          {"time":"2026-11-23T10:00:00Z","data":{"instant":{"details":{"air_temperature":5.0,"wind_speed":3.0,"relative_humidity":80}},"next_1_hours":{"details":{"precipitation_amount":0.5}},"next_6_hours":{"details":{"precipitation_amount":9.0}}}},
          {"time":"2026-11-23T11:00:00Z","data":{"instant":{"details":{"air_temperature":7.0,"wind_speed":4.0,"relative_humidity":70}},"next_1_hours":{"details":{"precipitation_amount":1.0}}}},
          {"time":"2026-11-23T12:00:00Z","data":{"instant":{"details":{"air_temperature":8.0,"wind_speed":5.0,"relative_humidity":60}},"next_6_hours":{"details":{"precipitation_amount":2.0}}}},
          {"time":"2026-11-23T18:00:00Z","data":{"instant":{"details":{"air_temperature":2.0,"wind_speed":2.0,"relative_humidity":90}},"next_6_hours":{"details":{"precipitation_amount":0.0}}}},
          {"time":"2026-11-24T06:00:00Z","data":{"instant":{"details":{"air_temperature":-1.0}}}}
        ]}}
    """.trimIndent()

    private val omBody = """
        {"daily":{"time":["2026-11-23","2026-11-24"],"temperature_2m_max":[9.8,5.3],"temperature_2m_min":[1.5,0.5],
         "precipitation_sum":[17.5,0.0],"wind_speed_10m_mean":[4.99,3.11],"relative_humidity_2m_mean":[83,67],"et0_fao_evapotranspiration":[0.68,1.14]}}
    """.trimIndent()

    private fun met(http: HttpTransport, store: ResponseStore = InMemoryResponseStore()) =
        MetNorway(http, store, { clock }, appVersion = "0.1.0", timeZone = TimeZone.UTC)

    private fun ok(body: String, vararg headers: Pair<String, String>) = HttpResponse(200, headers.toMap(), body)

    @Test
    fun coordinatesAreRoundedToTwoDecimals() {
        assertEquals("44.82,20.46", at.toString())
        val http = FakeHttp { _, _ -> ok(metBody) }
        assertTrue(met(http).url(at).endsWith("lat=44.82&lon=20.46"))
        assertTrue(OpenMeteo(http, InMemoryResponseStore(), { clock }).forecastUrl(at).contains("latitude=44.82&longitude=20.46"))
        assertEquals("-0.05", Coordinates.fmt(Coordinates.of(-0.049, 0.0).latitude))
    }

    @Test
    fun noMetNorwayRequestOutsideTheForeground() = runTest {
        val http = FakeHttp { _, _ -> ok(metBody) }
        assertIs<FetchResult.NotAllowed>(met(http).forecast(at, foreground = false))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun metNorwaySendsItsUserAgent() = runTest {
        val http = FakeHttp { _, _ -> ok(metBody) }
        met(http).forecast(at, foreground = true)
        assertEquals("Overgrown/0.1.0 https://github.com/stcksmsh/overgrown", http.calls.single().second["User-Agent"])
    }

    @Test
    fun noRequestBeforeExpiresThenAConditionalOne() = runTest {
        val http = FakeHttp { _, _ ->
            ok(metBody, "expires" to "Mon, 23 Nov 2026 10:30:00 GMT", "last-modified" to "Mon, 23 Nov 2026 09:55:00 GMT")
        }
        val client = met(http)
        assertIs<FetchResult.Fresh>(client.forecast(at, true))
        clock += 20.minutes
        assertIs<FetchResult.Cached>(client.forecast(at, true))
        assertEquals(1, http.calls.size)

        clock += 20.minutes
        http.respond = { _, _ -> HttpResponse(304, mapOf("expires" to "Mon, 23 Nov 2026 11:30:00 GMT"), "") }
        assertIs<FetchResult.Cached>(client.forecast(at, true))
        assertEquals(2, http.calls.size)
        assertEquals("Mon, 23 Nov 2026 09:55:00 GMT", http.calls[1].second["If-Modified-Since"])

        clock += 20.minutes // before the new Expires from the 304
        client.forecast(at, true)
        assertEquals(2, http.calls.size)
    }

    @Test
    fun backsOffOn429() = runTest {
        val http = FakeHttp { _, _ -> HttpResponse(429, mapOf("retry-after" to "600"), "") }
        val client = met(http)
        val limited = assertIs<FetchResult.RateLimited>(client.forecast(at, true))
        assertEquals(clock + 10.minutes, limited.retryAfter)
        clock += 5.minutes
        assertIs<FetchResult.RateLimited>(client.forecast(at, true))
        assertEquals(1, http.calls.size)

        val om = OpenMeteo(http, InMemoryResponseStore(), { clock })
        assertIs<FetchResult.RateLimited>(om.forecast(at))
        assertIs<FetchResult.RateLimited>(om.forecast(at))
        assertEquals(2, http.calls.size)
    }

    @Test
    fun withoutExpiresMetNorwayIsAskedAtMostHourly() = runTest {
        val http = FakeHttp { _, _ -> ok(metBody) }
        val client = met(http)
        client.forecast(at, true)
        clock += 30.minutes
        assertIs<FetchResult.Cached>(client.forecast(at, true))
        clock += 31.minutes
        client.forecast(at, true)
        assertEquals(2, http.calls.size)
    }

    @Test
    fun errorsBackOffForBothProviders() = runTest {
        val http = FakeHttp { _, _ -> HttpResponse(500, emptyMap(), "") }
        val om = OpenMeteo(http, InMemoryResponseStore(), { clock })
        assertIs<FetchResult.Failed>(om.forecast(at))
        clock += 5.minutes
        assertIs<FetchResult.RateLimited>(om.forecast(at))
        val client = met(http)
        assertIs<FetchResult.Failed>(client.forecast(at, true))
        assertIs<FetchResult.RateLimited>(client.forecast(at, true))
        assertEquals(2, http.calls.size)
    }

    @Test
    fun forbiddenIsReportedAsABug() = runTest {
        val result = met(FakeHttp { _, _ -> HttpResponse(403, emptyMap(), "") }).forecast(at, true)
        assertTrue(assertIs<FetchResult.Failed>(result).reason.contains("bug"))
    }

    @Test
    fun metNorwayHoursAggregateIntoDays() {
        val days = met(FakeHttp { _, _ -> ok("") }).aggregate(metBody)
        val d = days.single() // 24 November has one reading and is dropped as incomplete
        assertEquals(LocalDate(2026, Month.NOVEMBER, 23), d.date)
        assertEquals(2.0, d.tMinC); assertEquals(8.0, d.tMaxC)
        assertEquals(0.5 + 1.0 + 2.0 + 0.0, d.precipitationMm, 1e-9) // 1-hour periods, then 6-hour ones, no double count
        assertEquals(3.5, d.meanWindMs!!, 1e-9)
        assertEquals(null, d.et0Mm)
    }

    @Test
    fun openMeteoDailyParsesWithEt0() {
        val days = OpenMeteo(FakeHttp { _, _ -> ok("") }, InMemoryResponseStore(), { clock }).parseDaily(omBody)
        assertEquals(2, days.size)
        assertEquals(0.68, days[0].et0Mm)
        assertEquals(17.5, days[0].precipitationMm)
    }

    @Test
    fun openMeteoIsNotAskedTwiceWithinTheInterval() = runTest {
        val http = FakeHttp { _, _ -> ok(omBody) }
        val om = OpenMeteo(http, InMemoryResponseStore(), { clock })
        assertIs<FetchResult.Fresh>(om.forecast(at))
        clock += 30.minutes
        assertIs<FetchResult.Cached>(om.forecast(at))
        clock += 31.minutes
        assertIs<FetchResult.Fresh>(om.forecast(at))
        assertEquals(2, http.calls.size)
    }

    @Test
    fun fallsBackToMetNorwayAfterSustainedFailuresOnlyInTheForeground() = runTest {
        val http = FakeHttp { url, _ -> if ("open-meteo" in url) HttpResponse(500, emptyMap(), "") else ok(metBody) }
        val state = InMemoryFallbackState()
        val service = WeatherService(OpenMeteo(http, InMemoryResponseStore(), { clock }), met(http), state)

        // Three real failures, spaced past the back-off, in the background: never MET Norway.
        repeat(3) {
            assertIs<FetchResult.Failed>(service.fetch(at, foreground = false))
            clock += kotlin.time.Duration.parse("7h")
        }
        assertTrue(http.calls.none { "api.met.no" in it.first })
        assertTrue(state.useFallback())

        val fg = service.fetch(at, foreground = true)
        assertEquals(ProviderId.MET_NORWAY, assertIs<FetchResult.Fresh>(fg).provider)
    }

    @Test
    fun ownBackOffDoesNotCountAsAFailure() = runTest {
        val http = FakeHttp { url, _ -> if ("open-meteo" in url) HttpResponse(500, emptyMap(), "") else ok(metBody) }
        val state = InMemoryFallbackState()
        val service = WeatherService(OpenMeteo(http, InMemoryResponseStore(), { clock }), met(http), state)
        service.fetch(at, foreground = true) // one real failure
        repeat(5) { service.fetch(at, foreground = true) } // inside the back-off: no requests, no new failures
        assertEquals(1, state.failures())
        assertTrue(!state.useFallback())
        assertTrue(http.calls.none { "api.met.no" in it.first })
    }

    @Test
    fun perInstallRefreshTimesDiffer() {
        val a = RefreshSchedule.minuteOfDay(1_234_567L)
        val b = RefreshSchedule.minuteOfDay(7_654_321L)
        assertTrue(a != b && a in 0 until 1440 && b in 0 until 1440)
        val next = RefreshSchedule.nextRun(clock, TimeZone.UTC, minuteOfDay = 9 * 60)
        assertEquals(Instant.parse("2026-11-24T09:00:00Z"), next)
        assertEquals(23.hours, RefreshSchedule.delayUntilNext(clock, TimeZone.UTC, 9 * 60))
    }

    @Test
    fun gapsAreFilledFromClimatologyAndFlagged() {
        val nov = DailyWeather(LocalDate(2025, Month.NOVEMBER, 1), 2.0, 10.0, 1.5, 3.0, 80.0, 0.8)
        val clim = Climatology.from(listOf(nov, nov.copy(tMinC = 4.0)))
        val stored = mapOf(LocalDate(2026, Month.NOVEMBER, 21) to nov.copy(date = LocalDate(2026, Month.NOVEMBER, 21)))
        val series = Series.build(stored, LocalDate(2026, Month.NOVEMBER, 21), LocalDate(2026, Month.NOVEMBER, 23), today = LocalDate(2026, Month.NOVEMBER, 23), climatology = clim)
        assertEquals(listOf(WeatherQuality.STORED_FORECAST, WeatherQuality.CLIMATOLOGY_FILL, WeatherQuality.CLIMATOLOGY_FILL), series.map { it.quality })
        assertEquals(3.0, series[1].tMinC)
    }
}
