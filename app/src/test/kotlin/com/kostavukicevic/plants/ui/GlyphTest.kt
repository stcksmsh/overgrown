// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GlyphTest {
    @Test
    fun everyArchetypeHasItsGlyph() {
        assertEquals(GlyphKind.Conifer, glyphFor("hardy-potted-conifer"))
        assertEquals(GlyphKind.Succulent, glyphFor("indoor-succulent"))
        assertEquals(GlyphKind.Leafy, glyphFor("indoor-tropical-foliage"))
        assertEquals(GlyphKind.Fern, glyphFor("indoor-moisture-loving"))
        assertEquals(GlyphKind.Orchid, glyphFor("indoor-epiphytic-orchid"))
        assertEquals(GlyphKind.Herb, glyphFor("outdoor-leafy-herb"))
    }

    @Test
    fun nullAndUnknownFallBackToLeafy() {
        assertEquals(GlyphKind.Leafy, glyphFor(null))
        assertEquals(GlyphKind.Leafy, glyphFor("no-such-archetype"))
    }
}
