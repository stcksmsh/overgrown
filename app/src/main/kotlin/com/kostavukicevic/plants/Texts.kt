// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants

import android.content.Context
import com.kostavukicevic.plants.data.AlertEvent
import com.kostavukicevic.plants.data.AlertKind
import com.kostavukicevic.plants.data.PlantStatus
import com.kostavukicevic.plants.data.Reminder
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.Alert
import com.kostavukicevic.plants.engine.AlertType
import com.kostavukicevic.plants.engine.Coefficients
import com.kostavukicevic.plants.engine.HoldReason
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.engine.PromptReason
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.format
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Words for what the engine says. All text comes from resources. */
object Texts {
    private val dayFormat = LocalDate.Format { day(Padding.NONE); char(' '); monthName(MonthNames.ENGLISH_ABBREVIATED) }

    private val weekdayFormat = LocalDate.Format { dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED) }
    // A no-break space keeps "6 Dec" on one line.
    private val shortDayFormat = LocalDate.Format { day(Padding.NONE); char('\u00A0'); monthName(MonthNames.ENGLISH_ABBREVIATED) }

    fun date(d: LocalDate): String = d.format(dayFormat)

    /** A day seen from [today]: the weekday within six days, where it cannot be mistaken, else "6 Dec". */
    fun day(d: LocalDate, today: LocalDate): String = d.format(if (today.daysUntil(d) in 0..6) weekdayFormat else shortDayFormat)

    /**
     * The word for a share of water left, on the engine's check-in answer bands (as depletion, 1 - water left):
     * below [Coefficients.BAND_TOO_WET_MAX] is wet, below [Coefficients.BAND_OK_MAX] moist,
     * below [Coefficients.BAND_DRY_MAX] getting dry, and dry from there.
     */
    fun waterLevel(c: Context, waterLeft: Double): String {
        val depletion = 1 - waterLeft
        return c.getString(
            when {
                depletion < Coefficients.BAND_TOO_WET_MAX -> R.string.level_wet
                depletion < Coefficients.BAND_OK_MAX -> R.string.level_moist
                depletion < Coefficients.BAND_DRY_MAX -> R.string.level_getting_dry
                else -> R.string.level_dry
            },
        )
    }

    /** One short sentence per kind of weather alert, over the days it falls on: "Frost Mon-Tue, Thu." */
    fun alertSummary(c: Context, alerts: List<Alert>, today: LocalDate): String =
        alerts.groupBy { if (it.type == AlertType.ROOT_FREEZE_STRONG || it.type == AlertType.FROST) AlertType.ROOT_FREEZE_CAUTION else it.type }
            .entries.sortedBy { (_, a) -> a.minOf { it.date } }
            .joinToString(" ") { (type, a) ->
                val days = dayRuns(a.map { it.date }).map { (first, last) ->
                    if (first == last) day(first, today) else c.getString(R.string.today_day_range, day(first, today), day(last, today))
                }.reduce { acc, run -> c.getString(R.string.today_day_list, acc, run) }
                when (type) {
                    AlertType.ROOT_FREEZE_CAUTION, AlertType.ROOT_FREEZE_STRONG, AlertType.FROST -> c.getString(R.string.today_alert_frost, days)
                    AlertType.COLD -> c.getString(R.string.event_cold, days)
                    AlertType.HEAT -> c.getString(R.string.today_alert_heat, days)
                    AlertType.HEAVY_RAIN -> c.getString(R.string.today_alert_heavy_rain, days)
                    AlertType.WIND -> c.getString(R.string.today_alert_wind, days)
                    AlertType.WATERLOGGING -> c.getString(R.string.today_alert_waterlogging)
                }
            }

    /** [alertSummary] over alert events, so the line agrees with the cards shown above it. */
    fun eventSummary(c: Context, events: List<AlertEvent>, today: LocalDate): String =
        alertSummary(c, events.flatMap { e -> generateSequence(e.start) { it.plus(DatePeriod(days = 1)) }.takeWhile { it <= e.end }.map { Alert(e.severity, it) } }, today)

    /** An event's days: "Wed" or "Wed-Fri". An event is one run of consecutive days. */
    fun eventDays(c: Context, e: AlertEvent, today: LocalDate): String =
        if (e.start == e.end) day(e.start, today) else c.getString(R.string.today_day_range, day(e.start, today), day(e.end, today))

    /** Every plant in the event in one phrase: "Boris", "Boris and Fern", "Boris, Fern and Ivy". */
    fun eventPlants(c: Context, e: AlertEvent): String {
        val names = e.plants.map { it.name }
        if (names.size == 1) return names.single()
        return c.getString(R.string.event_names_last, names.dropLast(1).reduce { acc, n -> c.getString(R.string.today_day_list, acc, n) }, names.last())
    }

    private val AlertEvent.strong get() = severity == AlertType.ROOT_FREEZE_STRONG

    /** Cold or frost for a plant's own limit, not its pot's: bring it in or cover it. */
    private val AlertEvent.tender get() = severity == AlertType.COLD || severity == AlertType.FROST

    /** What to do about the event: "Move Boris and Fern against a wall." [boris] voices it, for the notification only. */
    fun eventAdvice(c: Context, e: AlertEvent, boris: Boolean = false): String = Voice.string(
        c.resources,
        boris,
        when (e.kind) {
            AlertKind.COLD -> when {
                e.tender -> R.string.event_do_tender
                e.strong -> R.string.event_do_cold_strong
                else -> R.string.event_do_cold
            }
            AlertKind.HEAT -> R.string.event_do_heat
            AlertKind.HEAVY_RAIN -> R.string.event_do_heavy_rain
            AlertKind.WIND -> R.string.event_do_wind
            AlertKind.WATERLOGGING -> R.string.event_do_waterlogging
        },
        eventPlants(c, e),
    )

    /** The alert card's text, the event and then what to do: "Cold nights Mon-Wed. Move Boris and Fern against a wall." */
    fun eventCard(c: Context, e: AlertEvent, today: LocalDate): String {
        val days = eventDays(c, e, today)
        val what = when (e.kind) {
            AlertKind.COLD -> c.getString(
                when (e.severity) {
                    AlertType.FROST -> R.string.today_alert_frost
                    AlertType.ROOT_FREEZE_STRONG -> R.string.event_cold_strong
                    else -> R.string.event_cold
                },
                days,
            )
            AlertKind.HEAT -> c.getString(R.string.today_alert_heat, days)
            AlertKind.HEAVY_RAIN -> c.getString(R.string.today_alert_heavy_rain, days)
            AlertKind.WIND -> c.getString(R.string.today_alert_wind, days)
            AlertKind.WATERLOGGING -> c.getString(R.string.today_alert_waterlogging)
        }
        return "$what ${eventAdvice(c, e)}"
    }

    /** The alert notification's title, starting with the event: "Frost Mon-Wed: Boris". */
    fun eventTitle(c: Context, e: AlertEvent, today: LocalDate): String {
        val days = eventDays(c, e, today)
        val plants = eventPlants(c, e)
        return when (e.kind) {
            AlertKind.COLD -> c.getString(if (e.severity == AlertType.COLD) R.string.notif_event_cold else R.string.notif_event_frost, days, plants)
            AlertKind.HEAT -> c.getString(R.string.notif_event_heat, days, plants)
            AlertKind.HEAVY_RAIN -> c.getString(R.string.notif_event_heavy_rain, days, plants)
            AlertKind.WIND -> c.getString(R.string.notif_event_wind, days, plants)
            AlertKind.WATERLOGGING -> c.getString(R.string.notif_event_waterlogging, plants)
        }
    }

    /** Runs of consecutive days, so a gap day is never inside a range: Mon, Wed is two runs, not Mon-Wed. */
    internal fun dayRuns(dates: List<LocalDate>): List<Pair<LocalDate, LocalDate>> {
        val runs = mutableListOf<Pair<LocalDate, LocalDate>>()
        for (d in dates.distinct().sorted()) {
            val last = runs.lastOrNull()
            if (last != null && last.second.daysUntil(d) == 1) runs[runs.lastIndex] = last.first to d else runs += d to d
        }
        return runs
    }

    /** Local date and time, e.g. "23 Nov 10:04". */
    fun dateTime(at: Instant): String {
        val t = at.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${date(t.date)} ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }

    /** What to do today. [boris] voices it, for the notification body only. */
    fun action(c: Context, a: Action, boris: Boolean = false): String = when (a) {
        is Action.WaterNow -> Voice.string(c.resources, boris, R.string.action_water_now, roundDose(a.doseMl))
        is Action.CheckSoil -> Voice.string(c.resources, boris, R.string.action_check_soil)
        is Action.Hold -> when (a.reason) {
            HoldReason.FROST -> c.getString(R.string.action_hold_frost)
            HoldReason.TOO_COLD -> c.getString(R.string.action_hold_cold)
            HoldReason.RAIN_EXPECTED -> c.getString(R.string.action_hold_rain)
        }
        Action.RepotOrWaterDaily -> Voice.string(c.resources, boris, R.string.action_repot)
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
            AlertType.COLD -> c.getString(R.string.alert_cold, d)
            AlertType.FROST -> c.getString(R.string.alert_frost, d)
        }
    }

    fun reminder(c: Context, r: Reminder): String = c.getString(
        when (r) {
            Reminder.FEED -> R.string.reminder_feed
            Reminder.REPOT -> R.string.reminder_repot
        },
    )

    fun moisture(c: Context, m: Moisture) = c.getString(
        when (m) {
            Moisture.TOO_WET -> R.string.moisture_too_wet
            Moisture.OK -> R.string.moisture_ok
            Moisture.DRY, Moisture.DRY_OR_BONE_DRY -> R.string.moisture_dry
            Moisture.BONE_DRY -> R.string.moisture_bone_dry
        },
    )

    /** A dose to the nearest 50 ml: nobody measures 437 ml. */
    fun roundDose(ml: Int): Int = ((ml + 25) / 50 * 50).coerceAtLeast(50)
}
