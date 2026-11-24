// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import com.kostavukicevic.plants.engine.AlertType
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

/** What an alert card is about. Root-freeze caution and strong are one kind; the event carries the severity. */
enum class AlertKind { COLD, HEAT, HEAVY_RAIN, WIND, WATERLOGGING }

fun AlertType.kind(): AlertKind = when (this) {
    AlertType.ROOT_FREEZE_CAUTION, AlertType.ROOT_FREEZE_STRONG -> AlertKind.COLD
    AlertType.HEAT -> AlertKind.HEAT
    AlertType.HEAVY_RAIN -> AlertKind.HEAVY_RAIN
    AlertType.WIND -> AlertKind.WIND
    AlertType.WATERLOGGING -> AlertKind.WATERLOGGING
}

/** Only cold has two levels. */
private fun AlertType.rank() = if (this == AlertType.ROOT_FREEZE_STRONG) 1 else 0

/**
 * One weather event: one kind over a run of consecutive days, with every plant it affects.
 * [severity] is the strongest day's alert type.
 */
data class AlertEvent(
    val kind: AlertKind,
    val start: LocalDate,
    val end: LocalDate,
    val severity: AlertType,
    /** Sorted by name. */
    val plants: List<PlantEntity>,
)

/**
 * Groups the per-plant, per-day alerts into events. Days before [today] are ignored. Plants in different
 * places share an event: the card names plants, not places.
 */
fun alertEvents(statuses: List<PlantStatus>, today: LocalDate): List<AlertEvent> {
    class Day(var severity: AlertType, val plants: MutableMap<Long, PlantEntity>)
    val byKind = HashMap<AlertKind, HashMap<LocalDate, Day>>()
    for (s in statuses) for (a in s.alerts) {
        if (a.date < today) continue
        val day = byKind.getOrPut(a.type.kind()) { HashMap() }.getOrPut(a.date) { Day(a.type, LinkedHashMap()) }
        if (a.type.rank() > day.severity.rank()) day.severity = a.type
        day.plants[s.plant.id] = s.plant
    }
    val out = ArrayList<AlertEvent>()
    for ((kind, days) in byKind) {
        var run = ArrayList<LocalDate>()
        fun close() {
            if (run.isEmpty()) return
            val plants = run.flatMap { days.getValue(it).plants.values }.associateBy { it.id }.values.sortedWith(compareBy({ it.name }, { it.id }))
            out += AlertEvent(kind, run.first(), run.last(), run.map { days.getValue(it).severity }.maxBy { it.rank() }, plants)
            run = ArrayList()
        }
        for (date in days.keys.sorted()) {
            if (run.isNotEmpty() && run.last().plus(DatePeriod(days = 1)) != date) close()
            run += date
        }
        close()
    }
    return out.sortedWith(compareBy({ it.start }, { it.kind }))
}

/**
 * A Done or Dismiss the person gave an event, as stored. [dismissedOn] is null for Done.
 * Dates are ISO strings, as everywhere else in storage.
 */
@Serializable
data class AlertAck(
    val kind: AlertKind,
    val start: String,
    val end: String,
    val severity: AlertType,
    val plantIds: Set<Long>,
    val dismissedOn: String? = null,
) {
    fun overlaps(e: AlertEvent) = kind == e.kind && LocalDate.parse(start) <= e.end && e.start <= LocalDate.parse(end)

    companion object {
        fun of(e: AlertEvent, dismissedOn: LocalDate? = null) =
            AlertAck(e.kind, e.start.toString(), e.end.toString(), e.severity, e.plants.map { it.id }.toSet(), dismissedOn?.toString())
    }
}

/**
 * Applies the stored acks to today's events. An ack matches an event of the same kind whose range overlaps
 * its own, so an event keeps its identity when a forecast refresh shifts or extends its range.
 *
 * Done hides the event until it gets more severe or a plant not in it at Done time joins it; a matched Done
 * takes the event's later end, so an extended event stays hidden until its new end. Dismiss hides the event
 * on the day it was given, unless it gets more severe. Returns the visible events and the acks to keep: those whose range has ended and
 * dismissals from earlier days are dropped.
 */
fun applyAcks(events: List<AlertEvent>, acks: List<AlertAck>, today: LocalDate): Pair<List<AlertEvent>, List<AlertAck>> {
    val kept = acks.filter { LocalDate.parse(it.end) >= today && (it.dismissedOn == null || LocalDate.parse(it.dismissedOn) == today) }.toMutableList()
    val visible = events.filter { e ->
        // A frost warning that turned strong after "not now" is the dangerous case: it must not stay hidden.
        val dismissed = kept.any { it.dismissedOn != null && it.overlaps(e) && e.severity.rank() <= it.severity.rank() }
        val i = kept.indexOfFirst { it.dismissedOn == null && it.overlaps(e) && e.severity.rank() <= it.severity.rank() && it.plantIds.containsAll(e.plants.map { p -> p.id }) }
        if (i >= 0 && e.end > LocalDate.parse(kept[i].end)) kept[i] = kept[i].copy(end = e.end.toString())
        !dismissed && i < 0
    }
    return visible to kept
}

/** The acks after Done or Dismiss on [e]: a new ack replaces any earlier one of the same type for the same event. */
fun acked(acks: List<AlertAck>, e: AlertEvent, dismissedOn: LocalDate?): List<AlertAck> =
    acks.filterNot { (it.dismissedOn == null) == (dismissedOn == null) && it.overlaps(e) } + AlertAck.of(e, dismissedOn)
