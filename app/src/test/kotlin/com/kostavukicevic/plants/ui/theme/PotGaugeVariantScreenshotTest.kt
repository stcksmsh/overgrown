// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The two candidate renderings of PotGauge on white-text blocks, for the owner to choose from. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1600dp-xhdpi")
class PotGaugeVariantScreenshotTest(private val dark: Boolean) {

    private fun capture(variant: String, empty: WhiteBlockEmpty) =
        captureRoboImage("src/test/snapshots/pot_gauge_variant${variant}_${if (dark) "dark" else "light"}_1.0x.png") {
            OvergrownTheme(dark = dark) {
                Column(
                    Modifier.width(360.dp).background(Overgrown.colors.ground).padding(Spacing.screen),
                    verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
                ) {
                    for (block in listOf(StateBlock.Brick, StateBlock.Moss)) {
                        Column(
                            Modifier.fillMaxWidth().background(block.fill, RoundedCornerShape(Radius.card)).padding(Spacing.cardPadding),
                            verticalArrangement = Arrangement.spacedBy(Spacing.grid * 2),
                        ) {
                            Text("${block.name}, variant $variant", color = block.text, style = Type.label)
                            for (size in PotGaugeSize.entries) {
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.grid * 4), verticalAlignment = Alignment.CenterVertically) {
                                    for (level in listOf(0f, 0.5f, 1f)) PotGauge(level, size, Modifier, block, empty)
                                }
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun variantA() = capture("A", WhiteBlockEmpty.Transparent)

    @Test
    fun variantB() = capture("B", WhiteBlockEmpty.Translucent)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}
