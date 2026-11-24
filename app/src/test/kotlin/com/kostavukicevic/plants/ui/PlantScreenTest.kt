// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
import androidx.work.WorkManager
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.UrlTransport
import com.kostavukicevic.plants.data.DbResponseStore
import com.kostavukicevic.plants.data.EventEntity
import com.kostavukicevic.plants.data.Garden
import com.kostavukicevic.plants.data.GardenDb
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.graph
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.WeatherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
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

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** Shows the screen and waits for the strip, which comes from its own query and moves everything below it. */
    private fun start() {
        compose.setContent { OvergrownTheme { PlantScreen(plantId, onBack = {}, onEdit = {}) } }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription(s(R.string.plant_next_days), substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitFor(text: String, description: Boolean = false) =
        compose.waitUntil(15_000) { compose.onAllNodes(if (description) hasContentDescription(text) else hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun log(date: String, type: String, moisture: String? = null) =
        runBlocking { db.dao().insertEvent(EventEntity(plantId = plantId, date = date, type = type, moisture = moisture)) }

    private fun stored() = runBlocking { g.garden.events(plantId).first() }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

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

    companion object {
        const val SNACKBAR_GONE = 15_000L
    }
}

/** Tuesday 24 Nov 2026, 10:00 UTC: every date on the plant screen is fixed. */
internal val PLANT_TEST_NOW: Instant = Instant.parse("2026-11-24T10:00:00Z")

/**
 * As useTestDatabase in NavigationTest, with a given clock (fixed by default), UTC, and the bundled care database so
 * plant types have names. Returns the database too, for logging past events and weather directly.
 */
internal fun fixedClockGraph(now: () -> Instant = { PLANT_TEST_NOW }): Pair<Graph, GardenDb> {
    val app = ApplicationProvider.getApplicationContext<Application>()
    if (!WorkManager.isInitialized()) WorkManager.initialize(app, Configuration.Builder().build())
    val g = app.graph
    val db = Room.inMemoryDatabaseBuilder(app, GardenDb::class.java)
        .setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    val metNorway = MetNorway(UrlTransport, DbResponseStore(db.dao()), now, g.versionName, TimeZone.UTC)
    val garden = Garden(
        db, g.settings, WeatherService(g.openMeteo, metNorway, g.settings), g.openMeteo, { g.careDb }, now,
        { TimeZone.UTC }, File(app.filesDir, "photos"),
    )
    Graph::class.java.getDeclaredField("garden").apply { isAccessible = true }.set(g, garden)
    return g to db
}
