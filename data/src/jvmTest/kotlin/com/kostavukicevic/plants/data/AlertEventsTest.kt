// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kostavukicevic.plants.engine.Alert
import com.kostavukicevic.plants.engine.AlertType
import com.kostavukicevic.plants.engine.AlertType.COLD
import com.kostavukicevic.plants.engine.AlertType.FROST
import com.kostavukicevic.plants.engine.AlertType.HEAT
import com.kostavukicevic.plants.engine.AlertType.ROOT_FREEZE_CAUTION
import com.kostavukicevic.plants.engine.AlertType.ROOT_FREEZE_STRONG
import com.kostavukicevic.plants.engine.AlertType.WIND
import com.kostavukicevic.plants.weather.HttpResponse
import com.kostavukicevic.plants.weather.HttpTransport
import com.kostavukicevic.plants.weather.InMemoryResponseStore
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.OpenMeteo
import com.kostavukicevic.plants.weather.WeatherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class AlertEventsTest {

    // Monday.
    private val mon = LocalDate(2026, 11, 23)
    private fun day(n: Int) = mon.plus(DatePeriod(days = n))
    private val tue = day(1)
    private val wed = day(2)
    private val thu = day(3)

    private val place = PlaceEntity(id = 1, name = "Balcony", outdoor = true, latitude = 44.8167, longitude = 20.4589)
    private val base = Planner.status(
        PlantEntity(id = 1, name = "Boris", placeId = 1, canopyWidthCm = 30.0, potVolumeL = 10.0, acquiredOn = mon.toString()),
        place, emptyList(), emptyList(), emptyList(), null, mon,
    )

    private fun status(id: Long, name: String, vararg alerts: Pair<AlertType, LocalDate>, placeId: Long = 1) =
        base.copy(plant = base.plant.copy(id = id, name = name, placeId = placeId), alerts = alerts.map { Alert(it.first, it.second) })

    private fun boris(vararg a: Pair<AlertType, LocalDate>) = status(1, "Boris", *a)
    private fun fern(vararg a: Pair<AlertType, LocalDate>) = status(2, "Fern", *a)

    private fun List<AlertEvent>.summary() = map { "${it.kind} ${it.start}..${it.end} ${it.severity} ${it.plants.map { p -> p.name }}" }

    // ---- Grouping ----------------------------------------------------------------------

    @Test
    fun consecutiveDaysOfOneKindAreOneEventAndCautionAndStrongAreBothCold() {
        val events = alertEvents(listOf(boris(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_STRONG to tue, ROOT_FREEZE_CAUTION to wed)), mon)
        assertEquals(listOf("COLD $mon..$wed ROOT_FREEZE_STRONG [Boris]"), events.summary())
    }

    @Test
    fun aGapDayBreaksTheRun() {
        val events = alertEvents(listOf(boris(HEAT to mon, HEAT to tue, HEAT to thu)), mon)
        assertEquals(listOf("HEAT $mon..$tue HEAT [Boris]", "HEAT $thu..$thu HEAT [Boris]"), events.summary())
    }

    @Test
    fun overlappingPlantsShareOneEventSpanningTheUnion() {
        val events = alertEvents(listOf(boris(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue), fern(ROOT_FREEZE_CAUTION to tue, ROOT_FREEZE_CAUTION to wed)), mon)
        assertEquals(listOf("COLD $mon..$wed ROOT_FREEZE_CAUTION [Boris, Fern]"), events.summary())
    }

    @Test
    fun plantsInDifferentPlacesShareOneEvent() {
        val other = status(3, "Agave", WIND to tue, placeId = 2)
        val events = alertEvents(listOf(boris(WIND to mon, WIND to tue), other), mon)
        assertEquals(listOf("WIND $mon..$tue WIND [Agave, Boris]"), events.summary())
    }

    @Test
    fun keyChangesWithSeverityAndPlantsButNotWithTheEnd() {
        fun key(vararg s: PlantStatus) = alertEvents(s.toList(), mon).single().key
        val caution = key(boris(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue))
        assertEquals(caution, key(boris(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue, ROOT_FREEZE_CAUTION to wed)))
        assertTrue(caution != key(boris(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_STRONG to tue)))
        assertTrue(caution != key(boris(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue), fern(ROOT_FREEZE_CAUTION to tue)))
    }

    @Test
    fun aPostedEventStaysPostedAsItsStartMovesToTodayUntilItWorsensOrReachesAnotherPlant() {
        val frost = arrayOf(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue, ROOT_FREEZE_CAUTION to wed)
        // Posted on Sunday for Monday to Wednesday.
        val posted = listOf(AlertAck.of(alertEvents(listOf(boris(*frost)), mon.plus(DatePeriod(days = -1))).single()))
        for (today in listOf(mon, tue, wed)) {
            val e = alertEvents(listOf(boris(*frost)), today).single()
            assertEquals(today, e.start)
            assertTrue(alreadyPosted(posted, e), "seen on $today")
        }
        assertTrue(!alreadyPosted(posted, alertEvents(listOf(boris(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_STRONG to tue)), tue).single()))
        assertTrue(!alreadyPosted(posted, alertEvents(listOf(boris(*frost), fern(ROOT_FREEZE_CAUTION to wed)), tue).single()))
        assertTrue(!alreadyPosted(posted, alertEvents(listOf(boris(HEAT to tue)), tue).single()))
        assertTrue(!alreadyPosted(emptyList(), alertEvents(listOf(boris(*frost)), mon).single()))
    }

    @Test
    fun postedRecordsSurviveStorageAndOldPerPlantKeysReadAsPosted() {
        val acks = listOf(AlertAck.of(alertEvents(listOf(boris(HEAT to mon)), mon).single()))
        assertEquals(acks, decodeAckList(encodeAckList(acks)))
        assertEquals(emptyList(), decodeAckList("not json"))
        assertEquals(emptyList(), decodeAckList(null))

        val old = legacyPosted("1-ROOT_FREEZE_CAUTION-$tue")!!
        assertEquals(AlertAck(AlertKind.COLD, tue.toString(), tue.toString(), ROOT_FREEZE_CAUTION, setOf(1L)), old)
        assertTrue(alreadyPosted(listOf(old), alertEvents(listOf(boris(ROOT_FREEZE_CAUTION to tue)), mon).single()))
        // Two plants each told on their own before the update are told together now.
        val both = listOf(old, legacyPosted("2-ROOT_FREEZE_CAUTION-$tue")!!)
        assertTrue(alreadyPosted(both, alertEvents(listOf(boris(ROOT_FREEZE_CAUTION to tue), fern(ROOT_FREEZE_CAUTION to tue)), mon).single()))
        assertEquals(null, legacyPosted("reminders-1-$tue"))
        assertEquals(null, legacyPosted("1-NOT_A_TYPE-$tue"))
    }

    @Test
    fun differentKindsAreSeparateEvents() {
        val events = alertEvents(listOf(boris(ROOT_FREEZE_CAUTION to mon, WIND to mon)), mon)
        assertEquals(listOf("COLD $mon..$mon ROOT_FREEZE_CAUTION [Boris]", "WIND $mon..$mon WIND [Boris]"), events.summary())
    }

    @Test
    fun coldAndFrostShareTheColdCardWithRootFreezeAndWinAtTheSameLevel() {
        // Basil (Fern here) is tender; Boris's pot gets root freeze. One card per day, and its advice is the tender one.
        val strong = alertEvents(listOf(boris(ROOT_FREEZE_STRONG to mon), fern(FROST to mon)), mon)
        assertEquals(listOf("COLD $mon..$mon FROST [Boris, Fern]"), strong.summary())
        val caution = alertEvents(listOf(fern(ROOT_FREEZE_CAUTION to mon), boris(COLD to mon)), mon)
        assertEquals(listOf("COLD $mon..$mon COLD [Boris, Fern]"), caution.summary())
        // A stronger level still wins over the tender caution.
        val mixed = alertEvents(listOf(boris(COLD to mon, ROOT_FREEZE_STRONG to tue)), mon)
        assertEquals(listOf("COLD $mon..$tue ROOT_FREEZE_STRONG [Boris]"), mixed.summary())
    }

    @Test
    fun doneOnColdComesBackWhenItTurnsToFrost() {
        val event = cold(COLD to mon, COLD to tue)
        val worse = cold(COLD to mon, FROST to tue)
        assertEquals(listOf(worse), applyAcks(listOf(worse), acked(emptyList(), event, null), mon).first)
        assertTrue(!alreadyPosted(listOf(AlertAck.of(event)), worse))
    }

    @Test
    fun noAlertsNoEvents() {
        assertEquals(emptyList(), alertEvents(listOf(boris(), fern()), mon))
    }

    // ---- Done and Dismiss, pure ---------------------------------------------------------

    private fun cold(vararg days: Pair<AlertType, LocalDate>, plants: List<PlantStatus>.() -> List<PlantStatus> = { this }) =
        alertEvents(listOf(boris(*days)).plants(), mon).single()

    @Test
    fun doneStaysHiddenWhenARefreshExtendsTheEvent() {
        val event = cold(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue, ROOT_FREEZE_CAUTION to wed)
        val acks = acked(emptyList(), event, dismissedOn = null)
        val longer = cold(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue, ROOT_FREEZE_CAUTION to wed, ROOT_FREEZE_CAUTION to thu)
        val (visible, kept) = applyAcks(listOf(longer), acks, mon)
        assertEquals(emptyList(), visible)
        // On Thursday the original Mon-Wed range has ended; the stored range followed the event, so it is still hidden.
        assertEquals(thu.toString(), kept.single().end)
        val onThu = cold(ROOT_FREEZE_CAUTION to thu)
        assertEquals(emptyList(), applyAcks(listOf(onThu), kept, thu).first)
    }

    @Test
    fun doneStaysHiddenWhenTheEventStartsLater() {
        val event = cold(ROOT_FREEZE_CAUTION to tue, ROOT_FREEZE_CAUTION to wed)
        val shifted = cold(ROOT_FREEZE_CAUTION to wed, ROOT_FREEZE_CAUTION to thu)
        assertEquals(emptyList(), applyAcks(listOf(shifted), acked(emptyList(), event, null), mon).first)
    }

    @Test
    fun doneComesBackWhenColdGetsStrong() {
        val event = cold(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue)
        val worse = cold(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_STRONG to tue)
        assertEquals(listOf(worse), applyAcks(listOf(worse), acked(emptyList(), event, null), mon).first)
    }

    @Test
    fun doneComesBackWhenANewPlantJoins() {
        val event = cold(ROOT_FREEZE_CAUTION to mon)
        val wider = cold(ROOT_FREEZE_CAUTION to mon) { this + fern(ROOT_FREEZE_CAUTION to mon) }
        assertEquals(listOf(wider), applyAcks(listOf(wider), acked(emptyList(), event, null), mon).first)
    }

    @Test
    fun doneDoesNotHideAnotherKindOrALaterEvent() {
        val event = cold(ROOT_FREEZE_CAUTION to mon)
        val acks = acked(emptyList(), event, null)
        val later = cold(ROOT_FREEZE_CAUTION to wed)
        val heat = alertEvents(listOf(boris(HEAT to mon)), mon).single()
        assertEquals(listOf(later, heat), applyAcks(listOf(later, heat), acks, mon).first)
    }

    @Test
    fun dismissHidesOnlyToday() {
        val event = cold(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue)
        val (today, kept) = applyAcks(listOf(event), acked(emptyList(), event, dismissedOn = mon), mon)
        assertEquals(emptyList(), today)
        val stillForecast = cold(ROOT_FREEZE_CAUTION to tue)
        val (tomorrow, keptTomorrow) = applyAcks(listOf(stillForecast), kept, tue)
        assertEquals(listOf(stillForecast), tomorrow)
        assertEquals(emptyList(), keptTomorrow)
    }

    @Test
    fun dismissComesBackWhenColdGetsStrongTheSameDay() {
        val event = cold(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue)
        val acks = acked(emptyList(), event, dismissedOn = mon)
        assertEquals(emptyList(), applyAcks(listOf(event), acks, mon).first)
        val worse = cold(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_STRONG to tue)
        assertEquals(listOf(worse), applyAcks(listOf(worse), acks, mon).first)
    }

    @Test
    fun ackedReplacesTheEarlierDoneForTheSameEvent() {
        val event = cold(ROOT_FREEZE_CAUTION to mon)
        val worse = cold(ROOT_FREEZE_STRONG to mon)
        val acks = acked(acked(emptyList(), event, null), worse, null)
        assertEquals(listOf(ROOT_FREEZE_STRONG), acks.map { it.severity })
    }

    // ---- Through Garden and DataStore ----------------------------------------------------

    private val dir = Files.createTempDirectory("alert-events-test")
    private val dbs = ArrayList<GardenDb>()
    private val scopes = ArrayList<Job>()
    private var clock = mon.atStartOfDayIn(TimeZone.UTC) + 8.hours

    @AfterTest fun close() { dbs.forEach { it.close() }; scopes.forEach { it.cancel() } }

    private val noNetwork = HttpTransport { _, _ -> HttpResponse(503, emptyMap(), "") }

    private fun settings(job: Job) = Settings(
        PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(Dispatchers.IO + job)) { dir.resolve("prefs.preferences_pb").toOkioPath() },
    )

    private fun garden(settings: Settings): Garden {
        val db = Room.inMemoryDatabaseBuilder<GardenDb>().setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Default).build().also { dbs += it }
        val om = OpenMeteo(noNetwork, InMemoryResponseStore(), { clock })
        val met = MetNorway(noNetwork, InMemoryResponseStore(), { clock }, "test", TimeZone.UTC)
        return Garden(db, settings, WeatherService(om, met, settings), om, { null }, { clock }, { TimeZone.UTC }, dir.resolve("photos").toFile())
    }

    private fun advance(days: Int) { clock += days.days }

    @Test
    fun gardenHidesDoneForTheRangeAndDismissForTheDay() = runTest {
        val g = garden(settings(Job().also { scopes += it }))
        val cold = listOf(boris(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue))
        val heat = listOf(fern(HEAT to mon, HEAT to tue))
        val both = cold + heat
        assertEquals(2, g.alertEvents(both).size)

        g.done(g.alertEvents(cold).single())
        g.dismiss(g.alertEvents(heat).single())
        assertEquals(emptyList(), g.alertEvents(both))

        advance(1)
        val tomorrow = listOf(boris(ROOT_FREEZE_CAUTION to tue), fern(HEAT to tue))
        assertEquals(listOf("HEAT $tue..$tue HEAT [Fern]"), g.alertEvents(tomorrow).summary())
        // Worse than at Done time: back.
        assertEquals(1, g.alertEvents(listOf(boris(ROOT_FREEZE_STRONG to tue))).size)
        // A plant that was not in it at Done time: back.
        assertEquals(1, g.alertEvents(listOf(boris(ROOT_FREEZE_CAUTION to tue), fern(ROOT_FREEZE_CAUTION to tue))).size)
    }

    @Test
    fun doneAndDismissSurviveARestartAndExpiredEntriesAreRemoved() = runTest {
        val first = Job()
        val a = garden(settings(first))
        val cold = listOf(boris(ROOT_FREEZE_CAUTION to mon, ROOT_FREEZE_CAUTION to tue))
        val heat = listOf(fern(HEAT to mon))
        a.done(a.alertEvents(cold).single())
        a.dismiss(a.alertEvents(heat).single())
        first.cancelAndJoin()

        val second = Job().also { scopes += it }
        val s = settings(second)
        val b = garden(s)
        assertEquals(emptyList(), b.alertEvents(cold + heat))
        assertEquals(2, s.alertAcks().size)

        // Wednesday: the cold event ended Tuesday and the dismissal was for Monday. Both entries go.
        advance(2)
        assertEquals(emptyList(), b.alertEvents(emptyList()))
        assertEquals(emptyList(), s.alertAcks())
    }

    @Test
    fun noAlertsStoresNothing() = runTest {
        val s = settings(Job().also { scopes += it })
        val g = garden(s)
        assertEquals(emptyList(), g.alertEvents(listOf(boris())))
        assertTrue(s.alertAcks().isEmpty())
        assertTrue(!Files.exists(dir.resolve("prefs.preferences_pb")), "nothing was written")
    }
}
