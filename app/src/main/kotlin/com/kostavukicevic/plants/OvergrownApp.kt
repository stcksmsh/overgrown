// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants

import android.app.Application
import android.content.Context
import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.data.AndroidStorage
import com.kostavukicevic.plants.data.DbResponseStore
import com.kostavukicevic.plants.data.Garden
import com.kostavukicevic.plants.data.Settings
import com.kostavukicevic.plants.notify.Notifier
import com.kostavukicevic.plants.notify.Scheduler
import com.kostavukicevic.plants.weather.FetchResult
import com.kostavukicevic.plants.weather.HttpResponse
import com.kostavukicevic.plants.weather.HttpTransport
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.OpenMeteo
import com.kostavukicevic.plants.weather.WeatherService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Clock

class OvergrownApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        Notifier.createChannels(this)
    }
}

val Context.graph: Graph get() = (applicationContext as OvergrownApp).graph

/** The app's objects, created once. */
class Graph(context: Context) {
    val settings: Settings = AndroidStorage.settings(context)
    private val db = AndroidStorage.database(context)
    private val now = { Clock.System.now() }
    private val zone = { TimeZone.currentSystemDefault() }
    val openMeteo = OpenMeteo(UrlTransport, DbResponseStore(db.dao()), now)
    /** From the package, which takes it from the static literal in app/build.gradle.kts. */
    val versionName: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    private val metNorway = MetNorway(UrlTransport, DbResponseStore(db.dao()), now, versionName, TimeZone.currentSystemDefault())

    /** The compiled care database shipped as an asset. Only reviewed entries are in it. */
    val careDb: CareDb? by lazy {
        runCatching { context.assets.open("caredb.json").bufferedReader().use { CareDb.parse(it.readText()) } }
            .onFailure { android.util.Log.e("Overgrown", "care database asset unreadable", it) }
            .getOrNull()
    }

    val garden = Garden(db, settings, WeatherService(openMeteo, metNorway, settings), openMeteo, { careDb }, now, zone, AndroidStorage.photos(context))
    val scheduler = Scheduler(context.applicationContext, this)

    /** Bumped after any change, so screens recompute. */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun changed() { changes.value++ }

    private val fetchCount = MutableStateFlow(0)
    private val lastFetchFailed = MutableStateFlow(false)

    /**
     * True while a weather fetch started through [refreshWeather] runs, so Today can say so whoever started it.
     * Derived from one counter, so two overlapping fetches end once both have. Unconfined keeps it in step with the count.
     */
    val fetching: StateFlow<Boolean> = fetchCount.map { it > 0 }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), SharingStarted.Eagerly, false)

    /** The last finished fetch through [refreshWeather] got no forecast anywhere and a request failed. */
    val fetchFailed: StateFlow<Boolean> = lastFetchFailed

    /** [Garden.refreshWeather], counted in [fetching]. A cancelled fetch did not fail, so it leaves [fetchFailed] as it was. */
    suspend fun refreshWeather(foreground: Boolean): Map<Long, FetchResult> {
        fetchCount.update { it + 1 }
        try {
            return garden.refreshWeather(foreground).also { lastFetchFailed.value = failedEverywhere(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFetchFailed.value = true
            throw e
        } finally {
            fetchCount.update { it - 1 }
        }
    }

    /** A rate limit or a fresh cache is not a failure. */
    private fun failedEverywhere(results: Map<Long, FetchResult>): Boolean =
        results.values.any { it is FetchResult.Failed } && results.values.none { it is FetchResult.Fresh || it is FetchResult.Cached }
}

/** HttpURLConnection, from the platform: no HTTP library needed. */
object UrlTransport : HttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val responseHeaders = conn.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull() ?: "" }
            HttpResponse(status, responseHeaders, body)
        } finally {
            conn.disconnect()
        }
    }
}
