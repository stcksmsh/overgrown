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
import com.kostavukicevic.plants.weather.HttpResponse
import com.kostavukicevic.plants.weather.HttpTransport
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.OpenMeteo
import com.kostavukicevic.plants.weather.WeatherService
import kotlinx.coroutines.Dispatchers
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
