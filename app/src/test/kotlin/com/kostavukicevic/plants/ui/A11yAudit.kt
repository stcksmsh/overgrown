// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue

/** Material's minimum touch target, also DESIGN section 4. */
private const val MIN_TOUCH_DP = 48f

/** Rounding of px to dp and of text bounds; anything past it is a real shortfall. */
private const val SLACK_PX = 1f

private fun SemanticsNode.label(): String = buildList {
    config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
    config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
}.joinToString(" ").ifEmpty { "unnamed ${config.getOrNull(SemanticsProperties.Role)}" }

/**
 * Fails, naming [screen], if anything that can be tapped, in any window, takes less than 48 x 48 dp. The size is the
 * clickable's own bounds, so padding before the click modifier does not count, except the space Material's
 * minimumInteractiveComponentSize reserves around a smaller button so that its touch area can grow into it. [materialExceptions] names parts drawn by Material
 * itself that the app cannot size, each recorded in docs/DECISIONS.md.
 */
internal fun ComposeTestRule.assertTouchTargets(screen: String, materialExceptions: Set<String> = emptySet()) {
    waitForIdle()
    val clickables = onAllNodes(hasClickAction()).fetchSemanticsNodes()
    assertTrue("$screen: nothing to tap was found", clickables.isNotEmpty())
    val small = clickables.filter { it.label() !in materialExceptions }.mapNotNull { n ->
        val density = n.layoutInfo.density.density
        // The node's own size is where its click modifier sits, inside any padding before it. Only Material's
        // minimumInteractiveComponentSize widens the touch area past that, to the 48 dp it reserves around the node.
        val reserved = n.layoutInfo.getModifierInfo().any { "MinimumInteractive" in it.modifier::class.java.name }
        val w = (if (reserved) n.layoutInfo.width else n.size.width) / density
        val h = (if (reserved) n.layoutInfo.height else n.size.height) / density
        if (w + SLACK_PX / density >= MIN_TOUCH_DP && h + SLACK_PX / density >= MIN_TOUCH_DP) null else "\"${n.label()}\" %.0f x %.0f dp".format(w, h)
    }
    assertTrue("$screen: ${small.size} touch target(s) under 48 dp: ${small.joinToString()}", small.isEmpty())
}

private fun SemanticsNode.isAncestorOf(other: SemanticsNode): Boolean {
    var p = other.parent
    while (p != null) {
        if (p.id == id) return true
        p = p.parent
    }
    return false
}

private fun SemanticsNode.top(): SemanticsNode = parent?.top() ?: this

private fun Rect.overlaps(o: Rect): Boolean =
    minOf(right, o.right) - maxOf(left, o.left) > SLACK_PX && minOf(bottom, o.bottom) - maxOf(top, o.top) > SLACK_PX

/**
 * Fails, naming [screen], if any text in any window is cut off: wider or taller than the room its layout gave it,
 * ellipsized, broken mid-word, drawn partly outside a parent, or lying on top of other text. Run in a window tall enough that nothing
 * scrolls, so text cut by a parent is a fault and not a page scrolled part way.
 */
internal fun ComposeTestRule.assertNoClippedText(screen: String) {
    waitForIdle()
    val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        .filter { it.config.getOrNull(SemanticsProperties.EditableText) == null && it.size.width > 0 && it.size.height > 0 }
    assertTrue("$screen: no text was found", texts.isNotEmpty())
    val faults = mutableListOf<String>()
    for (n in texts) {
        val layouts = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        for (l in layouts) {
            // Line widths, not hasVisualOverflow or line ends: both are measured against the paragraph's wrap width,
            // which is wider than a short line's own box, so they flag centred or short text that fits.
            val wide = (0 until l.lineCount).any { l.getLineRight(it) - l.getLineLeft(it) > l.size.width + SLACK_PX }
            if (wide || l.didOverflowHeight || l.multiParagraph.didExceedMaxLines) faults += "\"${n.label()}\" overflows its layout"
            if ((0 until l.lineCount).any { l.isLineEllipsized(it) }) faults += "\"${n.label()}\" is ellipsized"
            // A box too narrow for its longest word breaks the word across lines, which reads as cut text.
            if (l.breaksAWord()) faults += "\"${n.label()}\" breaks a word across lines"
        }
        val shown = n.boundsInRoot
        if (shown.width + SLACK_PX < n.size.width || shown.height + SLACK_PX < n.size.height) {
            faults += "\"${n.label()}\" shows %.0f x %.0f of %d x %d px".format(shown.width, shown.height, n.size.width, n.size.height)
        }
    }
    for ((i, a) in texts.withIndex()) {
        for (b in texts.drop(i + 1)) {
            if (a.top().id != b.top().id || a.isAncestorOf(b) || b.isAncestorOf(a)) continue
            if (a.boundsInRoot.overlaps(b.boundsInRoot)) faults += "\"${a.label()}\" overlaps \"${b.label()}\""
        }
    }
    assertTrue("$screen: ${faults.size} text fault(s): ${faults.joinToString("; ")}", faults.isEmpty())
}

/**
 * Fails, naming [screen], if a chosen radio button (a wizard tile or a dialog answer) has no check mark: its moss fill
 * is under 3:1 on a surface, so the fill alone would show the choice by colour only (ContrastTest).
 */
internal fun ComposeTestRule.assertSelectionsMarked(screen: String) {
    waitForIdle()
    val chosen = onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
    ).fetchSemanticsNodes()
    assertTrue("$screen: nothing chosen was found", chosen.isNotEmpty())
    val marks = onAllNodes(hasTestTag(SELECTED_MARK), useUnmergedTree = true).fetchSemanticsNodes()
    val bare = chosen.filter { n -> marks.none { n.isAncestorOf(it) } }.map { "\"${it.label()}\"" }
    assertTrue("$screen: ${bare.size} choice(s) shown by colour alone: ${bare.joinToString()}", bare.isEmpty())
}

/** Fails, naming [screen], unless each of [titles] is shown as a heading, which TalkBack can jump to. */
internal fun ComposeTestRule.assertHeadings(screen: String, vararg titles: String) {
    waitForIdle()
    val headings = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.label() }
    val missing = titles.filter { t -> headings.none { it == t } }
    assertTrue("$screen: not a heading: ${missing.joinToString()}; headings: ${headings.joinToString()}", missing.isEmpty())
}

/**
 * Shows [content] at [fontScale] as a phone does. Up to 1.3x, the largest step before Android 14, sp scale linearly.
 * Larger steps exist only from Android 14, which scales large text less than small text; the platform's converter
 * for that step is used, so a 44 sp title is measured at the size a phone draws it (docs/DECISIONS.md D62).
 */
@Composable
internal fun FontScaled(fontScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scaled = if (fontScale <= 1.3f) {
        Density(density.density, fontScale)
    } else {
        val context = LocalContext.current
        val config = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
        Density(context.createConfigurationContext(config))
    }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}
