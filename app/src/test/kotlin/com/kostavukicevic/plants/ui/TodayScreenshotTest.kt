// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.kostavukicevic.plants.LocalBoris
import com.kostavukicevic.plants.engine.Action
import com.kostavukicevic.plants.engine.PromptReason
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import com.kostavukicevic.plants.ui.theme.Spacing
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Golden images of Today's cards, tiles and whole screen, light and dark, at 1.0x and 1.3x font scale, in both voices. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1600dp-xhdpi")
class TodayScreenshotTest(private val dark: Boolean, private val fontScale: Float) {

    private val suffix = "${if (dark) "dark" else "light"}_${fontScale}x"

    // Any changed pixel fails, as in ThemeScreenshotTest. Built per instance, inside the Robolectric sandbox.
    private val exact = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f, imageComparator = SimpleImageComparator(maxDistance = 0f)),
    )

    private var zone: TimeZone? = null

    // The forecast time is shown in the device's zone; pin it so the captures match on every machine.
    @Before
    fun pinZone() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun capture(name: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/snapshots/today_${name}_$suffix.png", roborazziOptions = exact) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OvergrownTheme(dark = dark) {
                    Box(Modifier.width(SCREEN_WIDTH).background(Overgrown.colors.ground).padding(Spacing.screen)) { content() }
                }
            }
        }

    @Composable
    private fun TwoTiles(left: String, right: String) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            RestingTile(fixture(left, next = LocalDate(2026, 11, 26), depletion = 0.3), TODAY, Modifier.weight(1f).fillMaxHeight()) {}
            RestingTile(fixture(right, next = LocalDate(2026, 12, 6), depletion = 0.55, archetype = "indoor-succulent"), TODAY, Modifier.weight(1f).fillMaxHeight()) {}
        }
    }

    @Test
    fun waterCard() = capture("water_card") {
        ActionCard(fixture("Pothos", Action.WaterNow(680, PromptReason.DRY_BY_MODEL), depletion = 0.8), onOpen = {}, onWatered = {}, onAnswer = {})
    }

    @Test
    fun soilCard() = capture("soil_card") {
        ActionCard(fixture("Snake plant", Action.CheckSoil(PromptReason.UNCERTAIN), depletion = 0.6, archetype = "indoor-succulent"), onOpen = {}, onWatered = {}, onAnswer = {})
    }

    @Test
    fun twoTiles() = capture("tiles") { TwoTiles("Boris", "Aloe") }

    @Test
    fun longNameTile() = capture("long_name_tile") { TwoTiles("Monstera deliciosa variegata", "Aloe") }

    @Test
    fun wholeToday() = capture("full") {
        TodayContent(fullToday(), TODAY, onPlant = {}, onAdd = {}, onWatered = {}, onAnswer = { _, _ -> }) { FixedWeather() }
    }

    @Test
    fun gettingTheForecast() = capture("weather_loading") { FixedWeather(refreshing = true) }

    @Test
    fun emptyToday() = capture("empty") {
        TodayContent(emptyList(), TODAY, onPlant = {}, onAdd = {}, onWatered = {}, onAnswer = { _, _ -> }) { FixedWeather() }
    }

    // The same two screens in Boris's voice; the captures above are the plain voice.
    @Test
    fun wholeTodayInBorisVoice() = capture("full_boris") {
        CompositionLocalProvider(LocalBoris provides true) {
            TodayContent(fullToday(), TODAY, onPlant = {}, onAdd = {}, onWatered = {}, onAnswer = { _, _ -> }) { FixedWeather() }
        }
    }

    @Test
    fun emptyTodayInBorisVoice() = capture("empty_boris") {
        CompositionLocalProvider(LocalBoris provides true) {
            TodayContent(emptyList(), TODAY, onPlant = {}, onAdd = {}, onWatered = {}, onAnswer = { _, _ -> }) { FixedWeather() }
        }
    }

    companion object {
        private val SCREEN_WIDTH = 400.dp

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} fontScale={1}")
        fun params() = listOf(arrayOf<Any>(false, 1.0f), arrayOf<Any>(false, 1.3f), arrayOf<Any>(true, 1.0f), arrayOf<Any>(true, 1.3f))
    }
}
