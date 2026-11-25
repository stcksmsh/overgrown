// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

// "Use my location": one coarse fix, rounded, named after the nearest city of an offline list (docs/DECISIONS.md D40).

/** A populated place from Natural Earth, as tools/ne_cities.py writes it into assets/cities.tsv. */
internal data class City(val name: String, val country: String, val lat: Double, val lon: Double) {
    val label get() = if (country.isBlank()) name else "$name, $country"
}

internal data class NearestCity(val city: City, val km: Double)

/** Mean Earth radius, as the haversine formula commonly uses it. */
private const val EARTH_RADIUS_KM = 6371.0

/** Beyond this the city is not where the person is, so the label says how far it is. */
internal const val NEAR_KM = 50.0

/** A fix later than this counts as no fix. */
private const val FIX_TIMEOUT_MS = 10_000L

/** Two decimals of a degree is at most about 1.1 km, the rounding the privacy note promises. */
internal fun roundCoordinate(x: Double): Double = (x * 100).roundToLong() / 100.0

/** Great-circle distance in km. */
internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceAtMost(1.0)))
}

internal fun nearestCity(cities: List<City>, lat: Double, lon: Double): NearestCity? =
    cities.map { NearestCity(it, haversineKm(lat, lon, it.lat, it.lon)) }.minByOrNull { it.km }

/** The label the wizard shows after "Near": the city, with the distance when it is not close. */
internal fun cityLabel(n: NearestCity): String = if (n.km <= NEAR_KM) n.city.label else "${n.city.label} (${n.km.roundToLong()} km)"

/** One city per line: name, country, latitude, longitude, tab-separated. A malformed line fails, since the asset is built. */
internal fun parseCities(text: String): List<City> = text.lineSequence().filter { it.isNotEmpty() }.map { line ->
    val f = line.split('\t')
    require(f.size == 4) { "bad city line: $line" }
    City(f[0], f[1], f[2].toDouble(), f[3].toDouble())
}.toList()

internal suspend fun loadCities(context: Context): List<City> = withContext(Dispatchers.IO) {
    context.assets.open("cities.tsv").bufferedReader().use { parseCities(it.readText()) }
}

internal fun hasCoarseLocation(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** One current fix, or null when location is off, permission is missing, or nothing comes within [FIX_TIMEOUT_MS]. */
internal suspend fun currentLocation(context: Context): Location? {
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    val enabled = lm.getProviders(true)
    // No GPS: approximate permission does not cover it, and asking it throws.
    val preferred = listOfNotNull(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
        LocationManager.NETWORK_PROVIDER,
    )
    val provider = preferred.firstOrNull { it in enabled } ?: return null
    return try {
        withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
            }
        }
    } catch (e: SecurityException) {
        // Permission taken away between the check and the request.
        android.util.Log.w("Overgrown", "location permission lost", e)
        null
    }
}
