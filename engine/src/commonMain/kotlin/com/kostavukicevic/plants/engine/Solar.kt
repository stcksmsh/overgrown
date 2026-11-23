// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.engine

import com.kostavukicevic.plants.engine.Coefficients.HARGREAVES_COEFFICIENT
import com.kostavukicevic.plants.engine.Coefficients.HARGREAVES_TEMP_OFFSET_C
import com.kostavukicevic.plants.engine.Coefficients.MJ_TO_MM
import com.kostavukicevic.plants.engine.Coefficients.SOLAR_CONSTANT
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Solar geometry and reference evapotranspiration, FAO-56 chapter 3. */
object Solar {

    private fun inverseRelativeDistance(dayOfYear: Int) = 1 + 0.033 * cos(2 * PI * dayOfYear / 365)

    private fun declination(dayOfYear: Int) = 0.409 * sin(2 * PI * dayOfYear / 365 - 1.39)

    /** Sunset hour angle in radians. Clamped so polar day and night stay finite. */
    private fun sunsetHourAngle(latitudeDeg: Double, dayOfYear: Int): Double {
        val x = -tan(latitudeDeg * PI / 180) * tan(declination(dayOfYear))
        return acos(x.coerceIn(-1.0, 1.0))
    }

    /** Extraterrestrial radiation Ra, MJ m-2 day-1 (FAO-56 eq. 21). */
    fun extraterrestrialRadiation(latitudeDeg: Double, dayOfYear: Int): Double {
        val phi = latitudeDeg * PI / 180
        val decl = declination(dayOfYear)
        val ws = sunsetHourAngle(latitudeDeg, dayOfYear)
        return 24 * 60 / PI * SOLAR_CONSTANT * inverseRelativeDistance(dayOfYear) *
            (ws * sin(phi) * sin(decl) + cos(phi) * cos(decl) * sin(ws))
    }

    /** Day length in hours (FAO-56 eq. 34). */
    fun dayLengthHours(latitudeDeg: Double, dayOfYear: Int): Double =
        24 / PI * sunsetHourAngle(latitudeDeg, dayOfYear)

    /** Hargreaves-Samani reference evapotranspiration, mm day-1 (FAO-56 eq. 52). */
    fun hargreavesEt0(tMinC: Double, tMaxC: Double, latitudeDeg: Double, dayOfYear: Int): Double {
        val range = (tMaxC - tMinC).coerceAtLeast(0.0)
        val tMean = (tMaxC + tMinC) / 2
        val et0 = HARGREAVES_COEFFICIENT * (tMean + HARGREAVES_TEMP_OFFSET_C) * sqrt(range) *
            extraterrestrialRadiation(latitudeDeg, dayOfYear) * MJ_TO_MM
        return et0.coerceAtLeast(0.0)
    }
}
