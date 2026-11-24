// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kostavukicevic.plants.R

/** The six care-profile glyphs of DESIGN.md section 5. */
enum class GlyphKind(@DrawableRes val drawable: Int) {
    Conifer(R.drawable.glyph_conifer),
    Succulent(R.drawable.glyph_succulent),
    Leafy(R.drawable.glyph_leafy),
    Fern(R.drawable.glyph_fern),
    Orchid(R.drawable.glyph_orchid),
    Herb(R.drawable.glyph_herb),
}

/**
 * The glyph for a care archetype. Null or an unknown id falls back to [GlyphKind.Leafy]. No archetype maps to
 * [GlyphKind.Herb] yet; it exists for the add-plant picker.
 */
fun glyphFor(archetypeId: String?): GlyphKind = when (archetypeId) {
    "hardy-potted-conifer" -> GlyphKind.Conifer
    "indoor-succulent" -> GlyphKind.Succulent
    "indoor-tropical-foliage" -> GlyphKind.Leafy
    "indoor-moisture-loving" -> GlyphKind.Fern
    "indoor-epiphytic-orchid" -> GlyphKind.Orchid
    else -> GlyphKind.Leafy
}

/** Draws [kind] in one flat [color], which the caller takes from the theme. */
@Composable
fun Glyph(kind: GlyphKind, color: Color, contentDescription: String?, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Icon(painterResource(kind.drawable), contentDescription, modifier.then(Modifier.size(size)), tint = color)
}
