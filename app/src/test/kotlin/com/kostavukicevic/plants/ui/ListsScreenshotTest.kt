// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.kostavukicevic.plants.App
import com.kostavukicevic.plants.Graph
import com.kostavukicevic.plants.R
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden images of the two tabs, the Places list, the top-bar menu and the snackbars, light and dark, at 1.0x and 1.3x. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class ListsScreenshotTest(private val dark: Boolean, private val fontScale: Float) {

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
        g = useTestDatabase()
    }

    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start() = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            OvergrownTheme(dark = dark) { App(null) }
        }
    }

    private fun waitFor(text: String, description: Boolean = false) =
        compose.waitUntil(5_000) { compose.onAllNodes(if (description) hasContentDescription(text) else hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    // Screen capture, not onRoot(): the menu and the snackbar live in their own windows or layers.
    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/lists_${name}_$suffix.png", roborazziOptions = exact)
    }

    private fun openPlants() = compose.onNodeWithText(s(R.string.plants_title)).performClick()

    private fun openPlaces() {
        compose.onNodeWithContentDescription(s(R.string.top_menu)).performClick()
        compose.onNodeWithText(s(R.string.places_title)).performClick()
    }

    private fun threePlants() {
        val hall = place(g, "Hall")
        val kitchen = place(g, "Kitchen window")
        plant(g, "Boris", hall)
        plant(g, "Fern", hall)
        plant(g, "Pothos", kitchen)
    }

    @Test
    fun today() {
        start()
        waitFor(s(R.string.today_empty))
        capture("today")
    }

    @Test
    fun todayMenu() {
        start()
        waitFor(s(R.string.today_empty))
        compose.onNodeWithContentDescription(s(R.string.top_menu)).performClick()
        capture("today_menu")
    }

    @Test
    fun plantsEmpty() {
        start()
        openPlants()
        waitFor(s(R.string.plants_empty))
        capture("plants_empty")
    }

    @Test
    fun plants() {
        threePlants()
        start()
        openPlants()
        waitFor(s(R.string.delete_named, "Pothos"), description = true)
        capture("plants")
    }

    @Test
    fun plantDeletedWithUndo() {
        threePlants()
        start()
        openPlants()
        waitFor(s(R.string.delete_named, "Fern"), description = true)
        compose.onNodeWithContentDescription(s(R.string.delete_named, "Fern")).performClick()
        waitFor(s(R.string.deleted, "Fern"))
        capture("undo_snackbar")
    }

    @Test
    fun placesEmpty() {
        start()
        openPlaces()
        waitFor(s(R.string.places_empty))
        capture("places_empty")
    }

    @Test
    fun places() {
        place(g, "Hall")
        place(g, "Kitchen window")
        start()
        openPlaces()
        waitFor(s(R.string.delete_named, "Kitchen window"), description = true)
        capture("places")
    }

    @Test
    fun placeInUse() {
        threePlants()
        start()
        openPlaces()
        waitFor(s(R.string.delete_named, "Hall"), description = true)
        compose.onNodeWithContentDescription(s(R.string.delete_named, "Hall")).performClick()
        waitFor(s(R.string.place_in_use, "Hall", "Boris, Fern"))
        capture("place_in_use")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} fontScale={1}")
        fun params() = listOf(arrayOf<Any>(false, 1.0f), arrayOf<Any>(false, 1.3f), arrayOf<Any>(true, 1.0f), arrayOf<Any>(true, 1.3f))
    }
}
