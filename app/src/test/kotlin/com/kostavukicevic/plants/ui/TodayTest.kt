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
import androidx.compose.ui.test.performScrollTo
import com.kostavukicevic.plants.App
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.MainActivity
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.data.CareSource
import com.kostavukicevic.plants.data.EventEntity
import com.kostavukicevic.plants.data.Garden
import com.kostavukicevic.plants.data.GardenDb
import com.kostavukicevic.plants.data.PlaceEntity
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.data.PlantStatus
import com.kostavukicevic.plants.data.Planner
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.data.AlertEvent
import com.kostavukicevic.plants.data.alertEvents
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import com.kostavukicevic.plants.data.WeatherDayEntity
import com.kostavukicevic.plants.weather.HttpResponse
import com.kostavukicevic.plants.weather.HttpTransport
import com.kostavukicevic.plants.weather.InMemoryResponseStore
import com.kostavukicevic.plants.weather.MetNorway
import com.kostavukicevic.plants.weather.OpenMeteo
import com.kostavukicevic.plants.weather.WeatherService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import java.io.File
import java.io.IOException
import kotlin.time.Clock

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
        // One card for the one frost event, naming both plants in it.
        val alert = top(s(R.string.event_cold, "Wed", "Cedar and Elm"))
        val water = compose.onAllNodesWithText(s(R.string.plant_watered)).fetchSemanticsNodes().map { it.positionInRoot.y }
        val soil = top(s(R.string.today_soil_question))
        val fine = compose.onAllNodesWithText(s(R.string.tile_fine_check, "Thu"), substring = true).fetchSemanticsNodes().minOf { it.positionInRoot.y }
        assertTrue("alerts above Do now", alert < water.min())
        assertTrue("Do now above All fine", maxOf(water.max(), soil) < fine)
        // Elm has a water card and is not also a resting tile; Cedar has a tile. Both are named on the alert card.
        compose.onAllNodesWithText("Elm").assertCountEquals(1)
        compose.onAllNodesWithText("Cedar").assertCountEquals(1)
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

    @Test
    fun theRealTodayScreenStoresWateredAndTheThreeAnswers() {
        val hall = place(g, "Hall")
        // Three new plants, so a card that goes once answered still leaves one to answer the next.
        val asked = listOf("Fern", "Ivy", "Palm").map { plant(g, it, hall) }
        val pothos = plant(g, "Pothos", hall)
        // Eight five-day cycles of dry, watered and OK halfway, the last eight days ago: the engine is sure enough
        // to say water, where a plant with no history only asks for a soil check.
        runBlocking {
            for (k in 9 downTo 1) {
                val day = 3 + k * 5
                storeEvent(pothos, day, "CHECK_IN", Moisture.DRY_OR_BONE_DRY.name)
                storeEvent(pothos, day, "WATERED")
                if (k > 1) storeEvent(pothos, day - 2, "CHECK_IN", Moisture.OK.name)
            }
        }
        compose.setContent { OvergrownTheme { TodayScreen(onPlant = {}, onAdd = {}, onPlaces = {}, onSettings = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(s(R.string.today_soil_question))).fetchSemanticsNodes().isNotEmpty() }

        fun eventsOf(id: Long) = runBlocking { g.garden.events(id).first() }
        val before = eventsOf(pothos).count { it.type == "WATERED" }
        // A tap is a real touch at the node's place, so the button must be on screen first.
        compose.onNodeWithText(s(R.string.plant_watered)).performScrollTo().performClick()
        compose.waitUntil(10_000) { eventsOf(pothos).count { it.type == "WATERED" } == before + 1 }
        val watered = eventsOf(pothos).filter { it.type == "WATERED" }.maxBy { it.id }
        assertEquals(g.garden.today().toString(), watered.date)
        assertEquals(runBlocking { g.garden.plant(pothos)!!.drainageHoles }, watered.ranOutOfBottom)

        val expected = listOf(R.string.answer_wet to Moisture.TOO_WET, R.string.answer_ok to Moisture.OK, R.string.answer_dry to Moisture.DRY_OR_BONE_DRY)
        fun answers() = asked.flatMap { eventsOf(it) }.filter { it.type == "CHECK_IN" }.sortedBy { it.id }
        for ((n, answer) in expected.withIndex()) {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(s(answer.first))).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(s(answer.first))[0].performScrollTo().performClick()
            compose.waitUntil(10_000) { answers().size == n + 1 }
        }
        assertEquals(expected.map { it.second.name }, answers().map { it.moisture })
    }

    /** An event [daysAgo] days before today, which the app itself only records for today. */
    private suspend fun storeEvent(plantId: Long, daysAgo: Int, type: String, moisture: String? = null) {
        val db = Garden::class.java.getDeclaredField("db").apply { isAccessible = true }.get(g.garden) as GardenDb
        val date = LocalDate.fromEpochDays(g.garden.today().toEpochDays() - daysAgo)
        db.dao().insertEvent(EventEntity(plantId = plantId, date = date.toString(), type = type, ranOutOfBottom = if (type == "WATERED") true else null, moisture = moisture))
    }

    @Test
    fun alertDaysSplitAtGaps() {
        val c = compose.activity
        fun frost(vararg days: Int) = Texts.alertSummary(c, days.map { Alert(AlertType.ROOT_FREEZE_CAUTION, LocalDate(2026, 11, it)) }, TODAY)
        // Today is Tuesday 24 Nov; a frost Wednesday and Friday must not read as one Wed-Fri range.
        assertEquals("Frost Wed, Fri.", frost(25, 27))
        assertEquals("Frost Wed-Thu, Sat.", frost(25, 26, 28))
        assertEquals("Frost Wed-Fri.", frost(25, 26, 27))
        assertEquals("Frost Wed.", frost(25, 25))
    }

    @Test
    fun anAlertCardNamesItsDaysAndEveryPlant() {
        val c = compose.activity
        fun event(type: AlertType, days: IntRange, vararg names: String) =
            alertEvents(names.map { n -> fixture(n, alerts = days.map { Alert(type, LocalDate(2026, 11, it)) }) }, TODAY).single()
        assertEquals("Cold nights Wed. Move Boris against a wall or insulate the pot.", Texts.event(c, event(AlertType.ROOT_FREEZE_CAUTION, 25..25, "Boris"), TODAY))
        assertEquals(
            "Hard frost Wed-Fri. Move Aloe, Boris and Fern somewhere sheltered or wrap the pot well.",
            Texts.event(c, event(AlertType.ROOT_FREEZE_STRONG, 25..27, "Fern", "Boris", "Aloe"), TODAY),
        )
        assertEquals("Heat Thu-Fri. Check Basil and Boris more often; shade small pots.", Texts.event(c, event(AlertType.HEAT, 26..27, "Boris", "Basil"), TODAY))
    }

    @Test
    fun alertCardDoneAndNotNowPassTheirEvent() {
        val frost = listOf(Alert(AlertType.ROOT_FREEZE_CAUTION, LocalDate(2026, 11, 25)))
        val list = listOf(fixture("Boris", alerts = frost, next = LocalDate(2026, 11, 26)))
        val done = mutableListOf<AlertEvent>()
        val dismissed = mutableListOf<AlertEvent>()
        compose.setContent {
            OvergrownTheme {
                TodayContent(list, TODAY, onPlant = {}, onAdd = {}, onWatered = {}, onAnswer = { _, _ -> }, onDone = { done += it }, onDismiss = { dismissed += it }) { FixedWeather() }
            }
        }
        val event = alertEvents(list, TODAY).single()
        compose.onNodeWithText(s(R.string.event_done)).performClick()
        compose.onNodeWithText(s(R.string.event_not_now)).performClick()
        assertEquals(listOf(event), done)
        assertEquals(listOf(event), dismissed)
    }

    /** The garden on the test database, with every weather request answered by [http]. */
    private fun weatherBy(http: HttpTransport): GardenDb {
        val db = Garden::class.java.getDeclaredField("db").apply { isAccessible = true }.get(g.garden) as GardenDb
        val now = { Clock.System.now() }
        val om = OpenMeteo(http, InMemoryResponseStore(), now)
        val met = MetNorway(http, InMemoryResponseStore(), now, "test", TimeZone.UTC)
        val app = compose.activity.application
        val garden = Garden(db, g.settings, WeatherService(om, met, g.settings), om, { null }, now, { TimeZone.currentSystemDefault() }, File(app.filesDir, "photos"))
        Graph::class.java.getDeclaredField("garden").apply { isAccessible = true }.set(g, garden)
        return db
    }

    private fun outdoorPlant() = runBlocking { plant(g, "Rosemary", g.garden.savePlace(BALCONY.copy(id = 0))) }

    private fun showTodayScreen() {
        compose.setContent { OvergrownTheme { TodayScreen(onPlant = {}, onAdd = {}, onPlaces = {}, onSettings = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(s(R.string.refresh))).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun forecastBody(): String {
        val d = g.garden.today()
        val days = listOf(d, LocalDate.fromEpochDays(d.toEpochDays() + 1)).joinToString(",") { "\"$it\"" }
        return """{"daily":{"time":[$days],"temperature_2m_max":[9.8,5.3],"temperature_2m_min":[1.5,0.5],""" +
            """"precipitation_sum":[0.0,0.0],"wind_speed_10m_mean":[4.99,3.11],"relative_humidity_2m_mean":[83,67],"et0_fao_evapotranspiration":[0.68,1.14]}}"""
    }

    @Test
    fun refreshShowsGettingTheForecastThenTheNewTimeWithoutReopening() {
        val answer = CompletableDeferred<HttpResponse>()
        weatherBy { url, _ -> if ("archive" in url) HttpResponse(503, emptyMap(), "") else answer.await() }
        outdoorPlant()
        showTodayScreen()
        compose.onNodeWithText(s(R.string.forecast_never)).assertExists()

        compose.onNodeWithText(s(R.string.refresh)).performScrollTo().performClick()
        waitForText(s(R.string.forecast_loading))
        compose.onNodeWithText(s(R.string.forecast_never)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.refresh)).assertIsNotEnabled()

        answer.complete(HttpResponse(200, emptyMap(), forecastBody()))
        waitForText(s(R.string.forecast_fetched, ""), substring = true)
        compose.onNodeWithText(s(R.string.forecast_loading)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.forecast_never)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.refresh)).assertIsEnabled()
    }

    @Test
    fun aFetchThatEndsBeforeTodayLoadsStillShows() {
        val db = weatherBy { _, _ -> HttpResponse(503, emptyMap(), "") }
        val placeId = runBlocking { g.garden.savePlace(BALCONY.copy(id = 0)) }
        plant(g, "Rosemary", placeId)
        compose.setContent { OvergrownTheme { TodayScreen(onPlant = {}, onAdd = {}, onPlaces = {}, onSettings = {}) } }
        // As the fetch on opening the app: its rows land and it signals a change before Today has loaded.
        runBlocking { db.dao().upsertWeather(listOf(WeatherDayEntity(placeId, g.garden.today().toString(), 1.0, 9.0, 0.0, provider = "OPEN_METEO", fetchedAtEpochMs = Clock.System.now().toEpochMilliseconds()))) }
        g.changed()
        waitForText(s(R.string.forecast_fetched, ""), substring = true)
        compose.onNodeWithText(s(R.string.forecast_never)).assertDoesNotExist()
    }

    @Test
    fun aFailedFetchSaysSoAndKeepsTheLastTime() {
        val db = weatherBy { _, _ -> throw IOException("offline") }
        val placeId = runBlocking { g.garden.savePlace(BALCONY.copy(id = 0)) }
        plant(g, "Rosemary", placeId)
        val earlier = FETCHED.toEpochMilliseconds()
        runBlocking { db.dao().upsertWeather(listOf(WeatherDayEntity(placeId, g.garden.today().toString(), 1.0, 9.0, 0.0, provider = "OPEN_METEO", fetchedAtEpochMs = earlier))) }
        showTodayScreen()
        val fetchedLine = s(R.string.forecast_fetched, Texts.dateTime(FETCHED))
        // The time loads after the plants, so wait for it rather than expect it with the Refresh button.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(fetchedLine), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(s(R.string.refresh)).performScrollTo().performClick()
        waitForText(s(R.string.forecast_failed))
        compose.onNodeWithText(fetchedLine, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(s(R.string.refresh)).assertIsEnabled()
    }

    @Test
    fun aFailedFirstFetchSaysSoInsteadOfNoForecastYet() {
        weatherBy { _, _ -> throw IOException("offline") }
        outdoorPlant()
        showTodayScreen()
        compose.onNodeWithText(s(R.string.refresh)).performScrollTo().performClick()
        waitForText(s(R.string.forecast_failed))
        compose.onNodeWithText(s(R.string.forecast_never)).assertDoesNotExist()
    }

    @Test
    fun theFetchOnOpeningTheAppShowsOnTodayUntilItEnds() {
        val answer = CompletableDeferred<HttpResponse>()
        weatherBy { url, _ -> if ("archive" in url) HttpResponse(503, emptyMap(), "") else answer.await() }
        outdoorPlant()
        // The real activity: its onResume starts the fetch, as when the app is opened.
        val app = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            waitForText(s(R.string.forecast_loading))
            compose.onNodeWithText(s(R.string.refresh)).assertIsNotEnabled()
            assertTrue(g.fetching.value)

            answer.complete(HttpResponse(200, emptyMap(), forecastBody()))
            waitForText(s(R.string.forecast_fetched, ""), substring = true)
            compose.onNodeWithText(s(R.string.forecast_loading)).assertDoesNotExist()
            assertFalse(g.fetching.value)
        } finally {
            app.close()
        }
    }

    @Test
    fun aFailedFetchEndsFetchingAndIsMarkedFailed() {
        weatherBy { _, _ -> throw IOException("offline") }
        outdoorPlant()
        runBlocking { g.refreshWeather(foreground = true) }
        assertFalse(g.fetching.value)
        assertTrue(g.fetchFailed.value)
    }

    @Test
    fun aCancelledFetchIsNotAFailureAndEndsFetching() {
        val never = CompletableDeferred<HttpResponse>()
        weatherBy { _, _ -> never.await() }
        outdoorPlant()
        runBlocking {
            val job = launch(Dispatchers.Default) { g.refreshWeather(foreground = true) }
            withTimeout(10_000) { g.fetching.first { it } }
            job.cancelAndJoin()
        }
        assertFalse(g.fetching.value)
        assertFalse(g.fetchFailed.value)
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
internal fun FixedWeather(refreshing: Boolean = false) = WeatherStatus(FETCHED.toEpochMilliseconds() to "OPEN_METEO", FETCHED, refreshing, failed = false) {}

private val FETCHED = Instant.parse("2026-11-24T07:30:00Z")
