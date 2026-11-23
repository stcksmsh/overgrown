// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.caredb.Context
import com.kostavukicevic.plants.caredb.toProfile
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.Alert
import com.kostavukicevic.plants.engine.CareProfile
import com.kostavukicevic.plants.engine.Engine
import com.kostavukicevic.plants.engine.EngineState
import com.kostavukicevic.plants.engine.Event
import com.kostavukicevic.plants.engine.LightClass
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.engine.Place
import com.kostavukicevic.plants.engine.Plant
import com.kostavukicevic.plants.engine.Pot
import com.kostavukicevic.plants.engine.PotMaterial
import com.kostavukicevic.plants.engine.PotShape
import com.kostavukicevic.plants.engine.RainExposure
import com.kostavukicevic.plants.engine.WaterStrategy
import com.kostavukicevic.plants.engine.WeatherDay
import com.kostavukicevic.plants.engine.WeatherQuality
import com.kostavukicevic.plants.engine.WindExposure
import com.kostavukicevic.plants.weather.Climatology
import com.kostavukicevic.plants.weather.DailyWeather
import com.kostavukicevic.plants.weather.Series
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.plus

/** Everything the app shows about one plant today. */
data class PlantStatus(
    val plant: PlantEntity,
    val place: PlaceEntity,
    val engine: Plant,
    val state: EngineState,
    /** What to do today; [Action.None] while snoozed. */
    val today: Action,
    val alerts: List<Alert>,
    /** The first day, from today, the engine expects to prompt, if within the forecast. */
    val nextPrompt: Pair<LocalDate, Action>?,
    /** Prompts return on this day. */
    val snoozedUntil: LocalDate?,
    /** Days of the replay filled from climatology, for the "data is stale" hint. */
    val gapDays: Int,
    val careSource: CareSource,
)

enum class CareSource { SPECIES, ARCHETYPE, GENERIC }

/**
 * Turns stored facts into the engine's view of a plant. Nothing derived is stored: the engine
 * replays from the plant's first day over the stored weather and events, so a change to the
 * engine or an import gives consistent results. The engine is deterministic, so replay is exact.
 */
object Planner {

    const val FORECAST_DAYS = 10

    fun enginePlace(p: PlaceEntity): Place = if (p.outdoor) {
        Place.Outdoor(p.latitude, RainExposure.valueOf(p.rainExposure), WindExposure.valueOf(p.windExposure))
    } else {
        Place.Indoor(p.latitude, p.lightClass?.let(LightClass::valueOf) ?: LightClass.MEDIUM, p.lux, p.dryAir)
    }

    /** Species data for the plant's context, else its archetype's defaults, else engine defaults. */
    fun profile(plant: PlantEntity, place: PlaceEntity, db: CareDb?): Pair<CareProfile, CareSource> {
        val context = if (place.outdoor) Context.OUTDOOR_CONTAINER else Context.INDOOR
        plant.speciesSlug?.let { slug -> db?.species(slug)?.let { return it.groupsFor(context).toProfile() to CareSource.SPECIES } }
        plant.archetypeId?.let { id -> db?.archetypes?.get(id)?.let { return it.defaults.toProfile() to CareSource.ARCHETYPE } }
        return CareProfile(WaterStrategy.DRY_TOP_PART) to CareSource.GENERIC
    }

    fun enginePlant(plant: PlantEntity, place: PlaceEntity, profile: CareProfile) = Plant(
        profile = profile,
        canopyWidthCm = plant.canopyWidthCm,
        pot = Pot(
            substrateVolumeL = plant.potVolumeL,
            heightCm = plant.potHeightCm,
            shape = PotShape.valueOf(plant.potShape),
            material = PotMaterial.valueOf(plant.potMaterial),
            drainageHoles = plant.drainageHoles,
            inNurseryPot = plant.inNurseryPot,
            rootBound = plant.rootBound,
        ),
        place = enginePlace(place),
    )

