// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.R
import kotlin.math.roundToInt

/** The three PotGauge sizes from DESIGN.md section 5. */
enum class PotGaugeSize(val dp: Dp) { Small(40.dp), Medium(44.dp), Large(84.dp) }

/** Share of water left as a whole percent, clamped to 0..100. Not a number counts as empty. */
fun waterLeftPercent(waterLeft: Float): Int = if (waterLeft.isNaN()) 0 else (waterLeft.coerceIn(0f, 1f) * 100).roundToInt()

// Pot outline as fractions of the gauge's side: a trapezoid wider at the top, with a rim line below the top edge.
private const val TOP_Y = 0.12f
private const val RIM_Y = 0.28f
private const val BASE_Y = 0.9f
private const val TOP_INSET = 0.1f
private const val BASE_INSET = 0.27f
private const val STROKE = 0.055f

/**
 * Trapezoid pot filled from the bottom to the share of water left.
 * On a state block it draws in that block's text colour with a white empty part; on white-text blocks
 * (brick, moss) the empty part is left transparent instead, since white would match the fill (owner's
 * choice, docs/DECISIONS.md D32). Otherwise ink outline and gauge fill.
 */
@Composable
fun PotGauge(waterLeft: Float, size: PotGaugeSize, modifier: Modifier = Modifier, block: StateBlock? = null) {
    val percent = waterLeftPercent(waterLeft)
    val description = stringResource(R.string.pot_gauge_description, percent)
    val colors = Overgrown.colors
    val outline = block?.text ?: colors.ink
    val fill = block?.text ?: colors.gaugeFill
    val empty = if (block != null && block.text != GaugeEmptyOnBlock) GaugeEmptyOnBlock else null
    Canvas(modifier.size(size.dp).semantics { contentDescription = description }) {
        val s = this.size.width
        val pot = Path().apply {
            moveTo(s * TOP_INSET, s * TOP_Y)
            lineTo(s * (1 - TOP_INSET), s * TOP_Y)
            lineTo(s * (1 - BASE_INSET), s * BASE_Y)
            lineTo(s * BASE_INSET, s * BASE_Y)
            close()
        }
        if (empty != null) drawPath(pot, empty)
        // Water fills the body below the rim line, by height.
        val level = s * (BASE_Y - (BASE_Y - RIM_Y) * percent / 100f)
        clipPath(pot) { drawRect(fill, topLeft = Offset(0f, level), size = this.size.copy(height = s * BASE_Y - level)) }
        val stroke = s * STROKE
        drawPath(pot, outline, style = Stroke(width = stroke, join = StrokeJoin.Round))
        // The rim line meets the slanted sides at RIM_Y.
        val rimInset = s * (TOP_INSET + (BASE_INSET - TOP_INSET) * (RIM_Y - TOP_Y) / (BASE_Y - TOP_Y))
        drawLine(outline, Offset(rimInset, s * RIM_Y), Offset(s - rimInset, s * RIM_Y), strokeWidth = stroke)
    }
}
