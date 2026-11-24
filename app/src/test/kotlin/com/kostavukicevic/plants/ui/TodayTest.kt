// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kostavukicevic.plants.App
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.data.CareSource
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.data.PlantStatus
import com.kostavukicevic.plants.data.Planner
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.Alert
import com.kostavukicevic.plants.engine.AlertType
import com.kostavukicevic.plants.engine.EngineState
import com.kostavukicevic.plants.engine.HoldReason
import com.kostavukicevic.plants.engine.Moisture
import com.kostavukicevic.plants.engine.PromptReason
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/** The Today screen's cards, their order, their words and what their buttons store. */
@RunWith(RobolectricTestRunner::class)
class TodayTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private lateinit var g: Graph

    @Before
    fun setUp() {
        g = useTestDatabase()
    }

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun show(list: List<PlantStatus>, onWatered: (PlantStatus) -> Unit = {}, onAnswer: (PlantStatus, Moisture) -> Unit = { _, _ -> }) =
        compose.setContent {
            OvergrownTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TodayContent(list, TODAY, onPlant = {}, onAdd = {}, onWatered = onWatered, onAnswer = onAnswer) { FixedWeather() }
                }
            }
        }

    /** A plant stored in the test database, so a button's event can be read back. */
    private fun stored(name: String): PlantEntity = runBlocking { g.garden.plant(plant(g, name, place(g, "Hall")))!! }

    private fun events(p: PlantEntity) = runBlocking { g.garden.events(p.id).first() }

    // Positions, not bounds: bounds are clipped to the visible part of the scrolling column.
    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    private fun allText(): List<String> = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    @Test
    fun waterCardShowsLevelDoseAndRecordsWatering() {
        val p = stored("Pothos")
        show(listOf(fixture("Pothos", Action.WaterNow(437, PromptReason.DRY_BY_MODEL), depletion = 0.8, plant = p)), onWatered = { runBlocking { waterFromToday(g.garden, it.plant) } })
        compose.onNodeWithText(s(R.string.today_level, s(R.string.level_getting_dry), 20)).assertExists()
        compose.onNodeWithText(s(R.string.today_water_dose, 450)).assertExists()
        compose.onNodeWithContentDescription(s(R.string.pot_gauge_description, 20)).assertExists()
        assertTrue(events(p).none { it.type == "WATERED" })

        compose.onNodeWithText(s(R.string.plant_watered)).performClick()
        compose.waitForIdle()
        val watered = events(p).filter { it.type == "WATERED" }
        assertEquals(1, watered.size)
        // The test plant has drainage holes, so a one-tap watering counts as run through, as from a notification.
        assertEquals(true, watered.single().ranOutOfBottom)
    }

    @Test
    fun waterLevelWordsFollowTheAnswerBands() {
        val c = compose.activity
        assertEquals(s(R.string.level_wet), Texts.waterLevel(c, 0.71))
        assertEquals(s(R.string.level_moist), Texts.waterLevel(c, 0.69))
        assertEquals(s(R.string.level_moist), Texts.waterLevel(c, 0.31))
        assertEquals(s(R.string.level_getting_dry), Texts.waterLevel(c, 0.29))
        assertEquals(s(R.string.level_getting_dry), Texts.waterLevel(c, 0.06))
        assertEquals(s(R.string.level_dry), Texts.waterLevel(c, 0.04))
    }

    @Test
    fun soilCardStoresWetOkAndDry() {
        val expected = listOf(R.string.answer_wet to Moisture.TOO_WET, R.string.answer_ok to Moisture.OK, R.string.answer_dry to Moisture.DRY_OR_BONE_DRY)
        val p = stored("Fern")
        show(listOf(fixture("Fern", Action.CheckSoil(PromptReason.UNCERTAIN), plant = p)), onAnswer = { st, m -> runBlocking { g.garden.checkIn(st.plant.id, m.name) } })
        for ((label, _) in expected) {
            compose.onNodeWithText(s(label)).performClick()
            compose.waitForIdle()
        }
        val answers = events(p).filter { it.type == "CHECK_IN" }.sortedBy { it.id }.map { it.moisture }
        assertEquals(expected.map { it.second.name }, answers)
    }

    @Test
    fun orderIsAlertsThenDoNowThenAllFine() {
        val frost = listOf(Alert(AlertType.ROOT_FREEZE_CAUTION, LocalDate(2026, 11, 25)))
        show(
            listOf(
                fixture("Ash", next = LocalDate(2026, 11, 26)),
                fixture("Birch", Action.WaterNow(300, PromptReason.DRY_BY_MODEL), depletion = 0.8),
                fixture("Cedar", alerts = frost, next = LocalDate(2026, 11, 27)),
                fixture("Dahlia", Action.CheckSoil(PromptReason.UNCERTAIN)),
                fixture("Elm", Action.WaterNow(500, PromptReason.DRY_BY_MODEL), depletion = 0.9, alerts = frost),
            ),
        )
        val alert = compose.onAllNodesWithText(s(R.string.alert_root_freeze_caution, "25 Nov")).fetchSemanticsNodes().maxOf { it.positionInRoot.y }
        val water = compose.onAllNodesWithText(s(R.string.plant_watered)).fetchSemanticsNodes().map { it.positionInRoot.y }
        val soil = top(s(R.string.today_soil_question))
        val fine = compose.onAllNodesWithText(s(R.string.tile_fine_check, "Thu"), substring = true).fetchSemanticsNodes().minOf { it.positionInRoot.y }
        assertTrue("alerts above Do now", alert < water.min())
        assertTrue("Do now above All fine", maxOf(water.max(), soil) < fine)
        // Elm has an alert and a water card, and is not also a resting tile; Cedar has an alert block and a tile.
        compose.onAllNodesWithText("Elm").assertCountEquals(2)
        compose.onAllNodesWithText("Cedar").assertCountEquals(2)
        compose.onAllNodesWithText("Ash").assertCountEquals(1)
        compose.onAllNodes(hasText(s(R.string.tile_fine_check, "Fri"))).assertCountEquals(1)
    }

    @Test
    fun restingTileDates() {
        show(
            listOf(
                fixture("Thursday", next = LocalDate(2026, 11, 26)),
                fixture("Six days", next = LocalDate(2026, 11, 30)),
                fixture("Seven days", next = LocalDate(2026, 12, 1)),
                fixture("Later", next = LocalDate(2026, 12, 6)),
                fixture("Beyond", next = null),
                fixture("Snoozed", next = LocalDate(2026, 11, 25)).copy(snoozedUntil = LocalDate(2026, 11, 25)),
            ),
        )
        for (line in listOf("Fine. Check Thu.", "Fine. Check Mon.", "Fine. Check 1\u00A0Dec.", "Fine. Check 6\u00A0Dec.", "Fine.", "Fine. Check Wed.")) {
            compose.onNodeWithText(line).assertExists()
        }
    }

    @Test
    fun aHeldPlantRestsWithItsReason() {
        show(listOf(fixture("Boris", Action.Hold(HoldReason.FROST))))
        compose.onNodeWithText(s(R.string.action_hold_frost)).assertExists()
    }

    @Test
    fun emptyTodayIsOneSentenceAndOneButton() {
        compose.setContent { OvergrownTheme { App(null) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(s(R.string.today_empty))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(s(R.string.today_add_first)).assertExists()
        compose.onNodeWithText(s(R.string.refresh)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.forecast_never)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.weather_credit_open_meteo)).assertDoesNotExist()
    }

    @Test
    fun noModelWordsOnTheFullToday() {
        show(fullToday())
        val text = allText()
        assertTrue(text.any { it.contains("Watered") })
        for (never in NEVER) assertTrue("\"$never\" on Today: $text", text.none { it.contains(never, ignoreCase = true) })
    }

    @Test
    fun nextToAnAlertTodaySaysNothingToWater() {
        val frost = (25..27).map { Alert(AlertType.ROOT_FREEZE_STRONG, LocalDate(2026, 11, it)) }
        show(listOf(fixture("Boris", alerts = frost, next = LocalDate(2026, 11, 28))))
        compose.onNodeWithText("${s(R.string.today_nothing_to_water)} Frost Wed-Fri.").assertExists()
        val text = allText()
        for (never in NEVER) assertTrue("\"$never\" on Today: $text", text.none { it.contains(never, ignoreCase = true) })
    }

    companion object {
        private val NEVER = listOf("estimate", "depletion", "confidence", "Soil water used", "Nothing to do")
    }
}

