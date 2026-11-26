// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.LocalBoris
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.Texts
import com.kostavukicevic.plants.data.AlertAck
import com.kostavukicevic.plants.data.AlertEvent
import com.kostavukicevic.plants.data.AlertKind
import com.kostavukicevic.plants.data.Garden
import com.kostavukicevic.plants.data.GardenDb
import com.kostavukicevic.plants.data.PlantEntity
import com.kostavukicevic.plants.data.WeatherDayEntity
import com.kostavukicevic.plants.engine.AlertType
import com.kostavukicevic.plants.notify.Notifier
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import com.kostavukicevic.plants.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Clock

/** The alert cards on the real Today screen: one per event, and what Done and Dismiss store and take down. */
@RunWith(RobolectricTestRunner::class)
class TodayAlertTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private lateinit var g: Graph

    @Before
    fun setUp() {
        g = useTestDatabase()
    }

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    // Bumped to open Today afresh: content can be set only once per test.
    private var opened by mutableIntStateOf(0)

    private fun showToday() {
        if (opened++ == 0) compose.setContent { OvergrownTheme { key(opened) { TodayScreen(onPlant = {}, onAdd = {}, onPlaces = {}, onSettings = {}) } } }
    }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun visible() = runBlocking { g.garden.alertEvents() }

    // While Today is open: DataStore runs each update in its caller's context, and the screen's own update waits
    // for the main thread this test holds. Read from another thread and give up after a moment, so the screen
    // can go on between tries; null means not read yet.
    private fun <T> whileOpen(block: suspend () -> T): T? =
        runBlocking { withTimeoutOrNull(1_000) { withContext(Dispatchers.IO) { block() } } }

    private fun visibleWhileOpen() = whileOpen { g.garden.alertEvents() }

    @Test
    fun twoPlantsInOneEventAreOneCardAndDoneHidesItAndPersists() {
        heatWave(g, "Boris", "Fern")
        val event = visible().single()
        assertEquals(listOf("Boris", "Fern"), event.plants.map { it.name })
        val text = Texts.eventCard(compose.activity, event, g.garden.today())
        assertTrue(text, text.endsWith(s(R.string.event_do_heat, "Boris and Fern")))

        showToday()
        waitForText(text)
        compose.onAllNodes(hasText(s(R.string.alert_done))).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithText(s(R.string.alert_done)).performScrollTo().performClick()
        // Gone at once, and stored: the event is no longer visible to the next reader.
        compose.onNodeWithText(text).assertDoesNotExist()
        compose.waitUntil(10_000) { visibleWhileOpen()?.isEmpty() == true }
        assertTrue(whileOpen { g.settings.alertAcks() }!!.single().dismissedOn == null)

        // A fresh Today shows no card.
        showToday()
        waitForText(s(R.string.refresh))
        compose.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun dismissHidesTheCardForToday() {
        heatWave(g, "Boris")
        val text = Texts.eventCard(compose.activity, visible().single(), g.garden.today())
        showToday()
        waitForText(text)
        compose.onNodeWithText(s(R.string.alert_dismiss)).performScrollTo().performClick()
        compose.onNodeWithText(text).assertDoesNotExist()
        compose.waitUntil(10_000) { visibleWhileOpen()?.isEmpty() == true }
        assertEquals(g.garden.today().toString(), whileOpen { g.settings.alertAcks() }!!.single().dismissedOn)
    }

    @Test
    fun theNothingToWaterLineFollowsTheVisibleEvents() {
        heatWave(g, "Boris")
        val c = compose.activity
        val line = "${s(R.string.today_nothing_to_water)} ${Texts.eventSummary(c, visible(), g.garden.today())}"
        showToday()
        waitForText(line)
        compose.onNodeWithText(s(R.string.alert_done)).performScrollTo().performClick()
        waitForText(s(R.string.today_all_fine))
        compose.onNodeWithText(line).assertDoesNotExist()
    }

    @Test
    fun doneOnTodayTakesDownThePostedNotificationAndBlocksALaterOne() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier.createChannels(app)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        heatWave(g, "Boris")
        val posted = visible().single()
        assertTrue(runBlocking { g.scheduler.postEvent(AlertAck.of(posted)) })
        assertNotNull(nm.getNotification(Notifier.ALERT_TAG, Notifier.alertId(AlertAck.of(posted))))

        // A second event, not yet posted, so its scheduled notification is what Done must block.
        heatWave(g, "Fern", place = "Terrace", from = 5)
        val later = visible().single { it.key != posted.key }

        showToday()
        waitForText(Texts.eventCard(compose.activity, posted, g.garden.today()))
        for (n in 0 until 2) {
            compose.onAllNodes(hasText(s(R.string.alert_done)))[0].performScrollTo().performClick()
            compose.waitUntil(10_000) { visibleWhileOpen()?.size == 1 - n }
        }
        compose.waitUntil(10_000) { nm.getNotification(Notifier.ALERT_TAG, Notifier.alertId(AlertAck.of(posted))) == null }
        var postedLater: Boolean? = null
        compose.waitUntil(10_000) { whileOpen { g.scheduler.postEvent(AlertAck.of(later)) }.also { postedLater = it } != null }
        assertEquals(false, postedLater)
        assertNull(nm.getNotification(Notifier.ALERT_TAG, Notifier.alertId(AlertAck.of(later))))
        assertTrue(nm.allNotifications.isEmpty())
    }
}

