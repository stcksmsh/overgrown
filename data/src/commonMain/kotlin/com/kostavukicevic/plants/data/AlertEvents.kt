// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import com.kostavukicevic.plants.engine.AlertType
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What an alert card is about. Root freeze, cold and frost are one kind; the event carries the severity. */
enum class AlertKind { COLD, HEAT, HEAVY_RAIN, WIND, WATERLOGGING }

fun AlertType.kind(): AlertKind = when (this) {
    AlertType.ROOT_FREEZE_CAUTION, AlertType.ROOT_FREEZE_STRONG, AlertType.COLD, AlertType.FROST -> AlertKind.COLD
    AlertType.HEAT -> AlertKind.HEAT
    AlertType.HEAVY_RAIN -> AlertKind.HEAVY_RAIN
    AlertType.WIND -> AlertKind.WIND
    AlertType.WATERLOGGING -> AlertKind.WATERLOGGING
}

/** Only cold has two levels: caution (root freeze caution, cold) and strong (root freeze strong, frost). */
private fun AlertType.rank() = if (this == AlertType.ROOT_FREEZE_STRONG || this == AlertType.FROST) 1 else 0

/**
 * Picks the event's severity among its plants' alerts. At the same level, cold and frost win over root freeze:
 * their advice (bring the plant in or cover it) also protects a pot's roots, root-freeze advice does not protect a
 * tender plant. Acks and notifications compare [rank] only.
 */
private fun AlertType.precedence() = rank() * 2 + if (this == AlertType.COLD || this == AlertType.FROST) 1 else 0

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
) {
    /**
     * Names this version of the event within one day, for the screen and the scheduled work. It changes with
     * severity and plants, and also as the start moves to today, so "notify once" uses [alreadyPosted] instead.
     */
    val key: String get() = "event-$kind-$start-$severity-${plants.map { it.id }.sorted().joinToString(",")}"
}

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
        if (a.type.precedence() > day.severity.precedence()) day.severity = a.type
        day.plants[s.plant.id] = s.plant
    }
    val out = ArrayList<AlertEvent>()
    for ((kind, days) in byKind) {
        var run = ArrayList<LocalDate>()
        fun close() {
            if (run.isEmpty()) return
            val plants = run.flatMap { days.getValue(it).plants.values }.associateBy { it.id }.values.sortedWith(compareBy({ it.name }, { it.id }))
            out += AlertEvent(kind, run.first(), run.last(), run.map { days.getValue(it).severity }.maxBy { it.precedence() }, plants)
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

/**
 * Whether the notifications already posted ([posted], stored as acks without a dismissal) told the person
 * about [e]. Matched by overlap, as the acks are, so the event keeps its identity while its start moves to today
 * or a refresh shifts it; it notifies again only once it is more severe or reaches a plant none of them named.
 */
fun alreadyPosted(posted: List<AlertAck>, e: AlertEvent): Boolean {
    val same = posted.filter { it.overlaps(e) }
    return same.isNotEmpty() && same.maxOf { it.severity.rank() } >= e.severity.rank() &&
        same.flatMap { it.plantIds }.containsAll(e.plants.map { it.id })
}

private val ackListSerializer = ListSerializer(AlertAck.serializer())

fun encodeAckList(acks: List<AlertAck>): String = Json.encodeToString(ackListSerializer, acks)

/** An unreadable value reads as none. */
fun decodeAckList(text: String?): List<AlertAck> =
    text?.let { runCatching { Json.decodeFromString(ackListSerializer, it) }.getOrNull() } ?: emptyList()

private val legacyKey = Regex("""^(\d+)-([A-Z_]+)-(\d{4}-\d{2}-\d{2})$""")

/**
 * A per-plant alert posted before alerts were grouped into events ("<plant id>-<type>-<date>"), as a posted
 * record, so the first run after the update does not notify again. Null for any other key.
 */
fun legacyPosted(key: String): AlertAck? {
    val (id, type, date) = legacyKey.matchEntire(key)?.destructured ?: return null
    val t = AlertType.entries.find { it.name == type } ?: return null
    return AlertAck(t.kind(), date, date, t, setOf(id.toLong()))
}
