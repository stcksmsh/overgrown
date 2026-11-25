// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.kostavukicevic.plants.App
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.ui.theme.Motion
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Screen transitions (D44, D45): push and pop move, back can be dragged, and nothing pushes twice. */
@RunWith(RobolectricTestRunner::class)
class TransitionsTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private lateinit var g: Graph
    private var boris = 0L

    @Before
    fun setUp() {
        g = useTestDatabase()
        boris = plant(g, "Boris", place(g, "Hall"))
    }

    private fun s(id: Int) = compose.activity.getString(id)
    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    /** Today is drawn while its title is; the plant screen while its Edit is. */
    private val todayShown get() = count(s(R.string.today_title)) > 0
    private val plantShown get() = count(s(R.string.edit)) > 0

    private fun startOnToday() {
        compose.setContent { OvergrownTheme { App(null) } }
        compose.waitUntil(5_000) { count("Boris") > 0 }
        compose.waitForIdle()
    }

    /**
     * Taps Boris on Today with the clock held, then runs frames until the plant screen has read its plant from the
     * database (a frame or two). Returns the time that took.
     */
    private fun openBoris(): Long {
        compose.mainClock.autoAdvance = false
        val start = compose.mainClock.currentTime
        compose.onAllNodes(hasText("Boris")).onFirst().performClick()
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); plantShown }
        return compose.mainClock.currentTime - start
    }

    @Test
    fun aPushShowsBothScreensHalfwayAndOnlyThePlantAtTheEnd() {
        startOnToday()
        val loading = openBoris()
        assertTrue("the plant took $loading ms to load", loading < Motion.MEDIUM2 / 2)
        compose.mainClock.advanceTimeBy(Motion.MEDIUM2 / 2L - loading)
        assertTrue("halfway: today=$todayShown plant=$plantShown", todayShown && plantShown)

        compose.mainClock.advanceTimeBy(Motion.MEDIUM2.toLong())
        assertTrue("after: today=$todayShown plant=$plantShown", !todayShown && plantShown)
    }

    @Test
    fun aCancelledBackGestureStaysAndACompletedOneReturns() {
        startOnToday()
        openBoris()
        compose.mainClock.advanceTimeBy(Motion.MEDIUM2 * 2L)
        val back = compose.activity.onBackPressedDispatcher

        compose.runOnUiThread {
            back.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(200f, 500f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("dragged: today=$todayShown plant=$plantShown", todayShown && plantShown)
        compose.runOnUiThread { back.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(Motion.MEDIUM2 * 2L)
        assertTrue("cancelled: today=$todayShown plant=$plantShown", !todayShown && plantShown)

        compose.runOnUiThread {
            back.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(200f, 500f, 0.5f, BackEventCompat.EDGE_LEFT))
            back.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(Motion.MEDIUM2 * 2L)
        assertTrue("completed: today=$todayShown plant=$plantShown", todayShown && !plantShown)
    }

    @Test
    fun withAnimationsRemovedAPushIsInstant() {
        Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        startOnToday()
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasText("Boris")).onFirst().performClick()
        // Nothing recomposes without a frame; the first one after the tap already shows the plant screen alone.
        compose.mainClock.advanceTimeByFrame()
        assertTrue("Today is still drawn", !todayShown)
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); plantShown }
        assertTrue(!todayShown)
    }

    @Test
    fun twoTapsInOneFramePushOnce() {
        startOnToday()
        compose.mainClock.autoAdvance = false
        val boris = compose.onAllNodes(hasText("Boris")).onFirst()
        boris.performClick()
        boris.performClick()
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); plantShown }
        compose.mainClock.advanceTimeBy(Motion.MEDIUM2 * 2L)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        // The back handler's coroutine can start a few frames late on a busy machine, so run frames until Today is
        // back or give up; a second plant screen underneath would keep it from coming back at all.
        runCatching { compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); todayShown && !plantShown } }
        assertEquals("one back reaches Today", true, todayShown && !plantShown)
    }

    private fun tab(id: Int) = compose.onNode(isTab and hasText(s(id))).performClick()

    /** Where each "Boris" text is drawn, top left in the root. */
    private fun borisAt() = compose.onAllNodes(hasText("Boris"), useUnmergedTree = true).fetchSemanticsNodes().map { it.positionInRoot }.toSet()

    /**
     * Where the one Boris on screen rests once the screen's data has loaded (it loads off the main thread): the same
     * place over [STILL_POLLS] polls in a row, each after the UI went idle.
     */
    private fun borisAtRest(): Offset {
        var last = emptySet<Offset>()
        var still = 0
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            val now = borisAt()
            still = if (now.size == 1 && now == last) still + 1 else 0
            last = now
            still >= STILL_POLLS
        }
        return last.single()
    }

    @Test
    fun switchingTabsCrossfadesWithoutGrowingTheCardIntoTheRow() {
        startOnToday()
        val onToday = borisAtRest()
        tab(R.string.plants_title)
        val onPlants = borisAtRest()
        tab(R.string.today_title)
        assertEquals(onToday, borisAtRest())

        compose.mainClock.autoAdvance = false
        tab(R.string.plants_title)
        val frames = List(Motion.SHORT4 / 16) { compose.mainClock.advanceTimeByFrame(); borisAt() }
        // Both screens are drawn at once, and Today's Boris holds still while Plants fades in over it; a shared
        // element would carry it toward the Plants row.
        assertTrue("both drawn: $frames", frames.any { it.size == 2 })
        assertTrue("Today's Boris held: $frames", frames.all { it.size < 2 || onToday in it })
        compose.mainClock.advanceTimeBy(Motion.MEDIUM2.toLong())
        assertEquals(setOf(onPlants), borisAt())
    }

    @Test
    fun backDuringThePopFromTheWizardLeavesTheScreenBelowToo() {
        startOnToday()
        openBoris()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // Deleted elsewhere while his page is open: the wizard opens on "this plant is gone", whose back handler is always on.
        runBlocking { g.garden.deletePlant(boris) }
        compose.onNodeWithText(s(R.string.edit)).performClick()
        val gone = s(R.string.wizard_plant_missing)
        compose.waitUntil(5_000) { count(gone) > 0 }

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(s(R.string.back)).performClick()
        compose.mainClock.advanceTimeBy(Motion.MEDIUM2 / 2L)
        assertTrue("the wizard is still leaving", count(gone) > 0)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(Motion.MEDIUM2 * 3L)
        assertTrue("back reached Today", todayShown)
    }

    @Test
    fun removingAnimationsWhileAwayIsSeenOnReturn() {
        startOnToday()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { count("Boris") > 0 }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasText("Boris")).onFirst().performClick()
        compose.mainClock.advanceTimeByFrame()
        assertTrue("Today is still drawn", !todayShown)
    }

    @Test
    fun turningAnimationsOffOrOnWhileAwayKeepsTheTopScreensState() {
        startOnToday()
        // The menu's open state is a plain remember: it survives only if the screen stays in the same tree.
        compose.onNodeWithContentDescription(s(R.string.top_menu)).performClick()
        compose.waitUntil(5_000) { count(s(R.string.settings)) > 0 }
        for (scale in listOf(0f, 1f)) {
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertTrue("the menu is still open with the scale at $scale", count(s(R.string.settings)) > 0)
        }
    }

    private companion object {
        const val STILL_POLLS = 5
    }
}
