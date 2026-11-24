// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.kostavukicevic.plants.App
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.UrlTransport
import com.kostavukicevic.plants.data.DbResponseStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.time.Clock

/** Bottom tabs, the top-bar menu, delete with Undo, and opening a plant from a notification, against a real database. */
@RunWith(RobolectricTestRunner::class)
class NavigationTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private lateinit var g: Graph

    @Before
    fun setUp() {
        g = useTestDatabase()
    }

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun start(openPlant: Long? = null) = compose.setContent { OvergrownTheme { App(openPlant) } }
    private fun tab(id: Int) = compose.onNode(isTab and hasText(s(id)))
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    /** A tab root shows its name twice, in the top bar and on its tab; any other screen once at most. */
    private fun assertOn(title: Int) {
        compose.waitForIdle()
        compose.onAllNodesWithText(s(title)).assertCountEquals(2)
    }

    private fun waitFor(text: String, description: Boolean = false) =
        compose.waitUntil(5_000) { compose.onAllNodes(if (description) hasContentDescription(text) else hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun openMenu(item: Int) {
        compose.onNodeWithContentDescription(s(R.string.top_menu)).performClick()
        compose.onNodeWithText(s(item)).performClick()
    }

    @Test
    fun bottomBarHasExactlyTodayAndPlants() {
        plant(g, "Boris", place(g, "Hall"))
        start()
        compose.onAllNodes(isTab).assertCountEquals(2)
        assertOn(R.string.today_title)
        tab(R.string.today_title).assertIsSelected()
        tab(R.string.plants_title).assertIsNotSelected()

        tab(R.string.plants_title).performClick()
        waitFor(s(R.string.delete_named, "Boris"), description = true)
        assertOn(R.string.plants_title)
        tab(R.string.plants_title).assertIsSelected()
        tab(R.string.today_title).assertIsNotSelected()

        tab(R.string.today_title).performClick()
        assertOn(R.string.today_title)
        compose.onNodeWithContentDescription(s(R.string.delete_named, "Boris")).assertDoesNotExist()
        tab(R.string.today_title).assertIsSelected()
    }

    @Test
    fun backFromPlantsGoesToTodayAndBackFromTodayLeaves() {
        start()
        tab(R.string.plants_title).performClick()
        assertOn(R.string.plants_title)
        back()
        assertOn(R.string.today_title)
        assertFalse(compose.activity.isFinishing)
        back()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun tabAndStackSurviveStateRestore() {
        val restore = StateRestorationTester(compose)
        restore.setContent { OvergrownTheme { App(null) } }
        tab(R.string.plants_title).performClick()
        openMenu(R.string.places_title)
        waitFor(s(R.string.places_empty))

        restore.emulateSavedInstanceStateRestore()
        waitFor(s(R.string.places_empty))
        back()
        assertOn(R.string.plants_title)
        tab(R.string.plants_title).assertIsSelected()
    }

    @Test
    fun placesAndSettingsOpenFromTheTopBarOnBothTabs() {
        start()
        for (root in listOf(R.string.today_title, R.string.plants_title)) {
            tab(root).performClick()
            assertOn(root)

            openMenu(R.string.places_title)
            compose.onNodeWithText(s(R.string.places_title)).assertExists()
            waitFor(s(R.string.places_empty))
            compose.onAllNodes(isTab).assertCountEquals(0)
            back()
            assertOn(root)

            openMenu(R.string.settings)
            compose.onNodeWithText(s(R.string.about_title)).assertExists()
            compose.onAllNodes(isTab).assertCountEquals(0)
            back()
            assertOn(root)
        }
    }

    @Test
    fun undoKeepsTheDeletedPlantAndItsEvents() {
        val boris = plant(g, "Boris", place(g, "Hall"))
        runBlocking { g.garden.watered(boris, ranOutOfBottom = false) }
        start()
        tab(R.string.plants_title).performClick()
        val delete = s(R.string.delete_named, "Boris")
        waitFor(delete, description = true)

        compose.onNodeWithContentDescription(delete).performClick()
        compose.onNodeWithContentDescription(delete).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.deleted, "Boris")).assertExists()
        compose.onNodeWithText(s(R.string.undo)).performClick()
        waitFor(delete, description = true)

        compose.mainClock.advanceTimeBy(SNACKBAR_GONE)
        compose.waitForIdle()
        runBlocking {
            assertNotNull(g.garden.plant(boris))
            assertEquals(1, g.garden.events(boris).first().size)
        }
    }

    @Test
    fun withoutUndoThePlantIsDeletedWhenTheSnackbarGoes() {
        val boris = plant(g, "Boris", place(g, "Hall"))
        runBlocking { g.garden.watered(boris, ranOutOfBottom = false) }
        start()
        tab(R.string.plants_title).performClick()
        val delete = s(R.string.delete_named, "Boris")
        waitFor(delete, description = true)

        compose.onNodeWithContentDescription(delete).performClick()
        assertNotNull("deleted before the snackbar went", runBlocking { g.garden.plant(boris) })
        compose.onNodeWithText(s(R.string.deleted, "Boris")).assertExists()
        compose.mainClock.advanceTimeBy(SNACKBAR_GONE)
        compose.waitUntil(5_000) { runBlocking { g.garden.plant(boris) == null } }
        assertTrue(runBlocking { g.garden.events(boris).first().isEmpty() })
        compose.onNodeWithContentDescription(delete).assertDoesNotExist()
    }

    @Test
    fun aSecondDeleteCommitsTheFirst() {
        val hall = place(g, "Hall")
        val boris = plant(g, "Boris", hall)
        val fern = plant(g, "Fern", hall)
        start()
        tab(R.string.plants_title).performClick()
        waitFor(s(R.string.delete_named, "Boris"), description = true)

        compose.onNodeWithContentDescription(s(R.string.delete_named, "Boris")).performClick()
        compose.onNodeWithContentDescription(s(R.string.delete_named, "Fern")).performClick()
        compose.waitUntil(5_000) { runBlocking { g.garden.plant(boris) == null } }
        compose.onNodeWithText(s(R.string.deleted, "Fern")).assertExists()
        assertNotNull(runBlocking { g.garden.plant(fern) })
    }

    @Test
    fun undoKeepsAnEmptyPlace() {
        val shed = place(g, "Shed")
        start()
        openMenu(R.string.places_title)
        val delete = s(R.string.delete_named, "Shed")
        waitFor(delete, description = true)

        compose.onNodeWithContentDescription(delete).performClick()
        compose.onNodeWithContentDescription(delete).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.undo)).performClick()
        waitFor(delete, description = true)

        compose.mainClock.advanceTimeBy(SNACKBAR_GONE)
        compose.waitForIdle()
        assertNotNull(runBlocking { g.garden.place(shed) })
    }

    @Test
    fun withoutUndoAnEmptyPlaceIsDeletedWhenTheSnackbarGoes() {
        val shed = place(g, "Shed")
        start()
        openMenu(R.string.places_title)
        val delete = s(R.string.delete_named, "Shed")
        waitFor(delete, description = true)

        compose.onNodeWithContentDescription(delete).performClick()
        compose.mainClock.advanceTimeBy(SNACKBAR_GONE)
        compose.waitUntil(5_000) { runBlocking { g.garden.place(shed) == null } }
    }

    @Test
    fun aPlaceInUseIsNotDeletedAndItsPlantsAreNamed() {
        val hall = place(g, "Hall")
        plant(g, "Fern", hall)
        plant(g, "Boris", hall)
        start()
        openMenu(R.string.places_title)
        val delete = s(R.string.delete_named, "Hall")
        waitFor(delete, description = true)

        compose.onNodeWithContentDescription(delete).performClick()
        compose.onNodeWithText(s(R.string.place_in_use, "Hall", "Boris, Fern")).assertExists()
        compose.onNodeWithText(s(R.string.undo)).assertDoesNotExist()
        compose.onNodeWithContentDescription(delete).assertExists()

        compose.mainClock.advanceTimeBy(SNACKBAR_GONE)
        compose.waitForIdle()
        runBlocking {
            assertNotNull(g.garden.place(hall))
            assertEquals(2, g.garden.plants.first().size)
        }
    }

    @Test
    fun aNotificationOpensItsPlant() {
        val boris = plant(g, "Boris", place(g, "Hall"))
        start(openPlant = boris)
        waitFor("Boris")
        compose.onNodeWithText(s(R.string.edit)).assertExists()
        compose.onAllNodes(isTab).assertCountEquals(0)
        back()
        assertOn(R.string.today_title)
    }

    @Test
    fun aDeleteThatFailsBringsTheItemBackAndSaysSo() {
        compose.setContent {
            OvergrownTheme {
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val undo = remember { UndoDelete(snackbar, scope) }
                Box {
                    ListPage(
                        title = "Plants",
                        onBack = null,
                        addLabel = "Add plant",
                        onAdd = {},
                        empty = "No plants yet.",
                        undo = undo,
                        items = listOf(
                            ListItem("plant:1", "Boris", null, null, onEdit = {}, onDelete = {
                                undo.delete("plant:1", s(R.string.deleted, "Boris"), s(R.string.undo), s(R.string.delete_failed, "Boris")) {
                                    throw IllegalStateException("database is closed")
                                }
                            }),
                        ),
                    )
                    SnackbarHost(snackbar)
                }
            }
        }
        val delete = s(R.string.delete_named, "Boris")
        compose.onNodeWithContentDescription(delete).performClick()
        compose.onNodeWithContentDescription(delete).assertDoesNotExist()

        // Past the 4 s "Deleted" snackbar but not past the failure message that follows it.
        compose.mainClock.advanceTimeBy(5_000)
        waitFor(s(R.string.delete_failed, "Boris"))
        compose.onNodeWithText(s(R.string.deleted, "Boris")).assertDoesNotExist()
        compose.onNodeWithContentDescription(delete).assertExists()
    }

    private companion object {
        /** Longer than any snackbar duration. */
        const val SNACKBAR_GONE = 15_000L
    }
}

