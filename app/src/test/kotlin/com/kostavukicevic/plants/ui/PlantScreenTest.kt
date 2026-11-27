// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.UrlTransport
import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.data.DbResponseStore
import com.kostavukicevic.plants.data.EventEntity
import com.kostavukicevic.plants.data.Garden
import com.kostavukicevic.plants.data.GardenDb
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.OutlookDay
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.data.WeatherDayEntity
import com.kostavukicevic.plants.graph
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.WeatherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.time.Instant

/** The plant screen against a real database: answers, history weeks, editing and deleting entries. */
@RunWith(RobolectricTestRunner::class)
class PlantScreenTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private lateinit var g: Graph
    private lateinit var db: GardenDb
    private var plantId = 0L
    private var backed = false

    @Before
    fun setUp() {
        val (graph, database) = fixedClockGraph()
        g = graph
        db = database
        plantId = runBlocking {
            val placeId = g.garden.savePlace(PlaceEntity(name = "Hall", outdoor = false, latitude = 44.8, longitude = 20.5))
            g.garden.savePlant(PlantEntity(name = "Fern", placeId = placeId, canopyWidthCm = 20.0, potVolumeL = 2.0, acquiredOn = "2026-11-01"))
        }
    }

    @Test
    fun theStripHeaderGroupsFrostDaysIntoRuns() {
        // Fri 27 to Sun 29 Nov, then Tue 1 Dec.
        val days = (24..33).map { i ->
            val date = LocalDate(2026, 11, 24).plus(DatePeriod(days = i - 24))
            OutlookDay(date, 0.5, frost = i in 27..29 || i == 31, rain = false, heat = false)
        }
        compose.setContent { OvergrownTheme { DayStrip(days, next = null) } }
        compose.onNodeWithText(s(R.string.plant_frost_days, "Fri-Sun, Tue"), useUnmergedTree = true).assertExists()
    }

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** Shows the screen and waits for the strip, which comes from its own query and moves everything below it. */
    private fun start() {
        compose.setContent { OvergrownTheme { PlantScreen(plantId, onBack = { backed = true }, onEdit = {}) } }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription(s(R.string.plant_next_days), substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitFor(text: String, description: Boolean = false) =
        compose.waitUntil(15_000) { compose.onAllNodes(if (description) hasContentDescription(text) else hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    /** Waits for the screen's provider lookup to finish and returns the providers it found. */
    private fun waitForProviders(): List<String> {
        compose.waitUntil(15_000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(WeatherProviders)).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(SemanticsMatcher.keyIsDefined(WeatherProviders)).fetchSemanticsNode().config[WeatherProviders]
    }

    private fun log(date: String, type: String, moisture: String? = null) =
        runBlocking { db.dao().insertEvent(EventEntity(plantId = plantId, date = date, type = type, moisture = moisture)) }

    private fun stored() = runBlocking { g.garden.events(plantId).first() }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /** Moves the test plant to a new outdoor place, with a forecast row from [provider] unless that is null. */
    private fun moveOutdoors(provider: String?) = runBlocking {
        val balcony = g.garden.savePlace(PlaceEntity(name = "Balcony", outdoor = true, latitude = 44.8, longitude = 20.5))
        if (provider != null) {
            db.dao().upsertWeather(
                listOf(WeatherDayEntity(balcony, "2026-11-24", 4.0, 11.0, 0.0, provider = provider, fetchedAtEpochMs = PLANT_TEST_NOW.toEpochMilliseconds())),
            )
        }
        g.garden.savePlant(g.garden.plant(plantId)!!.copy(placeId = balcony))
    }

    @Test
    fun anOutdoorPlantWithAForecastCreditsOpenMeteoAsALink() {
        moveOutdoors("OPEN_METEO")
        start()
        waitFor(s(R.string.weather_credit_open_meteo))
        compose.onNode(hasText(s(R.string.weather_credit_open_meteo)) and hasClickAction()).assertExists()
        // TalkBack announces it as something to activate, as the links in Settings.
        compose.onNodeWithText(s(R.string.weather_credit_open_meteo)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun anotherPlacesNewerFetchDoesNotChangeThisPlantsCredit() {
        moveOutdoors("OPEN_METEO")
        runBlocking {
            val elsewhere = g.garden.savePlace(PlaceEntity(name = "Roof", outdoor = true, latitude = 59.9, longitude = 10.7))
            val later = PLANT_TEST_NOW.toEpochMilliseconds() + 60_000
            db.dao().upsertWeather(listOf(WeatherDayEntity(elsewhere, "2026-11-24", 1.0, 5.0, 0.0, provider = "MET_NORWAY", fetchedAtEpochMs = later)))
        }
        start()
        waitFor(s(R.string.weather_credit_open_meteo))
        waitForProviders()
        compose.onNodeWithText(s(R.string.weather_credit_met)).assertDoesNotExist()
    }

    @Test
    fun aForecastFromMetNorwayCreditsMetNorway() {
        moveOutdoors("MET_NORWAY")
        start()
        waitFor(s(R.string.weather_credit_met))
        compose.onNodeWithText(s(R.string.weather_credit_open_meteo)).assertDoesNotExist()
    }

    @Test
    fun anIndoorPlantShowsNoCredit() {
        start()
        // The place line proves the screen has loaded this plant where it is, so no credit is not just not yet loaded.
        waitFor("Hall")
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.weather_credit_open_meteo)).assertDoesNotExist()
    }

    @Test
    fun anOutdoorPlantWithNoForecastYetShowsNoCredit() {
        moveOutdoors(null)
        // The strip does not show with no weather stored, so wait for the place line instead.
        compose.setContent { OvergrownTheme { PlantScreen(plantId, onBack = {}, onEdit = {}) } }
        // The place line proves the screen has loaded this plant at its outdoor place.
        waitFor("Balcony")
        // The credit is looked up after the plant loads; only once that lookup is done does no credit mean none.
        assertEquals(emptyList<String>(), waitForProviders())
        compose.onNodeWithText(s(R.string.weather_credit_open_meteo)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.weather_credit_met)).assertDoesNotExist()
    }

    @Test
    fun eachAnswerButtonStoresItsAnswer() {
        start()
        waitFor(s(R.string.plant_history_empty))
        val answers = listOf(R.string.plant_answer_wet, R.string.moisture_ok, R.string.moisture_dry, R.string.moisture_bone_dry)
        answers.forEachIndexed { i, label ->
            // The button's own click action: a touch can land mid-move while the last answer's status settles.
            compose.onNodeWithText(s(label)).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(15_000) { stored().size == i + 1 }
        }
        // Newest first in the database's order; the same day, so by id.
        assertEquals(listOf("TOO_WET", "OK", "DRY", "BONE_DRY"), stored().reversed().map { it.moisture })
        assertTrue(stored().all { it.type == "CHECK_IN" && it.date == "2026-11-24" })
    }

    @Test
    fun historyIsGroupedByWeekNewestFirst() {
        log("2026-11-10", "CHECK_IN", "DRY")
        log("2026-11-24", "WATERED")
        log("2026-11-17", "MOVED")
        log("2026-11-23", "FED")
        start()
        waitFor(s(R.string.plant_week_of, "9 Nov"))

        val order = listOf(
            s(R.string.plant_week_this), "24 Nov: watered", "23 Nov: fed",
            s(R.string.plant_week_last), "17 Nov: moved",
            s(R.string.plant_week_of, "9 Nov"), s(R.string.plant_history_check_in, "10 Nov", s(R.string.moisture_dry)),
        ).map(::top)
        assertEquals(order.sorted(), order)
        compose.onNodeWithText(s(R.string.plant_history_empty)).assertDoesNotExist()
    }

    @Test
    fun editingDateAndAnswerPersists() {
        log("2026-11-24", "CHECK_IN", "OK")
        start()
        val line = s(R.string.plant_history_check_in, "24 Nov", s(R.string.moisture_ok))
        waitFor(s(R.string.edit_named, line), description = true)

        compose.onNodeWithContentDescription(s(R.string.edit_named, line)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.plant_day_before)).performClick()
        compose.onNodeWithText("23 Nov").assertExists()
        // The dialog's Bone dry, not the screen's button behind it.
        compose.onAllNodes(hasText(s(R.string.moisture_bone_dry))).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        compose.onNodeWithText(s(R.string.save)).performClick()

        compose.waitUntil(15_000) { stored().single().date == "2026-11-23" }
        assertEquals("BONE_DRY", stored().single().moisture)
        waitFor(s(R.string.plant_history_check_in, "23 Nov", s(R.string.moisture_bone_dry)))
    }

    @Test
    fun aFutureDateIsRefused() {
        log("2026-11-24", "CHECK_IN", "OK")
        start()
        val line = s(R.string.plant_history_check_in, "24 Nov", s(R.string.moisture_ok))
        waitFor(s(R.string.edit_named, line), description = true)

        compose.onNodeWithContentDescription(s(R.string.edit_named, line)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.plant_day_after)).performClick()
        compose.onNodeWithText(s(R.string.save)).performClick()

        waitFor(s(R.string.plant_entry_future))
        compose.waitForIdle()
        assertEquals("2026-11-24", stored().single().date)
    }

    @Test
    fun aDateBeforeThePlantWasAcquiredIsRefused() {
        log("2026-11-01", "FED")
        start()
        val edit = s(R.string.edit_named, "1 Nov: fed")
        waitFor(edit, description = true)

        compose.onNodeWithContentDescription(edit).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.plant_day_before)).performClick()
        compose.onNodeWithText(s(R.string.save)).performClick()

        waitFor(s(R.string.plant_entry_before_acquired, "1 Nov"))
        assertEquals("2026-11-01", stored().single().date)
    }

    @Test
    fun historyDatesHaveNoLeadingZero() {
        log("2026-11-04", "FED")
        start()
        waitFor(s(R.string.edit_named, "4 Nov: fed"), description = true)
        compose.onNodeWithText("04 Nov: fed").assertDoesNotExist()
    }

    @Test
    fun deletingThePlantAsksFirst() {
        start()
        waitFor(s(R.string.plant_history_empty))
        val question = s(R.string.plant_delete_question, "Fern")
        compose.onNodeWithText(s(R.string.delete)).performScrollTo().performClick()
        compose.onNodeWithText(question).assertExists()
        compose.onNodeWithText(s(R.string.cancel)).performClick()
        compose.onNodeWithText(question).assertDoesNotExist()
        compose.waitForIdle()
        assertNotNull(runBlocking { g.garden.plant(plantId) })

        compose.onNodeWithText(s(R.string.delete)).performScrollTo().performClick()
        compose.onNode(hasText(s(R.string.delete)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15_000) { runBlocking { g.garden.plant(plantId) == null } }
        assertTrue(backed)
    }

    @Test
    fun learningCountStopsAtEight() {
        for (day in 10..19) log("2026-11-$day", "CHECK_IN", "OK")
        start()
        waitFor(s(R.string.plant_still_learning, "Fern", 8))
    }

    @Test
    fun anAnswerWaitingOutItsUndoIsNotCounted() {
        log("2026-11-24", "CHECK_IN", "OK")
        start()
        waitFor(s(R.string.plant_still_learning, "Fern", 1))

        val line = s(R.string.plant_history_check_in, "24 Nov", s(R.string.moisture_ok))
        compose.onNodeWithContentDescription(s(R.string.delete_named, line)).performScrollTo().performClick()
        waitFor(s(R.string.plant_still_learning, "Fern", 0))
        compose.onNodeWithText(s(R.string.undo)).performClick()
        waitFor(s(R.string.plant_still_learning, "Fern", 1))
    }

    @Test
    fun deleteHidesUndoRestoresAndExpiryDeletes() {
        log("2026-11-24", "WATERED")
        start()
        val delete = s(R.string.delete_named, "24 Nov: watered")
        waitFor(delete, description = true)

        compose.onNodeWithContentDescription(delete).performScrollTo().performClick()
        compose.onNodeWithContentDescription(delete).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.plant_history_empty)).assertExists()
        compose.onNodeWithText(s(R.string.undo)).performClick()
        waitFor(delete, description = true)
        compose.mainClock.advanceTimeBy(SNACKBAR_GONE)
        compose.waitForIdle()
        assertEquals(1, stored().size)

        compose.onNodeWithContentDescription(delete).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("deleted before the snackbar went", 1, stored().size)
        compose.mainClock.advanceTimeBy(SNACKBAR_GONE)
        compose.waitUntil(15_000) { stored().isEmpty() }
        compose.onNodeWithContentDescription(delete).assertDoesNotExist()
    }

    @Test
    fun wateringThenLeavingAtOnceStillRecordsAndReschedules() {
        val onScreen = mutableStateOf(true)
        compose.setContent { OvergrownTheme { if (onScreen.value) PlantScreen(plantId, onBack = {}, onEdit = {}) } }
        waitFor(s(R.string.plant_watered))
        compose.onNodeWithText(s(R.string.plant_watered)).performScrollTo().performClick()
        waitFor(s(R.string.plant_watered_question))
        // Nothing scheduled yet, so a notification job queued from here on is reschedule()'s doing.
        val work = WorkManager.getInstance(compose.activity)
        work.cancelAllWorkByTag("notify").result.get()
        // Leaving in the frame of the tap, as Back pressed with it: the screen's scope is cancelled before its next frame.
        compose.runOnUiThread {
            compose.onNodeWithText(s(R.string.yes)).performClick()
            onScreen.value = false
        }
        compose.waitUntil(15_000) {
            stored().any { it.type == "WATERED" } &&
                work.getWorkInfosByTag("notify").get().any { it.state == WorkInfo.State.ENQUEUED }
        }
    }

    @Test
    fun weighingShowsOnlyWhenSwitchedOnInSettings() {
        start()
        waitFor(s(R.string.plant_history_empty))
        compose.onNodeWithText(s(R.string.calibration_title)).assertDoesNotExist()
        runBlocking { g.settings.setWeighing(true) }
        waitFor(s(R.string.calibration_title))
        runBlocking { g.settings.setWeighing(false) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(s(R.string.calibration_title))).fetchSemanticsNodes().isEmpty() }
    }

    companion object {
        const val SNACKBAR_GONE = 15_000L
    }
}

