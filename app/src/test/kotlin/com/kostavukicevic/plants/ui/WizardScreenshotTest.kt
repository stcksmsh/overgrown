// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.UrlTransport
import com.kostavukicevic.plants.caredb.CareDb
import com.kostavukicevic.plants.data.DbResponseStore
import com.kostavukicevic.plants.data.Garden
import com.kostavukicevic.plants.data.GardenDb
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.graph
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.WeatherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.time.Instant

/** Golden images of the four add-plant steps, a new outdoor place with "Use my location", and step 1 with search results (one of each kind) and with no match, light and dark, at 1.0x, 1.3x and 2.0x. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class WizardScreenshotTest(private val dark: Boolean, private val fontScale: Float) {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private val suffix = "${if (dark) "dark" else "light"}_${fontScale}x"

    // Any changed pixel fails, as in ThemeScreenshotTest. Built per instance, inside the Robolectric sandbox.
    private val exact = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f, imageComparator = SimpleImageComparator(maxDistance = 0f)),
    )

    private lateinit var g: Graph

    @Before
    fun setUp() {
        g = useFixedClockDatabase()
        runBlocking {
            g.garden.savePlace(PlaceEntity(name = "Kitchen", outdoor = false, latitude = 44.8, longitude = 20.5, lightClass = "MEDIUM"))
            g.garden.savePlace(PlaceEntity(name = "Bedroom", outdoor = false, latitude = 44.8, longitude = 20.5))
            g.garden.savePlace(PlaceEntity(name = "Balcony", outdoor = true, latitude = 44.8, longitude = 20.5))
        }
    }

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** Waits for the places to load, which enables Next, so every image shows the loaded wizard. */
    private fun start(db: CareDb = WizardTest.FIXTURE_DB) {
        compose.setContent {
            FontScaled(fontScale) {
                OvergrownTheme(dark = dark) { AddPlantWizard(null, onDone = {}, careDb = db) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(s(R.string.wizard_next_where)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/wizard_${name}_$suffix.png", roborazziOptions = exact)
    }

    private fun type(label: Int, text: String) = compose.onNode(hasSetTextAction() and hasText(s(label))).performScrollTo().performTextInput(text)
    private fun tap(id: Int) = compose.onNodeWithText(s(id)).performScrollTo().performClick()

    private fun next(id: Int) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(s(id)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(s(id)).performClick()
    }

    /** Clears focus so no cursor blinks into the image. */
    private fun unfocus() = compose.runOnUiThread { compose.activity.currentFocus?.clearFocus() }

    @Test
    fun step1() {
        start()
        capture("step1")
    }

    @Test
    fun step1Results() {
        start()
        type(R.string.wizard_name, "Test")
        unfocus()
        capture("step1_results")
    }

    /** A species row, a name-table row and an archetype row, in that order. */
    @Test
    fun step1ResultKinds() {
        start(WizardTest.NAMES_DB)
        type(R.string.wizard_name, "Test")
        unfocus()
        capture("step1_result_kinds")
    }

    @Test
    fun step1NoMatch() {
        start()
        type(R.string.wizard_name, "Zzplant")
        tap(R.string.look_fern)
        unfocus()
        compose.onNodeWithText(s(R.string.wizard_name)).performScrollTo()
        capture("step1_no_match")
    }

    @Test
    fun steps2To4() {
        start()
        type(R.string.wizard_name, "Zzplant")
        tap(R.string.look_leafy)
        next(R.string.wizard_next_where)
        tap(R.string.place_indoor)
        compose.onNodeWithText("Kitchen").performClick()
        capture("step2")
        next(R.string.wizard_next_pot)
        type(R.string.wizard_pot_width, "20")
        type(R.string.wizard_plant_width, "30")
        unfocus()
        compose.onNodeWithText(s(R.string.wizard_pot_title)).performScrollTo()
        capture("step3")
        next(R.string.wizard_next_exposure)
        tap(R.string.answer_little)
        compose.onAllNodesWithText(s(R.string.wizard_exposure_indoor_title))[0].performScrollTo()
        capture("step4")
    }

    @Test
    fun step2NewOutdoorPlace() {
        start()
        type(R.string.wizard_name, "Zzplant")
        next(R.string.wizard_next_where)
        tap(R.string.place_outdoor)
        tap(R.string.wizard_new_place)
        unfocus()
        compose.onNodeWithText(s(R.string.wizard_use_location)).performScrollTo()
        capture("step2_new_outdoor")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}, fontScale={1}")
        fun params() = listOf(1.0f, 1.3f, 2.0f).flatMap { scale -> listOf(arrayOf<Any>(false, scale), arrayOf<Any>(true, scale)) }

        /** Step 3 shows today's date, so the images need a clock that does not move. */
        private val NOW = Instant.parse("2026-11-24T09:00:00Z")

        /** [useTestDatabase] with a fixed clock and time zone. */
        private fun useFixedClockDatabase(): Graph {
            val app = ApplicationProvider.getApplicationContext<Application>()
            if (!WorkManager.isInitialized()) WorkManager.initialize(app, Configuration.Builder().build())
            val g = app.graph
            val db = Room.inMemoryDatabaseBuilder(app, GardenDb::class.java)
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
            val now = { NOW }
            val metNorway = MetNorway(UrlTransport, DbResponseStore(db.dao()), now, g.versionName, TimeZone.UTC)
            val garden = Garden(
                db, g.settings, WeatherService(g.openMeteo, metNorway, g.settings), g.openMeteo, { null }, now,
                { TimeZone.UTC }, File(app.filesDir, "photos"),
            )
            Graph::class.java.getDeclaredField("garden").apply { isAccessible = true }.set(g, garden)
            return g
        }
    }
}