internal val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

/** The compose rule hosts its content in a bare ComponentActivity, which the app's manifest does not declare. */
internal class RegisterHostActivity : ExternalResource() {
    override fun before() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }
}

/**
 * The app's database uses the bundled SQLite driver, whose native library is built for Android only and does not
 * load under Robolectric on the host JVM. Tests put a Garden over an in-memory database on the platform driver
 * in its place; everything above the database is the app's own code.
 */
internal fun useTestDatabase(): Graph {
    val app = ApplicationProvider.getApplicationContext<Application>()
    if (!WorkManager.isInitialized()) WorkManager.initialize(app, Configuration.Builder().build())
    val g = app.graph
    val db = Room.inMemoryDatabaseBuilder(app, GardenDb::class.java)
        .setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    val now = { Clock.System.now() }
    val metNorway = MetNorway(UrlTransport, DbResponseStore(db.dao()), now, g.versionName, TimeZone.UTC)
    val garden = Garden(
        db, g.settings, WeatherService(g.openMeteo, metNorway, g.settings), g.openMeteo, { null }, now,
        { TimeZone.currentSystemDefault() }, File(app.filesDir, "photos"),
    )
    Graph::class.java.getDeclaredField("garden").apply { isAccessible = true }.set(g, garden)
    return g
}

internal fun place(g: Graph, name: String): Long =
    runBlocking { g.garden.savePlace(PlaceEntity(name = name, outdoor = false, latitude = 44.8, longitude = 20.5)) }

internal fun plant(g: Graph, name: String, placeId: Long): Long =
    runBlocking { g.garden.savePlant(PlantEntity(name = name, placeId = placeId, canopyWidthCm = 20.0, potVolumeL = 2.0, acquiredOn = "2026-01-01")) }
