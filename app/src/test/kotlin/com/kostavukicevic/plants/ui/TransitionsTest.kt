// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.kostavukicevic.plants.App
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.data.TransitionChoice
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
import org.robolectric.ParameterizedRobolectricTestRunner

/** Screen transitions (D44), once per variant: push and pop move, back can be dragged, and nothing pushes twice. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class TransitionsTest(private val choice: TransitionChoice) {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    private lateinit var g: Graph

    @Before
    fun setUp() {
        g = useTestDatabase()
        plant(g, "Boris", place(g, "Hall"))
        runBlocking { g.settings.setTransition(choice) }
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
        compose.mainClock.advanceTimeBy(Motion.MEDIUM2 * 2L)
        assertEquals("one back reaches Today", true, todayShown && !plantShown)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params() = TransitionChoice.entries.map { arrayOf<Any>(it) }
    }
}