/** Tuesday 24 Nov 2026, 10:00 UTC: every date on the plant screen is fixed. */
internal val PLANT_TEST_NOW: Instant = Instant.parse("2026-11-24T10:00:00Z")

/**
 * As useTestDatabase in NavigationTest, with a given clock (fixed by default), UTC, and the bundled care database so
 * plant types have names, or [careDb] in its place. Returns the database too, for logging past events and weather
 * directly.
 */
internal fun fixedClockGraph(now: () -> Instant = { PLANT_TEST_NOW }, careDb: CareDb? = null): Pair<Graph, GardenDb> {
    val app = ApplicationProvider.getApplicationContext<Application>()
    if (!WorkManager.isInitialized()) WorkManager.initialize(app, Configuration.Builder().build())
    val g = app.graph
    val db = Room.inMemoryDatabaseBuilder(app, GardenDb::class.java)
        .setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    val metNorway = MetNorway(UrlTransport, DbResponseStore(db.dao()), now, g.versionName, TimeZone.UTC)
    val garden = Garden(
        db, g.settings, WeatherService(g.openMeteo, metNorway, g.settings), g.openMeteo, { careDb ?: g.careDb }, now,
        { TimeZone.UTC }, File(app.filesDir, "photos"),
    )
    Graph::class.java.getDeclaredField("garden").apply { isAccessible = true }.set(g, garden)
    return g to db
}