/**
 * Basil from the bundled care data (frost-tender, 10 degC minimum) in a pot outside: a cold night below its minimum
 * and a frost each give one cold card on Today and a notification, in both voices.
 */
@RunWith(RobolectricTestRunner::class)
class TenderColdAlertTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    /** Basil outside with [tMin] forecast tonight and tomorrow night; returns its one event. */
    private fun basilAt(tMin: Double): Pair<Graph, AlertEvent> {
        val (g, db) = fixedClockGraph()
        val placeId = runBlocking { g.garden.savePlace(BALCONY.copy(id = 0)) }
        val today = g.garden.today()
        val fetched = PLANT_TEST_NOW.toEpochMilliseconds()
        val days = (0..1).map { n ->
            WeatherDayEntity(placeId, LocalDate.fromEpochDays(today.toEpochDays() + n).toString(), tMin, tMin + 8, 0.0, provider = "OPEN_METEO", fetchedAtEpochMs = fetched)
        }
        runBlocking { db.dao().upsertWeather(days) }
        runBlocking {
            g.garden.savePlant(
                PlantEntity(
                    name = "Basil", placeId = placeId, speciesSlug = "ocimum-basilicum", archetypeId = "outdoor-leafy-herb",
                    canopyWidthCm = 20.0, potVolumeL = 2.0, acquiredOn = "2026-01-01",
                ),
            )
        }
        return g to runBlocking { g.garden.alertEvents() }.single()
    }

    /** The notification's title and body for [e] in the plain voice and in Boris's. */
    private fun notification(e: AlertEvent, today: LocalDate, boris: Boolean): Pair<String, String> {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier.createChannels(app)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        assertTrue(Notifier.postAlert(app, e, today, boris))
        val n = nm.getNotification(Notifier.ALERT_TAG, Notifier.alertId(AlertAck.of(e)))
        return n.extras.getCharSequence(Notification.EXTRA_TITLE).toString() to n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    }

    private fun showsCard(g: Graph, e: AlertEvent) {
        val text = Texts.eventCard(compose.activity, e, g.garden.today())
        compose.setContent { OvergrownTheme { TodayScreen(onPlant = {}, onAdd = {}, onPlaces = {}, onSettings = {}) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun belowItsMinimumBasilGetsAColdCardAndNotification() {
        val (g, e) = basilAt(8.0)
        val today = g.garden.today()
        assertEquals(AlertKind.COLD, e.kind)
        assertEquals(AlertType.COLD, e.severity)
        assertEquals("Cold nights Tue-Wed. Bring Basil indoors or cover for the night.", Texts.eventCard(app, e, today))
        showsCard(g, e)
        assertEquals("Cold Tue-Wed: Basil" to "Bring Basil indoors or cover for the night.", notification(e, today, boris = false))
        assertEquals("Cold Tue-Wed: Basil" to "Bring Basil indoors or cover for the night. Tender. Not built for this.", notification(e, today, boris = true))
    }

    @Test
    fun aFrostGivesBasilAFrostCardAndNotification() {
        val (g, e) = basilAt(-1.0)
        val today = g.garden.today()
        assertEquals(AlertType.FROST, e.severity)
        assertEquals("Frost Tue-Wed. Bring Basil indoors or cover for the night.", Texts.eventCard(app, e, today))
        showsCard(g, e)
        assertEquals("Frost Tue-Wed: Basil" to "Bring Basil indoors or cover for the night.", notification(e, today, boris = false))
        assertEquals("Frost Tue-Wed: Basil" to "Bring Basil indoors or cover for the night. Tender. Not built for this.", notification(e, today, boris = true))
    }
}

/** Card and notification words, from a made-up event. */
@RunWith(RobolectricTestRunner::class)
class AlertWordsTest {
    private val c = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun frostTitleStartsWithTheEventAndNamesEveryPlant() {
        // Seen from Monday 23 Nov; TODAY is the Tuesday after.
        val frost = event(AlertKind.COLD, AlertType.ROOT_FREEZE_CAUTION, 23, 25, "Boris")
        assertEquals("Frost Mon-Wed: Boris", Texts.eventTitle(c, frost, LocalDate(2026, 11, 23)))
        val three = event(AlertKind.COLD, AlertType.ROOT_FREEZE_CAUTION, 25, 25, "Boris", "Fern", "Ivy")
        assertEquals("Frost Wed: Boris, Fern and Ivy", Texts.eventTitle(c, three, TODAY))
        assertEquals("Cold nights Wed. Move Boris, Fern and Ivy against a wall.", Texts.eventCard(c, three, TODAY))
        val strong = event(AlertKind.COLD, AlertType.ROOT_FREEZE_STRONG, 25, 27, "Boris", "Fern")
        assertEquals("Severe frost Wed-Fri. Move Boris and Fern somewhere sheltered.", Texts.eventCard(c, strong, TODAY))
    }
}

/** Golden images of the alert card, with one plant and with two, light and dark, at 1.0x and 1.3x font scale. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1600dp-xhdpi")
class TodayAlertScreenshotTest(private val dark: Boolean, private val fontScale: Float) {

    private val suffix = "${if (dark) "dark" else "light"}_${fontScale}x"

    // Any changed pixel fails, as in ThemeScreenshotTest. Built per instance, inside the Robolectric sandbox.
    private val exact = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f, imageComparator = SimpleImageComparator(maxDistance = 0f)),
    )

    private fun capture(name: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/snapshots/today_alert_${name}_$suffix.png", roborazziOptions = exact) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OvergrownTheme(dark = dark) {
                    Box(Modifier.width(400.dp).background(Overgrown.colors.ground).padding(Spacing.screen)) { Column { content() } }
                }
            }
        }

    @Test
    fun onePlant() = capture("one_plant") {
        AlertCard(event(AlertKind.COLD, AlertType.ROOT_FREEZE_CAUTION, 25, 27, "Boris"), TODAY, onDone = {}, onDismiss = {})
    }

    @Test
    fun twoPlants() = capture("two_plants") {
        AlertCard(event(AlertKind.COLD, AlertType.ROOT_FREEZE_CAUTION, 25, 27, "Boris", "Fern"), TODAY, onDone = {}, onDismiss = {})
    }

    // The card's advice is plain in both voices (D52); the Boris capture shows it stays so.
    @Test
    fun tenderFrost() = capture("tender_frost") {
        AlertCard(event(AlertKind.COLD, AlertType.FROST, 25, 26, "Basil"), TODAY, onDone = {}, onDismiss = {})
    }

    @Test
    fun tenderFrostBoris() = capture("tender_frost_boris") {
        CompositionLocalProvider(LocalBoris provides true) {
            AlertCard(event(AlertKind.COLD, AlertType.FROST, 25, 26, "Basil"), TODAY, onDone = {}, onDismiss = {})
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} fontScale={1}")
        fun params() = listOf(arrayOf<Any>(false, 1.0f), arrayOf<Any>(false, 1.3f), arrayOf<Any>(true, 1.0f), arrayOf<Any>(true, 1.3f))
    }
}

/** An event in November 2026 over [first]..[last] for the named plants. */
internal fun event(kind: AlertKind, severity: AlertType, first: Int, last: Int, vararg names: String) = AlertEvent(
    kind, LocalDate(2026, 11, first), LocalDate(2026, 11, last), severity,
    names.mapIndexed { i, n -> PlantEntity(id = i + 1L, name = n, placeId = 1, canopyWidthCm = 20.0, potVolumeL = 2.0, acquiredOn = "2026-01-01") },
)

/**
 * Outdoor plants in a new place with heat forecast for three days from [from] days ahead: one heat event naming
 * them all. Heat, not frost, because frost alerts need care data the test database does not load.
 */
internal fun heatWave(g: Graph, vararg names: String, place: String = "Balcony", from: Int = 1): List<Long> {
    val db = Garden::class.java.getDeclaredField("db").apply { isAccessible = true }.get(g.garden) as GardenDb
    val placeId = runBlocking { g.garden.savePlace(BALCONY.copy(id = 0, name = place)) }
    val today = g.garden.today()
    val fetched = Clock.System.now().toEpochMilliseconds()
    val days = (0..from + 2).map { n ->
        val hot = n >= from
        WeatherDayEntity(placeId, LocalDate.fromEpochDays(today.toEpochDays() + n).toString(), 18.0, if (hot) 36.0 else 22.0, 0.0, provider = "OPEN_METEO", fetchedAtEpochMs = fetched)
    }
    runBlocking { db.dao().upsertWeather(days) }
    return names.map { plant(g, it, placeId) }
}
