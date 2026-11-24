// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden images of the token sheet and PotGauge, light and dark, at 1.0x and 1.3x font scale. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1600dp-xhdpi")
class ThemeScreenshotTest(private val dark: Boolean, private val fontScale: Float) {

    private val suffix = "${if (dark) "dark" else "light"}_${fontScale}x"

    // Any changed pixel fails: no share of changed pixels and no per-pixel colour distance is tolerated.
    // Built per instance, inside the Robolectric sandbox, which Roborazzi's options need.
    private val exact = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f, imageComparator = SimpleImageComparator(maxDistance = 0f)),
    )

    private fun capture(name: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/snapshots/${name}_$suffix.png", roborazziOptions = exact) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OvergrownTheme(dark = dark) {
                    Box(Modifier.width(SHEET_WIDTH).background(Overgrown.colors.ground).padding(Spacing.screen)) { content() }
                }
            }
        }

    @Test
    fun tokenSheet() = capture("tokens") {
        val c = Overgrown.colors
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            Swatch("ground", c.ground, c.ink)
            Swatch("surface", c.surface, c.ink)
            Swatch("ink", c.ink, c.onInk)
            Swatch("on-ink", c.onInk, c.ink)
            Swatch("muted text on surface", c.surface, c.muted)
            Swatch("outline", c.outline, c.ink)
            Swatch("gauge fill", c.gaugeFill, c.surface)
            Swatch("error text on surface", c.surface, c.errorText)
            for (b in StateBlock.entries) Swatch("${b.name} block", b.fill, b.text)
            Swatch("moss tint", Tints.moss, StateBlock.Moss.fill)
            Swatch("brick tint", Tints.brick, StateBlock.Brick.fill)
            TypeLine("display", Type.display)
            TypeLine("title", Type.title)
            TypeLine("card title", Type.cardTitle)
            TypeLine("body: water the pothos now", Type.body)
            TypeLine("label: Watered", Type.label)
            TypeLine("secondary: Fine. Check Thu.", Type.secondary)
        }
    }

    @Test
    fun potGauges() = capture("pot_gauge") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            GaugeCard("surface", Overgrown.colors.surface, Overgrown.colors.ink, null)
            for (b in StateBlock.entries) GaugeCard(b.name, b.fill, b.text, b)
        }
    }

    @Composable
    private fun Swatch(name: String, fill: Color, text: Color) {
        Box(
            Modifier.fillMaxWidth().background(fill, RoundedCornerShape(Radius.button))
                .padding(horizontal = Spacing.cardPadding, vertical = Spacing.grid * 3),
        ) { Text(name, color = text, style = Type.label) }
    }

    @Composable
    private fun TypeLine(name: String, style: TextStyle) = Text(name, color = Overgrown.colors.ink, style = style)

    @Composable
    private fun GaugeCard(name: String, fill: Color, text: Color, block: StateBlock?) {
        Column(
            Modifier.fillMaxWidth().background(fill, RoundedCornerShape(Radius.card)).padding(Spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.grid * 2),
        ) {
            Text(name, color = text, style = Type.label)
            for (size in PotGaugeSize.entries) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 4), verticalAlignment = Alignment.CenterVertically) {
                    for (level in LEVELS) PotGauge(level, size, block = block)
                }
            }
        }
    }

    companion object {
        private val SHEET_WIDTH = 360.dp
        private val LEVELS = listOf(0f, 0.5f, 1f)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} fontScale={1}")
        fun params() = listOf(arrayOf<Any>(false, 1.0f), arrayOf<Any>(false, 1.3f), arrayOf<Any>(true, 1.0f), arrayOf<Any>(true, 1.3f))
    }
}
