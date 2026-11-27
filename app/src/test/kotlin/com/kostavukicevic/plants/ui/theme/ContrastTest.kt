// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 2 contrast of every text/fill, gauge and control-boundary pair the theme and its Material mapping use, in both themes. */
class ContrastTest {

    private fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val (lo, hi) = listOf(luminance(a), luminance(b)).sorted()
        return (hi + 0.05) / (lo + 0.05)
    }

    private data class Check(val name: String, val fg: Color, val bg: Color, val min: Double)

    private fun pairs(theme: String, c: OvergrownColors, dark: Boolean): List<Check> {
        val m = materialScheme(c, dark)
        val text = buildList {
            add(Check("ink on ground", c.ink, c.ground, TEXT))
            add(Check("ink on surface", c.ink, c.surface, TEXT))
            add(Check("muted on ground", c.muted, c.ground, TEXT))
            add(Check("muted on surface", c.muted, c.surface, TEXT))
            add(Check("on-ink on ink", c.onInk, c.ink, TEXT))
            add(Check("error text on ground", c.errorText, c.ground, TEXT))
            add(Check("error text on surface", c.errorText, c.surface, TEXT))
            for (b in StateBlock.entries) add(Check("text on ${b.name}", b.text, b.fill, TEXT))
            add(Check("block button text on its fill", BlockButton.text, BlockButton.fill, TEXT))
        }
        val graphics = buildList {
            add(Check("gauge outline (ink) on surface", c.ink, c.surface, GRAPHIC))
            add(Check("gauge outline (ink) on ground", c.ink, c.ground, GRAPHIC))
            add(Check("gauge fill on surface", c.gaugeFill, c.surface, GRAPHIC))
            for (b in StateBlock.entries) add(Check("gauge outline on ${b.name}", b.text, b.fill, GRAPHIC))
            add(Check("moss on moss tint", StateBlock.Moss.fill, Tints.moss, GRAPHIC))
            add(Check("brick on brick tint", StateBlock.Brick.fill, Tints.brick, GRAPHIC))
            // Material draws a text field's and an off switch's boundary in outline: the only sign the control is there.
            add(Check("Material outline (text field, off switch) on ground", m.outline, c.ground, GRAPHIC))
            add(Check("Material outline (text field, off switch) on surface", m.outline, c.surface, GRAPHIC))
            // A selected chip or wizard tile shows its state by its moss fill alone.
            add(Check("selected chip and tile fill on ground", m.secondaryContainer, c.ground, GRAPHIC))
            add(Check("on switch track (primary) on ground", m.primary, c.ground, GRAPHIC))
        }
        return (text + graphics).map { it.copy(name = "$theme: ${it.name}") }
    }

    @Test
    fun ratioMatchesWcagReferenceValues() {
        assertEquals(21.0, ratio(Color.Black, Color.White), 1e-9)
        assertEquals(1.0, ratio(Color.White, Color.White), 1e-9)
    }

    @Test
    fun everyPairMeetsItsThreshold() {
        val all = pairs("light", LightColors, dark = false) + pairs("dark", DarkColors, dark = true)
        val failures = all.filter { ratio(it.fg, it.bg) < it.min }
            .map { "${it.name}: %.2f < %.1f".format(ratio(it.fg, it.bg), it.min) }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private companion object {
        /** WCAG 1.4.3 normal text. Every style is held to it, including the large ones. */
        const val TEXT = 4.5

        /** WCAG 1.4.11 graphical objects (gauge outlines, chip glyphs) and large text. */
        const val GRAPHIC = 3.0
    }
}
