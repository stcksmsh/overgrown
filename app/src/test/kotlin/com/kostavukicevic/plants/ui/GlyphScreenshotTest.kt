// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.kostavukicevic.plants.ui.theme.Overgrown
import com.kostavukicevic.plants.ui.theme.OvergrownTheme
import com.kostavukicevic.plants.ui.theme.Radius
import com.kostavukicevic.plants.ui.theme.Spacing
import com.kostavukicevic.plants.ui.theme.StateBlock
import com.kostavukicevic.plants.ui.theme.Tints
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden images of the six glyphs at 24 and 48 dp on surface, on moss and in a moss-tint chip, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1600dp-xhdpi")
class GlyphScreenshotTest(private val dark: Boolean) {

    // Any changed pixel fails. Built per instance, inside the Robolectric sandbox, which Roborazzi's options need.
    private val exact = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f, imageComparator = SimpleImageComparator(maxDistance = 0f)),
    )

    @Test
    fun glyphs() = captureRoboImage("src/test/snapshots/glyph_${if (dark) "dark" else "light"}.png", roborazziOptions = exact) {
        OvergrownTheme(dark = dark) {
            val c = Overgrown.colors
            Column(
                Modifier.width(400.dp).background(c.ground).padding(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
            ) {
                Strip(c.surface, c.ink)
                Strip(StateBlock.Moss.fill, StateBlock.Moss.text)
                // The tint is the same in both themes, so the chip glyph uses the fixed dark ink.
                Strip(Tints.moss, StateBlock.Sky.text)
            }
        }
    }

    @Composable
    private fun Strip(fill: Color, glyph: Color) {
        Column(
            Modifier.background(fill, RoundedCornerShape(Radius.card)).padding(Spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.grid * 3),
        ) {
            for (size in listOf(24.dp, 48.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 3), verticalAlignment = Alignment.CenterVertically) {
                    for (kind in GlyphKind.entries) Box { Glyph(kind, glyph, kind.name, size = size) }
                }
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}
