// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.data.EventEntity
import com.kostavukicevic.plants.data.GardenDb
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.data.WeatherDayEntity
import com.kostavukicevic.plants.engine.Coefficients
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.hours

/**
 * Golden images of the plant screen, light and dark, at 1.0x and 1.3x: an outdoor plant with frost ahead and two
 * weeks of history, a plant the app knows well, a plant with nothing logged, and the edit-entry dialog. The window
 * is tall enough to show the whole page.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1800dp-xhdpi")
class PlantScreenshotTest(private val dark: Boolean, private val fontScale: Float) {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private val suffix = "${if (dark) "dark" else "light"}_${fontScale}x"

    private val exact = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f, imageComparator = SimpleImageComparator(maxDistance = 0f)),
    )

    private var now = PLANT_TEST_NOW
    private val graph: Pair<Graph, GardenDb> by lazy { fixedClockGraph { now } }
    private val g get() = graph.first
    private val db get() = graph.second

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(plantId: Long) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            OvergrownTheme(dark = dark) { PlantScreen(plantId, onBack = {}, onEdit = {}) }
        }
    }

    private fun waitFor(text: String, description: Boolean = false, substring: Boolean = false) = compose.waitUntil(15_000) {
        compose.onAllNodes(if (description) hasContentDescription(text, substring) else hasText(text, substring)).fetchSemanticsNodes().isNotEmpty()
    }

    /** The strip comes from its own query after the status; until it lands, the page below it moves. */
    private fun waitForStrip(contains: String = s(R.string.plant_next_days)) = waitFor(contains, description = true, substring = true)

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/plant_${name}_$suffix.png", roborazziOptions = exact)
    }

    private fun log(plantId: Long, date: String, type: String, moisture: String? = null, ranOut: Boolean? = null) =
        runBlocking { db.dao().insertEvent(EventEntity(plantId = plantId, date = date, type = type, moisture = moisture, ranOutOfBottom = ranOut)) }

    /** An outdoor juniper with frost on Friday and Saturday, rain on Monday, and entries this week and last. */
    private fun outdoorPlant(): Long = runBlocking {
        val balcony = g.garden.savePlace(PlaceEntity(name = "Balcony", outdoor = true, latitude = 44.8, longitude = 20.5))
        val today = LocalDate(2026, 11, 24)
        db.dao().upsertWeather(
            (0 until 10).map { i ->
                val frost = i == 3 || i == 4
                WeatherDayEntity(
                    placeId = balcony, date = today.plus(DatePeriod(days = i)).toString(),
                    tMinC = if (frost) -3.0 else 4.0, tMaxC = if (frost) 3.0 else 11.0,
                    precipitationMm = if (i == 6) 8.0 else 0.0, provider = "test", fetchedAtEpochMs = PLANT_TEST_NOW.toEpochMilliseconds(),
                )
            },
        )
        g.garden.savePlant(
            PlantEntity(
                name = "Juniper", placeId = balcony, archetypeId = "hardy-potted-conifer", canopyWidthCm = 40.0, potVolumeL = 12.0,
                acquiredOn = "2026-11-01",
            ),
        ).also { id ->
            log(id, "2026-11-16", "WATERED", ranOut = true)
            log(id, "2026-11-19", "CHECK_IN", "OK")
            log(id, "2026-11-21", "FED")
            log(id, "2026-11-23", "CHECK_IN", "DRY")
            log(id, "2026-11-23", "WATERED", ranOut = false)
        }
    }

    @Test
    fun full() {
        val id = outdoorPlant()
        start(id)
        waitForStrip(s(R.string.plant_strip_frost))
        waitFor(s(R.string.plant_week_last))
        capture("full")
    }

    @Test
    fun editEntry() {
        val id = outdoorPlant()
        start(id)
        val edit = s(R.string.edit_named, s(R.string.plant_history_check_in, "23 Nov", s(R.string.moisture_dry)))
        waitFor(edit, description = true)
        waitForStrip(s(R.string.plant_strip_frost))
        compose.onNodeWithContentDescription(edit).performClick()
        waitFor(s(R.string.plant_edit_entry))
        capture("edit_entry")
    }

    @Test
    fun empty() {
        val id = runBlocking {
            val hall = g.garden.savePlace(PlaceEntity(name = "Hall", outdoor = false, latitude = 44.8, longitude = 20.5))
            g.garden.savePlant(PlantEntity(name = "Fern", placeId = hall, archetypeId = "indoor-moisture-loving", canopyWidthCm = 20.0, potVolumeL = 2.0, acquiredOn = "2026-11-20"))
        }
        start(id)
        waitFor(s(R.string.plant_history_empty))
        waitForStrip()
        capture("empty")
    }

    /**
     * A pothos looked after for eight weeks: each day the person answers what the soil really is (the app's own
     * prediction) and waters once it is dry, until the app knows it well.
     */
    @Test
    fun learned() {
        val id = runBlocking {
            val kitchen = g.garden.savePlace(PlaceEntity(name = "Kitchen", outdoor = false, latitude = 44.8, longitude = 20.5))
            g.garden.savePlant(PlantEntity(name = "Pothos", placeId = kitchen, archetypeId = "indoor-tropical-foliage", canopyWidthCm = 30.0, potVolumeL = 3.0, acquiredOn = "2026-09-28"))
        }
        runBlocking {
            var day = LocalDate(2026, 9, 29)
            while (day < LocalDate(2026, 11, 24)) {
                now = day.atStartOfDayIn(TimeZone.UTC) + 10.hours
                val d = g.garden.status(id)!!.state.depletion
                when {
                    d >= Coefficients.BAND_DRY_MAX -> {
                        g.garden.checkIn(id, "BONE_DRY")
                        g.garden.watered(id, ranOutOfBottom = true)
                    }
                    d >= Coefficients.BAND_OK_MAX -> g.garden.checkIn(id, "DRY")
                    else -> Unit
                }
                day = day.plus(DatePeriod(days = 1))
            }
            now = PLANT_TEST_NOW
            val sd = g.garden.status(id)!!.state.belief.sd
            assertTrue("not learned: sd $sd", sd < Coefficients.CONFIDENT_SD)
        }
        start(id)
        waitFor(s(R.string.plant_knows_well, "Pothos"))
        waitForStrip()
        capture("learned")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} fontScale={1}")
        fun params() = listOf(arrayOf<Any>(false, 1.0f), arrayOf<Any>(false, 1.3f), arrayOf<Any>(true, 1.0f), arrayOf<Any>(true, 1.3f))
    }
}