/** The day every Today fixture is seen from: a Tuesday. */
internal val TODAY = LocalDate(2026, 11, 24)

internal val BALCONY = PlaceEntity(id = 1, name = "Balcony", outdoor = true, latitude = 44.8, longitude = 20.5)

/** A plant's status as Today receives it, without running the engine. */
internal fun fixture(
    name: String,
    today: Action = Action.None,
    depletion: Double = 0.4,
    next: LocalDate? = null,
    alerts: List<Alert> = emptyList(),
    archetype: String? = "indoor-tropical-foliage",
    plant: PlantEntity = PlantEntity(id = name.hashCode().toLong(), name = name, placeId = BALCONY.id, archetypeId = archetype, canopyWidthCm = 20.0, potVolumeL = 2.0, acquiredOn = "2026-01-01"),
    place: PlaceEntity = BALCONY,
) = PlantStatus(
    plant = plant,
    place = place,
    engine = Planner.enginePlant(plant, place, Planner.profile(plant, place, null).first),
    state = EngineState(depletion = depletion),
    today = today,
    alerts = alerts,
    nextPrompt = next?.let { it to Action.CheckSoil(PromptReason.DRY_BY_MODEL) },
    snoozedUntil = null,
    gapDays = 0,
    careSource = CareSource.GENERIC,
)

/** One alert, one water card, one soil card and three fine plants. */
internal fun fullToday() = listOf(
    fixture("Boris", alerts = listOf(Alert(AlertType.ROOT_FREEZE_CAUTION, LocalDate(2026, 11, 25))), next = LocalDate(2026, 11, 26), archetype = "hardy-potted-conifer", depletion = 0.3),
    fixture("Pothos", Action.WaterNow(680, PromptReason.DRY_BY_MODEL), depletion = 0.8),
    fixture("Snake plant", Action.CheckSoil(PromptReason.UNCERTAIN), depletion = 0.6, archetype = "indoor-succulent"),
    fixture("Fern", next = LocalDate(2026, 11, 28), archetype = "indoor-moisture-loving", depletion = 0.45),
    fixture("Moth orchid", next = LocalDate(2026, 12, 6), archetype = "indoor-epiphytic-orchid", depletion = 0.2),
)

/** The weather status with a fixed fetch time, so captures do not change with the clock. */
@Composable
internal fun FixedWeather() = WeatherStatus(FETCHED.toEpochMilliseconds() to "OPEN_METEO", FETCHED, refreshing = false) {}

private val FETCHED = Instant.parse("2026-11-24T07:30:00Z")
