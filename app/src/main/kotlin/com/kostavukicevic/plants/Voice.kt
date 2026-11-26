// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalResources

/**
 * The app's two voices: plain, and Boris, the mascot's dry one (strings_boris.xml). Every Boris line is the twin of
 * a plain string with the same placeholders. A twin is used only when the person chose Boris and this language has
 * Boris lines ([R.bool.boris_voice_available]); otherwise, and for any string without a twin, the plain one.
 */
object Voice {
    // Listed here rather than found by name at run time, so resource shrinking keeps them and lint sees them used.
    // VoiceTest checks that every _boris resource is in one of these maps.
    internal val strings = mapOf(
        R.string.today_all_fine to R.string.today_all_fine_boris,
        R.string.today_nothing_to_water to R.string.today_nothing_to_water_boris,
        R.string.tile_fine to R.string.tile_fine_boris,
        R.string.tile_fine_check to R.string.tile_fine_check_boris,
        R.string.today_empty to R.string.today_empty_boris,
        R.string.plants_empty to R.string.plants_empty_boris,
        R.string.places_empty to R.string.places_empty_boris,
        R.string.forecast_failed to R.string.forecast_failed_boris,
        R.string.notif_stale_text to R.string.notif_stale_text_boris,
        R.string.action_water_now to R.string.action_water_now_boris,
        R.string.action_check_soil to R.string.action_check_soil_boris,
        R.string.action_repot to R.string.action_repot_boris,
        R.string.event_do_cold to R.string.event_do_cold_boris,
        R.string.event_do_cold_strong to R.string.event_do_cold_strong_boris,
        R.string.event_do_heat to R.string.event_do_heat_boris,
        R.string.event_do_heavy_rain to R.string.event_do_heavy_rain_boris,
        R.string.event_do_wind to R.string.event_do_wind_boris,
        R.string.event_do_waterlogging to R.string.event_do_waterlogging_boris,
        R.string.event_do_tender to R.string.event_do_tender_boris,
    )
    internal val plurals = mapOf(
        R.plurals.forecast_stale to R.plurals.forecast_stale_boris,
    )

    private fun pick(res: Resources, boris: Boolean, id: Int, twins: Map<Int, Int>): Int =
        if (boris && res.getBoolean(R.bool.boris_voice_available)) twins[id] ?: id else id

    /** The string [id] in the chosen voice: its Boris twin when [boris] is on and available, else the plain one. */
    fun string(res: Resources, boris: Boolean, @StringRes id: Int, vararg args: Any): String =
        res.getString(pick(res, boris, id, strings), *args)

    /** As [string], for plurals. */
    fun plural(res: Resources, boris: Boolean, @PluralsRes id: Int, count: Int, vararg args: Any): String =
        res.getQuantityString(pick(res, boris, id, plurals), count, *args)
}

/** Whether the person chose Boris. Plain outside ChosenTheme, which provides the stored choice. */
val LocalBoris = staticCompositionLocalOf { false }

/** [Voice.string] in the current composition's voice. */
@Composable
fun voicedResource(@StringRes id: Int, vararg args: Any): String = Voice.string(LocalResources.current, LocalBoris.current, id, *args)

/** [Voice.plural] in the current composition's voice. */
@Composable
fun voicedPlural(@PluralsRes id: Int, count: Int, vararg args: Any): String = Voice.plural(LocalResources.current, LocalBoris.current, id, count, *args)