    fun event(e: EventEntity): Event? {
        val date = LocalDate.parse(e.date)
        return when (e.type) {
            "WATERED" -> Event.Watered(date, e.ranOutOfBottom ?: false, e.moisture?.let(Moisture::valueOf))
            "CHECK_IN" -> Event.CheckIn(date, Moisture.valueOf(requireNotNull(e.moisture) { "check-in without an answer" }))
            "MOVED" -> Event.Moved(date)
            "REPOTTED" -> Event.Repotted(date)
            else -> null // SNOOZED changes no state
        }
    }

    fun daily(w: WeatherDayEntity) = DailyWeather(
        LocalDate.parse(w.date), w.tMinC, w.tMaxC, w.precipitationMm, w.meanWindMs, w.meanRelativeHumidityPct, w.et0Mm,
    )

    fun climatology(rows: List<ClimatologyEntity>): Climatology? = rows.takeIf { it.isNotEmpty() }?.let {
        Climatology(it.associate { r ->
            Month(r.month) to DailyWeather(LocalDate(2000, r.month, 1), r.tMinC, r.tMaxC, r.precipitationMm, r.meanWindMs, r.meanRelativeHumidityPct, r.et0Mm)
        })
    }

    fun status(
        plant: PlantEntity,
        place: PlaceEntity,
        events: List<EventEntity>,
        weather: List<WeatherDayEntity>,
        climatology: List<ClimatologyEntity>,
        db: CareDb?,
        today: LocalDate,
    ): PlantStatus {
        val (profile, source) = profile(plant, place, db)
        val enginePlant = enginePlant(plant, place, profile)
        val start = minOf(LocalDate.parse(plant.acquiredOn), today)
        val series = Series.build(
            weather.associate { LocalDate.parse(it.date) to daily(it) },
            from = start, to = today.plus(DatePeriod(days = FORECAST_DAYS - 1)), today = today,
            climatology = climatology(climatology),
        )
        val byDate = series.associateBy { it.date }
        val eventsByDate = events.groupBy { LocalDate.parse(it.date) }

        var state = Engine.initial(enginePlant)
        var d = start
        while (d < today) {
            eventsByDate[d]?.forEach { e -> event(e)?.let { state = Engine.apply(enginePlant, state, it) } }
            byDate[d]?.let { state = Engine.advance(enginePlant, state, it) }
            d = d.plus(DatePeriod(days = 1))
        }
        eventsByDate[today]?.forEach { e -> event(e)?.let { state = Engine.apply(enginePlant, state, it) } }

        val upcoming: List<WeatherDay> = series.filter { it.date >= today }
        // A snooze holds prompts until the day it names (the day they return); a later watering ends it.
        val lastSnooze = events.lastOrNull { it.type == "SNOOZED" }
        val wateredSince = lastSnooze != null && events.any { it.type == "WATERED" && it.id > lastSnooze.id }
        val snoozedUntil = lastSnooze?.takeIf { !wateredSince }?.snoozedUntil?.let(LocalDate::parse)?.takeIf { it > today }
        val rec = if (upcoming.isNotEmpty()) Engine.recommend(enginePlant, state, upcoming) else null
        val todayAction = when {
            rec == null -> Action.None
            snoozedUntil != null && rec.action.isPrompt() -> Action.None
            else -> rec.action
        }

        var next: Pair<LocalDate, Action>? = null
        var s = state
        for (i in upcoming.indices) {
            val day = upcoming[i]
            val a = Engine.recommend(enginePlant, s, upcoming.drop(i)).action
            if (a.isPrompt() && (snoozedUntil == null || day.date >= snoozedUntil)) { next = day.date to a; break }
            s = Engine.advance(enginePlant, s, day)
        }

        return PlantStatus(
            plant = plant, place = place, engine = enginePlant, state = state,
            today = todayAction, alerts = rec?.alerts ?: emptyList(), nextPrompt = next, snoozedUntil = snoozedUntil,
            gapDays = series.count { it.date < today && it.quality == WeatherQuality.CLIMATOLOGY_FILL },
            careSource = source,
        )
    }
}

fun Action.isPrompt() = this is Action.WaterNow || this is Action.CheckSoil || this == Action.RepotOrWaterDaily
