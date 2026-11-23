// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants

import android.content.Context
import com.kostavukicevic.plants.data.PlantStatus
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.Alert
import com.kostavukicevic.plants.engine.AlertType
import com.kostavukicevic.plants.engine.HoldReason
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.engine.PromptReason
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.format
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Words for what the engine says. All text comes from resources. */
object Texts {
    private val dayFormat = LocalDate.Format { day(); char(' '); monthName(MonthNames.ENGLISH_ABBREVIATED) }

    fun date(d: LocalDate): String = d.format(dayFormat)

    /** Local date and time, e.g. "23 Nov 10:04". */
    fun dateTime(at: Instant): String {
        val t = at.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${date(t.date)} ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }

    fun action(c: Context, a: Action): String = when (a) {
        is Action.WaterNow -> c.getString(R.string.action_water_now, roundDose(a.doseMl))
        is Action.CheckSoil -> c.getString(R.string.action_check_soil)
        is Action.Hold -> when (a.reason) {
            HoldReason.FROST -> c.getString(R.string.action_hold_frost)
            HoldReason.TOO_COLD -> c.getString(R.string.action_hold_cold)
            HoldReason.RAIN_EXPECTED -> c.getString(R.string.action_hold_rain)
        }
        Action.RepotOrWaterDaily -> c.getString(R.string.action_repot)
        Action.None -> c.getString(R.string.action_none)
    }

    /** Why, in one line: the prompt's reason, plus the dry spell when there is one. */
    fun reason(c: Context, s: PlantStatus): String? {
        val base = when (val a = s.today) {
            is Action.WaterNow -> reasonFor(c, a.reason)
            is Action.CheckSoil -> reasonFor(c, a.reason)
            else -> null
        } ?: return null
        val spell = s.state.daysSinceRain.takeIf { s.place.outdoor && it >= 3 }?.let { c.resources.getQuantityString(R.plurals.reason_dry_spell, it, it) }
        return listOfNotNull(base, spell).joinToString(" ")
    }

    private fun reasonFor(c: Context, r: PromptReason) = when (r) {
        PromptReason.DRY_BY_MODEL -> c.getString(R.string.reason_dry)
        PromptReason.UNCERTAIN -> c.getString(R.string.reason_uncertain)
        PromptReason.WINTER_FLOOR -> c.getString(R.string.reason_winter_floor)
    }

    fun alert(c: Context, a: Alert): String {
        val d = date(a.date)
        return when (a.type) {
            AlertType.ROOT_FREEZE_CAUTION -> c.getString(R.string.alert_root_freeze_caution, d)
            AlertType.ROOT_FREEZE_STRONG -> c.getString(R.string.alert_root_freeze_strong, d)
            AlertType.HEAT -> c.getString(R.string.alert_heat, d)
            AlertType.HEAVY_RAIN -> c.getString(R.string.alert_heavy_rain, d)
            AlertType.WIND -> c.getString(R.string.alert_wind, d)
            AlertType.WATERLOGGING -> c.getString(R.string.alert_waterlogging)
        }
    }

    fun moisture(c: Context, m: Moisture) = c.getString(
        when (m) {
            Moisture.TOO_WET -> R.string.moisture_too_wet
            Moisture.OK -> R.string.moisture_ok
            Moisture.DRY -> R.string.moisture_dry
            Moisture.BONE_DRY -> R.string.moisture_bone_dry
        },
    )

    /** A dose to the nearest 50 ml: nobody measures 437 ml. */
    fun roundDose(ml: Int): Int = ((ml + 25) / 50 * 50).coerceAtLeast(50)
}
